package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleActivityContext;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 对局结果事件组装（基线 {@code room.cpp:1181-1201}、{@code battle_result_activity.h:62-81}；battle-node-spec §4.9、§13.1）。 */
class BattleResultAssemblerTest {

    private static final long BIG = -2L; // uint64 0xFFFF_FFFF_FFFF_FFFE：无符号比较时最大

    private static BattleSettlementData settlement(long playerId, int team, boolean fled, boolean dead, int rounds) {
        return BattleSettlementData.newBuilder()
                .setPlayerId(playerId)
                .setPlayerTeamIndex(team)
                .setFled(fled)
                .setIsDead(dead)
                .setTotalRounds(rounds)
                .build();
    }

    private static BattleResultEvent assemble(eBattleOutcome outcome, BattleActivityContext activity, BattleSettlementData... settlements) {
        return BattleResultAssembler.assemble(77, 3, 12, activity, outcome, List.of(settlements), 1_760_000_123_456L);
    }

    @Test
    void 队伍与队内都按无符号升序_回合数与完成时刻() {
        BattleResultEvent event = assemble(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, BattleActivityContext.getDefaultInstance(),
                settlement(BIG, 1, false, false, 4),
                settlement(9, 0, false, false, 4),
                settlement(3, 1, false, false, 4),
                settlement(5, 0, false, false, 4));

        assertThat(event.getBattleId()).isEqualTo(77);
        assertThat(event.getMatchMode()).isEqualTo(3);
        assertThat(event.getBattleConfigId()).isEqualTo(12);
        assertThat(event.getTeamsCount()).isEqualTo(2);
        assertThat(event.getTeams(0).getTeamIndex()).isZero();
        assertThat(event.getTeams(0).getPlayerIdsList()).containsExactly(5L, 9L);
        assertThat(event.getTeams(1).getTeamIndex()).isEqualTo(1);
        assertThat(event.getTeams(1).getPlayerIdsList()).containsExactly(3L, BIG);
        assertThat(event.getTotalRounds()).isEqualTo(4);
        assertThat(event.getFinishedAtMs()).isEqualTo(1_760_000_123_456L);
    }

    @Test
    void 只有B方胜时winner为1() {
        BattleActivityContext none = BattleActivityContext.getDefaultInstance();
        assertThat(assemble(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, none, settlement(1, 0, false, false, 1)).getWinnerTeamIndex())
                .isEqualTo(1);
        assertThat(assemble(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, none, settlement(1, 0, false, false, 1)).getWinnerTeamIndex())
                .isZero();
        assertThat(assemble(eBattleOutcome.BATTLE_OUTCOME_DRAW, none, settlement(1, 0, false, false, 1)).getWinnerTeamIndex())
                .isZero();
    }

    @Test
    void 逃跑与阵亡名单无符号升序去重() {
        BattleResultEvent event = assemble(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, BattleActivityContext.getDefaultInstance(),
                settlement(BIG, 0, true, true, 2),
                settlement(8, 0, true, false, 2),
                settlement(8, 0, true, true, 2),
                settlement(2, 0, false, true, 2));

        assertThat(event.getFledPlayerIdsList()).containsExactly(8L, BIG);
        assertThat(event.getDeadPlayerIdsList()).containsExactly(2L, 8L, BIG);
    }

    @Test
    void kind为NONE不回显_kind为1回显() {
        assertThat(assemble(eBattleOutcome.BATTLE_OUTCOME_DRAW, BattleActivityContext.getDefaultInstance(),
                settlement(1, 0, false, false, 1)).hasActivityContext()).isFalse();
        assertThat(BattleResultAssembler.isActivity(BattleActivityContext.getDefaultInstance())).isFalse();

        BattleActivityContext trial = BattleActivityContext.newBuilder()
                .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                .setGuildId(66)
                .setActivityId(3)
                .build();
        BattleResultEvent event = assemble(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, trial, settlement(1, 0, false, false, 1));
        assertThat(event.getActivityContext()).isEqualTo(trial);
        assertThat(BattleResultAssembler.isActivity(trial)).isTrue();
    }

    @Test
    void 不认识的kind也回显并走活动通道() {
        BattleActivityContext unknown = BattleActivityContext.newBuilder().setKindValue(7).setActivityId(9).build();

        BattleResultEvent event = assemble(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, unknown, settlement(1, 0, false, false, 1));

        assertThat(BattleResultAssembler.isActivity(unknown)).isTrue();
        assertThat(event.getActivityContext().getKindValue()).isEqualTo(7);
        assertThat(event.getActivityContext().getActivityId()).isEqualTo(9);
    }
}
