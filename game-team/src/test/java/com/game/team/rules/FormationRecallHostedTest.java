package com.game.team.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.proto.PlayerPresence;
import com.game.discovery.proto.TeamInfo;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamEventS2C;
import com.game.proto.team.TeamEventType;
import com.game.proto.team.TeamFollowFormationStyle;
import com.game.proto.team.TeamView;
import com.game.table.CommonErrorTip;
import com.game.team.presence.TeamDisplay;
import com.game.team.proto.TeamRecord;
import com.game.team.push.TeamPushes;
import com.game.team.store.CommitResult;
import com.game.team.store.IndexEntry;
import com.game.team.store.TeamStore;
import com.game.team.view.MemberDisplay;
import com.game.team.view.TeamViews;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 组队阵型（524）、一键召集（525）与离线托管徽章（is_hosted）对齐单测。 */
class FormationRecallHostedTest {

    @Test
    void setFormationRulesViewAndProjection() {
        long now = 50_000L;
        Map<Long, SessionState> sessions = Map.of(101L, SessionState.ONLINE, 102L, SessionState.ONLINE);
        RuleConfig cfg = new RuleConfig(true);

        TeamRecord rec = TeamRules.apply(Op.create(101L, 7001L, 1), null, now, sessions, cfg).record();
        rec = TeamRules.apply(Op.apply(102L, 1), rec, now + 10, sessions, cfg).record();
        rec = TeamRules.apply(Op.handleApplication(101L, 102L, true), rec, now + 20, sessions, cfg).record();

        // 非队长拒绝
        Decision denied = TeamRules.apply(Op.setFormation(102L, TeamFollowFormationStyle.TEAM_FOLLOW_FORMATION_ECHELON_VALUE),
                rec, now + 30, sessions, cfg);
        assertThat(denied.code()).isEqualTo(TeamTips.NOT_LEADER);
        assertThat(denied.changed()).isFalse();

        // 非法枚举拒绝
        Decision invalid = TeamRules.apply(Op.setFormation(101L, 99), rec, now + 35, sessions, cfg);
        assertThat(invalid.code()).isEqualTo(CommonErrorTip.common_error.kInvalidParameter_VALUE);
        assertThat(invalid.changed()).isFalse();

        // 队长切到雁行两翼
        Decision set = TeamRules.apply(Op.setFormation(101L, TeamFollowFormationStyle.TEAM_FOLLOW_FORMATION_ECHELON_VALUE),
                rec, now + 40, sessions, cfg);
        assertThat(set.code()).isZero();
        assertThat(set.changed()).isTrue();
        assertThat(set.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_FORMATION_CHANGED);
        assertThat(set.record().getFollowFormation()).isEqualTo(TeamFollowFormationStyle.TEAM_FOLLOW_FORMATION_ECHELON_VALUE);
        assertThat(set.kept()).containsExactlyInAnyOrder(101L, 102L);
        rec = set.record();

        // 同阵型幂等无变更
        Decision same = TeamRules.apply(Op.setFormation(101L, TeamFollowFormationStyle.TEAM_FOLLOW_FORMATION_ECHELON_VALUE),
                rec, now + 50, sessions, cfg);
        assertThat(same.code()).isZero();
        assertThat(same.changed()).isFalse();

        // TeamView 与 TeamInfo 投影透传 follow_formation
        TeamView view = TeamViews.teamViewFor(102L, rec, 3L, 1L, now + 60, Map.of());
        assertThat(view.getFollowFormation()).isEqualTo(TeamFollowFormationStyle.TEAM_FOLLOW_FORMATION_ECHELON);
        TeamInfo proj = TeamStore.projectionOf(rec);
        assertThat(proj.getFollowFormation()).isEqualTo(TeamFollowFormationStyle.TEAM_FOLLOW_FORMATION_ECHELON_VALUE);
    }

