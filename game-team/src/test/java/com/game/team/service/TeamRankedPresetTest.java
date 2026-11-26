package com.game.team.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.match.MatchMode;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.ConfirmRankedPresetRequest;
import com.game.proto.team.HandleApplicationRequest;
import com.game.proto.team.KickMemberRequest;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamResponse;
import com.game.table.ConfigTables;
import com.game.team.rules.TeamRankedRules;
import com.game.team.rules.TeamTips;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 队伍排位预设确认（452）、名单变动失效与 PVP_TEAM 开战门禁回归。 */
class TeamRankedPresetTest {

    private TeamMatchFixture fx;

    @BeforeEach
    void setUp() {
        fx = new TeamMatchFixture(TeamMatchFixture.inMemory());
    }

    @AfterEach
    void tearDown() {
        if (fx != null) {
            fx.cleanup();
        }
    }

    @Test
    void 真实配表加载与非法预设版本校验() {
        TeamRankedRules rules = TeamRankedRules.from(ConfigTables.load(Path.of("../config-data/tables")));
        assertThat(rules.ruleVersion()).isEqualTo(2);
        assertThat(rules.isValidSelection(101, 201, 2)).isTrue();
        assertThat(rules.isValidSelection(103, 203, 2)).isTrue();
        assertThat(rules.isValidSelection(201, 101, 2)).isFalse();
        assertThat(rules.isValidSelection(101, 201, 1)).isFalse();
        assertThat(rules.isValidSelection(999, 201, 2)).isFalse();
    }

    @Test
    void 队员确认预设_名单变更失效_PVP开战门禁与全员确认放行() {
        long[] team = fx.team(2);
        long teamId = team[0];
        long leader = team[1];
        long member = team[2];
        fx.takePushes();

        // 1. 非法预设 / 版本不符直接回 4034
        TeamResponse badPreset = fx.service.confirmRankedPreset(leader, ConfirmRankedPresetRequest.newBuilder()
                .setExpectedTeamId(teamId).setCharacterPresetId(999).setPetPresetId(201).setRuleVersion(1).build(),
                TeamMatchFixture.deadline());
        assertThat(badPreset.getErrorMessage().getId()).isEqualTo(TeamTips.RANKED_PRESET_INVALID);

        // 2. 正式人宠会武(PVP_TEAM)已解耦为本人真实养成：仅校验规则版本，不阻塞于免费预设确认
        TeamResponse badVerEarly = fx.service.startTeamMatch(leader, StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(teamId).setBattleConfigId(5).setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM)
                .setRankedRuleVersion(99).build(), TeamMatchFixture.deadline());
        assertThat(badVerEarly.getErrorMessage().getId()).isEqualTo(TeamTips.RANKED_PRESET_INVALID);

        // 3. 队长确认后视图仅队长已确认，整体仍未全员确认，且向队友推送 RANKED_PRESET_CONFIRMED
        TeamResponse cLeader = fx.service.confirmRankedPreset(leader, ConfirmRankedPresetRequest.newBuilder()
                .setExpectedTeamId(teamId).setCharacterPresetId(102).setPetPresetId(202).setRuleVersion(1).build(),
                TeamMatchFixture.deadline());
        assertThat(cLeader.hasErrorMessage()).isFalse();
        assertThat(cLeader.getTeam().getRankedPresetsAllConfirmed()).isFalse();
        assertThat(cLeader.getTeam().getRankedRuleVersion()).isEqualTo(1);
        TeamMemberView leaderView = cLeader.getTeam().getMembers(0);
        assertThat(leaderView.getRankedCharacterPresetId()).isEqualTo(102);
        assertThat(leaderView.getRankedPetPresetId()).isEqualTo(202);
        assertThat(leaderView.getRankedPresetConfirmed()).isTrue();
        assertThat(TeamMatchFixture.byReason(fx.takePushes(), TeamChangeReason.TEAM_CHANGE_REASON_RANKED_PRESET_CONFIRMED))
                .hasSize(1);

        // 4. 队友确认 -> 全员已确认；若请求带旧/错规则版本则回 4034
        TeamResponse cMember = fx.service.confirmRankedPreset(member, ConfirmRankedPresetRequest.newBuilder()
                .setExpectedTeamId(teamId).setCharacterPresetId(103).setPetPresetId(201).setRuleVersion(1).build(),
                TeamMatchFixture.deadline());
        assertThat(cMember.hasErrorMessage()).isFalse();
        assertThat(cMember.getTeam().getRankedPresetsAllConfirmed()).isTrue();

        TeamResponse badVerStart = fx.service.startTeamMatch(leader, StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(teamId).setBattleConfigId(5).setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM)
                .setRankedRuleVersion(99).build(), TeamMatchFixture.deadline());
        assertThat(badVerStart.getErrorMessage().getId()).isEqualTo(TeamTips.RANKED_PRESET_INVALID);

        // 6. 新成员加入使原全员确认失效，但保留各自上次预设选择
        long third = fx.player();
        assertThat(fx.service.applyJoinTeam(third, ApplyJoinTeamRequest.newBuilder().setTargetPlayerId(leader).build(),
                TeamMatchFixture.deadline()).hasErrorMessage()).isFalse();
        TeamResponse joined = fx.service.handleApplication(leader, HandleApplicationRequest.newBuilder()
                .setExpectedTeamId(teamId).setApplicantId(third).setApprove(true).build(), TeamMatchFixture.deadline());
        assertThat(joined.hasErrorMessage()).isFalse();
        assertThat(joined.getTeam().getRankedPresetsAllConfirmed()).isFalse();
        assertThat(joined.getTeam().getMembers(0).getRankedPresetConfirmed()).isFalse();
        assertThat(joined.getTeam().getMembers(0).getRankedCharacterPresetId()).isEqualTo(102);

        // 7. 三人均确认后踢出一人 -> 留队两人确认态再次失效
        for (long p : new long[] {leader, member, third}) {
            assertThat(fx.service.confirmRankedPreset(p, ConfirmRankedPresetRequest.newBuilder()
                    .setExpectedTeamId(teamId).setCharacterPresetId(101).setPetPresetId(203).setRuleVersion(1).build(),
                    TeamMatchFixture.deadline()).hasErrorMessage()).isFalse();
        }
        TeamResponse kicked = fx.service.kickMember(leader, KickMemberRequest.newBuilder()
                .setExpectedTeamId(teamId).setTargetPlayerId(third).build(), TeamMatchFixture.deadline());
        assertThat(kicked.hasErrorMessage()).isFalse();
        assertThat(kicked.getTeam().getRankedPresetsAllConfirmed()).isFalse();
        assertThat(kicked.getTeam().getMembersList()).allSatisfy(m -> assertThat(m.getRankedPresetConfirmed()).isFalse());

        // 8. 两人重新确认后 PVP_TEAM 开战通过排位预设门禁
        for (long p : new long[] {leader, member}) {
            assertThat(fx.service.confirmRankedPreset(p, ConfirmRankedPresetRequest.newBuilder()
                    .setExpectedTeamId(teamId).setCharacterPresetId(101).setPetPresetId(201).setRuleVersion(1).build(),
                    TeamMatchFixture.deadline()).hasErrorMessage()).isFalse();
        }
        TeamResponse started = fx.service.startTeamMatch(leader, StartTeamMatchRequest.newBuilder()
                .setExpectedTeamId(teamId).setBattleConfigId(1).setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM)
                .setRankedRuleVersion(1).build(), TeamMatchFixture.deadline());
        assertThat(started.hasErrorMessage()).isFalse();
    }
}
