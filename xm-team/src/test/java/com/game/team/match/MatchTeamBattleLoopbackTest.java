package com.game.team.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.DubboGroups;
import com.game.api.MatchTeamService;
import com.game.api.asset.IsolatedDubboModule;
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
import com.game.proto.Empty;
import com.game.team.match.TeamBattlePort.Check;
import com.game.team.match.TeamBattlePort.Gather;
import com.game.team.match.TeamBattlePort.Tickets;
import com.game.team.rules.TeamTips;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link MatchTeamBattle} 走<b>真 Triple</b>（缺省执行，不需要外部依赖）：假的 xm-match 提供方与调用方各在自己的 Dubbo 框架模型里
 * （等价于两个进程），调用方鉴权过滤器照常生效（测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）。引用的参数照
 * {@code TeamDubboConfiguration}：group match、不重试；<b>引用上的缺省超时故意设得很小（300 ms）</b>，这样才能看出每次调用带的
 * 调用级超时确实生效——前三个方法取 min(3 s, 剩余预算)，{@code runTeamGather} 取开战锁时长。
 *
 * <p>钉住的是「线上才看得到」的那几件事：剩余预算过线到了提供方、调用级超时压得过引用缺省值、长挂的 gather 不被缺省超时掐断、
 * 超过锁时长才判结果不明、对端不在时四个方法各自落到约定的失败形态。
 */
class MatchTeamBattleLoopbackTest {

    private static final long A = Long.MIN_VALUE + 201, B = 202;
    private static final List<Long> ROSTER = List.of(A, B);
    private static final Map<Long, String> TICKETS = Map.of(A, "ticket-a", B, "ticket-b");
    /** 引用上的缺省超时：比下面任何一次「慢应答」都短。 */
    private static final int REFERENCE_DEFAULT_TIMEOUT_MS = 300;

    private static final Provider PROVIDER = new Provider();
    private static IsolatedDubboModule server;
    private static IsolatedDubboModule client;
    private static MatchTeamBattle battle;
    private static MatchTeamBattle unreachable;

    /** 假的 xm-match：按设定的毫秒数延迟应答，并记下在方法入口（Dubbo 线程）读到的预算附件。 */
    static final class Provider implements MatchTeamService {

        volatile long delayMs;
        volatile String checkBudget;
        volatile long checkRemainingMs;
        volatile String gatherBudget;
        volatile boolean failCheck;
        /** 非 0：建票照 xm-match 的纪律走——入口按预算附件定本地截止，在「工作队列」里等这么久，出队时截止已过就不写、回 EXPIRED。 */
        volatile long ticketsQueueMs;
        /** 建票出队时的结论（true = 截止没过、写了票）；出队之前未完成。 */
        volatile CompletableFuture<Boolean> ticketsWritten = new CompletableFuture<>();

        void reset() {
            delayMs = 0;
            checkBudget = null;
            checkRemainingMs = -1;
            gatherBudget = "unset";
            failCheck = false;
            ticketsQueueMs = 0;
            ticketsWritten = new CompletableFuture<>();
        }

        private <T> CompletableFuture<T> later(T reply) {
            long delay = delayMs;
            return delay == 0 ? CompletableFuture.completedFuture(reply)
                    : CompletableFuture.supplyAsync(() -> reply, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS));
        }

