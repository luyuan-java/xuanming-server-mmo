package com.game.data.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class AuditGroupProbeTest {
    private final List<AuditOffsetSnapshot.Target> targets = List.of(
            new AuditOffsetSnapshot.Target(AuditOffsetSnapshot.ConsumerName.TRANSACTION_LOG, "test-log-g1", 1, "test-log", 1),
            new AuditOffsetSnapshot.Target(AuditOffsetSnapshot.ConsumerName.PLAYER_SNAPSHOT, "test-snapshot-g1", 1, "test-snapshot", 1));
    private final AuditProbeProperties properties = new AuditProbeProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(10));

    private AuditOffsetSnapshot sample(AuditOffsetSnapshot.Target target) {
        return new AuditOffsetSnapshot(target, "id-" + target.consumer(),
                List.of(new AuditOffsetSnapshot.Partition(0, 0, 10L, 30, 20, 0L)), 20, 20, 0, 0);
    }

    @Test void 装配只登记UNKNOWN_启动后独立两目标_停止关闭自己的reader() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        var meters = new SimpleMeterRegistry();
        try {
            var probe = new AuditGroupProbe(targets, properties, () -> {
                created.incrementAndGet();
                return new GroupOffsetReader() {
                    final AtomicBoolean once = new AtomicBoolean();
                    @Override public AuditOffsetSnapshot read(AuditOffsetSnapshot.Target target, AuditProbeBudget budget) {
                        return sample(target);
                    }
                    @Override public void close() { if (once.compareAndSet(false, true)) closed.incrementAndGet(); }
                };
            }, Clock.systemUTC(), meters);
            assertThat(created).hasValue(0);
            assertThat(probe.views()).allSatisfy(view -> assertThat(view.status()).isEqualTo(AuditProbeState.Status.UNKNOWN));
            try {
                probe.start();
                probe.start();
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                        assertThat(probe.views()).allSatisfy(view -> assertThat(view.status()).isEqualTo(AuditProbeState.Status.OK)));
            } finally { probe.stop(); }
            assertThat(created).hasValue(2);
            assertThat(closed).hasValue(2);
            assertThat(probe.isRunning()).isFalse();
            assertThat(probe.views()).allSatisfy(view -> assertThat(view.status()).isEqualTo(AuditProbeState.Status.STOPPED));
        } finally {
            meters.close();
        }
    }

    @Test void 停止时取消在途请求_迟到成功不能覆盖STOPPED() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        var meters = new SimpleMeterRegistry();
        try {
            var probe = new AuditGroupProbe(targets, properties, () -> new GroupOffsetReader() {
                final AtomicBoolean once = new AtomicBoolean();
                @Override public AuditOffsetSnapshot read(AuditOffsetSnapshot.Target target, AuditProbeBudget budget) {
                    entered.countDown();
                    try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                        // 模拟底层收到取消后仍返成功；上层必须再复核生命周期。
                    }
                    return sample(target);
                }
                @Override public void close() {
                    if (once.compareAndSet(false, true)) closes.incrementAndGet();
                    release.countDown();
                }
            }, Clock.systemUTC(), meters);
            try {
                probe.start();
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            } finally { probe.stop(); release.countDown(); }
            assertThat(closes).hasValue(1);
            assertThat(probe.views()).allSatisfy(view -> {
                assertThat(view.status()).isEqualTo(AuditProbeState.Status.STOPPED);
                assertThat(view.lastSample()).isNull();
            });
        } finally {
            meters.close();
        }
    }

    @Test void 一个目标失败不把另一个目标置零或阻止观测() {
        var meters = new SimpleMeterRegistry();
        try {
            var probe = new AuditGroupProbe(targets, properties, () -> new GroupOffsetReader() {
                @Override public AuditOffsetSnapshot read(AuditOffsetSnapshot.Target target, AuditProbeBudget budget) {
                    if (target.consumer() == AuditOffsetSnapshot.ConsumerName.TRANSACTION_LOG) {
                        throw new AuditProbeFailure(AuditProbeFailure.Reason.BROKER);
                    }
                    return sample(target);
                }
                @Override public void close() {}
            }, Clock.systemUTC(), meters);
            try {
                probe.start();
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                    assertThat(probe.views().get(0).reason()).isEqualTo("BROKER");
                    assertThat(probe.views().get(0).lag()).isNaN();
                    assertThat(probe.views().get(1).status()).isEqualTo(AuditProbeState.Status.OK);
                    assertThat(probe.views().get(1).lag()).isEqualTo(20);
                });
            } finally { probe.stop(); }
        } finally {
            meters.close();
        }
    }
}
