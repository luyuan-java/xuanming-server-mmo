package com.game.inbox.registry;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleOutcome;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

class InboxVoidSettlementTest {

    @Test
    void voidSettlementAllowsOnlySevenWhitelistedFields() {
        BattleSettlementData valid = BattleSettlementData.newBuilder()
                .setBattleId(9001L)
                .setPlayerId(42L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_VOID)
                .setPlayerTeamIndex(1)
                .setFinishedAtMs(1_700_000_000_000L)
                .setMatchMode(3)
                .setBattleConfigId(1001)
                .build();
        assertNull(InboxRegistry.validateBattleSettlement(valid));

        // 任何白名单外字段（血量、金币、阵亡、排位标记或未知字段）均必须拒收
        assertNotNull(InboxRegistry.validateBattleSettlement(valid.toBuilder().setHealth(1).build()));
        assertNotNull(InboxRegistry.validateBattleSettlement(valid.toBuilder().setGoldGain(10).build()));
        assertNotNull(InboxRegistry.validateBattleSettlement(valid.toBuilder().setIsDead(true).build()));
        assertNotNull(InboxRegistry.validateBattleSettlement(valid.toBuilder().setCompetitiveRanked(true).build()));
        assertNotNull(InboxRegistry.validateBattleSettlement(valid.toBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                        .build())
                .build()));
    }
}