        @Override
        public CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request) {
            checkBudget = RpcContext.getServerAttachment().getAttachment(MatchRpcAttachments.BUDGET_MS);
            checkRemainingMs = MatchRpcAttachments.deadlineFromCall(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS).remainingMillis();
            if (failCheck) {
                throw new IllegalStateException("match-worker 里炸了");
            }
            TeamMatchCheckReply.Builder ok = TeamMatchCheckReply.newBuilder().setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK)
                    .setLockTtlSeconds(MatchBudgets.teamMatchLockSeconds(request.getRosterCount()));
            request.getRosterList().forEach(pid -> ok.putZones(pid, 3));
            return later(ok.build());
        }

        @Override
        public CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request) {
            long queueMs = ticketsQueueMs;
            if (queueMs > 0) {
                // 同 MatchTeamServiceImpl：截止在入口（Dubbo 线程）按附件定，出队时过期就什么都不写
                Deadline d = MatchRpcAttachments.deadlineFromCall(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS);
                CompletableFuture<Boolean> written = ticketsWritten;
                return CompletableFuture.supplyAsync(() -> {
                    boolean write = !d.expired();
                    written.complete(write);
                    return TeamTicketsReply.newBuilder().setStatus(write ? TeamTicketsStatus.TEAM_TICKETS_CREATED
                            : TeamTicketsStatus.TEAM_TICKETS_EXPIRED).build();
                }, CompletableFuture.delayedExecutor(queueMs, TimeUnit.MILLISECONDS));
            }
            return later(TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_FAILED)
                    .setFailedPlayerId(request.getRoster(0)).build());
        }

        @Override
        public CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request) {
            return later(Empty.getDefaultInstance());
        }

        @Override
        public CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request) {
            gatherBudget = RpcContext.getServerAttachment().getAttachment(MatchRpcAttachments.BUDGET_MS);
            return later(TeamGatherReply.newBuilder().setOk(true).setOutcome("success").setBattleId(request.getTeamId() + 1).build());
        }
    }

    @BeforeAll
    static void start() throws IOException {
        int port = freePort();
        server = IsolatedDubboModule.create("xm-team-test-match-provider");
        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");
        ServiceConfig<MatchTeamService> service = new ServiceConfig<>(server.module());
        service.setInterface(MatchTeamService.class);
        service.setRef(PROVIDER);
        service.setGroup(DubboGroups.MATCH);
        service.setRegister(false);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setProtocol(protocol);
        service.export();

        client = IsolatedDubboModule.create("xm-team-test-match-caller");
        battle = new MatchTeamBattle(reference(port));
        // 一个没有人监听的端口：xm-match 不在
        unreachable = new MatchTeamBattle(reference(freePort()));
    }

    private static MatchTeamService reference(int port) {
        ReferenceConfig<MatchTeamService> reference = new ReferenceConfig<>(client.module());
        reference.setInterface(MatchTeamService.class);
        reference.setGroup(DubboGroups.MATCH);
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(REFERENCE_DEFAULT_TIMEOUT_MS);
        return reference.get();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void reset() {
        PROVIDER.reset();
    }

    @Test
    void 预检_剩余预算过线到了提供方_应答翻译成通过并带回zone与锁时长() {
        Check check = battle.checkTeamMatch(1, ROSTER, Deadline.after(1500));

        assertThat(check.ok()).as("code=%d", check.code()).isTrue();
        assertThat(check.zones()).isEqualTo(Map.of(A, 3, B, 3));
        assertThat(check.lockTtlSeconds()).isEqualTo(MatchBudgets.teamMatchLockSeconds(2)).isEqualTo(74);
        assertThat(PROVIDER.checkBudget).as("xm-budget-ms 附件过了线").isNotNull();
        assertThat(Long.parseLong(PROVIDER.checkBudget)).isBetween(1L, 1500L);
        assertThat(PROVIDER.checkRemainingMs).as("提供方的本地截止 = 收到时刻 + 调用方带来的预算，而不是它自己的 4500 ms")
                .isBetween(1L, 1500L);
    }

    @Test
    void 预算充足时带给提供方的是这一跳的超时_不是整请求的剩余预算() {
        Check check = battle.checkTeamMatch(1, ROSTER, Deadline.after(3500));

        assertThat(check.ok()).as("code=%d", check.code()).isTrue();
        assertThat(PROVIDER.checkBudget).as("每跳超时封顶 3 s：过线的预算附件也是它").isEqualTo("3000");
        assertThat(PROVIDER.checkRemainingMs).as("提供方的本地截止不晚于调用方这一跳放弃的时刻（收到时刻 + 3 s），而不是 + 3.5 s")
                .isBetween(1L, MatchTeamBattle.HOP_TIMEOUT_MS);
    }

    /**
     * 评审 T1-01：建票在 xm-match 的工作队列里等的时间落在 (每跳超时 3 s, 整请求剩余预算 ≈ 3.5 s] 时，本端在 3 s 已经判「结果不明」并回滚，
     * 对端出队时<b>不得</b>再写票。预算附件若带的是整请求的剩余预算（改之前），对端的截止还没到、照常建出全员的 matched 票而没人收。
     */
    @Test
    void 建票在对端排队超过每跳超时_本端判结果不明_对端出队时预算已过期什么都不写() throws Exception {
        PROVIDER.ticketsQueueMs = MatchTeamBattle.HOP_TIMEOUT_MS + 200; // 3.2 s：晚于每跳超时，早于整请求预算（3.5 s）
        CompletableFuture<Boolean> written = PROVIDER.ticketsWritten;
        long started = System.nanoTime();

        Tickets tickets = battle.createTeamTickets(1, 77, ROSTER, Map.of(A, 3, B, 3), TICKETS, Deadline.after(3500));

        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(tickets).as("本端拿不到可信的应答：结果不明").isInstanceOf(Tickets.Unknown.class);
        assertThat(waited).as("等满了这一跳（不是连不上之类的立即失败）").isGreaterThanOrEqualTo(2900L);
        assertThat(written.get(5, TimeUnit.SECONDS)).as("对端出队时（到达后 3.2 s）预算附件给的截止（到达后 3 s）已过：不写票、回 EXPIRED").isFalse();
    }

    @Test
    void 慢于引用缺省超时的应答照样拿得到_每跳用的是调用级超时() {
        PROVIDER.delayMs = 900; // 引用缺省 300 ms；调用级超时 = min(3 s, 剩余预算 ≈ 3.5 s) = 3 s

        Check check = battle.checkTeamMatch(1, ROSTER, Deadline.after(3500));
        Tickets tickets = battle.createTeamTickets(1, 77, ROSTER, Map.of(A, 3, B, 3), TICKETS, Deadline.after(3500));
        boolean released = battle.releaseTeamTickets(TICKETS, Deadline.after(3500));

        assertThat(check.ok()).as("code=%d", check.code()).isTrue();
        assertThat(tickets).isEqualTo(new Tickets.Failed(A));
        assertThat(released).isTrue();
    }

    @Test
    void 剩余预算比应答耗时短_到点就按4030放弃_不等对端() {
        PROVIDER.delayMs = 2000;
        long started = System.nanoTime();

        Check check = battle.checkTeamMatch(1, ROSTER, Deadline.after(400));

        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(check.code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(waited).as("等到剩余预算（400 ms）为止，不是引用缺省的 300 ms，也不是对端的 2 s").isBetween(380L, 1500L);
    }

    @Test
    void 提供方抛异常_预检按4030() {
        PROVIDER.failCheck = true;

        Check check = battle.checkTeamMatch(1, ROSTER, Deadline.after(3000));

        assertThat(check.ok()).isFalse();
        assertThat(check.code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(check.param()).isZero();
    }

    @Test
    void gather长挂超过引用缺省超时仍然完成_调用级超时是开战锁时长_不带预算附件() throws Exception {
        PROVIDER.delayMs = 1500; // 引用缺省 300 ms；锁时长 5 s

        CompletableFuture<Gather> stage = battle.runTeamGather(1, 500, ROSTER, TICKETS, 5).toCompletableFuture();

        assertThat(stage).as("不阻塞调用线程").isNotDone();
        Gather gather = stage.get(10, TimeUnit.SECONDS);
        assertThat(gather).isEqualTo(new Gather(true, "success", 501));
        assertThat(PROVIDER.gatherBudget).as("gather 不受请求预算约束：提供方读不到预算附件").isNull();
    }

    @Test
    void gather超过开战锁时长还没回来_stage异常完成_按结果不明处理() {
        PROVIDER.delayMs = 4000; // 锁时长 1 s
        long started = System.nanoTime();

        CompletableFuture<Gather> stage = battle.runTeamGather(1, 500, ROSTER, TICKETS, 1).toCompletableFuture();

        assertThatThrownBy(() -> stage.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(waited).as("按锁时长（1 s）加本地兜底余量判超时，不等对端的 4 s").isBetween(900L, 1000L + MatchTeamBattle.GATHER_GUARD_MS + 600);
    }

    @Test
    void xm_match不在_四个方法各自落到约定的失败形态_都不抛() {
        assertThat(unreachable.checkTeamMatch(1, ROSTER, Deadline.after(1500)).code()).isEqualTo(TeamTips.INTERNAL);
        assertThat(unreachable.createTeamTickets(1, 77, ROSTER, Map.of(A, 3, B, 3), TICKETS, Deadline.after(1500)))
                .isInstanceOf(Tickets.Unknown.class);
        assertThat(unreachable.releaseTeamTickets(TICKETS, Deadline.after(1500))).isFalse();
        CompletableFuture<Gather> stage = unreachable.runTeamGather(1, 500, ROSTER, TICKETS, 2).toCompletableFuture();
        assertThatThrownBy(() -> stage.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }
}
