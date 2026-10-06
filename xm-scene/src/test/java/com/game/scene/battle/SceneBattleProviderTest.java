package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.metrics.SceneBattleMetrics.RpcResult;
import com.game.scene.testing.FakeBattleLocks;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.ScenePlayer;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@code SceneBattleService} 的提供方（不经 Dubbo；scene-battle-spec §7.3「提供方」，§13.2 SceneBattleProviderTest 一行逐条）。
 * Dubbo 线程上依次：在途名额（超出回 OVERLOADED）→ 实例核对（不符 NOT_HERE）→ 解析 body（失败按方法各回各的）→ 投递逻辑线程（被拒 = 异常完成）；
 * 应答在回写线程上完成；每次调用恰好还一次名额、恰好计一次 {@code rpc{method, result}}。
 *
 * <p>逻辑线程是夹具的手动队列（{@code f.logic}）：提供方的投递只入队，{@code f.drain()} 在测试线程上跑——「有没有进逻辑线程」就是看队列。
 * 回写线程是一条真的线程（{@value #REPLY_THREAD}），所以应答一律用带超时的 {@code get} 取。
 */
class SceneBattleProviderTest {

    private static final String REPLY_THREAD = "test-battle-reply";
    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long GOLD = 100;
    private static final int INVALID_PARAMETER = 1005;
    private static final int ENTITY_NULL = 1004;
    /** 任何 protobuf 消息都解析不了的字节（截断的 varint）。 */
    private static final byte[] GARBAGE = {(byte) 0xFF, (byte) 0xFF};

    private final BattleFixture f = new BattleFixture();
    private final ExecutorService replyThread = Executors.newSingleThreadExecutor(r -> new Thread(r, REPLY_THREAD));

    @AfterEach
    void tearDown() {
        replyThread.shutdownNow();
    }

    private SceneBattleProvider provider(int maxInFlight) {
        return new SceneBattleProvider(() -> f.battle, BattleFixture.SCENE_INSTANCE, f.logic, maxInFlight, replyThread, f.battleMetrics);
    }

    // ================================================================== 第 1 步：在途名额

    /** 在途已满：直接回 OVERLOADED（一条正常应答，不是传输失败），<b>没进逻辑线程</b>、零副作用；名额还回来后照常受理。 */
    @Test
    void 在途已满_回OVERLOADED_没进逻辑线程_名额释放后照常受理() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        SceneBattleProvider provider = provider(2);
        CompletableFuture<SceneBattleReply> first = provider.confirmBattle(confirmCall(f.deadline()));
        CompletableFuture<SceneBattleReply> second = provider.confirmBattle(confirmCall(f.deadline()));
        assertThat(provider.inFlight()).isEqualTo(2);
        assertThat(f.logic.pending()).isEqualTo(2);

        CompletableFuture<SceneBattleReply> third = provider.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE));

        assertThat(third).as("过载当场回").isCompleted();
        assertThat(BattleFixture.done(third)).isEqualTo(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_OVERLOADED).build());
        assertThat(f.logic.pending()).as("过载的那条没进逻辑线程").isEqualTo(2);
        assertThat(provider.inFlight()).as("过载的那条不占名额").isEqualTo(2);
        assertThat(rpc("settlement", RpcResult.OVERLOADED)).isEqualTo(1);
        assertThat(f.gold(player)).isZero();
        assertThat(player.battle().freeze().phase()).as("前两条也还没跑").isEqualTo(Phase.PREPARING);

        f.drain();
        assertThat(get(first).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(get(second).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(provider.inFlight()).isZero();

        CompletableFuture<SceneBattleReply> fourth = provider.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE));
        f.drain();
        assertThat(get(fourth).getSettlement()).as("名额还回来后照常受理").isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(rpc("settlement", RpcResult.OVERLOADED)).isEqualTo(1);
        assertThat(rpc("settlement", RpcResult.HANDLED)).isEqualTo(1);
        assertThat(rpc("confirm", RpcResult.HANDLED)).isEqualTo(2);
    }

    // ================================================================== 第 2 步：实例核对

    /**
     * {@code target_instance_id} 与本进程不符（scene 重启过、调用方手里的目录是旧的）或没填：四个方法都回 NOT_HERE，
     * 没进逻辑线程、零副作用、名额当场归还。
     */
    @Test
    void 实例不符或为空_四个方法都回NOT_HERE_没进逻辑线程_名额已还() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        SceneBattleProvider provider = provider(4);
        SceneBattleReply notHere = SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_NOT_HERE).build();

        for (String instance : List.of("scene-instance-old", "")) {
            List<CompletableFuture<SceneBattleReply>> replies = List.of(
                    provider.prepareBattle(call(instance, f.prepareRequest(PLAYER, 8).toByteArray())),
                    provider.cancelBattlePrepare(call(instance, cancelBody())),
                    provider.confirmBattle(call(instance, confirmBody(f.deadline()))),
                    provider.applySettlement(settlementCall(instance)));
            for (CompletableFuture<SceneBattleReply> reply : replies) {
                assertThat(reply).as("实例 [%s]：当场回", instance).isCompleted();
                assertThat(BattleFixture.done(reply)).isEqualTo(notHere);
            }
        }

        assertThat(f.logic.pending()).as("都没进逻辑线程").isZero();
        assertThat(provider.inFlight()).as("名额已还").isZero();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(player.battle().freeze().phase()).as("取消 / 确认都没生效").isEqualTo(Phase.PREPARING);
        assertThat(f.gold(player)).isZero();
        for (String method : List.of("prepare", "cancel", "confirm", "settlement")) {
            assertThat(rpc(method, RpcResult.NOT_HERE)).as(method).isEqualTo(2);
            assertThat(rpcTotal(method)).as("%s 每次调用恰好计一次", method).isEqualTo(2);
        }
    }

    // ================================================================== 第 3 步：解析 body

    /**
     * body 解析失败按方法各回各的，都<b>不进逻辑线程</b>、都打一条 ERROR：备战 → HANDLED + {@code PrepareBattleResponse{1005}}；
     * 结算 → HANDLED + DISCARDED（不销账）；取消 / 确认 → HANDLED。
     */
    @Test
    void 解析失败_备战回HANDLED带1005_结算回HANDLED加DISCARDED_取消与确认回HANDLED_都没进逻辑线程_各打一条ERROR() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        SceneBattleProvider provider = provider(4);
        ch.qos.logback.classic.Logger providerLog = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SceneBattleProvider.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        providerLog.addAppender(logs);
        try {
            CompletableFuture<SceneBattleReply> prepare = provider.prepareBattle(call(BattleFixture.SCENE_INSTANCE, GARBAGE));
            CompletableFuture<SceneBattleReply> cancel = provider.cancelBattlePrepare(call(BattleFixture.SCENE_INSTANCE, GARBAGE));
            CompletableFuture<SceneBattleReply> confirm = provider.confirmBattle(call(BattleFixture.SCENE_INSTANCE, GARBAGE));
            CompletableFuture<SceneBattleReply> settlement = provider.applySettlement(call(BattleFixture.SCENE_INSTANCE, GARBAGE));

            assertThat(f.logic.pending()).as("解析失败不进逻辑线程").isZero();
            SceneBattleReply prepared = get(prepare);
            assertThat(prepared.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
            PrepareBattleResponse response = PrepareBattleResponse.parseFrom(prepared.getBody());
            assertThat(response.getErrorMessage().getId()).as("备战的业务结论在 body 里").isEqualTo(INVALID_PARAMETER);
            assertThat(response.hasSnapshot()).isFalse();
            SceneBattleReply handled = SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).build();
            assertThat(get(cancel)).isEqualTo(handled);
            assertThat(get(confirm)).isEqualTo(handled);
            assertThat(get(settlement)).isEqualTo(handled.toBuilder().setSettlement(SettlementDisposition.SETTLEMENT_DISCARDED).build());
        } finally {
            providerLog.detachAppender(logs);
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).as("四次解析失败各一条 ERROR").hasSize(4);
        assertThat(f.locks.calls()).as("零 Redis 调用：不删锁、不续锁、不销账").isEmpty();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(provider.inFlight()).isZero();
        for (String method : List.of("prepare", "cancel", "confirm", "settlement")) {
            assertThat(rpc(method, RpcResult.HANDLED)).as(method).isEqualTo(1);
            assertThat(rpcTotal(method)).as(method).isEqualTo(1);
        }
    }

    // ================================================================== 第 4 步：投递逻辑线程；应答在回写线程上完成

    /**
     * 正常路径：四个方法各自进逻辑线程走到 {@link PlayerBattleService}，应答在回写线程上完成（挂在返回 future 上的后续——Dubbo 的序列化与回写——
     * 跑在完成它的线程上），不占逻辑线程。需要异步 Redis 的分支（备战写锁）等结局回来才完成。
     */
    @Test
    void 正常路径_四个方法进逻辑线程_应答在回写线程上完成_备战等写锁结局回来才完成() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        SceneBattleProvider provider = provider(4);
        String logicThreadName = Thread.currentThread().getName();
        List<String> completedOn = new ArrayList<>();
        Function<CompletableFuture<SceneBattleReply>, CompletableFuture<Void>> watch =
                reply -> reply.thenAccept(r -> record(completedOn, Thread.currentThread().getName()));

        // 备战：写锁挂起，应答必须等它
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<SceneBattleReply> prepare = provider.prepareBattle(call(BattleFixture.SCENE_INSTANCE, f.prepareRequest(PLAYER, X).toByteArray()));
        CompletableFuture<Void> prepareSeen = watch.apply(prepare);
        assertThat(prepare).as("还没轮到逻辑线程").isNotDone();
        assertThat(player.inBattle()).isFalse();
        f.drain();
        assertThat(player.inBattle()).as("逻辑线程上已挂冻结").isTrue();
        assertThat(prepare).as("写锁结局没回来，应答不完成").isNotDone();
        assertThat(provider.inFlight()).isEqualTo(1);
        f.locks.take(Op.PREPARE_LOCK).complete();
        f.locks.release(Op.PREPARE_LOCK);
        f.drain();
        SceneBattleReply prepared = get(prepare);
        prepareSeen.get(5, TimeUnit.SECONDS);
        assertThat(prepared.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        PrepareBattleResponse response = PrepareBattleResponse.parseFrom(prepared.getBody());
        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getSnapshot().getPlayerId()).isEqualTo(PLAYER);
        assertThat(response.getTableFingerprint()).isEqualTo(f.tables.fingerprint());

        // 确认
        long deadline = f.deadline();
        CompletableFuture<SceneBattleReply> confirm = provider.confirmBattle(confirmCall(deadline));
        CompletableFuture<Void> confirmSeen = watch.apply(confirm);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        f.drain();
        assertThat(get(confirm)).isEqualTo(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).build());
        confirmSeen.get(5, TimeUnit.SECONDS);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);

        // 取消（已确认开战：被拒，仍回 HANDLED——取消不回结论）
        CompletableFuture<SceneBattleReply> cancel = provider.cancelBattlePrepare(call(BattleFixture.SCENE_INSTANCE, cancelBody()));
        CompletableFuture<Void> cancelSeen = watch.apply(cancel);
        f.drain();
        assertThat(get(cancel).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        cancelSeen.get(5, TimeUnit.SECONDS);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.cancels("rejected_fighting")).isEqualTo(1);

        // 结算
        CompletableFuture<SceneBattleReply> settlement = provider.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE));
        CompletableFuture<Void> settlementSeen = watch.apply(settlement);
        assertThat(f.gold(player)).isZero();
        f.drain();
        SceneBattleReply settled = get(settlement);
        settlementSeen.get(5, TimeUnit.SECONDS);
        assertThat(settled.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(settled.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.inBattle()).isFalse();

        synchronized (completedOn) {
            assertThat(completedOn).as("四条应答都在回写线程上完成").containsExactly(REPLY_THREAD, REPLY_THREAD, REPLY_THREAD, REPLY_THREAD);
            assertThat(completedOn).as("不在逻辑线程上回写").doesNotContain(logicThreadName);
        }
        assertThat(provider.inFlight()).isZero();
        for (String method : List.of("prepare", "cancel", "confirm", "settlement")) {
            assertThat(rpc(method, RpcResult.HANDLED)).as(method).isEqualTo(1);
            assertThat(rpcTotal(method)).as(method).isEqualTo(1);
        }
    }

    /** 逻辑线程给出的状态原样带回并按状态计数：恢复还没就绪时的结算 → DEFERRED；玩家不在本节点 → NOT_HERE；备战的业务拒绝仍是 HANDLED。 */
    @Test
    void 逻辑线程给出的状态原样带回_按状态计数() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        SceneBattleProvider provider = provider(4);

        BattleFixture.setRecovery(player, Recovery.PENDING);
        CompletableFuture<SceneBattleReply> deferred = provider.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE));
        f.drain();
        assertThat(get(deferred).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        BattleFixture.setRecovery(player, Recovery.READY);

        CompletableFuture<SceneBattleReply> absent = provider.applySettlement(call(BattleFixture.SCENE_INSTANCE, 2002,
                FakeBattleLocks.record(BattleFixture.settlement(2002, X, GOLD).build())));
        f.drain();
        assertThat(get(absent).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);

        CompletableFuture<SceneBattleReply> rejected = provider.prepareBattle(call(BattleFixture.SCENE_INSTANCE, 2002,
                f.prepareRequest(2002, X).toByteArray()));
        f.drain();
        SceneBattleReply reply = get(rejected);
        assertThat(reply.getStatus()).as("业务拒绝也是 HANDLED，结论在 body 里").isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(PrepareBattleResponse.parseFrom(reply.getBody()).getErrorMessage().getId()).isEqualTo(ENTITY_NULL);

        assertThat(rpc("settlement", RpcResult.DEFERRED)).isEqualTo(1);
        assertThat(rpc("settlement", RpcResult.NOT_HERE)).isEqualTo(1);
        assertThat(rpc("prepare", RpcResult.HANDLED)).isEqualTo(1);
        assertThat(provider.inFlight()).isZero();
        assertThat(f.gold(player)).isZero();
    }

    /**
     * 需要异步 Redis 的分支等结局回来才完成应答（§7.3；调用方的超时因此必须大于一条脚本的最坏耗时）：取消备战等<b>删锁完成</b>
     * （冻结当场就摘了，但应答要等）；没有冻结的结算等<b>读锁</b>回来、按锁应用后才回 APPLIED。等待期间在途名额一直占着。
     */
    @Test
    void 取消的应答等删锁完成_按锁结算的应答等读锁回来_期间名额占着() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        SceneBattleProvider provider = provider(4);
        SceneBattleReply handled = SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).build();

        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<SceneBattleReply> cancel = provider.cancelBattlePrepare(call(BattleFixture.SCENE_INSTANCE, cancelBody()));
        f.drain();

        assertThat(player.inBattle()).as("冻结当场摘掉").isFalse();
        assertThat(f.locks.pending(Op.DELETE_PREPARING)).hasSize(1);
        assertThat(cancel).as("删锁还没完成：应答不完成").isNotDone();
        assertThat(provider.inFlight()).isEqualTo(1);
        assertThat(rpcTotal("cancel")).as("结局出来才计数").isZero();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.locks.release(Op.DELETE_PREPARING);
        f.drain();

        assertThat(get(cancel)).isEqualTo(handled);
        assertThat(f.locks.lock(PLAYER)).as("锁已删").isNull();
        assertThat(provider.inFlight()).isZero();
        assertThat(rpc("cancel", RpcResult.HANDLED)).isEqualTo(1);

        // 没有冻结、锁指向这一局（本实例的冻结丢了，例如备战到期后才确认）：结算按锁应用，应答等读锁
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, com.game.discovery.battle.BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> settlement = provider.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE));
        f.drain();

        assertThat(f.locks.pending(Op.READ_LOCK)).hasSize(1);
        assertThat(settlement).as("读锁还没回来：应答不完成").isNotDone();
        assertThat(f.gold(player)).isZero();
        assertThat(provider.inFlight()).isEqualTo(1);

        f.locks.take(Op.READ_LOCK).complete();
        f.locks.release(Op.READ_LOCK);
        f.drain();

        assertThat(get(settlement).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.settlementsCounted("by_lock", "applied")).isEqualTo(1);
        assertThat(provider.inFlight()).isZero();
        assertThat(rpc("settlement", RpcResult.HANDLED)).isEqualTo(1);
    }

    /**
     * 结算按<b>信封</b>上的 {@code player_id} 核对（调用方按目录解析目标时用的就是它）：与结算体里的玩家不符 = 投错了人 →
     * HANDLED + DISCARDED，不应用、不销账；结算体里那名玩家（就在本节点、冻结正等着这一局）不受影响，随后信封相符的同一份结算照常应用。
     */
    @Test
    void 结算的信封玩家与结算体不符_回HANDLED加DISCARDED_不应用不销账_相符的重投照常应用() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.locks.clearCalls();
        SceneBattleProvider provider = provider(4);
        byte[] body = FakeBattleLocks.record(BattleFixture.settlement(PLAYER, X, GOLD).build());

        CompletableFuture<SceneBattleReply> wrong = provider.applySettlement(call(BattleFixture.SCENE_INSTANCE, 2002, body));
        f.drain();

        SceneBattleReply discarded = get(wrong);
        assertThat(discarded.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(discarded.getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.gold(player)).as("结算体里的玩家没有被应用").isZero();
        assertThat(player.battle().freeze().phase()).as("冻结还在等这一局").isEqualTo(Phase.FIGHTING);
        assertThat(f.locks.calls()).as("不销账、不续锁").isEmpty();
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(f.settlementsCounted("online", "discarded_invalid")).isEqualTo(1);

        CompletableFuture<SceneBattleReply> right = provider.applySettlement(call(BattleFixture.SCENE_INSTANCE, PLAYER, body));
        f.drain();

        assertThat(get(right).getSettlement()).as("没有被那次错投毒化").isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(rpc("settlement", RpcResult.HANDLED)).isEqualTo(2);
    }

    /** 逻辑线程已停（停服）：四个方法的 future 都异常完成（调用方按传输失败处理），名额每次都还回来、各计一次 error。 */
    @Test
    void 逻辑线程已停_四个方法都异常完成_名额释放_计error() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        SceneBattleProvider provider = provider(1);
        f.logic.rejectNewTasks(true);

        List<CompletableFuture<SceneBattleReply>> replies = new ArrayList<>();
        // 在途上限是 1：前一条的名额没还，后一条就会过载——逐条等结局
        replies.add(provider.prepareBattle(call(BattleFixture.SCENE_INSTANCE, f.prepareRequest(PLAYER, 8).toByteArray())));
        assertRejected(replies.get(0));
        replies.add(provider.cancelBattlePrepare(call(BattleFixture.SCENE_INSTANCE, cancelBody())));
        assertRejected(replies.get(1));
        replies.add(provider.confirmBattle(confirmCall(f.deadline())));
        assertRejected(replies.get(2));
        replies.add(provider.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE)));
        assertRejected(replies.get(3));

        assertThat(provider.inFlight()).isZero();
        for (String method : List.of("prepare", "cancel", "confirm", "settlement")) {
            assertThat(rpc(method, RpcResult.ERROR)).as(method).isEqualTo(1);
            assertThat(rpc(method, RpcResult.OVERLOADED)).as("%s：名额每次都还回来了", method).isZero();
        }
        assertThat(f.locks.calls()).isEmpty();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
    }

    /**
     * 逻辑线程上的处理以异常告终（备战写锁的回调抛了意外异常 → 备战应答异常完成 = 结局未知）：提供方的 future 同样异常完成，
     * 名额照还、计 error——不会把在途名额漏掉。
     */
    @Test
    void 逻辑线程上的应答异常完成_提供方照样异常完成并归还名额() throws Exception {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        SceneBattleProvider provider = provider(1);
        // 备战失败后的尽力删锁同步抛出：回调中途出错
        f.locks.failNext(Op.PREPARE_LOCK, new IllegalStateException("模拟 Redis 超时"));
        f.locks.throwNext(Op.DELETE_PREPARING, new IllegalStateException("回调中途的意外异常"));

        CompletableFuture<SceneBattleReply> reply = provider.prepareBattle(call(BattleFixture.SCENE_INSTANCE, f.prepareRequest(PLAYER, X).toByteArray()));
        // Dubbo 是在返回的 future 上挂回调拿结局的（不是 get()，get() 自己会拆 CompletionException）：照它的方式看拿到的异常
        CompletableFuture<Throwable> handedToDubbo = new CompletableFuture<>();
        reply.whenComplete((r, error) -> handedToDubbo.complete(error));
        f.drain();

        assertThatThrownBy(() -> reply.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(handedToDubbo.get(5, TimeUnit.SECONDS)).as("交出去的是原始异常，不带 future 链上的 CompletionException 包装")
                .isInstanceOf(IllegalStateException.class).hasMessage("回调中途的意外异常");
        assertThat(provider.inFlight()).isZero();
        assertThat(rpc("prepare", RpcResult.ERROR)).isEqualTo(1);
        assertThat(player.inBattle()).as("冻结已收拾掉").isFalse();
    }

    /** 回写池已停（停服末尾）：退回到完成它的线程直接回写，名额照样释放。 */
    @Test
    void 回写池已停_退回完成线程直接回写_名额照样释放() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        SceneBattleProvider provider = provider(1);
        replyThread.shutdown();
        AtomicReference<String> completedOn = new AtomicReference<>();

        CompletableFuture<SceneBattleReply> reply = provider.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE));
        reply.thenAccept(r -> completedOn.set(Thread.currentThread().getName()));
        f.drain();

        assertThat(reply).isCompleted();
        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(completedOn.get()).as("回写池停了：就在完成它的线程（这里是逻辑线程）上回").isEqualTo(Thread.currentThread().getName());
        assertThat(provider.inFlight()).isZero();
        assertThat(rpc("settlement", RpcResult.HANDLED)).isEqualTo(1);
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    // ================================================================== 未就绪、参数、结局映射

    /** 节点还没启动好（业务供应方给 null）或调用为空：失败的 future、计 error、不占名额。 */
    @Test
    void 未就绪或空调用_回失败future_计error_不占名额() {
        SceneBattleProvider notReady = new SceneBattleProvider(() -> null, BattleFixture.SCENE_INSTANCE, f.logic, 4, replyThread,
                f.battleMetrics);

        assertThat(notReady.prepareBattle(call(BattleFixture.SCENE_INSTANCE, new byte[0]))).isCompletedExceptionally();
        assertThat(notReady.applySettlement(settlementCall(BattleFixture.SCENE_INSTANCE))).isCompletedExceptionally();
        assertThat(provider(4).confirmBattle(null)).isCompletedExceptionally();

        assertThat(notReady.inFlight()).isZero();
        assertThat(f.logic.pending()).isZero();
        assertThat(rpc("prepare", RpcResult.ERROR)).isEqualTo(1);
        assertThat(rpc("settlement", RpcResult.ERROR)).isEqualTo(1);
        assertThat(rpc("confirm", RpcResult.ERROR)).isEqualTo(1);
    }

    @Test
    void 在途上限至少为1_应答状态到指标的映射() {
        assertThatThrownBy(() -> provider(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(resultOf(SceneBattleStatus.SCENE_BATTLE_HANDLED)).isEqualTo(RpcResult.HANDLED);
        assertThat(resultOf(SceneBattleStatus.SCENE_BATTLE_NOT_HERE)).isEqualTo(RpcResult.NOT_HERE);
        assertThat(resultOf(SceneBattleStatus.SCENE_BATTLE_DEFERRED)).isEqualTo(RpcResult.DEFERRED);
        assertThat(resultOf(SceneBattleStatus.SCENE_BATTLE_OVERLOADED)).isEqualTo(RpcResult.OVERLOADED);
        assertThat(resultOf(SceneBattleStatus.SCENE_BATTLE_STATUS_UNSPECIFIED)).as("字段缺失不能被计成已处理").isEqualTo(RpcResult.ERROR);
        assertThat(SceneBattleProvider.resultOf(SceneBattleReply.newBuilder().setStatusValue(99).build())).isEqualTo(RpcResult.ERROR);
    }

    // ================================================================== 工具

    private static RpcResult resultOf(SceneBattleStatus status) {
        return SceneBattleProvider.resultOf(SceneBattleReply.newBuilder().setStatus(status).build());
    }

    private static void record(List<String> threads, String name) {
        synchronized (threads) {
            threads.add(name);
        }
    }

    private static SceneBattleReply get(CompletableFuture<SceneBattleReply> reply) throws Exception {
        return reply.get(5, TimeUnit.SECONDS);
    }

    private static void assertRejected(CompletableFuture<SceneBattleReply> reply) {
        assertThatThrownBy(() -> reply.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    private static SceneBattleCall call(String instanceId, byte[] body) {
        return call(instanceId, PLAYER, body);
    }

    private static SceneBattleCall call(String instanceId, long envelopePlayer, byte[] body) {
        return SceneBattleCall.newBuilder().setTargetInstanceId(instanceId).setPlayerId(envelopePlayer).setBody(ByteString.copyFrom(body))
                .build();
    }

    private static byte[] cancelBody() {
        return CancelBattlePrepareRequest.newBuilder().setPlayerId(PLAYER).setBattleId(X).build().toByteArray();
    }

    private static byte[] confirmBody(long deadline) {
        return BattleConfirmedEvent.newBuilder().setPlayerId(PLAYER).setBattleId(X).setDeadlineMs(deadline).build().toByteArray();
    }

    private static SceneBattleCall confirmCall(long deadline) {
        return call(BattleFixture.SCENE_INSTANCE, confirmBody(deadline));
    }

    private static SceneBattleCall settlementCall(String instanceId) {
        return call(instanceId, FakeBattleLocks.record(BattleFixture.settlement(PLAYER, X, GOLD).build()));
    }

    private double rpc(String method, RpcResult result) {
        return f.count("xm.scene.battle.rpc", "method", method, "result", result.name().toLowerCase(java.util.Locale.ROOT));
    }

    private double rpcTotal(String method) {
        double total = 0;
        for (RpcResult result : RpcResult.values()) {
            total += rpc(method, result);
        }
        return total;
    }
}
