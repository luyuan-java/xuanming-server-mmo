package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.eSpectateChatPreference;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.MatchMode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpectateChatPolicyTest {

    private static BattlePlayerSnapshot human(long id, int team, boolean captain, eSpectateChatPreference pref) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(id)
                .setTeamIndex(team)
                .setIsTeamCaptain(captain)
                .setSpectateChatPref(pref)
                .build();
    }

    private static BattlePlayerSnapshot ai(long id, int team, eSpectateChatPreference pref) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(id)
                .setTeamIndex(team)
                .setAiControlled(true)
                .setSpectateChatPref(pref)
                .build();
    }

    @Test
    void 每个MatchMode枚举都在决策表里显式表态() {
        List<MatchMode> defined = Arrays.stream(MatchMode.values())
                .filter(m -> m != MatchMode.UNRECOGNIZED)
                .toList();
        assertThat(SpectateChatPolicy.decidedModes()).containsExactlyInAnyOrderElementsOf(defined);
    }

    @Test
    void 私密局或总开关关闭一律禁交流_但不影响公开局按偏好放行() {
        List<BattlePlayerSnapshot> players = List.of(
                human(1L, 0, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW));
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_PVE_SOLO,
                eSpectateVisibility.SPECTATE_VISIBILITY_PRIVATE, players, SpectateChatPolicy.Config.DEFAULT)).isFalse();
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_PVE_SOLO,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, players,
                new SpectateChatPolicy.Config(true, false, false, false))).isFalse();
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_PVE_SOLO,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, players, SpectateChatPolicy.Config.DEFAULT)).isTrue();
    }

    @Test
    void PVE组队以队长为准_PVP默认全员一票否决且忽略AI() {
        List<BattlePlayerSnapshot> pveTeam = List.of(
                human(1L, 0, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW),
                human(2L, 0, false, eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID));
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_PVE_TEAM,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, pveTeam, SpectateChatPolicy.Config.DEFAULT)).isTrue();

        List<BattlePlayerSnapshot> pvpVeto = List.of(
                human(1L, 0, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW),
                human(2L, 1, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID),
                ai(3L, 1, eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW));
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_JIANGHU_DUEL,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, pvpVeto, SpectateChatPolicy.Config.DEFAULT)).isFalse();

        List<BattlePlayerSnapshot> pvpAiForbidIgnored = List.of(
                human(1L, 0, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_UNSPECIFIED),
                human(2L, 1, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW),
                ai(3L, 1, eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID));
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_GUILD_WAR_PVP,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, pvpAiForbidIgnored, SpectateChatPolicy.Config.DEFAULT)).isTrue();
    }

    @Test
    void 组队PVP可切为仅双方队长决定_锦标赛可独立关闭() {
        List<BattlePlayerSnapshot> teamPvp = List.of(
                human(1L, 0, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW),
                human(2L, 0, false, eSpectateChatPreference.SPECTATE_CHAT_PREF_FORBID),
                human(3L, 1, true, eSpectateChatPreference.SPECTATE_CHAT_PREF_ALLOW));
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_GUILD_WAR_PVP,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, teamPvp, SpectateChatPolicy.Config.DEFAULT)).isFalse();
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_GUILD_WAR_PVP,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, teamPvp,
                new SpectateChatPolicy.Config(false, false, true, false))).isTrue();
        assertThat(SpectateChatPolicy.decide(MatchMode.MATCH_MODE_SEASON_TOURNAMENT,
                eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC, teamPvp,
                new SpectateChatPolicy.Config(false, false, true, true))).isFalse();
    }
}
