package com.game.match.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.match.MatchMode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 战报回放录制策略表：只录 PVP，新增 {@link MatchMode} 必须显式表态。 */
class BattleReplayPolicyTest {

    @Test
    void 仅PVP模式开启录制_PVE与未填关闭() {
        for (MatchMode pvp : List.of(
                MatchMode.MATCH_MODE_1V1,
                MatchMode.MATCH_MODE_3V3,
                MatchMode.MATCH_MODE_5V5,
                MatchMode.MATCH_MODE_PVP_CHALLENGE,
                MatchMode.MATCH_MODE_PVP_TEAM,
                MatchMode.MATCH_MODE_POLARITY_5V5,
                MatchMode.MATCH_MODE_GUILD_WAR_PVP,
                MatchMode.MATCH_MODE_SEASON_TOURNAMENT)) {
            assertThat(BattleReplayPolicy.decide(pvp)).as(pvp.name()).isTrue();
        }
        for (MatchMode nonPvp : List.of(
                MatchMode.MATCH_MODE_PVE_SOLO,
                MatchMode.MATCH_MODE_PVE_TEAM,
                MatchMode.MATCH_MODE_GUILD_WAR_SIEGE,
                MatchMode.MATCH_MODE_UNSPECIFIED)) {
            assertThat(BattleReplayPolicy.decide(nonPvp)).as(nonPvp.name()).isFalse();
        }
    }

    @Test
    void 表外值与null一律默认关闭() {
        assertThat(BattleReplayPolicy.decide(MatchMode.UNRECOGNIZED)).isFalse();
        assertThat(BattleReplayPolicy.decide(null)).isFalse();
        assertThat(BattleReplayPolicy.decide(MatchMode.forNumber(4242))).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = MatchMode.class, names = "UNRECOGNIZED", mode = EnumSource.Mode.EXCLUDE)
    void 全局开关关闭时一律不录制(MatchMode mode) {
        assertThat(BattleReplayPolicy.decide(false, mode)).isFalse();
    }

    @Test
    void 每个MatchMode枚举值都在显式决策表里() {
        List<MatchMode> declared = Arrays.stream(MatchMode.values())
                .filter(mode -> mode != MatchMode.UNRECOGNIZED)
                .toList();
        assertThat(BattleReplayPolicy.decidedModes())
                .as("新增 MatchMode 必须在 BattleReplayPolicy 的决策表里显式表态")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void 试炼志有格PVE受控开启录制_普通PVE与全局关停保持关闭() {
        var trialCtx = com.game.proto.TrialBattleContext.newBuilder().setJournalEntryId(401).setTrialRuleVersion(1).build();
        assertThat(BattleReplayPolicy.decideForBattle(MatchMode.MATCH_MODE_PVE_SOLO, trialCtx)).isTrue();
        assertThat(BattleReplayPolicy.decideForBattle(MatchMode.MATCH_MODE_PVE_TEAM, trialCtx)).isTrue();
        assertThat(BattleReplayPolicy.decideForBattle(MatchMode.MATCH_MODE_PVE_SOLO, null)).isFalse();
        assertThat(BattleReplayPolicy.decideForBattle(MatchMode.MATCH_MODE_PVE_SOLO,
                com.game.proto.TrialBattleContext.getDefaultInstance())).isFalse();
        assertThat(BattleReplayPolicy.decideForBattle(false, MatchMode.MATCH_MODE_PVE_SOLO, trialCtx)).isFalse();
        assertThat(BattleReplayPolicy.decideForBattle(MatchMode.MATCH_MODE_GUILD_WAR_SIEGE, trialCtx)).isFalse();
    }
}
