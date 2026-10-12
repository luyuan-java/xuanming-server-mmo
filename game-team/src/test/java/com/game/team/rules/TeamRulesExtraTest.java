package com.game.team.rules;

import static com.game.team.rules.RuleFixtures.NOW;
import static com.game.team.rules.RuleFixtures.TID;
import static com.game.team.rules.RuleFixtures.ZONE;
import static com.game.team.rules.RuleFixtures.apply;
import static com.game.team.rules.RuleFixtures.newRecord;
import static com.game.team.rules.RuleFixtures.withApplication;
import static com.game.team.rules.RuleFixtures.withInvite;
import static com.game.team.rules.RuleFixtures.withLock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Java 增项（team-spec §10.1）：无符号 id 顺序、finish 的 Reason / Actor 优先级、「业务拒绝丢掉本轮清理与惰性转让」、
 * 未知操作种类，以及对规则输出集合契约（含 D25 的 {@code left ⊇ 旧 \ 新}）与可重算性的随机扫描。
 */
class TeamRulesExtraTest {

    /** 2^64-1、2^63、2^63+1：有符号比较下都是负数。 */
    private static final long MAX_U64 = -1L;
    private static final long HALF = Long.MIN_VALUE;
    private static final long HALF_PLUS_ONE = Long.MIN_VALUE + 1;

    private static TeamMemberRecord member(long pid, int seq) {
        return TeamMemberRecord.newBuilder().setPlayerId(pid).setZoneId(ZONE).setJoinedAtMs(NOW).setJoinSeq(seq).build();
    }

    // ================================================================ 无符号顺序

    @Test
    void 成员顺序按无符号比较_join_seq相同时比player_id() {
        TeamRecord rec = TeamRecord.newBuilder().setTeamId(TID).setLeaderId(MAX_U64).setZoneId(ZONE)
                .addMembers(member(MAX_U64, 1))
                .addMembers(member(HALF, 1))
                .addMembers(member(5, 1))
                .setNextJoinSeq(2)
                .build();
        assertThat(TeamRules.memberIds(rec)).containsExactly(5L, HALF, MAX_U64);
        assertThat(TeamRules.matchRoster(rec)).as("队长在前，其余照无符号顺序").containsExactly(MAX_U64, 5L, HALF);
    }

    @Test
    void join_seq按无符号比较_加入时取更大的seq() {
        int bigSeq = Integer.MIN_VALUE; // 2^31
        TeamRecord rec = TeamRecord.newBuilder().setTeamId(TID).setLeaderId(1).setZoneId(ZONE)
                .addMembers(member(1, bigSeq))
                .addMembers(member(2, 1))
                .setNextJoinSeq(2)
                .addApplications(TeamApplicationRecord.newBuilder().setPlayerId(3).setZoneId(ZONE)
                        .setAppliedAtMs(NOW).setExpireAtMs(NOW + 1))
                .build();
        assertThat(TeamRules.memberIds(rec)).as("2^31 排在 1 之后").containsExactly(2L, 1L);

        Decision d = apply(Op.handleApplication(1, 3, true), rec);
        assertThat(d.code()).isZero();
        assertThat(TeamRules.findMember(d.record(), 3).getJoinSeq())
                .as("现有成员 join_seq ≥ next_join_seq 时取其 +1（无符号）").isEqualTo(bigSeq + 1);
        assertThat(d.record().getNextJoinSeq()).isEqualTo(bigSeq + 2);
        assertThat(d.kept()).containsExactly(2L, 1L);
        assertThat(d.joined()).containsExactly(3L);
    }

