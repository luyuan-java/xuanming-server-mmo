package com.game.api.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.DubboGroups;
import com.game.api.MatchTeamService;
import com.game.api.asset.IsolatedDubboModule;
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
import java.io.IOException;
import java.net.ServerSocket;
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
import org.junit.jupiter.api.Test;

/**
 * 预算附件 {@code xm-budget-ms}（match-spec §7.2「截止」）：取值规则是纯函数；附件真的能过 Triple 的线——提供方与调用方各在自己的 Dubbo 框架模型里
 * （等价于两个进程），调用方鉴权过滤器照常生效（测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）。
 * 钉住三件事：提供方在服务方法入口读得到调用方给的剩余预算；附件只作用于那一次调用、不漏到同一线程上的下一次调用；调用级超时只作用于那一次调用。
 */
class MatchRpcAttachmentsTest {

    private static final long OWN_BUDGET_MS = MatchBudgets.DEFAULT_REQUEST_BUDGET_MS;

    private static final Provider PROVIDER = new Provider();
    private static IsolatedDubboModule server;
    private static IsolatedDubboModule client;
    private static MatchTeamService remote;

    /** 假的 xm-match：createTeamTickets 在入口读截止并把「是否已过期 / 剩余毫秒」带回；runTeamGather 按 team_id 毫秒数延迟完成。 */
    static final class Provider implements MatchTeamService {

        @Override
        public CompletableFuture<TeamMatchCheckReply> checkTeamMatch(TeamMatchCheckRequest request) {
            return CompletableFuture.completedFuture(TeamMatchCheckReply.newBuilder().setResult(TeamMatchCheckResult.TEAM_MATCH_CHECK_OK).build());
        }

        @Override
        public CompletableFuture<TeamTicketsReply> createTeamTickets(TeamTicketsRequest request) {
            Deadline deadline = MatchRpcAttachments.deadlineFromCall(OWN_BUDGET_MS);
            // 借字段把观察到的值带回调用方：EXPIRED = 到达时已过期；否则 failed_player_id = 剩余毫秒
            return CompletableFuture.completedFuture(deadline.expired()
                    ? TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_EXPIRED).build()
                    : TeamTicketsReply.newBuilder().setStatus(TeamTicketsStatus.TEAM_TICKETS_CREATED)
                            .setFailedPlayerId(deadline.remainingMillis()).build());
        }

        @Override
        public CompletableFuture<Empty> releaseTeamTickets(TeamTicketsRelease request) {
            return CompletableFuture.completedFuture(Empty.getDefaultInstance());
        }

