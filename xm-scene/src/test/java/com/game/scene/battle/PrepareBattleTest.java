package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.game.proto.ActorBaseAttributesS2C;
import com.game.proto.AllocateAttributePointsRequest;
import com.game.proto.AllocateAttributePointsResponse;
import com.game.proto.MessageContent;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.SwitchPhase;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 备战 {@code prepareBattle}（scene-battle-spec §7.5；§13.2 PrepareBattleTest 一行逐条）：
 * <ul>
 *   <li>逐条拒绝码及其次序（1005 → 1004 → 1006 换图在途 → 1006 已有冻结 → 1006 恢复未就绪 / 账本未落盘 / 账本损坏 → 1006 0 血 → 1011），
 *       拒绝时零冻结、零 Redis 调用；</li>
 *   <li>成功：挂冻结即生效（客户端的 168 立刻 25011）、锁写完才回应答、锁字段与 TTL、停步（旁观者收到速度 0 的 66）；</li>
 *   <li>写锁结局的各分支：被占、脚本重放、Redis 失败、写锁期间离场 / 收到取消 / 同 epoch 重进 / 备战到期；</li>
 *   <li>6.3 审计修掉的几处（FRZ-6 / RDS-10 过期分支结局不明也删锁、FRZ-7 只删备战锁、GAT-14 过期的 RESOLVING 槽不再挡、
 *       FRZ-2 回调出错应答仍有结局、STL-11 账本损坏打带原因的日志）；</li>
 *   <li>第二轮修正：R2-a 本节点删掉备战锁之后，现任实例上按这把锁重建出来的备战冻结一并摘掉（同 epoch 重进与写锁 / 删锁交错）；
 *       R2-c 期限超过上限（7 天）按参数非法 1005 拒绝。</li>
 * </ul>
 */
class PrepareBattleTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long BATTLE = 7;
    private static final int INVALID_PARAMETER = 1005;
    private static final int ENTITY_NULL = 1004;
    private static final int FEATURE_UNAVAILABLE = 1006;
    private static final int SERVICE_UNAVAILABLE = 1003;
    private static final int SESSION_NOT_FOUND = 1011;
    private static final int ATTRIBUTE_IN_BATTLE = 25011;

    private final BattleFixture f = new BattleFixture();
    private final ch.qos.logback.classic.Logger serviceLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PlayerBattleService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        serviceLog.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        serviceLog.detachAppender(logs);
    }

    // ------------------------------------------------------------------ 成功

    @Test
    void 成功_挂冻结即生效_写锁完成后才回快照与指纹_锁的字段与TTL正确() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long deadline = f.deadline();
        long prepareDeadline = f.prepareDeadline();

        CompletableFuture<PrepareBattleResponse> reply =
                f.battle.prepare(f.prepareRequest(PLAYER, BATTLE, deadline, prepareDeadline));

        // 写锁的回调还没回到逻辑线程：冻结已挂上（各闸立刻生效），应答还没完成
        assertThat(reply).isNotDone();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).isNotNull();
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.battleId()).isEqualTo(BATTLE);
        assertThat(freeze.battleNodeId()).isEqualTo(BattleFixture.BATTLE_NODE);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(freeze.prepareDeadlineMs()).isEqualTo(prepareDeadline);
        assertThat(freeze.preparedHere()).isTrue();
        assertThat(freeze.lockPending()).isTrue();
        assertThat(f.attributes.createScheme(player, "战斗中").tipId()).as("挂冻结后属性写入口立刻回 25011").isEqualTo(ATTRIBUTE_IN_BATTLE);
        Call write = f.locks.last(Op.PREPARE_LOCK);
        assertThat(write.playerId()).isEqualTo(PLAYER);
        assertThat(write.battleId()).isEqualTo(BATTLE);
        assertThat(write.longArg("node")).isEqualTo(BattleFixture.BATTLE_NODE);
        assertThat(write.longArg("deadline")).isEqualTo(deadline);
        assertThat(write.longArg("prepareDeadline")).isEqualTo(prepareDeadline);
        assertThat(write.longArg("ttl")).as("TTL = 备战期限剩余秒数 + 60").isEqualTo(BattleFixture.PREPARE_MILLIS / 1000 + 60);

        f.drain();

        assertThat(reply).isCompleted();
        PrepareBattleResponse response = BattleFixture.done(reply);
        assertThat(response.hasErrorMessage()).as("成功不带 error_message").isFalse();
        assertThat(response.getSnapshot().getPlayerId()).isEqualTo(PLAYER);
        assertThat(response.getSnapshot().getRouting().getSessionId()).isEqualTo(SESSION);
        assertThat(response.getSnapshot().getRouting().getSceneInstanceId()).isEqualTo(BattleFixture.SCENE_INSTANCE);
        assertThat(response.getTableFingerprint()).isEqualTo(f.tables.fingerprint()).matches("[0-9a-f]{32}");
        assertThat(response.getSnapshot().getTableFingerprint()).isEqualTo(f.tables.fingerprint());
        assertThat(freeze.lockPending()).isFalse();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(f.locks.lock(PLAYER)).containsEntry("b", "7").containsEntry("n", "21").containsEntry("s", "P")
                .containsEntry("d", String.valueOf(deadline)).containsEntry("p", String.valueOf(prepareDeadline));
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(120);
        assertThat(f.locks.ops()).as("只有一次写锁").containsExactly(Op.PREPARE_LOCK);
        assertThat(f.reconnectHints(player)).as("PREPARING 永不推 144").isEmpty();
        assertThat(f.prepares("ok")).isEqualTo(1);
    }

    @Test
    void 备战期限为0_沿用战斗期限_锁的TTL按它算() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long deadline = f.deadline();

        CompletableFuture<PrepareBattleResponse> reply = f.battle.prepare(f.prepareRequest(PLAYER, BATTLE, deadline, 0));
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isZero();
        assertThat(player.battle().freeze().prepareDeadlineMs()).isEqualTo(deadline);
        assertThat(f.locks.last(Op.PREPARE_LOCK).longArg("prepareDeadline")).isEqualTo(deadline);
        assertThat(f.locks.last(Op.PREPARE_LOCK).longArg("ttl")).isEqualTo(BattleFixture.BATTLE_MILLIS / 1000 + 60);
    }

    @Test
    void 备战即停步_速度清零并置速度脏位() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        WorldTestAccess.setVelocity(player, new Vec3(1, 0, 0));
        assertThat(WorldTestAccess.velocityDirty(player)).isFalse();

        f.prepare(PLAYER, BATTLE);

        assertThat(player.velocity().isOrigin()).as("挂冻结的同一步就停下，不等写锁").isTrue();
        assertThat(WorldTestAccess.velocityDirty(player)).as("旁观者下一个同步帧收到速度 0 的 66").isTrue();
    }

    @Test
    void 脚本重放_首发已执行又被重发_仍按成功处理_冻结与锁都在() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.replayNext(Op.PREPARE_LOCK);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        f.drain();

        assertThat(f.locks.last(Op.PREPARE_LOCK).executions()).isEqualTo(2);
        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).as("同局且仍是 P 的命中按重放成功，不当成被占").isZero();
        assertThat(player.inBattle()).isTrue();
        assertThat(player.battle().freeze().lockPending()).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(BATTLE);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("P");
        assertThat(f.locks.lockTtlSec(PLAYER)).as("重放那一遍照样续 TTL").isEqualTo(BattleFixture.PREPARE_MILLIS / 1000 + 60);
        assertThat(f.locks.ops()).as("不因为「被占」去删锁").containsExactly(Op.PREPARE_LOCK);
        assertThat(f.prepares("ok")).isEqualTo(1);
        assertThat(f.prepares("lock_held")).isZero();
    }

    // ------------------------------------------------------------------ 拒绝（零冻结、零 Redis 调用）

    @Test
    void 参数为0_回1005_零冻结零Redis调用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        PrepareBattleRequest ok = f.prepareRequest(PLAYER, BATTLE);

        for (PrepareBattleRequest bad : List.of(ok.toBuilder().setPlayerId(0).build(), ok.toBuilder().setBattleId(0).build(),
                ok.toBuilder().setDeadlineMs(0).build())) {
            CompletableFuture<PrepareBattleResponse> reply = f.battle.prepare(bad);
            assertThat(reply).isCompleted();
            assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(INVALID_PARAMETER);
        }

        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.prepares("invalid")).isEqualTo(3);
    }

    /**
     * 第二轮修正 R2-c：期限（{@code deadline_ms} / {@code prepare_deadline_ms}）距现在超过 {@link PlayerBattleService#MAX_DEADLINE_AHEAD_MS}
     * （7 天）按参数非法拒绝——与「参数为 0」同码（1005）、同序（先于「玩家不在本节点」与一切玩家状态判断）、同样零冻结零 Redis 调用。
     * 不拦的话 {@code PREPARE_LOCK} 的 HSET 之后 EXPIRE 因 TTL 越界报错，留下一把没有 TTL 的锁。期限按无符号比较：≥ 2^63 的值同样超界。
     */
    @Test
    void 期限超过上限_回1005_与参数为0同码同序_零冻结零Redis调用_恰在上限上放行() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long max = PlayerBattleService.MAX_DEADLINE_AHEAD_MS;
        assertThat(max).as("上限 = 7 天 = 待结算记录的保留期").isEqualTo(7L * 24 * 3600 * 1000).isEqualTo(BattleRedis.SETTLEMENT_TTL_SEC * 1000);
        long latest = f.clock.epochMillis() + max;

        assertRejected(f.prepareRequest(PLAYER, BATTLE, latest + 1, f.prepareDeadline()), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(PLAYER, BATTLE, f.deadline(), latest + 1), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(PLAYER, BATTLE, latest + 1, 0), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(PLAYER, BATTLE, Long.MAX_VALUE, f.prepareDeadline()), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(PLAYER, BATTLE, Long.MIN_VALUE, f.prepareDeadline()), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(PLAYER, BATTLE, -1L, f.prepareDeadline()), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(PLAYER, BATTLE, f.deadline(), -1L), INVALID_PARAMETER, "invalid");
        // 同序：排在「玩家不在本节点」（1004）之前
        assertRejected(f.prepareRequest(2002, BATTLE, latest + 1, 0), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(2002, BATTLE, f.deadline(), latest + 1), INVALID_PARAMETER, "invalid");
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.calls()).as("一次 Redis 都没碰").isEmpty();
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("期限"))
                .as("每次越界拒绝留一条带玩家与局号的 WARN").hasSize(9)
                .allSatisfy(e -> assertThat(e.getFormattedMessage()).contains("battle_id=7"));

        // 恰好在上限上（两个期限都是）：放行，锁的 TTL = 7 天 + 60 s，EXPIRE 不会报错
        CompletableFuture<PrepareBattleResponse> reply = f.battle.prepare(f.prepareRequest(PLAYER, BATTLE, latest, latest));
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isZero();
        assertThat(f.locks.last(Op.PREPARE_LOCK).longArg("ttl")).isEqualTo(BattleRedis.SETTLEMENT_TTL_SEC + 60);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(604_860);
        assertThat(player.battle().freeze().deadlineMs()).isEqualTo(latest);
    }

    /** R2-c 只设上界：已经过去的期限照旧放行（锁 TTL 取下限 60 s，冻结由下一轮 reaper 摘掉），不改原有行为。 */
    @Test
    void 期限已经过去_照旧放行_锁TTL取下限60秒() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long past = f.clock.epochMillis() - 1;

        CompletableFuture<PrepareBattleResponse> reply = f.battle.prepare(f.prepareRequest(PLAYER, BATTLE, past, past));
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isZero();
        assertThat(f.locks.last(Op.PREPARE_LOCK).longArg("ttl")).isEqualTo(60);
        assertThat(player.inBattle()).isTrue();
        f.reap();
        assertThat(player.inBattle()).as("期限已过：下一轮 reaper 摘掉").isFalse();
    }

    @Test
    void 玩家不在本节点_回1004_零Redis调用() {
        f.enter(SESSION, PLAYER);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(2002, BATTLE);

        assertThat(reply).isCompleted();
        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.prepares("not_here")).isEqualTo(1);
    }

    @Test
    void 已有冻结_回1006_原冻结与锁都不动() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, BATTLE);
        BattleFreeze first = player.battle().freeze();
        f.locks.clearCalls();

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, 8);

        assertThat(reply).isCompleted();
        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.battle().freeze()).isSameAs(first);
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(BATTLE);
        assertThat(f.prepares("in_battle")).isEqualTo(1);
    }

    @Test
    void 进场恢复还没就绪_回1006_恢复读回来后放行() {
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);

        CompletableFuture<PrepareBattleResponse> rejected = f.prepare(PLAYER, BATTLE);

        assertThat(BattleFixture.tipOf(BattleFixture.done(rejected))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.count(Op.PREPARE_LOCK)).isZero();
        assertThat(f.prepares("not_ready")).isEqualTo(1);

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, BATTLE))).isZero();
    }

    @Test
    void 气血为0_回1006() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        player.attributes().setHealth(0);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.prepares("dead")).isEqualTo(1);
    }

    @Test
    void 多个拒绝条件同时成立_回靠前的码_参数先于不在场先于在途() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, BATTLE);
        player.attributes().setHealth(0);

        assertThat(BattleFixture.tipOf(BattleFixture.done(f.battle.prepare(f.prepareRequest(PLAYER, 0))))).isEqualTo(INVALID_PARAMETER);
        assertThat(BattleFixture.tipOf(BattleFixture.done(f.prepare(PLAYER, 8)))).as("已有冻结先于 0 血").isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(f.prepares("in_battle")).isEqualTo(1);
        assertThat(f.prepares("dead")).isZero();
    }

    /** STL-11：账本损坏的玩家备战一律 1006；进场规整时大声报一次，被拒时再打一条带原因的日志（计数取值与「恢复中」相同，靠日志区分）。 */
    @Test
    void 账本损坏_进场时报一次ERROR_备战回1006并打带原因的日志() {
        PlayerState corrupt = PlayerState.newBuilder().setBattleLedger(BattleLedgerState.newBuilder()
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(5).setAppliedAtMs(1))
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(5).setAppliedAtMs(2))).build();

        ScenePlayer player = f.enter(SESSION, PLAYER, corrupt);

        assertThat(player.battleLedger().invalidReason()).contains("重复");
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("账本损坏"))
                .as("进场规整时报一次").singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("player=1001").contains("battle_id 重复 5"));
        assertThat(player.battle().recovery()).as("没有待结算记录时恢复照常就绪").isEqualTo(Recovery.READY);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.prepares("not_ready")).isEqualTo(1);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("备战被拒"))
                .singleElement().satisfies(e -> assertThat(e.getFormattedMessage()).contains("账本损坏").contains("battle_id 重复 5")
                        .contains("player=1001").contains("battle_id=7"));
        assertThat(BattleFixture.persistentState(player).getBattleLedger()).as("损坏的账本原样带回、不改写")
                .isEqualTo(corrupt.getBattleLedger());
    }

    // ------------------------------------------------------------------ 与 5.2 互斥（GAT-14）

    @Test
    void 换图选目标中_回1006_零冻结零Redis调用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.resolveRemote(player);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(player.switchPhase()).as("没过期的槽不动").isEqualTo(SwitchPhase.RESOLVING);
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.prepares("switching")).isEqualTo(1);
    }

    @Test
    void 交出冻结中_回1006() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.freezeForHandOff(player);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(player.frozen()).isTrue();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.prepares("switching")).isEqualTo(1);
    }

    /**
     * GAT-14：选目标的结果回调丢了，RESOLVING 槽过了期限（兜底超时 4 s + 1 s）。备战不能被这个死槽一直挡成 1006：
     * 备战闸经 {@code SceneWorld.switchInFlight} 判在途，过期即清槽、放行；之后迟到的选目标结果按过期丢弃，不会在战斗冻结上再起交出。
     */
    @Test
    void 选目标的槽已过期_备战放行并清掉槽_迟到的选目标结果被丢弃() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        FakeSwitchTargets.PendingSelect lost = f.resolveRemote(player);
        f.advance(4_999);
        assertThat(BattleFixture.tipOf(BattleFixture.done(f.prepare(PLAYER, BATTLE)))).as("差 1 ms 没过期，仍挡").isEqualTo(FEATURE_UNAVAILABLE);
        f.advance(1);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isZero();
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);

        lost.chosen(BattleFixture.TARGET_NODE, BattleFixture.REMOTE_SCENE, 2);

        assertThat(player.frozen()).as("迟到的结果不得把战斗中的玩家带进交出").isFalse();
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.NONE);
        assertThat(f.repo.pendingHandOffs()).isZero();
        assertThat(player.inBattle()).isTrue();
    }

    // ------------------------------------------------------------------ 写锁结局的各分支

    @Test
    void 锁被别的局占着_回1006_解冻_锁不动也不删() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, 99, 5, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        assertThat(player.inBattle()).isTrue();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(99);
        assertThat(f.locks.lockTtlSec(PLAYER)).as("别人的锁 TTL 不动").isEqualTo(360);
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);
        assertThat(f.follows.freezeCleared).as("保留锁的解冻当场补一次跟随").containsExactly(PLAYER);
        assertThat(f.prepares("lock_held")).isEqualTo(1);
    }

    /**
     * FRZ-7：备战失败后的尽力删锁只删备战锁（b == X 且 s ≠ F），不用不看 s 的 DELETE_IF_MATCH。
     *
     * <p>评审 R2-REV-1 的同一条规则：解冻（锁按保留处理）当场补的那次跟随读到的是这把残留的锁、会放弃；尽力删锁确实把它删掉了（返回 1）→ 再补一次，
     * 否则删完之后没有触发点。脚本根本没执行、没有锁可删的那种情形不补第二次，见 {@code 写锁连不上Redis_…}。
     */
    @Test
    void 写锁出错_回1003_解冻_尽力只删备战锁_删到了再补一次跟随() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.failNextAfterExecuting(Op.PREPARE_LOCK, new RuntimeException("Redis 超时"));

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        assertThat(f.locks.lockBattleId(PLAYER)).as("脚本其实已执行，只是回复丢了").isEqualTo(BATTLE);
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.last(Op.DELETE_PREPARING).battleId()).isEqualTo(BATTLE);
        assertThat(f.locks.lock(PLAYER)).as("残留的备战锁被删掉").isNull();
        assertThat(f.locks.last(Op.DELETE_PREPARING).lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(f.follows.freezeClearedPlayers).as("解冻当场一次（读到残留的锁会放弃）+ 锁确实删掉后一次").containsExactly(player, player);
        assertThat(f.prepares("redis_error")).isEqualTo(1);
    }

    @Test
    void 写锁期间收到取消_写锁完成后才删锁_回1006_只删备战锁() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);

        CompletableFuture<Void> cancelled = f.cancel(PLAYER, BATTLE);

        assertThat(cancelled).as("取消只记下，立即应答").isCompleted();
        assertThat(player.battle().freeze().cancelRequested()).isTrue();
        assertThat(f.locks.count(Op.DELETE_PREPARING)).as("写锁没完成前不删锁（删锁可能被排到写锁之前）").isZero();
        assertThat(f.cancels("deferred")).isEqualTo(1);

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.prepares("cancelled")).isEqualTo(1);
    }

    @Test
    void 写锁期间玩家离场_占到了_回1004并删掉这把备战锁() {
        f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        leave();

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).as("锁删掉了，但玩家已不在本节点：没有人可补跟随").isEmpty();
        assertThat(f.prepares("stale")).isEqualTo(1);
    }

    /**
     * FRZ-6 / RDS-10：写锁以出错 / 超时完成（脚本可能已执行、只是回复丢了）而实例又已经换了——应答 1004，match 不会补发取消，
     * 所以这里也要尽力删一次，否则锁留到备战 TTL（最长 96 + 60 s），玩家这段时间排不了队。
     */
    @Test
    void 写锁期间玩家离场_写锁结局不明_回1004并照样尽力删锁() {
        f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        leave();

        f.locks.take(Op.PREPARE_LOCK).executeThenFail(new RuntimeException("Redis 超时"));
        assertThat(f.locks.lockBattleId(PLAYER)).as("脚本已执行，锁写上了").isEqualTo(BATTLE);
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.last(Op.DELETE_PREPARING).battleId()).isEqualTo(BATTLE);
        assertThat(f.locks.lock(PLAYER)).as("孤儿备战锁被删掉").isNull();
        assertThat(f.prepares("stale")).isEqualTo(1);
    }

    @Test
    void 写锁期间玩家离场_锁被别的局占着_回1004不删别人的锁() {
        f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, 99, 5, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        leave();

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.ops()).as("没占到就不发删除").containsExactly(Op.PREPARE_LOCK);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(99);
    }

    // ------------------------------------------------------------------ 回调没能跑完时应答仍有结局（FRZ-2 / STL-8）

    /**
     * 回调里的意外异常不得让应答 future 悬着（提供方的在途名额只在应答完成时归还）：应答异常完成 = 结局未知，调用方按可能已冻结补发取消。
     * 这里让「写锁超时（其实已执行）→ 解冻」那一步的跟随钩子抛出：正常流程里紧随其后的尽力删锁被异常跳过，由收尾补上，孤儿备战锁不留。
     */
    @Test
    void 写锁回调中途抛异常_应答异常完成_没有冻结_孤儿备战锁照样被删() {
        RuntimeException boom = new IllegalStateException("跟随钩子炸了");
        BattleFixture broken = new BattleFixture(wiring -> new TeamFollow() {
            @Override
            public void onEnteredScene(SceneWorld world, ScenePlayer player) {
            }

            @Override
            public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
                throw boom;
            }
        });
        ScenePlayer player = broken.enter(SESSION, PLAYER);
        broken.locks.failNextAfterExecuting(Op.PREPARE_LOCK, new RuntimeException("Redis 超时"));

        CompletableFuture<PrepareBattleResponse> reply = broken.prepare(PLAYER, BATTLE);
        assertThat(broken.locks.lockBattleId(PLAYER)).as("脚本已执行，锁写上了").isEqualTo(BATTLE);
        broken.drain();

        assertThat(reply).isCompletedExceptionally();
        assertThatThrownBy(reply::join).isInstanceOf(CompletionException.class).hasCause(boom);
        assertThat(player.inBattle()).isFalse();
        assertThat(broken.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(broken.locks.lock(PLAYER)).isNull();
    }

    @Test
    void 逻辑线程已停_写锁结局投递被拒_应答异常完成_不在别的线程上碰玩家状态() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        f.logic.rejectNewTasks(true);

        f.locks.take(Op.PREPARE_LOCK).complete();

        assertThat(reply).as("逻辑线程已停：应答异常完成，不悬着").isCompletedExceptionally();
        assertThatThrownBy(reply::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(player.battle().freeze()).as("投递被拒时回调没有跑，玩家状态原样").isNotNull();
        assertThat(player.battle().freeze().lockPending()).isTrue();
        assertThat(f.follows.freezeCleared).isEmpty();
    }

    // ------------------------------------------------------------------ 拒绝码的次序（§7.5 第 1 步：前一步拒绝，后面全不执行、零痕迹）

    /**
     * 全部拒绝条件同时成立，再从前往后逐个放开：每次回的都是此刻最靠前的那一条。1006 有四种来历，靠计数取值区分先后：
     * 参数 1005 → 换图在途 switching（FREEZING、RESOLVING）→ 已有冻结 in_battle → 恢复未就绪 not_ready → 0 血 dead → 组快照取不到路由 1011。
     * 每次拒绝都零痕迹：冻结对象不变、没有任何 Redis 调用。
     */
    @Test
    void 拒绝次序_全部条件同时成立时逐个放开_每次回最靠前的那一条() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleFreeze other = new BattleFreeze(99, BattleFixture.BATTLE_NODE, Phase.PREPARING, f.deadline(), f.prepareDeadline(), true);
        BattleFixture.setFreeze(player, other);
        BattleFixture.setRecovery(player, Recovery.RETRY);
        player.attributes().setHealth(0);
        f.routingAvailable = false;
        WorldTestAccess.startFreezing(player);
        PrepareBattleRequest request = f.prepareRequest(PLAYER, BATTLE);

        assertRejected(request.toBuilder().setDeadlineMs(0).build(), INVALID_PARAMETER, "invalid");
        assertRejected(request.toBuilder().setDeadlineMs(f.clock.epochMillis() + PlayerBattleService.MAX_DEADLINE_AHEAD_MS + 1).build(),
                INVALID_PARAMETER, "invalid");
        assertRejected(request.toBuilder().setPrepareDeadlineMs(f.clock.epochMillis() + PlayerBattleService.MAX_DEADLINE_AHEAD_MS + 1)
                .build(), INVALID_PARAMETER, "invalid");
        assertRejected(request, FEATURE_UNAVAILABLE, "switching");
        WorldTestAccess.clearSwitch(player);
        WorldTestAccess.startResolving(player);
        assertRejected(request, FEATURE_UNAVAILABLE, "switching");
        WorldTestAccess.clearSwitch(player);
        assertRejected(request, FEATURE_UNAVAILABLE, "in_battle");
        assertRejected(request.toBuilder().setBattleId(99).build(), FEATURE_UNAVAILABLE, "in_battle");
        assertThat(player.battle().freeze()).as("被拒的备战不碰已有的冻结").isSameAs(other);
        BattleFixture.setFreeze(player, null);
        assertRejected(request, FEATURE_UNAVAILABLE, "not_ready");
        BattleFixture.setRecovery(player, Recovery.PENDING);
        assertRejected(request, FEATURE_UNAVAILABLE, "not_ready");
        BattleFixture.setRecovery(player, Recovery.READY);
        assertRejected(request, FEATURE_UNAVAILABLE, "dead");
        player.attributes().setHealth(1);
        assertRejected(request, SESSION_NOT_FOUND, "not_here");
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.calls()).as("一路被拒，一次 Redis 都没碰").isEmpty();
        f.routingAvailable = true;

        CompletableFuture<PrepareBattleResponse> accepted = f.battle.prepare(request);
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(accepted))).isZero();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK);
        assertThat(f.prepares("ok")).isEqualTo(1);
    }

    /** 一次被拒的备战：应答当场完成、码对、只有这一个计数取值加一、冻结与 Redis 调用都没有变化。 */
    private void assertRejected(PrepareBattleRequest request, int expectedTip, String expectedResult) {
        ScenePlayer player = f.world.playerById(request.getPlayerId());
        BattleFreeze freezeBefore = player == null ? null : player.battle().freeze();
        int callsBefore = f.locks.calls().size();
        Map<String, Double> before = prepareCounts();

        CompletableFuture<PrepareBattleResponse> reply = f.battle.prepare(request);

        assertThat(reply).as("被拒的备战当场应答").isCompleted();
        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(expectedTip);
        assertThat(BattleFixture.done(reply).hasSnapshot()).as("被拒不带快照").isFalse();
        Map<String, Double> after = prepareCounts();
        before.merge(expectedResult, 1.0, Double::sum);
        assertThat(after).as("只计 %s 一次", expectedResult).isEqualTo(before);
        if (player != null) {
            assertThat(player.battle().freeze()).isSameAs(freezeBefore);
        }
        assertThat(f.locks.calls()).hasSize(callsBefore);
    }

    private Map<String, Double> prepareCounts() {
        Map<String, Double> counts = new TreeMap<>();
        for (String result : List.of("ok", "invalid", "not_here", "switching", "in_battle", "not_ready", "dead", "lock_held",
                "redis_error", "stale", "cancelled")) {
            counts.put(result, f.prepares(result));
        }
        return counts;
    }

    @Test
    void 参数为0先于玩家不在本节点() {
        f.enter(SESSION, PLAYER);

        assertRejected(f.prepareRequest(2002, 0), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(2002, BATTLE).toBuilder().setDeadlineMs(0).build(), INVALID_PARAMETER, "invalid");
        assertRejected(f.prepareRequest(2002, BATTLE), ENTITY_NULL, "not_here");
    }

    /** 加载还没回来的玩家不在 playersById 里：与「不在本节点」同样回 1004；加载完成后同一份请求就能成功。 */
    @Test
    void 玩家还在加载_回1004_零Redis调用_加载完成后放行() {
        f.repo.put(new PlayerData(PLAYER, 1, 3, 1, "look-" + PLAYER, 1, f.scene1.configId(), new Vec3(5, 5, 0),
                PlayerState.getDefaultInstance(), "加载中"));
        f.world.onPlayerEnter(BattleFixture.LINK, PlayerEnter.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setSceneId(f.scene1.sceneId()).setOwnerEpoch(1).build());
        assertThat(f.repo.pendingLoads()).isEqualTo(1);
        assertThat(f.world.playerById(PLAYER)).isNull();

        assertRejected(f.prepareRequest(PLAYER, BATTLE), ENTITY_NULL, "not_here");
        assertThat(f.locks.calls()).isEmpty();

        f.repo.completeAll();
        f.drain();
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, BATTLE))).isZero();
    }

    @Test
    void 进场恢复待重试_回1006_reaper重读成功后放行() {
        f.locks.failNext(Op.ENTER_READ, new RuntimeException("Redis 超时"));
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);

        assertRejected(f.prepareRequest(PLAYER, BATTLE), FEATURE_UNAVAILABLE, "not_ready");

        f.reap();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, BATTLE))).isZero();
    }

    /**
     * D4（结算串行化）：上一局已应用、账本条目还没落盘时不许进下一局——此刻崩溃，上一局的奖励与账本一起丢，待结算记录要留给下一个持有者补应用，
     * 不能让下一局的锁把它顶掉。落盘（并销账）之后放行。
     */
    @Test
    void 账本里有未落盘的条目_回1006_落盘销账后放行() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, BATTLE);
        CompletableFuture<SceneBattleReply> settled = f.deliver(BattleFixture.settlement(PLAYER, BATTLE, 100).build());
        f.drain();
        assertThat(BattleFixture.done(settled).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(player.inBattle()).as("结算后已解冻").isFalse();
        assertThat(player.battleLedger().has(BATTLE)).isTrue();
        assertThat(BattleLedger.persistedHas(player.persistedState(), BATTLE)).as("在线存盘还挂着").isFalse();
        assertThat(f.repo.pendingProgress()).isEqualTo(1);

        assertRejected(f.prepareRequest(PLAYER, 8), FEATURE_UNAVAILABLE, "not_ready");

        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(player.battleLedger().has(BATTLE)).as("落盘后快路径销账，条目摘掉").isFalse();
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, 8))).isZero();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(8);
    }

    /** §7.5 第 1.5 步：已落盘、只是销账还没成功的条目不挡（锁要么还在、写锁自然被拒，要么已放、下一局覆盖不了已落盘的结果）。 */
    @Test
    void 账本条目已落盘只是销账一直失败_不挡备战() {
        f.locks.failAlways(Op.ACK, new RuntimeException("Redis 超时"));
        PlayerState saved = PlayerState.newBuilder().setBattleLedger(BattleLedgerState.newBuilder()
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(5).setAppliedAtMs(1))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, saved);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(player.battleLedger().has(5)).as("销账失败，条目留着").isTrue();
        assertThat(BattleLedger.persistedHas(player.persistedState(), 5)).isTrue();

        PrepareBattleResponse response = f.prepared(PLAYER, BATTLE);

        assertThat(BattleFixture.tipOf(response)).isZero();
        assertThat(player.battle().freeze().battleId()).isEqualTo(BATTLE);
        assertThat(player.battleLedger().has(5)).isTrue();
    }

    /**
     * §7.5 第 1 步的次序：账本那一条（第 1.5 步：损坏 / 有未落盘条目）排在 0 血（第 1.6 步）之前。两条都回 1006，靠计数取值区分先后
     * （「恢复未就绪先于 0 血」在 {@link #拒绝次序_全部条件同时成立时逐个放开_每次回最靠前的那一条} 里）。
     */
    @Test
    void 账本损坏且气血为0_回的是账本那一条_不是0血() {
        PlayerState corrupt = PlayerState.newBuilder().setBattleLedger(BattleLedgerState.newBuilder()
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(5).setAppliedAtMs(1))
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(5).setAppliedAtMs(2))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, corrupt);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        player.attributes().setHealth(0);

        assertRejected(f.prepareRequest(PLAYER, BATTLE), FEATURE_UNAVAILABLE, "not_ready");

        assertThat(f.prepares("dead")).isZero();
    }

    @Test
    void 账本有未落盘条目且气血为0_回的是账本那一条_落盘销账后才轮到0血() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, BATTLE);
        CompletableFuture<SceneBattleReply> settled = f.deliver(BattleFixture.settlement(PLAYER, BATTLE, 100).build());
        f.drain();
        assertThat(BattleFixture.done(settled).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(player.battleLedger().has(BATTLE)).isTrue();
        assertThat(BattleLedger.persistedHas(player.persistedState(), BATTLE)).isFalse();
        player.attributes().setHealth(0);

        assertRejected(f.prepareRequest(PLAYER, 8), FEATURE_UNAVAILABLE, "not_ready");
        assertThat(f.prepares("dead")).isZero();

        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(player.battleLedger().has(BATTLE)).isFalse();
        assertRejected(f.prepareRequest(PLAYER, 8), FEATURE_UNAVAILABLE, "dead");
        player.attributes().setHealth(1);
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, 8))).isZero();
    }

    /** 组快照的防御分支（基线 1011 kSessionNotFound）：会话链路不在登记表里。判定都过了才走到这里，所以仍是零冻结、零 Redis 调用。 */
    @Test
    void 会话链路取不到_回1011_零冻结零Redis调用_记ERROR() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.routingAvailable = false;

        assertRejected(f.prepareRequest(PLAYER, BATTLE), SESSION_NOT_FOUND, "not_here");

        assertThat(player.inBattle()).isFalse();
        assertThat(player.velocity().isOrigin()).isTrue();
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("组快照失败").contains("player=1001").contains("battle_id=7"));
    }

    // ------------------------------------------------------------------ 成功之后各闸立刻生效、旁观者看到停步

    /** 168 = SceneAttributeClientPlayer.AllocateAttributePoints，经真的客户端请求分发与属性服务的写前置。 */
    @Test
    void 成功_挂冻结后客户端的168立刻回25011_写锁还没回来就已挡住_取消解冻后恢复() {
        assertThat(Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "AllocateAttributePoints")).isEqualTo(168);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(allocateTip(player, 1)).as("没在战斗时照常加点（1 级有 5 点可分配）").isZero();
        assertThat(player.attributes().activeScheme().allocated(STRENGTH_DIMENSION)).isEqualTo(1);
        f.locks.hold(Op.PREPARE_LOCK);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);

        assertThat(reply).isNotDone();
        assertThat(allocateTip(player, 2)).as("冻结一挂上就挡，不等写锁").isEqualTo(ATTRIBUTE_IN_BATTLE);
        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();
        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isZero();
        assertThat(allocateTip(player, 2)).isEqualTo(ATTRIBUTE_IN_BATTLE);
        assertThat(player.attributes().activeScheme().allocated(STRENGTH_DIMENSION)).as("被挡的加点没有落下").isEqualTo(1);
        assertThat(f.count("xm.scene.battle.gate.rejects", "gate", "attribute")).isEqualTo(2);

        f.cancel(PLAYER, BATTLE);
        f.drain();
        assertThat(allocateTip(player, 2)).as("解冻后同一条请求成功").isZero();
        assertThat(player.attributes().activeScheme().allocated(STRENGTH_DIMENSION)).isEqualTo(2);
    }

    /** 角色属性池与其中的力量维度（正式配表：池 1、维度 103）。 */
    private static final int PLAYER_POOL = 1;
    private static final int STRENGTH_DIMENSION = 103;

    /** 发一条 168（把力量维度的目标已分配点设为 {@code points}），返回应答里的 error_message.id。 */
    private int allocateTip(ScenePlayer player, int points) {
        int messageId = Contracts.REGISTRY.requireId("SceneAttributeClientPlayer", "AllocateAttributePoints");
        long requestId = f.request(player, messageId, AllocateAttributePointsRequest.newBuilder().setPoolId(PLAYER_POOL)
                .putAllocated(STRENGTH_DIMENSION, points).build());
        MessageContent reply = f.pushes(player, messageId).stream().filter(m -> m.getId() == requestId).findFirst()
                .orElseThrow(() -> new AssertionError("168 没有应答 request_id=" + requestId));
        try {
            return AllocateAttributePointsResponse.parseFrom(reply.getSerializedMessage()).getErrorMessage().getId();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * D5：备战即停步。看得见他的人在下一个同步帧收到一条 66：只带 velocity、且是全零（「停了」），不带 transform；他自己不收
     * （66 不发给本人）；备战本身不给客户端推任何东西（结论只经 match 可见）。
     */
    @Test
    void 备战即停步_旁观者在下一个同步帧收到速度为0的66_本人什么都不收() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        ScenePlayer watcher = f.enter(SESSION + 1, 2002);
        f.world.step();
        f.world.step();
        WorldTestAccess.setVelocity(player, new Vec3(1, 0, 0));
        f.sink.clear();

        f.prepared(PLAYER, BATTLE);
        f.world.step();
        f.world.step();

        List<MessageContent> synced = f.pushes(watcher, Contracts.IDS.syncBaseAttribute());
        assertThat(synced).as("恰好一条 66").hasSize(1);
        ActorBaseAttributesS2C attributes = parseSync(synced.get(0));
        assertThat(attributes.getEntityId()).isEqualTo(PLAYER);
        assertThat(attributes.hasVelocity()).as("带 velocity 字段").isTrue();
        assertThat(attributes.getVelocity()).as("速度全零 = 停了").isEqualTo(Vec3.ORIGIN.toVelocity());
        assertThat(attributes.hasTransform()).as("没有位移，不带 transform").isFalse();
        assertThat(f.messageIds(player)).as("备战对本人不可见：不推 144、不推 66、什么都不推").isEmpty();
        assertThat(WorldTestAccess.velocityDirty(player)).as("发完清脏位").isFalse();

        f.world.step();
        f.world.step();
        assertThat(f.pushes(watcher, Contracts.IDS.syncBaseAttribute())).as("之后不再重复发").hasSize(1);
    }

    private static ActorBaseAttributesS2C parseSync(MessageContent content) {
        try {
            return ActorBaseAttributesS2C.parseFrom(content.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------------ 写锁结局的其余分支

    /** §7.2：同局但锁上已是 F（这一局已确认开战，备战请求是过期重发）→ 仍拒绝，不把在打的局改回备战期的字段与 TTL。 */
    @Test
    void 锁上是同一局但已确认开战_按被占拒绝_锁的字段与TTL都不动() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long fightDeadline = f.deadline() + 7_000;
        f.locks.putLock(PLAYER, BATTLE, 5, BattleRedis.STATE_FIGHTING, fightDeadline, 0, 360);

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        f.drain();

        assertThat(f.locks.last(Op.PREPARE_LOCK).lastReply()).as("脚本回现在的 b").isEqualTo("7");
        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(FEATURE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.lock(PLAYER)).containsEntry("b", "7").containsEntry("n", "5").containsEntry("s", "F")
                .containsEntry("d", String.valueOf(fightDeadline)).containsEntry("p", "0");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(360);
        assertThat(f.locks.ops()).as("不删锁").containsExactly(Op.PREPARE_LOCK);
        assertThat(f.prepares("lock_held")).isEqualTo(1);
    }

    /** Redis 根本连不上（脚本没执行）：同样回 1003、解冻、尽力删一次；那次删除也失败不影响应答，之后可以重新备战。 */
    @Test
    void 写锁连不上Redis_回1003_解冻_尽力删锁也失败不影响应答() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.failNext(Op.PREPARE_LOCK, new RuntimeException("连接断开"));
        f.locks.failNext(Op.DELETE_PREPARING, new RuntimeException("连接断开"));

        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        assertThat(reply).as("应答等写锁结局").isNotDone();
        assertThat(player.inBattle()).isTrue();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.last(Op.PREPARE_LOCK).executions()).as("脚本没执行过").isZero();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).as("解冻（锁按保留处理）当场补一次跟随；尽力删锁没有删到锁，不补第二次").containsExactly(PLAYER);
        assertThat(f.prepares("redis_error")).isEqualTo(1);

        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, BATTLE))).as("Redis 恢复后同一局可以重新备战").isZero();
    }

    /**
     * §7.8 第 0 步 + §7.5 第 4 步：写锁在途时同 epoch 重进（重连，换了会话）。新实例<b>不沿用</b>这个写锁还没回来的冻结
     * （沿用过来就是「没有锁、lockPending 永远为真」的孤儿）；旧实例的写锁回调核对实例失败，占到的锁删掉、回 1004。
     *
     * <p>评审 R2-REV-1 的同一条规则：新实例的恢复读排在写锁之前（读不到锁、不重建），但它进场那次组队跟随检查可能排在写锁之后、读到了这把刚写上的锁
     * 而放弃；旧回调把锁删掉（返回 1）之后给<b>现任实例</b>补一次，否则再没有触发点。
     */
    @Test
    void 写锁期间同epoch重进_新实例不带冻结_旧回调删锁回1004_删到了给新实例补一次跟随() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        assertThat(old.battle().freeze().lockPending()).isTrue();

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();

        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.ownerEpoch()).isEqualTo(old.ownerEpoch());
        assertThat(fresh.battle().freeze()).as("写锁在途的冻结不沿用").isNull();
        assertThat(fresh.battle().recovery()).as("恢复读时锁还没写上，照常就绪").isEqualTo(Recovery.READY);
        assertThat(f.rebuilds("carried", "rebuilt")).isZero();
        assertThat(reply).as("旧实例的应答仍在等写锁").isNotDone();

        f.locks.take(Op.PREPARE_LOCK).complete();
        assertThat(f.locks.lockBattleId(PLAYER)).as("写锁其实占到了").isEqualTo(BATTLE);
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.calls(Op.DELETE_PREPARING)).singleElement().satisfies(delete -> {
            assertThat(delete.battleId()).isEqualTo(BATTLE);
            assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        });
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(fresh.inBattle()).isFalse();
        assertThat(f.follows.freezeClearedPlayers).as("锁是这次删掉的：补给现任实例，不拿已被移除的旧实例去补").containsExactly(fresh);
        assertThat(f.rebuilds("login", "reverted")).as("新实例上没有冻结可摘").isZero();
        assertThat(f.reconnectHints(fresh)).isEmpty();
        assertThat(f.prepares("stale")).isEqualTo(1);
        assertThat(f.prepares("ok")).isZero();

        f.locks.release(Op.PREPARE_LOCK);
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, 8))).as("新实例可以正常备战下一局").isZero();
    }

    /**
     * 上一条的坏交错（第二轮修正 R2-a）：写锁在 Redis 上已经执行、回复还在路上时同 epoch 重进，新实例的恢复读看到了这把刚写上的备战锁 →
     * 按锁重建出 PREPARING 冻结（复核命中）；随后旧回调按「占到了就删」把锁删掉、回 1004。match 认为这人备战失败、不会对他发取消，
     * 所以删锁有了结局的那一刻，新实例上按这把锁重建的冻结要<b>一并摘掉</b>（与复核落空同一条撤销路径：{@code rebuilds{login, reverted}}、
     * 当场给新实例补一次组队跟随），不能留一把没有锁的冻结到备战期限。
     */
    @Test
    void 写锁期间同epoch重进_新实例的恢复读看到了刚写上的锁_旧回调删锁后新实例按锁重建的冻结一并摘掉() {
        f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK, Op.ENTER_READ, Op.DELETE_PREPARING);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        Call write = f.locks.take(Op.PREPARE_LOCK).execute();
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt).as("恢复读看到了备战锁，按锁重建").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        assertThat(rebuilt.preparedHere()).as("不是新实例自己备战的").isFalse();
        assertThat(rebuilt.lockPending()).isFalse();
        assertThat(f.rebuilds("login", "rebuilt")).as("复核命中：此刻锁还在").isEqualTo(1);
        f.follows.clear();

        write.reply();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.pending(Op.DELETE_PREPARING)).as("旧回调发了删除，还没有结局").hasSize(1);
        assertThat(fresh.battle().freeze()).as("删锁有结局之前不摘（锁此刻还在，冻结与锁仍然一致）").isSameAs(rebuilt);
        assertThat(f.follows.freezeCleared).isEmpty();

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(f.locks.lock(PLAYER)).as("旧回调把占到的锁删了").isNull();
        assertThat(fresh.inBattle()).as("锁删掉的同时，新实例上按它重建的备战冻结一并摘掉").isFalse();
        assertThat(f.attributes.createScheme(fresh, "解冻了").tipId()).as("在途闸当场放开").isNotEqualTo(ATTRIBUTE_IN_BATTLE);
        assertThat(f.rebuilds("login", "reverted")).as("按撤销重建计数").isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).as("给现任实例补一次组队跟随（不是已被移除的旧实例）").containsExactly(fresh);
        assertThat(f.count("xm.scene.battle.freeze.expired", "phase", "preparing")).as("不是等 reaper 按期限摘的").isZero();
        assertThat(f.locks.ops()).as("摘冻结不再发第二条删除").containsExactly(Op.PREPARE_LOCK, Op.ENTER_READ, Op.TOUCH, Op.DELETE_PREPARING);
        assertThat(f.reconnectHints(fresh)).as("PREPARING 不推 144").isEmpty();
        assertThat(f.prepares("stale")).isEqualTo(1);

        f.locks.release();
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, 8))).as("不必等备战期限，立刻可以备战下一局").isZero();
    }

    /**
     * R2-a 的「结局不明」一支：旧回调的那条删除以出错 / 超时完成（可能删了、可能没删）。这一局对 match 而言照样已经作废（备战回了 1004），
     * 新实例上按锁重建的冻结照样摘掉；锁若真的还在，留给备战 TTL，补的那次跟随读到锁自己放弃。
     */
    @Test
    void 写锁期间同epoch重进_旧回调的删锁结局不明_新实例按锁重建的冻结照样摘掉_锁留给TTL() {
        f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK, Op.ENTER_READ);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        Call write = f.locks.take(Op.PREPARE_LOCK).execute();
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(fresh.battle().freeze()).isNotNull();
        f.locks.failNext(Op.DELETE_PREPARING, new RuntimeException("Redis 超时"));
        f.follows.clear();

        write.reply();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.last(Op.DELETE_PREPARING).executions()).as("这条删除没有执行").isZero();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁还在，等备战 TTL").isEqualTo(BATTLE);
        assertThat(fresh.inBattle()).as("结局不明也摘：这一局不会再有确认，也不会再有取消").isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
        assertThat(f.locks.count(Op.DELETE_PREPARING)).as("不重试删除").isEqualTo(1);
    }

    /**
     * R2-a 的守卫——<b>现任实例自己备战的冻结不摘</b>（{@code preparedHere}）：旧实例的写锁在途时重进，新实例自己又收到同一局的备战
     * （它的写锁同样在途）。旧回调删掉的是它自己占到的那把锁；新实例的冻结对应的是新实例自己那次写锁，由它自己的回调收尾。
     */
    @Test
    void 写锁期间同epoch重进_新实例自己又备战了同一局_旧回调的删锁不摘新实例自己备战的冻结() {
        f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> first = f.prepare(PLAYER, BATTLE);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        assertThat(fresh.battle().freeze()).as("恢复读时锁还没写上：没有重建").isNull();
        CompletableFuture<PrepareBattleResponse> second = f.prepare(PLAYER, BATTLE);
        BattleFreeze own = fresh.battle().freeze();
        assertThat(own.preparedHere()).isTrue();
        List<Call> writes = f.locks.pending(Op.PREPARE_LOCK);
        assertThat(writes).hasSize(2);
        f.follows.clear();

        writes.get(0).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(first))).as("旧实例那次备战：实例已换").isEqualTo(ENTITY_NULL);
        assertThat(f.locks.last(Op.DELETE_PREPARING).lastReply()).as("旧回调删掉了它占到的锁").isEqualTo(BattleRedis.PREPARING_DELETE_DONE);
        assertThat(fresh.battle().freeze()).as("新实例自己备战的冻结不是按那把锁重建的：不摘").isSameAs(own);
        assertThat(own.phase()).isEqualTo(Phase.PREPARING);
        assertThat(own.lockPending()).isTrue();
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.follows.freezeCleared).isEmpty();
        assertThat(second).isNotDone();

        writes.get(1).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(second))).as("新实例自己那次写锁照常成功").isZero();
        assertThat(fresh.battle().freeze()).isSameAs(own);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(BATTLE);
    }

    /**
     * R2-a 同一条规则用在「写锁出错后的尽力删锁」上：写锁其实执行了、只是回复丢了 → 回 1003、解冻、发尽力删锁；这条删除在途时同 epoch 重进，
     * 新实例的恢复读看到了那把残留的备战锁、按它重建——删除有了结局后同样一并摘掉。
     */
    @Test
    void 写锁出错后的尽力删锁在途时同epoch重进_新实例按残留锁重建的冻结在删锁后摘掉() {
        f.enter(SESSION, PLAYER);
        f.locks.hold(Op.DELETE_PREPARING);
        f.locks.failNextAfterExecuting(Op.PREPARE_LOCK, new RuntimeException("Redis 超时"));
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        f.drain();
        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(SERVICE_UNAVAILABLE);
        assertThat(f.locks.lockBattleId(PLAYER)).as("脚本已执行，锁残留；尽力删锁还在途").isEqualTo(BATTLE);

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt).as("新实例按残留的备战锁重建").isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.PREPARING);
        f.follows.clear();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(fresh.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
    }

    /**
     * R2-a 同一条规则用在「备战回调抛异常后的收尾删锁」上（{@code abandonPrepare}）：过期分支里发删除那一步同步抛出 → 应答异常完成，
     * 收尾再尽力删一次；这次删除删掉锁之后，新实例上按它重建的冻结同样摘掉。
     */
    @Test
    void 写锁期间同epoch重进_旧回调发删除时抛异常_收尾的删锁同样摘掉新实例按锁重建的冻结() {
        f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK, Op.ENTER_READ);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        Call write = f.locks.take(Op.PREPARE_LOCK).execute();
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(fresh.battle().freeze()).isNotNull();
        RuntimeException boom = new IllegalStateException("Redisson 已关闭");
        f.locks.throwNext(Op.DELETE_PREPARING, boom);
        f.follows.clear();

        write.reply();
        f.drain();

        assertThat(reply).isCompletedExceptionally();
        assertThatThrownBy(() -> BattleFixture.done(reply)).isInstanceOf(CompletionException.class).hasCause(boom);
        assertThat(f.locks.lock(PLAYER)).as("收尾补的那次删除把锁删了").isNull();
        assertThat(fresh.inBattle()).isFalse();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
    }

    /**
     * 写锁在途时备战期限到了、reaper 把冻结摘掉（锁按保留处理）：写锁回来时冻结已不是这个 → 回 1004（match 认为这人没冻结成功，
     * 不会对他发取消），占到的锁要删掉，否则留到备战 TTL。
     */
    @Test
    void 写锁期间备战到期被reaper摘掉冻结_写锁回来后回1004并删掉这把备战锁() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> reply = f.prepare(PLAYER, BATTLE);
        f.advance(BattleFixture.PREPARE_MILLIS + 1);

        f.reap();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.count("xm.scene.battle.freeze.expired", "phase", "preparing")).isEqualTo(1);
        assertThat(reply).isNotDone();
        assertThat(f.locks.count(Op.DELETE_PREPARING) + f.locks.count(Op.DELETE_IF_MATCH)).as("备战到期只解冻，不删锁").isZero();

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(reply))).isEqualTo(ENTITY_NULL);
        assertThat(f.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.follows.freezeClearedPlayers).as("备战到期解冻当场一次 + 占到的锁确实删掉后一次（评审 R2-REV-1）").containsExactly(player, player);
        assertThat(f.prepares("stale")).isEqualTo(1);
    }

    private void leave() {
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();
    }
}