    @Test
    void recallMembersCooldownAndEventRecipients() {
        long now = 100_000L;
        Map<Long, SessionState> sessions = Map.of(
                101L, SessionState.ONLINE, 102L, SessionState.ONLINE, 103L, SessionState.ONLINE);
        RuleConfig cfg = new RuleConfig(true);

        TeamRecord rec = TeamRules.apply(Op.create(101L, 7002L, 1), null, now, sessions, cfg).record();
        rec = TeamRules.apply(Op.apply(102L, 1), rec, now + 10, sessions, cfg).record();
        rec = TeamRules.apply(Op.handleApplication(101L, 102L, true), rec, now + 20, sessions, cfg).record();
        rec = TeamRules.apply(Op.apply(103L, 1), rec, now + 30, sessions, cfg).record();
        rec = TeamRules.apply(Op.handleApplication(101L, 103L, true), rec, now + 40, sessions, cfg).record();

        // 非队长拒绝
        assertThat(TeamRules.apply(Op.recallMembers(102L), rec, now + 50, sessions, cfg).code())
                .isEqualTo(TeamTips.NOT_LEADER);

        // 首次召集成功：记录冷却时刻，仅通知非队长，不广播空 reason 快照
        Decision d1 = TeamRules.apply(Op.recallMembers(101L), rec, now + 100, sessions, cfg);
        assertThat(d1.code()).isZero();
        assertThat(d1.changed()).isTrue();
        assertThat(d1.record().getLastRecallAtMs()).isEqualTo(now + 100);
        assertThat(d1.recallRecipients()).containsExactlyInAnyOrder(102L, 103L);
        CommitResult commit = new CommitResult(7002L, 5L, d1, Map.of(
                101L, new IndexEntry(7002L, 1L),
                102L, new IndexEntry(7002L, 1L),
                103L, new IndexEntry(7002L, 1L)), now + 100);
        assertThat(TeamPushes.snapshotRecipients(commit)).isEmpty();
        rec = d1.record();

        // 15 秒冷却内拒绝
        Decision cool = TeamRules.apply(Op.recallMembers(101L), rec, now + 100 + TeamLimits.RECALL_COOLDOWN_MS - 1, sessions, cfg);
        assertThat(cool.code()).isEqualTo(CommonErrorTip.common_error.kRateLimitExceeded_VALUE);
        assertThat(cool.changed()).isFalse();

        // 15 秒后再次召集成功
        Decision d2 = TeamRules.apply(Op.recallMembers(101L), rec, now + 100 + TeamLimits.RECALL_COOLDOWN_MS, sessions, cfg);
        assertThat(d2.code()).isZero();
        assertThat(d2.changed()).isTrue();
        assertThat(d2.recallRecipients()).containsExactlyInAnyOrder(102L, 103L);
    }

    @Test
    void hostedFlagOnlyTrueWhenOfflineWithHostedPresence() {
        TeamDisplay display = new TeamDisplay(
                (ids, d) -> Map.of(),
                ids -> CompletableFuture.completedFuture(Map.of(101L, PlayerPresence.newBuilder().setPlayerId(101L).build())),
                ids -> CompletableFuture.completedFuture(Map.of()),
                ids -> CompletableFuture.completedFuture(Map.of(101L, true, 102L, true, 103L, false)));

        Map<Long, MemberDisplay> dc = display.load(List.of(101L, 102L, 103L), Deadline.after(3_000));
        var v101 = TeamViews.memberView(101L, 1, 1, 101L, dc);
        assertThat(v101.getIsOnline()).isTrue();
        assertThat(v101.getIsHosted()).isFalse();

        var v102 = TeamViews.memberView(102L, 1, 2, 101L, dc);
        assertThat(v102.getIsOnline()).isFalse();
        assertThat(v102.getIsHosted()).isTrue();

        var v103 = TeamViews.memberView(103L, 1, 3, 101L, dc);
        assertThat(v103.getIsOnline()).isFalse();
        assertThat(v103.getIsHosted()).isFalse();
    }
}
