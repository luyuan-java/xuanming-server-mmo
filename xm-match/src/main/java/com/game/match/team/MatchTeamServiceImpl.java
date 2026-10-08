package com.game.match.team;

import com.game.api.DubboGroups;
import com.game.api.MatchTeamService;
import com.game.api.match.MatchBudgets;
import com.game.api.match.MatchRpcAttachments;
import com.game.api.proto.TeamGatherReply;
import com.game.api.proto.TeamGatherRequest;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamMatchCheckResult;
import com.game.api.proto.TeamTicketsRelease;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.api.proto.TeamTicketsStatus;
import com.game.common.deadline.Deadline;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.TeamCallResult;
import com.game.match.metrics.MatchMetrics.TeamMethod;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketStore;
import com.game.match.ticket.TicketStore.GroupMember;
import com.game.proto.Empty;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MatchTeamService} 的 Dubbo 提供方：整队开战的 match 一侧（match-spec §7.5；基线 {@code go/match/internal/logic/team_battle.go} 与
 * {@code team/service.go:417-463} 的预检）。xm-team 持有开战锁与编排，这里只做「票据领域」的四件事：
 * <ul>
 *   <li>{@link #checkTeamMatch}：副本人数（未配置 / 超员）→ 逐成员预检（{@link MemberPrecheck}，首个失败即返回）→ 发号租约。
 *       判定先后不变：人数先于成员、成员按名单顺序；租约放在最后，只拦「本来会放行」的请求（不让必败的开战先加锁，§9.8），不改变其它拒绝的可见结果。</li>
 *   <li>{@link #createTeamTickets}：按调用方给的票号原子建全员的 matched 票。冲突 → {@code FAILED} + 第一个冲突者；建票调用出错（结局不明）→
 *       独立预算回滚后 {@code FAILED} + {@code roster[0]}（与基线 Redis 故障时最常见的可见结果逐字节相同，Q27）。</li>
 *   <li>{@link #releaseTeamTickets}：按票号删，幂等；用自己的 3 s 预算，不看调用方的（它来退票时预算往往已经见底）。</li>
 *   <li>{@link #runTeamGather}：名单原序、失败全员删票；future 在 gather 结束时完成，不占工作线程。</li>
 * </ul>
 *
 * <p><b>{@code EXPIRED} 的口径</b>：「这次建票没有执行、什么都没写，调用方按传输失败处理（4030）」。除了字面上的「请求到达时预算已过期」，
 * 过载（工作池满 / 排队超预算）、发号租约无效、参数不合法也回它——这几种都不是哪名成员的问题，回 {@code FAILED} 会让客户端看到 4026[队长]。
 *
 * <p><b>读战斗锁出错</b>在这里映射成 {@code MEMBER_IN_BATTLE}（同基线 {@code tsvc.go:429-435}「按战斗中处理」）；活动入口映射成 INTERNAL，别抄混。
 *
 * <p><b>线程</b>：前两个方法在 Dubbo 线程上取截止（{@code xm-budget-ms} 附件必须在这条线程上同步读）并投到 {@code match-worker}；
 * 工作池拒收或轮到执行时预算已用完，按各方法的过载口径回，不让调用悬着。{@code releaseTeamTickets} 同样投到工作池，但不看调用方的预算
 * （排了多久都照删，只有工作池拒收才不删）。{@code runTeamGather} 在 Dubbo 线程上直接交给开局管线（不阻塞）。
 * 业务结论全部在应答消息里；只有 {@code releaseTeamTickets}（应答是 Empty）用 future 异常完成表示没删成。线程安全，无可变状态。
 */
@DubboService(group = DubboGroups.MATCH)
public class MatchTeamServiceImpl implements MatchTeamService {

    private static final Logger log = LoggerFactory.getLogger(MatchTeamServiceImpl.class);
    private static final String WHAT = "整队开战";

    private final MatchWorkers workers;
    private final MatchProperties props;
    private final MemberPrecheck precheck;
    private final TicketStore tickets;
    private final GroupTickets groupTickets;
    private final GatherLauncher gather;
    private final MatchIds ids;
    private final MatchMetrics metrics;
    private final long budgetMs;

    public MatchTeamServiceImpl(MatchWorkers workers, MatchProperties props, MemberPrecheck precheck, TicketStore tickets, GatherLauncher gather,
                                MatchIds ids, MatchMetrics metrics) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.props = Objects.requireNonNull(props, "props");
        this.precheck = Objects.requireNonNull(precheck, "precheck");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.groupTickets = new GroupTickets(tickets);
        this.gather = Objects.requireNonNull(gather, "gather");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.budgetMs = props.requestBudget().toMillis();
    }

    // ================================================================ Dubbo 入口

    @Override
    public CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request) {
        Deadline d = MatchRpcAttachments.deadlineFromCall(budgetMs);
        return onWorker(d, () -> check(request, d),
                why -> {
                    log.warn("[match] checkTeamMatch {}，回 INTERNAL", why);
                    metrics.teamCall(TeamMethod.CHECK_TEAM_MATCH, TeamCallResult.OVERLOADED);
                    return checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0);
                },
                error -> {
                    log.error("[match] checkTeamMatch 处理失败，回 INTERNAL", error);
                    metrics.teamCall(TeamMethod.CHECK_TEAM_MATCH, TeamCallResult.INTERNAL);
                    return checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0);
                });
    }

    @Override
    public CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request) {
        Deadline d = MatchRpcAttachments.deadlineFromCall(budgetMs);
        if (d.expired()) {
            // 调用方发出时就没有预算了：不进工作池、不写任何东西
            return CompletableFuture.completedFuture(expiredOnArrival(request));
        }
        return onWorker(d, () -> create(request, d),
                why -> {
                    log.warn("[match] createTeamTickets {}，什么都没写 team={}", why, Long.toUnsignedString(request.getTeamId()));
                    metrics.teamCall(TeamMethod.CREATE_TEAM_TICKETS, TeamCallResult.OVERLOADED);
                    return notAttempted();
                },
                error -> {
                    // 走到这里是 bug（依赖故障在 create 里已经收敛）：票据可能已写，调用方按传输失败处理并 release
                    log.error("[match] createTeamTickets 处理失败 team={}", Long.toUnsignedString(request.getTeamId()), error);
                    metrics.teamCall(TeamMethod.CREATE_TEAM_TICKETS, TeamCallResult.ERROR);
                    return notAttempted();
                });
    }

    /**
     * 退票用<b>独立的</b> {@value MatchBudgets#TICKET_ROLLBACK_BUDGET_MS} ms 预算，从出队执行的那一刻起算，<b>不沿用调用方的预算</b>
     * （不读 {@code xm-budget-ms}）：调用方是在建票结果不明之后来退票的，它那一跳的预算（至多 3 s）可能正好在本进程的工作队列里耗尽——
     * 沿用的话存储会当场拒发，票要留到 matched TTL（42–66 s）才自灭，其间全员再排一律 16001。删票带票号做 CAS、幂等，
     * 调用方已经放弃之后才删成也只有好处；代价是工作线程为一个已放弃的调用方至多多阻塞 3 s。与 {@link GroupTickets#rollback} 同一个口径。
     */
    @Override
    public CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request) {
        CompletableFuture<Empty> reply = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try {
                    release(request, Deadline.after(MatchBudgets.TICKET_ROLLBACK_BUDGET_MS));
                    metrics.teamCall(TeamMethod.RELEASE_TEAM_TICKETS, TeamCallResult.OK);
                    reply.complete(Empty.getDefaultInstance());
                } catch (RuntimeException e) {
                    log.error("[match] releaseTeamTickets 没删成（票据靠 matched TTL 过期） players={}", describe(request.getTicketIdsMap()), e);
                    metrics.teamCall(TeamMethod.RELEASE_TEAM_TICKETS, TeamCallResult.ERROR);
                    reply.completeExceptionally(e);
                } catch (Error e) {
                    reply.completeExceptionally(e);
                    throw e;
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[match] releaseTeamTickets 工作队列已满（票据靠 matched TTL 过期） players={}", describe(request.getTicketIdsMap()));
            metrics.teamCall(TeamMethod.RELEASE_TEAM_TICKETS, TeamCallResult.OVERLOADED);
            reply.completeExceptionally(e);
        }
        return reply;
    }

    @Override
    public CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request) {
        String team = Long.toUnsignedString(request.getTeamId());
        CompletableFuture<GatherResult> launched;
        try {
            // 名单原序 = 站位顺序；票号必须恰好覆盖名单（构造器校验）
            launched = gather.launch(GatherPlan.team(request.getBattleConfigId(), request.getRosterList(), request.getTicketIdsMap()));
        } catch (RuntimeException e) {
            log.error("[match] runTeamGather 的名单或票号不合法，没有开局 team={} roster={}", team, request.getRosterList(), e);
            metrics.teamCall(TeamMethod.RUN_TEAM_GATHER, TeamCallResult.GATHER_FAILED);
            return CompletableFuture.completedFuture(gatherReply(GatherResult.failed(GatherOutcome.INTERNAL, 0)));
        }
        // 回调在 gather 的线程上：只计数、组应答，不阻塞
        return launched.handle((result, error) -> {
            if (error != null || result == null) {
                log.error("[match] 整队 gather 没有给出结果（按失败回） team={}", team, error);
                metrics.teamCall(TeamMethod.RUN_TEAM_GATHER, TeamCallResult.GATHER_FAILED);
                return gatherReply(GatherResult.failed(GatherOutcome.INTERNAL, 0));
            }
            metrics.teamCall(TeamMethod.RUN_TEAM_GATHER, result.ok() ? TeamCallResult.GATHER_OK : TeamCallResult.GATHER_FAILED);
            log.info("[match] 整队 gather 结束 team={} ok={} outcome={} battle={}", team, result.ok(), result.outcome().label(),
                    Long.toUnsignedString(result.battleId()));
            return gatherReply(result);
        });
    }

    // ================================================================ 业务（在工作线程上；包内可见供测试直接调）

    /** 副本人数 → 逐成员预检 → 发号租约。 */
    TeamMatchCheckReply check(TeamMatchCheckRequest request, Deadline d) {
        int configId = request.getBattleConfigId();
        List<Long> roster = request.getRosterList();
        int limit = props.pveTeamSizeFor(configId);
        if (limit == 0) {
            return counted(TeamCallResult.DUNGEON_NOT_OPEN, checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN, 0));
        }
        if (roster.size() > limit) {
            return counted(TeamCallResult.SIZE_EXCEEDED, checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_SIZE_EXCEEDED, 0));
        }
        String invalid = invalidRoster(roster);
        if (invalid != null) {
            log.error("[match] checkTeamMatch 名单不合法：{} roster={}", invalid, roster);
            return counted(TeamCallResult.INTERNAL, checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0));
        }

        MemberPrecheck.Result result = precheck.check(roster, d);
        if (!result.passed()) {
            return switch (result.reason()) {
                case OFFLINE -> counted(TeamCallResult.MEMBER_OFFLINE,
                        checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE, result.offender()));
                // 读锁出错按「已在战斗」（基线整队入口的口径）
                case IN_BATTLE, LOCK_READ_FAILED -> counted(TeamCallResult.MEMBER_IN_BATTLE,
                        checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_IN_BATTLE, result.offender()));
                case NO_LOCATION, TICKET_IN_FLIGHT -> counted(TeamCallResult.MEMBER_NOT_READY,
                        checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_NOT_READY, result.offender()));
                case PRESENCE_READ_FAILED, LOCATION_READ_FAILED, TICKET_READ_FAILED, DEADLINE_EXPIRED, OK ->
                        counted(TeamCallResult.INTERNAL, checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0));
            };
        }

        if (!ids.leaseValid()) {
            log.error("[match] checkTeamMatch：发号租约无效（已丢失={}），不放行整队开战", ids.leaseLost());
            return counted(TeamCallResult.INTERNAL, checked(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0));
        }

        TeamMatchCheckReply.Builder reply = TeamMatchCheckReply.newBuilder()
                .setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK)
                .setLockTtlSeconds(MatchBudgets.teamMatchLockSeconds(roster.size()));
        result.zones().forEach(reply::putZones);
        return counted(TeamCallResult.OK, reply.build());
    }

    /** 按调用方的票号原子建票。 */
    TeamTicketsReply create(TeamTicketsRequest request, Deadline d) {
        if (d.expired()) {
            return expiredOnArrival(request);
        }
        List<Long> roster = request.getRosterList();
        Map<Long, String> ticketIds = request.getTicketIdsMap();
        String invalid = invalidRoster(roster);
        if (invalid == null) {
            invalid = missingTicket(roster, ticketIds);
        }
        if (invalid != null) {
            log.error("[match] createTeamTickets 参数不合法：{} team={} roster={}", invalid, Long.toUnsignedString(request.getTeamId()), roster);
            metrics.teamCall(TeamMethod.CREATE_TEAM_TICKETS, TeamCallResult.ERROR);
            return notAttempted();
        }
        if (!ids.leaseValid()) {
            // 检查通过之后租约才失效：接下来的 gather 第一步必败，不让它先建票
            log.error("[match] createTeamTickets：发号租约无效（已丢失={}），不建票 team={}", ids.leaseLost(),
                    Long.toUnsignedString(request.getTeamId()));
            metrics.teamCall(TeamMethod.CREATE_TEAM_TICKETS, TeamCallResult.ERROR);
            return notAttempted();
        }

        Map<Long, Integer> zones = request.getZonesMap();
        List<GroupMember> members = new ArrayList<>(roster.size());
        for (long playerId : roster) {
            members.add(new GroupMember(playerId, ticketIds.get(playerId), zones.getOrDefault(playerId, 0)));
        }
        GroupTickets.Outcome outcome = groupTickets.create(request.getBattleConfigId(), request.getTeamId(), members, d, WHAT);
        if (outcome instanceof GroupTickets.Outcome.Created) {
            metrics.teamCall(TeamMethod.CREATE_TEAM_TICKETS, TeamCallResult.CREATED);
            return TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_CREATED).build();
        }
        // 冲突：第一个已有别的票据的人；结局不明：已回滚，记在名单第一个人（队长）身上
        long failed = outcome instanceof GroupTickets.Outcome.Conflict conflict ? conflict.playerId() : roster.get(0);
        metrics.teamCall(TeamMethod.CREATE_TEAM_TICKETS, TeamCallResult.FAILED);
        return TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_FAILED).setFailedPlayerId(failed).build();
    }

    /** 按 (玩家, 票号) 删票：票号一致才删，一个原子操作。玩家号为 0 或票号为空的项跳过。失败抛依赖异常。 */
    void release(TeamTicketsRelease request, Deadline d) {
        Map<Long, String> ticketIds = request.getTicketIdsMap();
        if (ticketIds.size() > MatchBudgets.MAX_GATHER_PLAYERS) {
            throw new IllegalArgumentException("一次释放的票据过多: " + ticketIds.size());
        }
        List<TicketRef> refs = new ArrayList<>(ticketIds.size());
        ticketIds.forEach((playerId, ticketId) -> {
            if (playerId != 0 && !ticketId.isEmpty()) {
                refs.add(new TicketRef(playerId, ticketId));
            }
        });
        if (refs.isEmpty()) {
            return;
        }
        int deleted = tickets.deleteGroup(refs, d);
        log.info("[match] releaseTeamTickets 删掉 {} / {} 张 players={}", deleted, refs.size(), describe(ticketIds));
    }

    // ================================================================ 内部

    /**
     * 把一段阻塞的工作投到工作池；future 永不异常完成。
     *
     * @param overloaded 工作池拒收、或轮到执行时预算已用完（入参是原因，进日志）
     * @param failed     工作里抛了未分类的异常（bug）
     */
    private <R> CompletableFuture<R> onWorker(Deadline d, Supplier<R> work, Function<String, R> overloaded, Function<RuntimeException, R> failed) {
        CompletableFuture<R> reply = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try {
                    reply.complete(d.expired() ? overloaded.apply("在工作队列里等过了预算") : work.get());
                } catch (RuntimeException e) {
                    reply.complete(failed.apply(e));
                } catch (Error e) {
                    reply.completeExceptionally(e);
                    throw e;
                }
            });
        } catch (RejectedExecutionException e) {
            reply.complete(overloaded.apply("工作队列已满"));
        }
        return reply;
    }

    private TeamMatchCheckReply counted(TeamCallResult result, TeamMatchCheckReply reply) {
        metrics.teamCall(TeamMethod.CHECK_TEAM_MATCH, result);
        return reply;
    }

    private static TeamMatchCheckReply checked(TeamMatchCheckResult result, long offender) {
        return TeamMatchCheckReply.newBuilder().setResult(result).setOffender(offender).build();
    }

    private TeamTicketsReply expiredOnArrival(TeamTicketsRequest request) {
        log.warn("[match] createTeamTickets 到达时预算已过期，什么都没写 team={}", Long.toUnsignedString(request.getTeamId()));
        metrics.teamCall(TeamMethod.CREATE_TEAM_TICKETS, TeamCallResult.EXPIRED);
        return notAttempted();
    }

    /** 没有执行建票、什么都没写：调用方按传输失败处理（见类注释「EXPIRED 的口径」）。 */
    private static TeamTicketsReply notAttempted() {
        return TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_EXPIRED).build();
    }

    private static TeamGatherReply gatherReply(GatherResult result) {
        TeamGatherReply.Builder reply = TeamGatherReply.newBuilder().setOk(result.ok()).setOutcome(result.outcome().label());
        if (result.ok()) {
            reply.setBattleId(result.battleId());
        }
        return reply.build();
    }

    /** 名单必须 1..5 人、不含 0、不重复；合法返回 null，否则是写进日志的原因。 */
    private static String invalidRoster(List<Long> roster) {
        if (roster.isEmpty() || roster.size() > MatchBudgets.MAX_TEAM_SIZE) {
            return "人数越界";
        }
        Set<Long> seen = new HashSet<>();
        for (long playerId : roster) {
            if (playerId == 0) {
                return "名单含 0";
            }
            if (!seen.add(playerId)) {
                return "名单重复";
            }
        }
        return null;
    }

    private static String missingTicket(List<Long> roster, Map<Long, String> ticketIds) {
        for (long playerId : roster) {
            String ticketId = ticketIds.get(playerId);
            if (ticketId == null || ticketId.isEmpty()) {
                return "成员缺票号 player=" + Long.toUnsignedString(playerId);
            }
        }
        return null;
    }

    private static String describe(Map<Long, String> ticketIds) {
        return ticketIds.keySet().stream().map(Long::toUnsignedString).sorted().toList().toString();
    }
}
