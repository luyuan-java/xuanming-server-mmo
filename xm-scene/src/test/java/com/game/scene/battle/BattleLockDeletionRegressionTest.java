package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.BattleSettlementData;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.testing.FakeBattleLocks.Call;
import com.game.scene.testing.FakeBattleLocks.Op;
import com.game.scene.world.ScenePlayer;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 删锁与销账的两处修正：
 * <ul>
 *   <li>审计 FRZ-7：取消备战（以及备战失败 / 过期的尽力删锁，见 {@code PrepareBattleTest}）只删备战锁（b == X 且 s ≠ F）。
 *       删除被 Redisson 排到紧随其后的确认之后时，锁已标 F、迟到确认已重建 FIGHTING 冻结——不看 s 的删除会留下「没有锁的在打冻结」。
 *       reaper 判废仍用不看 s 的 DELETE_IF_MATCH（要删的就是 F 锁）。</li>
 *   <li>审计 STL-9：结算「无冻结按锁」回调里的判定次序同规格 §7.10 第 7 步与基线——先看锁（不是本局 → 丢弃并销账），再看回调时刻的冻结
 *       （别的局 → 丢弃、不销账）。</li>
 * </ul>
 */
class BattleLockDeletionRegressionTest {

    private static final int SESSION = 11;
    private static final long PLAYER = 1001;
    private static final long X = 7;
    private static final long Y = 8;

    private final BattleFixture f = new BattleFixture();

    // ------------------------------------------------------------------ FRZ-7

    @Test
    void 取消备战_发的是只删备战锁_删完才完成应答_删完才补组队跟随() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.clearCalls();
        f.locks.hold(Op.DELETE_PREPARING);

        CompletableFuture<Void> done = f.cancel(PLAYER, X);

