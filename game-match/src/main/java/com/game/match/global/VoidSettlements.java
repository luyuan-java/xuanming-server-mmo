package com.game.match.global;

import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleOutcome;
import java.util.Set;

/**
 * 作废战斗结算（{@code BATTLE_OUTCOME_VOID = 4}）的构造方法与七字段白名单校验。
 * 只由 match 的作废决定产出，绝不携带任何奖励、消耗、血蓝终值或活动扩展字段。
 */
public final class VoidSettlements {

    public static final Set<Integer> ALLOWED_FIELD_NUMBERS = Set.of(
            BattleSettlementData.BATTLE_ID_FIELD_NUMBER,
            BattleSettlementData.PLAYER_ID_FIELD_NUMBER,
            BattleSettlementData.OUTCOME_FIELD_NUMBER,
            BattleSettlementData.PLAYER_TEAM_INDEX_FIELD_NUMBER,
            BattleSettlementData.FINISHED_AT_MS_FIELD_NUMBER,
            BattleSettlementData.MATCH_MODE_FIELD_NUMBER,
            BattleSettlementData.BATTLE_CONFIG_ID_FIELD_NUMBER);

    private VoidSettlements() {
    }

    public static BattleSettlementData build(long battleId, long playerId, int teamIndex,
                                             long finishedAtMs, int matchMode, int battleConfigId) {
        if (battleId == 0 || playerId == 0) {
            throw new IllegalArgumentException("作废结算必须携带非零 battle_id 与 player_id");
        }
        BattleSettlementData.Builder builder = BattleSettlementData.newBuilder()
                .setBattleId(battleId)
                .setPlayerId(playerId)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_VOID);
        if (teamIndex != 0) {
            builder.setPlayerTeamIndex(teamIndex);
        }
        if (finishedAtMs != 0) {
            builder.setFinishedAtMs(finishedAtMs);
        }
        if (matchMode != 0) {
            builder.setMatchMode(matchMode);
        }
        if (battleConfigId != 0) {
            builder.setBattleConfigId(battleConfigId);
        }
        return builder.build();
    }

    /** 按“只许出现哪些字段”判定，严禁逐个列举效果字段。 */
    public static boolean isValid(BattleSettlementData settlement) {
        if (settlement == null || settlement.getBattleId() == 0 || settlement.getPlayerId() == 0
                || settlement.getOutcome() != eBattleOutcome.BATTLE_OUTCOME_VOID
                || !settlement.getUnknownFields().asMap().isEmpty()) {
            return false;
        }
        for (var fd : settlement.getAllFields().keySet()) {
            if (!ALLOWED_FIELD_NUMBERS.contains(fd.getNumber())) {
                return false;
            }
        }
        return true;
    }
}
