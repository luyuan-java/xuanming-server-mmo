package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleSettlementData;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * 异步回调没能跑完时状态不被卡住（审计 FRZ-2 / STL-8 / OPS-11）：回调里的意外异常、或逻辑线程拒绝投递（停服），
 * 不得让进场恢复永久停在 PENDING（reaper 只重跑 RETRY），也不得让应答 future 永不完成（提供方的在途名额只在应答完成时归还，
 * 漏光后本节点的备战 / 确认 / 结算全部回 OVERLOADED 直到重启）。备战的两条见 {@code PrepareBattleTest}。
 *
 * <p>「意外异常」用 {@code FakeBattleLocks.throwNext} 在回调的指定位置同步抛出，或用一个会抛的组队跟随钩子。
 */
class BattleCallbackFailureTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long GOLD = 100;

    private final BattleFixture f = new BattleFixture();

    private static BattleFixture withThrowingTeamFollow(RuntimeException boom) {
        return new BattleFixture(wiring -> new TeamFollow() {
            @Override
            public void onEnteredScene(SceneWorld world, ScenePlayer player) {
            }

            @Override
            public void onBattleFreezeCleared(SceneWorld world, ScenePlayer player) {
                throw boom;
            }
        });
    }

    // ------------------------------------------------------------------ 进场恢复

    /**
     * 恢复读回来了，处理快照（第 2–4 步）中途抛异常：状态置 RETRY（不是留在 PENDING）、单独计 {@code recovery{error}}，下一轮 reaper 重跑。
     * 已应用的那一局有账本兜着，重跑不重复发奖。
     */
    @Test
    void 进场恢复处理快照时抛异常_不卡在PENDING_置RETRY并计error_reaper重跑后就绪且不重复应用() {
        f.store(BattleFixture.settlement(PLAYER, X, GOLD).build());
        // 应用成功之后、收尾里的续锁那一步抛出
        f.locks.throwNext(Op.HOLD, new IllegalStateException("意外"));

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();

        assertThat(player.battle().recovery()).as("不能停在 PENDING：reaper 只重跑 RETRY").isEqualTo(Recovery.RETRY);
        assertThat(f.recoveries("error")).isEqualTo(1);
        assertThat(f.recoveries("retry")).as("与读失败 / 延后的 retry 分开计").isZero();
        assertThat(f.recoveries("ready")).isZero();
        assertThat(f.gold(player)).as("抛出之前金币已入账、账本已登记").isEqualTo(GOLD);
        assertThat(player.battleLedger().has(X)).isTrue();
        assertThat(BattleFixture.tipOf(BattleFixture.done(f.prepare(PLAYER, 8)))).as("恢复没就绪：备战 1006").isEqualTo(1006);

        f.reap();
        f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.recoveries("ready")).isEqualTo(1);
        assertThat(f.gold(player)).as("重跑命中账本，不再发奖").isEqualTo(GOLD);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);
        assertThat(f.settlementsCounted("login", "already_applied")).isEqualTo(1);
        assertThat(player.battleLedger().has(X)).as("落盘后销账、forget").isFalse();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
    }

    /** 恢复读这一步自己同步抛出（端口约定是异常完成，这里只做防御）：钩子不抛、恢复不停在 PENDING。 */
    @Test
    void 恢复读同步抛出_钩子不抛异常_置RETRY等reaper重读() {
        f.locks.throwNext(Op.ENTER_READ, new IllegalStateException("客户端已关闭"));

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        assertThat(player.battle().recovery()).isEqualTo(Recovery.PENDING);
        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        assertThat(f.recoveries("retry")).isEqualTo(1);

        f.reap();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
    }

    @Test
    void 恢复读失败_置RETRY计retry_不计error() {
        f.locks.failNext(Op.ENTER_READ, new RuntimeException("Redis 超时"));

        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, PlayerState.getDefaultInstance());
        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.RETRY);
        assertThat(f.recoveries("retry")).isEqualTo(1);
        assertThat(f.recoveries("error")).isZero();
    }

    // ------------------------------------------------------------------ 结算按锁分支的应答（STL-8）

    @Test
    void 结算按锁分支_回调抛异常_应答异常完成_重投命中账本不重复应用() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, GOLD).build();
        f.store(settlement);
        RuntimeException boom = new IllegalStateException("意外");
        f.locks.throwNext(Op.HOLD, boom);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        assertThat(reply).as("应答等读锁结局").isNotDone();
        f.drain();

        assertThat(reply).as("不能悬着：在途名额只在应答完成时归还").isCompletedExceptionally();
        assertThatThrownBy(reply::join).isInstanceOf(CompletionException.class).hasCause(boom);
        assertThat(f.gold(player)).isEqualTo(GOLD);
        assertThat(player.battleLedger().has(X)).isTrue();

        CompletableFuture<SceneBattleReply> again = f.deliver(settlement);
        f.drain();

        assertThat(BattleFixture.done(again).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }

    @Test
    void 结算按锁分支_逻辑线程已停_应答异常完成() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(BattleFixture.settlement(PLAYER, X, GOLD).build());
        f.logic.rejectNewTasks(true);

        f.locks.take(Op.READ_LOCK).complete();

        assertThat(reply).isCompletedExceptionally();
        assertThatThrownBy(reply::join).isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(f.gold(player)).as("回调没有跑").isZero();
    }

    // ------------------------------------------------------------------ 取消 / 解冻的应答：照常完成

    @Test
    void 离线取消_逻辑线程已停_应答照常完成() {
        f.locks.hold(Op.CANCEL_OFFLINE);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        assertThat(done).as("应答等脚本结局").isNotDone();
        f.logic.rejectNewTasks(true);

        f.locks.take(Op.CANCEL_OFFLINE).complete();

        assertThat(done).isCompleted();
        assertThat(done).isNotCompletedExceptionally();
    }

    /** 在线取消：解冻在应答前已生效，删锁回来后的收尾（组队跟随钩子）抛异常也不让应答悬着、也不把它变成失败。 */
    @Test
    void 取消备战_删锁回调里抛异常_应答照常完成_解冻与删锁都已生效() {
        BattleFixture broken = withThrowingTeamFollow(new IllegalStateException("跟随钩子炸了"));
        ScenePlayer player = broken.enter(SESSION, PLAYER);
        broken.prepared(PLAYER, X);

        CompletableFuture<Void> done = broken.cancel(PLAYER, X);
        assertThat(player.inBattle()).as("取消当场解冻").isFalse();
        assertThat(done).as("应答等删锁结局").isNotDone();
        broken.drain();

        assertThat(done).isCompleted();
        assertThat(done).isNotCompletedExceptionally();
        assertThat(broken.locks.lock(PLAYER)).isNull();
        assertThat(broken.locks.ops()).containsExactly(Op.PREPARE_LOCK, Op.DELETE_PREPARING);
    }

    @Test
    void 取消备战_删锁结局回来时逻辑线程已停_应答照常完成() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING);
        CompletableFuture<Void> done = f.cancel(PLAYER, X);
        assertThat(done).isNotDone();
        f.logic.rejectNewTasks(true);

        f.locks.take(Op.DELETE_PREPARING).complete();

        assertThat(done).isCompleted();
        assertThat(done).isNotCompletedExceptionally();
        assertThat(player.inBattle()).isFalse();
        assertThat(f.follows.freezeCleared).as("投递被拒：回调没有跑，不在别的线程上碰玩家").isEmpty();
    }

    /** 一个回调抛异常不影响同一批里别的回调（逻辑线程的任务彼此独立）。 */
    @Test
    void 一次回调出错_不影响之后的回调() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, GOLD).build();
        f.locks.throwNext(Op.HOLD, new IllegalStateException("意外"));
        CompletableFuture<SceneBattleReply> failed = f.deliver(settlement);
        CompletableFuture<Void> cancelled = f.cancel(2002, 9);

        f.drain();

        assertThat(failed).isCompletedExceptionally();
        assertThat(cancelled).isCompleted();
        assertThat(f.cancels("offline_absent")).isEqualTo(1);
        assertThat(f.gold(player)).isEqualTo(GOLD);
    }
}
