package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.gainblock.GlobalGainBlocks;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakeBattleLocks;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.testing.FakeSwitchTargets;
import com.game.scene.world.PlayerData;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.game.scene.world.Vec3;
import com.game.scene.world.WorldTestAccess;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 进场恢复（scene-battle-spec §7.8 第 0–5 步；基线登录钩子 {@code pb.cpp:1999-2088}；§13.2 {@code BattleRecoveryTest} 一行逐条）：
 * <ul>
 *   <li>第 3 步按锁重建：PREPARING 不推、FIGHTING 推 144 且在 79 / 21 / 47 之后、无锁不重建、复核没命中撤销、锁指向账本里已有的局销账不重建；
 *       挂冻结即停步；锁缺字段时按 §1.2 反推的值进复核（纯函数本身在 {@code FreezeFromLockTest}）；复核回调的守卫（§7.4）——回来时重建的冻结
 *       已被结算摘掉 / 已换成下一局的、玩家已离场 / 已同 epoch 重进 → 丢弃，不推 144、不撤销别人的冻结、不计重建；</li>
 *   <li>第 2 步待结算记录：坏字段只删该字段（锁指向它时不重建）、按 battle_id <b>无符号</b>升序应用、遇延后即停；延后之后锁步骤照做；</li>
 *   <li>第 1 步读失败 → RETRY，reaper 重读后 READY；恢复就绪之前备战 1006、结算延后，在途闸不受影响；</li>
 *   <li>第 0 步同 epoch 沿用旧冻结（新对象、运行态复位、续期结论照抄；会话变了推 144；同会话 {@code preparedHere} 照抄旧值——旧值为真不推，
 *       旧值为假的 FIGHTING（按锁重建后复核还在途、144 还欠着）沿用时当场推一次，评审 R2-REV-2）；没落盘的账本条目随旧实例内存沿用；
 *       沿用的冻结是别的局时待结算记录照常应用、不摘它；</li>
 *   <li>恢复读回来时已在交出冻结 → 不挂冻结、待结算记录整笔延后；在选目标中 → 挂上，随后选中远端时中止换图；交出进场也跑。</li>
 * </ul>
 * 相邻的回归用例不在这里重复：复核出错 / 返回 2 / 144 恰好一次（{@code BattleRebuildRegressionTest}）、销账与恢复读乱序及恢复代际
 * （{@code BattleStaleReadTest}）、处理快照中途抛异常（{@code BattleCallbackFailureTest}）、原地解冻后重跑恢复（{@code BattleUnfreezeRecoveryTest}）、
 * 写锁在途的冻结不沿用（{@code PrepareBattleTest}）。
 */
class BattleRecoveryTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final int NODE = BattleFixture.BATTLE_NODE;
    private static final int FIGHTING_TTL = (int) (BattleFixture.BATTLE_MILLIS / 1000 + 60);
    private static final int PREPARING_TTL = (int) (BattleFixture.PREPARE_MILLIS / 1000 + 60);
    private static final int ENTER_FAILED = 3023;
    private static final int FEATURE_UNAVAILABLE = 1006;
    private static final int ENTER_SCENE = Contracts.IDS.notifyEnterScene();
    private static final int ACTOR_CREATE = Contracts.IDS.notifyActorCreate();
    private static final int ACTOR_LIST_CREATE = Contracts.IDS.notifyActorListCreate();

    private final BattleFixture f = new BattleFixture();

    private static PlayerState ledgerOf(long... battleIds) {
        BattleLedgerState.Builder ledger = BattleLedgerState.newBuilder();
        for (long id : battleIds) {
            ledger.addApplied(BattleLedgerEntry.newBuilder().setBattleId(id).setAppliedAtMs(1));
        }
        return PlayerState.newBuilder().setBattleLedger(ledger).build();
    }

    /** 带气血终值的结算（法力给大值 = 夹到满）。 */
    private static BattleSettlementData settlement(long playerId, long battleId, long gold, long health) {
        return BattleFixture.settlement(playerId, battleId, gold).setHealth(health).build();
    }

    private ScenePlayer load(PlayerState state) {
        return f.load(SESSION, PLAYER, 1, f.scene1, state);
    }

    private ScenePlayer load() {
        return load(PlayerState.getDefaultInstance());
    }

    private void putFightingLock(long playerId, long battleId) {
        f.locks.putLock(playerId, battleId, NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, FIGHTING_TTL);
    }

    private double rebuildsTotal(String reason) {
        double total = 0;
        for (String result : List.of("rebuilt", "reverted", "ledger_hit", "skipped_corrupt", "skipped_pending", "miss", "error")) {
            total += f.rebuilds(reason, result);
        }
        return total;
    }

    private List<Long> endedBattles(ScenePlayer player) {
        return f.battleEnds(player).stream().map(BattleEndS2C::getBattleId).toList();
    }

    private void banGold(boolean banned) {
        f.currency.applyGlobalBlocks(banned ? new GlobalGainBlocks(Set.of(Wallet.GOLD), Set.of()) : GlobalGainBlocks.NONE);
    }

    // ------------------------------------------------------------------ 第 3 步：按锁重建

    /** 锁是备战态：重建 PREPARING（节点号、两个期限取锁），TOUCH 按备战期限复核；PREPARING 永不推 144（房间还没建，B5）。 */
    @Test
    void 重建PREPARING_字段取锁_按备战期限复核_不推144() {
        long deadline = f.deadline();
        long prepareDeadline = f.prepareDeadline();
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, deadline, prepareDeadline, PREPARING_TTL);

        ScenePlayer player = load();
        assertThat(player.battle().recovery()).as("恢复读回来之前").isEqualTo(Recovery.PENDING);
        assertThat(player.inBattle()).isFalse();
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).isNotNull();
        assertThat(freeze.battleId()).isEqualTo(X);
        assertThat(freeze.battleNodeId()).isEqualTo(NODE);
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(freeze.prepareDeadlineMs()).isEqualTo(prepareDeadline);
        assertThat(freeze.preparedHere()).as("不是本实例备战的").isFalse();
        assertThat(freeze.lockExtended()).as("锁上还是 P：没有续过期").isFalse();
        assertThat(freeze.lockPending()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.TOUCH);
        Call touch = f.locks.last(Op.TOUCH);
        assertThat(touch.battleId()).isEqualTo(X);
        assertThat(touch.args()).containsEntry("state", "P").containsEntry("ttl", (long) PREPARING_TTL)
                .containsEntry("deadline", deadline).containsEntry("prepareDeadline", prepareDeadline);
        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_HIT);
        assertThat(f.messageIds(player)).as("只有进场下行，没有 144").containsExactly(ENTER_SCENE, ACTOR_CREATE);
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.recoveries("ready")).isEqualTo(1);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("P");
    }

    /**
     * 锁是战斗态：重建 FIGHTING 并推 144。进场下行（79 / 21 / 47）是同步发的、恢复是异步的，所以 144 一定排在它们之后；
     * 复核（TOUCH）命中之前不推。
     */
    @Test
    void 重建FIGHTING_推144_在79_21_47之后() {
        f.enter(SESSION + 1, 2002);
        long deadline = f.deadline();
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, deadline, f.prepareDeadline(), FIGHTING_TTL);
        f.locks.hold(Op.TOUCH);

        ScenePlayer player = load();
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(freeze.lockExtended()).as("锁上已是 F：之前的确认续过期").isTrue();
        assertThat(f.messageIds(player)).as("复核回来之前只有进场下行").containsExactly(ENTER_SCENE, ACTOR_CREATE, ACTOR_LIST_CREATE);
        Call touch = f.locks.take(Op.TOUCH);
        assertThat(touch.args()).containsEntry("state", "F").containsEntry("ttl", (long) FIGHTING_TTL).containsEntry("deadline", deadline);

        touch.complete();
        f.drain();

        assertThat(f.messageIds(player)).containsExactly(ENTER_SCENE, ACTOR_CREATE, ACTOR_LIST_CREATE, f.reconnectHintId);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.hints("login")).isEqualTo(1);
        assertThat(freeze.preparedHere()).as("144 已推过，之后的补发确认不再推").isTrue();
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    @Test
    void 无锁_不重建_只有一次恢复读() {
        ScenePlayer player = load();
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ);
        assertThat(rebuildsTotal("login")).isZero();
        assertThat(f.messageIds(player)).containsExactly(ENTER_SCENE, ACTOR_CREATE);
        assertThat(f.recoveries("ready")).isEqualTo(1);
    }

    /**
     * 恢复读与复核是两次往返：之间锁没了（到期 / 被取消删掉）→ 复核没命中，撤销刚重建的冻结。撤销不删锁（它已不是我们的），
     * 不推 144（哪怕重建的是 FIGHTING），并立即补一次组队跟随。
     */
    @Test
    void 复核没命中_撤销重建的冻结_不删锁_不推144() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = load();
        f.drain();
        assertThat(player.inBattle()).as("复核回来之前先冻着（各在途闸已生效）").isTrue();
        f.locks.removeLock(PLAYER);
        f.follows.clear();

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.rebuilds("login", "reverted")).isEqualTo(1);
        assertThat(f.rebuilds("login", "rebuilt")).isZero();
        assertThat(f.locks.count(Op.DELETE_IF_MATCH) + f.locks.count(Op.DELETE_PREPARING)).isZero();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(BattleFixture.tipOf(f.prepared(PLAYER, 8))).as("撤销后可以正常备战").isZero();
    }

    // ------------------------------------------------------------------ 复核回调的守卫（§7.4：先核对实例与冻结对象，再动状态）

    /**
     * 守卫的「冻结对象」一半：重建出 FIGHTING、复核在途时<b>这一局的结算先到并应用</b>，重建的冻结已摘（锁按 HOLD 留到落盘，所以复核在 Redis 上
     * 仍然命中）。命中的回复回来时不得再推 144——那是一场已经结束的战斗的重连提示，客户端会拿它去补签一个不存在的房间——也不计重建。
     */
    @Test
    void 复核在途时本局结算已应用并摘了冻结_复核命中回来_丢弃_不推144_不计重建() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = load();
        f.drain();
        BattleFreeze rebuilt = player.battle().freeze();
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        BattleSettlementData settlement = settlement(PLAYER, X, 100, 200);
        f.store(settlement);

        CompletableFuture<SceneBattleReply> delivered = f.deliver(settlement);
        f.drain();

        assertThat(BattleFixture.done(delivered).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(player.inBattle()).as("应用后摘掉了重建的冻结").isFalse();
        assertThat(endedBattles(player)).containsExactly(X);
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁留到落盘").isEqualTo(X);
        f.follows.clear();

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).as("锁还在：复核在 Redis 上是命中的").isEqualTo(BattleRedis.TOUCH_HIT);
        assertThat(f.reconnectHints(player)).as("这一局已经结束：不推 144").isEmpty();
        assertThat(f.messageIds(player)).containsExactly(ENTER_SCENE, ACTOR_CREATE, f.battleEndId);
        assertThat(f.hints("login")).isZero();
        assertThat(rebuildsTotal("login")).as("过期的复核结果不计任何重建结局").isZero();
        assertThat(player.inBattle()).isFalse();
        assertThat(rebuilt.preparedHere()).as("摘掉的那个冻结对象没有再被动过").isFalse();
        assertThat(f.follows.freezeCleared).isEmpty();
    }

    /**
     * 同一半的另一面——挂着的已是<b>下一局</b>的冻结：重建出 PREPARING X、复核在途时 X 被取消（备战锁删了），玩家又备战了下一局（写锁在途）。
     * X 的复核落空回来时能撤销的只有它自己重建的那个冻结；现在挂着的不是它，不得动——否则下一局的冻结被摘，下一局的写锁回调随即按
     * 「冻结已不是这个」删锁并回 1004，一次正常的备战就这样失败。
     */
    @Test
    void 复核在途时被取消又备战了下一局_旧复核落空回来_不摘下一局的冻结_下一局备战照常成功() {
        long next = 8;
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARING_TTL);
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = load();
        f.drain();
        assertThat(player.battle().freeze().battleId()).isEqualTo(X);
        f.cancel(PLAYER, X);
        f.drain();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.lock(PLAYER)).as("取消删了备战锁").isNull();
        f.locks.hold(Op.PREPARE_LOCK);
        CompletableFuture<PrepareBattleResponse> prepared = f.prepare(PLAYER, next);
        BattleFreeze nextFreeze = player.battle().freeze();
        assertThat(nextFreeze.battleId()).isEqualTo(next);
        assertThat(nextFreeze.lockPending()).isTrue();
        f.follows.clear();

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.battleId()).isEqualTo(X);
        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(player.battle().freeze()).as("挂着的不是这次复核重建的冻结：不动").isSameAs(nextFreeze);
        assertThat(rebuildsTotal("login")).as("不计 reverted，也不计别的").isZero();
        assertThat(f.follows.freezeCleared).as("没有解冻，也就没有补跟随").isEmpty();
        assertThat(prepared).as("下一局的备战还在等它自己的写锁").isNotDone();

        f.locks.take(Op.PREPARE_LOCK).complete();
        f.drain();

        assertThat(BattleFixture.tipOf(BattleFixture.done(prepared))).as("下一局的备战照常成功").isZero();
        assertThat(player.battle().freeze()).isSameAs(nextFreeze);
        assertThat(nextFreeze.lockPending()).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(next);
        assertThat(f.prepares("stale")).isZero();
        assertThat(f.locks.calls(Op.DELETE_PREPARING)).extracting(Call::battleId).as("只有取消 X 的那一次删锁").containsExactly(X);
    }

    /** 守卫的「实例」一半：复核命中的回复回来时玩家已离场——不对离场的实例推 144、不计重建。 */
    @Test
    void 复核回来时玩家已离场_丢弃_不推144_不计重建() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.TOUCH);
        ScenePlayer player = load();
        f.drain();
        assertThat(player.inBattle()).isTrue();
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();
        assertThat(player.inBattle()).as("前提：离场的实例身上冻结对象还挂着，守卫的冻结一半拦不住这次回调").isTrue();
        f.sink.clear();
        f.follows.clear();

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_HIT);
        assertThat(f.messageIds(player)).as("离场的会话什么都不收（没有 144）").isEmpty();
        assertThat(f.hints("login")).isZero();
        assertThat(rebuildsTotal("login")).isZero();
        assertThat(f.follows.freezeCleared).isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁不动：这一局还在打，下次进场再重建").isEqualTo(X);
    }

    /**
     * 实例一半的另一面——同 epoch 重进：新实例沿用了冻结（新对象，沿用时已按规则推过 144），旧实例那次复核落空回来（锁在此期间没了）。
     * 旧回调撤销的只能是旧实例上它自己重建的冻结：不得去动已被移除的旧实例、不得拿它补组队跟随，新实例沿用的冻结也不受影响
     * （它由 reaper 按期限处理，不由别的实例的过期复核裁决）。
     */
    @Test
    void 复核在途时同epoch重进_旧实例的复核落空回来_丢弃_不撤销不补跟随_新实例沿用的冻结不动() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.TOUCH);
        ScenePlayer old = load();
        f.drain();
        BattleFreeze rebuilt = old.battle().freeze();
        assertThat(rebuilt).isNotNull();

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        f.drain();

        assertThat(fresh).isNotSameAs(old);
        BattleFreeze carried = fresh.battle().freeze();
        assertThat(carried).as("沿用的是新对象").isNotNull().isNotSameAs(rebuilt);
        assertThat(f.reconnectHints(fresh)).as("沿用时（会话换了）推的那一条").containsExactly(X);
        assertThat(f.locks.pending(Op.TOUCH)).as("新实例已有冻结、不再复核：在途的只有旧实例那一次").hasSize(1);
        f.locks.removeLock(PLAYER);
        f.follows.clear();
        BattleFreeze leftOnOld = old.battle().freeze();
        assertThat(leftOnOld).as("前提：旧实例被移除时冻结对象还挂在它身上，守卫的冻结一半拦不住这次回调").isSameAs(rebuilt);

        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_MISS);
        assertThat(rebuildsTotal("login")).as("不计 reverted").isZero();
        assertThat(f.follows.freezeCleared).as("不拿已被移除的实例去补跟随").isEmpty();
        assertThat(old.battle().freeze()).as("已被移除的旧实例不再被改动").isSameAs(leftOnOld);
        assertThat(fresh.battle().freeze()).isSameAs(carried);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
        assertThat(f.reconnectHints(old)).isEmpty();
    }

    /**
     * 锁指向账本里已有的局（已应用、待销账）：这一局早打完了，不重建，销账（放锁）。账本里其余条目（第 4 步）也逐个销——
     * 加载自库的条目天然已落盘。
     */
    @Test
    void 锁指向账本里已有的局_销账放锁不重建_账本其余条目也逐个销账() {
        putFightingLock(PLAYER, X);

        ScenePlayer player = load(ledgerOf(9, X));
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.locks.ops()).as("先销锁指向的那一局，再销其余的；没有复核").containsExactly(Op.ENTER_READ, Op.ACK, Op.ACK);
        assertThat(f.locks.calls(Op.ACK)).extracting(Call::battleId).containsExactly(X, 9L);
        assertThat(f.locks.calls(Op.ACK)).extracting(Call::lastReply).as("X 放了锁（位 2）；9 什么都没有").containsExactly(2L, 0L);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.battleLedger().size()).as("销账回来后都摘掉").isZero();
        assertThat(f.rebuilds("login", "ledger_hit")).isEqualTo(1);
        assertThat(f.acks("login", "released")).isEqualTo(1);
        assertThat(f.acks("login", "not_ours")).isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    /**
     * 按锁重建挂上冻结的那一步<b>同时停步</b>：移动上行此后被在途闸丢弃，旧速度不能继续被外推。恢复读在途时玩家可以照常走动（在途闸只看冻结），
     * 读回来、冻结挂上的同一步速度清零并置脏位（旁观者下一个同步帧收到速度 0 的 66）——不等复核回来。
     */
    @Test
    void 按锁重建时停步_挂冻结的同一步速度清零并置脏位_不等复核() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.ENTER_READ, Op.TOUCH);
        ScenePlayer player = load();
        WorldTestAccess.setVelocity(player, new Vec3(1, 0, 0));
        assertThat(WorldTestAccess.velocityDirty(player)).isFalse();

        f.locks.take(Op.ENTER_READ).complete();
        assertThat(player.velocity().isOrigin()).as("读的结局回到逻辑线程之前不动").isFalse();
        f.drain();

        assertThat(player.inBattle()).isTrue();
        assertThat(f.locks.pending(Op.TOUCH)).as("复核还没回来").hasSize(1);
        assertThat(player.velocity().isOrigin()).isTrue();
        assertThat(WorldTestAccess.velocityDirty(player)).isTrue();
    }

    /**
     * §1.2 的取值规则接进重建流程：锁上只有 {@code b}（没有 s / d / p / n）→ 按战斗态重建，期限按锁的剩余 TTL 反推（现在 + TTL），节点号 0；
     * 复核（TOUCH）带的是<b>反推出来的</b>值，命中后把它们写回锁上、TTL 续成「反推的期限 + 60 s」。
     */
    @Test
    void 锁缺字段_按战斗态重建_期限按剩余TTL反推_复核把反推的值写回锁上() {
        f.locks.putLockFields(PLAYER, Map.of(BattleRedis.FIELD_BATTLE, Long.toUnsignedString(X)), 100);
        long inferred = f.clock.epochMillis() + 100_000;

        ScenePlayer player = load();
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).isNotNull();
        assertThat(freeze.battleId()).isEqualTo(X);
        assertThat(freeze.phase()).as("s 缺失按 FIGHTING（宁可多冻）").isEqualTo(Phase.FIGHTING);
        assertThat(freeze.deadlineMs()).as("d 缺失：现在 + 锁的剩余 TTL").isEqualTo(inferred);
        assertThat(freeze.prepareDeadlineMs()).isZero();
        assertThat(freeze.battleNodeId()).isZero();
        Call touch = f.locks.last(Op.TOUCH);
        assertThat(touch.args()).containsEntry("state", "F").containsEntry("deadline", inferred).containsEntry("prepareDeadline", 0L)
                .containsEntry("ttl", 160L);
        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_HIT);
        assertThat(f.locks.lock(PLAYER)).containsEntry(BattleRedis.FIELD_STATE, "F")
                .containsEntry(BattleRedis.FIELD_DEADLINE, Long.toUnsignedString(inferred))
                .containsEntry(BattleRedis.FIELD_PREPARE_DEADLINE, "0");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(160);
        assertThat(f.reconnectHints(player)).as("按战斗态重建的照常推 144").containsExactly(X);
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
    }

    /** 备战锁缺备战期限（{@code p}）→ 取战斗期限；有效期限、复核的 TTL 都按它算，不会因为 p = 0 被当成「早已过期」。 */
    @Test
    void 备战锁缺备战期限_取战斗期限_复核的TTL按它算_reaper不会当场摘掉() {
        long deadline = f.deadline();
        f.locks.putLockFields(PLAYER, Map.of(BattleRedis.FIELD_BATTLE, Long.toUnsignedString(X),
                BattleRedis.FIELD_NODE, Integer.toString(NODE), BattleRedis.FIELD_STATE, BattleRedis.STATE_PREPARING,
                BattleRedis.FIELD_DEADLINE, Long.toUnsignedString(deadline)), 100);

        ScenePlayer player = load();
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze.phase()).isEqualTo(Phase.PREPARING);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(freeze.prepareDeadlineMs()).as("P 缺 p 取 d").isEqualTo(deadline);
        assertThat(freeze.battleNodeId()).isEqualTo(NODE);
        Call touch = f.locks.last(Op.TOUCH);
        assertThat(touch.args()).containsEntry("state", "P").containsEntry("deadline", deadline).containsEntry("prepareDeadline", deadline)
                .containsEntry("ttl", (long) FIGHTING_TTL);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHTING_TTL);
        assertThat(f.reconnectHints(player)).isEmpty();

        f.advance(BattleFixture.PREPARE_MILLIS + 1);
        f.reap();
        assertThat(player.battle().freeze()).as("备战期限取的是战斗期限：过了缺省备战时长也不到期").isSameAs(freeze);
    }

    // ------------------------------------------------------------------ 第 2 步：待结算记录

    /**
     * 坏字段（解析失败、blob 里的 battle_id ≠ 字段名、player_id ≠ 自己、没有 settlement、字段名不是规范的无符号十进制）只删<b>该字段</b>
     * （按字段名的原始字节 HDEL，不销账、不写墓碑），好的记录照常应用；锁指向坏字段的那一局时不重建（这一局已经结束，结算记录就是证据）。
     */
    @Test
    void 坏字段只删该字段_好的记录照常应用_锁指向坏字段时不重建() {
        f.locks.storeSettlement(PLAYER, 7, "不是 protobuf".getBytes(StandardCharsets.UTF_8));
        f.locks.storeSettlement(PLAYER, 8, FakeBattleLocks.record(settlement(PLAYER, 80, 50, 100)));
        f.locks.storeSettlement(PLAYER, 9, FakeBattleLocks.record(settlement(2002, 9, 50, 100)));
        f.locks.storeSettlement(PLAYER, 10, new byte[0]);
        f.locks.putRawSettlementField(PLAYER, "abc".getBytes(StandardCharsets.UTF_8), FakeBattleLocks.record(settlement(PLAYER, 11, 50, 100)));
        f.locks.putRawSettlementField(PLAYER, "012".getBytes(StandardCharsets.UTF_8), FakeBattleLocks.record(settlement(PLAYER, 12, 50, 100)));
        f.locks.putRawSettlementField(PLAYER, new byte[] {(byte) 0xff, (byte) 0xfe}, FakeBattleLocks.record(settlement(PLAYER, 13, 50, 100)));
        f.store(settlement(PLAYER, 20, 100, 300));
        putFightingLock(PLAYER, 8);

        ScenePlayer player = load();
        f.drain();

        assertThat(f.locks.calls(Op.DELETE_FIELD))
                .extracting(c -> new String((byte[]) c.args().get("field"), StandardCharsets.ISO_8859_1))
                .as("按字段名的原始字节删").containsExactlyInAnyOrder("7", "8", "9", "10", "abc", "012", "ÿþ");
        assertThat(f.count("xm.scene.battle.pending.corrupt")).isEqualTo(7);
        assertThat(f.gold(player)).as("只有那条好记录的金币；坏字段里的 blob 一份都没应用").isEqualTo(100);
        assertThat(player.attributes().health()).isEqualTo(300);
        assertThat(endedBattles(player)).containsExactly(20L);
        assertThat(f.locks.settlementCount(PLAYER)).as("坏字段都删了，只剩那条好记录（没落盘不销账）").isEqualTo(1);
        assertThat(f.locks.hasSettlement(PLAYER, 20)).isTrue();
        assertThat(f.locks.count(Op.ACK)).as("坏字段不走销账").isZero();
        assertThat(f.locks.settled(PLAYER, 8)).as("也不写墓碑").isFalse();
        assertThat(player.inBattle()).as("锁指向坏字段的那一局：不重建").isFalse();
        assertThat(f.locks.count(Op.TOUCH)).isZero();
        assertThat(f.rebuilds("login", "skipped_corrupt")).isEqualTo(1);
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁不动，留给 TTL").isEqualTo(8);
        assertThat(player.battle().recovery()).as("坏字段不算延后").isEqualTo(Recovery.READY);
    }

    /**
     * 多条记录按 battle_id <b>无符号</b>升序逐个应用（雪花号升序即时间顺序；气血是终值，顺序就是语义）。高位为 1 的 id 有符号看是负数，
     * 按有符号排会被排到最前、气血终值就错了。
     */
    @Test
    void 多条记录按battle_id无符号升序应用_气血取最后一局的终值() {
        long huge = 0x8000_0000_0000_0005L;
        // Redis 里的字段次序与 battle_id 无关
        f.store(settlement(PLAYER, 9, 100, 300));
        f.store(settlement(PLAYER, huge, 1_000, 400));
        f.store(settlement(PLAYER, 7, 1, 100));
        f.store(settlement(PLAYER, 8, 10, 200));

        ScenePlayer player = load();
        f.drain();

        assertThat(endedBattles(player)).as("150 的次序 = 应用次序").containsExactly(7L, 8L, 9L, huge);
        assertThat(f.audit.currencies).extracting(c -> c.correlationId()).containsExactly(7L, 8L, 9L, huge);
        assertThat(player.attributes().health()).as("最后应用的是 id 最大（无符号）的那一局").isEqualTo(400);
        assertThat(f.gold(player)).isEqualTo(1_111);
        assertThat(f.locks.calls(Op.HOLD)).extracting(Call::battleId).containsExactly(7L, 8L, 9L, huge);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(4);
        assertThat(player.battleLedger().battleIds()).containsExactly(7L, 8L, 9L, huge);
        assertThat(f.locks.count(Op.ACK)).as("都还没落盘").isZero();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.messageIds(player)).as("150 都在进场下行之后").startsWith(ENTER_SCENE, ACTOR_CREATE);

        // 第一笔存盘的快照拍在第 7 局应用之后、其余各局之前：落盘后只销第 7 局，并为其余的再压一笔
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();
        assertThat(f.locks.calls(Op.ACK)).extracting(Call::battleId).containsExactly(7L);
        assertThat(f.repo.pendingProgress()).isEqualTo(1);
        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();
        assertThat(f.locks.calls(Op.ACK)).extracting(Call::battleId).containsExactly(7L, 8L, 9L, huge);
        assertThat(f.locks.settlementCount(PLAYER)).isZero();
        assertThat(player.battleLedger().size()).isZero();
    }

    /**
     * 遇延后即停：第 8 局的金币被拒（全服封禁）→ 恢复置 RETRY，不越过它应用更新的第 9 局（哪怕第 9 局自己能应用）。
     * 解封后 reaper 重跑恢复，按局序补齐，恰好各一次。
     */
    @Test
    void 遇延后即停_不越过它应用更新的局_解封后reaper重跑按局序补齐() {
        banGold(true);
        // 金币为 0 的结算不受封禁影响
        f.store(settlement(PLAYER, 9, 0, 300));
        f.store(settlement(PLAYER, 7, 0, 100));
        f.store(settlement(PLAYER, 8, 20, 200));

        ScenePlayer player = load();
        f.drain();

        assertThat(endedBattles(player)).containsExactly(7L);
        assertThat(player.attributes().health()).as("停在第 7 局的终值").isEqualTo(100);
        assertThat(player.battleLedger().battleIds()).containsExactly(7L);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("login", "deferred_currency")).isEqualTo(1);
        assertThat(f.locks.hasSettlement(PLAYER, 8)).isTrue();
        assertThat(f.locks.hasSettlement(PLAYER, 9)).as("没轮到的也留着").isTrue();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        assertThat(f.recoveries("retry")).isEqualTo(1);
        assertThat(BattleFixture.tipOf(BattleFixture.done(f.prepare(PLAYER, 30)))).as("恢复没就绪：备战 1006").isEqualTo(FEATURE_UNAVAILABLE);

        f.reap();
        assertThat(player.battle().recovery()).as("封禁还在：重跑仍停在第 8 局").isEqualTo(Recovery.RETRY);
        assertThat(endedBattles(player)).containsExactly(7L);

        banGold(false);
        f.reap();

        assertThat(endedBattles(player)).as("按局序补齐，第 7 局不重复").containsExactly(7L, 8L, 9L);
        assertThat(player.attributes().health()).isEqualTo(300);
        assertThat(f.gold(player)).isEqualTo(20);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(3);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    /**
     * 延后之后<b>锁步骤照做</b>（评审修订第 7 条）：锁指向一场没有结算记录、仍在进行的局 → 照常重建 FIGHTING 并推 144。
     * 原稿遇延后整步停下：金币长期被封时，这场在打的局在本实例上没有冻结，各在途闸全部敞开、也不推 144。
     */
    @Test
    void 延后之后锁步骤照做_锁指向没有记录的在途局_重建FIGHTING并推144() {
        banGold(true);
        f.store(settlement(PLAYER, 7, 20, 200));
        putFightingLock(PLAYER, 20);

        ScenePlayer player = load();
        f.drain();

        assertThat(f.gold(player)).isZero();
        assertThat(player.battle().recovery()).as("有延后的记录").isEqualTo(Recovery.RETRY);
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).as("在途的那一局照常重建").isNotNull();
        assertThat(freeze.battleId()).isEqualTo(20);
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(20L);
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.TOUCH);
        assertThat(f.locks.hasSettlement(PLAYER, 7)).isTrue();
    }

    /**
     * 延后之后锁步骤照做的另一半：锁指向<b>延后的那一局</b>或排在它后面<b>还没轮到的局</b>时不重建——这一局已经结束（结算记录就是证据），
     * 重建只会把玩家冻进一场打完的战斗。解封后重跑：两局按序应用，仍不重建。
     */
    @Test
    void 延后之后_锁指向延后的那一局或还没轮到的局_不重建_解封重跑后应用也不重建() {
        banGold(true);
        // 1001：锁指向被延后的第 7 局
        f.store(settlement(PLAYER, 7, 20, 200));
        putFightingLock(PLAYER, 7);
        // 2002：锁指向排在延后那一局之后、还没轮到的第 9 局
        f.store(settlement(2002, 7, 20, 200));
        f.store(settlement(2002, 9, 30, 300));
        putFightingLock(2002, 9);

        ScenePlayer deferred = load();
        ScenePlayer notReached = f.load(SESSION + 1, 2002, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();

        for (ScenePlayer player : List.of(deferred, notReached)) {
            assertThat(player.inBattle()).as("player=%s", player.playerId()).isFalse();
            assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
            assertThat(f.gold(player)).isZero();
            assertThat(f.reconnectHints(player)).isEmpty();
        }
        assertThat(f.rebuilds("login", "skipped_pending")).isEqualTo(2);
        assertThat(f.locks.count(Op.TOUCH)).isZero();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁不动").isEqualTo(7);
        assertThat(f.locks.lockBattleId(2002)).isEqualTo(9);

        banGold(false);
        f.reap();

        assertThat(f.gold(deferred)).isEqualTo(20);
        assertThat(f.gold(notReached)).isEqualTo(50);
        assertThat(endedBattles(notReached)).containsExactly(7L, 9L);
        for (ScenePlayer player : List.of(deferred, notReached)) {
            assertThat(player.inBattle()).isFalse();
            assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        }
        assertThat(f.rebuilds("login", "ledger_hit")).as("本轮刚应用的局：锁还指着它也不重建").isEqualTo(2);
        assertThat(f.rebuilds("login", "rebuilt")).isZero();
    }

    /** 登录补应用不看锁、不要求冻结匹配：离线期间打完、锁早已过期的局照常补发（锁 TTL 只有期限 + 60 s，记录活 7 天）。 */
    @Test
    void 待结算记录的应用不看锁_锁不在或是别的局都照常补发() {
        f.store(settlement(PLAYER, 7, 100, 200));
        f.locks.putLock(PLAYER, 30, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), PREPARING_TTL);

        ScenePlayer player = load();
        f.drain();

        assertThat(f.gold(player)).isEqualTo(100);
        assertThat(endedBattles(player)).containsExactly(7L);
        assertThat(f.locks.last(Op.HOLD).lastReply()).as("锁不是这一局：续锁落空，无妨").isEqualTo(0L);
        assertThat(player.battle().freeze().battleId()).as("锁指向的下一局照常重建").isEqualTo(30);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(PREPARING_TTL);
    }

    // ------------------------------------------------------------------ 第 1 步：恢复读失败与恢复未就绪的窗口

    /**
     * 读失败（Redis 断开）→ RETRY：期间这名玩家的备战回 1006、结算延后（都不碰 Redis），由 reaper 重读；重读成功后 READY，
     * 锁步骤这时才补上（重建 FIGHTING、推 144）。
     */
    @Test
    void 恢复读失败_置RETRY_期间备战1006结算延后_reaper重读后就绪并补上重建() {
        putFightingLock(PLAYER, X);
        f.locks.failNext(Op.ENTER_READ, new RuntimeException("Redis 不可用"));

        ScenePlayer player = load();
        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        assertThat(f.recoveries("retry")).isEqualTo(1);
        assertThat(f.recoveries("ready")).isZero();
        assertThat(player.inBattle()).as("没读到锁，重建不了").isFalse();
        assertThat(BattleFixture.tipOf(BattleFixture.done(f.prepare(PLAYER, 8)))).isEqualTo(FEATURE_UNAVAILABLE);
        CompletableFuture<SceneBattleReply> delivered = f.deliver(BattleFixture.settlement(PLAYER, X, 100).build());
        assertThat(BattleFixture.done(delivered).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        assertThat(f.gold(player)).isZero();
        assertThat(f.locks.ops()).as("只有那次失败的恢复读").containsExactly(Op.ENTER_READ);

        f.reap();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.recoveries("ready")).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.ENTER_READ, Op.TOUCH);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);

        f.locks.clearCalls();
        f.reap();
        assertThat(f.locks.calls()).as("就绪之后 reaper 不再重读").isEmpty();
    }

    /** 恢复读一直失败：每轮 reaper 重读一次，状态保持 RETRY，不会停在 PENDING。 */
    @Test
    void 恢复读一直失败_每轮reaper重读一次_保持RETRY() {
        f.locks.failAlways(Op.ENTER_READ, new RuntimeException("Redis 不可用"));
        ScenePlayer player = load();
        f.drain();

        f.reap();
        f.reap();

        assertThat(f.locks.count(Op.ENTER_READ)).isEqualTo(3);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        assertThat(f.recoveries("retry")).isEqualTo(3);

        f.locks.heal();
        f.reap();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    /**
     * 进场恢复完成之前，<b>在途闸不受影响</b>（它们只看冻结）：否则每次登录后马上发的 63 都会因为一次 Redis 往返被拒。
     */
    @Test
    void 恢复读在途时_换场景照常_在途闸只看冻结() {
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = load();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);
        assertThat(player.scene()).isSameAs(f.scene1);

        f.enterScene(player, f.scene2.sceneId(), f.scene2.configId());

        assertThat(player.scene()).as("63 没有因为恢复未就绪被拒").isSameAs(f.scene2);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);
    }

    @Test
    void 恢复读回来时玩家已离场_丢弃_记录不动() {
        f.store(settlement(PLAYER, 7, 100, 200));
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = load();
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();

        assertThat(f.gold(player)).as("离场的实例不再被应用").isZero();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ);
        assertThat(f.locks.hasSettlement(PLAYER, 7)).isTrue();
        assertThat(f.recoveries("ready") + f.recoveries("retry")).isZero();
    }

    // ------------------------------------------------------------------ 第 0 步：同 epoch 沿用旧实例的冻结

    /**
     * 同 epoch 重进且换了会话（重连 / 顶号回同一节点）：把旧冻结<b>复制成新对象</b>挂到新实例——运行态标记复位（{@code rescuing}、
     * {@code cancelRequested}），{@code lockExtended} 照抄；FIGHTING 当场推 144（新会话的客户端要凭它补签重连）。
     * 带着 {@code rescuing = true} 搬同一个对象过来，reaper 会永远跳过它、冻结永不过期。
     */
    @Test
    void 同epoch换会话_沿用FIGHTING冻结_是新对象_运行态复位_当场推144_旧实例在途的rescue读被丢弃() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleFreeze original = old.battle().freeze();
        // 让旧冻结带上运行态标记：reaper 的 rescue 读在途
        f.advance(BattleFixture.BATTLE_MILLIS + 10_001);
        f.locks.hold(Op.READ_SETTLEMENT);
        f.reap();
        assertThat(original.rescuing()).isTrue();
        original.requestCancel();
        assertThat(original.lockExtended()).isTrue();
        f.locks.clearCalls();

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);

        assertThat(fresh).isNotSameAs(old);
        BattleFreeze carried = fresh.battle().freeze();
        assertThat(carried).as("沿用的是新对象").isNotNull().isNotSameAs(original);
        assertThat(carried.battleId()).isEqualTo(X);
        assertThat(carried.battleNodeId()).isEqualTo(NODE);
        assertThat(carried.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(carried.deadlineMs()).isEqualTo(original.deadlineMs());
        assertThat(carried.prepareDeadlineMs()).isEqualTo(original.prepareDeadlineMs());
        assertThat(carried.rescuing()).as("运行态标记复位").isFalse();
        assertThat(carried.cancelRequested()).isFalse();
        assertThat(carried.lockPending()).isFalse();
        assertThat(carried.lockExtended()).as("续期结论照抄").isTrue();
        assertThat(f.messageIds(fresh)).as("会话换了：进场下行之后当场推 144，不等恢复读").containsExactly(ENTER_SCENE, ACTOR_CREATE, f.reconnectHintId);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
        assertThat(f.reconnectHints(old)).as("旧会话不收").isEmpty();
        assertThat(f.hints("carried")).isEqualTo(1);
        assertThat(f.rebuilds("carried", "rebuilt")).isEqualTo(1);

        f.drain();

        assertThat(fresh.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.locks.count(Op.TOUCH)).as("已有冻结：恢复的锁步骤不再重建").isZero();
        assertThat(f.reconnectHints(fresh)).as("144 只有一条").containsExactly(X);

        // 旧实例上在途的 rescue 读现在才回来（没有本局记录）：实例已换，丢弃——不得把新实例的冻结判废
        f.locks.take(Op.READ_SETTLEMENT).complete();
        f.drain();
        assertThat(fresh.battle().freeze()).isSameAs(carried);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.rescues("miss")).isZero();

        // 新冻结没带着 rescuing：下一轮 reaper 照常为它发 rescue 读
        f.battle.reap();
        assertThat(carried.rescuing()).isTrue();
        assertThat(f.locks.pending(Op.READ_SETTLEMENT)).hasSize(1);
    }

    /** 同 epoch、同一个会话的重复进场：照样沿用（新对象），但不推 144——这个会话的客户端本来就在战斗里。 */
    @Test
    void 同epoch同会话重复进场_沿用FIGHTING冻结_不推144() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleFreeze original = old.battle().freeze();
        f.sink.clear();
        f.locks.clearCalls();

        ScenePlayer fresh = f.reenter(SESSION, PLAYER, 1, f.scene1);
        f.drain();

        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.session()).isEqualTo(old.session());
        BattleFreeze carried = fresh.battle().freeze();
        assertThat(carried).isNotNull().isNotSameAs(original);
        assertThat(carried.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(original.preparedHere()).isTrue();
        assertThat(carried.preparedHere()).as("会话没变：「144 不必再推」照抄旧值（R2-b）").isTrue();
        assertThat(f.messageIds(fresh)).as("只有进场下行").containsExactly(ENTER_SCENE, ACTOR_CREATE);
        assertThat(f.hints("carried")).isZero();
        assertThat(f.rebuilds("carried", "rebuilt")).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ);
        assertThat(fresh.battle().recovery()).isEqualTo(Recovery.READY);

        // 之后的补发确认：续期已确认，零 Redis、不推
        f.locks.clearCalls();
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.reconnectHints(fresh)).isEmpty();
    }

    /**
     * 评审 R2-REV-2：同会话重复进场时推不推 144 只看沿用过来的 {@code preparedHere}，不再另判「会话变没变」。旧值为假的 FIGHTING 冻结只有一种来路——
     * 按 F 锁重建之后、复核（TOUCH）回来之前，144 还欠着。这时同一个会话重复进场：旧实例那次复核的回调认的是旧实例，回来即被丢弃；
     * 新实例已有冻结、恢复的锁步骤不再重建；补发的确认走续期已确认的幂等分支。沿用的那一刻不把欠着的这条推掉，这个会话就永远收不到这一局的 144
     * （玩家冻结在 FIGHTING 里而客户端不知道要补签，直到结算或期限；基线重建时不等复核就推）。恰好一条：旧实例的复核随后回来不再推第二条。
     */
    @Test
    void 同epoch同会话重复进场_按锁重建的FIGHTING冻结复核还在途_144还没推过_沿用时当场推一次_旧复核回来不再推() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.TOUCH);
        ScenePlayer old = load();
        f.drain();
        BattleFreeze rebuilt = old.battle().freeze();
        assertThat(rebuilt).isNotNull();
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(rebuilt.preparedHere()).as("复核在途：144 还没推过").isFalse();
        assertThat(f.reconnectHints(old)).isEmpty();
        f.sink.clear();

        ScenePlayer fresh = f.reenter(SESSION, PLAYER, 1, f.scene1);

        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.session()).as("同一个会话").isEqualTo(old.session());
        BattleFreeze carried = fresh.battle().freeze();
        assertThat(carried).as("沿用的是新对象").isNotNull().isNotSameAs(rebuilt);
        assertThat(carried.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.messageIds(fresh)).as("进场下行之后当场把欠着的 144 推掉，不等恢复读").containsExactly(ENTER_SCENE, ACTOR_CREATE, f.reconnectHintId);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
        assertThat(carried.preparedHere()).as("推过了，记下").isTrue();
        assertThat(f.hints("carried")).isEqualTo(1);
        assertThat(f.rebuilds("carried", "rebuilt")).isEqualTo(1);

        f.drain();

        assertThat(fresh.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(fresh.battle().freeze()).isSameAs(carried);
        assertThat(f.locks.pending(Op.TOUCH)).as("新实例已有冻结、不再复核：在途的只有旧实例那一次").hasSize(1);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);

        // 旧实例的复核现在才回来（命中）：实例已换，丢弃——不推第二条、不计登录重建
        Call touch = f.locks.take(Op.TOUCH).complete();
        f.drain();

        assertThat(touch.lastReply()).isEqualTo(BattleRedis.TOUCH_HIT);
        assertThat(f.reconnectHints(fresh)).as("这个会话上这一局的 144 恰好一条").containsExactly(X);
        assertThat(f.hints("login")).isZero();
        assertThat(rebuildsTotal("login")).isZero();

        // 之后的补发确认：锁上本来就是 F、续期已确认，零 Redis、不再推
        f.locks.clearCalls();
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.confirms("idempotent")).isEqualTo(1);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
    }

    /**
     * 沿用过来的冻结对应的局其实已经结算（记录在 Redis、投递还没到）：进场恢复按 login 路径应用，并把沿用的冻结摘掉（锁保留到落盘）。
     * 新会话依次收到 79 / 21 → 144（沿用时推）→ 150。
     */
    @Test
    void 沿用的冻结对应的局已有待结算记录_进场恢复应用并摘掉冻结_锁留到落盘() {
        f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.store(settlement(PLAYER, X, 100, 200));
        f.locks.clearCalls();
        f.follows.clear();

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        assertThat(fresh.inBattle()).isTrue();
        f.drain();

        assertThat(fresh.inBattle()).as("冻结 id == X：应用后摘掉").isFalse();
        assertThat(f.gold(fresh)).isEqualTo(100);
        assertThat(fresh.attributes().health()).isEqualTo(200);
        assertThat(f.messageIds(fresh)).containsExactly(ENTER_SCENE, ACTOR_CREATE, f.reconnectHintId, f.battleEndId);
        assertThat(f.locks.ops()).as("续锁，不删锁；没落盘不销账").containsExactly(Op.ENTER_READ, Op.HOLD);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.follows.freezeCleared).contains(PLAYER);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(fresh.battle().recovery()).isEqualTo(Recovery.READY);
    }

    /** 不同 epoch 的进场（顶号夺权）不沿用旧实例的冻结：新实例按锁重建，推 144 的来由是登录重建。 */
    @Test
    void 新epoch的进场不沿用旧冻结_按锁重建并推144() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleFreeze original = old.battle().freeze();
        f.locks.clearCalls();

        ScenePlayer fresh = f.load(SESSION + 1, PLAYER, 2, f.scene1, PlayerState.getDefaultInstance());
        assertThat(fresh.ownerEpoch()).isEqualTo(2);
        assertThat(fresh.battle().freeze()).as("不沿用").isNull();
        f.drain();

        BattleFreeze rebuilt = fresh.battle().freeze();
        assertThat(rebuilt).isNotNull().isNotSameAs(original);
        assertThat(rebuilt.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(rebuilt.battleId()).isEqualTo(X);
        assertThat(f.rebuilds("carried", "rebuilt")).isZero();
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.TOUCH);
        assertThat(f.reconnectHints(fresh)).containsExactly(X);
        assertThat(f.hints("login")).isEqualTo(1);
        assertThat(f.hints("carried")).isZero();
    }

    /**
     * {@code lockExtended} 照抄的另一半：旧实例上那次续期没成功（确认升级了内存、CONFIRM 脚本失败，锁还是 P）→ 沿用过来仍是「没续过」，
     * 新实例上的下一次补发确认再续一次（D9）。沿用时一律当成「续过了」的话，之后的确认全走零 Redis 的幂等分支，锁停在备战 TTL 提前过期。
     */
    @Test
    void 沿用旧冻结_续期没成功的照抄_新实例上的补发确认再续一次() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        long deadline = f.deadline();
        f.locks.failNext(Op.CONFIRM, new RuntimeException("Redis 超时"));
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(old.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(old.battle().freeze().lockExtended()).isFalse();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("P");
        f.locks.clearCalls();

        ScenePlayer fresh = f.reenter(SESSION, PLAYER, 1, f.scene1);
        f.drain();

        BattleFreeze carried = fresh.battle().freeze();
        assertThat(carried).isNotNull().isNotSameAs(old.battle().freeze());
        assertThat(carried.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(carried.lockExtended()).as("照抄：还没续成功").isFalse();
        assertThat(f.locks.ops()).as("已有冻结：只有恢复读，不复核").containsExactly(Op.ENTER_READ);

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.CONFIRM);
        assertThat(f.locks.last(Op.CONFIRM).args()).containsEntry("deadline", deadline).containsEntry("ttl", (long) FIGHTING_TTL);
        assertThat(carried.lockExtended()).isTrue();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHTING_TTL);
        assertThat(f.confirms("reextended")).isEqualTo(1);

        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(f.locks.count(Op.CONFIRM)).as("续成功之后的补发零 Redis").isEqualTo(1);
    }

    /**
     * 第 0 步「账本随 {@code persistentState()} 自动沿用」：旧实例应用了这一局、那笔在线存盘还没落盘（所以没销账，记录与锁都还在）时同 epoch 重进——
     * 新实例带着金币<b>和</b>账本条目一起过来，恢复读把记录带回来时命中账本：不再发奖、不推 150；库里还没有这一条，所以也不销账，
     * 压自己的一笔存盘，落盘后才销。旧实例那笔存盘之后回来时实例已换，不替新实例销账。
     */
    @Test
    void 同epoch重进_没落盘的账本条目随旧实例内存沿用_恢复读带回的记录不再发奖_新实例落盘后才销账() {
        ScenePlayer old = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = settlement(PLAYER, X, 100, 200);
        f.store(settlement);
        assertThat(BattleFixture.done(f.deliver(settlement)).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        f.drain();
        assertThat(f.repo.pendingProgress()).as("旧实例那笔存盘还在途").isEqualTo(1);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("没落盘就没销账").isTrue();
        f.sink.clear();
        f.locks.clearCalls();

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);

        assertThat(fresh).isNotSameAs(old);
        assertThat(f.gold(fresh)).as("金币随内存沿用").isEqualTo(100);
        assertThat(fresh.battleLedger().has(X)).as("账本条目同在").isTrue();
        assertThat(BattleLedger.persistedHas(fresh.persistedState(), X)).as("库里那份还没有这一条").isFalse();
        f.drain();

        assertThat(f.settlementsCounted("login", "already_applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("login", "applied")).isZero();
        assertThat(f.gold(fresh)).as("不再发奖").isEqualTo(100);
        assertThat(fresh.attributes().health()).isEqualTo(200);
        assertThat(f.audit.currencies).as("金币流水只有旧实例应用时的那一条").hasSize(1);
        assertThat(f.battleEnds(fresh)).as("已应用过的不推 150").isEmpty();
        assertThat(fresh.inBattle()).as("锁还指着这一局，但它已有结算记录：不重建").isFalse();
        assertThat(f.rebuilds("login", "ledger_hit")).isEqualTo(1);
        assertThat(f.locks.ops()).as("续锁；没落盘不销账").containsExactly(Op.ENTER_READ, Op.HOLD);
        assertThat(f.acks("login", "deferred")).isEqualTo(1);
        assertThat(f.repo.pendingProgress()).as("旧实例那一笔 + 新实例为销账压的一笔").isEqualTo(2);
        assertThat(fresh.battle().recovery()).isEqualTo(Recovery.READY);

        // 旧实例那笔存盘先回来：实例已换，不触发销账
        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();
        assertThat(f.locks.count(Op.ACK)).isZero();
        assertThat(fresh.battleLedger().has(X)).isTrue();

        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.drain();

        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.HOLD, Op.ACK);
        assertThat(f.locks.last(Op.ACK).lastReply()).as("删记录 + 放锁").isEqualTo(3L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(fresh.battleLedger().has(X)).isFalse();
        assertThat(f.gold(fresh)).isEqualTo(100);
    }

    /**
     * 登录补应用「不要求冻结匹配」的另一面：沿用过来的冻结是<b>别的局</b>（玩家已在下一局里）时，更早一局的待结算记录照常应用，
     * 但那个冻结不摘、它的锁不碰——只有冻结就是这一局时才解冻。
     */
    @Test
    void 沿用的冻结是别的局_待结算记录照常应用_不摘那个冻结_不碰它的锁() {
        long next = 20;
        f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, next);
        f.store(settlement(PLAYER, X, 100, 200));
        f.locks.clearCalls();
        f.follows.clear();

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        BattleFreeze carried = fresh.battle().freeze();
        assertThat(carried.battleId()).isEqualTo(next);
        f.drain();

        assertThat(f.gold(fresh)).isEqualTo(100);
        assertThat(fresh.attributes().health()).isEqualTo(200);
        assertThat(endedBattles(fresh)).containsExactly(X);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(fresh.battle().freeze()).as("冻结不是这一局：不摘").isSameAs(carried);
        assertThat(f.follows.freezeCleared).isEmpty();
        assertThat(f.locks.ops()).as("没有复核、没有删锁；没落盘不销账").containsExactly(Op.ENTER_READ, Op.HOLD);
        assertThat(f.locks.last(Op.HOLD).battleId()).isEqualTo(X);
        assertThat(f.locks.last(Op.HOLD).lastReply()).as("锁是下一局的：续锁落空").isEqualTo(0L);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(next);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(FIGHTING_TTL);
        assertThat(f.messageIds(fresh)).as("79 / 21 → 144（沿用的下一局）→ 150（上一局的结算）")
                .containsExactly(ENTER_SCENE, ACTOR_CREATE, f.reconnectHintId, f.battleEndId);
        assertThat(f.reconnectHints(fresh)).containsExactly(next);
        assertThat(fresh.battle().recovery()).isEqualTo(Recovery.READY);

        // 落盘后销的是上一局：只删它的记录，下一局的锁仍不动
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(X);
        assertThat(f.locks.last(Op.ACK).lastReply()).isEqualTo(1L);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(next);
        assertThat(fresh.battle().freeze()).isSameAs(carried);
    }

    // ------------------------------------------------------------------ 与 5.2 跨节点换图的互斥

    /**
     * 恢复读回来时玩家已经进入交出冻结（进场后马上发的 63 已选中远端）：<b>不挂战斗冻结</b>——两种冻结互斥；交出提交后由目标节点的进场恢复重建。
     * 交出没提交、原地解冻后重跑恢复，这时才重建并推 144。
     */
    @Test
    void 恢复读回来时已在交出冻结_不挂冻结_原地解冻后重跑恢复才重建() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = load();
        PendingHandOff handOff = f.freezeForHandOff(player);
        assertThat(player.frozen()).isTrue();

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();

        assertThat(player.inBattle()).as("交出冻结中不挂战斗冻结").isFalse();
        assertThat(f.locks.count(Op.TOUCH)).isZero();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.rebuilds("login", "miss")).isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁不动").isEqualTo(X);

        f.locks.release(Op.ENTER_READ);
        handOff.complete(new HandOffOutcome.LeaseTooShort());
        f.drain();

        assertThat(player.frozen()).isFalse();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.rebuilds("login", "rebuilt")).isEqualTo(1);
    }

    /**
     * 同一情形下快照里还带着待结算记录：交出冻结中整笔不可应用（冻结中玩家的可变状态必须与冻结快照一致）→ 这条记录延后、恢复置 RETRY，
     * 不续锁、不销账，记录留着——交出提交了就由目标节点的进场恢复应用；没提交、原地解冻后重跑恢复，在这里补应用恰好一次。
     */
    @Test
    void 恢复读回来时已在交出冻结_待结算记录整笔延后_冻结快照不被改_原地解冻后补应用一次() {
        f.store(settlement(PLAYER, X, 100, 200));
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = load();
        PendingHandOff handOff = f.freezeForHandOff(player);
        PlayerState frozen = BattleFixture.persistentState(player);

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();

        assertThat(f.gold(player)).isZero();
        assertThat(BattleFixture.persistentState(player)).as("冻结快照之后内存一个字段都没变").isEqualTo(frozen);
        assertThat(f.settlementsCounted("login", "deferred_frozen")).isEqualTo(1);
        assertThat(player.battle().recovery()).as("有延后的记录").isEqualTo(Recovery.RETRY);
        assertThat(f.recoveries("retry")).isEqualTo(1);
        assertThat(f.locks.ops()).as("不续锁、不销账").containsExactly(Op.ENTER_READ);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(f.repo.pendingProgress()).isZero();

        f.locks.release(Op.ENTER_READ);
        handOff.complete(new HandOffOutcome.LeaseTooShort());
        f.drain();

        assertThat(player.frozen()).isFalse();
        assertThat(f.gold(player)).isEqualTo(100);
        assertThat(player.attributes().health()).isEqualTo(200);
        assertThat(endedBattles(player)).containsExactly(X);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    /**
     * 恢复读回来时玩家在选目标中（RESOLVING，不是冻结）：照常挂上战斗冻结。随后选目标回来、选中的是远端节点 → 起交出前的复查发现已在战斗中，
     * 中止换图、推 23 {3023}，不进交出冻结。
     */
    @Test
    void 恢复读回来时在选目标中_照常挂冻结_随后选中远端时中止换图并推3023() {
        putFightingLock(PLAYER, X);
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = load();
        FakeSwitchTargets.PendingSelect select = f.resolveRemote(player);

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();

        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).as("RESOLVING 不挡重建").isNotNull();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(player.switchPhase()).isEqualTo(SwitchPhase.RESOLVING);

        select.chosen(BattleFixture.TARGET_NODE, BattleFixture.REMOTE_SCENE, 2);

        assertThat(player.switchPhase()).as("换图中止").isEqualTo(SwitchPhase.NONE);
        assertThat(player.frozen()).isFalse();
        assertThat(f.repo.pendingHandOffs()).as("没有提交交出").isZero();
        assertThat(f.pushedTips(player)).containsExactly(ENTER_FAILED);
        assertThat(player.battle().freeze()).as("战斗冻结不受影响").isSameAs(freeze);
        assertThat(player.scene()).isSameAs(f.scene1);
    }

    // ------------------------------------------------------------------ 交出进场

    /**
     * 所有进场类型都跑恢复，交出进场（5.2，{@code PlayerEnter.transfer = true}）也不例外：目标节点据锁重建 FIGHTING 并推 144
     * （源节点冻结期间到达的确认只续了锁），随交出快照带过来的账本条目在这里销账。
     */
    @Test
    void 交出进场也跑进场恢复_按锁重建FIGHTING并推144_带过来的账本条目销账() {
        putFightingLock(PLAYER, X);
        f.repo.put(new PlayerData(PLAYER, 5, 3, 1, "look-" + PLAYER, 1, f.scene1.configId(), new Vec3(5, 5, 0), ledgerOf(9),
                "玩家" + PLAYER));

        f.world.onPlayerEnter(BattleFixture.LINK, PlayerEnter.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setSceneId(f.scene1.sceneId()).setOwnerEpoch(5).setTransfer(true).build());
        f.repo.completeAll();
        ScenePlayer player = f.world.playerById(PLAYER);
        assertThat(player).isNotNull();
        assertThat(f.count("xm.scene.transfer.enters", "result", "ok")).as("确实是交出进场").isEqualTo(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);
        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).isNotNull();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.battleId()).isEqualTo(X);
        assertThat(f.messageIds(player)).containsExactly(ENTER_SCENE, ACTOR_CREATE, f.reconnectHintId);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.TOUCH, Op.ACK);
        assertThat(f.locks.last(Op.ACK).battleId()).isEqualTo(9);
        assertThat(player.battleLedger().has(9)).as("目标节点再销一次账（返回 0）后摘除").isFalse();
        assertThat(f.acks("login", "not_ours")).isEqualTo(1);
    }
}
