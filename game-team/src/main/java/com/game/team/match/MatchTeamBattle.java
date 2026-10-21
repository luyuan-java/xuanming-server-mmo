package com.game.team.match;

import com.game.api.MatchTeamService;
import com.game.api.match.MatchBudgets;
import com.game.api.match.MatchRpcAttachments;
import com.game.api.proto.TeamGatherReply;
import com.game.api.proto.TeamGatherRequest;
import com.game.api.proto.TeamMatchCheckReply;
import com.game.api.proto.TeamMatchCheckRequest;
import com.game.api.proto.TeamTicketsRelease;
import com.game.api.proto.TeamTicketsReply;
import com.game.api.proto.TeamTicketsRequest;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.team.rules.TeamTips;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TeamBattlePort} 的生产实现：调 game-match 的 {@link MatchTeamService}（Dubbo Triple，group {@code match}；match-spec §7.5、§7.6）。
 *
 * <p>调用纪律（{@code MatchTeamService} 的契约在调用方这一侧的落实）：
 * <ul>
 *   <li><b>不重试</b>：引用必须 {@code retries = 0}（装配见 {@code TeamDubboConfiguration}）。</li>
 *   <li><b>前三个方法</b>：每跳的 Dubbo 超时 = {@code min(3 s, 剩余请求预算)}（调用级 {@code timeout} 附件），<b>同一个值</b>（毫秒，发出时刻计）经附件
 *       {@code game-budget-ms} 带给 game-match——带下去的是这一跳本端真正肯等的时间，不是整请求的剩余预算（理由见 {@code call}）；
 *       预算已用完就不发。本地最多等到请求截止。</li>
 *   <li><b>{@link #runTeamGather}</b>：长挂调用，调用级超时 = 开战锁时长（5 人 101 s），不带预算附件；另加一个比它晚
 *       {@value #GATHER_GUARD_MS} ms 的本地兜底，保证返回的 stage 一定会完成。</li>
 *   <li><b>传输失败</b>（future 异常完成、超时、空应答）与<b>应答枚举的 UNSPECIFIED / 不认识的值</b>同样处理：预检 → 4030；建票 → 结果不明；
 *       gather → stage 异常完成。全默认值的应答绝不读成「通过 / 建成」。</li>
 * </ul>
 *
 * <p>线程：前三个方法阻塞调用线程（{@code team-worker} / {@code team-match-end}），不得在 Dubbo / Netty 线程上调；附件挂在调用线程的
 * {@code RpcContext} 上、调完即清。无可变状态，线程安全。
 */
public final class MatchTeamBattle implements TeamBattlePort {
    public enum IntentMode { SQL_AUTHORITY, LEGACY_COMPATIBILITY }
    private final IntentMode intentMode;
    @Override public boolean durableIntents(){return intentMode==IntentMode.SQL_AUTHORITY;}
    @Override public com.game.proto.team.TeamMatchIntent queryIntent(com.game.api.proto.SessionContext caller,String request,com.google.protobuf.ByteString payload,Deadline deadline){
        requireOriginal(caller);
        var query=com.game.api.proto.TeamMatchIntentQuery.newBuilder().setPlayerId(caller.getPlayerId()).setRequestId(request).setClientRequest(payload)
                .setOwnerAccount(caller.getAccount()).setOwnerRevision(caller.getOwnerRevision()).build();
        return call("queryTeamMatchIntent",deadline,()->match.queryTeamMatchIntent(query));
    }
    @Override public Tickets createDurableTickets(int config,long team,List<Long> roster,Map<Long,Integer> zones,Map<Long,String> tickets,
            com.game.api.proto.SessionContext caller,com.game.proto.team.StartTeamMatchRequest original,Deadline deadline){
        return createDurableTickets(config,team,roster,zones,tickets,caller,original,com.google.protobuf.ByteString.EMPTY,deadline);
    }
    @Override public Tickets createDurableTickets(int config,long team,List<Long> roster,Map<Long,Integer> zones,Map<Long,String> tickets,
            com.game.api.proto.SessionContext caller,com.game.proto.team.StartTeamMatchRequest original,com.google.protobuf.ByteString launchOriginal,Deadline deadline){
        requireOriginal(caller);
        var request=TeamTicketsRequest.newBuilder().setBattleConfigId(config).setTeamId(team).addAllRoster(roster).putAllZones(zones).putAllTicketIds(tickets)
                .setRequestId(original.getRequestId()).setInitiatorPlayerId(caller.getPlayerId()).setClientRequest(original.toByteString())
                .setOwnerAccount(caller.getAccount()).setOwnerRevision(caller.getOwnerRevision()).setLaunchOriginal(launchOriginal).build();
        try{var reply=call("createTeamTickets",deadline,()->match.createTeamTickets(request));return switch(reply.getStatus()){
            case TEAM_TICKETS_CREATED -> new Tickets.Created();case TEAM_TICKETS_QUEUED -> new Tickets.Queued();default -> new Tickets.Unknown("原开局意图尚未确认");};}
        catch(RuntimeException e){return new Tickets.Unknown("原开局意图尚未确认");}
    }
    private static void requireOriginal(com.game.api.proto.SessionContext caller) {
        if(caller==null||caller.getPlayerId()==0||caller.getAccount().isEmpty()||caller.getOwnerRevision()<=0)
            throw new IllegalArgumentException("缺少队伍开局原会话身份或修订越界");
    }

    private static final Logger log = LoggerFactory.getLogger(MatchTeamBattle.class);

    /** 前三个方法每跳 Dubbo 超时的上限（毫秒）；实际取它与剩余请求预算的较小者。 */
    public static final long HOP_TIMEOUT_MS = 3_000;

    /**
     * 可接受的开战锁时长上限（秒）= 契约上限 {@link MatchBudgets#MAX_TEAM_LOCK_SECONDS}。game-match 给出更长的锁（两边版本不一，或应答不可信）
     * 时拒绝开战（4030）：它限制一把锁最多占住收尾线程与 {@link #runTeamGather} 长挂调用多久。EndMatch 的截止由这把锁的实际时长推出
     * （{@code TeamStore.endMatchDeadlineMs}），不靠这个上限保证。
     */
    public static final int MAX_LOCK_TTL_SECONDS = MatchBudgets.MAX_TEAM_LOCK_SECONDS;

    /** {@link #runTeamGather} 的本地兜底相对调用级超时的余量（毫秒）。 */
    static final long GATHER_GUARD_MS = 2_000;

    private final MatchTeamService match;

    /** @param match game-match 的整队开战接口（Dubbo 引用：group match、retries = 0） */
    public MatchTeamBattle(MatchTeamService match) { this(match,IntentMode.SQL_AUTHORITY); }
    /** 旧 Redis 兼容模型须显式选择；生产装配始终使用默认 SQL 权威。 */
    public MatchTeamBattle(MatchTeamService match,IntentMode intentMode) {
        this.match = java.util.Objects.requireNonNull(match);
        this.intentMode=java.util.Objects.requireNonNull(intentMode);
    }

    @Override
    public Check checkTeamMatch(int battleConfigId, List<Long> roster, Deadline deadline) {
        TeamMatchCheckRequest request = TeamMatchCheckRequest.newBuilder().setBattleConfigId(battleConfigId)
                .addAllRoster(roster).build();
        TeamMatchCheckReply reply;
        try {
            reply = call("checkTeamMatch", deadline, () -> match.checkTeamMatch(request));
        } catch (RuntimeException e) {
            log.error("[team] 整队开战预检调 game-match 失败（按 4030）config={}: {}", Integer.toUnsignedString(battleConfigId),
                    e.toString());
            return Check.rejected(TeamTips.INTERNAL, 0);
        }
        return switch (reply.getResult()) {
            case TEAM_MATCH_CHECK_OK -> {
                int ttl = reply.getLockTtlSeconds();
                if (ttl < 1 || ttl > MAX_LOCK_TTL_SECONDS) {
                    log.error("[team] game-match 给的开战锁时长不在 [1, {}] 秒内（两边版本不一？按 4030）: {}", MAX_LOCK_TTL_SECONDS,
                            Integer.toUnsignedString(ttl));
                    yield Check.rejected(TeamTips.INTERNAL, 0);
                }
                if (!reply.getZonesMap().keySet().containsAll(roster)) {
                    // 通过的应答必须带齐每个人的 zone（建票时原样带回）：缺了就是不可信的应答，不拿 0 顶替
                    log.error("[team] game-match 的预检通过应答没有带齐每名成员的 zone（按 4030）: roster={} zones={}", roster.size(),
                            reply.getZonesCount());
                    yield Check.rejected(TeamTips.INTERNAL, 0);
                }
                yield Check.passed(reply.getZonesMap(), ttl);
            }
            case TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN -> Check.rejected(TeamTips.DUNGEON_NOT_OPEN, 0);
            case TEAM_MATCH_CHECK_SIZE_EXCEEDED -> Check.rejected(TeamTips.SIZE_EXCEEDED, 0);
            case TEAM_MATCH_CHECK_MEMBER_OFFLINE -> Check.rejected(TeamTips.MEMBER_OFFLINE, reply.getOffender());
            case TEAM_MATCH_CHECK_MEMBER_IN_BATTLE -> Check.rejected(TeamTips.MEMBER_IN_BATTLE, reply.getOffender());
            case TEAM_MATCH_CHECK_MEMBER_NOT_READY -> Check.rejected(TeamTips.MEMBER_NOT_READY, reply.getOffender());
            case TEAM_MATCH_CHECK_INTERNAL -> {
                log.error("[team] 整队开战预检：game-match 回内部故障 config={}", Integer.toUnsignedString(battleConfigId));
                yield Check.rejected(TeamTips.INTERNAL, 0);
            }
            case TEAM_MATCH_CHECK_UNSPECIFIED, UNRECOGNIZED -> {
                log.error("[team] 整队开战预检：game-match 的应答没有可用的 result（{}），按传输失败处理", reply.getResultValue());
                yield Check.rejected(TeamTips.INTERNAL, 0);
            }
        };
    }

    @Override
    public Tickets createTeamTickets(int battleConfigId, long teamId, List<Long> roster, Map<Long, Integer> zones,
                                     Map<Long, String> ticketIds, Deadline deadline) {
        TeamTicketsRequest request = TeamTicketsRequest.newBuilder().setBattleConfigId(battleConfigId).setTeamId(teamId)
                .addAllRoster(roster).putAllZones(zones).putAllTicketIds(ticketIds).build();
        TeamTicketsReply reply;
        try {
            reply = call("createTeamTickets", deadline, () -> match.createTeamTickets(request));
        } catch (RuntimeException e) {
            return new Tickets.Unknown(e.toString());
        }
        return switch (reply.getStatus()) {
            case TEAM_TICKETS_CREATED -> new Tickets.Created();
            case TEAM_TICKETS_FAILED -> reply.getFailedPlayerId() != 0
                    ? new Tickets.Failed(reply.getFailedPlayerId())
                    : new Tickets.Unknown("game-match 回 FAILED 但没有 failed_player_id");
            case TEAM_TICKETS_EXPIRED -> new Tickets.Unknown("game-match：请求到达时预算已过期");
            case TEAM_TICKETS_UNSPECIFIED, UNRECOGNIZED ->
                    new Tickets.Unknown("game-match 的应答没有可用的 status（" + reply.getStatusValue() + "）");
        };
    }

    @Override
    public boolean releaseTeamTickets(Map<Long, String> ticketIds, Deadline deadline) {
        TeamTicketsRelease request = TeamTicketsRelease.newBuilder().putAllTicketIds(ticketIds).build();
        try {
            call("releaseTeamTickets", deadline, () -> match.releaseTeamTickets(request));
            return true;
        } catch (RuntimeException e) {
            log.warn("[team] 退票调 game-match 失败（票据按 matched TTL 自然过期）: {}", e.toString());
            return false;
        }
    }

    @Override
    public CompletionStage<Gather> runTeamGather(int battleConfigId, long teamId, List<Long> roster,
                                                 Map<Long, String> ticketIds, int lockTtlSeconds) {
        TeamGatherRequest request = TeamGatherRequest.newBuilder().setBattleConfigId(battleConfigId).setTeamId(teamId)
                .addAllRoster(roster).putAllTicketIds(ticketIds).build();
        long timeoutMs = lockTtlSeconds * 1000L;
        CompletableFuture<TeamGatherReply> future;
        try {
            future = MatchRpcAttachments.callWithTimeout(timeoutMs, () -> match.runTeamGather(request));
        } catch (RuntimeException e) { // 调用级超时非法（锁时长 < 1 s）：没有发出
            return CompletableFuture.failedFuture(e);
        }
        return future.orTimeout(timeoutMs + GATHER_GUARD_MS, TimeUnit.MILLISECONDS).thenApply(reply -> {
            if (reply == null) {
                throw new IllegalStateException("runTeamGather 返回了空应答");
            }
            return new Gather(reply.getOk(), reply.getOutcome(), reply.getBattleId());
        });
    }

    /**
     * 发一次调用并等到应答：每跳超时 = min({@link #HOP_TIMEOUT_MS}, 剩余请求预算)，带给 game-match 的预算附件<b>就是这个每跳超时</b>。
     *
     * <p>为什么不把整请求的剩余预算带下去：本端在这一跳最多只等「每跳超时」，过了这个点应答没人收（建票会被判结果不明并回滚）。
     * 附件比它大（请求预算缺省 3500 ms、每跳封顶 3 s，差出最多约 500 ms），game-match 的本地截止就晚于本端真正放弃的时刻，
     * 在工作队列里等过了 3 s 的建票仍会照常写票——正是预算附件要堵的「迟到的建票」窗口。
     *
     * @throws DependencyException 预算已用完（没有发出）、传输失败、超出请求预算、空应答——一律是「没有可信的应答」
     */
    private static <R> R call(String what, Deadline deadline, Supplier<CompletableFuture<R>> invocation) {
        long remaining = deadline.remainingMillis();
        if (remaining <= 0) {
            throw new DependencyException(what + "：请求预算已用完，没有发出");
        }
        long hop = Math.min(HOP_TIMEOUT_MS, remaining);
        CompletableFuture<R> future = MatchRpcAttachments.callWithBudget(hop, hop, invocation);
        R reply;
        try {
            reply = deadline.await(future, what);
        } catch (CancellationException e) {
            throw new DependencyException(what + " 被取消", e);
        }
        if (reply == null) {
            throw new DependencyException(what + " 返回了空应答");
        }
        return reply;
    }
}
