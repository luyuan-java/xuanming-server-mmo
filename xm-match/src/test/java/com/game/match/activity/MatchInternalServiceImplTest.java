package com.game.match.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.match.MatchRpcAttachments;
import com.game.common.id.Snowflake;
import com.game.match.MatchProperties;
import com.game.match.dispatch.MatchWorkers;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.precheck.MemberPrecheck.Reason;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakeMemberPrecheck;
import com.game.match.testing.InMemoryTicketStore;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 活动开战的 Dubbo 提供方（match-spec §7.2「截止」、§9.3）：在 Dubbo 线程上取截止（调用方的剩余预算在附件里）、把请求投到工作池；
 * 工作池满、排队超预算、到达时已过期、处理中出了未分类的异常，一律回 INTERNAL，future 永不异常完成、不悬着。
 */
class MatchInternalServiceImplTest {

    private static final long A = 9801;
    private static final long B = 9802;

    private final InMemoryTicketStore tickets = new InMemoryTicketStore();
    private final FakeMemberPrecheck precheck = new FakeMemberPrecheck();
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final MatchProperties props = new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null, null);
    private final ActivityBattleService service = service(precheck);

    private ActivityBattleService service(MemberPrecheck check) {
        return new ActivityBattleService(check, tickets, gather, new MatchIds(new Snowflake(5), () -> true, () -> false), metrics);
    }

    @AfterEach
    void clearAttachment() {
        RpcContext.getServerAttachment().removeAttachment(MatchRpcAttachments.BUDGET_MS);
    }

    private static StartActivityBattleRequest request() {
        return StartActivityBattleRequest.newBuilder().setBattleConfigId(1).addAllMemberPlayerIds(List.of(A, B))
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(1)
                        .setActivityId(2).setPeriodKey(3).setGuildPeriodKey(4).setInitiatorPlayerId(A))
                .build();
    }

    private static StartActivityBattleResponse get(CompletableFuture<StartActivityBattleResponse> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private double internal() {
        return meters.get("xm.match.activity.battles").tags("kind", "guild_trial", "result", "internal").counter().count();
    }

    @Test
    void 正常_投到工作池上执行_不在调用线程上做阻塞的活() throws Exception {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        Deque<Runnable> queued = new ArrayDeque<>();
        MatchInternalServiceImpl provider = new MatchInternalServiceImpl(service, queued::add, props);

        CompletableFuture<StartActivityBattleResponse> reply = provider.startActivityBattle(request());

        assertThat(reply).as("调用线程只投递").isNotDone();
        assertThat(precheck.rosters).isEmpty();
        Thread worker = new Thread(() -> {
            ranOn.set(Thread.currentThread());
            queued.remove().run();
        }, "test-match-worker");
        worker.start();
        worker.join(5_000);

        StartActivityBattleResponse response = get(reply);
        assertThat(response.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE);
        assertThat(response.getBattleId()).isNotZero();
        assertThat(precheck.rosters).containsExactly(List.of(A, B));
        assertThat(tickets.ticketCount()).isEqualTo(2);
        assertThat(gather.plans).hasSize(1);
        assertThat(ranOn.get().getName()).isEqualTo("test-match-worker");
    }

    @Test
    void 工作池满_INTERNAL_不执行业务() throws Exception {
        MatchWorkers full = task -> {
            throw new RejectedExecutionException("满了");
        };

        StartActivityBattleResponse response = get(new MatchInternalServiceImpl(service, full, props).startActivityBattle(request()));

        assertThat(response.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL);
        assertThat(response.getOffenderPlayerId()).isZero();
        assertThat(response.getBattleId()).isZero();
        assertThat(precheck.rosters).isEmpty();
        assertThat(tickets.calls).isEmpty();
        assertThat(internal()).isEqualTo(1);
    }

    @Test
    void 调用方带来的预算已是0_或在队列里等过了预算_INTERNAL_不建票不开局() throws Exception {
        MatchInternalServiceImpl inline = new MatchInternalServiceImpl(service, Runnable::run, props);
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "0");
        assertThat(get(inline.startActivityBattle(request())).getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL);

        Deque<Runnable> queued = new ArrayDeque<>();
        MatchInternalServiceImpl slow = new MatchInternalServiceImpl(service, queued::add, props);
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "60");
        CompletableFuture<StartActivityBattleResponse> waiting = slow.startActivityBattle(request());
        TimeUnit.MILLISECONDS.sleep(120);
        queued.remove().run();
        assertThat(get(waiting).getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL);

        assertThat(precheck.rosters).isEmpty();
        assertThat(tickets.calls).isEmpty();
        assertThat(gather.plans).isEmpty();
        assertThat(internal()).isEqualTo(2);
    }

    @Test
    void 预算附件在调用线程上读_带着足够的预算照常执行_业务拒绝原样回() throws Exception {
        MatchInternalServiceImpl inline = new MatchInternalServiceImpl(service, Runnable::run, props);
        RpcContext.getServerAttachment().setAttachment(MatchRpcAttachments.BUDGET_MS, "3000");
        precheck.fail(Reason.OFFLINE, B);

        StartActivityBattleResponse response = get(inline.startActivityBattle(request()));

        assertThat(response.getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE);
        assertThat(response.getOffenderPlayerId()).isEqualTo(B);
    }

    @Test
    void 处理中出了未分类的异常_INTERNAL_future不异常完成() throws Exception {
        MemberPrecheck broken = (roster, deadline) -> {
            throw new IllegalStateException("预检里的 bug");
        };
        MatchInternalServiceImpl provider = new MatchInternalServiceImpl(service(broken), Runnable::run, props);

        CompletableFuture<StartActivityBattleResponse> reply = provider.startActivityBattle(request());

        assertThat(reply).isCompleted();
        assertThat(get(reply).getReject()).isEqualTo(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INTERNAL);
        assertThat(tickets.calls).isEmpty();
        assertThat(internal()).isEqualTo(1);
    }
}
