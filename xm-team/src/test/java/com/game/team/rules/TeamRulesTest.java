package com.game.team.rules;

import static com.game.team.rules.RuleFixtures.NOW;
import static com.game.team.rules.RuleFixtures.TID;
import static com.game.team.rules.RuleFixtures.ZONE;
import static com.game.team.rules.RuleFixtures.allOnline;
import static com.game.team.rules.RuleFixtures.apply;
import static com.game.team.rules.RuleFixtures.newRecord;
import static com.game.team.rules.RuleFixtures.withApplication;
import static com.game.team.rules.RuleFixtures.withInvite;
import static com.game.team.rules.RuleFixtures.withLock;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamRecord;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 纯函数规则的表驱动单测，逐个对应基线 go/match/internal/team/rules_test.go 的测试函数（设计稿 team-system.md §I.3 #1-#5）。
 * 不起 Redis、不读墙钟。
 */
class TeamRulesTest {

    private static final RuleConfig CFG = RuleConfig.DEFAULT;

    // ================================================================ TestApplyRejections（rules_test.go:67-159）

    /** 一行拒绝用例；toString 只给名字，作为参数化测试的显示名。 */
    record Rejection(String name, Op op, TeamRecord rec, Map<Long, SessionState> sessions, int code, long param) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Rejection reject(String name, Op op, TeamRecord rec, int code) {
        return new Rejection(name, op, rec, null, code, 0);
    }

    private static Rejection reject(String name, Op op, TeamRecord rec, int code, long param) {
        return new Rejection(name, op, rec, null, code, param);
    }

    private static Rejection reject(String name, Op op, TeamRecord rec, Map<Long, SessionState> sessions, int code,
                                    long param) {
        return new Rejection(name, op, rec, sessions, code, param);
    }

