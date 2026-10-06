package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleSettlementData;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.BattleSettlementService.Result;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.player.Wallet;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.FakeBattleLocks;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.WorldTestAccess;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * reaper（scene-battle-spec §7.9；基线 {@code pb.cpp:2090-2195}；§13.2 {@code BattleReaperTest} 一行逐条）：
 * <ul>
 *   <li>PREPARING 过了备战期限（0 时退回战斗期限）只解冻、锁保留到 TTL；</li>
 *   <li>FIGHTING 在期限后 10 s 内不动；过了宽限先读本局记录（rescue）：有 → 应用 + 150 + 销账；没有 / 应用被延后 → 解冻并条件删锁（判废）；
 *       <b>读出错不判废</b>、下一轮再读，过了期限 + 60 s 才不读直接判废；{@code recovery ≠ READY} / 交出冻结时得 DEFERRED → 判废但记录保留；
 *       rescue 在途不重复；</li>
 *   <li>账本排空（已落盘 → 销账，没落盘 → 压存盘）；遍历时不改集合；一名玩家出错不影响其余；冻结人数指标。</li>
 * </ul>
 * 时间线（{@link BattleFixture#fighting}）：战斗期限 = 进战斗时刻 + 300 s，锁 TTL = 360 s（期限 + 60 s）；宽限 10 s。
 * 相邻用例：恢复待重试的玩家本轮只重启恢复、不排空账本（{@code BattleStaleReadTest}）；写锁在途时备战到期（{@code PrepareBattleTest}）。
 */
class BattleReaperTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long GOLD = 100;
    private static final int NODE = BattleFixture.BATTLE_NODE;
    private static final long BATTLE = BattleFixture.BATTLE_MILLIS;
    private static final long PREPARE = BattleFixture.PREPARE_MILLIS;
    private static final long GRACE = 10_000;
    private static final long LOCK_EXTRA = 60_000;

    private final BattleFixture f = new BattleFixture();

    private static BattleSettlementData settlement(long playerId, long battleId) {
        return BattleFixture.settlement(playerId, battleId, GOLD).build();
    }

    private static PlayerState ledgerOf(long... battleIds) {
        BattleLedgerState.Builder ledger = BattleLedgerState.newBuilder();
        for (long id : battleIds) {
            ledger.addApplied(BattleLedgerEntry.newBuilder().setBattleId(id).setAppliedAtMs(1));
        }
        return PlayerState.newBuilder().setBattleLedger(ledger).build();
    }

    private static double frozen(BattleFixture fixture, String state) {
        return fixture.meters.find("xm.scene.battle.frozen").tag("state", state).gauge().value();
    }

    private double expired(String phase) {
        return f.count("xm.scene.battle.freeze.expired", "phase", phase);
    }

    private double rescuesTotal() {
        double total = 0;
        for (String result : List.of("applied", "already_applied", "deferred", "miss", "error", "gave_up")) {
            total += f.rescues(result);
        }
        return total;
    }

    /** 进战斗（FIGHTING，期限 = 现在 + 300 s），清掉记录。 */
    private ScenePlayer fightingPlayer() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.locks.clearCalls();
        f.sink.clear();
        f.follows.clear();
        return player;
    }

    // ------------------------------------------------------------------ PREPARING

    /**
     * 备战过了期限（确认没来 = 建房失败 / match 挂了）：只摘冻结，<b>锁留到 TTL</b>——给迟到的确认留重建余地；零 Redis 调用。
     * 锁还在，所以当场补一次组队跟随（跟随链读到锁会放弃）。期限那一刻还不算过期。
     */
    @Test
    void 备战过期_只解冻_锁保留到TTL_零Redis调用_迟到的确认还能重建() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.follows.clear();

        f.advance(PREPARE);
        f.reap();

        assertThat(player.inBattle()).as("期限那一刻还不算过期").isTrue();
        assertThat(frozen(f, "preparing")).isEqualTo(1);
        assertThat(f.locks.calls()).isEmpty();

        f.advance(1);
        f.reap();

        assertThat(player.inBattle()).isFalse();
        assertThat(expired("preparing")).isEqualTo(1);
        assertThat(expired("fighting")).isZero();
        assertThat(f.locks.calls()).as("不读、不删").isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁留着").isEqualTo(X);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("P");
        assertThat(f.locks.lockTtlSec(PLAYER)).as("备战 TTL 120 s 已走了 60 s").isEqualTo(60);
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertThat(f.messageIds(player)).isEmpty();
        assertThat(frozen(f, "preparing")).isZero();

        // 锁还在：迟到的确认据它重建 FIGHTING 并推 144
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);

        f.reap();
        assertThat(player.inBattle()).as("重建出来的 FIGHTING 按战斗期限算，没到期").isTrue();
    }

    /** 备战请求没带备战期限（0）：冻结按战斗期限算，过了缺省的备战时长不解冻，过了战斗期限才解冻。 */
    @Test
    void 备战期限为0_退回战斗期限() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        long deadline = f.deadline();
        f.battle.prepare(f.prepareRequest(PLAYER, X, deadline, 0));
        f.drain();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.PREPARING);

        f.advance(PREPARE + 1);
        f.reap();
        assertThat(player.inBattle()).as("没有单独的备战期限：60 s 时不过期").isTrue();

        f.advance(BATTLE - PREPARE - 1);
        f.reap();
        assertThat(player.inBattle()).as("战斗期限那一刻还不算").isTrue();

        f.advance(1);
        f.reap();
        assertThat(player.inBattle()).isFalse();
        assertThat(expired("preparing")).isEqualTo(1);
    }

    /** 冻结对象上的备战期限就是 0（旧数据 / 手工摆出来的）：同样退回战斗期限，不会被当成「早已过期」当场摘掉。 */
    @Test
    void 冻结上的备战期限为0_按战斗期限判_不会当场过期() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleFreeze freeze = new BattleFreeze(X, NODE, Phase.PREPARING, f.deadline(), 0, true);
        BattleFixture.setFreeze(player, freeze);
        assertThat(freeze.effectiveDeadlineMs()).isEqualTo(freeze.deadlineMs());

        f.reap();
        f.advance(BATTLE);
        f.reap();
        assertThat(player.battle().freeze()).isSameAs(freeze);

        f.advance(1);
        f.reap();
        assertThat(player.inBattle()).isFalse();
    }

    // ------------------------------------------------------------------ FIGHTING：宽限

    @Test
    void 战斗期限后10秒宽限内不动_过了宽限才读本局记录() {
        ScenePlayer player = fightingPlayer();
        BattleFreeze freeze = player.battle().freeze();

        f.advance(BATTLE - 1);
        f.reap();
        f.advance(1 + GRACE);
        f.reap();

        assertThat(player.battle().freeze()).as("期限 + 10 s 那一刻还在宽限内").isSameAs(freeze);
        assertThat(f.locks.calls()).as("宽限内零 Redis 调用").isEmpty();
        assertThat(freeze.rescuing()).isFalse();
        assertThat(frozen(f, "fighting")).isEqualTo(1);

        f.locks.hold(Op.READ_SETTLEMENT);
        f.advance(1);
        f.reap();

        assertThat(f.locks.ops()).as("过了宽限：判废前先读本局记录").containsExactly(Op.READ_SETTLEMENT);
        Call read = f.locks.take(Op.READ_SETTLEMENT);
        assertThat(read.playerId()).isEqualTo(PLAYER);
        assertThat(read.battleId()).isEqualTo(X);
        assertThat(freeze.rescuing()).isTrue();
        assertThat(player.battle().freeze()).as("读回来之前不动").isSameAs(freeze);
    }

    // ------------------------------------------------------------------ FIGHTING：rescue 的各结局

    /**
     * 过了宽限、Redis 里有本局记录（battle 落了库、投递没到）：按 rescue 路径应用——收尾同结算到达：HOLD 续锁、摘冻结（锁保留）、推 150、
     * 落盘后销账。奖励没有因为投递丢了而丢。
     */
    @Test
    void 过了宽限_有本局记录_应用_推150_续锁_落盘后销账() {
        ScenePlayer player = fightingPlayer();
        BattleSettlementData settlement = settlement(PLAYER, X);
        f.store(settlement);
        f.advance(BATTLE + GRACE + 1);

        f.reap();

        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).as("读记录 → 续锁；不删锁，没落盘不销账").containsExactly(Op.READ_SETTLEMENT, Op.HOLD);
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);
        assertThat(f.locks.lockTtlSec(PLAYER)).as("锁本来只剩 50 s，续到 180 s").isEqualTo(180);
        assertThat(f.battleEnds(player)).singleElement().satisfies(end -> {
            assertThat(end.getBattleId()).isEqualTo(X);
            assertThat(end.getSettlement()).isEqualTo(settlement);
        });
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertThat(f.rescues("applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("rescue", "applied")).isEqualTo(1);
        assertThat(expired("fighting")).as("应用成功不算判废").isZero();
        assertThat(player.battleLedger().has(X)).isTrue();

        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);

        assertThat(f.locks.ops()).containsExactly(Op.READ_SETTLEMENT, Op.HOLD, Op.ACK);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.battleLedger().has(X)).isFalse();

        f.locks.clearCalls();
        f.reap();
        assertThat(f.locks.calls()).as("之后的轮次没有可做的事").isEmpty();
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    /**
     * 过了宽限、没有本局记录：判废——解冻并条件删锁（不看阶段，F 锁也删）。组队跟随要等<b>删锁完成</b>后才补（跟随链读到锁会放弃）。
     */
    @Test
    void 过了宽限_没有本局记录_解冻并条件删锁_删完才补跟随() {
        ScenePlayer player = fightingPlayer();
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.DELETE_IF_MATCH);

        f.reap();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.rescues("miss")).isEqualTo(1);
        assertThat(expired("fighting")).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.READ_SETTLEMENT, Op.DELETE_IF_MATCH);
        assertThat(f.follows.freezeCleared).as("删锁还没完成").isEmpty();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(X);

        Call delete = f.locks.take(Op.DELETE_IF_MATCH).complete();
        f.drain();

        assertThat(delete.battleId()).isEqualTo(X);
        assertThat(delete.lastReply()).as("已标 F 的锁照删").isEqualTo(1L);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertThat(f.messageIds(player)).as("判废不推 150").isEmpty();
        assertThat(f.locks.count(Op.ACK)).isZero();
        assertThat(f.gold(player)).isZero();
    }

    /**
     * 评审 R2-REV-1：判废的那条条件删锁在途时玩家同 epoch 重进——旧实例的冻结已摘、没有可沿用的；新实例的恢复读排在删锁之后才执行，读不到锁、不重建。
     * 但新实例进场那次组队跟随检查先于恢复读发出，读到的还是这把没删掉的锁、已经放弃。锁确实是这一次删掉的（返回 1）→「删完补一次」落到<b>现任实例</b>上，
     * 不跟着旧实例一起丢（否则删完之后再没有触发点）。
     */
    @Test
    void 判废删锁在途时同epoch重进_锁是这次删掉的_给新实例补一次跟随() {
        ScenePlayer old = fightingPlayer();
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.DELETE_IF_MATCH, Op.ENTER_READ);
        f.reap();
        assertThat(old.inBattle()).as("判废：冻结已摘，删锁在途").isFalse();
        assertThat(f.locks.pending(Op.DELETE_IF_MATCH)).hasSize(1);

        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        assertThat(fresh).isNotSameAs(old);
        assertThat(fresh.battle().freeze()).as("没有可沿用的冻结").isNull();
        assertThat(f.follows.entered).as("新实例进场那次跟随检查已经发出（此刻锁还在）").containsExactly(PLAYER);
        f.follows.clear();

        Call delete = f.locks.take(Op.DELETE_IF_MATCH).complete();
        f.drain();

        assertThat(delete.lastReply()).isEqualTo(1L);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeClearedPlayers).as("补给现任实例，恰好一次；不拿已被移除的旧实例去补").containsExactly(fresh);

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(fresh.inBattle()).as("恢复读看到的已是「没有锁」").isFalse();
        assertThat(f.follows.freezeClearedPlayers).containsExactly(fresh);
    }

    /** 上一条的守卫：判废删锁回来时实例已换，但这次删除没有删到锁（锁在它执行之前已经过期，返回 0）——不给新实例补。 */
    @Test
    void 判废删锁在途时同epoch重进_这次没有删到锁_不给新实例补跟随() {
        ScenePlayer old = fightingPlayer();
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.DELETE_IF_MATCH, Op.ENTER_READ);
        f.reap();
        ScenePlayer fresh = f.reenter(SESSION + 1, PLAYER, 1, f.scene1);
        assertThat(fresh).isNotSameAs(old);
        f.locks.removeLock(PLAYER);
        f.follows.clear();

        Call missed = f.locks.take(Op.DELETE_IF_MATCH).complete();
        f.drain();

        assertThat(missed.lastReply()).isEqualTo(0L);
        assertThat(f.follows.freezeCleared).as("没有锁是被这一次删掉的：不补").isEmpty();
    }

    /** 另一条守卫：判废删锁在途时玩家离场，删到了锁——没有现任实例可补，也不拿已被移除的旧实例去补。 */
    @Test
    void 判废删锁在途时玩家离场_删到了锁_没有现任实例不补跟随() {
        fightingPlayer();
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.DELETE_IF_MATCH);
        f.reap();
        f.world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(SESSION).setPlayerId(PLAYER)
                .setVoluntary(true).build());
        assertThat(f.world.playerById(PLAYER)).isNull();
        f.follows.clear();

        Call deleted = f.locks.take(Op.DELETE_IF_MATCH).complete();
        f.drain();

        assertThat(deleted.lastReply()).isEqualTo(1L);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).as("玩家已不在本节点：没有人可补").isEmpty();
    }

    /** 读到的记录是坏的（解析不了、blob 里的 battle_id / player_id 对不上）：等同没有记录，判废；坏记录不在这里删（进场恢复按坏字段删）。 */
    @Test
    void 过了宽限_本局记录损坏或归属不符_按没有记录判废_记录留着() {
        ScenePlayer garbage = f.enter(SESSION, PLAYER);
        ScenePlayer wrongOwner = f.enter(SESSION + 1, 1002);
        ScenePlayer wrongBattle = f.enter(SESSION + 2, 1003);
        for (ScenePlayer player : List.of(garbage, wrongOwner, wrongBattle)) {
            f.fighting(player.playerId(), X);
        }
        f.locks.storeSettlement(PLAYER, X, "不是 protobuf".getBytes(StandardCharsets.UTF_8));
        f.locks.storeSettlement(1002, X, FakeBattleLocks.record(settlement(2002, X)));
        f.locks.storeSettlement(1003, X, FakeBattleLocks.record(settlement(1003, 99)));
        f.advance(BATTLE + GRACE + 1);

        f.reap();

        for (ScenePlayer player : List.of(garbage, wrongOwner, wrongBattle)) {
            assertThat(player.inBattle()).as("player=%s", player.playerId()).isFalse();
            assertThat(f.gold(player)).isZero();
            assertThat(f.battleEnds(player)).isEmpty();
            assertThat(f.locks.lock(player.playerId())).as("判废删锁").isNull();
            assertThat(f.locks.hasSettlement(player.playerId(), X)).as("坏记录留着").isTrue();
        }
        assertThat(f.rescues("miss")).isEqualTo(3);
        assertThat(expired("fighting")).isEqualTo(3);
        assertThat(f.locks.count(Op.ACK) + f.locks.count(Op.DELETE_FIELD)).isZero();
    }

    /**
     * 评审修订第 8 条：rescue <b>读出错不判废</b>（判废删锁后，随后到达的重投会落进「无冻结、锁不在」被丢弃并销账——一次读错就丢奖）。
     * 本轮只计 {@code rescues{error}}、清掉在途标记，下一轮再读；直到过了期限 + 60 s（锁也已过期）仍读不到，才不读直接判废。
     */
    @Test
    void 读本局记录出错_不判废_下一轮再读_过了期限加60秒才不读直接判废() {
        ScenePlayer player = fightingPlayer();
        BattleFreeze freeze = player.battle().freeze();
        f.locks.failAlways(Op.READ_SETTLEMENT, new RuntimeException("Redis 超时"));
        f.advance(BATTLE + GRACE + 1);

        f.reap();

        assertThat(player.battle().freeze()).as("读错不判废").isSameAs(freeze);
        assertThat(freeze.rescuing()).as("在途标记已清，下一轮还会读").isFalse();
        assertThat(f.rescues("error")).isEqualTo(1);
        assertThat(expired("fighting")).isZero();
        assertThat(f.locks.ops()).containsExactly(Op.READ_SETTLEMENT);
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁没删").isEqualTo(X);

        f.advance(30_000);
        f.reap();

        assertThat(f.locks.count(Op.READ_SETTLEMENT)).as("下一轮再读").isEqualTo(2);
        assertThat(f.rescues("error")).isEqualTo(2);
        assertThat(player.battle().freeze()).isSameAs(freeze);

        // 期限 + 60 s 那一刻还读
        f.advance(LOCK_EXTRA - GRACE - 1 - 30_000);
        f.reap();
        assertThat(f.locks.count(Op.READ_SETTLEMENT)).isEqualTo(3);
        assertThat(player.battle().freeze()).isSameAs(freeze);

        f.advance(1);
        f.reap();

        assertThat(f.locks.count(Op.READ_SETTLEMENT)).as("过了期限 + 60 s：不再读").isEqualTo(3);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.rescues("gave_up")).isEqualTo(1);
        assertThat(f.rescues("miss")).isZero();
        assertThat(expired("fighting")).isEqualTo(1);
        assertThat(f.locks.calls(Op.DELETE_IF_MATCH)).singleElement().satisfies(delete -> {
            assertThat(delete.battleId()).isEqualTo(X);
            assertThat(delete.lastReply()).as("锁此时已按 TTL 过期，条件删落空").isEqualTo(0L);
        });
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    /** 读错之后的下一轮读到了记录：照常应用——这正是「读错不判废」要保住的那份奖励。 */
    @Test
    void 读本局记录出错_下一轮读到了_照常应用_奖励没丢() {
        ScenePlayer player = fightingPlayer();
        f.store(settlement(PLAYER, X));
        f.locks.failNext(Op.READ_SETTLEMENT, new RuntimeException("Redis 超时"));
        f.advance(BATTLE + GRACE + 1);
        f.reap();
        assertThat(player.inBattle()).isTrue();
        assertThat(f.gold(player)).isZero();

        f.advance(30_000);
        f.reap();

        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.inBattle()).isFalse();
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(f.rescues("error")).isEqualTo(1);
        assertThat(f.rescues("applied")).isEqualTo(1);
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁没被删过，续到了 180 s").isEqualTo(X);
    }

    /**
     * rescue 读到了记录，但此刻不可应用（进场恢复还没就绪）→ 得 DEFERRED：冻结照样判废（过了期限，不能一直冻着），<b>记录留在 Redis、不销账</b>，
     * 由进场恢复按局序补应用。这里的 FIGHTING 冻结是恢复读失败期间由迟到确认重建出来的。
     */
    @Test
    void rescue读到记录但恢复未就绪_判废_记录保留不销账_恢复就绪后补应用() {
        f.locks.failAlways(Op.ENTER_READ, new RuntimeException("Redis 不可用"));
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.confirm(PLAYER, X, f.deadline());
        f.drain();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        f.store(settlement(PLAYER, X));
        f.locks.clearCalls();
        f.sink.clear();
        f.advance(BATTLE + GRACE + 1);

        f.reap();

        assertThat(player.inBattle()).as("判废").isFalse();
        assertThat(f.gold(player)).as("没有应用").isZero();
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("记录保留").isTrue();
        assertThat(f.locks.count(Op.ACK)).as("不销账").isZero();
        assertThat(f.locks.count(Op.HOLD)).isZero();
        assertThat(f.locks.calls(Op.DELETE_IF_MATCH)).hasSize(1);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.rescues("deferred")).isEqualTo(1);
        assertThat(f.settlementsCounted("rescue", "deferred_recovering")).isEqualTo(1);
        assertThat(expired("fighting")).isEqualTo(1);
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(player.battle().recovery()).as("同一轮里重启的恢复读又失败了").isEqualTo(Recovery.RETRY);

        f.locks.heal();
        f.reap();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.gold(player)).as("进场恢复补应用").isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
    }

    /** 同一组前置的另一半（纵深防御：两种冻结按设计互斥）：rescue 回调时玩家在交出冻结 → 同样 DEFERRED，记录保留、不改冻结快照里的资产。 */
    @Test
    void rescue读到记录但玩家在交出冻结_判废_记录保留_资产不动() {
        ScenePlayer player = fightingPlayer();
        f.store(settlement(PLAYER, X));
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.READ_SETTLEMENT);
        f.reap();
        WorldTestAccess.startFreezing(player);

        f.locks.take(Op.READ_SETTLEMENT).complete();
        f.drain();

        assertThat(f.gold(player)).isZero();
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(f.locks.count(Op.ACK)).isZero();
        assertThat(f.rescues("deferred")).isEqualTo(1);
        assertThat(f.settlementsCounted("rescue", "deferred_frozen")).isEqualTo(1);
    }

    /**
     * rescue 读到了记录，但应用被延后（金币被 GM 封禁）：判废、记录保留。之后 battle 的重投落进「无冻结、锁不在」——丢弃并销账，这份奖励作废。
     * 这是照搬基线的已知取舍（规格 §4.6 S-6 / Q-a：金币被封的局常被 reaper 按期限判废，之后的重投以「锁不在」丢弃），钉住它不是「修好了」。
     */
    @Test
    void rescue应用被延后_金币被拒_判废记录保留_随后的重投按锁不在丢弃并销账() {
        ScenePlayer player = fightingPlayer();
        BattleSettlementData settlement = settlement(PLAYER, X);
        f.store(settlement);
        assertThat(f.currency.block(player, Wallet.GOLD)).isZero();
        f.advance(BATTLE + GRACE + 1);

        f.reap();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.gold(player)).isZero();
        assertThat(f.rescues("deferred")).isEqualTo(1);
        assertThat(f.settlementsCounted("rescue", "deferred_currency")).isEqualTo(1);
        assertThat(expired("fighting")).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.READ_SETTLEMENT, Op.DELETE_IF_MATCH);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("记录留在 Redis").isTrue();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(player.battleLedger().has(X)).isFalse();

        CompletableFuture<SceneBattleReply> redelivered = f.deliver(settlement);
        f.drain();

        assertThat(BattleFixture.done(redelivered).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("S-6：被判废的局，重投把记录销掉").isFalse();
        assertThat(f.gold(player)).isZero();
    }

    /** 冻结与账本条目同在（正常走不到，这里手工摆）：rescue 读到记录时命中账本 → 按已应用收尾，不再发奖、不推 150。 */
    @Test
    void rescue读到的局账本里已有_按已应用收尾_不再发奖不推150() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleSettlementData settlement = settlement(PLAYER, X);
        assertThat(f.settlements.apply(f.world, player, settlement)).isEqualTo(Result.APPLIED);
        f.store(settlement);
        long deadline = f.deadline();
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_FIGHTING, deadline, 0, 360);
        BattleFreeze freeze = new BattleFreeze(X, NODE, Phase.FIGHTING, deadline, 0, true);
        BattleFixture.setFreeze(player, freeze);
        f.advance(BATTLE + GRACE + 1);

        f.reap();

        assertThat(f.rescues("already_applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("rescue", "already_applied")).isEqualTo(1);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.count(Op.DELETE_IF_MATCH)).as("不是判废：锁留到落盘").isZero();
        assertThat(expired("fighting")).isZero();
    }

    // ------------------------------------------------------------------ FIGHTING：rescue 在途

    @Test
    void rescue在途_之后的轮次不重复发读_读回来后才处理() {
        ScenePlayer player = fightingPlayer();
        BattleFreeze freeze = player.battle().freeze();
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.READ_SETTLEMENT);

        f.reap();
        f.reap();
        f.advance(30_000);
        f.reap();

        assertThat(f.locks.count(Op.READ_SETTLEMENT)).as("同一个冻结同时只有一次 rescue 读").isEqualTo(1);
        assertThat(freeze.rescuing()).isTrue();
        assertThat(player.battle().freeze()).isSameAs(freeze);
        assertThat(rescuesTotal()).isZero();
        assertThat(frozen(f, "fighting")).as("读在途的仍算冻结中").isEqualTo(1);

        f.locks.take(Op.READ_SETTLEMENT).complete();
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.rescues("miss")).isEqualTo(1);
    }

    /**
     * rescue 读一直不回来：在途标记挡得住重复发读，挡不住上限——过了期限 + 60 s 照样直接判废。迟到的读结果（哪怕带着记录）回来时冻结已不是那个对象，
     * 丢弃、不应用（记录还在，留给进场恢复）。
     */
    @Test
    void rescue读一直不回来_过了期限加60秒照样判废_迟到的读结果被丢弃() {
        ScenePlayer player = fightingPlayer();
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.READ_SETTLEMENT);
        f.reap();
        Call read = f.locks.take(Op.READ_SETTLEMENT);

        f.advance(LOCK_EXTRA - GRACE);
        f.reap();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.rescues("gave_up")).isEqualTo(1);
        assertThat(expired("fighting")).isEqualTo(1);
        assertThat(f.locks.count(Op.READ_SETTLEMENT)).isEqualTo(1);

        f.store(settlement(PLAYER, X));
        read.complete();
        f.drain();

        assertThat(read.lastReply()).as("这次读确实带回了记录").isNotNull();
        assertThat(f.gold(player)).as("冻结已不是那个对象：丢弃").isZero();
        assertThat(f.rescues("applied")).isZero();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(rescuesTotal()).isEqualTo(1);
    }

    /** rescue 读在途时这一局的结算正常送到了：应用并摘掉冻结。读回来时冻结已不在 → 丢弃，不重复应用、更不会把锁删掉。 */
    @Test
    void rescue读在途时结算正常到达_读回来后丢弃_不重复应用不删锁() {
        ScenePlayer player = fightingPlayer();
        BattleSettlementData settlement = settlement(PLAYER, X);
        f.store(settlement);
        f.advance(BATTLE + GRACE + 1);
        f.locks.hold(Op.READ_SETTLEMENT);
        f.reap();

        assertThat(BattleFixture.done(f.deliver(settlement)).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        f.locks.take(Op.READ_SETTLEMENT).complete();
        f.drain();

        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(rescuesTotal()).isZero();
        assertThat(f.locks.count(Op.DELETE_IF_MATCH)).isZero();
        assertThat(f.locks.lockBattleId(PLAYER)).as("锁留到落盘").isEqualTo(X);
        assertThat(f.settlementsCounted("online", "applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("rescue", "applied") + f.settlementsCounted("rescue", "already_applied")).isZero();
    }

    // ------------------------------------------------------------------ 账本排空

    /** 账本里已落盘的条目（进场那次销账失败留下的）：每轮逐条销账，按 battle_id 无符号升序；成功后摘掉。 */
    @Test
    void 账本排空_已落盘的条目逐条销账_成功后摘掉() {
        long huge = 0x8000_0000_0000_0001L;
        f.locks.failAlways(Op.ACK, new RuntimeException("销账脚本失败"));
        ScenePlayer player = f.enter(SESSION, PLAYER, ledgerOf(9, huge, 7));
        assertThat(player.battleLedger().size()).isEqualTo(3);

        f.reap();
        assertThat(f.acks("reaper", "error")).as("还在失败：条目保留，下一轮再试").isEqualTo(3);
        assertThat(player.battleLedger().size()).isEqualTo(3);

        f.locks.heal();
        f.locks.clearCalls();
        f.reap();

        assertThat(f.locks.ops()).containsExactly(Op.ACK, Op.ACK, Op.ACK);
        assertThat(f.locks.calls(Op.ACK)).extracting(Call::battleId).containsExactly(7L, 9L, huge);
        assertThat(player.battleLedger().size()).isZero();
        assertThat(f.acks("reaper", "not_ours")).isEqualTo(3);

        f.locks.clearCalls();
        f.reap();
        assertThat(f.locks.calls()).as("账本空了，不再销").isEmpty();
    }

    /**
     * 账本里没落盘的条目（那次在线存盘失败了）：reaper 不销账，只再压一笔存盘；落盘后由存盘回调销。存储积压时这一轮什么都不提交，下一轮再试。
     */
    @Test
    void 账本排空_没落盘的条目不销账_只压存盘_存储积压时下一轮再试() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(f.settlements.apply(f.world, player, settlement(PLAYER, X))).isEqualTo(Result.APPLIED);
        assertThat(f.completeSaves(ProgressResult.FAILED)).isEqualTo(1);
        assertThat(player.persistedState()).isNull();
        f.repo.setAcceptsProgress(false);

        f.reap();

        assertThat(f.locks.calls()).as("没落盘不销账").isEmpty();
        assertThat(f.repo.pendingProgress()).as("存储积压：没提交").isZero();
        assertThat(f.acks("reaper", "deferred")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isTrue();

        f.repo.setAcceptsProgress(true);
        f.reap();

        assertThat(f.repo.pendingProgress()).as("再压一笔").isEqualTo(1);
        assertThat(f.locks.calls()).isEmpty();
        assertThat(f.acks("reaper", "deferred")).isEqualTo(2);

        f.reap();
        assertThat(f.repo.pendingProgress()).as("已有一笔在途就不再压").isEqualTo(1);

        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(f.locks.ops()).containsExactly(Op.ACK);
        assertThat(f.acks("persisted", "not_ours")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isFalse();
    }

    /**
     * 账本损坏（D24）的玩家：reaper 不拿损坏的账本去销账、也不为它压存盘——原样保留等人工排查。它身上到期的 FIGHTING 冻结照常处理：
     * rescue 读到本局记录、应用因账本损坏被延后 → 判废（解冻、删锁），记录留在 Redis、不销账。
     */
    @Test
    void 账本损坏的玩家_不排空账本不压存盘_到期的冻结照常判废_记录保留() {
        BattleLedgerState corrupt = BattleLedgerState.newBuilder()
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(9).setAppliedAtMs(1))
                .addApplied(BattleLedgerEntry.newBuilder().setBattleId(9).setAppliedAtMs(2)).build();
        ScenePlayer player = f.enter(SESSION, PLAYER, PlayerState.newBuilder().setBattleLedger(corrupt).build());
        assertThat(player.battleLedger().invalidReason()).isNotNull();
        assertThat(player.battle().recovery()).as("没有待结算记录：恢复照常就绪").isEqualTo(Recovery.READY);

        f.reap();

        assertThat(f.locks.calls()).as("损坏的账本不拿去销账").isEmpty();
        assertThat(f.repo.pendingProgress()).isZero();

        // 迟到确认按锁重建出 FIGHTING 冻结（账本损坏不挡重建），之后这一局的结算落了库、投递没到
        long deadline = f.deadline();
        f.locks.putLock(PLAYER, X, NODE, BattleRedis.STATE_PREPARING, deadline, f.prepareDeadline(), 120);
        f.confirm(PLAYER, X, deadline);
        f.drain();
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        f.store(settlement(PLAYER, X));
        f.locks.clearCalls();
        f.sink.clear();
        f.advance(BATTLE + GRACE + 1);

        f.reap();

        assertThat(player.inBattle()).as("过了期限不能一直冻着").isFalse();
        assertThat(f.gold(player)).isZero();
        assertThat(f.rescues("deferred")).isEqualTo(1);
        assertThat(f.settlementsCounted("rescue", "deferred_ledger")).isEqualTo(1);
        assertThat(expired("fighting")).isEqualTo(1);
        assertThat(f.locks.ops()).as("读记录 → 判废删锁；不续锁、不销账").containsExactly(Op.READ_SETTLEMENT, Op.DELETE_IF_MATCH);
        assertThat(f.locks.hasSettlement(PLAYER, X)).as("记录留着，等账本修好后由进场恢复补应用").isTrue();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(BattleFixture.persistentState(player).getBattleLedger()).as("损坏的账本原样带回").isEqualTo(corrupt);
        assertThat(f.repo.pendingProgress()).isZero();
    }

    // ------------------------------------------------------------------ 遍历与隔离

    /** 钩子可编程的组队跟随：在「冻结解除」钩子里改玩家集合 / 抛异常。 */
    private static final class HookedFollow implements TeamFollow {
        private BiConsumer<SceneWorld, ScenePlayer> onCleared = (world, player) -> { };

        @Override
        public void onEnteredScene(SceneWorld world, ScenePlayer player) {
        }

        @Override
        public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
            onCleared.accept(world, player);
        }
    }

    /**
     * reaper 先收集在场玩家再处理：处理一名玩家时（解冻钩子里）别的玩家离场、又有新玩家进场，都不会让遍历出错
     * （直接遍历在场集合会抛 {@code ConcurrentModificationException}）；已经离场的实例被跳过，不再动它。
     */
    @Test
    void 遍历中玩家离场或进场_不抛异常_已离场的实例被跳过() {
        HookedFollow follow = new HookedFollow();
        BattleFixture g = new BattleFixture(wiring -> follow);
        List<Long> ids = List.of(1001L, 1002L, 1003L);
        List<ScenePlayer> players = ids.stream().map(id -> g.enter((int) (id - 990), id)).toList();
        ids.forEach(id -> g.prepared(id, X));
        boolean[] fired = new boolean[1];
        follow.onCleared = (world, cleared) -> {
            if (fired[0]) {
                return;
            }
            fired[0] = true;
            // 第一名玩家解冻时：其余还冻着的玩家全部离场，再进来一名新玩家
            for (long other : ids) {
                ScenePlayer present = world.playerById(other);
                if (other != cleared.playerId() && present != null) {
                    world.onPlayerLeave(BattleFixture.LINK, PlayerLeave.newBuilder().setSessionId(present.session().sessionId())
                            .setPlayerId(other).setVoluntary(true).build());
                }
            }
            g.load(50, 2002, 1, g.scene1, PlayerState.getDefaultInstance());
        };
        g.advance(PREPARE + 1);

        assertThatCode(g::reap).doesNotThrowAnyException();

        assertThat(fired[0]).isTrue();
        assertThat(g.count("xm.scene.battle.freeze.expired", "phase", "preparing")).as("只处理了还在场的那一名").isEqualTo(1);
        assertThat(players.stream().filter(p -> g.world.playerById(p.playerId()) == p).count()).isEqualTo(1);
        assertThat(players.stream().filter(ScenePlayer::inBattle).count()).as("离场的两个实例没有被再动过").isEqualTo(2);
        assertThat(g.world.playerById(2002)).isNotNull();
    }

    /** 处理一名玩家时出了意外异常（这里是解冻钩子抛出）：记 ERROR、继续处理其余玩家，reaper 本身不抛。 */
    @Test
    void 处理一名玩家出错_不影响其余玩家_reaper不抛() {
        HookedFollow follow = new HookedFollow();
        BattleFixture g = new BattleFixture(wiring -> follow);
        List<Long> ids = List.of(1001L, 1002L, 1003L);
        List<ScenePlayer> players = ids.stream().map(id -> g.enter((int) (id - 990), id)).toList();
        ids.forEach(id -> g.prepared(id, X));
        follow.onCleared = (world, cleared) -> {
            if (cleared.playerId() == 1002) {
                throw new IllegalStateException("跟随钩子炸了");
            }
        };
        g.advance(PREPARE + 1);

        assertThatCode(g::reap).doesNotThrowAnyException();

        assertThat(players).as("三名玩家都解冻了").noneMatch(ScenePlayer::inBattle);
        assertThat(g.count("xm.scene.battle.freeze.expired", "phase", "preparing")).as("出错的那名没走到计数").isEqualTo(2);
        assertThat(frozen(g, "preparing")).as("这一轮的人数指标照常推").isZero();
    }

    // ------------------------------------------------------------------ 指标与空转

    @Test
    void 每轮推冻结人数_备战与战斗分开数_没有冻结的玩家零调用() {
        f.enter(SESSION, 1001);
        f.enter(SESSION + 1, 1002);
        f.enter(SESSION + 2, 1003);
        f.enter(SESSION + 3, 1004);
        f.prepared(1001, X);
        f.prepared(1002, X);
        f.fighting(1003, X);
        f.locks.clearCalls();

        f.reap();

        assertThat(frozen(f, "preparing")).isEqualTo(2);
        assertThat(frozen(f, "fighting")).isEqualTo(1);
        assertThat(f.locks.calls()).as("没到期、账本空、恢复就绪：一轮下来零 Redis 调用").isEmpty();
        assertThat(expired("preparing") + expired("fighting")).isZero();

        f.advance(PREPARE + 1);
        f.reap();

        assertThat(frozen(f, "preparing")).as("到期被摘的不算").isZero();
        assertThat(frozen(f, "fighting")).isEqualTo(1);
        assertThat(expired("preparing")).isEqualTo(2);
    }
}
