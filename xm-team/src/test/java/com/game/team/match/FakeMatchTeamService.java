package com.game.team.match;

import com.game.api.MatchTeamService;
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
import com.game.proto.Empty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.RpcContext;

/**
 * xm-match 的 {@link MatchTeamService} 替身（对应基线测试的 fakeBattlePort，{@code team_battle_test.go:46-104}）：按配置给预检结论、
 * 建票结论与 gather 结果，并记下每次调用——<b>连同发起那一刻调用线程上的 Dubbo 附件</b>（预算 {@code xm-budget-ms} 与调用级超时），
 * 这样「每跳超时 = min(3 s, 剩余预算)，并把这同一个值当预算带给 xm-match」能在不起 Dubbo 的单测里断言。
 *
 * <p>预检是提供方逻辑的简化模型：副本人数（{@link #sizes}，没配 = 未开放）→ 按名单顺序找第一个 {@link #offline} / {@link #inBattle} /
 * {@link #notReady} 的人。四个方法的应答都可以整体顶替（{@code xxxReply}），用来制造传输失败（异常完成的 future）、永不完成、缺字段的应答。
 * 线程安全。
 */
public final class FakeMatchTeamService implements MatchTeamService {

    /**
     * 一次调用。
     *
     * @param request   请求
     * @param budgetMs  调用线程上的 {@code xm-budget-ms} 附件（十进制毫秒）；没带为 null
     * @param timeoutMs 调用线程上的调用级 {@code timeout} 附件（毫秒）；没带为 null
     */
    public record Call<T>(T request, String budgetMs, Long timeoutMs) {

        public long budget() {
            return Long.parseLong(budgetMs);
        }
    }

    public final List<Call<TeamMatchCheckRequest>> checks = Collections.synchronizedList(new ArrayList<>());
    public final List<Call<TeamTicketsRequest>> tickets = Collections.synchronizedList(new ArrayList<>());
    public final List<Call<TeamTicketsRelease>> releases = Collections.synchronizedList(new ArrayList<>());
    public final List<Call<TeamGatherRequest>> gathers = Collections.synchronizedList(new ArrayList<>());

    /** 副本号 → 组队人数上限；没有的副本 = 未开放组队。 */
    public final Map<Integer, Integer> sizes = new ConcurrentHashMap<>();
    public final Set<Long> offline = ConcurrentHashMap.newKeySet();
    public final Set<Long> inBattle = ConcurrentHashMap.newKeySet();
    public final Set<Long> notReady = ConcurrentHashMap.newKeySet();
    /** 预检通过时每人的 zone。 */
    public volatile int zone = 1;
    /** 预检通过时回的开战锁时长（秒）。 */
    public volatile int lockTtlSeconds = 83;

    /** 非 null 时整体顶替预检的应答（传输失败、缺字段等）。 */
    public volatile Function<TeamMatchCheckRequest, CompletableFuture<TeamMatchCheckReply>> checkReply;
    /** 预检通过、应答返回之前调用：确定性地在「预检与加锁之间」插入并发（基线 afterPreflightHook）。 */
    public volatile Consumer<TeamMatchCheckRequest> afterCheckPassed = request -> {
    };
    public volatile Function<TeamTicketsRequest, CompletableFuture<TeamTicketsReply>> ticketsReply =
            request -> done(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_CREATED).build());
    public volatile Function<TeamTicketsRelease, CompletableFuture<Empty>> releaseReply = request -> done(Empty.getDefaultInstance());
    public volatile Function<TeamGatherRequest, CompletableFuture<TeamGatherReply>> gatherReply = request -> done(gathered(true, 9001));

    /** 开放一个副本的组队。 */
    public FakeMatchTeamService open(int battleConfigId, int teamSize) {
        sizes.put(battleConfigId, teamSize);
        return this;
    }

    /** 之后的建票一律回 FAILED + 这个人。 */
    public void failTickets(long playerId) {
        ticketsReply = request -> done(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_FAILED)
                .setFailedPlayerId(playerId).build());
    }

    @Override
    public CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request) {
        checks.add(call(request));
        Function<TeamMatchCheckRequest, CompletableFuture<TeamMatchCheckReply>> override = checkReply;
        if (override != null) {
            return override.apply(request);
        }
        Integer size = sizes.get(request.getBattleConfigId());
        if (size == null) {
            return done(rejected(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN, 0));
        }
        if (request.getRosterCount() > size) {
            return done(rejected(TeamMatchCheckResult.TEAM_MATCH_CHECK_SIZE_EXCEEDED, 0));
        }
        TeamMatchCheckReply.Builder ok = TeamMatchCheckReply.newBuilder().setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK)
                .setLockTtlSeconds(lockTtlSeconds);
        for (long pid : request.getRosterList()) {
            if (offline.contains(pid)) {
                return done(rejected(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE, pid));
            }
            if (inBattle.contains(pid)) {
                return done(rejected(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_IN_BATTLE, pid));
            }
            if (notReady.contains(pid)) {
                return done(rejected(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_NOT_READY, pid));
            }
            ok.putZones(pid, zone);
        }
        afterCheckPassed.accept(request);
        return done(ok.build());
    }

    @Override
    public CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request) {
        tickets.add(call(request));
        return ticketsReply.apply(request);
    }

    @Override
    public CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request) {
        releases.add(call(request));
        return releaseReply.apply(request);
    }

    @Override
    public CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request) {
        gathers.add(call(request));
        return gatherReply.apply(request);
    }

    /** 每次预检收到的名单（按调用顺序）。 */
    public List<List<Long>> checkedRosters() {
        synchronized (checks) {
            return checks.stream().map(c -> c.request().getRosterList()).map(List::copyOf).toList();
        }
    }

    public static TeamMatchCheckReply rejected(TeamMatchCheckResult result, long offender) {
        return TeamMatchCheckReply.newBuilder().setResult(result).setOffender(offender).build();
    }

    public static TeamGatherReply gathered(boolean ok, long battleId) {
        return TeamGatherReply.newBuilder().setOk(ok).setOutcome(ok ? "success" : "prepare_failed").setBattleId(ok ? battleId : 0)
                .build();
    }

    public static <T> CompletableFuture<T> done(T reply) {
        return CompletableFuture.completedFuture(reply);
    }

    /** 传输失败：异常完成的 future。 */
    public static <T> CompletableFuture<T> broken(String why) {
        return CompletableFuture.failedFuture(new IllegalStateException(why));
    }

    private static <T> Call<T> call(T request) {
        Object timeout = RpcContext.getClientAttachment().getObjectAttachment(CommonConstants.TIMEOUT_KEY);
        return new Call<>(request, RpcContext.getClientAttachment().getAttachment(MatchRpcAttachments.BUDGET_MS),
                timeout == null ? null : Long.valueOf(String.valueOf(timeout)));
    }
}
