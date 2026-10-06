package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.player.Wallet;
import com.game.scene.testing.FakeBattleLocks;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 结算到达的入口分流 {@code applySettlement}（scene-battle-spec §7.10 第 1–9 步；基线 {@code ApplySettlement}，{@code pb.cpp:1811-1934}；
 * §13.2 {@code SettlementDeliverTest} 一行逐条）：
 * <pre>
 * 冻结匹配 → 应用 + 摘冻结 + HOLD + 150          冻结不匹配 → DISCARDED + 销账
 * 无冻结、锁 == X → 按锁应用                      无冻结、锁不在 / 易主 → DISCARDED + 销账
 * 读锁失败 → DEFERRED，零副作用                   异步读回来时实例已换 / 恢复未就绪 / 已在交出冻结 → DEFERRED
 * 回调时已有别的局的冻结 → DISCARDED，不销账       实例不符 → NOT_HERE（提供方，Dubbo 线程上）
 * 恢复中 / FREEZING → DEFERRED                    参数非法 / 信封与结算的 player_id 不符 → DISCARDED，不销账
 * </pre>
 * 应用本身的取值与「恰好一次」在 {@code SettlementApplyValuesTest} / {@code SettlementApplyTest}；读锁与销账乱序（过期读）在 {@code BattleStaleReadTest}；
 * 回调抛异常 / 逻辑线程已停时应答的结局在 {@code BattleCallbackFailureTest}。
 */
class SettlementDeliverTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long Y = 8;
    private static final long GOLD = 100;
    private static final int NODE = BattleFixture.BATTLE_NODE;

    private final BattleFixture f = new BattleFixture();

    private static BattleSettlementData settlement(long battleId) {
        return BattleFixture.settlement(PLAYER, battleId, GOLD).build();
    }

    private static void assertReply(SceneBattleReply reply, SceneBattleStatus status, SettlementDisposition disposition) {
        assertThat(reply.getStatus()).isEqualTo(status);
        assertThat(reply.getSettlement()).isEqualTo(disposition);
        assertThat(reply.getBody().isEmpty()).as("结算的应答不带 body").isTrue();
    }

    private static void assertDeferred(SceneBattleReply reply) {
        assertReply(reply, SceneBattleStatus.SCENE_BATTLE_DEFERRED, SettlementDisposition.SETTLEMENT_DISPOSITION_UNSPECIFIED);
    }

    private void leave() {
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();
    }

    // ------------------------------------------------------------------ 第 8、9 步：冻结匹配

    /**
     * 在线且冻结是这一局：当场应用（不等任何 Redis 往返），HOLD 把锁留到落盘、摘冻结（锁保留，所以立即补跟随）、销账等落盘、推 150。
     * 先销账后应用会两头落空，所以销账一定排在应用与落盘之后。
     */
    @Test
    void 冻结匹配_当场应用_HOLD续锁_摘冻结锁保留_推150_落盘后才销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = settlement(X);
        f.store(settlement);
        f.locks.clearCalls();
        f.follows.clear();
        f.sink.clear();

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);

        assertThat(reply).as("冻结匹配的分支不等 Redis，应答当场完成").isCompleted();
        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.inBattle()).as("冻结已摘").isFalse();
        assertThat(f.locks.ops()).as("只发了续锁；没落盘不销账").containsExactly(Op.HOLD);
        Call hold = f.locks.last(Op.HOLD);
        assertThat(hold.battleId()).isEqualTo(X);
        assertThat(hold.longArg("hold")).isEqualTo(180);
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁留着（跨进程「这一局还没完」的证据）").isEqualTo(X);
        assertThat(f.locks.count(Op.DELETE_IF_MATCH) + f.locks.count(Op.DELETE_PREPARING)).isZero();
        assertThat(f.follows.freezeCleared).as("锁保留的解冻立即补跟随（跟随链读到锁会放弃）").containsExactly(PLAYER);
        assertThat(f.messageIds(player)).containsExactly(f.battleEndId);
        BattleEndS2C end = f.battleEnds(player).get(0);
        assertThat(end.getBattleId()).isEqualTo(X);
        assertThat(end.getOutcome()).isEqualTo(settlement.getOutcome());
        assertThat(end.getSettlement()).isEqualTo(settlement);
        assertThat(f.settlementsCounted("online", "applied")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isTrue();

        f.drain();
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);

        assertThat(f.locks.ops()).as("HOLD 在前，落盘后 ACK").containsExactly(Op.HOLD, Op.ACK);
        assertThat(f.locks.last(Op.ACK).lastReply()).isEqualTo(3L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).as("放锁后再补一次").containsExactly(PLAYER, PLAYER);
    }

    /** 备战中（确认还没到）就收到这一局的结算：同样按 online 路径应用，不要求已 FIGHTING。 */
    @Test
    void 冻结匹配_备战阶段同样应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(f.reconnectHints(player)).isEmpty();
    }

    /**
     * 第 9 步的另一半：应用回「已应用」（冻结是这一局，而账本里已有它——这里是加载自库的条目，进场那次销账失败留了下来）时，收尾与新应用相同：
     * 续锁 → 摘冻结（锁保留）→ 销账；只是<b>不再发奖、不推 150</b>（基线 {@code pb.cpp:1631-1637}：调用方照常解冻并销账）。
     * 条目已在落库快照里，所以销账当场发出，不等存盘。
     */
    @Test
    void 冻结匹配_账本里已有这一局_按已应用收尾_照常续锁解冻销账_不再发奖不推150() {
        f.locks.failNext(Op.ACK, new RuntimeException("销账脚本失败"));
        PlayerState saved = PlayerState.newBuilder().setBattleLedger(BattleLedgerState.newBuilder()
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(X).setAppliedAtMs(1234))).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, saved);
        assertThat(player.battleLedger().has(X)).as("进场那次销账失败，条目保留").isTrue();
        long deadline = f.deadline();
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, deadline, 0, 100);
        BattleFixture.setFreeze(player, new BattleFreeze(X, NODE, Phase.FIGHTING, deadline, 0, true));
        BattleSettlementData settlement = settlement(X);
        f.store(settlement);
        double errorsBefore = f.acks("login", "error");

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);

        assertThat(reply).isCompleted();
        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).as("奖是上一次发的（已在存档里），这次不再发").isZero();
        assertThat(f.audit.currencies).isEmpty();
        assertThat(f.messageIds(player)).as("不推 150").isEmpty();
        assertThat(player.inBattle()).as("照常解冻：玩家不会被卡在已结束的战斗里").isFalse();
        assertThat(f.locks.ops()).as("续锁在前、销账在后；条目已落盘，销账不等存盘").containsExactly(Op.HOLD, Op.ACK);
        assertThat(f.locks.last(Op.HOLD).longArg("hold")).isEqualTo(180);
        assertThat(f.repo.pendingProgress()).as("没有新改动，不压存盘").isZero();
        assertThat(f.settlementsCounted("online", "already_applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("online", "applied")).isZero();
        assertThat(f.follows.freezeCleared).as("锁保留的解冻立即补一次跟随").containsExactly(PLAYER);

        f.drain();

        assertThat(f.locks.last(Op.ACK).lastReply()).as("删记录 + 放锁").isEqualTo(3L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.battleLedger().has(X)).as("销账成功后才摘").isFalse();
        assertThat(f.acks("apply", "released")).isEqualTo(1);
        assertThat(f.acks("login", "error")).isEqualTo(errorsBefore);
        assertThat(f.follows.freezeCleared).as("放锁后再补一次").containsExactly(PLAYER, PLAYER);
    }

    // ------------------------------------------------------------------ 第 6 步：冻结不匹配

    /** 玩家已在下一局（冻结是 Y）时收到 X 的结算：丢弃并销账——销账只删 X 的记录，Y 的冻结与锁都不动。 */
    @Test
    void 冻结不匹配_丢弃并立即销账_只删这一局的记录_当前冻结与锁不动() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, Y);
        BattleFreeze current = player.battle().freeze();
        f.store(settlement(X));
        f.locks.clearCalls();
        f.sink.clear();

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.gold(player)).isZero();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(player.battle().freeze()).isSameAs(current);
        assertThat(f.locks.ops()).as("没有读锁、没有续锁，只有销账").containsExactly(Op.ACK);
        assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(X);
        assertThat(f.locks.last(Op.ACK).lastReply()).as("删了 X 的记录（位 1），没放 Y 的锁").isEqualTo(1L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(Y);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.settlementsCounted("online", "discarded_mismatch")).isEqualTo(1);
        assertThat(f.acks("discard", "released")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(f.repo.pendingProgress()).isZero();
    }

    // ------------------------------------------------------------------ 第 7 步：没有冻结，按锁

    /** 没有冻结（备战到期被 reaper 摘了、锁留到 TTL 之类）而锁仍是这一局：读锁回来后按 by_lock 路径应用。 */
    @Test
    void 无冻结_锁是本局_读锁回来后按锁应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = settlement(X);
        f.store(settlement);
        f.locks.hold(Op.READ_LOCK);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);

        assertThat(reply).as("应答等读锁结局").isNotDone();
        assertThat(f.gold(player)).isZero();
        assertThat(f.locks.ops()).containsExactly(Op.READ_LOCK);

        f.locks.take(Op.READ_LOCK).complete();
        assertThat(reply).as("结局回到逻辑线程之前不动状态").isNotDone();
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.READ_LOCK, Op.HOLD);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.settlementsCounted("by_lock", "applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("online", "applied")).isZero();
        assertThat(f.follows.freezeCleared).as("本来就没有冻结可摘").isEmpty();
    }

    /** 没有冻结、锁也不在：这一局已作废（被 reaper 判废等），丢弃并销账，待结算记录随之删掉、不再重投。 */
    @Test
    void 无冻结_锁不在_丢弃并销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.store(settlement(X));

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.gold(player)).isZero();
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(f.locks.ops()).containsExactly(Op.READ_LOCK, Op.ACK);
        assertThat(f.locks.last(Op.READ_LOCK).lastReply()).isEqualTo(0L);
        assertThat(f.locks.last(Op.ACK).lastReply()).as("只删到记录（位 1），没有锁可放").isEqualTo(1L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.settled(PLAYER, X)).as("销账写了墓碑：之后这一局的落库被挡").isTrue();
        assertThat(f.settlementsCounted("by_lock", "discarded_void")).isEqualTo(1);
        assertThat(f.acks("discard", "released")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(f.follows.freezeCleared).as("没有放掉锁（位 2 为 0）：不补组队跟随").isEmpty();
    }

    @Test
    void 无冻结_锁已是别的局_丢弃并销账_别的局的锁不动() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, Y, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.store(settlement(X));

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.gold(player)).isZero();
        assertThat(f.locks.last(Op.ACK).lastReply()).isEqualTo(1L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(Y);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(120);
        assertThat(f.settlementsCounted("by_lock", "discarded_void")).isEqualTo(1);
        assertThat(f.follows.freezeCleared).as("别的局的锁没被放掉（位 2 为 0）：不补组队跟随").isEmpty();
    }

    /** 读锁失败（D15：没问到结论不当结论）：延后、零副作用——不应用、不销账，记录留着等下一轮重投。 */
    @Test
    void 无冻结_读锁失败_延后_零副作用_下一轮重投照常应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = settlement(X);
        f.store(settlement);
        f.locks.failNext(Op.READ_LOCK, new RuntimeException("Redis 超时"));

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();

        assertDeferred(BattleFixture.done(reply));
        assertThat(f.gold(player)).isZero();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(f.locks.ops()).as("只有那次失败的读锁").containsExactly(Op.READ_LOCK);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(BattleFixture.writtenOff(player, X)).as("没有销账").isFalse();
        assertThat(f.repo.pendingProgress()).isZero();
        assertThat(f.settlementsCounted("by_lock", "deferred_lock_read")).isEqualTo(1);

        CompletableFuture<SceneBattleReply> retry = f.deliver(settlement);
        f.drain();

        assertReply(BattleFixture.done(retry), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    /**
     * 第 9 步「回 DEFERRED → 原样回 DEFERRED」在按锁分支上：锁是本局、应用却被延后（金币被 GM 封禁）→ 应答延后，
     * 不续锁、不销账、不推 150，记录与锁都原样留着；解封后的重投照常按锁应用。
     */
    @Test
    void 无冻结_锁是本局_应用被延后_原样回延后_不续锁不销账_解封后重投照常按锁应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 100);
        BattleSettlementData settlement = settlement(X);
        f.store(settlement);
        assertThat(f.currency.block(player, Wallet.GOLD)).isZero();

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();

        assertDeferred(BattleFixture.done(reply));
        assertThat(f.gold(player)).isZero();
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(f.locks.ops()).as("只有读锁：延后不续锁、不销账").containsExactly(Op.READ_LOCK);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.locks.lockTtlSec(PLAYER)).as("锁的 TTL 没被续").isEqualTo(100);
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(BattleFixture.writtenOff(player, X)).isFalse();
        assertThat(f.repo.pendingProgress()).isZero();
        assertThat(f.settlementsCounted("by_lock", "deferred_currency")).isEqualTo(1);

        assertThat(f.currency.unblock(player, Wallet.GOLD)).isZero();
        CompletableFuture<SceneBattleReply> retry = f.deliver(settlement);
        f.drain();

        assertReply(BattleFixture.done(retry), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(f.locks.lockTtlSec(PLAYER)).as("应用后才续到 180 s").isEqualTo(180);
        assertThat(f.settlementsCounted("by_lock", "applied")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 第 7 步：读锁回来之后的复核

    @Test
    void 读锁回来时玩家已离场_延后_不应用不销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.store(settlement(X));
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        leave();

        f.locks.take(Op.READ_LOCK).complete();
        f.drain();

        assertDeferred(BattleFixture.done(reply));
        assertThat(f.gold(player)).as("离场的旧实例不再被改").isZero();
        assertThat(f.locks.count(Op.ACK) + f.locks.count(Op.HOLD)).isZero();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.settlementsCounted("by_lock", "deferred_recovering")).isEqualTo(1);
    }

    /**
     * 读锁在途时同 epoch 重进（实例换了）：旧调用的回调核对实例失败 → DEFERRED，新旧实例都不动；这一局由新实例自己的进场恢复应用，恰好一次。
     */
    @Test
    void 读锁回来时实例已换_延后_新实例由进场恢复应用恰好一次() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = settlement(X);
        f.store(settlement);
        f.locks.hold(Op.READ_LOCK, Op.ENTER_READ);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        assertThat(fresh).isNotSameAs(old);

        f.locks.take(Op.READ_LOCK).complete();
        f.drain();

        assertDeferred(BattleFixture.done(reply));
        assertThat(f.gold(old)).isZero();
        assertThat(f.gold(fresh)).isZero();
        assertThat(f.locks.count(Op.ACK) + f.locks.count(Op.HOLD)).isZero();
        assertThat(f.battleEnds(fresh)).isEmpty();

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();

        assertThat(f.gold(fresh)).isEqualTo(GOLD);
        assertThat(f.gold(old)).isZero();
        assertThat(f.battleEnds(fresh)).hasSize(1);
        assertThat(f.battleEnds(old)).isEmpty();
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("by_lock", "applied")).isZero();
    }

    @Test
    void 读锁回来时进场恢复又在途_延后_恢复读回来后由它应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = settlement(X);
        f.store(settlement);
        f.locks.hold(Op.READ_LOCK, Op.ENTER_READ);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        // 发出读锁之后恢复被重启（原地解冻 / reaper 重试都走这里）
        f.battle.onUnfrozenInPlace(f.world, player);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);

        f.locks.take(Op.READ_LOCK).complete();
        f.drain();

        assertDeferred(BattleFixture.done(reply));
        assertThat(f.gold(player)).isZero();
        assertThat(f.settlementsCounted("by_lock", "deferred_recovering")).isEqualTo(1);

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
    }

    @Test
    void 读锁回来时玩家已进入交出冻结_延后_冻结快照不被改() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.store(settlement(X));
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        f.freezeForHandOff(player);
        PlayerState frozen = BattleFixture.persistentState(player);

        f.locks.take(Op.READ_LOCK).complete();
        f.drain();

        assertDeferred(BattleFixture.done(reply));
        assertThat(BattleFixture.persistentState(player)).isEqualTo(frozen);
        assertThat(f.gold(player)).isZero();
        assertThat(f.locks.count(Op.ACK) + f.locks.count(Op.HOLD)).isZero();
        assertThat(f.settlementsCounted("by_lock", "deferred_frozen")).isEqualTo(1);
    }

    /**
     * 读锁读到的是本局，但回调时玩家身上已挂了别的局的冻结：丢弃，<b>不销账</b>（同基线 {@code pb.cpp:1898-1904}）——
     * 锁此刻明明是 X，销账会把 X 的锁放掉；记录留着，下一轮重投时再按当时的锁裁决。
     */
    @Test
    void 读锁是本局_回调时已有别的局的冻结_丢弃不销账_记录留着() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.store(settlement(X));
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        Call read = f.locks.take(Op.READ_LOCK).execute();
        assertThat(read.lastReply()).isEqualTo(X);
        BattleFreeze other = new BattleFreeze(Y, NODE, Phase.PREPARING, f.deadline(), f.prepareDeadline(), true);
        BattleFixture.setFreeze(player, other);

        read.reply();
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.gold(player)).isZero();
        assertThat(player.battle().freeze()).isSameAs(other);
        assertThat(f.locks.count(Op.ACK)).as("不销账").isZero();
        assertThat(f.locks.count(Op.HOLD)).isZero();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(BattleFixture.writtenOff(player, X)).isFalse();
        assertThat(f.settlementsCounted("by_lock", "discarded_mismatch")).isEqualTo(1);
    }

    /**
     * 判定次序（审计 STL-9）：先看锁、再看回调时刻的冻结。锁已经不是 X（易主成 Y）且回调时挂着 Y 的冻结 → 按「已作废」丢弃并销账
     * （销账只删 X 的记录，b ≠ X 的锁不动），而不是落进「别的局的冻结 → 不销账」那一支。
     */
    @Test
    void 读锁不是本局_回调时已有别的局的冻结_按已作废丢弃并销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.store(settlement(X));
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        f.locks.release(Op.READ_LOCK);
        // 读锁在途时玩家备战了下一局 Y（写锁成功、冻结挂上）
        f.prepared(PLAYER, Y);
        BattleFreeze current = player.battle().freeze();

        f.locks.take(Op.READ_LOCK).complete();
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.settlementsCounted("by_lock", "discarded_void")).isEqualTo(1);
        assertThat(f.settlementsCounted("by_lock", "discarded_mismatch")).isZero();
        assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(X);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(player.battle().freeze()).isSameAs(current);
        assertThat(f.locks.lockBattleId(PLAYER)).as("Y 的锁不动").isEqualTo(Y);
        assertThat(f.gold(player)).isZero();
    }

    /** 读锁在途时迟到确认把这一局的冻结重建出来了：回调时冻结就是本局 → 按 online 路径应用，并把这个冻结摘掉。 */
    @Test
    void 读锁是本局_回调时已有本局的冻结_照常应用并摘冻结() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.store(settlement(X));
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);

        f.locks.take(Op.READ_LOCK).complete();
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.settlementsCounted("online", "applied")).isEqualTo(1);
        assertThat(f.messageIds(player)).as("144（迟到确认重建）→ 150").containsExactly(f.reconnectHintId, f.battleEndId);
    }

    // ------------------------------------------------------------------ 第 3–5 步：不在场、恢复中、交出冻结

    @Test
    void 玩家不在本节点_回NOT_HERE_零Redis调用() {
        f.enter(SESSION, PLAYER);
        f.store(BattleFixture.settlement(2002, X, GOLD).build());

        CompletableFuture<SceneBattleReply> reply = f.deliver(BattleFixture.settlement(2002, X, GOLD).build());

        assertThat(reply).isCompleted();
        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_NOT_HERE, SettlementDisposition.SETTLEMENT_DISPOSITION_UNSPECIFIED);
        assertThat(f.locks.calls()).as("离线玩家不碰 Redis：battle 下一轮按位置重新解析").isEmpty();
        assertThat(f.locks.hasSettlement(2002, X)).isTrue();
        assertThat(f.settlementsCounted("online", "not_here")).isEqualTo(1);
    }

    @Test
    void 进场恢复读在途_延后_零副作用_恢复就绪后由恢复应用() {
        BattleSettlementData settlement = settlement(X);
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);
        f.store(settlement);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);

        assertThat(reply).isCompleted();
        assertDeferred(BattleFixture.done(reply));
        assertThat(f.gold(player)).isZero();
        assertThat(f.locks.ops()).as("除了进场那次恢复读，没有别的 Redis 调用").containsExactly(Op.ENTER_READ);
        assertThat(f.settlementsCounted("online", "deferred_recovering")).isEqualTo(1);

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    @Test
    void 进场恢复待重试_延后_零Redis调用() {
        f.locks.failNext(Op.ENTER_READ, new RuntimeException("Redis 不可用"));
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));

        assertDeferred(BattleFixture.done(reply));
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.gold(player)).isZero();
    }

    /** 恢复未就绪排在「冻结不匹配」之前：同 epoch 沿用了 Y 的冻结、恢复读还没回来时收到 X 的结算 → 延后，而不是丢弃并销账。 */
    @Test
    void 恢复未就绪先于冻结不匹配_延后_不销账() {
        f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, Y);
        f.store(settlement(X));
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        assertThat(fresh.battle().freeze().battleId()).isEqualTo(Y);
        assertThat(fresh.battle().recovery()).isEqualTo(Recovery.PENDING);
        f.locks.clearCalls();

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));

        assertDeferred(BattleFixture.done(reply));
        assertThat(f.locks.count(Op.ACK)).isZero();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.settlementsCounted("online", "deferred_recovering")).isEqualTo(1);
        assertThat(f.settlementsCounted("online", "discarded_mismatch")).isZero();
    }

    @Test
    void 交出冻结中_延后_零Redis调用_原地解冻后重投照常() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        var handOff = f.freezeForHandOff(player);
        f.locks.clearCalls();

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));

        assertThat(reply).isCompleted();
        assertDeferred(BattleFixture.done(reply));
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.gold(player)).isZero();
        assertThat(f.settlementsCounted("online", "deferred_frozen")).isEqualTo(1);

        handOff.complete(new HandOffOutcome.LeaseTooShort());
        f.drain();
        // 原地解冻重跑了进场恢复：这一局没有待结算记录（battle 落库失败、只投递的那类），按锁重建了 FIGHTING 冻结
        assertThat(player.battle().freeze().battleId()).isEqualTo(X);

        CompletableFuture<SceneBattleReply> retry = f.deliver(settlement(X));
        f.drain();
        assertReply(BattleFixture.done(retry), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    /** 选目标中（RESOLVING）不算冻结：照常应用（之后拍的冻结快照会包含这次应用）。 */
    @Test
    void 选目标中不算冻结_照常应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        f.resolveRemote(player);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement(X));
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    // ------------------------------------------------------------------ 第 2 步：非法投递

    /** player_id / battle_id 为 0、信封的 player_id 与结算里的不符：丢弃，<b>不销账</b>、零 Redis 调用；不毒化随后合法的投递。 */
    @Test
    void 参数为0或信封与结算的玩家不符_丢弃不销账_零Redis调用_随后合法的投递照常() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.store(settlement(X));
        f.locks.clearCalls();
        BattleFreeze freeze = player.battle().freeze();

        SceneBattleReply zeroPlayer = BattleFixture.done(f.battle.deliver(PLAYER, settlement(X).toBuilder().setPlayerId(0).build()));
        SceneBattleReply zeroBattle = BattleFixture.done(f.battle.deliver(PLAYER, settlement(0)));
        SceneBattleReply wrongEnvelope = BattleFixture.done(f.battle.deliver(2002, settlement(X)));
        SceneBattleReply wrongOwner = BattleFixture.done(f.battle.deliver(PLAYER, settlement(X).toBuilder().setPlayerId(2002).build()));

        for (SceneBattleReply reply : new SceneBattleReply[] {zeroPlayer, zeroBattle, wrongEnvelope, wrongOwner}) {
            assertReply(reply, SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        }
        assertThat(f.locks.calls()).as("不销账、不读锁").isEmpty();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.gold(player)).isZero();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(f.settlementsCounted("online", "discarded_invalid")).isEqualTo(4);

        assertReply(BattleFixture.done(f.deliver(settlement(X))), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    // ------------------------------------------------------------------ 第 1 步：经提供方（实例核对与解析在 Dubbo 线程上）

    private SceneBattleProvider provider() {
        return new SceneBattleProvider(() -> f.battle, BattleFixture.SCENE_INSTANCE, f.logic, 4, Runnable::run, f.battleMetrics);
    }

    private static SceneBattleCall call(String instanceId, long envelopePlayer, byte[] body) {
        return SceneBattleCall.newBuilder().setTargetInstanceId(instanceId).setPlayerId(envelopePlayer)
                .setBody(ByteString.copyFrom(body)).build();
    }

    /** {@code target_instance_id} 不是本进程（scene 重启过、battle 手里的目录是旧的）→ NOT_HERE，根本不进逻辑线程。 */
    @Test
    void 经提供方_实例不符_回NOT_HERE_不进逻辑线程() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.locks.clearCalls();
        SceneBattleProvider provider = provider();

        CompletableFuture<SceneBattleReply> reply =
                provider.applySettlement(call("scene-instance-old", PLAYER, FakeBattleLocks.record(settlement(X))));

        assertThat(reply).isCompleted();
        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_NOT_HERE, SettlementDisposition.SETTLEMENT_DISPOSITION_UNSPECIFIED);
        assertThat(f.logic.pending()).as("没有投递到逻辑线程").isZero();
        assertThat(f.gold(player)).isZero();
        assertThat(player.inBattle()).isTrue();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(provider.inFlight()).as("在途名额已归还").isZero();
        assertThat(f.count("xm.scene.battle.rpc", "method", "settlement", "result", "not_here")).isEqualTo(1);
    }

    /** 实例相符：body 就是 battle 落库的那份 {@code BattleSettlementEvent} 字节，进逻辑线程后走到同一个入口。 */
    @Test
    void 经提供方_实例相符_进逻辑线程应用_应答带处置结论() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        SceneBattleProvider provider = provider();

        CompletableFuture<SceneBattleReply> reply =
                provider.applySettlement(call(BattleFixture.SCENE_INSTANCE, PLAYER, FakeBattleLocks.record(settlement(X))));

        assertThat(reply).as("还没轮到逻辑线程").isNotDone();
        assertThat(f.gold(player)).isZero();
        assertThat(provider.inFlight()).isEqualTo(1);
        f.drain();

        assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(provider.inFlight()).isZero();
        assertThat(f.count("xm.scene.battle.rpc", "method", "settlement", "result", "handled")).isEqualTo(1);
    }

    @Test
    void 经提供方_body解析失败或信封玩家不符_丢弃不销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.store(settlement(X));
        f.locks.clearCalls();
        SceneBattleProvider provider = provider();

        CompletableFuture<SceneBattleReply> garbage = provider.applySettlement(
                call(BattleFixture.SCENE_INSTANCE, PLAYER, "不是 protobuf".getBytes(StandardCharsets.UTF_8)));
        assertThat(f.logic.pending()).as("解析失败不进逻辑线程").isZero();
        CompletableFuture<SceneBattleReply> wrongEnvelope =
                provider.applySettlement(call(BattleFixture.SCENE_INSTANCE, 2002, FakeBattleLocks.record(settlement(X))));
        // 空 body = 没有 settlement 的事件：player_id / battle_id 都是 0
        CompletableFuture<SceneBattleReply> empty = provider.applySettlement(call(BattleFixture.SCENE_INSTANCE, PLAYER, new byte[0]));
        f.drain();

        for (CompletableFuture<SceneBattleReply> reply : java.util.List.of(garbage, wrongEnvelope, empty)) {
            assertReply(BattleFixture.done(reply), SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        }
        assertThat(f.locks.calls()).as("不销账").isEmpty();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.gold(player)).isZero();
        assertThat(player.inBattle()).isTrue();
        assertThat(provider.inFlight()).isZero();
    }
}
