package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.state.CurrencyDebtState;
import com.game.player.store.state.CurrencyState;
import com.game.scene.audit.AssetAudit;
import com.game.scene.battle.BattleFixture;
import com.game.scene.battle.BattleFreeze;
import com.game.scene.battle.PlayerBattleService;
import com.game.scene.player.BagType;
import com.game.scene.player.Wallet;
import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 覆盖 Batch 4S-2（欠款未知字段守卫、背包腾位唤醒、FIGHTING 到期查终局）与 Actor 隔离审阅两处收尾。 */
class Batch4S2AndActorGuardTest {

    @Test
    void 场景逻辑线程守卫_单线程执行器内放行_越线程调用抛异常() throws Exception {
        BattleFixture f = new BattleFixture();
        var logic = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-scene-logic"));
        try {
            Thread logicThread = logic.submit(Thread::currentThread).get();
            f.world.withLogicThreadGuard(() -> Thread.currentThread() == logicThread);

            // 逻辑线程内正常操作
            logic.submit(() -> {
                ScenePlayer p = f.enter(1, 1001L);
                assertThat(f.world.playerById(1001L)).isSameAs(p);
                f.world.step();
            }).get();

            // 跨线程越线调用立即抛出 IllegalStateException
            assertThatThrownBy(() -> f.world.playerById(1001L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scene-logic");
            assertThatThrownBy(f.world::step)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("scene-logic");
        } finally {
            logic.shutdownNow();
        }
    }

    @Test
    void 欠款带未知字段时冻结不扣还不删欠款且记录版本偏移计数() {
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(7).build())
                .build();
        CurrencyDebtState debtWithUnknown = CurrencyDebtState.newBuilder()
                .setCurrencyType(Wallet.GOLD)
                .setOwed(500L)
                .setPaid(0L)
                .setUnknownFields(unknown)
                .build();
        Wallet wallet = Wallet.restore(CurrencyState.newBuilder()
                .addBalances(0L)
                .addDebts(debtWithUnknown)
                .build());

        long beforeSkew = Wallet.clawbackVersionSkewCount();
        Wallet.Change change = wallet.add(Wallet.GOLD, 1_000L, false, 100_000L);

        assertThat(change.ok()).isTrue();
        assertThat(change.after()).as("欠款带未知字段按冻结处理，收入全额入账").isEqualTo(1_000L);
        assertThat(wallet.debt(Wallet.GOLD)).isNotNull();
        assertThat(wallet.debt(Wallet.GOLD).remaining()).isEqualTo(500L);
        assertThat(wallet.debt(Wallet.GOLD).hasUnknownFields()).isTrue();
        assertThat(Wallet.clawbackVersionSkewCount()).isGreaterThan(beforeSkew);
    }

    @Test
    void 背包腾出空间出口立即触发收件箱唤醒回调() {
        BattleFixture f = new BattleFixture();
        AtomicInteger wakeups = new AtomicInteger();
        f.bags.withOnInventorySpaceFreed(p -> wakeups.incrementAndGet());
        ScenePlayer p = f.enter(1, 2001L);

        assertThat(f.bags.addItems(p, BagType.TEMPORARY, Map.of(10, 2L), AssetAudit.Reason.ITEM_AWARD, 1L, "").ok()).isTrue();
        assertThat(wakeups.get()).isZero();

        assertThat(f.bags.retrieveTemporary(p, 0L, true)).isZero();
        assertThat(wakeups.get()).as("取回临时格立即唤醒").isEqualTo(1);

        f.bags.removeClamped(p, BagType.INVENTORY, Map.of(10, 1L), AssetAudit.Reason.ITEM_DESTROY, 2L, "");
        assertThat(wakeups.get()).as("扣减物品立即唤醒").isEqualTo(2);
    }

    @Test
    void FIGHTING到期先查SQL终局_未应用续冻至多15分钟_判废即解冻() {
        BattleFixture f = new BattleFixture();
        AtomicReference<PlayerBattleService.SqlTerminalVerdict> verdict =
                new AtomicReference<>(PlayerBattleService.SqlTerminalVerdict.HOLD_AND_EXTEND);
        f.battle.withTerminalOutcomeReader((pid, bid) -> verdict.get());

        ScenePlayer p = f.enter(1, 3001L);
        f.fighting(3001L, 777L);
        long effectiveDeadline = p.battle().freeze().effectiveDeadlineMs();

        // 超过到期时刻：HOLD_AND_EXTEND -> 续冻，不解冻
        f.clock.advanceMillis((effectiveDeadline - f.clock.epochMillis()) + 5_000L);
        f.battle.reap();
        assertThat(p.inBattle()).as("COMPLETE 未应用或无终局时应续冻").isTrue();

        // 超过最大续冻上限（+15 分钟）：强行解冻兜底
        f.clock.advanceMillis(BattleFreeze.MAX_FIGHTING_EXTEND_MS + 60_000L);
        f.battle.reap();
        assertThat(p.inBattle()).as("超出 15 分钟续冻上限强制解冻").isFalse();

        // 另开一局：SAFE_TO_RELEASE (ABORT/无原件) -> 到期立即解冻
        f.fighting(3001L, 778L);
        long effectiveDeadline2 = p.battle().freeze().effectiveDeadlineMs();
        verdict.set(PlayerBattleService.SqlTerminalVerdict.SAFE_TO_RELEASE);
        f.clock.advanceMillis((effectiveDeadline2 - f.clock.epochMillis()) + 5_000L);
        f.battle.reap();
        assertThat(p.inBattle()).as("ABORT/无原件立即解冻").isFalse();
    }
}
