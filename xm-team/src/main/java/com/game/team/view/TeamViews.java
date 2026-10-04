package com.game.team.view;

import com.game.proto.team.TeamApplicationView;
import com.game.proto.team.TeamIncomingInviteView;
import com.game.proto.team.TeamMatchState;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamOutgoingInviteView;
import com.game.proto.team.TeamView;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.rules.TeamLimits;
import com.game.team.rules.TeamRules;
import com.game.team.store.CommitResult;
import com.game.team.store.IndexEntry;
import com.game.team.store.MembersSnapshot;
import com.game.team.store.Snapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 视图构建（基线 go/match/internal/team/view.go，team-spec §4.1）。只放纯函数：输入「记录 + 版本 + 接收者 epoch + Redis 时钟 + 展示缓存」，
 * 输出下发给客户端的视图，不做 I/O。可在任意线程调用。
 *
 * <p><b>同源前提</b>（客户端按 {@code (membership_epoch, version)} 排序的正确性全靠它）：一份视图里的 team_id / version / epoch / 记录
 * 必须出自同一次原子操作。所以只有三个入口，各自对应一种原子来源：
 * <ul>
 *   <li>{@link #viewFromSnapshot}：S_READ（调用者自己的索引 + 记录）；</li>
 *   <li>{@link #viewFromCommit}：S_COMMIT（提交后每人的 tid / epoch）；</li>
 *   <li>{@link #viewFromMembers}：S_READ_MEMBERS（记录 + 每名成员的 tid / epoch）。</li>
 * </ul>
 * 来源里接收者的 tid 与视图 team_id 对不上时一律返回空，调用方不推 / 改用自由读，绝不把「某时刻的记录」和「另一时刻的 epoch」拼在一起。
 *
 * <p>id、时间、版本都按无符号比较（基线 uint64），join_seq 按无符号 32 位。
 */
public final class TeamViews {

    /** 申请：applied_at_ms 升序、同值按 player_id 升序（view.go:65-70）。 */
    private static final Comparator<TeamApplicationRecord> APPLICATION_ORDER = (a, b) -> {
        int byTime = Long.compareUnsigned(a.getAppliedAtMs(), b.getAppliedAtMs());
        return byTime != 0 ? byTime : Long.compareUnsigned(a.getPlayerId(), b.getPlayerId());
    };

    /** 已发出邀请：invited_at_ms 升序、同值按 invitee_id 升序（view.go:79-84）。 */
    private static final Comparator<TeamInviteRecord> INVITE_ORDER = (a, b) -> {
        int byTime = Long.compareUnsigned(a.getInvitedAtMs(), b.getInvitedAtMs());
        return byTime != 0 ? byTime : Long.compareUnsigned(a.getInviteeId(), b.getInviteeId());
    };

    private TeamViews() {
    }

    /**
     * 无队伍视图（view.go:25-33）：team_id=0、version=0，只设 capacity、接收者最新 epoch 与 server_time_ms。
     * 索引缺失时 S_READ 回报 Redis nowMs 作 epoch，保证整队过期后的空视图仍大于客户端手里的旧 epoch。
     */
    public static TeamView emptyTeamView(long epoch, long nowMs) {
        return TeamView.newBuilder()
                .setCapacity(TeamLimits.CAPACITY)
                .setMembershipEpoch(epoch)
                .setServerTimeMs(nowMs)
                .build();
    }

    /**
     * 为 viewer 构建 rec 的视图（view.go:35-92）。rec 按 nowMs 过滤掉已过期的申请与邀请；申请与已发出邀请只下发给队长
     * （viewer ≠ 0 且 == leader），application_count 所有人可见。rec 为 null 或 team_id=0 时是空视图。
     */
    public static TeamView teamViewFor(long viewer, TeamRecord rec, long version, long epoch, long nowMs,
                                       Map<Long, MemberDisplay> dc) {
        if (rec == null || rec.getTeamId() == 0) {
            return emptyTeamView(epoch, nowMs);
        }
        TeamRecord live = TeamRules.pruneExpired(rec, nowMs);
        long leader = live.getLeaderId();
        TeamView.Builder view = TeamView.newBuilder()
                .setTeamId(live.getTeamId())
                .setLeaderId(leader)
                .setCapacity(TeamLimits.CAPACITY)
                .setZoneId(live.getZoneId())
                .setVersion(version)
                .setMembershipEpoch(epoch)
                .setMatchState(TeamRules.matchLockActive(live, nowMs)
                        ? TeamMatchState.TEAM_MATCH_STATE_STARTING : TeamMatchState.TEAM_MATCH_STATE_IDLE)
                .setApplicationCount(live.getApplicationsCount())
                .setServerTimeMs(nowMs);
        // join_seq 升序；客户端仍按 join_seq 字段排序，不依赖下标
        for (long pid : TeamRules.memberIds(live)) {
            TeamMemberRecord m = TeamRules.findMember(live, pid);
            view.addMembers(memberView(pid, m.getZoneId(), m.getJoinSeq(), leader, dc));
        }
        if (viewer == 0 || viewer != leader) {
            return view.build();
        }
        List<TeamApplicationRecord> apps = new ArrayList<>(live.getApplicationsList());
        apps.sort(APPLICATION_ORDER);
        for (TeamApplicationRecord a : apps) {
            view.addApplications(TeamApplicationView.newBuilder()
                    .setPlayer(memberView(a.getPlayerId(), a.getZoneId(), 0, leader, dc))
                    .setAppliedAtMs(a.getAppliedAtMs())
                    .setExpireAtMs(a.getExpireAtMs()));
        }
        List<TeamInviteRecord> invites = new ArrayList<>(live.getInvitesList());
        invites.sort(INVITE_ORDER);
        for (TeamInviteRecord inv : invites) {
            view.addPendingInvites(TeamOutgoingInviteView.newBuilder()
                    .setInvitee(memberView(inv.getInviteeId(), inv.getZoneId(), 0, leader, dc))
                    .setExpireAtMs(inv.getExpireAtMs()));
        }
        return view.build();
    }

    /**
     * 一名玩家的展示视图（view.go:94-111）：昵称、等级、职业、外观、性别、在线、战斗中取自展示缓存（缺失为零值）；
     * is_leader = pid == leader；zone_id 与 join_seq 由调用方传入。
     */
    public static TeamMemberView memberView(long playerId, int zone, int joinSeq, long leader, Map<Long, MemberDisplay> dc) {
        MemberDisplay d = dc == null ? null : dc.get(playerId);
        if (d == null) {
            d = MemberDisplay.NONE;
        }
        return TeamMemberView.newBuilder()
                .setPlayerId(playerId)
                .setName(d.name())
                .setLevel(d.level())
                .setClassId(d.classId())
                .setAppearanceId(d.appearanceId())
                .setGender(d.gender())
                .setIsLeader(playerId == leader)
                .setIsOnline(d.online())
                .setInBattle(d.inBattle())
                .setZoneId(zone)
                .setJoinSeq(joinSeq)
                .build();
    }

    /**
     * 被邀请人看到的「收到的邀请」（view.go:113-133）；rec 里没有给 invitee 的未过期邀请时返回 null。
     * 邀请人可能已离队：zone / join_seq 取不到时为 0。
     */
    public static TeamIncomingInviteView incomingInviteView(TeamRecord rec, long invitee, long nowMs,
                                                            Map<Long, MemberDisplay> dc) {
        if (rec == null) {
            return null;
        }
        TeamRecord live = TeamRules.pruneExpired(rec, nowMs);
        TeamInviteRecord inv = TeamRules.findInvite(live, invitee);
        if (inv == null) {
            return null;
        }
        long leader = live.getLeaderId();
        TeamMemberRecord inviter = TeamRules.findMember(live, inv.getInviterId());
        int inviterZone = inviter == null ? 0 : inviter.getZoneId();
        int inviterSeq = inviter == null ? 0 : inviter.getJoinSeq();
        return TeamIncomingInviteView.newBuilder()
                .setTeamId(live.getTeamId())
                .setInviter(memberView(inv.getInviterId(), inviterZone, inviterSeq, leader, dc))
                .setLeaderId(leader)
                .setMemberCount(live.getMembersCount())
                .setZoneId(live.getZoneId())
                .setExpireAtMs(inv.getExpireAtMs())
                .build();
    }

    /**
     * 视图里会出现的全部玩家（成员、申请人、被邀请人、邀请人），供一次性加载展示缓存（view.go:135-148）。
     * 去掉 0 与重复，保持首次出现顺序；rec 为 null 返回空表。
     */
    public static List<Long> rosterIds(TeamRecord rec) {
        if (rec == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(TeamRules.memberIds(rec));
        for (TeamApplicationRecord a : rec.getApplicationsList()) {
            ids.add(a.getPlayerId());
        }
        for (TeamInviteRecord inv : rec.getInvitesList()) {
            ids.add(inv.getInviteeId());
            ids.add(inv.getInviterId());
        }
        return uniqueIds(ids);
    }

    /** 去掉 0 与重复，保持首次出现顺序（基线 presence.go:149-161 uniqueIds）。 */
    public static List<Long> uniqueIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id != 0) {
                seen.add(id);
            }
        }
        return List.copyOf(seen);
    }

    // ================================================================ 三个同源入口

    /**
     * S_READ 快照能否为 {@code snap.playerId} 构建同源视图（view.go:150-157）：调用者无队（索引 tid=0 或缺失），
     * 或调用者索引正指向这次读到的、存在的记录。
     */
    public static boolean snapshotViewable(Snapshot snap) {
        if (snap == null) {
            return false;
        }
        return snap.playerTeamId() == 0 || (snap.playerTeamId() == snap.teamId() && snap.record() != null);
    }

    /** 由一次 S_READ 构建调用者视图；不可同源构建时为空（view.go:159-168）。 */
    public static Optional<TeamView> viewFromSnapshot(Snapshot snap, Map<Long, MemberDisplay> dc) {
        if (!snapshotViewable(snap)) {
            return Optional.empty();
        }
        if (snap.playerTeamId() == 0) {
            return Optional.of(emptyTeamView(snap.playerEpoch(), snap.nowMs()));
        }
        return Optional.of(teamViewFor(snap.playerId(), snap.record(), snap.version(), snap.playerEpoch(), snap.nowMs(), dc));
    }

    /** 提交结果能否为 pid 构建同源视图（view.go:170-180）：pid 在 J/K/L 里，且提交后 tid 为 0 或本队（且记录未删）。 */
    public static boolean commitViewable(CommitResult c, long pid) {
        if (c == null) {
            return false;
        }
        IndexEntry idx = c.indexes().get(pid);
        if (idx == null) {
            return false;
        }
        return idx.teamId() == 0 || (idx.teamId() == c.teamId() && c.decision().record() != null);
    }

    /**
     * 由 S_COMMIT 返回的 (tid, epoch) 为 pid 构建视图（view.go:182-193）；不可同源构建时为空
     * （例如修复提交里被移出、索引已指向别队的成员）。移出者拿到的是 epoch 为提交后新值的空视图。
     */
    public static Optional<TeamView> viewFromCommit(CommitResult c, long pid, Map<Long, MemberDisplay> dc) {
        if (!commitViewable(c, pid)) {
            return Optional.empty();
        }
        IndexEntry idx = c.indexes().get(pid);
        if (idx.teamId() == 0) {
            return Optional.of(emptyTeamView(idx.epoch(), c.nowMs()));
        }
        return Optional.of(teamViewFor(pid, c.decision().record(), c.version(), idx.epoch(), c.nowMs(), dc));
    }

    /** 由 S_READ_MEMBERS 为成员 pid 构建视图；只给索引 tid == 本队的成员（view.go:195-205）。 */
    public static Optional<TeamView> viewFromMembers(MembersSnapshot m, long pid, Map<Long, MemberDisplay> dc) {
        if (m == null || m.record() == null) {
            return Optional.empty();
        }
        IndexEntry idx = m.indexes().get(pid);
        if (idx == null || idx.teamId() != m.teamId()) {
            return Optional.empty();
        }
        return Optional.of(teamViewFor(pid, m.record(), m.version(), idx.epoch(), m.nowMs(), dc));
    }

    /** 空展示缓存（不需要加载时用）。 */
    public static Map<Long, MemberDisplay> noDisplay() {
        return Collections.emptyMap();
    }
}
