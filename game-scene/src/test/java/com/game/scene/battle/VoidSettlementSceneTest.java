package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleOutcome;
import com.game.scene.battle.BattleSettlementService.Outcome;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;
import org.junit.jupiter.api.Test;

class VoidSettlementSceneTest {

    @Test
    void voidSettlementShortCircuitsWithoutTouchingVitalsOrAssets() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001L);
        player.attributes().setHealth(420L);
        player.attributes().setMana(310L);
        long goldBefore = player.wallet().balance(Wallet.GOLD);

        BattleSettlementData validVoid = BattleSettlementData.newBuilder()
                .setBattleId(8801L)
                .setPlayerId(1001L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_VOID)
                .setPlayerTeamIndex(1)
                .setFinishedAtMs(1_700_000_000_000L)
                .setMatchMode(3)
                .setBattleConfigId(1001)
                .build();

        var result = f.settlements.apply(f.world, player, validVoid);
        assertThat(result.outcome()).isEqualTo(Outcome.APPLIED);
        // 不得把血蓝回写成载荷里的默认值 0，也不得改资产
        assertThat(player.attributes().health()).isEqualTo(420L);
        assertThat(player.attributes().mana()).isEqualTo(310L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(goldBefore);
        // 近期战果记入一条空明细 VOID 记录
        var entry = player.battleResults().findByBattleId(8801L);
        assertThat(entry).isNotNull();
        assertThat(entry.getBattleId()).isEqualTo(8801L);
        assertThat(entry.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_VOID_VALUE);
        assertThat(entry.getCurrenciesCount()).isZero();
        assertThat(entry.getItemsGainedCount()).isZero();
        assertThat(entry.getItemsConsumedCount()).isZero();
        assertThat(entry.getExpGain()).isZero();
    }

    @Test
    void invalidVoidSettlementWithExtraFieldsIsDeferredWithZeroSideEffects() {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1002L);
        player.attributes().setHealth(420L);
        player.attributes().setMana(310L);
        long goldBefore = player.wallet().balance(Wallet.GOLD);

        BattleSettlementData malformedVoid = BattleSettlementData.newBuilder()
                .setBattleId(8802L)
                .setPlayerId(1002L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_VOID)
                .setGoldGain(500L)
                .build();

        var result = f.settlements.apply(f.world, player, malformedVoid);
        assertThat(result.outcome()).isEqualTo(Outcome.DEFERRED);
        assertThat(player.attributes().health()).isEqualTo(420L);
        assertThat(player.attributes().mana()).isEqualTo(310L);
        assertThat(player.wallet().balance(Wallet.GOLD)).isEqualTo(goldBefore);
        assertThat(player.battleResults().findByBattleId(8802L)).isNull();
        assertThat(player.battleLedger().has(8802L)).isFalse();
    }
}
