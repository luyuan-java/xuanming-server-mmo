package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerLeave;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.ScenePlayer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 取消备战 {@code cancelBattlePrepare}（scene-battle-spec §7.6 的表、§13.2；基线 {@code CancelBattlePrepare}，{@code pb.cpp:1233-1310}）：
 * <ul>
 *   <li>玩家不在本节点：一段 {@code CANCEL_OFFLINE}，1 / 2 / 0 与出错各计各的，应答等脚本结局；</li>
 *   <li>在线、没有冻结：忽略、<b>不碰锁</b>（B2）；battle_id 不符：忽略；FIGHTING：拒绝（B1）；</li>
 *   <li>PREPARING：立刻解冻，发「只删备战锁」的条件删除，应答与组队跟随的补查都等删锁结局；</li>
 *   <li>PREPARING 且写锁在途：只记下，写锁完成后才删（Redisson 可能把删锁排到写锁之前，§1.8）。</li>
 * </ul>
 * 取消与紧随其后的确认乱序（审计 FRZ-7）在 {@link BattleLockDeletionRegressionTest}；回调出错 / 逻辑线程已停时应答仍完成在
 * {@link BattleCallbackFailureTest}。
 */
class CancelBattlePrepareTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    /** 不在本节点的玩家。 */
    private static final long ABSENT = 2002;
    private static final long X = 7;
    private static final long Y = 8;
    private static final int NODE = BattleFixture.BATTLE_NODE;
    private static final int FEATURE_UNAVAILABLE = 1006;
    private static final int SERVICE_UNAVAILABLE = 1003;
    private static final int ATTRIBUTE_IN_BATTLE = 25011;
    private static final List<String> RESULTS = List.of("cleared", "idempotent", "mismatch", "rejected_fighting", "deferred",
            "offline_deleted", "offline_rejected_fighting", "offline_absent", "offline_error");

    private final BattleFixture f = new BattleFixture();

    /** 全部取消计数里只有这一个取值是 1，其余是 0（一次取消恰好计一次）。 */
    private void assertCountedOnce(String result) {
        Map<String, Double> expected = new TreeMap<>();
        Map<String, Double> actual = new TreeMap<>();
        for (String each : RESULTS) {
            expected.put(each, each.equals(result) ? 1.0 : 0.0);
            actual.put(each, f.cancels(each));
        }
        assertThat(actual).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ 玩家不在本节点：一段 CANCEL_OFFLINE

    @Test
    void 离线_备战锁是这一局_删掉_计offline_deleted_应答等脚本结局() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.hold(Op.CANCEL_OFFLINE);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);

        assertThat(done).as("应答等脚本结局").isNotDone();
        Call script = f.locks.take(Op.CANCEL_OFFLINE);
        assertThat(script.playerId()).isEqualTo(ABSENT);
        assertThat(script.battleId()).isEqualTo(X);
        script.complete();
        assertThat(done).as("结局还没回到逻辑线程").isNotDone();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(script.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(f.locks.lock(ABSENT)).isNull();
        assertThat(f.locks.ops()).as("一次往返").containsExactly(Op.CANCEL_OFFLINE);
        assertCountedOnce("offline_deleted");
    }

    /** B1 的离线形态：锁上已是 F = 确认已到、房间建成过，这时的取消是建房超时后的过期回滚，不能把在打的局的锁删掉。 */
    @Test
    void 离线_锁已确认开战_拒绝_锁的字段与TTL都不动_计offline_rejected_fighting() {
        long deadline = f.deadline();
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_FIGHTING, deadline, f.prepareDeadline(), 360);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.CANCEL_OFFLINE).lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_FIGHTING);
        assertThat(f.locks.lock(ABSENT)).containsEntry("b", "7").containsEntry("s", "F").containsEntry("d", String.valueOf(deadline));
        assertThat(f.locks.lockTtlSec(ABSENT)).isEqualTo(360);
        assertCountedOnce("offline_rejected_fighting");
    }

    @Test
    void 离线_没有锁_幂等忽略_计offline_absent() {
        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.CANCEL_OFFLINE).lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(f.locks.ops()).containsExactly(Op.CANCEL_OFFLINE);
        assertCountedOnce("offline_absent");
    }

    @Test
    void 离线_锁已是别的局_不动别人的锁_计offline_absent() {
        f.locks.putLock(ABSENT, Y, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lockBattleId(ABSENT)).isEqualTo(Y);
        assertThat(f.locks.lockState(ABSENT)).isEqualTo("P");
        assertThat(f.locks.lockTtlSec(ABSENT)).isEqualTo(120);
        assertCountedOnce("offline_absent");
    }

    @Test
    void 离线_脚本出错_计offline_error_应答照常完成_锁留给TTL() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.failNext(Op.CANCEL_OFFLINE, new RuntimeException("Redis 超时"));

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        assertThat(done).isNotDone();
        f.drain();

        assertThat(done).as("取消只管尽力，出错也给调用方一个结局").isCompleted();
        assertThat(f.locks.lockBattleId(ABSENT)).isEqualTo(X);
        assertThat(f.locks.ops()).as("不重试").containsExactly(Op.CANCEL_OFFLINE);
        assertCountedOnce("offline_error");
    }

    /** §7.2 重放语义：首发已把锁删掉、回复超时、Redisson 原样重发 → 第二遍回 0，计 offline_absent，无害。 */
    @Test
    void 离线_脚本被重放_第二遍回0计offline_absent_锁已删() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.replayNext(Op.CANCEL_OFFLINE);

        CompletableFuture<Void> done = f.cancel(ABSENT, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.CANCEL_OFFLINE).executions()).isEqualTo(2);
        assertThat(f.locks.lock(ABSENT)).isNull();
        assertCountedOnce("offline_absent");
    }

    // ------------------------------------------------------------------ 忽略与拒绝（都不碰锁）

    @Test
    void player_id或battle_id为0_直接忽略_零Redis调用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        f.locks.clearCalls();

        CompletableFuture<Void> noPlayer = f.cancel(0, X);
        CompletableFuture<Void> noBattle = f.cancel(PLAYER, 0);

        assertThat(noPlayer).isCompleted();
        assertThat(noBattle).isCompleted();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(f.cancels("mismatch")).isEqualTo(2);
        assertThat(f.cancels("cleared") + f.cancels("offline_absent") + f.cancels("offline_deleted")).isZero();
    }

    /**
     * B2：玩家在线、没有冻结（备战到期被 reaper 摘了冻结、锁留到 TTL 给迟到的确认）。取消是幂等忽略，<b>不碰锁</b>——
     * 删了的话随后到达的确认就没法据锁重建。
     */
    @Test
    void 在线没有冻结_忽略_不碰还留着的锁_计idempotent() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.calls()).as("在线分支不发 CANCEL_OFFLINE，也不发删除").isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(120);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.follows.freezeCleared).isEmpty();
        assertCountedOnce("idempotent");
    }

    /** 迟到的取消（上一局的）不得解冻下一局。 */
    @Test
    void battle_id不符_忽略_冻结与锁都不动_计mismatch() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        f.locks.clearCalls();

        CompletableFuture<Void> done = f.cancel(PLAYER, Y);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.cancelRequested()).isFalse();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.follows.freezeCleared).isEmpty();
        assertCountedOnce("mismatch");
    }

    /** B1：确认已到 = 房间建成过，这时的取消是 CreateBattle 超时后的过期回滚，拒绝。 */
    @Test
    void 已确认开战_拒绝取消_冻结与锁都不动_计rejected_fighting() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        long ttl = f.locks.lockTtlSec(PLAYER);
        f.locks.clearCalls();

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(ttl);
        assertThat(f.follows.freezeCleared).isEmpty();
        assertCountedOnce("rejected_fighting");
    }

    // ------------------------------------------------------------------ PREPARING：解冻并条件删锁

    /**
     * 在线 PREPARING 的取消：当场解冻（在途闸立刻放开），发一条「只删备战锁」（b == X 且 s ≠ F 才删，审计 FRZ-7；不用不看 s 的
     * DELETE_IF_MATCH，也不用 CANCEL_OFFLINE）。应答与组队跟随的补查都等删锁结局：跟随链会读锁，删锁完成之前读到的还是「在途」。
     */
    @Test
    void 备战中_立刻解冻并条件删锁_删锁完成后才完成应答并触发跟随复查() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.hold(Op.DELETE_PREPARING);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);

        assertThat(player.inBattle()).as("取消当场解冻").isFalse();
        assertThat(f.attributes.createScheme(player, "解冻了").tipId()).as("在途闸立刻放开").isNotEqualTo(ATTRIBUTE_IN_BATTLE);
        assertThat(f.locks.ops()).containsExactly(Op.DELETE_PREPARING);
        assertThat(f.locks.take(Op.DELETE_PREPARING).playerId()).isEqualTo(PLAYER);
        assertThat(f.locks.take(Op.DELETE_PREPARING).battleId()).isEqualTo(X);
        assertThat(done).as("应答等删锁结局").isNotDone();
        assertThat(f.follows.freezeCleared).as("锁还没删，不补跟随").isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        assertThat(done).as("结局还没回到逻辑线程").isNotDone();
        assertThat(f.follows.freezeCleared).isEmpty();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).as("删锁完成后补一次跟随").containsExactly(PLAYER);
        assertThat(f.locks.ops()).as("只有这一条脚本").containsExactly(Op.DELETE_PREPARING);
        assertThat(f.reconnectHints(player)).isEmpty();
        assertCountedOnce("cleared");
    }

    @Test
    void 备战中_取消后同一局可以重新备战() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.cancel(PLAYER, X);
        f.drain();
        assertThat(f.locks.lock(PLAYER)).isNull();

        PrepareBattleResponse again = f.prepared(PLAYER, X);

        assertThat(BattleFixture.tipOf(again)).isZero();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
    }

    /** 重复取消幂等：第二次到达时已经没有冻结 → 按「在线没有冻结」忽略，不再发第二条删除。 */
    @Test
    void 备战中_重复取消_第二次忽略_只删一次锁() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.hold(Op.DELETE_PREPARING);

        CompletableFuture<Void> first = f.cancel(PLAYER, X);
        CompletableFuture<Void> second = f.cancel(PLAYER, X);

        assertThat(second).as("第二次当场完成").isCompleted();
        assertThat(first).isNotDone();
        f.locks.completeAll();
        f.drain();

        assertThat(first).isCompleted();
        assertThat(f.locks.ops()).containsExactly(Op.DELETE_PREPARING);
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertThat(f.cancels("cleared")).isEqualTo(1);
        assertThat(f.cancels("idempotent")).isEqualTo(1);
    }

    /** 删锁脚本出错：解冻已经生效，应答照常完成、跟随照常补（读到残留的锁会自己放弃）；锁留给 TTL，不重试。 */
    @Test
    void 备战中_删锁出错_解冻已生效_应答与跟随照常_锁留给TTL() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.failNext(Op.DELETE_PREPARING, new RuntimeException("Redis 超时"));

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        assertThat(done).isNotDone();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).as("没删掉，等 TTL").isEqualTo(X);
        assertThat(f.locks.ops()).as("不重试").containsExactly(Op.DELETE_PREPARING);
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertCountedOnce("cleared");
    }

    /** 删除被重放（首发已删、回复丢了、重发回 0）：与删到了同样收尾。 */
    @Test
    void 备战中_删锁被重放_第二遍回0_照常收尾() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.replayNext(Op.DELETE_PREPARING);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.last(Op.DELETE_PREPARING).lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_MISS);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    /** 删锁结局回来时实例已换（玩家离场了）：不对已经不在的实例补跟随；应答照常完成。 */
    @Test
    void 备战中_删锁完成时玩家已离场_不触发跟随_应答照常完成() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).isEmpty();
    }

    /**
     * 删锁结局回来时同一名玩家已重进（同 epoch，新实例）：回调认的是旧实例，不对新实例补跟随。
     * （新实例的恢复读这里排在删锁之后才执行，读不到锁、不重建；排在删锁之前的那种交错见汇报的残余风险。）
     */
    @Test
    void 备战中_删锁完成时实例已换成新实例_不触发跟随() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.ENTER_READ);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.battle().freeze()).as("已取消的冻结不会被沿用").isNull();
        f.follows.clear();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).as("旧实例的删锁回调不碰新实例").isEmpty();

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(fresh.inBattle()).isFalse();
        assertThat(fresh.battle().recovery()).isEqualTo(PlayerBattle.Recovery.READY);
    }

    /**
     * 上一条的坏交错（已知残余，钉住它<b>有界</b>）：删锁还没在 Redis 上执行时同 epoch 重进，新实例的恢复读与复核都排在删锁之前——
     * 看到的还是那把备战锁，按锁重建出 PREPARING 冻结、复核命中；随后那条删除才执行。新实例上留下一个没有锁的备战冻结
     * （这一局已被 match 取消，不会有确认来）：到备战期限由 reaper 摘掉。删锁只晚于恢复读、早于复核时，复核落空当场撤销（见下一条）。
     */
    @Test
    void 备战中_删锁排在新实例的恢复读与复核之后才执行_重建的备战冻结到备战期限被reaper摘掉() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt).as("恢复读看到了还没删掉的备战锁").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(fresh.battle().freeze()).as("残余：新实例上的备战冻结此刻没有锁").isSameAs(rebuilt);
        assertThat(f.reconnectHints(fresh)).isEmpty();

        f.advance(BattleFixture.PREPARE_MILLIS + 1);
        f.reap();

        assertThat(fresh.inBattle()).as("备战期限一过，reaper 摘掉").isFalse();
    }

    /** 删锁排在新实例的恢复读之后、复核之前：复核（TOUCH）落空，重建当场撤销，不留冻结。 */
    @Test
    void 备战中_删锁排在新实例的恢复读之后复核之前_复核落空当场撤销() {
        f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.TOUCH);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        assertThat(fresh.battle().freeze()).as("按快照里的锁重建，等复核").isNotNull();

        f.locks.take(Op.DELETE_PREPARING).complete();
        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(done).isCompleted();
        assertThat(fresh.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.locks.lock(PLAYER)).isNull();
    }

    // ------------------------------------------------------------------ PREPARING 且写锁在途：延后

    /**
     * 写锁在途时的取消只记下（cancelRequested）、当场应答、不解冻、不删锁；写锁成功回来后才解冻并删锁，备战回 1006（计 cancelled）。
     * 立刻删的话删除可能被 Redisson 排到写锁之前，留下一把没人管的备战锁。
     */
    @Test
    void 写锁在途_只记下不解冻不删锁_写锁完成后才解冻删锁_删完才补跟随() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();

        CompletableFuture<Void> done = f.cancel(PLAYER, X);

        assertThat(done).as("延后的取消当场应答").isCompleted();
        assertThat(player.battle().freeze()).as("写锁没回来之前冻结还在").isSameAs(freeze);
        assertThat(freeze.cancelRequested()).isTrue();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);
        assertCountedOnce("deferred");

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lockBattleId(PLAYER)).as("删除还没执行").isEqualTo(X);
        assertThat(f.follows.freezeCleared).as("锁还没删，不补跟随").isEmpty();
        assertThat(f.prepares("cancelled")).isEqualTo(1);

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    @Test
    void 写锁在途_重复取消幂等_写锁完成后只删一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);

        assertThat(f.cancel(PLAYER, X)).isCompleted();
        assertThat(f.cancel(PLAYER, X)).isCompleted();
        assertThat(f.cancels("deferred")).isEqualTo(2);
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    /** 写锁在途时取消的是别的局：不记 cancelRequested，写锁回来后备战照常成功。 */
    @Test
    void 写锁在途_取消的是别的局_不记下_备战照常成功() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);

        assertThat(f.cancel(PLAYER, Y)).isCompleted();
        assertThat(player.battle().freeze().cancelRequested()).isFalse();
        assertCountedOnce("mismatch");

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isZero();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
    }

    /** 记下了取消、写锁却发现锁被别的局占着：按被占收尾（1006、锁不动、不删别人的锁），取消没有别的事可做。 */
    @Test
    void 写锁在途记下取消_写锁回来是被占_不删别人的锁() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, Y, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);
        f.cancel(PLAYER, X);

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(Y);
        assertThat(f.prepares("lock_held")).isEqualTo(1);
        assertThat(f.prepares("cancelled")).isZero();
    }

    /** 记下了取消、写锁以出错完成：按写锁失败收尾（1003、尽力删一次备战锁），不重复删。 */
    @Test
    void 写锁在途记下取消_写锁出错_按写锁失败收尾_只尽力删一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, X);
        f.cancel(PLAYER, X);

        f.locks.take(Op.PREPARE_LOCK).executeThenFail(new RuntimeException("Redis 超时"));
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.prepares("redis_error")).isEqualTo(1);
        assertThat(f.prepares("cancelled")).isZero();
    }

    // ------------------------------------------------------------------ 与 5.2 的交出冻结

    /** 交出冻结中的玩家没有战斗冻结（两种冻结互斥）：取消按「在线没有冻结」忽略，不碰锁、不动交出。 */
    @Test
    void 交出冻结中的玩家_取消按没有冻结忽略_不碰锁() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.freezeForHandOff(player);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(player.frozen()).isTrue();
        assertCountedOnce("idempotent");
    }
}
