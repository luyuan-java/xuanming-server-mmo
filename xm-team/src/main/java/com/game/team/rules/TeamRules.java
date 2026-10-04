package com.game.team.rules;

import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.TeamApplicationRecord;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamMemberRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.proto.TeamRecordOrBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组队规则（状态机；基线 go/match/internal/team/rules.go，team-spec §2）。
 *
 * <p>全是纯函数：输入「记录快照 + nowMs（Redis TIME，唯一时钟源）+ 成员会话状态 + 配置」，输出 {@link Decision}。
 * 不做 I/O、不读墙钟；版本冲突后存储层会重读记录再调用这里，所以必须幂等、可以重算。入参记录永不修改
 * （protobuf 消息本身不可变，工作副本一律经 {@code toBuilder()}）。可在任意线程调用。
 *
 * <p>id 一律按无符号比较（{@link Long#compareUnsigned}），join_seq 用 {@link Integer#compareUnsigned}，与基线 uint64 / uint32 的顺序一致。
 */
public final class TeamRules {

    /** 成员顺序：(join_seq, player_id) 无符号升序（基线 rules.go:912-919 sortMembers）。 */
    public static final Comparator<TeamMemberRecord> MEMBER_ORDER = (a, b) -> {
        int bySeq = Integer.compareUnsigned(a.getJoinSeq(), b.getJoinSeq());
        return bySeq != 0 ? bySeq : Long.compareUnsigned(a.getPlayerId(), b.getPlayerId());
    };

    private TeamRules() {
    }

    // ================================================================ 只读辅助（视图与存储层也用）

    /**
     * 开战锁是否有效（rules.go:184-187）：{@code token 非空 && now < expire}；{@code now == expire} 已无效。
     * 锁自然过期后字段不清，等下次加锁覆盖或释放清空（team-spec §1.2 第 8 条）。
     */
    public static boolean matchLockActive(TeamRecordOrBuilder rec, long nowMs) {
        return rec != null && !rec.getMatchLockToken().isEmpty()
                && Long.compareUnsigned(nowMs, rec.getMatchLockExpireAtMs()) < 0;
    }

    /** playerId 是否是队长（rules.go:437-439；0 永远不是）。 */
    public static boolean isLeader(TeamRecordOrBuilder rec, long playerId) {
        return rec != null && playerId != 0 && rec.getLeaderId() == playerId;
    }

    /** 在记录里找成员；没有返回 null（rules.go:189-197）。 */
    public static TeamMemberRecord findMember(TeamRecordOrBuilder rec, long playerId) {
        if (rec == null) {
            return null;
        }
        for (TeamMemberRecord m : rec.getMembersList()) {
            if (m.getPlayerId() == playerId) {
                return m;
            }
        }
        return null;
    }

    /** 找申请；没有返回 null（rules.go:940-947）。不过滤过期项，调用方先 {@link #pruneExpired}。 */
    public static TeamApplicationRecord findApplication(TeamRecordOrBuilder rec, long playerId) {
        if (rec == null) {
            return null;
        }
        for (TeamApplicationRecord a : rec.getApplicationsList()) {
            if (a.getPlayerId() == playerId) {
                return a;
            }
        }
        return null;
    }

    /** 找邀请；没有返回 null（rules.go:959-966）。不过滤过期项，调用方先 {@link #pruneExpired}。 */
    public static TeamInviteRecord findInvite(TeamRecordOrBuilder rec, long inviteeId) {
        if (rec == null) {
            return null;
        }
        for (TeamInviteRecord inv : rec.getInvitesList()) {
            if (inv.getInviteeId() == inviteeId) {
                return inv;
            }
        }
        return null;
    }

    /** 成员 player_id，按 {@link #MEMBER_ORDER}（rules.go:199-208）。rec 为 null 返回空表。 */
    public static List<Long> memberIds(TeamRecordOrBuilder rec) {
        if (rec == null) {
            return List.of();
        }
        List<TeamMemberRecord> members = new ArrayList<>(rec.getMembersList());
        members.sort(MEMBER_ORDER);
        List<Long> ids = new ArrayList<>(members.size());
        for (TeamMemberRecord m : members) {
            ids.add(m.getPlayerId());
        }
        return List.copyOf(ids);
    }

    /**
     * 两组 id 按多重集是否相等（基线 store.go:940-955 sameIdSet；加锁复核与提交前集合校验共用）。null 视为空。
     */
    public static boolean sameIdSet(List<Long> a, List<Long> b) {
        List<Long> left = a == null ? List.of() : a;
        List<Long> right = b == null ? List.of() : b;
        if (left.size() != right.size()) {
            return false;
        }
        Map<Long, Integer> counts = new HashMap<>(left.size() * 2);
        for (Long id : left) {
            counts.merge(id, 1, Integer::sum);
        }
        for (Long id : right) {
            Integer n = counts.get(id);
            if (n == null || n == 0) {
                return false;
            }
            counts.put(id, n - 1);
        }
        return true;
    }

    /**
     * 返回去掉已过期申请与邀请（{@code expire_at_ms <= nowMs}）的副本，不改入参、不转让队长，供视图层按 Redis 时钟过滤
     * （rules.go:210-219）。rec 为 null 返回 null。
     */
    public static TeamRecord pruneExpired(TeamRecord rec, long nowMs) {
        if (rec == null) {
            return null;
        }
        TeamRecord.Builder work = rec.toBuilder();
        prune(work, nowMs);
        return work.build();
    }

    // ================================================================ 名册操作

    /**
     * 对记录快照执行一次名册操作（rules.go:221-259）。
     *
     * <p>流水线：CREATE 直接建记录（不清理、不转让）；其余操作遇到 rec 为 null → 4013；克隆 → 清理过期项 → 惰性转让队长 →
     * 分派 → {@code finish}。业务拒绝会丢掉这一轮的清理和惰性转让（{@code finish} 先判码，team-spec §8.1 第 3 条）。
     * 队长判定基于惰性转让之后的记录，新队长在同一次请求里就能执行队长操作。
     *
     * @param rec      本轮 S_READ 读到的记录（CREATE 时必须为 null）；不会被修改
     * @param nowMs    本轮 S_READ 的 Redis TIME（毫秒）
     * @param sessions 成员会话状态；null 或缺项 = {@link SessionState#UNKNOWN}
     * @param cfg      规则配置；null = {@link RuleConfig#DEFAULT}
     */
    public static Decision apply(Op op, TeamRecord rec, long nowMs, Map<Long, SessionState> sessions, RuleConfig cfg) {
        if (op.kind() == OpKind.CREATE) {
            return applyCreate(op, rec, nowMs);
        }
        if (rec == null) {
            return Decision.reject(TeamTips.NO_TEAM, 0);
        }
        RuleContext c = new RuleContext(op, rec.toBuilder(), nowMs, sessions, cfg);
        // 所有操作前先清理过期项并做惰性转让队长检查（rules.go:233-235）
        c.pruneAndRecord();
        c.transferred = lazyTransferLeader(c.rec, sessions);
        switch (op.kind()) {
            case REFRESH -> {
            }
            case APPLY -> c.applyToJoin();
            case HANDLE_APPLICATION -> c.handleApplication();
            case INVITE -> c.invite();
            case RESPOND_INVITE -> c.respondInvite();
            case LEAVE -> c.leave();
            case KICK -> c.kick();
            case TRANSFER_LEADER -> c.transferLeader();
            case DISBAND -> c.disband();
            case null, default -> {
                return Decision.reject(TeamTips.INTERNAL, 0);
            }
        }
        return c.finish(rec);
    }

    /** 等价于 {@code repairRemoveMembers(rec, [playerId], nowMs, sessions)}（rules.go:261-265）。 */
    public static Decision repairRemoveMember(TeamRecord rec, long playerId, long nowMs,
                                              Map<Long, SessionState> sessions) {
        return repairRemoveMembers(rec, List.of(playerId), nowMs, sessions);
    }

    /**
     * 生成「把 playerIds 一次性从本队移除」的修复决策（rules.go:267-297）：提交脚本回报保留成员的索引已指向别的队（{@code {-2,i}}）时使用。
     *
     * <p>克隆 → 清理过期项 → 惰性转让 → 逐个移出（跳过 0 和不在记录里的；开战锁内名单同步去掉此人）。
     * 至少移出一人时 Reason = HEALED、Actor = 第一个实际被移出者。{@code left} 始终相对原记录计算，全员移出即解散。
     * 注意：都不在记录里、但同时有过期项或惰性转让时，会以清理类 reason 返回 changed（基线注释说 !changed，不完全对，team-spec §2.7）。
     */
    public static Decision repairRemoveMembers(TeamRecord rec, List<Long> playerIds, long nowMs,
                                               Map<Long, SessionState> sessions) {
        if (rec == null) {
            return Decision.reject(TeamTips.NO_TEAM, 0);
        }
        RuleContext c = new RuleContext(null, rec.toBuilder(), nowMs, sessions, null);
        c.pruneAndRecord();
        c.transferred = lazyTransferLeader(c.rec, sessions);
        long actor = 0;
        for (long pid : playerIds == null ? List.<Long>of() : playerIds) {
            if (pid == 0 || findMember(c.rec, pid) == null) {
                continue;
            }
            c.removeMember(pid);
            // 锁有效期间也可能走到这里：锁内名单同步去掉他，保持「锁期间 match_lock_roster == members」（rules.go:286-288）
            List<Long> roster = removeId(c.rec.getMatchLockRosterList(), pid);
            c.rec.clearMatchLockRoster().addAllMatchLockRoster(roster);
            if (actor == 0) {
                actor = pid;
            }
        }
        if (actor != 0) {
            c.markOp(TeamChangeReason.TEAM_CHANGE_REASON_HEALED, actor);
        }
        return c.finish(rec);
    }

    // ================================================================ 开战锁（team-spec §2.6）

    /**
     * 开战前置规则（rules.go:301-313）：rec 为 null → 4013；caller 为 0 或不是队长 → 4018；锁有效 → 4023。返回 0 表示通过。
     */
    public static int checkMatchStart(TeamRecordOrBuilder rec, long caller, long nowMs) {
        if (rec == null) {
            return TeamTips.NO_TEAM;
        }
        if (caller == 0 || rec.getLeaderId() != caller) {
            return TeamTips.NOT_LEADER;
        }
        if (matchLockActive(rec, nowMs)) {
            return TeamTips.IN_MATCH;
        }
        return TeamTips.OK;
    }

    /**
     * 副本人数规则（rules.go:315-325）。{@code required} 是副本组队人数上限（无符号；已按 {@link TeamLimits#CAPACITY} 收口，
     * 0 = 未开放组队）：0 → 4027；成员数 &gt; required → 4028；人数少于 required 允许开战。返回 0 表示通过。
     */
    public static int checkMatchTeamSize(TeamRecordOrBuilder rec, int required) {
        if (required == 0) {
            return TeamTips.DUNGEON_NOT_OPEN;
        }
        int members = rec == null ? 0 : rec.getMembersCount();
        if (Integer.compareUnsigned(members, required) > 0) {
            return TeamTips.SIZE_EXCEEDED;
        }
        return TeamTips.OK;
    }

    /** 开战名单（rules.go:327-342）：队长在前（前提是队长在成员里），其余按 {@link #MEMBER_ORDER}。 */
    public static List<Long> matchRoster(TeamRecordOrBuilder rec) {
        if (rec == null) {
            return List.of();
        }
        long leader = rec.getLeaderId();
        List<Long> ids = memberIds(rec);
        List<Long> roster = new ArrayList<>(ids.size());
        if (findMember(rec, leader) != null) {
            roster.add(leader);
        }
        for (long pid : ids) {
            if (pid != leader) {
                roster.add(pid);
            }
        }
        return List.copyOf(roster);
    }

    /**
     * 开战锁决策（rules.go:344-368）。rec 是开战这一轮 S_READ 读到的记录，存储层按它的 ver 钉死提交、不重算。
     *
     * <p>依次：{@link #checkMatchStart} → token 为空或 {@code expireAtMs <= nowMs} → 4030 → roster 与成员集合不等 → 4029
     * → 克隆、清理过期项（<b>不做</b>惰性转让）→ 写 token / expire / roster；Reason = MATCH_STARTED，Actor = caller，kept = 全员。
     */
    public static Decision lockMatch(TeamRecord rec, long caller, String token, List<Long> roster, long expireAtMs,
                                     long nowMs) {
        int code = checkMatchStart(rec, caller, nowMs);
        if (code != TeamTips.OK) {
            return Decision.reject(code, 0);
        }
        if (token == null || token.isEmpty() || Long.compareUnsigned(expireAtMs, nowMs) <= 0) {
            return Decision.reject(TeamTips.INTERNAL, 0);
        }
        if (!sameIdSet(roster, memberIds(rec))) {
            return Decision.reject(TeamTips.STATE_CHANGED, 0);
        }
        RuleContext c = new RuleContext(null, rec.toBuilder(), nowMs, null, null);
        c.pruneAndRecord();
        c.rec.setMatchLockToken(token)
                .setMatchLockExpireAtMs(expireAtMs)
                .clearMatchLockRoster()
                .addAllMatchLockRoster(roster == null ? List.of() : roster);
        c.markOp(TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED, caller);
        return c.finish(rec);
    }

    /**
     * EndMatch 清锁决策（rules.go:370-391）。rec 为 null、token 为空、token 不符、锁已无效，任一情况都返回
     * {@link Decision#unchanged()}（调用方停止，不写）。否则：清理过期项、惰性转让、清空锁的三个字段；
     * Reason = ok ? MATCH_ENDED : MATCH_FAILED，Actor = 0，kept = 全员。
     */
    public static Decision releaseMatchLock(TeamRecord rec, String token, boolean ok, long nowMs,
                                            Map<Long, SessionState> sessions) {
        if (rec == null || token == null || token.isEmpty() || !rec.getMatchLockToken().equals(token)
                || !matchLockActive(rec, nowMs)) {
            return Decision.unchanged();
        }
        RuleContext c = new RuleContext(null, rec.toBuilder(), nowMs, sessions, null);
        c.pruneAndRecord();
        c.transferred = lazyTransferLeader(c.rec, sessions);
        c.rec.clearMatchLockToken().clearMatchLockExpireAtMs().clearMatchLockRoster();
        c.markOp(ok ? TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED : TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED, 0);
        return c.finish(rec);
    }

    // ================================================================ 内部实现

    private static Decision applyCreate(Op op, TeamRecord rec, long nowMs) {
        if (rec != null || op.newTeamId() == 0 || op.caller() == 0) {
            return Decision.reject(TeamTips.INTERNAL, 0);
        }
        if (op.callerZone() == 0) {
            return Decision.reject(TeamTips.HOME_ZONE_UNKNOWN, 0);
        }
        if (op.callerTeamId() != 0) {
            return Decision.reject(TeamTips.MEMBER_IN_TEAM, 0);
        }
        TeamRecord record = TeamRecord.newBuilder()
                .setTeamId(op.newTeamId())
                .setLeaderId(op.caller())
                .setZoneId(op.callerZone())
                .setCreatedAtMs(nowMs)
                .addMembers(TeamMemberRecord.newBuilder()
                        .setPlayerId(op.caller())
                        .setZoneId(op.callerZone())
                        .setJoinedAtMs(nowMs)
                        .setJoinSeq(1))
                .setNextJoinSeq(2)
                .build();
        return new Decision(TeamTips.OK, 0, true, record, List.of(op.caller()), List.of(), List.of(), List.of(),
                List.of(), TeamChangeReason.TEAM_CHANGE_REASON_CREATED, op.caller(), false, false, List.of(), 0, 0);
    }

    /** 过期结果：申请 / 邀请是否各有过期项被清掉。 */
    private record Pruned(boolean applications, boolean invites) {
    }

    /**
     * 按 Redis 时钟删掉 {@code expire_at_ms <= nowMs} 的申请与邀请（rules.go:852-874；与邀请反查清理 ZREMRANGEBYSCORE -inf now 同口径）。
     */
    private static Pruned prune(TeamRecord.Builder rec, long nowMs) {
        List<TeamApplicationRecord> apps = new ArrayList<>(rec.getApplicationsCount());
        boolean appsExpired = false;
        for (TeamApplicationRecord a : rec.getApplicationsList()) {
            if (Long.compareUnsigned(a.getExpireAtMs(), nowMs) > 0) {
                apps.add(a);
            } else {
                appsExpired = true;
            }
        }
        if (appsExpired) {
            rec.clearApplications().addAllApplications(apps);
        }
        List<TeamInviteRecord> invites = new ArrayList<>(rec.getInvitesCount());
        boolean invitesExpired = false;
        for (TeamInviteRecord inv : rec.getInvitesList()) {
            if (Long.compareUnsigned(inv.getExpireAtMs(), nowMs) > 0) {
                invites.add(inv);
            } else {
                invitesExpired = true;
            }
        }
        if (invitesExpired) {
            rec.clearInvites().addAllInvites(invites);
        }
        return new Pruned(appsExpired, invitesExpired);
    }

    /**
     * 惰性转让队长（rules.go:876-895）：只有队长是成员、且其会话是 {@link SessionState#ABSENT} 时才触发，
     * 转给其余成员里在线且 join_seq 最小的；没有在线成员不转。UNKNOWN / PRESENT 不触发（fail-closed）。
     */
    private static boolean lazyTransferLeader(TeamRecord.Builder rec, Map<Long, SessionState> sessions) {
        long leader = rec.getLeaderId();
        if (findMember(rec, leader) == null || SessionState.of(sessions, leader) != SessionState.ABSENT) {
            return false;
        }
        List<TeamMemberRecord> others = new ArrayList<>(rec.getMembersCount());
        for (TeamMemberRecord m : rec.getMembersList()) {
            if (m.getPlayerId() != leader) {
                others.add(m);
            }
        }
        long next = pickLeader(others, sessions, true);
        if (next == 0) {
            return false;
        }
        rec.setLeaderId(next);
        return true;
    }

    /**
     * 在候选里选在线且 join_seq 最小的（rules.go:897-910）；没有在线者时 {@code onlineOnly=false} 退回 join_seq 最小的，
     * {@code onlineOnly=true} 返回 0。
     */
    private static long pickLeader(List<TeamMemberRecord> candidates, Map<Long, SessionState> sessions,
                                   boolean onlineOnly) {
        List<TeamMemberRecord> sorted = new ArrayList<>(candidates);
        sorted.sort(MEMBER_ORDER);
        for (TeamMemberRecord m : sorted) {
            if (SessionState.of(sessions, m.getPlayerId()) == SessionState.ONLINE) {
                return m.getPlayerId();
            }
        }
        if (onlineOnly || sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(0).getPlayerId();
    }

    private static void sortMembers(TeamRecord.Builder rec) {
        List<TeamMemberRecord> sorted = new ArrayList<>(rec.getMembersList());
        sorted.sort(MEMBER_ORDER);
        rec.clearMembers().addAllMembers(sorted);
    }

    /** 返回去掉 id 的副本（rules.go:921-930）。 */
    private static List<Long> removeId(List<Long> ids, long id) {
        List<Long> out = new ArrayList<>(ids.size());
        for (long v : ids) {
            if (v != id) {
                out.add(v);
            }
        }
        return out;
    }

    private static Set<Long> memberSet(TeamRecordOrBuilder rec) {
        Set<Long> set = new HashSet<>();
        for (TeamMemberRecord m : rec.getMembersList()) {
            set.add(m.getPlayerId());
        }
        return set;
    }

    private static int indexOfApplication(TeamRecord.Builder rec, long playerId) {
        for (int i = 0; i < rec.getApplicationsCount(); i++) {
            if (rec.getApplications(i).getPlayerId() == playerId) {
                return i;
            }
        }
        return -1;
    }

    private static void removeApplication(TeamRecord.Builder rec, long playerId) {
        List<TeamApplicationRecord> kept = new ArrayList<>(rec.getApplicationsCount());
        for (TeamApplicationRecord a : rec.getApplicationsList()) {
            if (a.getPlayerId() != playerId) {
                kept.add(a);
            }
        }
        rec.clearApplications().addAllApplications(kept);
    }

    private static int indexOfInvite(TeamRecord.Builder rec, long inviteeId) {
        for (int i = 0; i < rec.getInvitesCount(); i++) {
            if (rec.getInvites(i).getInviteeId() == inviteeId) {
                return i;
            }
        }
        return -1;
    }

    private static void removeInvite(TeamRecord.Builder rec, long inviteeId) {
        List<TeamInviteRecord> kept = new ArrayList<>(rec.getInvitesCount());
        for (TeamInviteRecord inv : rec.getInvitesList()) {
            if (inv.getInviteeId() != inviteeId) {
                kept.add(inv);
            }
        }
        rec.clearInvites().addAllInvites(kept);
    }

    /**
     * 超过上限时按 applied_at_ms（同刻按 player_id，均无符号）淘汰最早的（rules.go:978-1002）；
     * keep 是本次刚追加的申请人，永不淘汰。
     */
    private static void evictOldestApplications(TeamRecord.Builder rec, long keep) {
        while (rec.getApplicationsCount() > TeamLimits.MAX_APPLICATIONS) {
            int oldest = -1;
            for (int i = 0; i < rec.getApplicationsCount(); i++) {
                TeamApplicationRecord a = rec.getApplications(i);
                if (a.getPlayerId() == keep) {
                    continue;
                }
                if (oldest < 0) {
                    oldest = i;
                    continue;
                }
                TeamApplicationRecord o = rec.getApplications(oldest);
                int byTime = Long.compareUnsigned(a.getAppliedAtMs(), o.getAppliedAtMs());
                if (byTime < 0 || (byTime == 0 && Long.compareUnsigned(a.getPlayerId(), o.getPlayerId()) < 0)) {
                    oldest = i;
                }
            }
            if (oldest < 0) {
                return;
            }
            rec.removeApplications(oldest);
        }
    }

    /**
     * 超过上限时按 invited_at_ms（同刻按 invitee_id，均无符号）淘汰最早的（rules.go:1004-1028）；
     * keep 是本次刚追加的被邀请人，永不淘汰（否则 IA 写了反查索引、记录里却没有这条邀请）。
     */
    private static void evictOldestInvites(TeamRecord.Builder rec, long keep) {
        while (rec.getInvitesCount() > TeamLimits.MAX_INVITES_PER_TEAM) {
            int oldest = -1;
            for (int i = 0; i < rec.getInvitesCount(); i++) {
                TeamInviteRecord inv = rec.getInvites(i);
                if (inv.getInviteeId() == keep) {
                    continue;
                }
                if (oldest < 0) {
                    oldest = i;
                    continue;
                }
                TeamInviteRecord o = rec.getInvites(oldest);
                int byTime = Long.compareUnsigned(inv.getInvitedAtMs(), o.getInvitedAtMs());
                if (byTime < 0 || (byTime == 0 && Long.compareUnsigned(inv.getInviteeId(), o.getInviteeId()) < 0)) {
                    oldest = i;
                }
            }
            if (oldest < 0) {
                return;
            }
            rec.removeInvites(oldest);
        }
    }

    /** 一次规则执行的工作区（基线 rules.go:395-419 ruleCtx）。只在一次调用内使用，不跨线程。 */
    private static final class RuleContext {

        /** 名册操作；修复 / 开战锁路径为 null（这些路径不调用依赖 op 的方法）。 */
        private final Op op;
        /** 工作副本；解散时成员已清空。 */
        private final TeamRecord.Builder rec;
        private final long now;
        private final Map<Long, SessionState> sessions;
        private final RuleConfig cfg;

        private int code;
        private long codeParam;

        private boolean appsExpired;
        private boolean invitesExpired;
        private boolean transferred;

        private boolean opChanged;
        private TeamChangeReason reason = TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED;
        private long actor;

        private boolean disbanded;
        private final List<Long> revoked = new ArrayList<>();
        private final List<Long> extraLeft = new ArrayList<>();
        private final List<InviteAdd> added = new ArrayList<>();
        private long rejected;
        private long invited;

        RuleContext(Op op, TeamRecord.Builder rec, long now, Map<Long, SessionState> sessions, RuleConfig cfg) {
            this.op = op;
            this.rec = rec;
            this.now = now;
            this.sessions = sessions;
            this.cfg = cfg == null ? RuleConfig.DEFAULT : cfg;
        }

        void pruneAndRecord() {
            Pruned pruned = prune(rec, now);
            appsExpired = pruned.applications();
            invitesExpired = pruned.invites();
        }

        /** 只记第一个错误码（rules.go:425-429）。 */
        void fail(int failCode, long param) {
            if (code == TeamTips.OK) {
                code = failCode;
                codeParam = param;
            }
        }

        void markOp(TeamChangeReason opReason, long opActor) {
            opChanged = true;
            reason = opReason;
            actor = opActor;
        }

        boolean full() {
            return rec.getMembersCount() >= TeamLimits.CAPACITY;
        }

        boolean locked() {
            return matchLockActive(rec, now);
        }

        /** 跨区校验（rules.go:445-456）：zone 为 0 视为查不到 home zone → 4019；不允许跨区且 zone 不等 → 4020。 */
        boolean zoneCheck(int zone) {
            if (zone == 0) {
                fail(TeamTips.HOME_ZONE_UNKNOWN, 0);
                return false;
            }
            if (!cfg.allowCrossZone() && zone != rec.getZoneId()) {
                fail(TeamTips.CROSS_ZONE_DENIED, 0);
                return false;
            }
            return true;
        }

        /** 申请加入（rules.go:458-485）：不检查开战锁；被淘汰的申请人不收通知。 */
        void applyToJoin() {
            long caller = op.caller();
            if (findMember(rec, caller) != null || op.callerTeamId() != 0) {
                fail(TeamTips.MEMBER_IN_TEAM, 0);
                return;
            }
            if (!zoneCheck(op.callerZone())) {
                return;
            }
            if (full()) {
                fail(TeamTips.MEMBERS_FULL, 0);
                return;
            }
            int i = indexOfApplication(rec, caller);
            if (i >= 0) {
                // 重复申请：刷新过期时间，保留首次申请时刻，淘汰顺序不变
                rec.setApplications(i, rec.getApplications(i).toBuilder()
                        .setExpireAtMs(now + TeamLimits.APPLICATION_TTL_MS)
                        .setZoneId(op.callerZone())
                        .build());
            } else {
                rec.addApplications(TeamApplicationRecord.newBuilder()
                        .setPlayerId(caller)
                        .setZoneId(op.callerZone())
                        .setAppliedAtMs(now)
                        .setExpireAtMs(now + TeamLimits.APPLICATION_TTL_MS)
                        .build());
                evictOldestApplications(rec, caller);
            }
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED, caller);
        }

        /** 队长审批（rules.go:487-524）：拒绝不查锁；同意依次判已是成员 → 申请失效 → 锁 → 满员 → 按申请记录里的 zone 复核。 */
        void handleApplication() {
            long applicant = op.target();
            if (!isLeader(rec, op.caller())) {
                fail(TeamTips.NOT_LEADER, 0);
                return;
            }
            TeamApplicationRecord app = findApplication(rec, applicant);
            if (!op.accept()) {
                if (app == null) {
                    return; // 拒绝不存在的申请：幂等成功
                }
                removeApplication(rec, applicant);
                rejected = applicant;
                markOp(TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED, applicant);
                return;
            }
            if (findMember(rec, applicant) != null) {
                return; // 已是本队成员：幂等成功
            }
            if (app == null) {
                fail(TeamTips.APPLICATION_NOT_FOUND, 0);
                return;
            }
            if (locked()) {
                fail(TeamTips.IN_MATCH, 0);
                return;
            }
            if (full()) {
                fail(TeamTips.MEMBERS_FULL, 0);
                return;
            }
            // 按申请记录里的 zone 复核：开关中途改成 false 也拦得住
            if (!zoneCheck(app.getZoneId())) {
                return;
            }
            addMember(applicant, app.getZoneId());
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED, applicant);
        }

        /**
         * 队长邀请（rules.go:526-565）：非队长 → 0 / 自己 → 已是本队成员 → zone → 满员。不检查开战锁。
         * 刷新时也写 IA；Actor 是被邀请人。
         */
        void invite() {
            long target = op.target();
            if (!isLeader(rec, op.caller())) {
                fail(TeamTips.NOT_LEADER, 0);
                return;
            }
            if (target == 0 || target == op.caller()) {
                fail(TeamTips.PLAYER_ID, 0);
                return;
            }
            if (findMember(rec, target) != null) {
                fail(TeamTips.MEMBER_IN_TEAM, target);
                return;
            }
            if (!zoneCheck(op.targetZone())) {
                return;
            }
            if (full()) {
                fail(TeamTips.MEMBERS_FULL, 0);
                return;
            }
            long expire = now + TeamLimits.INVITE_TTL_MS;
            int i = indexOfInvite(rec, target);
            if (i >= 0) {
                rec.setInvites(i, rec.getInvites(i).toBuilder()
                        .setExpireAtMs(expire)
                        .setInviterId(op.caller())
                        .setZoneId(op.targetZone())
                        .build());
            } else {
                rec.addInvites(TeamInviteRecord.newBuilder()
                        .setInviteeId(target)
                        .setInviterId(op.caller())
                        .setZoneId(op.targetZone())
                        .setInvitedAtMs(now)
                        .setExpireAtMs(expire)
                        .build());
                evictOldestInvites(rec, target); // 被淘汰者在 finish 的差集里进入 invitesRemoved
            }
            added.add(new InviteAdd(target, expire));
            invited = target;
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED, target);
        }

        /** 应邀（rules.go:567-602）：接受依次判已是成员 → 邀请失效 → 锁 → 已在别队 → 满员 → 按邀请记录里的 zone 复核。 */
        void respondInvite() {
            long caller = op.caller();
            TeamInviteRecord inv = findInvite(rec, caller);
            if (!op.accept()) {
                if (inv == null) {
                    return; // 拒绝不存在的邀请：幂等成功
                }
                removeInvite(rec, caller);
                markOp(TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED, caller);
                return;
            }
            if (findMember(rec, caller) != null) {
                return; // 已是本队成员：幂等成功
            }
            if (inv == null) {
                fail(TeamTips.INVITE_NOT_FOUND, 0);
                return;
            }
            if (locked()) {
                fail(TeamTips.IN_MATCH, 0);
                return;
            }
            if (op.callerTeamId() != 0 && op.callerTeamId() != rec.getTeamId()) {
                fail(TeamTips.MEMBER_IN_TEAM, 0);
                return;
            }
            if (full()) {
                fail(TeamTips.MEMBERS_FULL, 0);
                return;
            }
            if (!zoneCheck(inv.getZoneId())) {
                return;
            }
            addMember(caller, inv.getZoneId());
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_JOINED, caller);
        }

        /** 离队（rules.go:604-621）。 */
        void leave() {
            long caller = op.caller();
            if (findMember(rec, caller) == null) {
                // 索引指向本队但记录里没有我（索引与记录矛盾）：提交 L=[caller] 把索引置 0，脚本只在索引仍等于本队时才写
                if (op.callerTeamId() == rec.getTeamId()) {
                    extraLeft.add(caller);
                    markOp(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_LEFT, caller);
                }
                return;
            }
            if (locked()) {
                fail(TeamTips.IN_MATCH, 0);
                return;
            }
            removeMember(caller);
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_LEFT, caller);
        }

        /** 踢人（rules.go:623-643）。 */
        void kick() {
            long target = op.target();
            if (!isLeader(rec, op.caller())) {
                fail(TeamTips.KICK_NOT_LEADER, 0);
                return;
            }
            if (target == op.caller()) {
                fail(TeamTips.KICK_SELF, 0);
                return;
            }
            if (findMember(rec, target) == null) {
                fail(TeamTips.MEMBER_NOT_IN_TEAM, target);
                return;
            }
            if (locked()) {
                fail(TeamTips.IN_MATCH, 0);
                return;
            }
            removeMember(target);
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_MEMBER_KICKED, target);
        }

        /**
         * 转让（rules.go:645-678）：顺序 4001 → 4007 → 幂等 → 4008 → 4004 → 4024 → 4023。「转给自己」有意排在「目标已是队长」之前，
         * 否则 4007 永远走不到。目标会话 UNKNOWN / PRESENT / ABSENT 都回 4024（fail-closed）。
         */
        void transferLeader() {
            long target = op.target();
            if (target == 0) {
                fail(TeamTips.PLAYER_ID, 0);
                return;
            }
            if (target == op.caller()) {
                fail(TeamTips.APPOINT_SELF, 0);
                return;
            }
            if (isLeader(rec, target)) {
                return; // 目标已是队长：幂等成功
            }
            if (!isLeader(rec, op.caller())) {
                fail(TeamTips.APPOINT_NOT_LEADER, 0);
                return;
            }
            if (findMember(rec, target) == null) {
                fail(TeamTips.MEMBER_NOT_IN_TEAM, target);
                return;
            }
            if (SessionState.of(sessions, target) != SessionState.ONLINE) {
                fail(TeamTips.MEMBER_OFFLINE, target);
                return;
            }
            if (locked()) {
                fail(TeamTips.IN_MATCH, 0);
                return;
            }
            rec.setLeaderId(target);
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_LEADER_TRANSFERRED, target);
        }

        /** 解散（rules.go:680-692）。 */
        void disband() {
            if (!isLeader(rec, op.caller())) {
                fail(TeamTips.DISBAND_NOT_LEADER, 0);
                return;
            }
            if (locked()) {
                fail(TeamTips.IN_MATCH, 0);
                return;
            }
            rec.clearMembers();
            dissolve();
            markOp(TeamChangeReason.TEAM_CHANGE_REASON_DISBANDED, op.caller());
        }

        /**
         * 加入成员并删掉此人在本队的申请与邀请（rules.go:694-715）。seq 取 next_join_seq，遍历现有成员遇到 join_seq ≥ seq 就改成
         * join_seq+1（next_join_seq 缺失或落后时自愈），最后为 0 则取 1。
         */
        void addMember(long playerId, int zone) {
            int seq = rec.getNextJoinSeq();
            for (TeamMemberRecord m : rec.getMembersList()) {
                if (Integer.compareUnsigned(m.getJoinSeq(), seq) >= 0) {
                    seq = m.getJoinSeq() + 1;
                }
            }
            if (seq == 0) {
                seq = 1;
            }
            rec.addMembers(TeamMemberRecord.newBuilder()
                    .setPlayerId(playerId)
                    .setZoneId(zone)
                    .setJoinedAtMs(now)
                    .setJoinSeq(seq)
                    .build());
            rec.setNextJoinSeq(seq + 1);
            sortMembers(rec);
            removeApplication(rec, playerId);
            removeInvite(rec, playerId);
        }

        /** 移出成员（rules.go:717-734）：没有剩余成员则解散；移出的是队长则按 pickLeader(onlineOnly=false) 选新队长。 */
        void removeMember(long playerId) {
            List<TeamMemberRecord> kept = new ArrayList<>(rec.getMembersCount());
            for (TeamMemberRecord m : rec.getMembersList()) {
                if (m.getPlayerId() != playerId) {
                    kept.add(m);
                }
            }
            rec.clearMembers().addAllMembers(kept);
            if (kept.isEmpty()) {
                dissolve();
                return;
            }
            if (rec.getLeaderId() == playerId) {
                rec.setLeaderId(pickLeader(kept, sessions, false));
            }
        }

        /** 标记解散（rules.go:736-744）：未过期邀请的被邀请人收 INVITE_REVOKED；邀请和申请全部清空。 */
        void dissolve() {
            disbanded = true;
            for (TeamInviteRecord inv : rec.getInvitesList()) {
                revoked.add(inv.getInviteeId());
            }
            rec.clearInvites();
            rec.clearApplications();
        }

        /**
         * 汇总成 {@link Decision}（rules.go:746-819）。orig 是规则执行前的原始记录（求成员 / 邀请差集用）。
         *
         * <p>Reason / Actor 优先级：自身操作 → 惰性转让（新队长）→ 申请过期（0）→ 邀请过期（0）。
         */
        Decision finish(TeamRecord orig) {
            if (code != TeamTips.OK) {
                return Decision.reject(code, codeParam);
            }
            boolean housekeeping = appsExpired || invitesExpired || transferred;
            if (!opChanged && !housekeeping) {
                return new Decision(TeamTips.OK, 0, false, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                        TeamChangeReason.TEAM_CHANGE_REASON_UNSPECIFIED, 0, transferred, false, List.of(), rejected,
                        invited);
            }
            TeamChangeReason outReason;
            long outActor = 0;
            if (opChanged) {
                outReason = reason;
                outActor = actor;
            } else if (transferred) {
                outReason = TeamChangeReason.TEAM_CHANGE_REASON_LEADER_OFFLINE_TRANSFERRED;
                outActor = rec.getLeaderId();
            } else if (appsExpired) {
                outReason = TeamChangeReason.TEAM_CHANGE_REASON_APPLICATION_CHANGED;
            } else {
                outReason = TeamChangeReason.TEAM_CHANGE_REASON_INVITE_CHANGED;
            }

            Set<Long> oldMembers = memberSet(orig);
            Set<Long> newMembers = Set.of();
            TeamRecord record = null;
            if (!disbanded) {
                sortMembers(rec);
                record = rec.build();
                newMembers = memberSet(record);
            }
            List<Long> joined = new ArrayList<>();
            List<Long> kept = new ArrayList<>();
            for (long id : memberIds(rec)) {
                if (oldMembers.contains(id)) {
                    kept.add(id);
                } else {
                    joined.add(id);
                }
            }
            List<Long> left = new ArrayList<>();
            for (long id : memberIds(orig)) {
                if (!newMembers.contains(id)) {
                    left.add(id);
                }
            }
            for (long id : extraLeft) {
                if (!newMembers.contains(id) && !oldMembers.contains(id)) {
                    left.add(id);
                }
            }

            Set<Long> addedSet = new HashSet<>();
            for (InviteAdd a : added) {
                addedSet.add(a.inviteeId());
            }
            Set<Long> newInvitees = new HashSet<>();
            for (TeamInviteRecord inv : rec.getInvitesList()) {
                newInvitees.add(inv.getInviteeId());
            }
            Set<Long> seen = new HashSet<>();
            List<Long> invitesRemoved = new ArrayList<>();
            for (TeamInviteRecord inv : orig.getInvitesList()) {
                long id = inv.getInviteeId();
                if (newInvitees.contains(id) || addedSet.contains(id) || seen.contains(id)) { // ID := ID \ IA
                    continue;
                }
                seen.add(id);
                invitesRemoved.add(id);
            }
            return new Decision(TeamTips.OK, 0, true, record, joined, kept, left, added, invitesRemoved, outReason,
                    outActor, transferred, disbanded, disbanded ? revoked : List.of(), rejected, invited);
        }
    }
}
