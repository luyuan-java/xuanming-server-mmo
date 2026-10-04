package com.game.team.rules;

import static com.game.team.rules.RuleFixtures.NOW;
import static com.game.team.rules.RuleFixtures.newRecord;
import static com.game.team.rules.RuleFixtures.withLock;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 开战锁的纯函数规则（基线 team_battle_test.go:179-229 TestMatchLockRules，team-spec §2.6）。 */
class TeamMatchLockRulesTest {

    @Test
    void 开战锁规则() {
        assertThat(TeamLimits.CAPACITY)
                .as("引擎每队上限 kMaxBattleTeamSize 同样钉住 5（两边互不依赖），改容量要两边同步")
                .isEqualTo(5);

        TeamRecord rec = newRecord(1, 2, 3).toBuilder().setLeaderId(2).build();
        assertThat(TeamRules.matchRoster(rec)).as("队长在前，其余按 join_seq").containsExactly(2L, 1L, 3L);

        assertThat(TeamRules.checkMatchTeamSize(rec, 0)).isEqualTo(TeamTips.DUNGEON_NOT_OPEN);
        assertThat(TeamRules.checkMatchTeamSize(rec, 2)).isEqualTo(TeamTips.SIZE_EXCEEDED);
        assertThat(TeamRules.checkMatchTeamSize(rec, 3)).isZero();
        assertThat(TeamRules.checkMatchTeamSize(rec, 5)).as("人数少于上限允许开战（J-5）").isZero();
        assertThat(TeamRules.checkMatchStart(rec, 1, NOW)).isEqualTo(TeamTips.NOT_LEADER);
        assertThat(TeamRules.checkMatchStart(rec, 2, NOW)).isZero();

        TeamRecord orig = rec.toBuilder().build();
        Decision d = TeamRules.lockMatch(rec, 2, "tok", List.of(2L, 1L, 3L), NOW + 83_000, NOW);
        assertThat(d.code()).isZero();
        assertThat(d.changed()).isTrue();
        assertThat(rec).as("纯函数不改入参").isEqualTo(orig);
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED);
        assertThat(d.kept()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(d.joined()).isEmpty();
        assertThat(d.left()).isEmpty();
        assertThat(d.record().getMatchLockToken()).isEqualTo("tok");
        assertThat(d.record().getMatchLockExpireAtMs()).isEqualTo(NOW + 83_000);
        assertThat(d.record().getMatchLockRosterList()).containsExactly(2L, 1L, 3L);
        assertThat(TeamRules.lockMatch(rec, 2, "tok", List.of(2L, 1L), NOW + 83_000, NOW).code())
                .as("名单与成员集合不等绝不加锁").isEqualTo(TeamTips.STATE_CHANGED);
        assertThat(TeamRules.lockMatch(d.record(), 2, "tok2", List.of(2L, 1L, 3L), NOW + 84_000, NOW + 1).code())
                .isEqualTo(TeamTips.IN_MATCH);

        TeamRecord locked = d.record();
        assertThat(TeamRules.releaseMatchLock(locked, "other", true, NOW + 1, null).changed()).as("token 不符不清").isFalse();
        assertThat(TeamRules.releaseMatchLock(locked, "tok", true, NOW + 83_000, null).changed()).as("锁已过期不清").isFalse();
        Decision failed = TeamRules.releaseMatchLock(locked, "tok", false, NOW + 1, null);
        assertThat(failed.changed()).isTrue();
        assertThat(failed.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED);
        assertThat(failed.record().getMatchLockToken()).isEmpty();
        assertThat(failed.record().getMatchLockExpireAtMs()).isZero();
        assertThat(failed.record().getMatchLockRosterList()).isEmpty();
        assertThat(failed.kept()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(TeamRules.releaseMatchLock(locked, "tok", true, NOW + 1, null).reason())
                .isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED);

        // 锁期间 {-2} 修复移出成员：锁内名单同步去掉他，保持 roster == members
        Decision fix = TeamRules.repairRemoveMember(locked, 3, NOW + 1, null);
        assertThat(fix.changed()).isTrue();
        assertThat(fix.record().getMatchLockRosterList()).containsExactly(2L, 1L);
        assertThat(fix.record().getMatchLockRosterList())
                .containsExactlyInAnyOrderElementsOf(TeamRules.memberIds(fix.record()));
    }

