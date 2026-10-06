package com.game.match.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeIdLease;
import com.game.match.MatchConfiguration;
import com.game.match.MatchInstance;
import com.game.match.dispatch.MatchDispatcher;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.match.testing.LeaseOnlyRedis;
import com.game.proto.match.ChallengePlayerRequest;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.JoinQueueRequest;
import com.game.proto.match.JoinQueueResponse;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthEndpointWebExtension;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 发号租约丢失（lead 裁决 2；match-spec §9.8、M28）。「续期滞后」会自己恢复：不拒排队、健康检查仍 UP；「真正丢失」不会自愈，补三件事——
 * 健康检查 DOWN（{@code /actuator/health} 503）、ERROR 日志 + {@code xm_match_lease_lost = 1}、拒收新的排队 157 与发起切磋 152
 * （in-band 16004「服务器繁忙,请稍后再试」，不让票据入队后永不成局）；取消排队、查状态、补签、应答切磋与当场回的四个号不受影响。
 *
 * <p>这里钉的是 M0 自己的部分：派发层的拒收、健康组件、租约丢失回调的真实接线（{@code MatchConfiguration} 的 bean 方法 + 真的 {@link NodeIdLease}）。
 * 规格同一条里的另外几项——PVE_SOLO 在租约无效时不建票、{@code checkTeamMatch} 回 INTERNAL、活动开战回 INTERNAL、凑单暂停——在排队、整队、活动、
 * 凑单各自的包里，按 {@code MatchIds.leaseValid()} 判（丢失时它恒为假）；各包合入后在这个类里把它们串进来。
 */