    static Stream<Rejection> rejections() {
        TeamRecord full = newRecord(1, 2, 3, 4, 5);
        return Stream.of(
                reject("create 记录已存在", Op.create(1, TID, ZONE), newRecord(9), TeamTips.INTERNAL),
                reject("create team_id 为 0", Op.create(1, 0, ZONE), null, TeamTips.INTERNAL),
                reject("create zone 未知", Op.create(1, TID, 0), null, TeamTips.HOME_ZONE_UNKNOWN),
                reject("create 已在队", Op.create(1, TID, ZONE).withCallerTeamId(42), null, TeamTips.MEMBER_IN_TEAM),
                reject("非建队记录缺失", Op.leave(1), null, TeamTips.NO_TEAM),

                reject("apply 已是成员", Op.apply(2, ZONE), newRecord(1, 2), TeamTips.MEMBER_IN_TEAM),
                reject("apply 已在别队", Op.apply(3, ZONE).withCallerTeamId(99), newRecord(1), TeamTips.MEMBER_IN_TEAM),
                reject("apply zone 未知", Op.apply(3, 0), newRecord(1), TeamTips.HOME_ZONE_UNKNOWN),
                reject("apply 跨区", Op.apply(3, 2), newRecord(1), TeamTips.CROSS_ZONE_DENIED),
                reject("apply 队满", Op.apply(6, ZONE), full, TeamTips.MEMBERS_FULL),

                reject("handle 非队长", Op.handleApplication(2, 3, true),
                        withApplication(newRecord(1, 2), 3, ZONE, NOW, NOW + 1), TeamTips.NOT_LEADER),
                reject("handle 申请不存在", Op.handleApplication(1, 3, true), newRecord(1), TeamTips.APPLICATION_NOT_FOUND),
                reject("handle 申请已过期", Op.handleApplication(1, 3, true),
                        withApplication(newRecord(1), 3, ZONE, NOW - 5, NOW), TeamTips.APPLICATION_NOT_FOUND),
                reject("handle 开战锁", Op.handleApplication(1, 3, true),
                        withLock(withApplication(newRecord(1), 3, ZONE, NOW, NOW + 1), NOW + 1), TeamTips.IN_MATCH),
                reject("handle 队满", Op.handleApplication(1, 6, true),
                        withApplication(full, 6, ZONE, NOW, NOW + 1), TeamTips.MEMBERS_FULL),
                reject("handle 申请记录跨区", Op.handleApplication(1, 3, true),
                        withApplication(newRecord(1), 3, 2, NOW, NOW + 1), TeamTips.CROSS_ZONE_DENIED),

                reject("invite 非队长", Op.invite(2, 3, ZONE), newRecord(1, 2), TeamTips.NOT_LEADER),
                reject("invite 自己", Op.invite(1, 1, ZONE), newRecord(1), TeamTips.PLAYER_ID),
                reject("invite 0", Op.invite(1, 0, ZONE), newRecord(1), TeamTips.PLAYER_ID),
                reject("invite 目标已是成员", Op.invite(1, 2, ZONE), newRecord(1, 2), TeamTips.MEMBER_IN_TEAM, 2),
                reject("invite zone 未知", Op.invite(1, 3, 0), newRecord(1), TeamTips.HOME_ZONE_UNKNOWN),
                reject("invite 跨区", Op.invite(1, 3, 2), newRecord(1), TeamTips.CROSS_ZONE_DENIED),
                reject("invite 队满", Op.invite(1, 6, ZONE), full, TeamTips.MEMBERS_FULL),

                reject("respond accept 无邀请", Op.respondInvite(3, true), newRecord(1), TeamTips.INVITE_NOT_FOUND),
                reject("respond accept 邀请已过期", Op.respondInvite(3, true),
                        withInvite(newRecord(1), 3, ZONE, NOW - 5, NOW), TeamTips.INVITE_NOT_FOUND),
                reject("respond accept 开战锁", Op.respondInvite(3, true),
                        withLock(withInvite(newRecord(1), 3, ZONE, NOW, NOW + 1), NOW + 1), TeamTips.IN_MATCH),
                reject("respond accept 已在别队", Op.respondInvite(3, true).withCallerTeamId(99),
                        withInvite(newRecord(1), 3, ZONE, NOW, NOW + 1), TeamTips.MEMBER_IN_TEAM),
                reject("respond accept 队满", Op.respondInvite(6, true),
                        withInvite(full, 6, ZONE, NOW, NOW + 1), TeamTips.MEMBERS_FULL),
                reject("respond accept 邀请记录跨区", Op.respondInvite(3, true),
                        withInvite(newRecord(1), 3, 2, NOW, NOW + 1), TeamTips.CROSS_ZONE_DENIED),

                reject("leave 开战锁", Op.leave(2).withCallerTeamId(TID), withLock(newRecord(1, 2), NOW + 1),
                        TeamTips.IN_MATCH),

                reject("kick 非队长", Op.kick(2, 3), newRecord(1, 2, 3), TeamTips.KICK_NOT_LEADER),
                reject("kick 自己", Op.kick(1, 1), newRecord(1, 2), TeamTips.KICK_SELF),
                reject("kick 目标不在队", Op.kick(1, 9), newRecord(1, 2), TeamTips.MEMBER_NOT_IN_TEAM, 9),
                reject("kick 开战锁", Op.kick(1, 2), withLock(newRecord(1, 2), NOW + 1), TeamTips.IN_MATCH),

                reject("transfer 目标 0", Op.transferLeader(1, 0), newRecord(1, 2), TeamTips.PLAYER_ID),
                reject("transfer 给自己", Op.transferLeader(1, 1), newRecord(1, 2), TeamTips.APPOINT_SELF),
                reject("transfer 非队长", Op.transferLeader(2, 3), newRecord(1, 2, 3), allOnline(1, 2, 3),
                        TeamTips.APPOINT_NOT_LEADER, 0),
                reject("transfer 目标不在队", Op.transferLeader(1, 9), newRecord(1, 2), TeamTips.MEMBER_NOT_IN_TEAM, 9),
                reject("transfer 目标断线中", Op.transferLeader(1, 2), newRecord(1, 2),
                        Map.of(1L, SessionState.ONLINE, 2L, SessionState.PRESENT), TeamTips.MEMBER_OFFLINE, 2),
                reject("transfer 目标会话未知", Op.transferLeader(1, 2), newRecord(1, 2), TeamTips.MEMBER_OFFLINE, 2),
                reject("transfer 开战锁", Op.transferLeader(1, 2), withLock(newRecord(1, 2), NOW + 1), allOnline(1, 2),
                        TeamTips.IN_MATCH, 0),

                reject("disband 非队长", Op.disband(2), newRecord(1, 2), TeamTips.DISBAND_NOT_LEADER),
                reject("disband 开战锁", Op.disband(1), withLock(newRecord(1, 2), NOW + 1), TeamTips.IN_MATCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    void 每条拒绝分支的码与参数_不提交_不改入参(Rejection tc) {
        TeamRecord before = tc.rec() == null ? null : tc.rec().toBuilder().build();
        Decision d = TeamRules.apply(tc.op(), tc.rec(), NOW, tc.sessions(), CFG);
        assertThat(d.code()).isEqualTo(tc.code());
        assertThat(d.param()).isEqualTo(tc.param());
        assertThat(d.changed()).as("拒绝不能要求提交").isFalse();
        assertThat(d.record()).isNull();
        if (before != null) {
            assertThat(tc.rec()).as("规则不得修改入参记录").isEqualTo(before);
        }
    }

    // ================================================================ 锁边界（rules_test.go:161-175）

    @Test
    void 开战锁边界_now等于expire视为已过期() {
        TeamRecord rec = withLock(newRecord(1), NOW + 10);
        assertThat(TeamRules.matchLockActive(rec, NOW + 9)).isTrue();
        assertThat(TeamRules.matchLockActive(rec, NOW + 10)).as("now == expire 视为已过期").isFalse();
        assertThat(TeamRules.matchLockActive(rec.toBuilder().setMatchLockToken("").build(), NOW)).isFalse();
        assertThat(TeamRules.matchLockActive(null, NOW)).isFalse();
    }

    @Test
    void 锁过期后名单变更照常放行() {
        Decision d = apply(Op.kick(1, 2), withLock(newRecord(1, 2), NOW));
        assertThat(d.code()).isZero();
        assertThat(d.left()).containsExactly(2L);
    }

    // ================================================================ 建队（rules_test.go:177-190）

    @Test
    void 建队生成记录() {
        Decision d = apply(Op.create(11, TID, 3), null);
        assertThat(d.code()).isZero();
        assertThat(d.changed()).isTrue();
        assertThat(d.joined()).containsExactly(11L);
        assertThat(d.kept()).isEmpty();
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_CREATED);
        assertThat(d.record().getTeamId()).isEqualTo(TID);
        assertThat(d.record().getLeaderId()).isEqualTo(11L);
        assertThat(d.record().getZoneId()).isEqualTo(3);
        assertThat(d.record().getCreatedAtMs()).isEqualTo(NOW);
        assertThat(d.record().getMembers(0).getJoinSeq()).isEqualTo(1);
        assertThat(d.record().getNextJoinSeq()).isEqualTo(2);
    }

    // ================================================================ #2 跨区开关（rules_test.go:192-219）

    @Test
    void 跨区开关_中途改false时审批与接受被拦_已有跨区成员保留() {
        RuleConfig allow = new RuleConfig(true);
        RuleConfig deny = RuleConfig.DEFAULT;

        Decision d = TeamRules.apply(Op.apply(3, 2), newRecord(1), NOW, null, allow);
        assertThat(d.code()).isZero();
        assertThat(d.record().getApplications(0).getZoneId()).as("申请记录存申请人 zone").isEqualTo(2);

        TeamRecord pending = d.record();
        d = TeamRules.apply(Op.handleApplication(1, 3, true), pending, NOW, null, deny);
        assertThat(d.code()).as("开关改 false 后按申请记录里的 zone 复核").isEqualTo(TeamTips.CROSS_ZONE_DENIED);

        d = TeamRules.apply(Op.handleApplication(1, 3, true), pending, NOW, null, allow);
        assertThat(d.code()).isZero();
        assertThat(TeamRules.findMember(d.record(), 3).getZoneId()).as("成员 zone 写进记录").isEqualTo(2);

        d = TeamRules.apply(Op.invite(1, 4, 2), newRecord(1), NOW, null, allow);
        assertThat(d.code()).isZero();
        TeamRecord invited = d.record();
        assertThat(TeamRules.apply(Op.respondInvite(4, true), invited, NOW, null, deny).code())
                .isEqualTo(TeamTips.CROSS_ZONE_DENIED);
        assertThat(TeamRules.apply(Op.respondInvite(4, true), invited, NOW, null, allow).code()).isZero();

        // 已有跨区成员保留，只拦新增
        TeamRecord mixed = newRecord(1, 2);
        mixed = mixed.toBuilder().setMembers(1, mixed.getMembers(1).toBuilder().setZoneId(2)).build();
        d = TeamRules.apply(Op.kick(1, 2), mixed, NOW, null, deny);
        assertThat(d.code()).isZero();
    }

    // ================================================================ #3 队长交接（rules_test.go:221-283）

    @Nested
    class 队长交接 {

        @Test
        void 队长离队转给在线且join_seq最小的() {
            Map<Long, SessionState> sessions = Map.of(2L, SessionState.PRESENT, 3L, SessionState.ONLINE,
                    4L, SessionState.ONLINE);
            Decision d = TeamRules.apply(Op.leave(1).withCallerTeamId(TID), newRecord(1, 2, 3, 4), NOW, sessions, CFG);
            assertThat(d.code()).isZero();
            assertThat(d.record().getLeaderId()).isEqualTo(3L);
            assertThat(d.left()).containsExactly(1L);
            assertThat(d.kept()).containsExactly(2L, 3L, 4L);
            assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_LEFT);
        }

        @Test
        void 全员离线时取join_seq最小的() {
            Decision d = apply(Op.leave(1).withCallerTeamId(TID), newRecord(1, 3, 2));
            assertThat(d.record().getLeaderId()).as("按 join_seq 而非 player_id").isEqualTo(3L);
        }

        @Test
        void 非队长离队不换队长() {
            Decision d = TeamRules.apply(Op.leave(2).withCallerTeamId(TID), newRecord(1, 2), NOW, allOnline(1, 2), CFG);
            assertThat(d.record().getLeaderId()).isEqualTo(1L);
        }

        @Test
        void 最后一人离队解散() {
            TeamRecord rec = withInvite(withInvite(newRecord(1), 7, ZONE, NOW, NOW + 5), 8, ZONE, NOW - 9, NOW);
            rec = withApplication(rec, 9, ZONE, NOW, NOW + 5);
            Decision d = apply(Op.leave(1).withCallerTeamId(TID), rec);
            assertThat(d.code()).isZero();
            assertThat(d.changed()).isTrue();
            assertThat(d.disbanded()).isTrue();
            assertThat(d.record()).isNull();
            assertThat(d.left()).containsExactly(1L);
            assertThat(d.joined()).isEmpty();
            assertThat(d.kept()).isEmpty();
            assertThat(d.invitesRemoved()).as("全部邀请（含过期）反查项都要删").containsExactlyInAnyOrder(7L, 8L);
            assertThat(d.revokedInvitees()).as("只通知未过期邀请的被邀请人").containsExactly(7L);
        }

        @Test
        void 惰性转让_队长会话不存在() {
            Map<Long, SessionState> sessions = Map.of(1L, SessionState.ABSENT, 2L, SessionState.PRESENT,
                    3L, SessionState.ONLINE);
            Decision d = TeamRules.apply(Op.refresh(2), newRecord(1, 2, 3), NOW, sessions, CFG);
            assertThat(d.code()).isZero();
            assertThat(d.changed()).isTrue();
            assertThat(d.leaderOfflineTransferred()).isTrue();
            assertThat(d.record().getLeaderId()).isEqualTo(3L);
            assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_LEADER_OFFLINE_TRANSFERRED);
            assertThat(d.kept()).containsExactly(1L, 2L, 3L);
        }

        @Test
        void 惰性转让_没有在线成员不转() {
            Decision d = TeamRules.apply(Op.refresh(2), newRecord(1, 2), NOW,
                    Map.of(1L, SessionState.ABSENT, 2L, SessionState.PRESENT), CFG);
            assertThat(d.changed()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = SessionState.class, names = {"PRESENT", "UNKNOWN", "ONLINE"})
        void 惰性转让_断线宽限或读失败都不转(SessionState state) {
            Decision d = TeamRules.apply(Op.refresh(2), newRecord(1, 2), NOW,
                    Map.of(1L, state, 2L, SessionState.ONLINE), CFG);
            assertThat(d.changed()).as("state=%s", state).isFalse();
        }

        @Test
        void 惰性转让后新队长可直接执行队长操作() {
            Map<Long, SessionState> sessions = Map.of(1L, SessionState.ABSENT, 2L, SessionState.ONLINE,
                    3L, SessionState.ONLINE);
            Decision d = TeamRules.apply(Op.kick(2, 3), newRecord(1, 2, 3), NOW, sessions, CFG);
            assertThat(d.code()).isZero();
            assertThat(d.leaderOfflineTransferred()).isTrue();
            assertThat(d.record().getLeaderId()).isEqualTo(2L);
            assertThat(d.left()).containsExactly(3L);
            assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED);
        }
    }