    // ---- Java 增项：§2.6 列出、基线测试没覆盖的分支 ----

    @Test
    void 开战前置_无队4013_caller为0或非队长4018() {
        TeamRecord rec = newRecord(1, 2);
        assertThat(TeamRules.checkMatchStart(null, 1, NOW)).isEqualTo(TeamTips.NO_TEAM);
        assertThat(TeamRules.checkMatchStart(rec, 0, NOW)).isEqualTo(TeamTips.NOT_LEADER);
        assertThat(TeamRules.checkMatchStart(rec.toBuilder().setLeaderId(0).build(), 0, NOW))
                .as("caller 为 0 时哪怕 leader 字段也是 0 也不放行").isEqualTo(TeamTips.NOT_LEADER);
        assertThat(TeamRules.checkMatchStart(withLock(rec, NOW + 1), 1, NOW)).isEqualTo(TeamTips.IN_MATCH);
        assertThat(TeamRules.checkMatchStart(withLock(rec, NOW), 1, NOW)).as("过期锁不挡开战").isZero();
    }

    @Test
    void 加锁_token为空或截止不晚于now回4030_过期锁可被覆盖() {
        TeamRecord rec = newRecord(1, 2);
        List<Long> roster = List.of(1L, 2L);
        assertThat(TeamRules.lockMatch(rec, 1, "", roster, NOW + 1, NOW).code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(TeamRules.lockMatch(rec, 1, null, roster, NOW + 1, NOW).code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(TeamRules.lockMatch(rec, 1, "tok", roster, NOW, NOW).code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(TeamRules.lockMatch(rec, 2, "tok", roster, NOW + 1, NOW).code()).isEqualTo(TeamTips.NOT_LEADER);
        assertThat(TeamRules.lockMatch(null, 1, "tok", roster, NOW + 1, NOW).code()).isEqualTo(TeamTips.NO_TEAM);

        Decision d = TeamRules.lockMatch(withLock(rec, NOW), 1, "new", List.of(2L, 1L), NOW + 5, NOW);
        assertThat(d.code()).isZero();
        assertThat(d.record().getMatchLockToken()).isEqualTo("new");
        assertThat(d.actor()).isEqualTo(1L);
    }

    @Test
    void 加锁不做惰性转让_释放锁会做() {
        TeamRecord rec = newRecord(1, 2);
        var sessions = java.util.Map.of(1L, SessionState.ABSENT, 2L, SessionState.ONLINE);
        Decision lockedDecision = TeamRules.lockMatch(rec, 1, "tok", List.of(1L, 2L), NOW + 10, NOW);
        assertThat(lockedDecision.leaderOfflineTransferred()).isFalse();
        assertThat(lockedDecision.record().getLeaderId()).isEqualTo(1L);

        Decision released = TeamRules.releaseMatchLock(lockedDecision.record(), "tok", true, NOW + 1, sessions);
        assertThat(released.changed()).isTrue();
        assertThat(released.leaderOfflineTransferred()).isTrue();
        assertThat(released.record().getLeaderId()).isEqualTo(2L);
        assertThat(released.reason()).as("自身操作优先于惰性转让").isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED);
        assertThat(released.actor()).isZero();
    }

    @Test
    void 释放锁_记录或token缺失返回零值() {
        TeamRecord locked = withLock(newRecord(1), NOW + 10);
        assertThat(TeamRules.releaseMatchLock(null, "lock-token", true, NOW, null)).isEqualTo(Decision.unchanged());
        assertThat(TeamRules.releaseMatchLock(locked, "", true, NOW, null)).isEqualTo(Decision.unchanged());
        assertThat(TeamRules.releaseMatchLock(locked, null, true, NOW, null)).isEqualTo(Decision.unchanged());
        assertThat(TeamRules.releaseMatchLock(locked, "lock-token", true, NOW, null).changed()).isTrue();
    }
}
