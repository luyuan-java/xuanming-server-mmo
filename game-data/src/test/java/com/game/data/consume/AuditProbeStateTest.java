package com.game.data.consume;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AuditProbeStateTest {
    private final AtomicLong nanos = new AtomicLong();
    private final AuditOffsetSnapshot.Target target = new AuditOffsetSnapshot.Target(
            AuditOffsetSnapshot.ConsumerName.TRANSACTION_LOG, "audit-test-g7", 7, "audit-test-group", 1);
    private final AuditProbeState state = new AuditProbeState(target, Duration.ofSeconds(90), nanos::get);

    private AuditOffsetSnapshot sample(String id, long gap, boolean uncommitted) {
        return new AuditOffsetSnapshot(target, id,
                List.of(new AuditOffsetSnapshot.Partition(0, 10, uncommitted ? null : 10 - gap, 30, 20 + gap,
                        uncommitted ? null : gap)), 20 + gap, 20 + gap, gap, uncommitted ? 1 : 0);
    }

    @Test void 未观测UNKNOWN且积压NaN() {
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.UNKNOWN);
        assertThat(state.view().lag()).isNaN();
        assertThat(state.view().ageSeconds()).isNaN();
    }

    @Test void 失败保留原件但不再暴露健康数值() {
        var original = sample("topic-a", 0, false);
        state.succeeded(original, 0, 1000);
        state.failed(AuditProbeFailure.Reason.BROKER, 2000);
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.UNKNOWN);
        assertThat(state.view().lastSample()).isSameAs(original);
        assertThat(state.view().lag()).isNaN();
        assertThat(state.view().lastSuccessMillis()).isEqualTo(1000);
    }

    @Test void 到达过期边界即STALE_从读取开始算() {
        nanos.set(Duration.ofSeconds(10).toNanos());
        state.succeeded(sample("topic-a", 0, false), 0, 1000);
        nanos.set(Duration.ofSeconds(90).toNanos());
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.STALE);
        assertThat(state.view().ageSeconds()).isEqualTo(90);
        assertThat(state.view().lag()).isNaN();
    }

    @Test void 无提交历史与正常组区分_缺口不能报零() {
        state.succeeded(sample("topic-a", 0, true), 0, 1000);
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.NO_COMMIT_HISTORY);
        assertThat(state.view().lag()).isEqualTo(20);
        assertThat(state.view().retentionGap()).isNaN();
        assertThat(state.view().uncommittedPartitions()).isEqualTo(1);
    }

    @Test void 恢复不抹掉曾见缺口_重复采样不累计丢失数() {
        var evidence = sample("topic-a", 7, false);
        state.succeeded(evidence, 0, 1000);
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.RETENTION_GAP);
        state.succeeded(evidence, 0, 1500);
        state.succeeded(sample("topic-a", 0, false), 0, 2000);
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.OK);
        assertThat(state.view().retentionGap()).isZero();
        assertThat(state.view().lastGapEvidence()).isSameAs(evidence);
        assertThat(state.view().gapHighWater()).isEqualTo(7);
    }

    @Test void 部分分区无历史不隐藏其他分区的确认缺口下界() {
        var mixedTarget = new AuditOffsetSnapshot.Target(target.consumer(), target.topic(), 7, target.group(), 2);
        var mixed = new AuditProbeState(mixedTarget, Duration.ofSeconds(90), nanos::get);
        var sample = new AuditOffsetSnapshot(mixedTarget, "topic-a", List.of(
                new AuditOffsetSnapshot.Partition(0, 10, 3L, 30, 27, 7L),
                new AuditOffsetSnapshot.Partition(1, 20, null, 30, 10, null)), 37, 27, 7, 1);
        mixed.succeeded(sample, 0, 1000);
        assertThat(mixed.view().status()).isEqualTo(AuditProbeState.Status.RETENTION_GAP);
        assertThat(mixed.view().retentionGap()).isNaN();
        assertThat(mixed.view().confirmedGapLowerBound()).isEqualTo(7);
        try (var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) {
            new AuditProbeMetrics(registry, Map.of(target.consumer(), mixed));
            assertThat(registry.scrape())
                    .contains("game_data_audit_retention_gap{consumer=\"transaction_log\"} NaN")
                    .contains("game_data_audit_retention_gap_lowerbound{consumer=\"transaction_log\"} 7.0");
        }
    }

    @Test void 同代topic重建不会被后续采样自动接受() {
        var original = sample("topic-a", 0, false);
        assertThat(state.succeeded(original, 0, 1000)).isTrue();
        assertThat(state.succeeded(sample("topic-b", 0, false), 0, 2000)).isFalse();
        assertThat(state.succeeded(sample("topic-b", 0, false), 0, 3000)).isFalse();
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.UNKNOWN);
        assertThat(state.view().reason()).isEqualTo("TOPIC_CHANGED");
        assertThat(state.view().lastSample()).isSameAs(original);
    }

    @Test void nanoTime绕回不误判长时间_时钟倒退则失效() {
        long start = Long.MAX_VALUE - 5;
        nanos.set(Long.MIN_VALUE + 4);
        state.succeeded(sample("topic-a", 0, false), start, 1000);
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.OK);
        nanos.set(start - 1);
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.STALE);
    }

    @Test void 停止后原件保留_指标无效() {
        state.succeeded(sample("topic-a", 3, false), 0, 1000);
        state.stopped();
        assertThat(state.view().status()).isEqualTo(AuditProbeState.Status.STOPPED);
        assertThat(state.view().lag()).isNaN();
        assertThat(state.view().lastGapEvidence()).isNotNull();
    }

    @Test void Prometheus名称与未知过期缺口指标闭环_无高基数label() {
        try (var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) {
            var metrics = new AuditProbeMetrics(registry, Map.of(target.consumer(), state));
            assertThat(registry.scrape())
                    .contains("game_data_audit_group_lag{consumer=\"transaction_log\"} NaN")
                    .contains("game_data_audit_probe_status{consumer=\"transaction_log\",status=\"unknown\"} 1.0");
            state.succeeded(sample("topic-a", 7, false), 0, 1000);
            metrics.attempted(target.consumer(), true);
            assertThat(registry.scrape())
                    .contains("game_data_audit_group_max_partition_lag{consumer=\"transaction_log\"} 27.0")
                    .contains("game_data_audit_retention_gap{consumer=\"transaction_log\"} 7.0")
                    .contains("game_data_audit_retention_gap_observed{consumer=\"transaction_log\"} 1.0")
                    .contains("game_data_audit_probe_attempts_total{consumer=\"transaction_log\",outcome=\"ok\"} 1.0");
            nanos.set(Duration.ofSeconds(91).toNanos());
            assertThat(registry.scrape()).contains("game_data_audit_probe_status{consumer=\"transaction_log\",status=\"stale\"} 1.0");
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags()).allSatisfy(tag ->
                            assertThat(tag.getKey()).isIn("consumer", "status", "outcome")));
        }
    }
}