    // ================================================================ #4 FIFO 淘汰、刷新、过期（rules_test.go:285-372）

    @Test
    void 申请_FIFO淘汰_重复刷新_过期清理() {
        TeamRecord rec = newRecord(1);
        for (int i = 0; i < TeamLimits.MAX_APPLICATIONS; i++) {
            rec = withApplication(rec, 100 + i, ZONE, NOW - (100 - i), NOW + TeamLimits.APPLICATION_TTL_MS);
        }
        Decision d = apply(Op.apply(200, ZONE), rec);
        assertThat(d.code()).isZero();
        assertThat(d.record().getApplicationsList()).hasSize(TeamLimits.MAX_APPLICATIONS);
        assertThat(TeamRules.findApplication(d.record(), 100)).as("最早一条被淘汰").isNull();
        assertThat(TeamRules.findApplication(d.record(), 200)).isNotNull();
        assertThat(d.kept()).containsExactly(1L);
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED);

        // 重复申请：刷新 expire，保留 applied_at
        long later = NOW + 30_000;
        Decision d2 = TeamRules.apply(Op.apply(200, ZONE), d.record(), later, null, CFG);
        assertThat(d2.code()).isZero();
        var app = TeamRules.findApplication(d2.record(), 200);
        assertThat(app.getAppliedAtMs()).isEqualTo(NOW);
        assertThat(app.getExpireAtMs()).isEqualTo(later + TeamLimits.APPLICATION_TTL_MS);
        assertThat(d2.record().getApplicationsList()).hasSize(TeamLimits.MAX_APPLICATIONS);

