package com.game.team.service;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.HomeZones;
import com.game.proto.TipInfoMessage;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.DisbandTeamRequest;
import com.game.proto.team.GetMyTeamRequest;
import com.game.proto.team.HandleApplicationRequest;
import com.game.proto.team.InviteToTeamRequest;
import com.game.proto.team.KickMemberRequest;
import com.game.proto.team.LeaveTeamRequest;
import com.game.proto.team.ListMyInvitesRequest;
import com.game.proto.team.ListMyInvitesResponse;
import com.game.proto.team.RespondInviteRequest;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamIncomingInviteView;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamView;
import com.game.proto.team.TransferLeaderRequest;
import com.game.team.match.TeamBattlePort;
import com.game.team.metrics.TeamMetrics;
import com.game.team.metrics.TeamMetrics.HealKind;
import com.game.team.presence.DisplayLoader;
import com.game.team.presence.SessionReads;
import com.game.team.proto.TeamInviteRecord;
import com.game.team.proto.TeamRecord;
import com.game.team.push.TeamPushes;
import com.game.team.rules.Op;
import com.game.team.rules.RuleConfig;
import com.game.team.rules.TeamRules;
import com.game.team.rules.TeamTips;
import com.game.team.store.Bind;
import com.game.team.store.CommitResult;
import com.game.team.store.FreeRead;
import com.game.team.store.InviteIndexEntry;
import com.game.team.store.InviteList;
import com.game.team.store.MutateResult;
import com.game.team.store.Outcome;
import com.game.team.store.Snapshot;
import com.game.team.store.TeamStore;
import com.game.team.view.MemberDisplay;
import com.game.team.view.TeamViews;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组队 RPC 编排（基线 go/match/internal/team/service.go，team-spec §3、§5.4）。
 *
 * <p>每个方法：前置校验与外部查询（只做一次）→ {@link TeamStore#mutate}（读 → 纯函数规则 → CAS 提交 → 重试 / 修复）
 * → 结局映射成 tip → 回包视图 → 异步推送（{@link TeamPushes}）。
 *
 * <p>契约：
 * <ul>
 *   <li>调用者身份由派发层从会话取得并保证非 0；请求体里没有自己的 player_id；</li>
 *   <li>方法从不抛业务异常：故障以 in-band 4030 表达，业务拒绝以对应 tip 表达（基线 service.go:26-28）；tip 的 parameters
 *       只放十进制 player_id，绝不放文字（team-spec §0.3）；</li>
 *   <li>{@code deadline} 是请求预算（缺省 3500 ms），本次请求的全部 Redis / MySQL 等待都受它约束；推送用独立预算；</li>
 *   <li>回包视图与推送视图一律同源构建（{@link TeamViews}），失败回包的视图取调用者自己的一次自由读；
 *       只有服务端自己读失败时才不带视图（客户端靠「有没有视图」区分「服务端读失败」与「你不在队」）；</li>
 *   <li>线程：所有方法都阻塞（Redis 等待、MySQL），只在 {@code team-worker} 工作线程上调用（AGENTS.md §3）；本类无可变状态，线程安全。</li>
 * </ul>
 */
public final class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    /** StartTeamMatch 整轮重来的上限（service.go:68），同时受请求预算约束。 */
    static final int MATCH_START_ROUNDS = 3;

    /** ListMyInvites 的排序：expire_at_ms 升序、同值按 team_id 升序（service.go:293-298，无符号）。 */
    private static final Comparator<TeamIncomingInviteView> INVITE_ORDER = (a, b) -> {
        int byExpire = Long.compareUnsigned(a.getExpireAtMs(), b.getExpireAtMs());
        return byExpire != 0 ? byExpire : Long.compareUnsigned(a.getTeamId(), b.getTeamId());
    };

    private final TeamStore store;
    private final SessionReads sessions;
    private final DisplayLoader display;
    private final HomeZones homeZones;
    private final LongSupplier teamIds;
    private final TeamBattlePort battle;
    private final TeamPushes pushes;
    private final TeamMetrics metrics;
    private final RuleConfig cfg;

    /**
     * @param sessions  规则用的会话四态与邀请目标的严格在线判定（{@code TeamSessions}）
     * @param display   视图展示缓存（{@code TeamDisplay}）
     * @param homeZones home zone 查询（xm-common 的 {@code PlayerHomeZones}：缺项或 0 → 4019，抛异常 → 4030）
     * @param teamIds   team_id 发号（{@code TeamIds::nextId}；抛异常或返回 0 → CreateTeam 回 4030 + 空视图）
     * @param battle    开战端口（4.3 为 {@code NoTeamBattle}）
     * @param cfg       规则配置（{@code xm.team.allow-cross-zone}）
     */
    public TeamService(TeamStore store, SessionReads sessions, DisplayLoader display, HomeZones homeZones,
                       LongSupplier teamIds, TeamBattlePort battle, TeamPushes pushes, TeamMetrics metrics, RuleConfig cfg) {
        this.store = store;
        this.sessions = sessions;
        this.display = display;
        this.homeZones = homeZones;
        this.teamIds = teamIds;
        this.battle = battle;
        this.pushes = pushes;
        this.metrics = metrics;
        this.cfg = cfg == null ? RuleConfig.DEFAULT : cfg;
    }

    // ================================================================ RPC

    /** CreateTeam（214，service.go:113-134）：已在队回 4003 + 当前视图（重放语义：客户端把 4003 且 leader 是自己当成功）。 */
    public TeamResponse createTeam(long caller, CreateTeamRequest request, Deadline deadline) {
        SelfRead self = readSelf(TeamMethods.CREATE_TEAM, caller, deadline);
        if (self.failure() != null) {
            return self.failure();
        }
        if (self.snapshot().playerTeamId() != 0) {
            return snapshotResponse(caller, TeamTips.MEMBER_IN_TEAM, 0, self.snapshot(), deadline);
        }
        Zone zone = homeZoneOf(caller, deadline);
        if (zone.code() != 0) {
            return snapshotResponse(caller, zone.code(), 0, self.snapshot(), deadline);
        }
        long teamId;
        try {
            teamId = newTeamId();
        } catch (RuntimeException e) {
            log.error("[team] CreateTeam 发号失败 player={}: {}", u(caller), e.toString());
            return snapshotResponse(caller, TeamTips.INTERNAL, 0, self.snapshot(), deadline);
        }
        return runMutate(caller, Bind.create(caller, teamId), Op.create(caller, teamId, zone.zone()),
                MutateSpec.of(TeamMethods.CREATE_TEAM), deadline).response();
    }

    /**
     * GetMyTeam（207，service.go:136-163）：自由读 + 过期清理 / 惰性转让；没变化但记录 TTL 不足 12 h 时续期；
     * {@code notify_online} 且回包视图里有队伍时把当前视图推给其他在线队员（错误回包上也会触发，照搬基线，§8.1 第 12 条）。
     */
    public TeamResponse getMyTeam(long caller, GetMyTeamRequest request, Deadline deadline) {
        SelfRead self = readSelf(TeamMethods.GET_MY_TEAM, caller, deadline);
        if (self.failure() != null) {
            return self.failure();
        }
        if (self.snapshot().record() == null) {
            return snapshotResponse(caller, TeamTips.OK, 0, self.snapshot(), deadline);
        }
        // 读操作：两次读之间索引变了（未绑定 / 记录缺失）不是错误，回调用者当时的视图
        Mutated m = runMutate(caller, Bind.caller(caller, self.snapshot().teamId()), Op.refresh(caller),
                new MutateSpec(TeamMethods.GET_MY_TEAM, true, false), deadline);
        MutateResult res = m.result();
        if (res != null && res.outcome() == Outcome.UNCHANGED && TeamStore.needsTouch(res.snapshot())) {
            try {
                store.touch(res.snapshot(), deadline);
            } catch (DependencyException e) {
                log.error("[team] 续期失败 team={}: {}", u(res.snapshot().teamId()), e.toString());
            }
        }
        TeamResponse resp = m.response();
        if (request.getNotifyOnline() && resp.hasTeam() && resp.getTeam().getTeamId() != 0) {
            List<Long> members = new ArrayList<>(resp.getTeam().getMembersCount());
            for (TeamMemberView member : resp.getTeam().getMembersList()) {
                members.add(member.getPlayerId());
            }
            pushes.publishOnline(caller, resp.getTeam().getTeamId(), members);
        }
        return resp;
    }

    /** ApplyJoinTeam（206，service.go:165-193）：申请加入目标玩家所在的队伍。目标无队回 4013（文案对申请人有误导，照搬基线，§8.1 第 16 条）。 */
    public TeamResponse applyJoinTeam(long caller, ApplyJoinTeamRequest request, Deadline deadline) {
        long target = request.getTargetPlayerId();
        if (target == 0 || target == caller) {
            return respond(caller, TeamTips.PLAYER_ID, 0, deadline);
        }
        // 先对调用者自由读：顺手自愈孤儿索引，否则规则会因 callerTeamId ≠ 0 误回 4003
        SelfRead self = readSelf(TeamMethods.APPLY_JOIN_TEAM, caller, deadline);
        if (self.failure() != null) {
            return self.failure();
        }
        if (self.snapshot().playerTeamId() != 0) {
            return snapshotResponse(caller, TeamTips.MEMBER_IN_TEAM, 0, self.snapshot(), deadline);
        }
        FreeRead other = readFree(TeamMethods.APPLY_JOIN_TEAM, target, deadline);
        if (!other.ok()) {
            return snapshotResponse(caller, readFailureCode(other), 0, self.snapshot(), deadline);
        }
        if (other.snapshot().playerTeamId() == 0) {
            return snapshotResponse(caller, TeamTips.NO_TEAM, 0, self.snapshot(), deadline);
        }
        Zone zone = homeZoneOf(caller, deadline);
        if (zone.code() != 0) {
            return snapshotResponse(caller, zone.code(), 0, self.snapshot(), deadline);
        }
        return runMutate(caller, Bind.target(caller, other.snapshot().playerTeamId()), Op.apply(caller, zone.zone()),
                MutateSpec.of(TeamMethods.APPLY_JOIN_TEAM), deadline).response();
    }

    /** HandleApplication（208，service.go:195-201）：队长同意 / 拒绝申请。服务层没有前置检查，applicant_id=0 也直接进规则。 */
    public TeamResponse handleApplication(long caller, HandleApplicationRequest request, Deadline deadline) {
        return runMutate(caller, Bind.caller(caller, request.getExpectedTeamId()),
                Op.handleApplication(caller, request.getApplicantId(), request.getApprove()),
                MutateSpec.of(TeamMethods.HANDLE_APPLICATION), deadline).response();
    }

    /**
     * InviteToTeam（201，service.go:203-231）：队长邀请在线且无队的玩家。前置检查都在绑定之前，所以调用者不在队时也可能先拿到
     * 4017 / 4003 / 4019 / 4030 而不是 4013（§8.1 第 14 条）。
     */
    public TeamResponse inviteToTeam(long caller, InviteToTeamRequest request, Deadline deadline) {
        long target = request.getTargetPlayerId();
        if (target == 0 || target == caller) {
            return respond(caller, TeamTips.PLAYER_ID, 0, deadline);
        }
        boolean online;
        try {
            online = sessions.isOnline(target, deadline);
        } catch (RuntimeException e) {
            log.error("[team] InviteToTeam 读目标会话失败 target={}: {}", u(target), e.toString());
            return respond(caller, TeamTips.INTERNAL, 0, deadline);
        }
        if (!online) {
            return respond(caller, TeamTips.TARGET_OFFLINE, 0, deadline);
        }
        FreeRead other = readFree(TeamMethods.INVITE_TO_TEAM, target, deadline);
        if (!other.ok()) {
            return respond(caller, readFailureCode(other), 0, deadline);
        }
        if (other.snapshot().playerTeamId() != 0) {
            return respond(caller, TeamTips.MEMBER_IN_TEAM, target, deadline);
        }
        Zone zone = homeZoneOf(target, deadline);
        if (zone.code() != 0) {
            return respond(caller, zone.code(), 0, deadline);
        }
        return runMutate(caller, Bind.caller(caller, request.getExpectedTeamId()), Op.invite(caller, target, zone.zone()),
                MutateSpec.of(TeamMethods.INVITE_TO_TEAM), deadline).response();
    }

    /** RespondInvite（204，service.go:233-251）：被邀请人接受 / 拒绝；team_id 本身即绑定的队伍。 */
    public TeamResponse respondInvite(long caller, RespondInviteRequest request, Deadline deadline) {
        long teamId = request.getTeamId();
        if (teamId == 0) {
            return respond(caller, TeamTips.NO_TEAM, 0, deadline);
        }
        if (request.getAccept()) {
            SelfRead self = readSelf(TeamMethods.RESPOND_INVITE, caller, deadline);
            if (self.failure() != null) {
                return self.failure();
            }
            long mine = self.snapshot().playerTeamId();
            if (mine != 0 && mine != teamId) {
                return snapshotResponse(caller, TeamTips.MEMBER_IN_TEAM, 0, self.snapshot(), deadline);
            }
        }
        return runMutate(caller, Bind.target(caller, teamId), Op.respondInvite(caller, request.getAccept()),
                new MutateSpec(TeamMethods.RESPOND_INVITE, false, true), deadline).response();
    }

    /**
     * ListMyInvites（205，service.go:253-300）：列出收到的未过期邀请；反查索引里已失效的项按 score CAS 删除（S_INVITE_PRUNE，
     * 不会误删队长刚重邀写入的新项）。这个读 RPC 会写；任一队伍读失败整体回 4030（§8.1 第 15 条）。不过滤「我已在别的队」「对方队已满」。
     */
    public ListMyInvitesResponse listMyInvites(long caller, ListMyInvitesRequest request, Deadline deadline) {
        InviteList list;
        try {
            list = store.listInvites(caller, deadline);
        } catch (DependencyException e) {
            log.error("[team] ListMyInvites 读反查索引失败 player={}: {}", u(caller), e.toString());
            return ListMyInvitesResponse.newBuilder().setErrorMessage(tipOf(TeamTips.INTERNAL, 0)).build();
        }
        record LiveInvite(TeamRecord record, long nowMs) {
        }
        List<LiveInvite> live = new ArrayList<>(list.entries().size());
        List<Long> roster = new ArrayList<>();
        for (InviteIndexEntry entry : list.entries()) {
            Snapshot snap;
            try {
                snap = store.read(caller, entry.teamId(), deadline);
            } catch (DependencyException e) {
                log.error("[team] ListMyInvites 读队伍失败 player={} team={}: {}", u(caller), u(entry.teamId()), e.toString());
                return ListMyInvitesResponse.newBuilder().setErrorMessage(tipOf(TeamTips.INTERNAL, 0)).build();
            }
            TeamInviteRecord inv = TeamRules.findInvite(TeamRules.pruneExpired(snap.record(), snap.nowMs()), caller);
            if (inv != null) {
                live.add(new LiveInvite(snap.record(), snap.nowMs()));
                roster.add(inv.getInviterId());
                continue;
            }
            // 记录已不存在，或记录里已没有给我的未过期邀请：只在 score 未变时删
            try {
                store.pruneInvite(caller, entry.teamId(), entry.score(), deadline);
            } catch (DependencyException e) {
                log.error("[team] ListMyInvites 清理失效索引失败 player={} team={}: {}", u(caller), u(entry.teamId()),
                        e.toString());
            }
        }
        Map<Long, MemberDisplay> dc = display.load(roster, deadline);
        List<TeamIncomingInviteView> invites = new ArrayList<>(live.size());
        for (LiveInvite l : live) {
            TeamIncomingInviteView view = TeamViews.incomingInviteView(l.record(), caller, l.nowMs(), dc);
            if (view != null) {
                invites.add(view);
            }
        }
        invites.sort(INVITE_ORDER);
        return ListMyInvitesResponse.newBuilder().addAllInvites(invites).setServerTimeMs(list.nowMs()).build();
    }

    /** LeaveTeam（210，service.go:302-307）：expected 队伍里已经没有我 → 成功、不写，带当前视图。 */
    public TeamResponse leaveTeam(long caller, LeaveTeamRequest request, Deadline deadline) {
        return runMutate(caller, Bind.caller(caller, request.getExpectedTeamId()), Op.leave(caller),
                new MutateSpec(TeamMethods.LEAVE_TEAM, true, false), deadline).response();
    }

    /** KickMember（202，service.go:309-314）：队长踢人；服务层不检查 target。 */
    public TeamResponse kickMember(long caller, KickMemberRequest request, Deadline deadline) {
        return runMutate(caller, Bind.caller(caller, request.getExpectedTeamId()),
                Op.kick(caller, request.getTargetPlayerId()), MutateSpec.of(TeamMethods.KICK_MEMBER), deadline).response();
    }

    /** TransferLeader（212，service.go:316-321）：队长转让。 */
    public TeamResponse transferLeader(long caller, TransferLeaderRequest request, Deadline deadline) {
        return runMutate(caller, Bind.caller(caller, request.getExpectedTeamId()),
                Op.transferLeader(caller, request.getTargetPlayerId()), MutateSpec.of(TeamMethods.TRANSFER_LEADER),
                deadline).response();
    }

    /** DisbandTeam（209，service.go:323-328）：队长解散；当前队伍 ≠ expected → 4013 + 当前视图。 */
    public TeamResponse disbandTeam(long caller, DisbandTeamRequest request, Deadline deadline) {
        return runMutate(caller, Bind.caller(caller, request.getExpectedTeamId()), Op.disband(caller),
                MutateSpec.of(TeamMethods.DISBAND_TEAM), deadline).response();
    }

    /**
     * StartTeamMatch（211，service.go:330-412；team-spec §5.1；跨进程后的流程见 match-spec §7.6）。整队开战：不补位、即时开战、不进队列。
     * 第 1–4 步算一轮；开战锁提交返回冲突 / 修复时<b>整轮重来</b>（重新读记录、重排名单、重新预检），最多 3 轮且不超出请求预算，
     * 耗尽回 4029 + 自由读。
     * <ol>
     *   <li>{@code mutate(Bind.caller, Refresh)}：存储故障 → 4030 + 自由读；惰性转让 / 过期清理有提交 → 照常推送并整轮重来（占一轮）；
     *       未绑定 / 记录缺失 → 4013 + 同源视图；拒绝 → code + 自由读；</li>
     *   <li>非队长 → 4018、锁有效 → 4023，都带同源视图（重复开战时是 STARTING，客户端视为进行中）；</li>
     *   <li>{@code checkTeamMatch}（xm-match：副本人数 → 按名单逐人预检）：非通过 → 4027 / 4028 / 4024[pid] / 4025[pid] / 4026[pid]；
     *       xm-match 调不通、超出请求预算、应答不可信 → 4030。<b>一律带本轮 S_READ 的同源视图</b>（基线 service.go:375-378）；</li>
     *   <li>开战锁钉版本提交（{@code 截止 = 本轮 S_READ 的 nowMs + 锁时长}，ver 钉死在第 1 步）：<b>报错或未提交都可能已落锁</b>
     *       （回复丢失 / Redisson 重发同一段 EVAL）——报错 → 后台按 token 清锁、回 4030；未提交 → 重来之前按 token 同步确认一轮；</li>
     *   <li>按<b>锁内名单</b>建票（每人一个 UUID 的 ticket id）：失败 → 回 4026[pid]，后台清锁并给全员推带同一 tip 的 MATCH_FAILED；
     *       结果不明（传输失败等，基线没有这个故障面）→ 回 4030，后台先退票再清锁、推不带 tip 的 MATCH_FAILED；</li>
     *   <li>回包 = 加锁那次提交构建的视图（STARTING）；除发起人外的队员收 MATCH_STARTED；</li>
     *   <li>gather 是一次长挂的异步调用，结束时在 {@code team-match-end} 执行器上清锁并给全员（含发起人）推 MATCH_ENDED / MATCH_FAILED
     *       （不带 tip：gather 失败拿不到具体原因）。传输失败（结果不明）同样推 MATCH_FAILED，但<b>不退票</b>——gather 可能仍在跑。</li>
     * </ol>
     * 每次 211 记一次 {@code xm_team_matches_total}（同步拒绝按 tip 定性；已受理的在 gather 收尾时记）。本进程在第 6 步之后退出时
     * 锁靠自然过期（同基线：进程退出不等 EndMatch）。
     */
    public TeamResponse startTeamMatch(long caller, StartTeamMatchRequest request, Deadline deadline) {
        int configId = request.getBattleConfigId();
        Bind bind = Bind.caller(caller, request.getExpectedTeamId());
        for (int round = 0; round < MATCH_START_ROUNDS && !deadline.expired(); round++) {
            MutateResult res;
            try {
                res = store.mutate(bind, Op.refresh(caller), sessions, cfg, deadline);
            } catch (DependencyException e) {
                log.error("[team] StartTeamMatch 存储故障 player={} team={}: {}", u(caller), u(bind.teamId()), e.toString());
                return matchReject(caller, TeamTips.INTERNAL, 0, null, deadline);
            }
            mutateEffects(caller, TeamMethods.START_TEAM_MATCH, res);
            switch (res.outcome()) {
                case COMMITTED -> {
                    continue; // 惰性转让 / 过期清理已落盘：整轮重来
                }
                case NOT_BOUND, RECORD_MISSING -> {
                    return matchReject(caller, TeamTips.NO_TEAM, 0, freshSnapshot(res), deadline);
                }
                case REJECTED -> {
                    return matchReject(caller, res.code(), res.param(), null, deadline);
                }
                case UNCHANGED -> {
                    // 记录、ver、nowMs 与调用者索引出自这一次 S_READ（记录必然存在）
                }
            }
            Snapshot snap = res.snapshot();
            TeamRecord rec = snap.record();
            int code = TeamRules.checkMatchStart(rec, caller, snap.nowMs());
            if (code != TeamTips.OK) {
                return matchReject(caller, code, 0, snap, deadline);
            }
            List<Long> roster = TeamRules.matchRoster(rec);
            TeamBattlePort.Check check = battle.checkTeamMatch(configId, roster, deadline);
            if (!check.ok()) {
                return matchReject(caller, check.code(), check.param(), snap, deadline);
            }

            String token = UUID.randomUUID().toString();
            long expireAtMs = snap.nowMs() + check.lockTtlSeconds() * 1000L;
            PinnedResult lock;
            try {
                lock = store.commitMatchLock(snap, caller, token, roster, expireAtMs, sessions, deadline);
            } catch (DependencyException e) {
                log.error("[team] StartTeamMatch 开战锁提交故障 player={} team={}: {}", u(caller), u(snap.teamId()), e.toString());
                // 结果未知：EVAL 可能已在 Redis 执行、只是回复超时 / 断连。按 token 后台清锁（锁没写入时只读不写不推），
                // 否则锁白挂到自然过期，期间全队的名单操作都回 4023
                releaseLockInBackground(snap.teamId(), token);
                return matchReject(caller, TeamTips.INTERNAL, 0, null, deadline);
            }
            pushes.publish(caller, pinnedCommits(lock.repairs(), lock.commit()));
            if (lock.code() != TeamTips.OK) {
                return matchReject(caller, lock.code(), lock.param(), null, deadline);
            }
            if (lock.commit() == null) {
                // 预检期间名单或记录变了：整轮重来。绝不在新名单上加锁、却按旧名单建票
                metrics.commitRetry(TeamMethods.START_TEAM_MATCH);
                // 「未提交」不等于没写入：同一段 EVAL 被重发、第一次已落锁时第二次回 {0}。重来之前按 token 确认一次，
                // 否则下一轮会被自己的锁挡成 4023
                settleUnconfirmedLock(snap.teamId(), token, deadline);
                continue;
            }
            return launchMatch(caller, configId, lock.commit(), token, check, deadline);
        }
        return matchReject(caller, TeamTips.STATE_CHANGED, 0, null, deadline);
    }

    // ================================================================ 整队开战：加锁之后（service.go:465-595）

    /**
     * 开战锁已落盘之后（service.go:465-495）。建票与 gather 只用锁内名单（与锁同一次提交落盘）。
     *
     * @param lock  加锁的那次提交
     * @param token 开战锁令牌（清锁凭它确认）
     * @param check 本轮预检的结论（每人的 zone 与锁时长）
     */
    private TeamResponse launchMatch(long caller, int configId, CommitResult lock, String token, TeamBattlePort.Check check,
                                     Deadline deadline) {
        long teamId = lock.teamId();
        List<Long> roster = List.copyOf(lock.decision().record().getMatchLockRosterList());
        // 每人一个 UUID（match-spec §0.5）：建票回包丢失时凭它退票；JoinQueue 的 16001 会把票号回给客户端，所以不能用锁令牌
        Map<Long, String> ticketIds = new LinkedHashMap<>();
        for (long pid : roster) {
            ticketIds.put(pid, UUID.randomUUID().toString());
        }
        TeamBattlePort.Tickets tickets = battle.createTeamTickets(configId, teamId, roster, check.zones(), ticketIds, deadline);
        if (tickets instanceof TeamBattlePort.Tickets.Failed failed) {
            metrics.match(MatchOutcome.TICKET_FAILED);
            log.info("[team] 整队开战建票失败，释放开战锁 team={} player={}", u(teamId), u(failed.playerId()));
            // 补偿不继承请求预算：后台清锁，结果推全员（含发起人）。回包里的视图可能仍是 STARTING，客户端按 (epoch, version) 排序，
            // 随后到达的 MATCH_FAILED 视图版本更新。推给队员的 MATCH_FAILED 带同一个原因
            TipInfoMessage tip = tipOf(TeamTips.MEMBER_NOT_READY, failed.playerId());
            inBackground("建票失败后清锁", teamId, () -> finishMatch(teamId, token, false, roster, tip));
            return respond(caller, TeamTips.MEMBER_NOT_READY, failed.playerId(), deadline);
        }
        if (tickets instanceof TeamBattlePort.Tickets.Unknown unknown) {
            metrics.match(MatchOutcome.INTERNAL);
            log.error("[team] 整队开战建票结果不明，退票并释放开战锁 team={}: {}", u(teamId), unknown.why());
            // 迟到的建票可能已经执行：先按本次的票号退票，再清锁。gather 还没发出，所以可以退（match-spec §7.5）
            inBackground("建票结果不明后的退票与清锁", teamId, () -> {
                battle.releaseTeamTickets(ticketIds, Deadline.after(RELEASE_TICKETS_BUDGET_MS));
                finishMatch(teamId, token, false, roster, null);
            });
            return respond(caller, TeamTips.INTERNAL, 0, deadline);
        }
        TeamResponse resp = commitResponse(caller, lock, deadline);
        log.info("[team] 整队开战已受理 team={} config={} roster={}", u(teamId), Integer.toUnsignedString(configId), us(roster));
        CompletionStage<TeamBattlePort.Gather> gather;
        try {
            gather = battle.runTeamGather(configId, teamId, roster, ticketIds, check.lockTtlSeconds());
        } catch (RuntimeException e) {
            gather = CompletableFuture.failedFuture(e);
        }
        // 回调在 Dubbo 的线程上触发：只做投递，清锁（阻塞、最坏 110 s）在 team-match-end 上
        gather.whenComplete((result, error) -> inBackground("gather 收尾", teamId, () -> {
            boolean ok = error == null && result != null && result.ok();
            if (error != null || result == null) {
                // xm-match 中途退出、网络分区或调用超时：结果不明。不退票——gather 可能仍在跑，票据由它收尾或按 matched TTL 自愈
                metrics.match(MatchOutcome.GATHER_UNKNOWN);
                log.error("[team] 整队 gather 结果不明（按失败收尾，不退票）team={}: {}", u(teamId), String.valueOf(error));
            } else if (ok) {
                metrics.match(MatchOutcome.SUCCESS);
                log.info("[team] 整队开战成功 team={} battle={}", u(teamId), u(result.battleId()));
            } else {
                metrics.match(MatchOutcome.GATHER_FAILED);
                log.info("[team] 整队 gather 失败 team={} outcome={}", u(teamId), result.outcome());
            }
            // gather 失败拿不到要告诉玩家的具体原因：tip 留空
            finishMatch(teamId, token, ok, roster, null);
        }));
        return resp;
    }

    /**
     * 清开战锁并推结果（service.go:497-523），在 {@code team-match-end} 上执行，不受任何请求预算约束（阻塞，最坏 110 s）。
     * 清锁提交成功 → 按提交推全员（含发起人）MATCH_ENDED / MATCH_FAILED；没有提交（锁已被清 / 重新加锁 / 自然过期 / 截止）→
     * 仍尽力给当前队员推一次当前视图与结果原因，保证客户端不停在 STARTING。
     *
     * @param tip 结果推送的原因（{@code TeamSnapshotS2C.tip}，可为 null）；只随结果推送下发，不进存储
     */
    void finishMatch(long teamId, String token, boolean ok, List<Long> roster, TipInfoMessage tip) {
        EndMatchResult res = store.endMatch(teamId, token, ok, sessions);
        pushes.publish(0, pinnedCommits(res.repairs(), res.commit() == null ? null : res.commit().withPushTip(tip)));
        switch (res.stop()) {
            case RELEASED -> {
                return;
            }
            case INTERRUPTED -> {
                log.warn("[team] EndMatch 被停机打断（锁靠自然过期）team={} conflicts={}", u(teamId), res.conflicts());
                return; // 进程正在退出：推送池随后关闭，不再推
            }
            case DEADLINE -> log.error("[team] EndMatch 截止仍未清锁（靠锁自然过期）team={} conflicts={} last_err={}", u(teamId),
                    res.conflicts(), String.valueOf(res.lastError()));
            default -> log.info("[team] EndMatch 未写入 team={} stop={} conflicts={}", u(teamId), res.stop(), res.conflicts());
        }
        pushes.publishMatchView(teamId, roster, ok ? TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED
                : TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED, tip);
    }

    /**
     * 开战锁提交结果未知时的后台补偿（service.go:525-542）：按 token 清锁（EndMatch，ok = false）。与 {@link #finishMatch} 不同，
     * <b>没有清锁提交就不推送</b>——锁可能根本没写入，给全队推 MATCH_FAILED 等于凭空多出一场从没开始过的开战。锁没写入时 EndMatch
     * 读到 token 不符即停止，不写。
     */
    private void releaseLockInBackground(long teamId, String token) {
        inBackground("结果未知的开战锁按 token 清除", teamId, () -> {
            EndMatchResult res = store.endMatch(teamId, token, false, sessions);
            if (res.commit() != null) {
                log.info("[team] 结果未知的开战锁已按 token 清除 team={}", u(teamId));
            }
            pushes.publish(0, pinnedCommits(res.repairs(), res.commit()));
            if (res.stop() == EndMatchStop.DEADLINE) {
                log.error("[team] 开战锁补偿截止仍未清锁（靠锁自然过期）team={} conflicts={} last_err={}", u(teamId), res.conflicts(),
                        String.valueOf(res.lastError()));
            }
        });
    }

    /**
     * 开战锁提交没拿到「已提交」时，用请求预算按 token 同步确认一轮（service.go:544-566）：锁不在 / token 不符 / 已过期 → 只读不写
     * （常见情形：预检期间真有别的提交）；锁在（同一段 EVAL 被重发、第一次已落锁）→ 钉版本清掉并推送。同步确认不了（故障或清锁冲突）
     * 就交给后台补偿。
     */
    private void settleUnconfirmedLock(long teamId, String token, Deadline deadline) {
        LockRelease release;
        try {
            release = store.releaseMatchLockOnce(teamId, token, sessions, deadline);
        } catch (DependencyException e) {
            log.error("[team] 确认未提交的开战锁失败，转后台清锁 team={}: {}", u(teamId), e.toString());
            releaseLockInBackground(teamId, token);
            return;
        }
        if (release.stop() != null) {
            return;
        }
        PinnedResult pinned = release.pinned();
        if (pinned.commit() != null) {
            log.info("[team] 开战锁 EVAL 被重发且已落锁，已按 token 清除 team={}", u(teamId));
        }
        pushes.publish(0, pinnedCommits(pinned.repairs(), pinned.commit()));
        if (pinned.commit() == null) {
            releaseLockInBackground(teamId, token);
        }
    }

    /** 钉版本提交的待推送列表（按落盘顺序：修复在前、主提交在后；service.go:667-675），并为每个修复记一次自愈指标。 */
    private List<CommitResult> pinnedCommits(List<CommitResult> repairs, CommitResult commit) {
        List<CommitResult> commits = new ArrayList<>(repairs.size() + 1);
        for (CommitResult repair : repairs) {
            metrics.heal(HealKind.INDEX_MISMATCH);
            commits.add(repair);
        }
        if (commit != null) {
            commits.add(commit);
        }
        return commits;
    }

    /**
     * 把开战的后台收尾投到 {@code team-match-end}（基线 asyncFn）。执行器已满时放弃并记 ERROR：开战锁靠自然过期（最长 101 s），
     * 期间客户端停在 STARTING、靠拉取自愈。
     */
    private void inBackground(String what, long teamId, Runnable task) {
        try {
            matchEnd.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.error("[team] {}出错（开战锁靠自然过期）team={}", what, u(teamId), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[team] team-match-end 执行器已满，放弃{}（开战锁靠自然过期）team={}", what, u(teamId));
        }
    }

    // ================================================================ 结果映射（service.go:604-764）

    /**
     * @param method               RPC 名，用作日志与 {@code xm_team_commit_retries_total{op}}
     * @param unboundIsSuccess     未绑定 / 记录缺失回成功（LeaveTeam 的幂等语义、GetMyTeam 的读语义）
     * @param pruneInviteOnMissing 记录缺失时顺手删调用者自己的邀请反查项（RespondInvite）
     */
    private record MutateSpec(String method, boolean unboundIsSuccess, boolean pruneInviteOnMissing) {

        static MutateSpec of(String method) {
            return new MutateSpec(method, false, false);
        }
    }

    /** @param result 仅在存储故障时为 null */
    private record Mutated(TeamResponse response, MutateResult result) {
    }

    /** @param failure 自由读失败时现成的失败回包（不带视图）；否则为 null */
    private record SelfRead(Snapshot snapshot, TeamResponse failure) {
    }

    /** @param code 0 = 查到了 */
    private record Zone(int zone, int code) {
    }

    /**
     * 执行一次名册写并映射结局（service.go:615-648，team-spec §3.1）：
     * <ul>
     *   <li>存储故障 → 4030 + 自由读，不派发副作用（同基线：前几轮已落盘的修复不推送，§8.1 第 8 条）；</li>
     *   <li>COMMITTED → 0 + 提交视图；</li>
     *   <li>NOT_BOUND / RECORD_MISSING → 4013（unboundIsSuccess 时 0）+ 同源快照；RespondInvite 记录缺失时先删自己的反查项；</li>
     *   <li>UNCHANGED → 0 + 同源快照；</li>
     *   <li>REJECTED：规则拒绝（{@code decision.code ≠ 0}）的判定就基于最后一次 S_READ，视图可同源复用；Lua 拒绝（{-1} / {-3}）、
     *       冲突耗尽、预算过期、建队撞号时那次读已不代表现状，改用自由读。</li>
     * </ul>
     * 副作用先于回包派发：修复提交无论最终结局如何都已落盘，必须照常推送。
     */
    private Mutated runMutate(long caller, Bind bind, Op op, MutateSpec spec, Deadline deadline) {
        MutateResult res;
        try {
            res = store.mutate(bind, op, sessions, cfg, deadline);
        } catch (DependencyException e) {
            log.error("[team] {} 存储故障 player={} team={}: {}", spec.method(), u(caller), u(bind.teamId()), e.toString());
            return new Mutated(respond(caller, TeamTips.INTERNAL, 0, deadline), null);
        }
        mutateEffects(caller, spec.method(), res);
        TeamResponse response = switch (res.outcome()) {
            case COMMITTED -> commitResponse(caller, res.commit(), deadline);
            case NOT_BOUND, RECORD_MISSING -> {
                if (res.outcome() == Outcome.RECORD_MISSING && spec.pruneInviteOnMissing()) {
                    pruneOwnInvite(caller, bind.teamId(), deadline);
                }
                int code = spec.unboundIsSuccess() ? TeamTips.OK : TeamTips.NO_TEAM;
                yield snapshotResponse(caller, code, 0, freshSnapshot(res), deadline);
            }
            case UNCHANGED -> snapshotResponse(caller, TeamTips.OK, 0, freshSnapshot(res), deadline);
            case REJECTED -> snapshotResponse(caller, res.code(), res.param(),
                    res.decision().code() != TeamTips.OK ? freshSnapshot(res) : null, deadline);
        };
        return new Mutated(response, res);
    }

    /**
     * 记一次 mutate 的冲突 / 自愈指标，并派发已落盘提交的推送（修复提交在前、主提交在后；service.go:650-675）。
     * 孤儿索引自愈在基线还给调用者发 scene 信号，Java 不发（D7），只记指标。
     */
    private void mutateEffects(long caller, String method, MutateResult res) {
        for (int i = 0; i < res.conflicts(); i++) {
            metrics.commitRetry(method);
        }
        List<CommitResult> commits = new ArrayList<>(res.repairs().size() + 1);
        for (CommitResult repair : res.repairs()) {
            metrics.heal(HealKind.INDEX_MISMATCH);
            commits.add(repair);
        }
        if (res.healedOrphan()) {
            metrics.heal(HealKind.ORPHAN_INDEX);
        }
        if (res.outcome() == Outcome.COMMITTED) {
            commits.add(res.commit());
        }
        pushes.publish(caller, commits);
    }

    /**
     * 最后一轮 S_READ 仍能代表调用者当前状态时返回它；本次 mutate 自己落过修复提交或自愈过孤儿索引时返回 null
     * （那次读已过时，调用方改用自由读；service.go:677-684）。
     */
    private static Snapshot freshSnapshot(MutateResult res) {
        if (!res.repairs().isEmpty() || res.healedOrphan()) {
            return null;
        }
        return res.snapshot();
    }

    /** 提交成功的回包：调用者在 J/K/L 里时用同一次提交构建视图，否则自由读（service.go:686-697）。 */
    private TeamResponse commitResponse(long caller, CommitResult c, Deadline deadline) {
        if (!TeamViews.commitViewable(c, caller)) {
            return respond(caller, TeamTips.OK, 0, deadline);
        }
        Map<Long, MemberDisplay> dc = c.indexes().get(caller).teamId() != 0
                ? display.load(TeamViews.rosterIds(c.decision().record()), deadline) : TeamViews.noDisplay();
        TeamView view = TeamViews.viewFromCommit(c, caller, dc).orElseThrow();
        return TeamResponse.newBuilder().setTeam(view).build();
    }

    /** tip + 由 snap 同源构建的调用者视图；snap 为 null 或不可同源构建时改用自由读（service.go:699-708）。 */
    private TeamResponse snapshotResponse(long caller, int code, long param, Snapshot snap, Deadline deadline) {
        Optional<TeamView> view = snapshotView(snap, deadline);
        return response(code, param, view.isPresent() ? view.get() : freeView(caller, deadline));
    }

    /** tip + 调用者自由读视图（失败回包的视图只取调用者自己的一次 S_READ；service.go:710-713）。 */
    private TeamResponse respond(long caller, int code, long param, Deadline deadline) {
        return response(code, param, freeView(caller, deadline));
    }

    /** 调用者自由读视图；读失败返回 null（回包不带视图，客户端 3 s 后重拉自愈；service.go:715-723）。 */
    private TeamView freeView(long caller, Deadline deadline) {
        FreeRead read = readFree("view", caller, deadline);
        if (!read.ok()) {
            return null;
        }
        return snapshotView(read.snapshot(), deadline).orElse(null);
    }

    /** 展示缓存只在视图里有队伍时加载（service.go:725-734）。 */
    private Optional<TeamView> snapshotView(Snapshot snap, Deadline deadline) {
        if (!TeamViews.snapshotViewable(snap)) {
            return Optional.empty();
        }
        Map<Long, MemberDisplay> dc = snap.playerTeamId() != 0
                ? display.load(TeamViews.rosterIds(snap.record()), deadline) : TeamViews.noDisplay();
        return TeamViews.viewFromSnapshot(snap, dc);
    }

    /** 调用者自由读；失败时带现成的失败回包（4029 / 4030，不带视图；service.go:736-743）。 */
    private SelfRead readSelf(String method, long caller, Deadline deadline) {
        FreeRead read = readFree(method, caller, deadline);
        if (!read.ok()) {
            return new SelfRead(null, response(readFailureCode(read), 0, null));
        }
        return new SelfRead(read.snapshot(), null);
    }

    /** {@link TeamStore#readFree} 加上自愈指标与故障日志（service.go:745-756；Java 不发 scene 信号，D7）。 */
    private FreeRead readFree(String method, long playerId, Deadline deadline) {
        FreeRead read = store.readFree(playerId, deadline);
        if (read.healed()) {
            metrics.heal(HealKind.ORPHAN_INDEX);
        }
        switch (read.status()) {
            case OK -> {
            }
            case UNSTABLE -> log.warn("[team] {} 自由读不稳定（索引持续变化）player={}", method, u(playerId));
            case FAILED -> log.error("[team] {} 自由读失败 player={}: {}", method, u(playerId), String.valueOf(read.error()));
        }
        return read;
    }

    /** 自由读失败的 tip：索引持续变化是「状态已变化」，其余是内部故障（service.go:758-764）。 */
    private static int readFailureCode(FreeRead read) {
        return read.status() == FreeRead.Status.UNSTABLE ? TeamTips.STATE_CHANGED : TeamTips.INTERNAL;
    }

    /** 查单个玩家 home zone（service.go:766-783）：查询失败 → 4030；没有或为 0 → 4019。 */
    private Zone homeZoneOf(long playerId, Deadline deadline) {
        Map<Long, Integer> zones;
        try {
            zones = homeZones.homeZones(List.of(playerId), deadline);
        } catch (RuntimeException e) {
            log.error("[team] 查询 home zone 失败 player={}: {}", u(playerId), e.toString());
            return new Zone(0, TeamTips.INTERNAL);
        }
        Integer zone = zones == null ? null : zones.get(playerId);
        if (zone == null || zone == 0) {
            log.info("[team] 玩家没有 home zone player={}", u(playerId));
            return new Zone(0, TeamTips.HOME_ZONE_UNKNOWN);
        }
        return new Zone(zone, TeamTips.OK);
    }

    /** service.go:785-797：发号器出错或返回 0 都是故障，不许用 0 顶替。 */
    private long newTeamId() {
        long id = teamIds.getAsLong();
        if (id == 0) {
            throw new IllegalStateException("team_id 发号器返回 0");
        }
        return id;
    }

    /** 删调用者反查索引里 teamId 的项（按 ListInvites 看到的 score CAS）；失败只记日志（service.go:799-814）。 */
    private void pruneOwnInvite(long caller, long teamId, Deadline deadline) {
        InviteList list;
        try {
            list = store.listInvites(caller, deadline);
        } catch (DependencyException e) {
            log.error("[team] 清理邀请反查项时读索引失败 player={} team={}: {}", u(caller), u(teamId), e.toString());
            return;
        }
        for (InviteIndexEntry entry : list.entries()) {
            if (entry.teamId() != teamId) {
                continue;
            }
            try {
                store.pruneInvite(caller, teamId, entry.score(), deadline);
            } catch (DependencyException e) {
                log.error("[team] 清理邀请反查项失败 player={} team={}: {}", u(caller), u(teamId), e.toString());
            }
        }
    }

    /** StartTeamMatch 的同步失败出口：记 {@code xm_team_matches_total}，再回 tip + 视图（snap 可同源构建时复用，否则自由读）。 */
    private TeamResponse matchReject(long caller, int code, long param, Snapshot snap, Deadline deadline) {
        metrics.match(TeamMetrics.rejectOutcome(code));
        return snapshotResponse(caller, code, param, snap, deadline);
    }

    private static TeamResponse response(int code, long param, TeamView view) {
        TeamResponse.Builder b = TeamResponse.newBuilder();
        TipInfoMessage tip = tipOf(code, param);
        if (tip != null) {
            b.setErrorMessage(tip);
        }
        if (view != null) {
            b.setTeam(view);
        }
        return b.build();
    }

    /**
     * 组装 error_message（service.go:816-826）：成功为 null（成功时不设 error_message）；param ≠ 0 时放进 parameters[0]，
     * 只放十进制 player_id（无符号），绝不放文字。
     */
    static TipInfoMessage tipOf(int code, long param) {
        if (code == TeamTips.OK) {
            return null;
        }
        TipInfoMessage.Builder tip = TipInfoMessage.newBuilder().setId(code);
        if (param != 0) {
            tip.addParameters(Long.toUnsignedString(param));
        }
        return tip.build();
    }

    private static String u(long id) {
        return Long.toUnsignedString(id);
    }
}
