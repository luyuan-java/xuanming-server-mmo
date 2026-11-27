package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.Ack;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.game.gate.link.EnterFailure;
import com.game.gate.link.SceneLinkManager;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SceneLinkCooldownAndDisconnectRetryTest {

    @Test
    void connectBackoffDoublesFrom500msTo8sCapWithJitterAndPendingEntryJitters() {
        // 中点 randomUnit=0.5 对应 1.0x 基础值：500 -> 1000 -> 2000 -> 4000 -> 8000 -> 8000
        assertThat(SceneLinkManager.connectBackoffMs(1, 0.5)).isEqualTo(500L);
        assertThat(SceneLinkManager.connectBackoffMs(2, 0.5)).isEqualTo(1_000L);
        assertThat(SceneLinkManager.connectBackoffMs(3, 0.5)).isEqualTo(2_000L);
        assertThat(SceneLinkManager.connectBackoffMs(4, 0.5)).isEqualTo(4_000L);
        assertThat(SceneLinkManager.connectBackoffMs(5, 0.5)).isEqualTo(8_000L);
        assertThat(SceneLinkManager.connectBackoffMs(9, 0.5)).isEqualTo(8_000L);
        // ±25% 抖动边界
        assertThat(SceneLinkManager.connectBackoffMs(1, 0.0)).isEqualTo(375L);
        assertThat(SceneLinkManager.connectBackoffMs(1, 1.0)).isEqualTo(625L);

        // PendingSceneEntry 退避带 ±25% 抖动
        var entry = new PendingSceneEntry(1, PlayerEnter.getDefaultInstance(), 1_000L);
        entry.attempts = 1;
        long low = entry.retryDelay(EnterFailure.NODE_MISSING, 1_000L, 0.0);
        long high = entry.retryDelay(EnterFailure.NODE_MISSING, 1_000L, 1.0);
        assertThat(low).isEqualTo(Duration.ofMillis(187).toNanos() + 500_000L);
        assertThat(high).isEqualTo(Duration.ofMillis(312).toNanos() + 500_000L);
    }

    @Test
    void disconnectNotifierRetriesWith1sTo30sJitteredBackoffUntilLoginAcks() throws Exception {
        // 验证退避曲线 1s -> 2s -> 4s -> 8s -> 16s -> 30s 封顶
        assertThat(DisconnectNotifier.backoffMs(1, 0.5)).isEqualTo(1_000L);
        assertThat(DisconnectNotifier.backoffMs(2, 0.5)).isEqualTo(2_000L);
        assertThat(DisconnectNotifier.backoffMs(5, 0.5)).isEqualTo(16_000L);
        assertThat(DisconnectNotifier.backoffMs(6, 0.5)).isEqualTo(30_000L);
        assertThat(DisconnectNotifier.backoffMs(1, 0.0)).isEqualTo(750L);
        assertThat(DisconnectNotifier.backoffMs(1, 1.0)).isEqualTo(1_250L);

        // 验证失败后自动保留同一会话上下文并重试直到 login 确认成功
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        try (var notifier = new DisconnectNotifier(event -> {
            assertThat(event.getSession().getGateInstanceId()).isEqualTo("gate-uuid-1");
            assertThat(event.getSession().getSessionId()).isEqualTo(77);
            if (calls.incrementAndGet() < 2) {
                return CompletableFuture.failedFuture(new RuntimeException("login transient failure"));
            }
            return CompletableFuture.completedFuture(Ack.getDefaultInstance());
        }, exec, () -> 0.0, 128)) {
            SessionClosed event = SessionClosed.newBuilder()
                    .setSession(SessionContext.newBuilder().setGateNodeId(1).setGateInstanceId("gate-uuid-1").setSessionId(77).setPlayerId(9001L))
                    .build();
            notifier.send(event, delivered::countDown, err -> { });
            assertThat(delivered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(2);
            assertThat(notifier.pendingCount()).isZero();
        } finally {
            exec.shutdownNow();
        }
    }
}