    @Test
    void 选队长与淘汰都按无符号顺序() {
        // 全员离线时取 join_seq 最小，同 seq 取 player_id 无符号最小
        TeamRecord rec = TeamRecord.newBuilder().setTeamId(TID).setLeaderId(7).setZoneId(ZONE)
                .addMembers(member(7, 1))
                .addMembers(member(MAX_U64, 2))
                .addMembers(member(HALF_PLUS_ONE, 2))
                .setNextJoinSeq(3)
                .build();
        Decision d = apply(Op.leave(7).withCallerTeamId(TID), rec);
        assertThat(d.record().getLeaderId()).isEqualTo(HALF_PLUS_ONE);

        // 申请同刻时淘汰 player_id 无符号最小的；applied_at 也按无符号比较
        TeamRecord apps = newRecord(1);
        for (int i = 0; i < TeamLimits.MAX_APPLICATIONS - 1; i++) {
            apps = withApplication(apps, MAX_U64 - i, ZONE, NOW, NOW + 10);
        }
        apps = withApplication(apps, 9, ZONE, NOW, NOW + 10);
        Decision evicted = apply(Op.apply(HALF, ZONE), apps);
        assertThat(TeamRules.findApplication(evicted.record(), 9)).as("9 < 2^63 < 2^64-k").isNull();
        assertThat(TeamRules.findApplication(evicted.record(), HALF)).isNotNull();

        TeamRecord invites = newRecord(1);
        for (int i = 0; i < TeamLimits.MAX_INVITES_PER_TEAM - 1; i++) {
            invites = withInvite(invites, 100 + i, ZONE, NOW, NOW + 10);
        }
        invites = withInvite(invites, 50, ZONE, HALF, NOW + 10); // invited_at = 2^63：无符号下是最晚的
        Decision d2 = apply(Op.invite(1, 3, ZONE), invites);
        assertThat(d2.invitesRemoved()).containsExactly(100L);
        assertThat(TeamRules.findInvite(d2.record(), 50)).isNotNull();
    }

    @Test
    void 时间与人数按无符号比较() {
        // expire_at = 2^64-1 永不过期；锁截止 2^63 时 now = 2^63-1 仍有效
        TeamRecord rec = withApplication(newRecord(1), 3, ZONE, NOW, MAX_U64);
        assertThat(TeamRules.pruneExpired(rec, NOW).getApplicationsList()).hasSize(1);
        TeamRecord locked = rec.toBuilder().setMatchLockToken("t").setMatchLockExpireAtMs(HALF).build();
        assertThat(TeamRules.matchLockActive(locked, Long.MAX_VALUE)).isTrue();
        assertThat(TeamRules.matchLockActive(locked, HALF)).isFalse();
        // required 是 uint32：2^32-1 不会被当成 -1
        assertThat(TeamRules.checkMatchTeamSize(newRecord(1, 2, 3), -1)).isZero();
    }

    // ================================================================ finish 的 Reason / Actor 优先级（rules.go:761-770）

