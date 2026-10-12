package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.BattleLedgerEntry;
import com.game.player.store.state.BattleLedgerState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleSettlementData;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 过期读不得导致重复发奖（审计 FRZ-1，critical）与恢复代际（FRZ-9）。
 *
 * <p>背景：销账（ACK）与各种读（进场恢复读 ENTER_READ、结算按锁分支的读锁、迟到确认的 CONFIRM）是各自发出的脚本，Redis 上谁先执行、
 * 回调谁先回到逻辑线程都没有保证（§1.8）。坏交错 = 读先在 Redis 上执行（快照里还有这一局的记录 / 锁），销账后执行，而销账的回调先回来
 * （账本 forget）——此时只靠账本去重，过期快照里的这一局会被再应用一遍。两层防护分别钉住：
 * ① reaper 对恢复待重试的玩家本轮不排空账本；② 恢复只认最新一代的读，且本实例已发出过销账的局不管被哪条读带回来都不再应用、不再按锁重建。
 */
class BattleStaleReadTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long GOLD = 100;

    private final BattleFixture f = new BattleFixture();

    private static PlayerState ledgerOf(long... battleIds) {
        BattleLedgerState.Builder ledger = BattleLedgerState.newBuilder();
        for (long id : battleIds) {
            ledger.addApplied(BattleLedgerEntry.newBuilder().setBattleId(id).setAppliedAtMs(1));
        }
        return PlayerState.newBuilder().setBattleLedger(ledger).build();
    }

    /** 在线打完一局并应用：金币到账、150 推了一条、账本有 X 但还没落盘（在线存盘挂起）、记录与锁都还在 Redis。 */
    private ScenePlayer appliedOnlineNotYetSaved() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, GOLD).build();
        assertThat(f.store(settlement)).isEqualTo(1);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();
        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(f.repo.pendingProgress()).as("应用后压了一次在线存盘，还没落盘").isEqualTo(1);
        assertThat(f.locks.count(Op.ACK)).as("没落盘不销账").isZero();
        return player;
    }

    // ------------------------------------------------------------------ 进场恢复读（LOGIN 路径）

    /**
     * FRZ-1 的坏交错：恢复读先在 Redis 上执行（快照里记录 X 与锁 X 都在）→ 存盘落地、销账执行（删记录、放锁）→ 销账的回调先回来（账本 forget）
     * → 那份更早的快照才回来。修之前：账本已没有 X，快照里的 X 按 login 路径再应用一遍，金币翻倍、再推一条 150。
     */
    @Test
    void 销账的回调先回来_更早拍下的恢复读快照后回来_这一局不再应用_金币只加一次_150只推一次() {
        ScenePlayer player = appliedOnlineNotYetSaved();
        f.locks.hold(Op.ENTER_READ, Op.ACK);
        // 重跑进场恢复（原地解冻 / reaper 重试都走这里）
        f.battle.onUnfrozenInPlace(f.world, player);
        Call read = f.locks.take(Op.ENTER_READ).execute();
        assertThat(((BattleRedis.EnterRead) read.lastReply()).lockBattleId()).as("快照拍在销账之前：锁还指着 X").isEqualTo(X);
        assertThat(((BattleRedis.EnterRead) read.lastReply()).settlements()).hasSize(1);

        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.locks.take(Op.ACK).complete();
        f.drain();
        assertThat(player.battleLedger().has(X)).as("销账回来，账本已 forget").isFalse();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).isNull();

        read.reply();
        f.drain();

        assertThat(f.gold(player)).as("金币只加一次").isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).as("150 只推一次").hasSize(1);
        assertThat(player.battleLedger().has(X)).as("没有再应用，账本不会重新登记").isFalse();
        assertThat(player.inBattle()).as("快照里那把过期的锁不得把玩家冻回一场打完的战斗").isFalse();
        assertThat(f.locks.count(Op.TOUCH)).isZero();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.settlementsCounted("login", "already_applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("login", "applied")).isZero();
        assertThat(f.audit.currencies).as("只有一条金币流水").hasSize(1);
    }

    /**
     * 同一坏交错的「只有锁、没有记录」形态（battle 落库失败、只投了一次的局）：快照里锁指着 X、没有记录；销账放了锁、账本 forget 之后快照才回来。
     * 修之前会按这把已经放掉的锁重建一个冻结，要等 TOUCH 复核落空才撤销（期间各在途闸全关）。
     */
    @Test
    void 销账已放锁_更早的快照里锁还指着这一局_不按它重建冻结() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        CompletableFuture<SceneBattleReply> reply = f.deliver(BattleFixture.settlement(PLAYER, X, GOLD).build());
        f.drain();
        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        f.locks.hold(Op.ENTER_READ);
        f.battle.onUnfrozenInPlace(f.world, player);
        Call read = f.locks.take(Op.ENTER_READ).execute();
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(f.locks.last(Op.ACK).lastReply()).as("没有记录可删，只放了锁").isEqualTo(2L);
        assertThat(player.battleLedger().has(X)).isFalse();
        f.locks.clearCalls();

        read.reply();
        f.drain();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.count(Op.TOUCH)).as("没有重建，也就没有复核").isZero();
        assertThat(f.rebuilds("login", "ledger_hit")).isEqualTo(1);
        assertThat(f.rebuilds("login", "rebuilt")).isZero();
        assertThat(f.rebuilds("login", "reverted")).isZero();
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    // ------------------------------------------------------------------ 结算按锁分支（BY_LOCK 路径）

    /**
     * deliver() 无冻结按锁分支的同型问题：第二次投递的读锁先执行（b == X），销账后执行并先回来（账本 forget、锁已放），读锁的回复才回来。
     * 修之前：锁「是本局」、账本没有 X → 再应用一遍。
     */
    @Test
    void 结算重投的读锁早于销账执行_回复晚于销账回来_按锁分支不再应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        // 没有冻结、锁是本局（备战到期被 reaper 摘了冻结、锁留到 TTL 之后确认只续了锁的那类局）
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, GOLD).build();
        f.store(settlement);
        CompletableFuture<SceneBattleReply> first = f.deliver(settlement);
        f.drain();
        assertThat(BattleFixture.done(first).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(f.settlementsCounted("by_lock", "applied")).isEqualTo(1);
        assertThat(f.gold(player)).isEqualTo(GOLD);

        f.locks.hold(Op.READ_LOCK, Op.ACK);
        CompletableFuture<SceneBattleReply> second = f.deliver(settlement);
        Call lockRead = f.locks.take(Op.READ_LOCK).execute();
        assertThat(lockRead.lastReply()).as("读锁执行在销账之前：b 还是 X").isEqualTo(X);
        f.repo.takeProgress().complete(ProgressResult.SAVED);
        f.locks.take(Op.ACK).complete();
        f.drain();
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(second).isNotDone();

        lockRead.reply();
        f.drain();

        assertThat(BattleFixture.done(second).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(BattleFixture.done(second).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).as("金币只加一次").isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).as("150 只推一次").hasSize(1);
        assertThat(f.settlementsCounted("by_lock", "applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("by_lock", "already_applied")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 迟到确认

    /** 迟到确认的 CONFIRM 早于销账执行（回了锁的字段）、回复晚于销账回来：这一局已经打完并销账，不得据这份过期回复重建 FIGHTING 冻结。 */
    @Test
    void 迟到确认的回复早于销账_晚于forget回来_不重建冻结不推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        f.locks.hold(Op.CONFIRM);
        f.confirm(PLAYER, X, f.deadline());
        Call confirm = f.locks.take(Op.CONFIRM).execute();
        assertThat(confirm.lastReply()).as("CONFIRM 命中，回了锁的字段").isNotNull();
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, GOLD).build();
        f.store(settlement);
        f.deliver(settlement);
        f.drain();
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(f.locks.lock(PLAYER)).as("销账已放锁").isNull();
        f.sink.clear();

        confirm.reply();
        f.drain();

        assertThat(player.inBattle()).as("没有锁的 FIGHTING 冻结要到期限 + 60 s 才会被 reaper 判废").isFalse();
        assertThat(f.reconnectHints(player)).isEmpty();
        assertThat(f.confirms("ledger_hit")).isEqualTo(1);
        assertThat(f.confirms("rebuilt")).isZero();
        assertThat(f.rebuilds("late_confirm", "ledger_hit")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ reaper（第一层）

    /**
     * FRZ-1 第一层：恢复待重试（RETRY）的玩家，reaper 这一轮只重启恢复读，不排空账本——销账与恢复读背靠背发出正是坏交错的来源；
     * 恢复读回来后的第 4 步自己会销账。
     */
    @Test
    void reaper_恢复待重试的玩家_本轮只发恢复读不发销账_恢复读回来后才销账() {
        f.locks.failNext(Op.ENTER_READ, new RuntimeException("Redis 不可用"));
        ScenePlayer player = f.enter(SESSION, PLAYER, ledgerOf(X));
        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(BattleLedger.persistedHas(player.persistedState(), X)).as("加载自库的条目天然已落盘").isTrue();

        f.battle.reap();

        assertThat(f.locks.ops()).as("同一轮里不与销账背靠背").containsExactly(Op.ENTER_READ);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);

        f.drain();

        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.ACK);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(player.battleLedger().has(X)).as("销账回来后 forget").isFalse();
        assertThat(f.acks("login", "not_ours")).isEqualTo(1);
        assertThat(f.acks("reaper", "not_ours") + f.acks("reaper", "released")).as("reaper 这一轮没有销账").isZero();
    }

    @Test
    void reaper_恢复读在途的玩家_不排空账本() {
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, ledgerOf(X));
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);

        f.reap();

        assertThat(f.locks.ops()).as("只有进场那一次恢复读，reaper 不重发、也不销账").containsExactly(Op.ENTER_READ);

        f.locks.take(Op.ENTER_READ).complete();
        f.drain();
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.ACK);
        assertThat(player.battleLedger().has(X)).isFalse();
    }

    @Test
    void reaper_恢复已就绪的玩家_照常排空账本() {
        f.locks.failAlways(Op.ACK, new RuntimeException("销账脚本失败"));
        ScenePlayer player = f.enter(SESSION, PLAYER, ledgerOf(X));
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(player.battleLedger().has(X)).as("进场那次销账失败，条目保留").isTrue();
        f.locks.heal();

        f.reap();

        assertThat(f.locks.ops()).containsExactly(Op.ACK);
        assertThat(player.battleLedger().has(X)).isFalse();
        assertThat(f.acks("reaper", "not_ours")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 恢复代际（FRZ-9）

    /** 两次恢复读同时在途（进场一次、原地解冻又一次）：只认后发的那一代，两份快照不会各跑一遍第 2–4 步。 */
    @Test
    void 两次恢复读在途_后发的先回来并处理_先发的后回来被丢弃() {
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        Call older = f.locks.take(Op.ENTER_READ).execute();
        f.store(BattleFixture.settlement(PLAYER, X, GOLD).build());
        f.battle.onUnfrozenInPlace(f.world, player);
        Call newer = f.locks.pending(Op.ENTER_READ).get(1);

        newer.complete();
        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(f.recoveries("ready")).isEqualTo(1);

        older.reply();
        f.drain();

        assertThat(f.recoveries("ready")).as("更旧的一代不处理、不计数").isEqualTo(1);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    @Test
    void 两次恢复读在途_先发的先回来被丢弃_恢复仍未就绪_后发的回来才就绪() {
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        Call older = f.locks.take(Op.ENTER_READ);
        f.battle.onUnfrozenInPlace(f.world, player);
        Call newer = f.locks.pending(Op.ENTER_READ).get(1);
        f.store(BattleFixture.settlement(PLAYER, X, GOLD).build());

        older.complete();
        f.drain();

        assertThat(player.battle().recovery()).as("旧一代的快照不算数：没有应用、也没有把恢复置为就绪").isEqualTo(Recovery.PENDING);
        assertThat(f.gold(player)).isZero();
        assertThat(f.recoveries("ready")).isZero();
        assertThat(BattleFixture.tipOf(BattleFixture.done(f.prepare(PLAYER, 8)))).as("恢复没就绪，备战仍回 1006").isEqualTo(1006);

        newer.complete();
        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(f.recoveries("ready")).isEqualTo(1);
    }

    /** 更旧一代的恢复读以失败回来：不得把已经就绪的恢复打回 RETRY。 */
    @Test
    void 旧一代恢复读失败回来_不影响已就绪的恢复() {
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        Call older = f.locks.take(Op.ENTER_READ);
        f.battle.onUnfrozenInPlace(f.world, player);
        f.locks.pending(Op.ENTER_READ).get(1).complete();
        f.drain();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);

        older.fail(new RuntimeException("超时"));
        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.recoveries("retry")).isZero();
    }

    // ------------------------------------------------------------------ 已销账集合本身

    @Test
    void 发出销账的那一刻就记下_脚本失败也不撤_丢弃的局同样记下() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        assertThat(BattleFixture.writtenOff(player, X)).isFalse();
        f.locks.failNext(Op.ACK, new RuntimeException("销账脚本失败"));

        // 没有冻结、锁不在：这一局已作废，丢弃并销账（账本里没有 X）
        CompletableFuture<SceneBattleReply> reply = f.deliver(BattleFixture.settlement(PLAYER, X, GOLD).build());
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(BattleFixture.writtenOff(player, X)).isTrue();
        assertThat(BattleFixture.writtenOff(player, 8)).isFalse();
        assertThat(BattleFixture.writtenOff(player, 0)).as("0 号永不记").isFalse();
        assertThat(f.gold(player)).isZero();
    }

    @Test
    void 已销账集合有界_先进先出() {
        PlayerBattle battle = new PlayerBattle();
        for (long id = 1; id <= PlayerBattle.WRITTEN_OFF_CAPACITY + 1; id++) {
            battle.markWrittenOff(id);
        }

        assertThat(battle.writtenOff(1)).as("最早的一个被淘汰").isFalse();
        assertThat(battle.writtenOff(2)).isTrue();
        assertThat(battle.writtenOff(PlayerBattle.WRITTEN_OFF_CAPACITY + 1L)).isTrue();
        battle.markWrittenOff(2);
        battle.markWrittenOff(PlayerBattle.WRITTEN_OFF_CAPACITY + 2L);
        assertThat(battle.writtenOff(2)).as("重复记不刷新次序：2 仍是最早的，被淘汰").isFalse();
    }

    /** 同 epoch 的重复进场沿用旧实例的内存（账本随之过来）：旧实例「已为哪些局发出过销账」也一并沿用，新实例上的过期读照样被挡住。 */
    @Test
    void 同epoch重复进场_沿用旧实例已销账的局() {
        ScenePlayer old = appliedOnlineNotYetSaved();
        assertThat(f.completeSaves(ProgressResult.SAVED)).isEqualTo(1);
        assertThat(BattleFixture.writtenOff(old, X)).isTrue();
        assertThat(old.battleLedger().has(X)).isFalse();

        ScenePlayer fresh = f.reenter(SESSION, PLAYER, 1, f.scene1);
        f.drain();

        assertThat(fresh).isNotSameAs(old);
        assertThat(BattleFixture.writtenOff(fresh, X)).isTrue();
    }
}
