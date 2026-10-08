package com.game.match.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.game.common.id.Snowflake;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchWorkerPool;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.gather.FailPolicy;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.precheck.DefaultMemberPrecheck;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.precheck.MemberPrecheck.Reason;
import com.game.match.rating.RatingReader;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakeMemberPrecheck;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FakeTicketHealing;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.ForwardingTicketStore;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import com.game.proto.Empty;
import com.game.proto.match.MatchMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 整队开战的 match 一侧（match-spec §7.5、§15.2；对照基线 {@code logic/team_battle_test.go}）：副本人数与收口、预检结论的翻译（读锁失败按
 * 「已在战斗」）、交错顺序、租约、按调用方的票号原子建票、冲突与结局不明的回滚、预算过期不写、过载、释放幂等、gather 保持名单顺序。
 * 票据存储用内存实现，预检 / 开局管线 / 发号租约是替身；工作池缺省用当场执行的。
 */
class MatchTeamServiceImplTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final long C = 1003;
    private static final long TEAM = 880001;

    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    private final FakeMemberPrecheck precheck = new FakeMemberPrecheck();
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private final AtomicBoolean leaseLost = new AtomicBoolean(false);
    private final MatchIds ids = new MatchIds(new Snowflake(5), leaseValid::get, leaseLost::get);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    /** 副本 1 配 5 人、副本 2 配 9 人（按 5 收口）、副本 3 配 2 人；其余未开放。 */
    private final MatchProperties props = new MatchProperties(null, null, null, null, null, null, null, Map.of(1, 5, 2, 9, 3, 2), null, null, null,
            null);
    private final AtomicInteger submitted = new AtomicInteger();
    private final MatchWorkers inline = task -> {
        submitted.incrementAndGet();
        task.run();
    };
    private final MatchTeamServiceImpl service = service(inline, precheck, tickets);

    private MatchTeamServiceImpl service(MatchWorkers workers, MemberPrecheck check, TicketStore store) {
        return new MatchTeamServiceImpl(workers, props, check, store, gather, ids, metrics);
    }

    @AfterEach
    void clearAttachment() {
        RpcContext.getServerAttachment().removeAttachment(MatchRpcAttachments.BUDGET_MS);
    }

    private static Deadline d() {
        return Deadline.after(3_000);
    }

    private static TeamMatchCheckRequest checkRequest(int config, Long... roster) {
        return TeamMatchCheckRequest.newBuilder().setBattleConfigId(config).addAllRoster(List.of(roster)).build();
    }

    private static TeamTicketsRequest.Builder ticketsRequest(Long... roster) {
        TeamTicketsRequest.Builder request = TeamTicketsRequest.newBuilder().setBattleConfigId(1).setTeamId(TEAM).addAllRoster(List.of(roster));
        for (long playerId : roster) {
            request.putTicketIds(playerId, "t-" + playerId).putZones(playerId, (int) (playerId % 10));
        }
        return request;
    }

    private static Ticket otherTicket(String ticketId) {
        return new Ticket(ticketId, 3, 0, TicketState.MATCHED, 1, 1, "", RatingReader.DEFAULT_CENTI, 0, 0, 0);
    }

    private double count(String method, String result) {
        return meters.get("xm.match.team.calls").tags("method", method, "result", result).counter().count();
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    // ================================================================ checkTeamMatch

    @Test
    void 检查_未配置组队人数的副本_DUNGEON_NOT_OPEN_不做预检() {
        TeamMatchCheckReply reply = service.check(checkRequest(9, A, B), d());

        assertThat(reply.getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN);
        assertThat(reply.getOffender()).isZero();
        assertThat(reply.getZonesMap()).isEmpty();
        assertThat(reply.getLockTtlSeconds()).isZero();
        assertThat(precheck.rosters).isEmpty();
        assertThat(count("checkTeamMatch", "dungeon_not_open")).isEqualTo(1);
    }

    @Test
    void 检查_人数上限是配置值按5收口_超员_SIZE_EXCEEDED_不做预检() {
        assertThat(service.check(checkRequest(3, A, B, C), d()).getResult()).as("副本 3 配 2 人，来了 3 人")
                .isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_SIZE_EXCEEDED);
        assertThat(service.check(checkRequest(2, 1L, 2L, 3L, 4L, 5L, 6L), d()).getResult()).as("副本 2 配 9 人但按 5 收口，来了 6 人")
                .isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_SIZE_EXCEEDED);
        assertThat(precheck.rosters).isEmpty();

        assertThat(service.check(checkRequest(2, 1L, 2L, 3L, 4L, 5L), d()).getResult()).as("恰好 5 人可以")
                .isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK);
        assertThat(service.check(checkRequest(3, A), d()).getResult()).as("人数少于上限允许开战").isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK);
        assertThat(count("checkTeamMatch", "size_exceeded")).isEqualTo(2);
    }

    @Test
    void 检查_通过_回每人的zone与开战锁时长_名单原序交给预检() {
        precheck.zone(A, 4).zone(B, 9);

        TeamMatchCheckReply reply = service.check(checkRequest(1, B, A), d());

        assertThat(reply.getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK);
        assertThat(reply.getOffender()).isZero();
        assertThat(reply.getZonesMap()).containsOnly(Map.entry(A, 4), Map.entry(B, 9));
        assertThat(reply.getLockTtlSeconds()).as("2 人：matched 48 + 补偿 16 + 10").isEqualTo(74);
        assertThat(precheck.rosters).containsExactly(List.of(B, A));
        assertThat(count("checkTeamMatch", "ok")).isEqualTo(1);
    }

    @Test
    void 检查_开战锁时长按名单人数_65_74_83_92_101() {
        Long[] five = {1L, 2L, 3L, 4L, 5L};
        int[] expected = {65, 74, 83, 92, 101};
        for (int n = 1; n <= 5; n++) {
            Long[] roster = java.util.Arrays.copyOf(five, n);
            assertThat(service.check(checkRequest(1, roster), d()).getLockTtlSeconds()).as("%d 人", n).isEqualTo(expected[n - 1]);
        }
    }

    @Test
    void 检查_预检结论的翻译_读战斗锁出错按已在战斗_其余读失败与预算用完是INTERNAL且不带offender() {
        record Case(Reason reason, TeamMatchCheckResult result, long offender, String metric) {
        }
        List<Case> cases = List.of(
                new Case(Reason.OFFLINE, TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE, B, "member_offline"),
                new Case(Reason.IN_BATTLE, TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_IN_BATTLE, B, "member_in_battle"),
                new Case(Reason.LOCK_READ_FAILED, TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_IN_BATTLE, B, "member_in_battle"),
                new Case(Reason.NO_LOCATION, TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_NOT_READY, B, "member_not_ready"),
                new Case(Reason.TICKET_IN_FLIGHT, TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_NOT_READY, B, "member_not_ready"),
                new Case(Reason.PRESENCE_READ_FAILED, TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0, "internal"),
                new Case(Reason.LOCATION_READ_FAILED, TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0, "internal"),
                new Case(Reason.TICKET_READ_FAILED, TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0, "internal"),
                new Case(Reason.DEADLINE_EXPIRED, TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL, 0, "internal"));
        for (Case c : cases) {
            precheck.fail(c.reason(), B);

            TeamMatchCheckReply reply = service.check(checkRequest(1, A, B), d());

            assertThat(reply.getResult()).as(c.reason().name()).isEqualTo(c.result());
            assertThat(reply.getOffender()).as(c.reason().name()).isEqualTo(c.offender());
            assertThat(reply.getZonesMap()).isEmpty();
            assertThat(reply.getLockTtlSeconds()).isZero();
        }
        assertThat(count("checkTeamMatch", "member_offline")).isEqualTo(1);
        assertThat(count("checkTeamMatch", "member_in_battle")).isEqualTo(2);
        assertThat(count("checkTeamMatch", "member_not_ready")).isEqualTo(2);
        assertThat(count("checkTeamMatch", "internal")).isEqualTo(4);
    }

    @Test
    void 检查_交错顺序_成员1有在途票_成员2离线_结论是成员1没准备好() {
        FakePlayerStatus players = new FakePlayerStatus().online(A, 1, 7).disconnected(B);
        FakeTicketHealing healing = new FakeTicketHealing().inFlight(A, "t-queued");
        MatchTeamServiceImpl real = service(inline, new DefaultMemberPrecheck(players, healing), tickets);

        TeamMatchCheckReply reply = real.check(checkRequest(1, A, B), d());

        assertThat(reply.getResult()).as("预检整个在 match 里按人交错着做：不是先查全员在线").isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_NOT_READY);
        assertThat(reply.getOffender()).isEqualTo(A);
    }

    @Test
    void 检查_发号租约无效_INTERNAL_只拦本来会放行的请求_其它拒绝的可见结果不变() {
        leaseValid.set(false);

        assertThat(service.check(checkRequest(1, A, B), d()).getResult()).as("本来会 OK：不让必败的开战先加锁")
                .isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL);
        assertThat(service.check(checkRequest(9, A, B), d()).getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_DUNGEON_NOT_OPEN);
        precheck.fail(Reason.OFFLINE, B);
        TeamMatchCheckReply offline = service.check(checkRequest(1, A, B), d());
        assertThat(offline.getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_MEMBER_OFFLINE);
        assertThat(offline.getOffender()).isEqualTo(B);

        leaseLost.set(true);
        precheck.pass();
        assertThat(service.check(checkRequest(1, A, B), d()).getResult()).as("租约已丢失同样拒").isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL);
    }

    @Test
    void 检查_名单为空或含0或重复_INTERNAL_不做预检() {
        assertThat(service.check(checkRequest(1), d()).getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL);
        assertThat(service.check(checkRequest(1, A, 0L), d()).getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL);
        assertThat(service.check(checkRequest(1, A, B, A), d()).getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL);
        assertThat(precheck.rosters).isEmpty();
    }

    @Test
    void 检查_Dubbo入口_投到工作池_过载或到达时预算已过期回INTERNAL_不让调用悬着() throws Exception {
        assertThat(get(service.checkTeamMatch(checkRequest(1, A, B))).getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK);
        assertThat(submitted.get()).as("阻塞的预检不在 Dubbo 线程上做").isEqualTo(1);

        MatchTeamServiceImpl full = service(task -> {
            throw new RejectedExecutionException("满了");
        }, precheck, tickets);
        assertThat(get(full.checkTeamMatch(checkRequest(1, A, B))).getResult()).isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL);

        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "0");
        assertThat(get(service.checkTeamMatch(checkRequest(1, A, B))).getResult()).as("调用方发出时就没有预算了")
                .isEqualTo(TeamMatchCheckResult.TEAM_MATCH_CHECK_INTERNAL);

        assertThat(precheck.rosters).as("只有第一次真的做了预检").hasSize(1);
        assertThat(count("checkTeamMatch", "overloaded")).isEqualTo(2);
    }

    // ================================================================ createTeamTickets

    @Test
    void 建票_按调用方给的票号原子建全员的matched票_带team_id_不入队_TTL按人数() {
        TeamTicketsRequest request = ticketsRequest(B, A, C).removeZones(C).build();

        TeamTicketsReply reply = service.create(request, d());

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        assertThat(reply.getFailedPlayerId()).isZero();
        for (long playerId : List.of(A, B, C)) {
            Ticket ticket = tickets.ticketOf(playerId).orElseThrow();
            assertThat(ticket.ticketId()).as("票号是 xm-team 给的那个").isEqualTo("t-" + playerId);
            assertThat(ticket.state()).isEqualTo(TicketState.MATCHED);
            assertThat(ticket.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM_VALUE);
            assertThat(ticket.configId()).isEqualTo(1);
            assertThat(ticket.teamId()).isEqualTo(TEAM);
            assertThat(ticket.queueKey()).as("不入队").isEmpty();
            assertThat(ticket.zoneId()).as("zone 取请求里带回来的；缺的人按 0").isEqualTo(playerId == C ? 0 : (int) (playerId % 10));
            assertThat(tickets.ttlMs(playerId)).as("3 人的 matched TTL = 54 s").isEqualTo(54_000);
        }
        assertThat(tickets.queueIndex(d())).as("不登记任何队列").isEmpty();
        assertThat(tickets.calls).as("一次原子建票，名单原序").contains("createGroup([1002, 1001, 1003])");
        assertThat(count("createTeamTickets", "created")).isEqualTo(1);
    }

    @Test
    void 建票_有人已有别的票_什么都不写_回名单序第一个冲突者() {
        tickets.putTicket(C, otherTicket("someone-else-c"), 60_000);
        tickets.putTicket(B, otherTicket("someone-else-b"), 60_000);

        TeamTicketsReply reply = service.create(ticketsRequest(A, B, C).build(), d());

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_FAILED);
        assertThat(reply.getFailedPlayerId()).as("B 与 C 都冲突：取名单里靠前的 B").isEqualTo(B);
        assertThat(tickets.ticketOf(A)).as("原子：没冲突的人也没建").isEmpty();
        assertThat(tickets.ticketOf(B).orElseThrow().ticketId()).as("别人的票不动").isEqualTo("someone-else-b");
        assertThat(tickets.ticketOf(C).orElseThrow().ticketId()).isEqualTo("someone-else-c");
        assertThat(tickets.calls).as("冲突不需要回滚").noneMatch(call -> call.startsWith("delete"));
        assertThat(count("createTeamTickets", "failed")).isEqualTo(1);
    }

    @Test
    void 建票_同一批票号重发_当作重放_CREATED_不重写() {
        TeamTicketsRequest request = ticketsRequest(A, B).build();
        assertThat(service.create(request, d()).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        clock.advanceSeconds(10);

        assertThat(service.create(request, d()).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);

        assertThat(tickets.ttlMs(A)).as("重放不续期").isEqualTo(38_000);
        assertThat(tickets.ticketCount()).isEqualTo(2);
    }

    @Test
    void 建票_结局不明_其实已写入_按本次票号逐个回滚_回FAILED与队长() {
        tickets.faults.failNext("createGroup:after");

        TeamTicketsReply reply = service.create(ticketsRequest(B, A, C).build(), d());

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_FAILED);
        assertThat(reply.getFailedPlayerId()).as("Redis 出错没有「第几个人」：记在名单第一个人身上（→ 4026[队长]，同基线最常见的情形）").isEqualTo(B);
        assertThat(tickets.ticketCount()).as("已写入的票被回滚干净").isZero();
        assertThat(tickets.calls).containsExactly("createGroup([1002, 1001, 1003])", "delete(1002)", "delete(1001)", "delete(1003)");
        assertThat(count("createTeamTickets", "failed")).isEqualTo(1);
    }

    @Test
    void 建票_结局不明_回滚只删本次的票号_单张删失败不影响其余() {
        // 建票在执行之前就失败（什么都没写）；A 手里其实是别人的票：回滚不许误删
        tickets.putTicket(A, otherTicket("someone-else-a"), 60_000);
        tickets.faults.failNext("createGroup");
        tickets.faults.failNext("delete");

        TeamTicketsReply reply = service.create(ticketsRequest(B, A, C).build(), d());

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_FAILED);
        assertThat(reply.getFailedPlayerId()).isEqualTo(B);
        assertThat(tickets.calls).as("第一张删票出错，后两张照删").containsExactly("createGroup([1002, 1001, 1003])", "delete(1002)", "delete(1001)",
                "delete(1003)");
        assertThat(tickets.ticketOf(A).orElseThrow().ticketId()).as("票号不是本次的：不删").isEqualTo("someone-else-a");
    }

    @Test
    void 建票_回滚用独立的3秒预算_不受快用完的请求截止约束() {
        Map<String, List<Deadline>> seen = new ConcurrentHashMap<>();
        MatchTeamServiceImpl recording = service(inline, precheck, recordingDeadlines(tickets, seen));
        tickets.faults.failNext("createGroup:after");
        Deadline request = Deadline.after(200);

        TeamTicketsReply reply = recording.create(ticketsRequest(A, B).build(), request);

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_FAILED);
        assertThat(seen.get("createGroup")).as("建票本身用请求的截止").containsExactly(request);
        assertThat(seen.get("delete")).hasSize(2).allSatisfy(deadline -> {
            assertThat(deadline).isNotSameAs(request);
            assertThat(deadline.remainingMillis()).as("独立预算 3 s").isBetween(2_000L, 3_000L);
        });
        assertThat(tickets.ticketCount()).isZero();
    }

    @Test
    void 建票_到达时预算已过期_不写任何东西_EXPIRED_不进工作池() throws Exception {
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "0");

        TeamTicketsReply reply = get(service.createTeamTickets(ticketsRequest(A, B).build()));

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(reply.getFailedPlayerId()).isZero();
        assertThat(tickets.calls).isEmpty();
        assertThat(submitted.get()).isZero();
        assertThat(count("createTeamTickets", "expired")).isEqualTo(1);
    }

    @Test
    void 建票_带着足够的预算经Dubbo入口_投到工作池建成() throws Exception {
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "2500");

        TeamTicketsReply reply = get(service.createTeamTickets(ticketsRequest(A, B).build()));

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        assertThat(submitted.get()).isEqualTo(1);
        assertThat(tickets.ticketCount()).isEqualTo(2);
    }

    @Test
    void 建票_工作池满或在队列里等过了预算_没有执行_EXPIRED_什么都不写() throws Exception {
        MatchTeamServiceImpl full = service(task -> {
            throw new RejectedExecutionException("满了");
        }, precheck, tickets);
        assertThat(get(full.createTeamTickets(ticketsRequest(A, B).build())).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);

        Deque<Runnable> queued = new ArrayDeque<>();
        MatchTeamServiceImpl slow = service(queued::add, precheck, tickets);
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "60");
        CompletableFuture<TeamTicketsReply> waiting = slow.createTeamTickets(ticketsRequest(A, B).build());
        assertThat(waiting).as("还在队列里").isNotDone();
        TimeUnit.MILLISECONDS.sleep(120);
        queued.remove().run();

        assertThat(get(waiting).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(tickets.calls).isEmpty();
        assertThat(count("createTeamTickets", "overloaded")).isEqualTo(2);
    }

    /**
     * xm-team 带来的预算是「这一跳的超时」（生产是 min(3 s, 剩余请求预算)），比本进程自己的整请求预算（4.5 s）短：截止必须按附件定、
     * 从受理时刻起算、把排队时间算进去——否则 xm-team 那边已经超时放弃（并且退完了票），这边才出队把票建出来，全员的 matched 票留到 TTL。
     * 走真的工作池（单线程、被占住），同时排着两条：预算不够的那条出队后不执行，预算够的那条照常建成（证明差别只在预算，不是排队本身）。
     */
    @Test
    void 建票_在真的工作池里排队超过了附件给的预算_EXPIRED_什么都没写_同样排着但预算够的那条照常建成() throws Exception {
        try (MatchWorkerPool pool = new MatchWorkerPool(1, 8, Duration.ofSeconds(2))) {
            MatchTeamServiceImpl pooled = service(pool, precheck, tickets);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch occupied = new CountDownLatch(1);
            pool.execute(() -> {
                occupied.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(occupied.await(5, TimeUnit.SECONDS)).as("唯一的工作线程已被占住").isTrue();

            RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "200");
            CompletableFuture<TeamTicketsReply> starved = pooled.createTeamTickets(ticketsRequest(A, B).build());
            RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "4000");
            CompletableFuture<TeamTicketsReply> patient = pooled.createTeamTickets(ticketsRequest(C).setTeamId(TEAM + 1).build());
            assertThat(starved).as("进了队列，还没轮到").isNotDone();
            assertThat(patient).isNotDone();
            TimeUnit.MILLISECONDS.sleep(400); // 两条都排了 400 ms：超过第一条的 200 ms，远没到第二条的 4 s
            release.countDown();

            TeamTicketsReply expired = get(starved);
            assertThat(expired.getStatus()).as("不回 FAILED：那会让客户端看到 4026[队长]").isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
            assertThat(expired.getFailedPlayerId()).isZero();
            assertThat(get(patient).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
            assertThat(tickets.ticketOf(A)).as("超预算的那条什么都没写").isEmpty();
            assertThat(tickets.ticketOf(B)).isEmpty();
            assertThat(tickets.ticketOf(C)).isPresent();
            assertThat(tickets.calls).as("超预算的那条没有碰过存储").containsExactly("createGroup([1003])");
            assertThat(count("createTeamTickets", "overloaded")).isEqualTo(1);
            assertThat(count("createTeamTickets", "created")).isEqualTo(1);
            assertThat(count("createTeamTickets", "expired")).as("「到达时已过期」是另一个出口，这里没走到").isZero();
        }
    }

    @Test
    void 建票_发号租约无效_不建票_按没有执行回() {
        leaseValid.set(false);

        TeamTicketsReply reply = service.create(ticketsRequest(A, B).build(), d());

        assertThat(reply.getStatus()).as("不回 FAILED：那会让客户端看到 4026[队长]").isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(tickets.calls).isEmpty();
    }

    @Test
    void 建票_名单不合法或有人缺票号_不建票() {
        assertThat(service.create(TeamTicketsRequest.newBuilder().setBattleConfigId(1).build(), d()).getStatus())
                .isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(service.create(ticketsRequest(A, B).removeTicketIds(B).build(), d()).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(service.create(ticketsRequest(A, B).putTicketIds(B, "").build(), d()).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(service.create(ticketsRequest(A, B, A).build(), d()).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(service.create(ticketsRequest(1L, 2L, 3L, 4L, 5L, 6L).build(), d()).getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(tickets.calls).isEmpty();
        assertThat(count("createTeamTickets", "error")).isEqualTo(5);
    }

    // ================================================================ releaseTeamTickets

    @Test
    void 释放_只删票号一致的票_幂等_一次原子调用() throws Exception {
        service.create(ticketsRequest(A, B, C).build(), d());
        tickets.putTicket(B, otherTicket("requeued-b"), 60_000);
        clock.advanceSeconds(1);
        tickets.calls.clear();
        TeamTicketsRelease release = TeamTicketsRelease.newBuilder().putTicketIds(A, "t-1001").putTicketIds(B, "t-1002").putTicketIds(1009L, "t-1009")
                .build();

        assertThat(get(service.releaseTeamTickets(release))).isEqualTo(Empty.getDefaultInstance());
        assertThat(get(service.releaseTeamTickets(release))).as("再来一次不算错").isEqualTo(Empty.getDefaultInstance());

        assertThat(tickets.ticketOf(A)).isEmpty();
        assertThat(tickets.ticketOf(B).orElseThrow().ticketId()).as("B 已经换了新票：不删").isEqualTo("requeued-b");
        assertThat(tickets.ticketOf(C)).as("没点名的人不动").isPresent();
        assertThat(tickets.calls).hasSize(2).allMatch(call -> call.startsWith("deleteGroup("));
        assertThat(count("releaseTeamTickets", "ok")).isEqualTo(2);
    }

    @Test
    void 释放_空请求与非法项直接跳过_不碰存储() throws Exception {
        TeamTicketsRelease release = TeamTicketsRelease.newBuilder().putTicketIds(0L, "t-0").putTicketIds(A, "").build();

        assertThat(get(service.releaseTeamTickets(release))).isEqualTo(Empty.getDefaultInstance());
        assertThat(get(service.releaseTeamTickets(TeamTicketsRelease.getDefaultInstance()))).isEqualTo(Empty.getDefaultInstance());

        assertThat(tickets.calls).isEmpty();
    }

    @Test
    void 释放_存储出错或工作池满_future异常完成_调用方据此记日志() {
        service.create(ticketsRequest(A).build(), d());
        TeamTicketsRelease release = TeamTicketsRelease.newBuilder().putTicketIds(A, "t-1001").build();
        tickets.faults.failNext("deleteGroup");

        assertThatThrownBy(() -> get(service.releaseTeamTickets(release))).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(Deadline.DependencyException.class);
        assertThat(tickets.ticketOf(A)).as("没删成：票留到 matched TTL").isPresent();

        MatchTeamServiceImpl full = service(task -> {
            throw new RejectedExecutionException("满了");
        }, precheck, tickets);
        assertThatThrownBy(() -> get(full.releaseTeamTickets(release))).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(count("releaseTeamTickets", "error")).isEqualTo(1);
        assertThat(count("releaseTeamTickets", "overloaded")).isEqualTo(1);
    }

    /**
     * 退票是 xm-team 在建票结果不明之后发来的补偿，它那一跳的预算（至多 3 s）可能正好在本进程的工作队列里耗尽。沿用那份预算的话，
     * 真的存储会当场拒发（{@code RedissonTicketStore}：截止已过不发命令），票要留到 matched TTL 才自灭。这里的存储替身照真存储那样拒收过期的截止。
     */
    @Test
    void 释放_在工作队列里等过了调用方给的预算_出队后照样删_用的是独立的3秒预算() throws Exception {
        service.create(ticketsRequest(A, B).build(), d());
        tickets.calls.clear();
        List<Long> remainingAtDelete = new CopyOnWriteArrayList<>();
        Deque<Runnable> queued = new ArrayDeque<>();
        MatchTeamServiceImpl slow = service(queued::add, precheck, refusingExpiredDeletes(remainingAtDelete));
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "60");

        CompletableFuture<Empty> waiting = slow.releaseTeamTickets(
                TeamTicketsRelease.newBuilder().putTicketIds(A, "t-1001").putTicketIds(B, "t-1002").build());
        assertThat(waiting).as("还在队列里").isNotDone();
        TimeUnit.MILLISECONDS.sleep(120); // 调用方给的 60 ms 在队列里耗尽了
        queued.remove().run();

        assertThat(get(waiting)).isEqualTo(Empty.getDefaultInstance());
        assertThat(tickets.ticketCount()).as("两张票都删掉了，不用等 matched TTL").isZero();
        assertThat(tickets.calls).containsExactly("deleteGroup([1001, 1002])");
        assertThat(remainingAtDelete).singleElement().satisfies(remaining ->
                assertThat(remaining).as("独立的 3 s 预算，从出队那一刻起算（不是调用方那份已经耗尽的 60 ms）").isBetween(2_000L, 3_000L));
        assertThat(count("releaseTeamTickets", "ok")).isEqualTo(1);
        assertThat(count("releaseTeamTickets", "error")).isZero();
    }

    @Test
    void 释放_调用方带来的预算是0也照删_不看附件() throws Exception {
        service.create(ticketsRequest(A).build(), d());
        List<Long> remainingAtDelete = new CopyOnWriteArrayList<>();
        MatchTeamServiceImpl strict = service(inline, precheck, refusingExpiredDeletes(remainingAtDelete));
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "0");

        assertThat(get(strict.releaseTeamTickets(TeamTicketsRelease.newBuilder().putTicketIds(A, "t-1001").build()))).isEqualTo(Empty.getDefaultInstance());

        assertThat(tickets.ticketOf(A)).isEmpty();
        assertThat(remainingAtDelete).singleElement().satisfies(remaining -> assertThat(remaining).isBetween(2_000L, 3_000L));
    }

    /** 像真的存储那样对待截止的票据存储：删一组票时截止已过就不发、直接抛依赖异常；顺带记下每次删票那一刻截止还剩多少毫秒。 */
    private TicketStore refusingExpiredDeletes(List<Long> remainingAtDelete) {
        return new ForwardingTicketStore(tickets) {
            @Override
            public int deleteGroup(List<TicketRef> refs, Deadline deadline) {
                remainingAtDelete.add(deadline.remainingMillis());
                if (deadline.expired()) {
                    throw new Deadline.DependencyException("删票 之前请求预算已用完（没有发出）");
                }
                return super.deleteGroup(refs, deadline);
            }
        };
    }

    // ================================================================ runTeamGather

    @Test
    void 开局_名单原序与票号交给管线_失败全员删票的策略_gather结束时future才完成_不占工作池() throws Exception {
        gather.hold();
        TeamGatherRequest request = TeamGatherRequest.newBuilder().setBattleConfigId(1).setTeamId(TEAM).addAllRoster(List.of(C, A, B))
                .putTicketIds(A, "t-1001").putTicketIds(B, "t-1002").putTicketIds(C, "t-1003").build();

        CompletableFuture<TeamGatherReply> reply = service.runTeamGather(request);

        assertThat(reply).as("长挂：gather 没结束就不完成").isNotDone();
        assertThat(submitted.get()).as("只登记 future 就返回").isZero();
        assertThat(gather.plans).hasSize(1);
        GatherPlan plan = gather.plans.get(0);
        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
        assertThat(plan.battleConfigId()).isEqualTo(1);
        assertThat(plan.members()).as("站位顺序 = 名单顺序（队长在前）").containsExactly(C, A, B);
        assertThat(plan.tickets()).containsExactly(Map.entry(C, "t-1003"), Map.entry(A, "t-1001"), Map.entry(B, "t-1002"));
        assertThat(plan.onFail()).isEqualTo(FailPolicy.DELETE_ALL);
        assertThat(plan.presetBattleId()).isZero();
        assertThat(plan.activityContext()).isNull();

        gather.complete(0, GatherResult.success(77001));

        assertThat(get(reply)).isEqualTo(TeamGatherReply.newBuilder().setOk(true).setOutcome("success").setBattleId(77001).build());
        assertThat(count("runTeamGather", "gather_ok")).isEqualTo(1);
    }

    @Test
    void 开局_gather失败_ok为false带结局标签_不带battle_id() throws Exception {
        gather.nextResult(GatherResult.failed(GatherOutcome.CREATE_FAILED_ROOM_ALIVE, 77002));
        TeamGatherRequest request = TeamGatherRequest.newBuilder().setBattleConfigId(1).setTeamId(TEAM).addRoster(A).putTicketIds(A, "t-1001").build();

        TeamGatherReply reply = get(service.runTeamGather(request));

        assertThat(reply).isEqualTo(TeamGatherReply.newBuilder().setOk(false).setOutcome("create_failed_room_alive").build());
        assertThat(count("runTeamGather", "gather_failed")).isEqualTo(1);
    }

    @Test
    void 开局_租约丢失或过载由管线自己收尾_应答照实回() throws Exception {
        gather.permits(0);
        TeamGatherRequest request = TeamGatherRequest.newBuilder().setBattleConfigId(1).setTeamId(TEAM).addRoster(A).putTicketIds(A, "t-1001").build();

        assertThat(get(service.runTeamGather(request))).isEqualTo(TeamGatherReply.newBuilder().setOk(false).setOutcome("overloaded").build());
    }

    @Test
    void 开局_名单与票号对不上_不开局_回失败() throws Exception {
        TeamGatherRequest missing = TeamGatherRequest.newBuilder().setBattleConfigId(1).setTeamId(TEAM).addAllRoster(List.of(A, B))
                .putTicketIds(A, "t-1001").build();
        TeamGatherRequest empty = TeamGatherRequest.newBuilder().setBattleConfigId(1).setTeamId(TEAM).build();

        assertThat(get(service.runTeamGather(missing))).isEqualTo(TeamGatherReply.newBuilder().setOk(false).setOutcome("internal").build());
        assertThat(get(service.runTeamGather(empty))).isEqualTo(TeamGatherReply.newBuilder().setOk(false).setOutcome("internal").build());

        assertThat(gather.plans).isEmpty();
        assertThat(count("runTeamGather", "gather_failed")).isEqualTo(2);
    }

    /** 记下每个方法收到的 {@link Deadline}，其余原样委托。 */
    private static TicketStore recordingDeadlines(TicketStore delegate, Map<String, List<Deadline>> seen) {
        return (TicketStore) Proxy.newProxyInstance(TicketStore.class.getClassLoader(), new Class<?>[] {TicketStore.class}, (proxy, method, args) -> {
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof Deadline deadline) {
                        seen.computeIfAbsent(method.getName(), k -> new CopyOnWriteArrayList<>()).add(deadline);
                    }
                }
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