    @Test
    void 自身操作优先_同时惰性转让也带上标记() {
        Map<Long, SessionState> sessions = Map.of(1L, SessionState.ABSENT, 2L, SessionState.ONLINE);
        TeamRecord rec = withInvite(newRecord(1, 2), 8, ZONE, NOW - 1, NOW);
        Decision d = TeamRules.apply(Op.apply(3, ZONE), rec, NOW, sessions, RuleConfig.DEFAULT);
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED);
        assertThat(d.actor()).isEqualTo(3L);
        assertThat(d.leaderOfflineTransferred()).isTrue();
        assertThat(d.record().getLeaderId()).isEqualTo(2L);
        assertThat(d.invitesRemoved()).containsExactly(8L);
    }

    @Test
    void 无自身操作时_惰性转让优先于过期清理_actor为新队长() {
        Map<Long, SessionState> sessions = Map.of(1L, SessionState.ABSENT, 2L, SessionState.ONLINE);
        TeamRecord rec = withInvite(withApplication(newRecord(1, 2), 7, ZONE, NOW - 1, NOW), 8, ZONE, NOW - 1, NOW);
        Decision d = TeamRules.apply(Op.refresh(1), rec, NOW, sessions, RuleConfig.DEFAULT);
        assertThat(d.changed()).isTrue();
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_LEADER_OFFLINE_TRANSFERRED);
        assertThat(d.actor()).isEqualTo(2L);
        assertThat(d.kept()).containsExactly(1L, 2L);
    }

    @Test
    void 只有过期时_申请过期优先于邀请过期_actor为0() {
        TeamRecord both = withInvite(withApplication(newRecord(1), 7, ZONE, NOW - 1, NOW), 8, ZONE, NOW - 1, NOW);
        Decision d = apply(Op.refresh(1), both);
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED);
        assertThat(d.actor()).isZero();
        assertThat(d.invitesRemoved()).containsExactly(8L);

        Decision invitesOnly = apply(Op.refresh(1), withInvite(newRecord(1), 8, ZONE, NOW - 1, NOW));
        assertThat(invitesOnly.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED);
        assertThat(invitesOnly.actor()).isZero();
    }

    @Test
    void 幂等成功但有过期项时_以清理类reason提交_不带通知对象() {
        // 拒绝一条不存在的申请（幂等）+ 一条过期邀请：要提交清理，但不通知任何申请人
        Decision d = apply(Op.handleApplication(1, 3, false), withInvite(newRecord(1), 8, ZONE, NOW - 1, NOW));
        assertThat(d.code()).isZero();
        assertThat(d.changed()).isTrue();
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED);
        assertThat(d.actor()).isZero();
        assertThat(d.rejectedApplicant()).isZero();
        assertThat(d.invitedPlayer()).isZero();
    }

    @Test
    void 无变化时是零值决策() {
        Decision d = apply(Op.refresh(1), newRecord(1, 2));
        assertThat(d).isEqualTo(Decision.unchanged());
        assertThat(d.reason()).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED);
    }

    // ================================================================ 业务拒绝丢掉本轮清理与惰性转让（team-spec §8.1 第 3 条）

    @Test
    void 业务拒绝丢掉本轮的过期清理与惰性转让() {
        Map<Long, SessionState> sessions = Map.of(1L, SessionState.ABSENT, 2L, SessionState.ONLINE,
                3L, SessionState.ONLINE);
        TeamRecord rec = withApplication(newRecord(1, 2, 3), 9, ZONE, NOW - 1, NOW);
        // 惰性转让后队长是 2；3 踢人被拒
        Decision rejected = TeamRules.apply(Op.kick(3, 2), rec, NOW, sessions, RuleConfig.DEFAULT);
        assertThat(rejected.code()).isEqualTo(TeamTips.KICK_NOT_LEADER);
        assertThat(rejected.changed()).isFalse();
        assertThat(rejected.record()).isNull();
        assertThat(rejected.leaderOfflineTransferred()).as("被拒的请求不会触发 LEADER_OFFLINE_TRANSFERRED").isFalse();
        assertThat(rejected).isEqualTo(Decision.reject(TeamTips.KICK_NOT_LEADER, 0));

        // 同一份记录走 Refresh 才会落盘
        Decision refreshed = TeamRules.apply(Op.refresh(3), rec, NOW, sessions, RuleConfig.DEFAULT);
        assertThat(refreshed.changed()).isTrue();
        assertThat(refreshed.leaderOfflineTransferred()).isTrue();
        assertThat(refreshed.record().getApplicationsList()).isEmpty();
    }

    // ================================================================ 未知操作种类与入参容错

    @Test
    void 未知操作种类_有记录回4030_无记录回4013() {
        Op unknown = new Op(null, 1, 0, ZONE, 0, 0, 0, false);
        assertThat(apply(unknown, newRecord(1)).code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(apply(unknown, null).code()).isEqualTo(TeamTips.NO_TEAM);
    }

    @Test
    void 会话表缺项或为null都是UNKNOWN() {
        assertThat(SessionState.of(null, 1)).isEqualTo(SessionState.UNKNOWN);
        assertThat(SessionState.of(Map.of(2L, SessionState.ONLINE), 1)).isEqualTo(SessionState.UNKNOWN);
        assertThat(SessionState.of(Map.of(MAX_U64, SessionState.ABSENT), MAX_U64)).isEqualTo(SessionState.ABSENT);
    }

    @Test
    void 工厂与withCallerTeamId() {
        Op op = Op.invite(1, MAX_U64, 2).withCallerTeamId(HALF);
        assertThat(op).isEqualTo(new Op(OpKind.INVITE, 1, HALF, 0, MAX_U64, 2, 0, false));
        assertThat(Op.create(1, TID, ZONE)).isEqualTo(new Op(OpKind.CREATE, 1, 0, ZONE, 0, 0, TID, false));
        assertThat(Op.handleApplication(1, 3, true).accept()).isTrue();
        assertThat(Op.respondInvite(3, false).kind()).isEqualTo(OpKind.RESPOND_INVITE);
    }

    @Test
    void 决策里的列表不可变() {
        Decision d = apply(Op.kick(1, 2), newRecord(1, 2));
        assertThatThrownBy(() -> d.left().add(9L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> d.invitesAdded().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sameIdSet按多重集比较() {
        assertThat(TeamRules.sameIdSet(List.of(1L, 2L), List.of(2L, 1L))).isTrue();
        assertThat(TeamRules.sameIdSet(List.of(1L, 1L), List.of(1L, 2L))).isFalse();
        assertThat(TeamRules.sameIdSet(List.of(1L), List.of(1L, 1L))).isFalse();
        assertThat(TeamRules.sameIdSet(null, List.of())).isTrue();
    }

    // ================================================================ 集合契约与可重算（随机扫描，固定种子）

    private static final long[] POOL = {1, 2, 3, 4, 5, 6, 7, 8, MAX_U64, HALF, HALF_PLUS_ONE};

    private static long pick(Random r) {
        return POOL[r.nextInt(POOL.length)];
    }

    private static long expiry(Random r) {
        return NOW + new long[]{-1, 0, 1, 60_000}[r.nextInt(4)];
    }

    private static TeamRecord randomRecord(Random r) {
        List<Long> ids = new ArrayList<>();
        for (long id : POOL) {
            ids.add(id);
        }
        Collections.shuffle(ids, r);
        int n = 1 + r.nextInt(TeamLimits.CAPACITY);
        List<Long> members = ids.subList(0, n);
        List<Integer> seqs = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            seqs.add(i);
        }
        Collections.shuffle(seqs, r);
        TeamRecord.Builder b = TeamRecord.newBuilder().setTeamId(TID).setZoneId(ZONE).setCreatedAtMs(NOW - 1000)
                .setLeaderId(members.get(r.nextInt(n)))
                .setNextJoinSeq(r.nextInt(n + 2));
        for (int i = 0; i < n; i++) {
            b.addMembers(TeamMemberRecord.newBuilder().setPlayerId(members.get(i)).setZoneId(r.nextInt(4) == 0 ? 2 : ZONE)
                    .setJoinedAtMs(NOW - 1000).setJoinSeq(seqs.get(i)));
        }
        List<Long> outsiders = new ArrayList<>(ids.subList(n, ids.size()));
        for (long pid : outsiders) {
            if (r.nextBoolean()) {
                b.addApplications(TeamApplicationRecord.newBuilder().setPlayerId(pid).setZoneId(r.nextInt(4) == 0 ? 2 : ZONE)
                        .setAppliedAtMs(NOW - r.nextInt(100)).setExpireAtMs(expiry(r)));
            }
            if (r.nextBoolean()) {
                b.addInvites(TeamInviteRecord.newBuilder().setInviteeId(pid).setInviterId(b.getLeaderId())
                        .setZoneId(r.nextInt(4) == 0 ? 2 : ZONE).setInvitedAtMs(NOW - r.nextInt(100))
                        .setExpireAtMs(expiry(r)));
            }
        }
        TeamRecord rec = b.build();
        return switch (r.nextInt(4)) {
            case 0 -> withLock(rec, NOW + 1);
            case 1 -> withLock(rec, NOW);
            default -> rec;
        };
    }

    private static Op randomOp(Random r) {
        long caller = r.nextInt(8) == 0 ? 0 : pick(r);
        long target = r.nextInt(8) == 0 ? 0 : pick(r);
        int zone = new int[]{0, ZONE, ZONE, 2}[r.nextInt(4)];
        Op op = switch (r.nextInt(10)) {
            case 0 -> Op.create(caller, TID + 1, zone);
            case 1 -> Op.refresh(caller);
            case 2 -> Op.apply(caller, zone);
            case 3 -> Op.handleApplication(caller, target, r.nextBoolean());
            case 4 -> Op.invite(caller, target, zone);
            case 5 -> Op.respondInvite(caller, r.nextBoolean());
            case 6 -> Op.leave(caller);
            case 7 -> Op.kick(caller, target);
            case 8 -> Op.transferLeader(caller, target);
            default -> Op.disband(caller);
        };
        return op.withCallerTeamId(new long[]{0, TID, 99}[r.nextInt(3)]);
    }

    private static Map<Long, SessionState> randomSessions(Random r) {
        Map<Long, SessionState> s = new HashMap<>();
        for (long id : POOL) {
            int k = r.nextInt(SessionState.values().length + 1);
            if (k < SessionState.values().length) {
                s.put(id, SessionState.values()[k]);
            }
        }
        return s;
    }

    @Test
    void 随机扫描_集合契约与可重算() {
        Random r = new Random(20261004L);
        int committed = 0;
        int disbanded = 0;
        for (int i = 0; i < 20_000; i++) {
            TeamRecord rec = r.nextInt(10) == 0 ? null : randomRecord(r);
            Op op = randomOp(r);
            if (op.kind() == OpKind.CREATE && r.nextBoolean()) {
                rec = null;
            }
            Map<Long, SessionState> sessions = randomSessions(r);
            RuleConfig cfg = new RuleConfig(r.nextInt(4) == 0);
            TeamRecord before = rec == null ? null : rec.toBuilder().build();

            Decision d = TeamRules.apply(op, rec, NOW, sessions, cfg);
            String ctx = "第 " + i + " 轮 op=" + op + " rec=" + rec;
            assertThat(TeamRules.apply(op, rec, NOW, sessions, cfg)).as("同输入可重算：%s", ctx).isEqualTo(d);
            if (before != null) {
                assertThat(rec).as(ctx).isEqualTo(before);
            }
            assertContract(rec, d, ctx);
            if (d.changed()) {
                committed++;
                if (d.disbanded()) {
                    disbanded++;
                }
            }

            if (rec != null && r.nextInt(4) == 0) {
                List<Long> victims = List.of(pick(r), pick(r));
                Decision repair = TeamRules.repairRemoveMembers(rec, victims, NOW, sessions);
                assertThat(repair.joined()).isEmpty();
                assertContract(rec, repair, "修复 " + victims + " " + ctx);
            }
        }
        assertThat(committed).as("扫描要真的覆盖到提交路径").isGreaterThan(2_000);
        assertThat(disbanded).as("也要覆盖解散").isGreaterThan(50);
    }

    private static void assertContract(TeamRecord orig, Decision d, String ctx) {
        if (d.code() != TeamTips.OK || !d.changed()) {
            assertThat(d.record()).as(ctx).isNull();
            assertThat(d.joined()).as(ctx).isEmpty();
            assertThat(d.kept()).as(ctx).isEmpty();
            assertThat(d.left()).as(ctx).isEmpty();
            assertThat(d.invitesAdded()).as(ctx).isEmpty();
            assertThat(d.invitesRemoved()).as(ctx).isEmpty();
            assertThat(d.reason()).as(ctx).isEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED);
            if (d.code() != TeamTips.OK) {
                assertThat(TeamTips.isTeamCode(d.code())).as(ctx).isTrue();
            }
            return;
        }
        assertThat(d.reason()).as(ctx).isNotEqualTo(TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED);
        Set<Long> oldMembers = new HashSet<>(TeamRules.memberIds(orig));
        Set<Long> newMembers = d.record() == null ? Set.of() : new HashSet<>(TeamRules.memberIds(d.record()));

        // J ∪ K == 新成员，两两不相交、无重复
        List<Long> jk = new ArrayList<>(d.joined());
        jk.addAll(d.kept());
        assertThat(TeamRules.sameIdSet(jk, List.copyOf(newMembers))).as("J ∪ K == 新成员：%s", ctx).isTrue();
        assertThat(d.kept()).as(ctx).allMatch(oldMembers::contains);
        assertThat(d.joined()).as(ctx).noneMatch(oldMembers::contains);
        // L ⊇ 旧 \ 新（D25），且与 J / K 不相交
        Set<Long> gone = new HashSet<>(oldMembers);
        gone.removeAll(newMembers);
        assertThat(d.left()).as("L ⊇ 旧 \\ 新：%s", ctx).containsAll(gone);
        assertThat(d.left()).as(ctx).doesNotHaveDuplicates().noneMatch(newMembers::contains);
        // IR ∩ IA == ∅
        Set<Long> added = new HashSet<>();
        d.invitesAdded().forEach(a -> added.add(a.inviteeId()));
        assertThat(d.invitesRemoved()).as(ctx).doesNotHaveDuplicates().noneMatch(added::contains);

        if (d.disbanded()) {
            assertThat(d.record()).as(ctx).isNull();
            assertThat(d.joined()).as(ctx).isEmpty();
            assertThat(d.kept()).as(ctx).isEmpty();
            return;
        }
        TeamRecord rec = d.record();
        assertThat(rec).as(ctx).isNotNull();
        assertThat(rec.getMembersCount()).as(ctx).isBetween(1, TeamLimits.CAPACITY);
        assertThat(TeamRules.findMember(rec, rec.getLeaderId())).as("队长是成员：%s", ctx).isNotNull();
        List<TeamMemberRecord> sorted = new ArrayList<>(rec.getMembersList());
        sorted.sort(TeamRules.MEMBER_ORDER);
        assertThat(rec.getMembersList()).as("落盘前已排序：%s", ctx).isEqualTo(sorted);
        Set<Integer> seqs = new HashSet<>();
        rec.getMembersList().forEach(m -> seqs.add(m.getJoinSeq()));
        assertThat(seqs).as("join_seq 唯一：%s", ctx).hasSize(rec.getMembersCount());
        assertThat(rec.getApplicationsCount()).as(ctx).isLessThanOrEqualTo(TeamLimits.MAX_APPLICATIONS);
        assertThat(rec.getInvitesCount()).as(ctx).isLessThanOrEqualTo(TeamLimits.MAX_INVITES_PER_TEAM);
        for (TeamApplicationRecord a : rec.getApplicationsList()) {
            assertThat(Long.compareUnsigned(a.getExpireAtMs(), NOW)).as("无过期申请：%s", ctx).isPositive();
            assertThat(newMembers).as("申请人不是成员：%s", ctx).doesNotContain(a.getPlayerId());
        }
        for (TeamInviteRecord inv : rec.getInvitesList()) {
            assertThat(Long.compareUnsigned(inv.getExpireAtMs(), NOW)).as("无过期邀请：%s", ctx).isPositive();
            assertThat(newMembers).as("被邀请人不是成员：%s", ctx).doesNotContain(inv.getInviteeId());
        }
        for (InviteAdd a : d.invitesAdded()) {
            assertThat(TeamRules.findInvite(rec, a.inviteeId())).as("IA 都在记录里：%s", ctx).isNotNull()
                    .extracting(TeamInviteRecord::getExpireAtMs).isEqualTo(a.expireAtMs());
        }
        if (TeamRules.matchLockActive(rec, NOW)) {
            assertThat(TeamRules.sameIdSet(rec.getMatchLockRosterList(), TeamRules.memberIds(rec)))
                    .as("锁期间 roster == members：%s", ctx).isTrue();
        }
    }
}