        assertThat(player.inBattle()).as("取消当场解冻").isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.DELETE_PREPARING);
        assertThat(f.locks.take(Op.DELETE_PREPARING).battleId()).isEqualTo(X);
        assertThat(done).as("应答等删锁结局").isNotDone();
        assertThat(f.follows.freezeCleared).as("删锁完成之前不补跟随（跟随链会读到还没删掉的锁）").isEmpty();

        f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(done).isCompleted();
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
        assertThat(f.cancels("cleared")).isEqualTo(1);
    }

    /**
     * B1 场景里取消与确认几乎同时到：取消先处理（摘冻结、删锁在途），确认随后处理（无冻结 → 发 CONFIRM）。Redis 上 CONFIRM 先执行：
     * 锁标 F、迟到确认重建 FIGHTING 冻结并推 144；随后那条在途的删除才执行——必须被拒（回 2），锁留着。
     */
    @Test
    void 取消后紧跟确认_确认先于删锁执行_删除被拒_重建的FIGHTING冻结仍有锁() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.prepared(PLAYER, X);
        f.locks.hold(Op.DELETE_PREPARING, Op.CONFIRM);
        CompletableFuture<Void> cancelled = f.cancel(PLAYER, X);
        long deadline = f.deadline();
        f.confirm(PLAYER, X, deadline);

        f.locks.take(Op.CONFIRM).complete();
        f.drain();
        assertThat(player.battle().freeze().phase()).as("迟到确认按锁重建").isEqualTo(Phase.FIGHTING);
        assertThat(f.reconnectHints(player)).containsExactly(X);

        Call delete = f.locks.take(Op.DELETE_PREPARING).complete();
        f.drain();

        assertThat(delete.lastReply()).as("是本局但已 F：拒绝").isEqualTo(BattleRedis.PREPARING_DELETE_FIGHTING);
        assertThat(f.locks.lockBattleId(PLAYER)).as("在打的局的锁还在").isEqualTo(X);
        assertThat(f.locks.lockState(PLAYER)).isEqualTo("F");
        assertThat(f.locks.lockTtlSec(PLAYER)).isEqualTo(BattleFixture.BATTLE_MILLIS / 1000 + 60);
        assertThat(player.battle().freeze().phase()).isEqualTo(Phase.FIGHTING);
        assertThat(cancelled).isCompleted();
        assertThat(f.follows.freezeCleared).as("玩家又在战斗中了，不补跟随").isEmpty();
        assertThat(f.locks.count(Op.DELETE_IF_MATCH)).as("取消路径不发不看 s 的删除").isZero();
    }

    /** reaper 判废要删的就是 F 锁：仍用不看 s 的 DELETE_IF_MATCH（换成只删备战锁的话这把锁会一直留到 TTL，玩家排不了队）。 */
    @Test
    void reaper判废FIGHTING_没有本局记录_用不看阶段的条件删锁把F锁删掉() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.fighting(PLAYER, X);
        f.locks.clearCalls();
        f.advance(BattleFixture.BATTLE_MILLIS + BattleRedis.FIGHTING_EXPIRY_GRACE.toMillis() + 1);

        f.reap();

        assertThat(player.inBattle()).isFalse();
        assertThat(f.locks.ops()).containsExactly(Op.READ_SETTLEMENT, Op.DELETE_IF_MATCH);
        assertThat(f.locks.last(Op.DELETE_IF_MATCH).lastReply()).isEqualTo(1L);
        assertThat(f.locks.lock(PLAYER)).isNull();
        assertThat(f.rescues("miss")).isEqualTo(1);
        assertThat(f.follows.freezeCleared).containsExactly(PLAYER);
    }

    // ------------------------------------------------------------------ STL-9

    /**
     * 读锁往返之间玩家进了下一局 Y：回调时冻结是 Y、锁也是 Y。规格与基线先判锁：不是本局 → 丢弃并销账 X（ACK 只删 X 的记录，b ≠ X 的锁不动）。
     * 修之前先判冻结、不销账，记录 X 留在 Redis；发件箱恰好用尽时它会留到下次进场，被不看锁的 login 路径应用。
     */
    @Test
    void 按锁回调时锁不是本局且已有别的局的冻结_丢弃并销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, 100).build();
        f.store(settlement);
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        Call lockRead = f.locks.take(Op.READ_LOCK);
        f.prepared(PLAYER, Y);
        BattleFreeze next = player.battle().freeze();

        lockRead.complete();
        f.drain();

        assertThat(lockRead.lastReply()).as("读到的锁是下一局的").isEqualTo(Y);
        assertThat(BattleFixture.done(reply).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.locks.calls(Op.ACK)).as("销账 X").singleElement().satisfies(ack -> assertThat(ack.battleId()).isEqualTo(X));
        assertThat(f.locks.last(Op.ACK).lastReply()).as("只删了 X 的记录，没碰锁").isEqualTo(1L);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.locks.lockBattleId(PLAYER)).isEqualTo(Y);
        assertThat(player.battle().freeze()).isSameAs(next);
        assertThat(f.gold(player)).isZero();
        assertThat(f.battleEnds(player)).isEmpty();
        assertThat(f.settlementsCounted("by_lock", "discarded_void")).isEqualTo(1);
        assertThat(f.settlementsCounted("by_lock", "discarded_mismatch")).isZero();
        assertThat(f.acks("discard", "released")).isEqualTo(1);
    }

    /** 第二段判定：锁读到的确实是本局，但回调时已有别的局的冻结 → 丢弃、<b>不</b>销账（同基线 pb.cpp:1898-1904）。 */
    @Test
    void 按锁回调时锁是本局但已有别的局的冻结_丢弃不销账_记录留着() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        f.locks.putLock(PLAYER, X, BattleFixture.BATTLE_NODE, BattleRedis.STATE_FIGHTING, f.deadline(), 0, 360);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, 100).build();
        f.store(settlement);
        f.locks.hold(Op.READ_LOCK);
        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        Call lockRead = f.locks.take(Op.READ_LOCK).execute();
        BattleFreeze other = new BattleFreeze(Y, BattleFixture.BATTLE_NODE, Phase.PREPARING, f.deadline(), f.prepareDeadline(), true);
        BattleFixture.setFreeze(player, other);

        lockRead.reply();
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.locks.count(Op.ACK)).as("不销账").isZero();
        assertThat(f.locks.hasSettlement(PLAYER, X)).isTrue();
        assertThat(player.battle().freeze()).isSameAs(other);
        assertThat(f.gold(player)).isZero();
        assertThat(f.settlementsCounted("by_lock", "discarded_mismatch")).isEqualTo(1);
        assertThat(f.settlementsCounted("by_lock", "discarded_void")).isZero();
    }

    @Test
    void 按锁回调时锁不在且没有冻结_丢弃并销账() {
        ScenePlayer player = f.enter(SESSION, PLAYER);
        BattleSettlementData settlement = BattleFixture.settlement(PLAYER, X, 100).build();
        f.store(settlement);

        CompletableFuture<SceneBattleReply> reply = f.deliver(settlement);
        f.drain();

        assertThat(BattleFixture.done(reply).getSettlement()).isEqualTo(SettlementDisposition.SETTLEMENT_DISCARDED);
        assertThat(f.locks.ops()).containsExactly(Op.READ_LOCK, Op.ACK);
        assertThat(f.locks.hasSettlement(PLAYER, X)).isFalse();
        assertThat(f.gold(player)).isZero();
        assertThat(f.settlementsCounted("by_lock", "discarded_void")).isEqualTo(1);
    }
}