        @Override
        public CompletableFuture<TeamGatherReply> runTeamGather(TeamGatherRequest request) {
            return CompletableFuture.supplyAsync(() -> TeamGatherReply.newBuilder().setOk(true).setBattleId(request.getTeamId()).build(),
                    CompletableFuture.delayedExecutor(request.getTeamId(), TimeUnit.MILLISECONDS));
        }
    }

    @BeforeAll
    static void start() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        server = IsolatedDubboModule.create("xm-api-test-match-provider");
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

        client = IsolatedDubboModule.create("xm-api-test-match-caller");
        ReferenceConfig<MatchTeamService> reference = new ReferenceConfig<>(client.module());
        reference.setInterface(MatchTeamService.class);
        reference.setGroup(DubboGroups.MATCH);
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(5_000);
        remote = reference.get();
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

    private static TeamTicketsReply create(CompletableFuture<TeamTicketsReply> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- 取值规则（纯函数）

    @Test
    void 取值规则_附件有效取附件_缺失或非法取提供方预算_比提供方预算大按提供方预算收口() {
        assertThat(MatchRpcAttachments.budgetOf("1200", 4500)).isEqualTo(1200);
        assertThat(MatchRpcAttachments.budgetOf("0", 4500)).as("0 = 发出时就没有预算了").isZero();
        assertThat(MatchRpcAttachments.budgetOf("4500", 4500)).isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf("4501", 4500)).isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf("999999999999999999", 4500)).as("18 位仍在 long 之内").isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf(null, 4500)).isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf("", 4500)).isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf("-1", 4500)).as("负号不是数字：按缺失").isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf("12a", 4500)).isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf(" 12", 4500)).isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf("1.5", 4500)).isEqualTo(4500);
        assertThat(MatchRpcAttachments.budgetOf("9999999999999999999", 4500)).as("超过 18 位（会溢出 long）：按缺失").isEqualTo(4500);
        assertThatThrownBy(() -> MatchRpcAttachments.budgetOf("1", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 附件名全小写_Triple把附件当HTTP2头传() {
        assertThat(MatchRpcAttachments.BUDGET_MS).isEqualTo("xm-budget-ms").isEqualTo(MatchRpcAttachments.BUDGET_MS.toLowerCase());
    }

    // ---------------------------------------------------------------- 真 Triple

    @Test
    void 提供方在方法入口读到调用方给的剩余预算() throws Exception {
        TeamTicketsReply reply = create(MatchRpcAttachments.callWithBudget(1200, () -> remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance())));

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        assertThat(reply.getFailedPlayerId()).as("本地截止 = 收到时刻 + 1200 ms").isGreaterThan(0).isLessThanOrEqualTo(1200);
    }

    @Test
    void 预算为0_提供方看到的截止一开始就是过期的() throws Exception {
        TeamTicketsReply zero = create(MatchRpcAttachments.callWithBudget(0, () -> remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance())));
        TeamTicketsReply negative = create(MatchRpcAttachments.callWithBudget(-50, () -> remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance())));

        assertThat(zero.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
        assertThat(negative.getStatus()).as("负的剩余预算按 0 发出").isEqualTo(TeamTicketsStatus.TEAM_TICKETS_EXPIRED);
    }

    @Test
    void 不带附件_提供方按自己的整请求预算() throws Exception {
        TeamTicketsReply reply = create(remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance()));

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        assertThat(reply.getFailedPlayerId()).isGreaterThan(OWN_BUDGET_MS - 1000).isLessThanOrEqualTo(OWN_BUDGET_MS);
    }

    @Test
    void 调用方给的预算比提供方的大_按提供方的收口() throws Exception {
        TeamTicketsReply reply = create(MatchRpcAttachments.callWithBudget(60_000, () -> remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance())));

        assertThat(reply.getFailedPlayerId()).isGreaterThan(OWN_BUDGET_MS - 1000).isLessThanOrEqualTo(OWN_BUDGET_MS);
    }

    @Test
    void 附件只作用于那一次调用_不漏到同一线程上的下一次() throws Exception {
        TeamTicketsReply first = create(MatchRpcAttachments.callWithBudget(300, () -> remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance())));
        assertThat(RpcContext.getClientAttachment().getAttachment(MatchRpcAttachments.BUDGET_MS)).as("调完即摘").isNull();
        TeamTicketsReply second = create(remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance()));

        assertThat(first.getFailedPlayerId()).isLessThanOrEqualTo(300);
        assertThat(second.getFailedPlayerId()).as("第二次没带附件：提供方按自己的预算").isGreaterThan(OWN_BUDGET_MS - 1000);
    }

    @Test
    void 调用里抛出的异常变成异常完成的future_附件照样摘掉() {
        CompletableFuture<TeamTicketsReply> failed = MatchRpcAttachments.callWithBudget(300, () -> {
            throw new IllegalStateException("客户端还没建好");
        });

        assertThat(failed).isCompletedExceptionally();
        assertThat(RpcContext.getClientAttachment().getAttachment(MatchRpcAttachments.BUDGET_MS)).isNull();
        CompletableFuture<TeamTicketsReply> nullFuture = MatchRpcAttachments.callWithBudget(300, () -> null);
        assertThat(nullFuture).isCompletedExceptionally();
    }

    @Test
    void 调用级超时只作用于那一次_长挂的gather用它而不是引用上的缺省超时() throws Exception {
        // 挂 600 ms 的 gather：调用级超时 200 ms → 超时；紧接着同一线程上不设调用级超时的同一个调用（引用缺省 5 s）→ 正常完成
        TeamGatherRequest slow = TeamGatherRequest.newBuilder().setTeamId(600).build();
        long started = System.nanoTime();
        CompletableFuture<TeamGatherReply> timedOut = MatchRpcAttachments.callWithTimeout(200, () -> remote.runTeamGather(slow));

        assertThatThrownBy(() -> timedOut.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("按 200 ms 超时，而不是等 600 ms 的应答").isLessThan(550);

        TeamGatherReply done = remote.runTeamGather(slow).get(10, TimeUnit.SECONDS);
        assertThat(done.getOk()).isTrue();
        assertThat(done.getBattleId()).isEqualTo(600);
    }

    @Test
    void 预算与调用级超时可以一起带() throws Exception {
        TeamTicketsReply reply = create(MatchRpcAttachments.callWithBudget(800, 3_000,
                () -> remote.createTeamTickets(TeamTicketsRequest.getDefaultInstance())));

        assertThat(reply.getStatus()).isEqualTo(TeamTicketsStatus.TEAM_TICKETS_CREATED);
        assertThat(reply.getFailedPlayerId()).isGreaterThan(0).isLessThanOrEqualTo(800);
        assertThatThrownBy(() -> MatchRpcAttachments.callWithTimeout(0, () -> remote.runTeamGather(TeamGatherRequest.getDefaultInstance())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
