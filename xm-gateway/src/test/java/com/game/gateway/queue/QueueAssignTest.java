package com.game.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.api.proto.GateNodeInfo;
import com.game.common.token.GateTokens;
import com.game.gateway.assign.AssignGateResponse;
import com.game.gateway.assign.AssignGateService;
import com.game.gateway.gate.GateSource;
import com.game.gateway.gate.GateTokenIssuer;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** assign-gate / queue-status 的排队分支：快速通道、入队、轮询三结局、令牌校验、准入在前、存储故障 fail-closed。存储是替身。 */
class QueueAssignTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC);
    private static final GateNodeInfo GATE = GateNodeInfo.newBuilder().setZoneId(1).setNodeId(4).setInstanceId("g4")
            .setClientHost("10.0.0.4").setClientPort(11004).setPlayerCount(10).build();

    private final AtomicReference<List<ZoneRow>> table = new AtomicReference<>(
            List.of(new ZoneRow(1, "一区", 0, 100, "", null, true, 1, 0, 0)));
    private final ZoneDirectory zones = new ZoneDirectory(table::get, System::nanoTime);
    private final GateSource gates = mock(GateSource.class);
    private final LoginQueue queue = mock(LoginQueue.class);
    private final QueueTokens tokens = new QueueTokens("s".getBytes(StandardCharsets.UTF_8));
    private final AssignGateService service = new AssignGateService(zones, gates,
            new GateTokenIssuer(GateTokens.ofUtf8("s"), CLOCK, new SecureRandom()),
            new AssignGateService.Queueing(queue, new QueueCapacity(1.5), tokens, 2000), CLOCK);

    {
        when(gates.listGates(1)).thenReturn(List.of(GATE));
        when(queue.entryTtl()).thenReturn(Duration.ofHours(1));
    }

    @Test
    void 队列空且快速通道占位成功_直接签_预算是容量减在线() {
        when(queue.queueLength(1)).thenReturn(0L);
        when(queue.tryReserveFastPath(1, 90)).thenReturn(true);
        AssignGateResponse resp = service.assign(1, null);
        assertThat(resp.code()).isZero();
        assertThat(resp.gateIp()).isEqualTo("10.0.0.4");
        verify(queue, never()).enqueue(anyInt());
    }

    @Test
    void 有人在排或占位失败_入队回100_令牌可验() {
        when(queue.queueLength(1)).thenReturn(3L);
        when(queue.enqueue(1)).thenReturn(new LoginQueue.Enqueued(QueueTokens.newQueueId(), 3, 4));
        AssignGateResponse resp = service.assign(1, "");
        assertThat(resp.code()).isEqualTo(100);
        assertThat(resp.queueSource()).isEqualTo("login");
        assertThat(resp.queueRank()).isEqualTo(3);
        assertThat(resp.queueTotal()).isEqualTo(4);
        assertThat(resp.retryAfterMs()).isEqualTo(2000);
        assertThat(tokens.verify(resp.queueToken(), CLOCK.millis() / 1000)).get()
                .extracting(QueueTokens.Claims::zoneId).isEqualTo(1);
        verify(queue, never()).tryReserveFastPath(anyInt(), anyLong());
    }

    @Test
    void 带令牌轮询_还在排回100同一令牌_放行签gate令牌_过期410() {
        String id = QueueTokens.newQueueId();
        String token = tokens.sign(id, 1, CLOCK.millis() / 1000 + 60);
        when(queue.lookup(1, id)).thenReturn(new LoginQueue.Waiting(2, 5));
        AssignGateResponse waiting = service.assign(1, token);
        assertThat(waiting.code()).isEqualTo(100);
        assertThat(waiting.queueToken()).isEqualTo(token);
        assertThat(waiting.queueRank()).isEqualTo(2);

        when(queue.lookup(1, id)).thenReturn(new LoginQueue.Admitted(GATE, 0));
        AssignGateResponse admitted = service.queueStatus(1, token);
        assertThat(admitted.code()).isZero();
        assertThat(admitted.gatePort()).isEqualTo(11004);
        assertThat(admitted.tokenPayload()).isNotEmpty();

        when(queue.lookup(1, id)).thenReturn(new LoginQueue.Expired());
        assertThat(service.queueStatus(0, token).error()).as("没带区取令牌里的区").isEqualTo("queue_token_expired");
    }

    @Test
    void 令牌验不过或不是这个区_410_不碰存储() {
        assertThat(service.queueStatus(1, "garbage").code()).isEqualTo(410);
        assertThat(service.queueStatus(0, "garbage").error()).isEqualTo("queue_token_expired");
        table.set(List.of(new ZoneRow(1, "一区", 0, 100, "", null, true, 1, 0, 0),
                new ZoneRow(2, "二区", 0, 100, "", null, false, 2, 0, 0)));
        String token = tokens.sign(QueueTokens.newQueueId(), 1, CLOCK.millis() / 1000 + 60);
        assertThat(new AssignGateService(new ZoneDirectory(table::get, System::nanoTime), gates,
                new GateTokenIssuer(GateTokens.ofUtf8("s"), CLOCK, new SecureRandom()),
                new AssignGateService.Queueing(queue, new QueueCapacity(1.5), tokens, 2000), CLOCK)
                .queueStatus(2, token).error()).as("别的区的令牌").isEqualTo("queue_token_expired");
        verify(queue, never()).lookup(anyInt(), anyString());
        assertThat(service.queueStatus(1, " ").error()).isEqualTo("missing_queue_token");
    }

    @Test
    void 轮询也先过准入_维护中的区拦下排队者() {
        table.set(List.of(new ZoneRow(1, "一区", 1, 100, "维护", null, true, 1, 0, 0)));
        ZoneDirectory maintenance = new ZoneDirectory(table::get, System::nanoTime);
        AssignGateService s = new AssignGateService(maintenance, gates,
                new GateTokenIssuer(GateTokens.ofUtf8("s"), CLOCK, new SecureRandom()),
                new AssignGateService.Queueing(queue, new QueueCapacity(1.5), tokens, 2000), CLOCK);
        String token = tokens.sign(QueueTokens.newQueueId(), 1, CLOCK.millis() / 1000 + 60);
        assertThat(s.queueStatus(1, token).error()).isEqualTo("zone_maintenance");
        assertThat(s.queueStatus(0, token).error()).isEqualTo("zone_maintenance");
        verifyNoInteractions(queue);
    }

    @Test
    void 排队存储出错_500_queue_unavailable_不签令牌() {
        when(queue.queueLength(1)).thenThrow(new IllegalStateException("Redis 不可达"));
        assertThat(service.assign(1, null).error()).isEqualTo("queue_unavailable");
        String id = QueueTokens.newQueueId();
        when(queue.lookup(eq(1), eq(id))).thenThrow(new IllegalStateException("Redis 不可达"));
        assertThat(service.queueStatus(1, tokens.sign(id, 1, CLOCK.millis() / 1000 + 60)).error())
                .isEqualTo("queue_unavailable");
    }

    @Test
    void 排队关闭时_令牌照收不用_轮询410() {
        AssignGateService off = new AssignGateService(zones, gates,
                new GateTokenIssuer(GateTokens.ofUtf8("s"), CLOCK, new SecureRandom()));
        assertThat(off.assign(1, "q").code()).isZero();
        assertThat(off.queueStatus(1, "q").error()).isEqualTo("queue_disabled");
    }

    @Test
    void 带令牌轮询不过限流_不带令牌的过限流_排队关闭时令牌绕不过限流() {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.check(anyInt(), any(), any(), any())).thenReturn(RateLimitDecision.deny(RateLimitDecision.IP_RATE_LIMIT));
        GateTokenIssuer issuer = new GateTokenIssuer(GateTokens.ofUtf8("s"), CLOCK, new SecureRandom());
        AssignGateService limited = new AssignGateService(zones, gates, issuer,
                new AssignGateService.Queueing(queue, new QueueCapacity(1.5), tokens, 2000), limiter, CLOCK);
        String id = QueueTokens.newQueueId();
        when(queue.lookup(1, id)).thenReturn(new LoginQueue.Waiting(0, 1));

        assertThat(limited.assign(1, tokens.sign(id, 1, CLOCK.millis() / 1000 + 60), "1.1.1.1", "a").code()).isEqualTo(100);
        verifyNoInteractions(limiter);
        AssignGateResponse denied = limited.assign(1, null, "1.1.1.1", "a");
        assertThat(denied.code()).isEqualTo(429);
        assertThat(denied.error()).isEqualTo("IP_RATE_LIMIT");
        verify(queue, never()).enqueue(anyInt());

        AssignGateService off = new AssignGateService(zones, gates, issuer, null, limiter, CLOCK);
        assertThat(off.assign(1, "q", "1.1.1.1", "a").code()).isEqualTo(429);
    }
}
