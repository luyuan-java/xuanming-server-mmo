package com.game.battle.room;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.game.proto.BattleActivityContext;
import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class BattleResultAssemblerVoidTest {

    @Test
    void rejectsVoidOutcomeFromBattleEngine() {
        BattleSettlementData normal = BattleSettlementData.newBuilder()
                .setBattleId(101L)
                .setPlayerId(1L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .build();
        assertThrows(IllegalArgumentException.class, () -> BattleResultAssembler.assemble(
                101L, 3, 1, BattleActivityContext.getDefaultInstance(),
                eBattleOutcome.BATTLE_OUTCOME_VOID, List.of(normal), 1000L));

        BattleSettlementData voidSettlement = BattleSettlementData.newBuilder()
                .setBattleId(101L)
                .setPlayerId(1L)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_VOID)
                .build();
        assertThrows(IllegalArgumentException.class, () -> BattleResultAssembler.assemble(
                101L, 3, 1, BattleActivityContext.getDefaultInstance(),
                eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, List.of(voidSettlement), 1000L));
    }
}
