package com.game.team.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.team.TeamApplicationView;
import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamIncomingInviteView;
import com.game.proto.team.TeamMatchState;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamOutgoingInviteView;
import com.game.proto.team.TeamView;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.Decision;
import com.game.team.store.CommitResult;
import com.game.team.store.IndexEntry;
import com.game.team.store.MembersSnapshot;
import com.game.team.store.Snapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 视图构建的纯函数（基线 view.go，team-spec §4.1）。id 取 ≥ 2^63 的大号，排序必须按无符号。 */
class TeamViewsTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long TID = Long.MIN_VALUE + 77;
    private static final long A = Long.MIN_VALUE + 1; // 队长
    private static final long B = Long.MIN_VALUE + 2;
    private static final long C = 5;                  // 有符号比较时 C > A，无符号时 C < A
    private static final long D = Long.MIN_VALUE + 9;
    private static final long E = Long.MIN_VALUE + 10;

    private static TeamRecord record() {
        return TeamRecord.newBuilder()
                .setTeamId(TID).setLeaderId(A).setZoneId(3).setCreatedAtMs(NOW - 1000).setNextJoinSeq(3)
                .addMembers(TeamMemberRecord.newBuilder().setPlayerId(B).setZoneId(3).setJoinSeq(2))
                .addMembers(TeamMemberRecord.newBuilder().setPlayerId(A).setZoneId(3).setJoinSeq(1))
                // 申请：同一 applied_at 下按 player_id 无符号升序 → C(5) 在 D(2^63+9) 之前；过期的不出现
                .addApplications(TeamApplicationRecord.newBuilder().setPlayerId(D).setZoneId(4).setAppliedAtMs(NOW - 10)
                        .setExpireAtMs(NOW + 100))
                .addApplications(TeamApplicationRecord.newBuilder().setPlayerId(C).setZoneId(5).setAppliedAtMs(NOW - 10)
                        .setExpireAtMs(NOW + 100))
                .addApplications(TeamApplicationRecord.newBuilder().setPlayerId(E).setZoneId(5).setAppliedAtMs(NOW - 20)
                        .setExpireAtMs(NOW)) // expire == now 已过期
                .addInvites(TeamInviteRecord.newBuilder().setInviteeId(E).setInviterId(A).setZoneId(6)
                        .setInvitedAtMs(-5L).setExpireAtMs(-1L)) // invited_at ≥ 2^63：无符号排在后面
                .addInvites(TeamInviteRecord.newBuilder().setInviteeId(C).setInviterId(B).setZoneId(7)
                        .setInvitedAtMs(NOW - 5).setExpireAtMs(NOW + 50))
                .build();
    }

    private static Map<Long, MemberDisplay> display() {
        Map<Long, MemberDisplay> dc = new LinkedHashMap<>();
        dc.put(A, new MemberDisplay(true, false, 30, 2, "甲", "ap-a", 1));
        dc.put(B, new MemberDisplay(false, false, 20, 3, "乙", "", 2));
        return dc;
    }

    @Test
    void 空视图只有容量_epoch与服务器时间() {
        TeamView v = TeamViews.emptyTeamView(-3L, NOW);
        assertThat(v).isEqualTo(TeamView.newBuilder().setCapacity(5).setMembershipEpoch(-3L).setServerTimeMs(NOW).build());
        assertThat(TeamViews.teamViewFor(A, null, 9, 4, NOW, Map.of())).isEqualTo(TeamViews.emptyTeamView(4, NOW));
    }

    @Test
    void 队长视图_成员按join_seq_申请与邀请只给队长且按时间再按id无符号排序() {
        TeamView v = TeamViews.teamViewFor(A, record(), 12, 34, NOW, display());
        assertThat(v.getTeamId()).isEqualTo(TID);
        assertThat(v.getLeaderId()).isEqualTo(A);
        assertThat(v.getCapacity()).isEqualTo(5);
        assertThat(v.getZoneId()).isEqualTo(3);
        assertThat(v.getVersion()).isEqualTo(12);
        assertThat(v.getMembershipEpoch()).isEqualTo(34);
        assertThat(v.getServerTimeMs()).isEqualTo(NOW);
        assertThat(v.getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
        assertThat(v.getMembersList()).extracting(TeamMemberView::getPlayerId).containsExactly(A, B);
        TeamMemberView a = v.getMembers(0);
        assertThat(a.getIsLeader()).isTrue();
        assertThat(a.getIsOnline()).isTrue();
        assertThat(a.getName()).isEqualTo("甲");
        assertThat(a.getLevel()).isEqualTo(30);
        assertThat(a.getClassId()).isEqualTo(2);
        assertThat(a.getGender()).isEqualTo(1);
        assertThat(a.getAppearanceId()).isEqualTo("ap-a");
        assertThat(a.getJoinSeq()).isEqualTo(1);
        assertThat(a.getInBattle()).isFalse();
        assertThat(v.getMembers(1).getIsLeader()).isFalse();
        assertThat(v.getMembers(1).getJoinSeq()).isEqualTo(2);

        assertThat(v.getApplicationCount()).isEqualTo(2);
        assertThat(v.getApplicationsList()).extracting(x -> x.getPlayer().getPlayerId()).containsExactly(C, D);
        TeamApplicationView first = v.getApplications(0);
        assertThat(first.getPlayer().getZoneId()).isEqualTo(5);
        assertThat(first.getPlayer().getJoinSeq()).isZero();
        assertThat(first.getPlayer().getName()).isEmpty(); // 展示缓存缺项 → 零值
        assertThat(first.getExpireAtMs()).isEqualTo(NOW + 100);
        assertThat(v.getPendingInvitesList()).extracting(x -> x.getInvitee().getPlayerId()).containsExactly(C, E);
        TeamOutgoingInviteView inv = v.getPendingInvites(0);
        assertThat(inv.getInvitee().getZoneId()).isEqualTo(7);
        assertThat(inv.getExpireAtMs()).isEqualTo(NOW + 50);
    }

    @Test
    void 非队长看不到申请与邀请_只看到计数() {
        TeamView v = TeamViews.teamViewFor(B, record(), 12, 35, NOW, display());
        assertThat(v.getApplicationsList()).isEmpty();
        assertThat(v.getPendingInvitesList()).isEmpty();
        assertThat(v.getApplicationCount()).isEqualTo(2);
        assertThat(v.getMembershipEpoch()).isEqualTo(35);
        // viewer = 0 也不给
        assertThat(TeamViews.teamViewFor(0, record().toBuilder().setLeaderId(0).build(), 1, 1, NOW, Map.of())
                .getApplicationsList()).isEmpty();
    }

    @Test
    void 开战锁有效时为STARTING_过期或now等于截止为IDLE() {
        TeamRecord locked = record().toBuilder().setMatchLockToken("t").setMatchLockExpireAtMs(NOW + 1).build();
        assertThat(TeamViews.teamViewFor(A, locked, 1, 1, NOW, Map.of()).getMatchState())
                .isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        TeamRecord expired = locked.toBuilder().setMatchLockExpireAtMs(NOW).build();
        assertThat(TeamViews.teamViewFor(A, expired, 1, 1, NOW, Map.of()).getMatchState())
                .isEqualTo(TeamMatchState.TEAM_MATCH_STATE_IDLE);
    }

    @Test
    void 收到的邀请_邀请人已离队时zone与join_seq为0_没有未过期邀请时为null() {
        TeamIncomingInviteView in = TeamViews.incomingInviteView(record(), C, NOW, display());
        assertThat(in.getTeamId()).isEqualTo(TID);
        assertThat(in.getLeaderId()).isEqualTo(A);
        assertThat(in.getMemberCount()).isEqualTo(2);
        assertThat(in.getZoneId()).isEqualTo(3);
        assertThat(in.getExpireAtMs()).isEqualTo(NOW + 50);
        assertThat(in.getInviter().getPlayerId()).isEqualTo(B);
        assertThat(in.getInviter().getJoinSeq()).isEqualTo(2);
        assertThat(in.getInviter().getName()).isEqualTo("乙");

        TeamRecord inviterLeft = record().toBuilder().removeMembers(0).build(); // B 离队
        TeamIncomingInviteView orphan = TeamViews.incomingInviteView(inviterLeft, C, NOW, Map.of());
        assertThat(orphan.getInviter().getPlayerId()).isEqualTo(B);
        assertThat(orphan.getInviter().getZoneId()).isZero();
        assertThat(orphan.getInviter().getJoinSeq()).isZero();
        assertThat(orphan.getMemberCount()).isEqualTo(1);

        assertThat(TeamViews.incomingInviteView(record(), D, NOW, Map.of())).isNull();
        assertThat(TeamViews.incomingInviteView(record(), C, NOW + 50, Map.of())).as("expire == now 已过期").isNull();
        assertThat(TeamViews.incomingInviteView(null, C, NOW, Map.of())).isNull();
    }

    @Test
    void rosterIds_成员申请人被邀请人邀请人_去零去重保序() {
        assertThat(TeamViews.rosterIds(record())).containsExactly(A, B, D, C, E);
        assertThat(TeamViews.rosterIds(null)).isEmpty();
        assertThat(TeamViews.uniqueIds(List.of(0L, 3L, 3L, 0L, 1L))).containsExactly(3L, 1L);
    }

    @Test
    void 快照视图_只在无队或索引指向这次读到的存在记录时可同源构建() {
        Snapshot none = new Snapshot(A, 0, 99, 0, 0, null, -2, NOW);
        assertThat(TeamViews.viewFromSnapshot(none, Map.of())).contains(TeamViews.emptyTeamView(99, NOW));
        Snapshot mine = new Snapshot(A, TID, 7, TID, 3, record(), 100, NOW);
        TeamView v = TeamViews.viewFromSnapshot(mine, display()).orElseThrow();
        assertThat(v.getVersion()).isEqualTo(3);
        assertThat(v.getMembershipEpoch()).isEqualTo(7);
        assertThat(v.getApplicationsCount()).isEqualTo(2);
        Snapshot foreign = new Snapshot(A, TID + 1, 7, TID, 3, record(), 100, NOW);
        assertThat(TeamViews.snapshotViewable(foreign)).isFalse();
        assertThat(TeamViews.viewFromSnapshot(foreign, Map.of())).isEmpty();
        Snapshot missing = new Snapshot(A, TID, 7, TID, 0, null, -2, NOW);
        assertThat(TeamViews.snapshotViewable(missing)).isFalse();
        assertThat(TeamViews.snapshotViewable(null)).isFalse();
    }

    @Test
    void 提交视图_移出者是新epoch的空视图_索引在别队者不可构建() {
        Decision d = new Decision(0, 0, true, record(), List.of(), List.of(A, B), List.of(C, D), List.of(), List.of(),
                TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED, C, false, false, List.of(), 0, 0);
        Map<Long, IndexEntry> idx = new LinkedHashMap<>();
        idx.put(A, new IndexEntry(TID, 10));
        idx.put(B, new IndexEntry(TID, 11));
        idx.put(C, new IndexEntry(0, 12));
        idx.put(D, new IndexEntry(TID + 5, 13));
        CommitResult c = new CommitResult(TID, 8, d, idx, NOW);
        assertThat(TeamViews.viewFromCommit(c, B, Map.of()).orElseThrow().getMembershipEpoch()).isEqualTo(11);
        assertThat(TeamViews.viewFromCommit(c, B, Map.of()).orElseThrow().getVersion()).isEqualTo(8);
        assertThat(TeamViews.viewFromCommit(c, C, Map.of())).contains(TeamViews.emptyTeamView(12, NOW));
        assertThat(TeamViews.commitViewable(c, D)).isFalse();
        assertThat(TeamViews.commitViewable(c, E)).as("不在 J/K/L").isFalse();
        CommitResult disbanded = new CommitResult(TID, 9, new Decision(0, 0, true, null, List.of(), List.of(), List.of(A),
                List.of(), List.of(), TeamChangeReason.TEAM_CHANGE_REASON_DISBANDED, A, false, true, List.of(), 0, 0),
                Map.of(A, new IndexEntry(TID, 1)), NOW);
        assertThat(TeamViews.commitViewable(disbanded, A)).as("记录已删、索引仍指向本队").isFalse();
    }

    @Test
    void 成员读视图_只给索引tid等于本队的成员() {
        Map<Long, IndexEntry> idx = Map.of(A, new IndexEntry(TID, 21), B, new IndexEntry(0, 0));
        MembersSnapshot m = new MembersSnapshot(TID, 4, record(), NOW, idx);
        assertThat(TeamViews.viewFromMembers(m, A, Map.of()).orElseThrow().getMembershipEpoch()).isEqualTo(21);
        assertThat(TeamViews.viewFromMembers(m, B, Map.of())).isEmpty();
        assertThat(TeamViews.viewFromMembers(new MembersSnapshot(TID, 0, null, NOW, Map.of()), A, Map.of())).isEmpty();
    }
}