@ExtendWith(OutputCaptureExtension.class)
class LeaseLostTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final String BUSY_TEXT = "服务器繁忙,请稍后再试";

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicBoolean lost = new AtomicBoolean(false);
    private final MatchIds ids = new MatchIds(new Snowflake(5), valid::get, lost::get);
    private final Map<String, Counting> handlers = handlers();

    /** 数被调了几次的处理器：十个方法各一个，应答体是方法名本身（好认出是谁回的）。 */
    private static final class Counting implements MatchMethodHandler {
        final String method;
        final AtomicInteger handled = new AtomicInteger();

        Counting(String method) {
            this.method = method;
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public boolean inline() {
            return true; // 这里只看「交没交给处理器」，不需要工作池
        }

        @Override
        public Reply handle(SessionContext session, ByteString body, Deadline deadline) {
            handled.incrementAndGet();
            return new Reply.Body(ByteString.copyFromUtf8(method));
        }

        @Override
        public Reply onOverload() {
            return Reply.envelope(1003);
        }
    }

    private static Map<String, Counting> handlers() {
        Map<String, Counting> all = new LinkedHashMap<>();
        MatchMethods.ALL.forEach(method -> all.put(method, new Counting(method)));
        return all;
    }

    private MatchDispatcher dispatcher(MatchIds matchIds) {
        return new MatchDispatcher(REGISTRY, handlers.values(), Runnable::run, metrics, 4500, matchIds::leaseLost);
    }

    private static ClientCall call(String method, ByteString body, long playerId) {
        return ClientCall.newBuilder().setMessageId(REGISTRY.requireId(MatchMethods.SERVICE, method)).setRequestId(3).setBody(body)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(2).setPlayerId(playerId).setAccount("acc")).build();
    }

    private static ClientReply reply(CompletableFuture<ClientReply> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private double joinInternal(String mode) {
        return meters.get("xm.match.join.queue").tag("mode", mode).tag("outcome", "internal").counter().count();
    }

    // ================================================================ 派发层的拒收

    @Test
    void 租约丢失_排队157不交给处理器_回in_band的16004_计internal() throws Exception {
        lost.set(true);
        ByteString body = JoinQueueRequest.newBuilder().setModeValue(3).setBattleConfigId(0).build().toByteString();

        CompletableFuture<ClientReply> future = dispatcher(ids).dispatch(call(MatchMethods.JOIN_QUEUE, body, 1001));

        assertThat(future).as("当场回，不进工作池").isDone();
        ClientReply reply = reply(future);
        assertThat(reply.getTipId()).as("in-band：信封不带 tip").isZero();
        assertThat(reply.getBody()).as("逐字节").isEqualTo(MatchTips.joinRejected(MatchTip.BUSY).toByteString());
        JoinQueueResponse response = JoinQueueResponse.parseFrom(reply.getBody());
        assertThat(response.getErrorCode()).isEqualTo(16004);
        assertThat(response.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(response.getErrorMessage().getParametersList()).containsExactly(BUSY_TEXT);
        assertThat(response.getQueueTicket()).as("没有建票").isEmpty();
        assertThat(handlers.get(MatchMethods.JOIN_QUEUE).handled).as("排队的处理器没有被调到：票据不会入队").hasValue(0);
        assertThat(joinInternal("MATCH_MODE_1V1")).isEqualTo(1);
        assertThat(meters.get("xm.match.requests").tag("method", "JoinQueue").tag("result", "ok").timer().count()).isEqualTo(1);
    }

    @Test
    void 租约丢失_五种模式的排队一律拒_包括不用发号就能入队的1V1与5V5与PVE组队() throws Exception {
        lost.set(true);
        MatchDispatcher dispatcher = dispatcher(ids);

        for (int mode : new int[] {1, 3, 4, 5, 2, 0, 99}) {
            ByteString body = JoinQueueRequest.newBuilder().setModeValue(mode).setBattleConfigId(1).build().toByteString();
            JoinQueueResponse response = JoinQueueResponse.parseFrom(reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, body, 1001))).getBody());
            assertThat(response.getErrorCode()).as("mode=%d", mode).isEqualTo(16004);
            assertThat(response.getErrorMessage().getParametersList()).containsExactly(BUSY_TEXT);
        }
        assertThat(handlers.get(MatchMethods.JOIN_QUEUE).handled).hasValue(0);
        assertThat(joinInternal("MATCH_MODE_5V5")).isEqualTo(1);
        assertThat(joinInternal("MATCH_MODE_PVE_SOLO")).isEqualTo(1);
        assertThat(joinInternal("MATCH_MODE_PVE_TEAM")).isEqualTo(1);
        assertThat(joinInternal("unknown")).as("契约外的模式值净化成 unknown").isEqualTo(1);
    }

    @Test
    void 租约丢失_发起切磋152不交给处理器_回in_band的16004() throws Exception {
        lost.set(true);
        ByteString body = ChallengePlayerRequest.newBuilder().setTargetPlayerId(1002).build().toByteString();

        ClientReply reply = reply(dispatcher(ids).dispatch(call(MatchMethods.CHALLENGE_PLAYER, body, 1001)));

        assertThat(reply.getTipId()).isZero();
        assertThat(reply.getBody()).isEqualTo(MatchTips.challengeRejected(MatchTip.BUSY).toByteString());
        ChallengePlayerResponse response = ChallengePlayerResponse.parseFrom(reply.getBody());
        assertThat(response.getChallengeId()).isZero();
        assertThat(response.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(response.getErrorMessage().getParametersList()).containsExactly(BUSY_TEXT);
        assertThat(handlers.get(MatchMethods.CHALLENGE_PLAYER).handled).hasValue(0);
        assertThat(meters.get("xm.match.challenges").tag("stage", "invite").tag("result", "internal").counter().count()).isEqualTo(1);
    }

    @Test
    void 租约丢失_比拒收更靠前的两条判定保留_没绑定玩家回缺少玩家身份_解析失败回信封1003() throws Exception {
        lost.set(true);
        MatchDispatcher dispatcher = dispatcher(ids);
        ByteString truncated = ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01});

        JoinQueueResponse join = JoinQueueResponse.parseFrom(reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 0))).getBody());
        ChallengePlayerResponse challenge = ChallengePlayerResponse.parseFrom(
                reply(dispatcher.dispatch(call(MatchMethods.CHALLENGE_PLAYER, ByteString.EMPTY, 0))).getBody());
        ClientReply badJoin = reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, truncated, 1001)));
        ClientReply badChallenge = reply(dispatcher.dispatch(call(MatchMethods.CHALLENGE_PLAYER, truncated, 1001)));

        assertThat(join.getErrorCode()).isEqualTo(16004);
        assertThat(join.getErrorMessage().getParametersList()).containsExactly("缺少玩家身份");
        assertThat(challenge.getErrorMessage().getId()).isEqualTo(16004);
        assertThat(challenge.getErrorMessage().getParametersList()).containsExactly("缺少玩家身份");
        assertThat(badJoin.getTipId()).isEqualTo(1003);
        assertThat(badJoin.getBody().isEmpty()).isTrue();
        assertThat(badChallenge.getTipId()).isEqualTo(1003);
        assertThat(handlers.values()).allSatisfy(h -> assertThat(h.handled).hasValue(0));
    }

    @Test
    void 租约丢失_取消排队_查状态_补签_应答切磋与当场回的四个号照常交给处理器() throws Exception {
        lost.set(true);
        MatchDispatcher dispatcher = dispatcher(ids);
        List<String> unaffected = List.of(MatchMethods.CANCEL_QUEUE, MatchMethods.GET_QUEUE_STATUS, MatchMethods.REQUEST_BATTLE_TICKET,
                MatchMethods.RESPOND_CHALLENGE, MatchMethods.NOTIFY_CHALLENGE_INVITE, MatchMethods.NOTIFY_CHALLENGE_RESULT,
                MatchMethods.WATCH_BATTLE, MatchMethods.LIST_WATCHABLE_BATTLES);

        for (String method : unaffected) {
            ClientReply reply = reply(dispatcher.dispatch(call(method, ByteString.EMPTY, 1001)));
            assertThat(reply.getBody().toStringUtf8()).as("是它自己的处理器回的").isEqualTo(method);
            assertThat(handlers.get(method).handled).as(method).hasValue(1);
        }
        assertThat(unaffected).hasSize(MatchMethods.ALL.size() - 2);
    }

    @Test
    void 续期滞后不是丢失_排队与发起切磋照常交给处理器() throws Exception {
        valid.set(false);
        MatchDispatcher dispatcher = dispatcher(ids);

        reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 1001)));
        reply(dispatcher.dispatch(call(MatchMethods.CHALLENGE_PLAYER, ByteString.EMPTY, 1001)));

        assertThat(ids.leaseValid()).isFalse();
        assertThat(ids.nextBattleId()).as("滞后期间发不出号").isEmpty();
        assertThat(handlers.get(MatchMethods.JOIN_QUEUE).handled).as("1V1 / 5V5 照常入队，恢复后照常成局").hasValue(1);
        assertThat(handlers.get(MatchMethods.CHALLENGE_PLAYER).handled).as("由处理器自己在发号那一步回 16004").hasValue(1);
    }

    @Test
    void 租约在运行中丢失_之前照常_之后拒收_不需要重建派发器() throws Exception {
        MatchDispatcher dispatcher = dispatcher(ids);

        reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 1001)));
        lost.set(true);
        ClientReply after = reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 1001)));

        assertThat(handlers.get(MatchMethods.JOIN_QUEUE).handled).hasValue(1);
        assertThat(JoinQueueResponse.parseFrom(after.getBody()).getErrorCode()).isEqualTo(16004);
    }

    @Test
    void 没有处理器的号在租约丢失时仍是信封1003_拒收只替换已有的处理器() throws Exception {
        lost.set(true);
        MatchDispatcher bare = new MatchDispatcher(REGISTRY, List.of(), Runnable::run, metrics, 4500, ids::leaseLost);

        assertThat(reply(bare.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 1001))).getTipId()).isEqualTo(1003);
    }

    @Test
    void 拒收日志限频_丢失期间每十秒至多一条WARN(CapturedOutput output) throws Exception {
        lost.set(true);
        MatchDispatcher dispatcher = dispatcher(ids);

        for (int i = 0; i < 50; i++) {
            reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 1001)));
        }

        // 只有 WARN 那一条带「每 10 s 至多一条本日志」；其余 49 次是 DEBUG（文案不同）
        assertThat(output.getOut().split("每 10 s 至多一条本日志", -1).length - 1).as("50 次拒收只有 1 条 WARN").isEqualTo(1);
        assertThat(handlers.get(MatchMethods.JOIN_QUEUE).handled).hasValue(0);
    }

    // ================================================================ 健康组件

    @Test
    void 健康组件_租约有效UP_续期滞后仍UP只在详情里标出_真正丢失DOWN并给处置提示() {
        MatchLeaseHealthIndicator indicator = new MatchLeaseHealthIndicator(ids);

        Health healthy = indicator.health();
        valid.set(false);
        Health lagging = indicator.health();
        lost.set(true);
        Health down = indicator.health();
        valid.set(true); // 丢失之后 valid 是什么都不重要
        Health stillDown = indicator.health();

        assertThat(healthy.getStatus()).isEqualTo(Status.UP);
        assertThat(healthy.getDetails()).containsEntry("lease", "valid");
        assertThat(lagging.getStatus()).as("Redis 抖动：重启没有好处").isEqualTo(Status.UP);
        assertThat(lagging.getDetails()).containsEntry("lease", "renewal_lagging");
        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.getDetails()).containsEntry("lease", "lost");
        assertThat(down.getDetails().get("action").toString()).contains("restart xm-match");
        assertThat(stillDown.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void 健康端点_组件名matchLease_丢失时整体DOWN_HTTP状态503() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HealthContributorAutoConfiguration.class, HealthEndpointAutoConfiguration.class))
                .withBean("matchLeaseHealthIndicator", MatchLeaseHealthIndicator.class, () -> new MatchLeaseHealthIndicator(ids))
                .run(context -> {
                    HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);
                    HealthEndpointWebExtension web = context.getBean(HealthEndpointWebExtension.class);

                    assertThat(endpoint.healthForPath("matchLease").getStatus()).as("bean 名去掉 HealthIndicator 后缀").isEqualTo(Status.UP);
                    assertThat(endpoint.health().getStatus()).isEqualTo(Status.UP);
                    assertThat(web.health(ApiVersion.LATEST, WebServerNamespace.SERVER, SecurityContext.NONE).getStatus()).isEqualTo(200);

                    valid.set(false);
                    assertThat(web.health(ApiVersion.LATEST, WebServerNamespace.SERVER, SecurityContext.NONE).getStatus())
                            .as("续期滞后：仍 200").isEqualTo(200);

                    lost.set(true);
                    WebEndpointResponse<HealthComponent> response = web.health(ApiVersion.LATEST, WebServerNamespace.SERVER, SecurityContext.NONE);
                    assertThat(response.getStatus()).as("编排层看到的 HTTP 状态").isEqualTo(503);
                    assertThat(response.getBody().getStatus()).isEqualTo(Status.DOWN);
                    assertThat(endpoint.healthForPath("matchLease").getStatus()).isEqualTo(Status.DOWN);
                    assertThat(endpoint.healthForPath("ping").getStatus()).as("别的组件没事：是租约把整体拉成 DOWN").isEqualTo(Status.UP);
                });
    }

    // ================================================================ 真实接线：MatchConfiguration 的 bean 方法 + 真的 NodeIdLease

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 真实接线_续期发现号已易主_回调置gauge并记ERROR_发号停止_健康DOWN_派发器拒收_关闭时不再交还(CapturedOutput output) throws Exception {
        LeaseOnlyRedis redis = new LeaseOnlyRedis();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture renewTask = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> renewal = ArgumentCaptor.forClass(Runnable.class);
        when(scheduler.scheduleAtFixedRate(renewal.capture(), anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(renewTask);
        MatchConfiguration configuration = new MatchConfiguration();

        NodeIdLease lease = configuration.matchIdLease(redis.client, scheduler, new MatchInstance("instance-under-test"), metrics,
                new MatchStartupChecks.Passed(4200, 1));
        MatchIds realIds = configuration.matchIds(lease);
        MatchLeaseHealthIndicator indicator = configuration.matchLeaseHealthIndicator(realIds);
        MatchDispatcher dispatcher = dispatcher(realIds);
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), org.mockito.ArgumentMatchers.eq(5000L), org.mockito.ArgumentMatchers.eq(5000L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));

        // 续期正常：一切照常
        renewal.getValue().run();
        assertThat(realIds.leaseLost()).isFalse();
        assertThat(realIds.nextBattleId()).isPresent();
        assertThat(meters.get("xm.match.lease.lost").gauge().value()).isZero();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 1001)));
        assertThat(handlers.get(MatchMethods.JOIN_QUEUE).handled).hasValue(1);

        // 号被别的实例占走：下一次续期判丢失
        redis.loseLease();
        renewal.getValue().run();

        assertThat(realIds.leaseLost()).isTrue();
        assertThat(realIds.leaseValid()).isFalse();
        assertThat(realIds.nextBattleId()).as("停止发号").isEmpty();
        assertThat(realIds.nextChallengeId()).isEmpty();
        assertThat(meters.get("xm.match.lease.lost").gauge().value()).isEqualTo(1.0);
        assertThat(output.getOut()).contains("ERROR").contains("match 发号租约丢失").contains("不会自愈，需要重启本进程");
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        ClientReply refused = reply(dispatcher.dispatch(call(MatchMethods.JOIN_QUEUE, ByteString.EMPTY, 1001)));
        assertThat(JoinQueueResponse.parseFrom(refused.getBody()).getErrorCode()).isEqualTo(16004);
        assertThat(handlers.get(MatchMethods.JOIN_QUEUE).handled).as("丢失之后没有再进处理器").hasValue(1);
        reply(dispatcher.dispatch(call(MatchMethods.CANCEL_QUEUE, ByteString.EMPTY, 1001)));
        assertThat(handlers.get(MatchMethods.CANCEL_QUEUE).handled).as("取消排队照常").hasValue(1);

        // 丢失的租约不自愈：续期任务已取消，再跑也不会恢复；关闭时不去删别人的号
        verify(renewTask).cancel(false);
        redis.loseLease();
        renewal.getValue().run();
        assertThat(realIds.leaseLost()).isTrue();
        lease.close();
        assertThat(redis.leaseReleases()).as("已丢失：不执行释放脚本").isZero();
    }
}
