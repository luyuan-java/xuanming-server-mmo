package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 开局确认 {@code confirmBattle} 与迟到重建（scene-battle-spec §7.7 的表、§13.2；基线 {@code ConfirmBattle} {@code pb.cpp:1312-1438}、
 * {@code RebuildBattleFreezeFromLock} {@code :1440-1525}）：
 * <ul>
 *   <li>PREPARING → FIGHTING：期限取事件值（0 时沿用），条件续期（标 F、写正式期限、续 TTL），成功才记 {@code lockExtended}（D9）；</li>
 *   <li>battle_id 不符忽略；已 FIGHTING 的重投：续期确认过就零 Redis，没确认过就再续一次；</li>
 *   <li>玩家不在本节点：一段 CONFIRM（事件不带期限就跳过）；交出冻结中：只续锁、不挂冻结；</li>
 *   <li>在线、没有冻结：账本命中 → 销账不重建；否则一段 CONFIRM，回来后核对实例 / 冻结 / 账本 / 交出冻结 → 重建 FIGHTING、推 144；</li>
 *   <li>144：本实例备战的冻结确认时不推；按锁重建 / 沿用旧实例的 PREPARING 在确认时推且只推一次；PREPARING 状态下任何路径都不推。</li>
 * </ul>
 * 审计修掉的几处交错（重建复核在途时确认到达、恢复读在途时确认到达、迟到确认不带期限、取消后紧跟确认、已销账的局的过期确认）各在
 * {@link BattleRebuildRegressionTest}、{@link BattleLockDeletionRegressionTest}、{@link BattleStaleReadTest}，这里不重复。
 */
class ConfirmBattleTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    /** 不在本节点的玩家。 */
    private static final long ABSENT = 2002;
    private static final long X = 7;
    private static final long Y = 8;
    private static final int NODE = BattleFixture.BATTLE_NODE;
    /** 备战锁的 TTL（备战时长 60 s + 余量 60 s）与正式期限的 TTL（战斗时长 300 s + 60 s）。 */
    private static final long PREPARE_TTL = BattleFixture.PREPARE_MILLIS / 1000 + BattleRedis.LOCK_EXTRA_TTL_SEC;
    private static final long FIGHT_TTL = BattleFixture.BATTLE_MILLIS / 1000 + BattleRedis.LOCK_EXTRA_TTL_SEC;
    private static final List<String> RESULTS = List.of("upgraded", "idempotent", "reextended", "mismatch", "rebuilt",
            "frozen_extended", "offline_extended", "offline_miss", "ledger_hit", "invalid", "error");
    private static final List<String> HINT_TRIGGERS = List.of("confirm", "late_confirm", "login", "carried");

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

    /** 确认计数的全貌：只列非 0 的取值。 */
    private Map<String, Double> confirmCounts() {
        Map<String, Double> counts = new TreeMap<>();
        for (String result : RESULTS) {
            double count = f.confirms(result);
            if (count != 0) {
                counts.put(result, count);
            }
        }
        return counts;
    }

    private double hintsTotal() {
        return HINT_TRIGGERS.stream().mapToDouble(f::hints).sum();
    }

    private static PlayerState ledgerOf(long... battleIds) {
        BattleLedgerState.Builder ledger = BattleLedgerState.newBuilder();
        for (long id : battleIds) {
            ledger.addApplied(BattleLedgerEntry.newBuilder().setBattleId(id).setAppliedAtMs(1));
        }
        return PlayerState.newBuilder().setBattleLedger(ledger).build();
    }

    // ------------------------------------------------------------------ 参数

    @Test
    void 参数非法_丢弃_零Redis调用_记ERROR() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();

        f.confirm(0, X, f.deadline());
        f.confirm(PLAYER, 0, f.deadline());
        f.drain();

        assertThat(f.locks.calls()).isEmpty();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(confirmCounts()).containsExactly(Map.entry("invalid", 2.0));
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("确认事件参数非法"))
                .hasSize(2);
    }

    // ------------------------------------------------------------------ PREPARING → FIGHTING：升级与续期

    /**
     * 升级当场生效（阶段、期限取事件值）；续期是一段 CONFIRM(X, d, ttl(d))，TTL 按<b>确认那一刻</b>的时钟算；{@code lockExtended} 要等续期成功回来才置真。
     * 本实例备战的冻结（preparedHere）确认时不推 144——客户端已经从 match 拿到开战通知。
     */
    @Test
    void 升级与续期_期限取事件值_续期成功后才记已续期_本实例备战的不推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        long prepareDeadline = freeze.prepareDeadlineMs();
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(PREPARE_TTL);
        f.locks.clearCalls();
        f.advance(10_000);
        long deadline = f.clock.epochMillis() + 250_000;
        f.locks.hold(Op.CONFIRM);

        f.confirm(PLAYER, X, deadline);

        assertThat(player.battle().freeze()).as("升级的是同一个冻结对象").isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(freeze.prepareDeadlineMs()).as("备战期限不动").isEqualTo(prepareDeadline);
        assertThat(freeze.effectiveDeadlineMs()).as("FIGHTING 的有效期限看战斗期限").isEqualTo(deadline);
        assertThat(freeze.lockExtended()).as("续期结局还没回来").isFalse();
        Call extend = f.locks.take(Op.CONFIRM);
        assertThat(extend.playerId()).isEqualTo(PLAYER);
        assertThat(extend.battleId()).isEqualTo(X);
        assertThat(extend.longArg("deadline")).isEqualTo(deadline);
        assertThat(extend.longArg("ttl")).as("(d − now) / 1000 + 60").isEqualTo(250 + 60);
        assertThat(f.locks.lockState(PLAYER)).as("脚本还没执行").isEqualTo("P");

        extend.complete();
        assertThat(freeze.lockExtended()).as("结局还没回到逻辑线程").isFalse();
        f.drain();

        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.locks.lock(PLAYER)).containsEntry("b", "7").containsEntry("n", String.valueOf(NODE)).containsEntry("s", "F")
                .containsEntry("d", String.valueOf(deadline)).containsEntry("p", String.valueOf(prepareDeadline));
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(310);
        assertThat(f.locks.ops()).as("只有一段续期").containsExactly(Op.CONFIRM);
        assertThat(f.messageIds(player)).as("本实例备战的冻结：确认不给客户端推任何东西").isEmpty();
        assertThat(hintsTotal()).isZero();
        assertThat(confirmCounts()).containsExactly(Map.entry("upgraded", 1.0));
    }

    @Test
    void 事件期限为0_沿用冻结里的期限_续期按它算() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long deadline = f.deadline();
        f.battle.prepare(f.prepareRequest(PLAYER, X, deadline, f.prepareDeadline()));
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        f.locks.clearCalls();

        f.confirm(PLAYER, X, 0);
        f.drain();

        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).as("沿用备战时给的战斗期限").isEqualTo(deadline);
        Call extend = f.locks.last(Op.CONFIRM);
        assertThat(extend.longArg("deadline")).isEqualTo(deadline);
        assertThat(extend.longArg("ttl")).isEqualTo(FIGHT_TTL);
        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lock(PLAYER)).containsEntry("d", String.valueOf(deadline));
        assertThat(f.locks.lockTtlSec(PLAYER)).as("从备战 TTL 续到正式期限 + 60").isEqualTo(FIGHT_TTL);
    }

    /** 期限已经过了的确认（钟差 / 极迟到）：照常升级，锁的 TTL 取 60 s 余量（{@code lockTtlSec}：期限已过 → 60）。 */
    @Test
    void 事件期限已过_照常升级_锁TTL取60秒余量() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        long past = f.clock.epochMillis() - 1;

        f.confirm(PLAYER, X, past);
        f.drain();

        assertThat(player.battle().freeze().deadlineMs()).isEqualTo(past);
        assertThat(f.locks.last(Op.CONFIRM).longArg("ttl")).isEqualTo(BattleRedis.LOCK_EXTRA_TTL_SEC);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(60);
    }

    @Test
    void battle_id不符_忽略_冻结不升级_零Redis调用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        BattleFreeze freeze = player.battle().freeze();
        long deadline = freeze.deadlineMs();
        f.locks.clearCalls();

        f.confirm(PLAYER, Y, deadline + 99_000);
        f.drain();

        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("P");
        assertThat(confirmCounts()).containsExactly(Map.entry("mismatch", 1.0));
    }

    /** 重投 / 补发（首发后每 10 s 一次，共 17 次）：续期已确认成功 → 零 Redis 的幂等；重投里带的期限不再改冻结。 */
    @Test
    void 重投幂等_续期成功后零Redis_不改期限_不推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        long deadline = f.deadline();
        f.confirm(PLAYER, X, deadline);
        f.drain();
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.lockExtended()).isTrue();
        f.locks.clearCalls();

        f.confirm(PLAYER, X, deadline);
        f.confirm(PLAYER, X, deadline + 50_000);
        f.confirm(PLAYER, X, 0);
        f.drain();

        assertThat(f.locks.calls()).as("续期确认过了，重投不碰 Redis").isEmpty();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(freeze.deadlineMs()).as("已不是 PREPARING：重投不再改期限").isEqualTo(deadline);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHT_TTL);
        assertThat(hintsTotal()).isZero();
        assertThat(confirmCounts()).containsExactly(Map.entry("idempotent", 3.0), Map.entry("upgraded", 1.0));
    }

    // ------------------------------------------------------------------ D9：续期失败后重投再续

    /**
     * D9：升级时的续期出错（锁还停在备战 TTL，战斗没打完锁先过期）→ {@code lockExtended} 保持假 → 下一次补发确认再续一次；
     * 成功后的重投零 Redis。
     */
    @Test
    void 续期出错_冻结照样升级_下一次确认再续一次_成功后零Redis() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        long deadline = f.deadline();
        f.locks.clearCalls();
        f.locks.failNext(Op.CONFIRM, new RuntimeException("Redis 超时"));

        f.confirm(PLAYER, X, deadline);
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).as("内存照样升级（权威在内存）").isEqualTo(Phase.FIGHTING);
        assertThat(freeze.lockExtended()).isFalse();
        assertThat(f.locks.lockState(PLAYER)).as("锁还停在备战期").isEqualTo("P");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(PREPARE_TTL);
        assertThat(confirmCounts()).containsExactly(Map.entry("error", 1.0), Map.entry("upgraded", 1.0));

        f.locks.clearCalls();
        f.confirm(PLAYER, X, deadline + 30_000);
        f.drain();

        assertThat(f.locks.ops()).as("再续一次").containsExactly(Op.CONFIRM);
        Call again = f.locks.last(Op.CONFIRM);
        assertThat(again.longArg("deadline")).as("续的是冻结里的期限，不是重投里带的").isEqualTo(deadline);
        assertThat(again.longArg("ttl")).isEqualTo(FIGHT_TTL);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHT_TTL);
        assertThat(confirmCounts()).containsExactly(Map.entry("error", 1.0), Map.entry("reextended", 1.0), Map.entry("upgraded", 1.0));

        f.locks.clearCalls();
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(f.locks.calls()).as("续期成功后的重投零 Redis").isEmpty();
        assertThat(f.confirms("idempotent")).isEqualTo(1);
        assertThat(hintsTotal()).isZero();
    }

    /** 续期脚本执行了、只是回复丢了：锁其实已续，但 scene 没拿到确认，下一次重投仍会再续一次（CONFIRM 幂等），之后才零 Redis。 */
    @Test
    void 续期已执行但回复丢了_下一次确认再续一次_脚本幂等() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        long deadline = f.deadline();
        f.locks.failNextAfterExecuting(Op.CONFIRM, new RuntimeException("Redis 超时"));

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(player.battle().freeze().lockExtended()).isFalse();

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(f.locks.count(Op.CONFIRM)).isEqualTo(2);
        assertThat(player.battle().freeze().lockExtended()).isTrue();
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHT_TTL);
    }

    /** 续期返回 nil（锁已不在 / 易主）：不记已续期，每次重投都会再试；冻结保留（由结算或 reaper 收尾）。 */
    @Test
    void 续期返回nil_锁已不在_不记已续期_重投每次再试() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.removeLock(PLAYER);
        f.locks.clearCalls();
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.lockExtended()).isFalse();
        assertThat(f.locks.last(Op.CONFIRM).lastReply()).isNull();
        assertThat(f.locks.lock(PLAYER)).as("CONFIRM 不会凭空造锁").isNull();

        f.confirm(PLAYER, X, deadline);
        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(f.locks.count(Op.CONFIRM)).isEqualTo(3);
        assertThat(freeze.lockExtended()).isFalse();
        assertThat(confirmCounts()).containsExactly(Map.entry("reextended", 2.0), Map.entry("upgraded", 1.0));
    }

    /** 续期在途时又来一次确认：还没确认成功，照样再发一条；两条都成功，最终只是记一次已续期。 */
    @Test
    void 续期在途时重投_再发一条_都回来后记已续期() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.hold(Op.CONFIRM);
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.confirm(PLAYER, X, deadline);

        assertThat(f.locks.pending(Op.CONFIRM)).hasSize(2);
        f.locks.completeAll();
        f.drain();
        assertThat(player.battle().freeze().lockExtended()).isTrue();
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHT_TTL);
        assertThat(confirmCounts()).containsExactly(Map.entry("reextended", 1.0), Map.entry("upgraded", 1.0));
    }

    /** 续期的结局回来时这个冻结已经不在了（结算先到、已解冻）：不把已续期记到别的冻结上，也不重新挂冻结。 */
    @Test
    void 续期回来时冻结已被结算摘掉_不动任何状态() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.CONFIRM);
        f.confirm(PLAYER, X, f.deadline());
        BattleFreeze freeze = player.battle().freeze();
        CompletableFuture<SceneBattleReply> settled = f.deliver(BattleFixture.settlement(PLAYER, X, 10).build());
        assertThat(BattleFixture.done(settled).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(player.inBattle()).isFalse();

        f.locks.take(Op.CONFIRM).complete();
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(freeze.lockExtended()).as("旧冻结对象不再被改").isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
    }

    // ------------------------------------------------------------------ 玩家不在本节点：一段 CONFIRM

    @Test
    void 离线_一段CONFIRM_命中即标F写期限续TTL_计offline_extended() {
        long prepareDeadline = f.prepareDeadline();
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), prepareDeadline, PREPARE_TTL);
        long deadline = f.deadline() + 5_000;

        f.confirm(ABSENT, X, deadline);
        Call script = f.locks.last(Op.CONFIRM);
        f.drain();

        assertThat(script.playerId()).isEqualTo(ABSENT);
        assertThat(script.battleId()).isEqualTo(X);
        assertThat(script.longArg("deadline")).isEqualTo(deadline);
        assertThat(script.longArg("ttl")).isEqualTo(305 + 60);
        assertThat(f.locks.ops()).as("一次往返").containsExactly(Op.CONFIRM);
        assertThat(f.locks.lock(ABSENT)).containsEntry("s", "F").containsEntry("d", String.valueOf(deadline))
                .containsEntry("n", String.valueOf(NODE)).containsEntry("p", String.valueOf(prepareDeadline));
        assertThat(f.locks.lockTtlSec(ABSENT)).isEqualTo(365);
        assertThat(confirmCounts()).containsExactly(Map.entry("offline_extended", 1.0));
    }

    @Test
    void 离线_事件不带期限_跳过_零Redis调用() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);

        f.confirm(ABSENT, X, 0);
        f.drain();

        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.locks.lockState(ABSENT)).isEqualTo("P");
        assertThat(confirmCounts()).containsExactly(Map.entry("offline_miss", 1.0));
    }

    @Test
    void 离线_锁不在或已是别的局_不动_计offline_miss() {
        f.confirm(ABSENT, X, f.deadline());
        f.drain();
        assertThat(f.locks.last(Op.CONFIRM).lastReply()).isNull();
        assertThat(f.locks.lock(ABSENT)).isNull();

        f.locks.putLock(ABSENT, Y, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.confirm(ABSENT, X, f.deadline());
        f.drain();

        assertThat(f.locks.lockBattleId(ABSENT)).isEqualTo(Y);
        assertThat(f.locks.lockState(ABSENT)).as("别的局的锁不被标 F").isEqualTo("P");
        assertThat(f.locks.lockTtlSec(ABSENT)).isEqualTo(PREPARE_TTL);
        assertThat(confirmCounts()).containsExactly(Map.entry("offline_miss", 2.0));
    }

    @Test
    void 离线_脚本出错_计error_不重试() {
        f.locks.putLock(ABSENT, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.locks.failNext(Op.CONFIRM, new RuntimeException("Redis 超时"));

        f.confirm(ABSENT, X, f.deadline());
        f.drain();

        assertThat(f.locks.ops()).containsExactly(Op.CONFIRM);
        assertThat(f.locks.lockState(ABSENT)).isEqualTo("P");
        assertThat(confirmCounts()).containsExactly(Map.entry("error", 1.0));
    }

    // ------------------------------------------------------------------ 交出冻结中：只续锁、不挂冻结

    /**
     * 两种冻结互斥：玩家在 5.2 的交出冻结里时，确认只把锁标 F 并续期，不在这个实例上挂战斗冻结、不推 144——
     * 交出提交后目标节点的进场恢复据锁重建，没提交则原地解冻后重跑进场恢复（见 BattleUnfreezeRecoveryTest）。
     */
    @Test
    void 交出冻结中_只续锁不挂冻结_不推144_计frozen_extended() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.freezeForHandOff(player);
        f.sink.clear();
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(f.locks.ops()).containsExactly(Op.CONFIRM);
        assertThat(f.locks.last(Op.CONFIRM).args()).containsEntry("deadline", deadline).containsEntry("ttl", FIGHT_TTL);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHT_TTL);
        assertThat(player.inBattle()).as("不挂战斗冻结").isFalse();
        assertThat(player.frozen()).isTrue();
        assertThat(f.messageIds(player)).as("不推 144").isEmpty();
        assertThat(confirmCounts()).containsExactly(Map.entry("frozen_extended", 1.0));
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isZero();
    }

    @Test
    void 交出冻结中_锁不是这一局_计offline_miss_出错计error() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.freezeForHandOff(player);

        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(confirmCounts()).containsExactly(Map.entry("offline_miss", 1.0));

        f.locks.failNext(Op.CONFIRM, new RuntimeException("Redis 超时"));
        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(confirmCounts()).containsExactly(Map.entry("error", 1.0), Map.entry("offline_miss", 1.0));
        assertThat(player.inBattle()).isFalse();
    }

    /**
     * 交出冻结中、事件没带期限（{@code deadline_ms = 0}）：与「玩家不在本节点」不同，这里<b>不跳过</b>——CONFIRM 以「不改期限、不续 TTL」的参数
     * （0 / 0）发出，只把锁标成 F。标了 F，随后的取消就删不掉它；目标节点的进场恢复（或原地解冻后重跑的恢复）按 F 重建 FIGHTING，
     * 复核时再把 TTL 续到正式期限。
     */
    @Test
    void 交出冻结中_事件不带期限_只把锁标F_期限与TTL都不动() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long lockDeadline = f.deadline();
        long prepareDeadline = f.prepareDeadline();
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, lockDeadline, prepareDeadline, PREPARE_TTL);
        f.freezeForHandOff(player);
        f.sink.clear();

        f.confirm(PLAYER, X, 0);
        f.drain();

        assertThat(f.locks.ops()).containsExactly(Op.CONFIRM);
        assertThat(f.locks.last(Op.CONFIRM).args()).as("0 = 不改").containsEntry("deadline", 0L).containsEntry("ttl", 0L);
        assertThat(f.locks.lock(PLAYER)).containsEntry("b", "7").containsEntry("s", "F")
                .containsEntry("d", String.valueOf(lockDeadline)).containsEntry("p", String.valueOf(prepareDeadline));
        assertThat(f.locks.lockTtlSec(PLAYER)).as("没带期限就不续 TTL").isEqualTo(PREPARE_TTL);
        assertThat(player.inBattle()).isFalse();
        assertThat(player.frozen()).isTrue();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(confirmCounts()).containsExactly(Map.entry("frozen_extended", 1.0));
    }

    // ------------------------------------------------------------------ 在线、没有冻结：迟到重建

    /**
     * 备战到期被 reaper 摘了冻结（锁留到 TTL），确认才到：一段 CONFIRM 核对锁并标 F，回来后重建 FIGHTING——节点号与备战期限取锁里的，
     * 战斗期限取事件值，续期视为已确认；停步；推 144（客户端不知道自己又被冻进了战斗）。
     */
    @Test
    void 迟到确认_按锁重建FIGHTING_节点号与备战期限取锁_期限取事件值_停步_推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long prepareDeadline = f.prepareDeadline();
        f.locks.putLock(PLAYER, X, 33, BattleRedis.STATE_PREPARING, f.deadline(), prepareDeadline, PREPARE_TTL);
        WorldTestAccess.setVelocity(player, new Vec3(1, 0, 0));
        long deadline = f.deadline() + 5_000;
        f.locks.hold(Op.CONFIRM);

        f.confirm(PLAYER, X, deadline);

        assertThat(player.inBattle()).as("CONFIRM 没回来之前不挂冻结").isFalse();
        Call script = f.locks.take(Op.CONFIRM);
        assertThat(script.longArg("deadline")).isEqualTo(deadline);
        assertThat(script.longArg("ttl")).isEqualTo(365);
        script.complete();
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).isNotNull();
        assertThat(freeze.battleId()).isEqualTo(X);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.battleNodeId()).as("n 取锁").isEqualTo(33);
        assertThat(freeze.prepareDeadlineMs()).as("p 取锁").isEqualTo(prepareDeadline);
        assertThat(freeze.deadlineMs()).as("期限取事件值").isEqualTo(deadline);
        assertThat(freeze.lockExtended()).as("这段 CONFIRM 已续到正式期限").isTrue();
        assertThat(freeze.lockPending()).isFalse();
        assertThat(freeze.rescuing()).isFalse();
        assertThat(player.velocity().isOrigin()).as("挂冻结即停步").isTrue();
        assertThat(WorldTestAccess.velocityDirty(player)).isTrue();
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(365);
        assertThat(f.locks.ops()).as("一段 Lua，不做第二次复核（D10）").containsExactly(Op.CONFIRM);
        assertThat(confirmCounts()).containsExactly(Map.entry("rebuilt", 1.0));
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isEqualTo(1);
        assertThat(f.hints("late_confirm")).isEqualTo(1);
        assertThat(hintsTotal()).isEqualTo(1);

        // 之后的补发：零 Redis、不再推
        f.locks.clearCalls();
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.reconnectHints(player)).hasSize(1);
    }

    /**
     * 重建出来的战斗期限以<b>事件值</b>为准（§7.7），不取决于脚本回复里的 {@code d}：这里让回复带的是续期<b>之前</b>的字段
     * （{@code d} 还是备战时写的那个），冻结仍取事件值；节点号与备战期限照旧取回复里的。
     */
    @Test
    void 迟到确认_期限以事件值为准_不取回复里的d() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long lockDeadline = f.deadline();
        long prepareDeadline = f.prepareDeadline();
        f.locks.putLock(PLAYER, X, 33, BattleRedis.STATE_PREPARING, lockDeadline, prepareDeadline, PREPARE_TTL);
        Map<String, String> staleFields = new TreeMap<>(f.locks.lock(PLAYER));
        staleFields.put(BattleRedis.FIELD_STATE, BattleRedis.STATE_FIGHTING);
        long deadline = lockDeadline + 5_000;
        f.locks.hold(Op.CONFIRM);

        f.confirm(PLAYER, X, deadline);
        f.locks.take(Op.CONFIRM).execute().replyWith(staleFields);
        f.drain();

        assertThat(staleFields).containsEntry(BattleRedis.FIELD_DEADLINE, String.valueOf(lockDeadline));
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).as("事件带了期限就用事件的").isEqualTo(deadline);
        assertThat(freeze.battleNodeId()).isEqualTo(33);
        assertThat(freeze.prepareDeadlineMs()).isEqualTo(prepareDeadline);
        assertThat(freeze.lockExtended()).isTrue();
        assertThat(f.locks.lock(PLAYER)).as("脚本确实把 d 续成了事件值").containsEntry("d", String.valueOf(deadline));
    }

    @Test
    void 迟到确认_锁不在或已是别的局_不重建_不推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.rebuilds("late_confirm", "miss")).isEqualTo(1);

        f.locks.putLock(PLAYER, Y, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.lockState(PLAYER)).as("别的局的锁不被标 F").isEqualTo("P");
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(confirmCounts()).containsExactly(Map.entry("mismatch", 2.0));
        assertThat(f.rebuilds("late_confirm", "miss")).isEqualTo(2);
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isZero();
    }

    @Test
    void 迟到确认_脚本出错_不重建_计error() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.locks.failNext(Op.CONFIRM, new RuntimeException("Redis 超时"));

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(confirmCounts()).containsExactly(Map.entry("error", 1.0));
        assertThat(f.rebuilds("late_confirm", "error")).isEqualTo(1);

        // 下一次补发照常重建
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);
    }

    @Test
    void 迟到确认_回来时实例已换_不重建() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.locks.hold(Op.CONFIRM);
        f.confirm(PLAYER, X, f.deadline());
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());

        f.locks.take(Op.CONFIRM).complete();
        f.drain();

        assertThat(player.inBattle()).as("已离场的实例不挂冻结").isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.locks.lockState(PLAYER)).as("锁已标 F：他重新进场时据此重建").isEqualTo("F");
        assertThat(confirmCounts()).containsExactly(Map.entry("idempotent", 1.0));
        assertThat(f.rebuilds("late_confirm", "miss")).isEqualTo(1);
    }

    /** 两种冻结互斥：CONFIRM 在途期间玩家进了交出冻结 → 回来后不挂战斗冻结（锁已标 F，目标节点 / 原地解冻后的进场恢复会重建）。 */
    @Test
    void 迟到确认_回来时已在交出冻结_不重建_不推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.locks.hold(Op.CONFIRM);
        f.confirm(PLAYER, X, f.deadline());
        f.freezeForHandOff(player);
        f.sink.clear();

        f.locks.take(Op.CONFIRM).complete();
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(player.frozen()).isTrue();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.rebuilds("late_confirm", "miss")).isEqualTo(1);
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isZero();
    }

    /** 选目标中（RESOLVING）不冻结：迟到确认照常重建（随后选中远端时由换图那边复查、中止换图）。 */
    @Test
    void 迟到确认_选目标中照常重建() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.resolveRemote(player);

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);
    }

    /**
     * 第一条迟到确认的 CONFIRM 还没回来，补发的第二条又到了（玩家此刻仍没有冻结，同样发一段 CONFIRM）：第一条回来时重建，
     * 第二条回来时已有同局的 FIGHTING 冻结 → 没有可做的事：不换冻结对象、不改期限、不再推 144。
     */
    @Test
    void 迟到确认_两条CONFIRM都在途_只重建一次_第二条回来时已是同局FIGHTING_144只推一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.locks.hold(Op.CONFIRM);
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.confirm(PLAYER, X, deadline + 20_000);

        assertThat(f.locks.pending(Op.CONFIRM)).as("没有冻结时每条确认各发一段").hasSize(2);
        assertThat(player.inBattle()).isFalse();
        f.locks.take(Op.CONFIRM).complete();
        f.drain();
        BattleFreeze rebuilt = player.battle().freeze();
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(rebuilt.deadlineMs()).isEqualTo(deadline);

        f.locks.take(Op.CONFIRM).complete();
        f.drain();

        assertThat(player.battle().freeze()).as("第二条不重建").isSameAs(rebuilt);
        assertThat(rebuilt.deadlineMs()).as("已是 FIGHTING：不再改期限").isEqualTo(deadline);
        assertThat(rebuilt.lockExtended()).isTrue();
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(hintsTotal()).isEqualTo(1);
        assertThat(confirmCounts()).containsExactly(Map.entry("idempotent", 1.0), Map.entry("rebuilt", 1.0));
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isEqualTo(1);
        assertThat(f.rebuilds("late_confirm", "miss")).isEqualTo(1);
    }

    /**
     * CONFIRM 在途期间这一局的结算先到并应用了（按锁分支）：回来时账本已有 X → 不重建（否则把玩家冻进一场打完的战斗）、不推 144，
     * 只再推进一次销账。
     */
    @Test
    void 迟到确认_回来时账本已有这一局_不重建_不推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.locks.hold(Op.CONFIRM);
        f.confirm(PLAYER, X, f.deadline());
        CompletableFuture<SceneBattleReply> settled = f.deliver(BattleFixture.settlement(PLAYER, X, 10).build());
        f.drain();
        assertThat(BattleFixture.done(settled).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(f.battleEnds(player)).hasSize(1);

        f.locks.take(Op.CONFIRM).complete();
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.gold(player)).isEqualTo(10);
        assertThat(confirmCounts()).containsExactly(Map.entry("ledger_hit", 1.0));
        assertThat(f.rebuilds("late_confirm", "ledger_hit")).isEqualTo(1);
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isZero();
    }

    // ------------------------------------------------------------------ 在线、没有冻结：账本命中 → 销账不重建

    /** 这一局已应用、已落盘、只是销账还没成功：确认到达时不发 CONFIRM、不重建，直接推进销账（ACK）。 */
    @Test
    void 账本命中_已落盘_销账不重建_不发CONFIRM() {
        f.locks.failAlways(Op.ACK, new RuntimeException("Redis 超时"));
        ScenePlayer player = f.enter(SESSION, PLAYER, ledgerOf(X));
        assertThat(player.battleLedger().has(X)).as("进场时的销账失败，条目留着").isTrue();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        f.locks.heal(Op.ACK);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 180);

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(f.locks.ops()).as("不发 CONFIRM，只销账").containsExactly(Op.ACK);
        assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(X);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(player.battleLedger().has(X)).as("销账成功，条目摘掉").isFalse();
        assertThat(f.locks.lock(PLAYER)).as("销账顺带放锁").isNull();
        assertThat(confirmCounts()).containsExactly(Map.entry("ledger_hit", 1.0));
        assertThat(f.acks("apply", "released")).isEqualTo(1);
    }

    /** 这一局刚应用、还没落盘：确认到达时同样不重建；没落盘不销账（只压存盘），零 Redis 调用。 */
    @Test
    void 账本命中_未落盘_不重建_不销账_零Redis调用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        CompletableFuture<SceneBattleReply> settled = f.deliver(BattleFixture.settlement(PLAYER, X, 10).build());
        f.drain();
        assertThat(BattleFixture.done(settled).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
        f.locks.clearCalls();
        f.sink.clear();

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(f.locks.calls()).isEmpty();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(f.confirms("ledger_hit")).isEqualTo(1);
        assertThat(f.acks("apply", "deferred")).as("应用时一次 + 这次一次，都因没落盘而延后").isEqualTo(2);
    }

    /**
     * 正常路径上最常见的「过期确认」：这一局已结算、已落盘、已销账（账本条目摘了、锁放了），battle 的补发确认（首发后每 10 s 一次）才又到。
     * 此时账本里已经没有这一局，靠的是锁：一段 CONFIRM 落空（锁不在）→ 不重建、不推 144——不能把玩家重新冻进一场打完的战斗。
     */
    @Test
    void 这一局已结算并销账_之后迟到的补发确认_锁已放_不重建_不推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        CompletableFuture<SceneBattleReply> settled = f.deliver(BattleFixture.settlement(PLAYER, X, 10).build());
        f.drain();
        assertThat(BattleFixture.done(settled).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).as("落盘后销账，条目摘掉").isFalse();
        assertThat(f.locks.lock(PLAYER)).as("销账放锁").isNull();
        f.locks.clearCalls();
        f.sink.clear();

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(f.locks.ops()).containsExactly(Op.CONFIRM);
        assertThat(f.locks.last(Op.CONFIRM).lastReply()).as("锁不在，脚本回 nil").isNull();
        assertThat(f.locks.lock(PLAYER)).as("CONFIRM 不会凭空造锁").isNull();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.messageIds(player)).as("不推 144，也不再推 150").isEmpty();
        assertThat(f.gold(player)).isEqualTo(10);
        assertThat(f.confirms("mismatch")).isEqualTo(1);
        assertThat(f.confirms("rebuilt")).isZero();
        assertThat(f.rebuilds("late_confirm", "miss")).isEqualTo(1);
        assertThat(f.rebuilds("late_confirm", "rebuilt")).isZero();
    }

    // ------------------------------------------------------------------ 144：谁推、推几次

    /**
     * D7：冻结不是在本实例上备战的（这里是进场时按锁重建出来的 PREPARING）→ 确认升级时推 144，且只推一次；
     * 之后的补发不再推。
     */
    @Test
    void 按锁重建的备战冻结_确认时推144_只推一次() {
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.preparedHere()).isFalse();
        assertThat(hintsTotal()).as("重建出来的是 PREPARING：进场时不推").isZero();
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.messageIds(player)).as("只有一条 144").containsExactly(f.reconnectHintId);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.hints("confirm")).isEqualTo(1);

        f.confirm(PLAYER, X, deadline);
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(f.reconnectHints(player)).as("补发不再推").containsExactly(X);
        assertThat(hintsTotal()).isEqualTo(1);
    }

    /**
     * 同 epoch 重进（重连，换了会话）沿用旧实例的 PREPARING 冻结：沿用时不推（PREPARING 永不推）；确认升级时推一次——
     * 新会话的客户端没有收到过 match 的开战通知。
     */
    @Test
    void 沿用旧实例的备战冻结_沿用时不推_确认时推144_只推一次() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        BattleFreeze original = old.battle().freeze();
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        BattleFreeze carried = fresh.battle().freeze();
        assertThat(carried).as("沿用的是新对象").isNotNull().isNotSameAs(original);
        assertThat(carried.phase()).isEqualTo(Phase.PREPARING);
        assertThat(carried.preparedHere()).isFalse();
        assertThat(f.reconnectHints(fresh)).as("PREPARING 沿用时不推").isEmpty();
        assertThat(f.rebuilds("carried", "rebuilt")).isEqualTo(1);
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(carried.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(carried.lockExtended()).isTrue();
        assertThat(original.phase()).as("旧实例的冻结对象不再被改").isEqualTo(Phase.PREPARING);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
        assertThat(f.reconnectHints(old)).as("旧会话什么都不收").isEmpty();
        assertThat(f.hints("confirm")).isEqualTo(1);

        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
        assertThat(hintsTotal()).isEqualTo(1);
    }

    /**
     * 同会话的重复进场（同 epoch、会话号没变）同样把冻结复制成 {@code preparedHere = false} 的新对象（§7.8 第 0 步不分会话），
     * 所以确认升级时也推一次 144。基线按「备战时记下的会话号 ≠ 当前会话号」才推，同会话不推——这里多出的一条 144 对客户端只是多一次补签，无害。
     */
    @Test
    void 同会话重复进场沿用的备战冻结_确认时同样推一次144() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        ScenePlayer fresh = f.reenter(SESSION, PLAYER, 1, f.scene1);
        f.drain();
        f.sink.clear();
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.session()).isEqualTo(old.session());
        assertThat(fresh.battle().freeze().preparedHere()).isFalse();
        assertThat(hintsTotal()).isZero();

        f.confirm(PLAYER, X, f.deadline());
        f.drain();

        assertThat(fresh.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.messageIds(fresh)).containsExactly(f.reconnectHintId);
        assertThat(f.hints("confirm")).isEqualTo(1);
    }

    /** PREPARING 永不推 144（B5：房间还没建，客户端拿它去补签会被判 BattleGone）——备战、按锁重建、沿用、不符的确认、取消，都不推。 */
    @Test
    void 备战状态下任何路径都不推144() {
        // 本实例备战
        ScenePlayer here = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        // 不符的确认
        f.confirm(PLAYER, Y, f.deadline());
        f.drain();
        // 同 epoch 重进：换会话沿用 PREPARING
        ScenePlayer carried = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();
        assertThat(carried.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        // 另一名玩家：进场按锁重建 PREPARING，复核命中
        f.locks.putLock(3003, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        ScenePlayer rebuilt = f.load(SESSION + 2, 3003, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();
        assertThat(rebuilt.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        // 再一名玩家：进场按锁重建 PREPARING，复核出错（保守保留）
        f.locks.putLock(4004, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARE_TTL);
        f.locks.failNext(Op.TOUCH, new RuntimeException("Redis 超时"));
        ScenePlayer kept = f.load(SESSION + 3, 4004, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();
        assertThat(kept.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        // 取消
        f.cancel(3003, X);
        f.drain();
        assertThat(rebuilt.inBattle()).isFalse();

        assertThat(hintsTotal()).isZero();
        for (ScenePlayer player : List.of(here, carried, rebuilt, kept)) {
            assertThat(f.reconnectHints(player)).as("player=%s session=%s", player.playerId(), player.session()).isEmpty();
        }
    }
}
