package com.game.scene.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.scene.battle.BattleFixture;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoalescedAssetSaveTest {

    @Test
    void assetMutationsTriggerCoalescedSaveWithinOneSecondEvenWhenAuditSinkRejects() {
        AtomicInteger triggerCount = new AtomicInteger();
        AssetAudit failingDelegate = new AssetAudit() {
            @Override
            public void currencyChanged(long playerId, int currencyType, long delta, long before, long after,
                                        Reason reason, long correlationId, String extra) {
                throw new IllegalStateException("模拟号段耗尽或队列满拒收流水");
            }
            @Override
            public void itemGained(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                                   long correlationId, String extra) {
                throw new IllegalStateException("模拟拒收流水");
            }
            @Override
            public void itemDestroyed(long playerId, long itemUuid, int configId, long quantity, Reason reason,
                                      long correlationId, String extra) {
                throw new IllegalStateException("模拟拒收流水");
            }
        };
        AssetAudit wrapped = AssetAudit.withAssetSaveTrigger(failingDelegate, pid -> triggerCount.incrementAndGet());
        assertThatThrownBy(() -> wrapped.currencyChanged(1001L, Wallet.GOLD, 50L, 0L, 50L, AssetAudit.Reason.QUEST_REWARD, 0L, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThat(triggerCount.get()).isEqualTo(1);

        // 1 秒内同一玩家多次资产变动合并成一笔在线存盘，满 1 秒冲刷后提交
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001L);
        player.wallet().add(Wallet.GOLD, 100L);
        f.world.requestCoalescedAssetSave(1001L);
        f.world.requestCoalescedAssetSave(1001L);
        f.world.requestCoalescedAssetSave(1001L);

        // 刚过 500ms 尚未到期，不重复提交
        f.clock.advanceMillis(500L);
        assertThat(f.world.flushDueCoalescedAssetSaves()).isZero();

        // 满 1000ms 到期后合并成 1 笔提交
        f.clock.advanceMillis(500L);
        assertThat(f.world.flushDueCoalescedAssetSaves()).isEqualTo(1);
        assertThat(f.world.flushDueCoalescedAssetSaves()).isZero();
    }
}
