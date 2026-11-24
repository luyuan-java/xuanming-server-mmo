package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.ClientReply;
import com.game.common.net.EndpointCircuitBreaker;
import com.game.common.token.GateTokens;
import com.game.contract.MessageIdRegistry;
import com.game.gate.metrics.GateMetrics;
import com.game.net.limit.MessageLimits;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 后端三态熔断接入校验：按域隔离、业务拒绝不触发、半开探测在连接中途关闭时不泄漏锁。 */
class BackendCircuitBreakerTest {

    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 3;
    private static final int ZONE = 1;
    private static final int TIP_MSG = 23;

    private final MessageIdRegistry ids = MessageIdRegistry.loadFromClasspath();
    private final MessageRoutes routes = MessageRoutes.of(ids);
    private final GateTokens tokens = GateTokens.ofUtf8("test-secret");
    private final FakeLogin login = new FakeLogin();
    private final FakeLogin match = new FakeLogin();
    private final FakeLogin friend = new FakeLogin();
    private final FakeLinks links = new FakeLinks();
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GateMetrics metrics = new GateMetrics(meters);
    private final AtomicLong nanos = new AtomicLong(1);

    @Test
    void 连续故障打开熔断并按域隔离_业务错误不计故障() {
        EndpointCircuitBreaker breaker = new EndpointCircuitBreaker(2, Duration.ofSeconds(10), nanos::get);
        EmbeddedChannel ch = verified(breaker, Map.of(DubboGroups.MATCH, match, DubboGroups.FRIEND, friend));
        int matchMsg = ids.requireId("MatchService", "GetQueueStatus");
        int friendMsg = ids.requireId("ClientPlayerFriend", "AddFriend");

        // 业务错误应答（tip=1008）不计入基础设施失败，且清零计数
        ch.writeInbound(request(1, matchMsg));
        match.complete(ClientReply.newBuilder().setTipId(1008).build());
        ch.runPendingTasks();
        MessageContent bizReply = ch.readOutbound();
        assertThat(bizReply.getErrorMessage().getId()).isEqualTo(1008);
        assertThat(breaker.stateOf(DubboGroups.MATCH)).isEqualTo(EndpointCircuitBreaker.State.CLOSED);

        // 连续两次基础设施故障（使用同步抛出免去 5s 结果未知保序等待）
        ch.close();
        ch.runPendingTasks();

        AtomicLong invocations = new AtomicLong();
        ClientMessageService countingFail = new ClientMessageService() {
            @Override
            public java.util.concurrent.CompletableFuture<ClientReply> handle(com.game.api.proto.ClientCall call) {
                invocations.incrementAndGet();
                throw new IllegalStateException("rpc down");
            }
            @Override
            public java.util.concurrent.CompletableFuture<com.game.api.proto.Ack> sessionClosed(com.game.api.proto.SessionClosed closed) {
                return java.util.concurrent.CompletableFuture.completedFuture(com.game.api.proto.Ack.getDefaultInstance());
            }
            @Override
            public java.util.concurrent.CompletableFuture<com.game.api.proto.Ack> abandonEnter(com.game.api.proto.AbandonedEnter event) {
                return java.util.concurrent.CompletableFuture.completedFuture(com.game.api.proto.Ack.getDefaultInstance());
            }
        };
        EmbeddedChannel ch2 = verified(breaker, Map.of(DubboGroups.MATCH, countingFail, DubboGroups.FRIEND, friend));
        ch2.writeInbound(request(10, matchMsg));
        ch2.runPendingTasks();
        assertThat(((MessageContent) ch2.readOutbound()).getErrorMessage().getId()).isEqualTo(1003);
        ch2.writeInbound(request(11, matchMsg));
        ch2.runPendingTasks();
        assertThat(((MessageContent) ch2.readOutbound()).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(breaker.stateOf(DubboGroups.MATCH)).isEqualTo(EndpointCircuitBreaker.State.OPEN);
        assertThat(invocations.get()).isEqualTo(2);

        // 打开期间快速失败，不再触达后端，也不影响同连接健康的 friend 域
        ch2.writeInbound(request(12, matchMsg));
        ch2.runPendingTasks();
        MessageContent fastFail = ch2.readOutbound();
        assertThat(fastFail.getId()).isEqualTo(12);
        assertThat(fastFail.getErrorMessage().getId()).isEqualTo(1003);
        assertThat(invocations.get()).isEqualTo(2);
        assertThat(ch2.isActive()).isTrue();

        ch2.writeInbound(request(13, friendMsg));
        friend.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("ok")).build());
        ch2.runPendingTasks();
        MessageContent friendReply = ch2.readOutbound();
        assertThat(friendReply.getId()).isEqualTo(13);
        assertThat(friendReply.getSerializedMessage().toStringUtf8()).isEqualTo("ok");
        ch2.close();
    }

    @Test
    void 半开探测期间客户端断连不泄漏探测锁() {
        EndpointCircuitBreaker breaker = new EndpointCircuitBreaker(1, Duration.ofSeconds(10), nanos::get);
        breaker.recordFailure(DubboGroups.MATCH);
        assertThat(breaker.stateOf(DubboGroups.MATCH)).isEqualTo(EndpointCircuitBreaker.State.OPEN);

        nanos.addAndGet(TimeUnit.SECONDS.toNanos(11));
        EmbeddedChannel ch = verified(breaker, Map.of(DubboGroups.MATCH, match));
        int matchMsg = ids.requireId("MatchService", "GetQueueStatus");
        ch.writeInbound(request(20, matchMsg));
        assertThat(match.calls).hasSize(1);
        assertThat(breaker.stateOf(DubboGroups.MATCH)).isEqualTo(EndpointCircuitBreaker.State.HALF_OPEN);

        // 客户端中途断连，随后后端探测应答才返回：熔断器应照常恢复 CLOSED
        ch.close();
        ch.runPendingTasks();
        match.complete(ClientReply.newBuilder().setBody(ByteString.copyFromUtf8("recovered")).build());
        assertThat(breaker.stateOf(DubboGroups.MATCH)).isEqualTo(EndpointCircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire(DubboGroups.MATCH)).isTrue();
    }

    private EmbeddedChannel verified(EndpointCircuitBreaker breaker, Map<String, ClientMessageService> backends) {
        ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-uuid", ZONE), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), routes, TIP_MSG, login, backends, links, registry,
                new GateLimits(16, 50, Duration.ZERO, MessageLimits.of(Map.of())),
                metrics, new RecordingPresence(), nanos::get).withBackendCircuitBreaker(breaker);
        EmbeddedChannel ch = new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher));
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(GATE_NODE).setZoneId(ZONE)
                .setExpireTimestamp(NOW + 600).build().toByteString();
        ch.writeInbound(ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build());
        assertThat(((ClientTokenVerifyResponse) ch.readOutbound()).getSuccess()).isTrue();
        return ch;
    }

    private static ClientRequest request(long id, int messageId) {
        return ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(ByteString.EMPTY).build();
    }
}