        // 同一毫秒内挤满：刚加的不能被挤掉
        TeamRecord same = newRecord(1);
        for (int i = 0; i < TeamLimits.MAX_APPLICATIONS; i++) {
            same = withApplication(same, 500 + i, ZONE, NOW, NOW + 1);
        }
        Decision d3 = apply(Op.apply(3, ZONE), same);
        assertThat(TeamRules.findApplication(d3.record(), 3)).isNotNull();
        assertThat(TeamRules.findApplication(d3.record(), 500)).isNull();

        // 过期清理：expire <= now 即过期，GetMyTeam 发现后要求提交
        TeamRecord exp = withApplication(withApplication(newRecord(1), 7, ZONE, NOW - 10, NOW), 8, ZONE, NOW - 10,
                NOW + 1);
        Decision d4 = apply(Op.refresh(1), exp);
        assertThat(d4.changed()).isTrue();
        assertThat(TeamRules.findApplication(d4.record(), 7)).isNull();
        assertThat(TeamRules.findApplication(d4.record(), 8)).isNotNull();
        assertThat(d4.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED);
        assertThat(apply(Op.refresh(1), d4.record()).changed()).as("清理完再读不再变化").isFalse();
    }

    @Test
    void 邀请_FIFO淘汰_重复刷新_过期清理_ID除去IA() {
        TeamRecord rec = newRecord(1);
        for (int i = 0; i < TeamLimits.MAX_INVITES_PER_TEAM; i++) {
            rec = withInvite(rec, 100 + i, ZONE, NOW - (100 - i), NOW + TeamLimits.INVITE_TTL_MS);
        }
        Decision d = apply(Op.invite(1, 200, ZONE), rec);
        assertThat(d.code()).isZero();
        assertThat(d.record().getInvitesList()).hasSize(TeamLimits.MAX_INVITES_PER_TEAM);
        assertThat(TeamRules.findInvite(d.record(), 100)).isNull();
        assertThat(d.invitesAdded()).containsExactly(new InviteAdd(200, NOW + TeamLimits.INVITE_TTL_MS));
        assertThat(d.invitesRemoved()).as("被淘汰者进入 ID").containsExactly(100L);
        assertThat(d.invitedPlayer()).isEqualTo(200L);
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED);

        // 重复邀请：刷新 expire，仍写 IA，不进 ID
        long later = NOW + 10_000;
        Decision d2 = TeamRules.apply(Op.invite(1, 200, ZONE), d.record(), later, null, CFG);
        assertThat(d2.code()).isZero();
        assertThat(TeamRules.findInvite(d2.record(), 200).getExpireAtMs()).isEqualTo(later + TeamLimits.INVITE_TTL_MS);
        assertThat(d2.invitesAdded()).containsExactly(new InviteAdd(200, later + TeamLimits.INVITE_TTL_MS));
        assertThat(d2.invitesRemoved()).isEmpty();

        // 过期后重邀同一人：清理与重邀重叠，ID := ID \ IA
        TeamRecord expired = withInvite(withInvite(newRecord(1), 7, ZONE, NOW - 100, NOW), 8, ZONE, NOW - 100, NOW);
        Decision d3 = apply(Op.invite(1, 7, ZONE), expired);
        assertThat(d3.code()).isZero();
        assertThat(d3.invitesRemoved()).containsExactly(8L);
        assertThat(d3.invitesAdded().get(0).inviteeId()).isEqualTo(7L);
        assertThat(TeamRules.findInvite(d3.record(), 7)).isNotNull();
        assertThat(TeamRules.findInvite(d3.record(), 8)).isNull();

        // 同一毫秒挤满：刚加的不能被挤掉（否则 IA 写了索引、记录里却没有）
        TeamRecord same = newRecord(1);
        for (int i = 0; i < TeamLimits.MAX_INVITES_PER_TEAM; i++) {
            same = withInvite(same, 500 + i, ZONE, NOW, NOW + 1);
        }
        Decision d4 = apply(Op.invite(1, 3, ZONE), same);
        assertThat(TeamRules.findInvite(d4.record(), 3)).isNotNull();
        assertThat(d4.invitesRemoved()).containsExactly(500L);

        // 只有邀请过期时 Refresh 也要提交并删反查项
        Decision d5 = apply(Op.refresh(1), withInvite(newRecord(1), 9, ZONE, NOW - 1, NOW));
        assertThat(d5.changed()).isTrue();
        assertThat(d5.invitesRemoved()).containsExactly(9L);
        assertThat(d5.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED);
    }

    // ================================================================ #5 重试安全表（rules_test.go:374-470）

    @Nested
    class 重试安全 {

        /** 第二次调用吃第一次的结果记录。 */
        private Decision second(Op op, Decision first, Map<Long, SessionState> sessions) {
            assertThat(first.code()).isZero();
            assertThat(first.changed()).isTrue();
            return TeamRules.apply(op, first.record(), NOW, sessions, CFG);
        }

        @Test
        void CreateTeam已在队() {
            Decision d = apply(Op.create(1, TID + 1, ZONE).withCallerTeamId(TID), null);
            assertThat(d.code()).isEqualTo(TeamTips.MEMBER_IN_TEAM);
        }

        @Test
        void ApplyJoinTeam刷新并成功() {
            Op op = Op.apply(3, ZONE);
            Decision d = second(op, apply(op, newRecord(1)), null);
            assertThat(d.code()).isZero();
            assertThat(d.record().getApplicationsList()).hasSize(1);
        }

        @Test
        void InviteToTeam刷新并成功() {
            Op op = Op.invite(1, 3, ZONE);
            Decision d = second(op, apply(op, newRecord(1)), null);
            assertThat(d.code()).isZero();
            assertThat(d.record().getInvitesList()).hasSize(1);
            assertThat(d.invitesAdded()).hasSize(1);
        }

        @Test
        void HandleApplication同意重放() {
            Op op = Op.handleApplication(1, 3, true);
            Decision first = apply(op, withApplication(newRecord(1), 3, ZONE, NOW, NOW + 1));
            assertThat(first.joined()).containsExactly(3L);
            assertThat(first.kept()).containsExactly(1L);
            assertThat(TeamRules.findMember(first.record(), 3).getJoinSeq()).isEqualTo(2);
            assertThat(first.record().getNextJoinSeq()).isEqualTo(3);
            assertThat(first.record().getApplicationsList()).as("加入后删申请").isEmpty();
            Decision d = second(op, first, null);
            assertThat(d.code()).isZero();
            assertThat(d.changed()).isFalse();
        }

        @Test
        void HandleApplication拒绝重放() {
            Op op = Op.handleApplication(1, 3, false);
            Decision first = apply(op, withApplication(newRecord(1), 3, ZONE, NOW, NOW + 1));
            assertThat(first.rejectedApplicant()).isEqualTo(3L);
            Decision d = second(op, first, null);
            assertThat(d.code()).isZero();
            assertThat(d.changed()).isFalse();
            assertThat(d.rejectedApplicant()).as("重放不再通知申请人").isZero();
        }

        @Test
        void RespondInvite接受重放() {
            Op op = Op.respondInvite(3, true);
            Decision first = apply(op, withInvite(newRecord(1), 3, ZONE, NOW, NOW + 1));
            assertThat(first.joined()).containsExactly(3L);
            assertThat(first.invitesRemoved()).as("接受后删邀请反查项").containsExactly(3L);
            assertThat(first.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED);
            Decision d = second(op.withCallerTeamId(TID), first, null);
            assertThat(d.code()).isZero();
            assertThat(d.changed()).isFalse();
        }

        @Test
        void RespondInvite拒绝重放() {
            Op op = Op.respondInvite(3, false);
            Decision first = apply(op, withInvite(newRecord(1), 3, ZONE, NOW, NOW + 1));
            assertThat(first.invitesRemoved()).containsExactly(3L);
            assertThat(first.kept()).containsExactly(1L);
            Decision d = second(op, first, null);
            assertThat(d.code()).isZero();
            assertThat(d.changed()).isFalse();
        }

        @Test
        void LeaveTeam索引仍指向本队但记录里没有我() {
            Decision d = apply(Op.leave(9).withCallerTeamId(TID), newRecord(1, 2));
            assertThat(d.code()).isZero();
            assertThat(d.changed()).as("提交 L=[caller] 修复索引").isTrue();
            assertThat(d.left()).containsExactly(9L);
            assertThat(d.kept()).containsExactly(1L, 2L);
            d = apply(Op.leave(9), newRecord(1, 2));
            assertThat(d.changed()).as("索引不指向本队时什么都不写").isFalse();
        }

        @Test
        void TransferLeader目标已是队长() {
            Op op = Op.transferLeader(1, 2);
            Decision first = TeamRules.apply(op, newRecord(1, 2), NOW, allOnline(1, 2), CFG);
            assertThat(first.record().getLeaderId()).isEqualTo(2L);
            Decision d = second(op, first, allOnline(1, 2));
            assertThat(d.code()).isZero();
            assertThat(d.changed()).isFalse();
        }

        @Test
        void KickMember目标已不在队() {
            Op op = Op.kick(1, 2);
            Decision d = second(op, apply(op, newRecord(1, 2)), null);
            assertThat(d.code()).isEqualTo(TeamTips.MEMBER_NOT_IN_TEAM);
            assertThat(d.param()).isEqualTo(2L);
        }

        @Test
        void DisbandTeam记录已删() {
            Decision first = apply(Op.disband(1), newRecord(1, 2));
            assertThat(first.disbanded()).isTrue();
            assertThat(first.left()).containsExactlyInAnyOrder(1L, 2L);
            assertThat(apply(Op.disband(1), first.record()).code()).isEqualTo(TeamTips.NO_TEAM);
        }
    }

    // ================================================================ 加入、修复、PruneExpired（rules_test.go:472-521）

    @Test
    void 加入者同时有申请与邀请_两条都删_邀请反查项进入ID() {
        TeamRecord rec = withInvite(withApplication(newRecord(1), 3, ZONE, NOW, NOW + 5), 3, ZONE, NOW, NOW + 5);
        Decision d = apply(Op.handleApplication(1, 3, true), rec);
        assertThat(d.code()).isZero();
        assertThat(d.record().getApplicationsList()).isEmpty();
        assertThat(d.record().getInvitesList()).isEmpty();
        assertThat(d.invitesRemoved()).containsExactly(3L);
    }

    @Test
    void 修复移出成员() {
        Decision d = TeamRules.repairRemoveMember(newRecord(1, 2, 3), 1, NOW, allOnline(3));
        assertThat(d.changed()).isTrue();
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_HEALED);
        assertThat(d.actor()).isEqualTo(1L);
        assertThat(d.left()).containsExactly(1L);
        assertThat(d.kept()).containsExactly(2L, 3L);
        assertThat(d.record().getLeaderId()).as("被修复移出的是队长时照离队规则转让").isEqualTo(3L);

        d = TeamRules.repairRemoveMember(newRecord(1), 1, NOW, null);
        assertThat(d.disbanded()).isTrue();
        assertThat(d.record()).isNull();

        assertThat(TeamRules.repairRemoveMember(newRecord(1, 2), 9, NOW, null).changed()).isFalse();

        // 一次移出多人：left 相对原记录；不在记录里的 id 跳过；actor 为第一个实际移出的人
        d = TeamRules.repairRemoveMembers(newRecord(1, 2, 3, 4), List.of(9L, 3L, 1L), NOW, allOnline(2, 4));
        assertThat(d.changed()).isTrue();
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_HEALED);
        assertThat(d.actor()).isEqualTo(3L);
        assertThat(d.left()).containsExactly(1L, 3L);
        assertThat(d.kept()).containsExactly(2L, 4L);
        assertThat(d.record().getLeaderId()).isEqualTo(2L);

        d = TeamRules.repairRemoveMembers(newRecord(1, 2), List.of(2L, 1L), NOW, null);
        assertThat(d.disbanded()).as("全员移出即解散").isTrue();
        assertThat(d.record()).isNull();
        assertThat(d.left()).containsExactly(1L, 2L);
        assertThat(TeamRules.repairRemoveMembers(newRecord(1, 2), List.of(8L, 9L), NOW, null).changed()).isFalse();
    }

    @Test
    void pruneExpired不改入参() {
        TeamRecord rec = withInvite(withApplication(newRecord(1), 3, ZONE, NOW - 5, NOW), 4, ZONE, NOW, NOW + 5);
        TeamRecord before = rec.toBuilder().build();
        TeamRecord out = TeamRules.pruneExpired(rec, NOW);
        assertThat(rec).isEqualTo(before);
        assertThat(out.getApplicationsList()).isEmpty();
        assertThat(out.getInvitesList()).hasSize(1);
        assertThat(TeamRules.pruneExpired(null, NOW)).isNull();
    }
}
