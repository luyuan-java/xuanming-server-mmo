package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.BattleSettlementData;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.testing.FakePlayerRepository.PendingHandOff;
import com.game.scene.world.PlayerRepository.HandOffOutcome;
import com.game.scene.world.ScenePlayer;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 交出没提交、原地解冻之后<b>重跑完整的进场恢复</b>（审计 FRZ-9 / GAT-13：规格 §10.5 只要求补「锁步骤」，实现有意做成超集并加了恢复代际）。
 * 走真的 5.2 交出流程（63 → 选目标 → 冻结 → 交出结局「没提交」→ {@code SceneWorld.unfreezeInPlace} → 战斗钩子），不直接调钩子。
 */
class BattleUnfreezeRecoveryTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final int ENTER_FAILED = 3023;

    private final BattleFixture f = new BattleFixture();

    /** 冻结期间到达的确认只续了锁、没挂冻结（两种冻结互斥）；原地解冻后按锁重建 FIGHTING 并推 144——不依赖确认的下一次补发还在不在窗口内。 */
    @Test
    void 交出冻结期间确认只续锁不挂冻结_原地解冻后按锁重建FIGHTING并推144() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_PREPARING, f.deadline(), f.prepareDeadline(), 120);
        PendingHandOff handOff = f.freezeForHandOff(player);
        long deadline = f.deadline();

        f.confirm(PLAYER, X, deadline);
        f.drain();

        assertThat(player.inBattle()).as("交出冻结中不挂战斗冻结").isFalse();
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.confirms("frozen_extended")).isEqualTo(1);
        assertThat(f.reconnectHints(player)).isEmpty();
        f.locks.clearCalls();

        handOff.complete(new HandOffOutcome.LeaseTooShort());

        assertThat(player.frozen()).isFalse();
        assertThat(f.pushedTips(player)).containsExactly(ENTER_FAILED);
        assertThat(player.battle().recovery()).as("重跑的是完整的进场恢复：读回来之前恢复未就绪").isEqualTo(Recovery.PENDING);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ);
        assertThat(BattleFixture.tipOf(BattleFixture.done(f.prepare(PLAYER, 8)))).as("这一次往返内备战回 1006").isEqualTo(1006);

        f.drain();

        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        BattleFreeze freeze = player.battle().freeze();
        assertThat(freeze).isNotNull();
        assertThat(freeze.phase()).isEqualTo(Phase.FIGHTING);
        assertThat(freeze.battleId()).isEqualTo(X);
        assertThat(freeze.deadlineMs()).isEqualTo(deadline);
        assertThat(f.reconnectHints(player)).containsExactly(X);
        assertThat(f.locks.ops()).containsExactly(Op.ENTER_READ, Op.TOUCH);
    }

    /** 超集带来的好处：冻结期间被延后的结算，原地解冻后当场补应用（不必等 battle 的下一次重投），且只应用一次。 */
    @Test
    void 交出冻结期间到达的结算被延后_原地解冻后重跑恢复时补应用一次() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        PendingHandOff handOff = f.freezeForHandOff(player);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, 100).build();
        f.store(settlement);

        CompletableFuture<SceneBattleReply> deferred = f.deliver(settlement);

        assertThat(BattleFixture.done(deferred).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_DEFERRED);
        assertThat(f.gold(player)).as("冻结中零副作用").isZero();
        assertThat(f.locks.calls()).isEmpty();

        handOff.complete(new HandOffOutcome.LeaseTooShort());
        f.drain();

        assertThat(f.gold(player)).isEqualTo(100);
        assertThat(f.battleEnds(player)).hasSize(1);
        assertThat(player.inBattle()).as("这一局已有结算记录，不按锁重建").isFalse();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.settlementsCounted("login", "applied")).isEqualTo(1);

        // battle 的下一次重投：账本命中
        CompletableFuture<SceneBattleReply> again = f.deliver(settlement);
        f.drain();
        assertThat(BattleFixture.done(again).getSettlement()).isEqualTo(com.game.api.proto.SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
        assertThat(f.gold(player)).isEqualTo(100);
        assertThat(f.battleEnds(player)).hasSize(1);
    }

    /** 进场那次恢复读还没回来就发生了原地解冻：两次读在途，只认后一代；先发的那份回来时被丢弃，不会把恢复提前置为就绪。 */
    @Test
    void 进场恢复读还在途时原地解冻_只认解冻后发的那一代() {
        f.locks.hold(Op.ENTER_READ);
        ScenePlayer player = f.load(SESSION, PLAYER, 1, f.scene1, com.game.player.store.state.PlayerState.getDefaultInstance());
        Call first = f.locks.take(Op.ENTER_READ);
        PendingHandOff handOff = f.freezeForHandOff(player);
        handOff.complete(new HandOffOutcome.LeaseTooShort());
        assertThat(f.locks.pending(Op.ENTER_READ)).hasSize(2);
        Call second = f.locks.pending(Op.ENTER_READ).get(1);

        first.complete();
        f.drain();
        assertThat(player.battle().recovery()).as("旧一代不算数").isEqualTo(Recovery.PENDING);
        assertThat(f.recoveries("ready")).isZero();

        second.complete();
        f.drain();
        assertThat(player.battle().recovery()).isEqualTo(Recovery.READY);
        assertThat(f.recoveries("ready")).isEqualTo(1);
    }
}
