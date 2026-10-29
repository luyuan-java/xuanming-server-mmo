package com.game.data.consume;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** 每个目标仅 consumer 标签；topic、组、代次、分区与 offset 原件留在鉴权诊断面。 */
public final class AuditProbeMetrics {
    private final Map<AuditOffsetSnapshot.ConsumerName, Counter[]> attempts =
            new EnumMap<>(AuditOffsetSnapshot.ConsumerName.class);

    public AuditProbeMetrics(MeterRegistry registry, Map<AuditOffsetSnapshot.ConsumerName, AuditProbeState> states) {
        states.forEach((consumer, state) -> {
            String label = consumer.label();
            Gauge.builder("game.data.audit.group.lag", state, s -> s.view().lag())
                    .description("broker 组级积压 offset 差总和；失效时 NaN，多副本按 max 聚合")
                    .tag("consumer", label).register(registry);
            Gauge.builder("game.data.audit.group.max.partition.lag", state, s -> s.view().maxPartitionLag())
                    .description("broker 组级最大分区积压 offset 差；失效时 NaN")
                    .tag("consumer", label).register(registry);
            Gauge.builder("game.data.audit.retention.gap", state, s -> s.view().retentionGap())
                    .description("已提交恢复位点低于保留起点的 offset 差；缺提交历史或探针失效时 NaN")
                    .tag("consumer", label).register(registry);
            Gauge.builder("game.data.audit.retention.gap.lowerbound", state, s -> s.view().confirmedGapLowerBound())
                    .description("已知分区已提交位点低于保留起点的 offset 差下界；其他分区无提交历史不掩盖风险")
                    .tag("consumer", label).register(registry);
            Gauge.builder("game.data.audit.uncommitted.partitions", state, s -> s.view().uncommittedPartitions())
                    .description("没有提交历史的分区数量；不能据此证明为新组")
                    .tag("consumer", label).register(registry);
            Gauge.builder("game.data.audit.probe.age.seconds", state, s -> s.view().ageSeconds())
                    .description("距最近成功观测开始的秒数；从未成功时 NaN")
                    .tag("consumer", label).register(registry);
            Gauge.builder("game.data.audit.retention.gap.observed", state, s -> s.view().lastGapEvidence() == null ? 0 : 1)
                    .description("本进程曾观察到保留缺口；恢复后保持 1，重启不构成恢复证明")
                    .tag("consumer", label).register(registry);
            Gauge.builder("game.data.audit.retention.gap.highwater", state, s -> s.view().gapHighWater())
                    .description("本进程曾观察到的缺口 offset 差最大值；不累加重复采样")
                    .tag("consumer", label).register(registry);
            for (AuditProbeState.Status status : AuditProbeState.Status.values()) {
                Gauge.builder("game.data.audit.probe.status", state, s -> s.view().status() == status ? 1 : 0)
                        .description("固定状态 one-hot；UNKNOWN/STALE/STOPPED 不等于健康")
                        .tag("consumer", label).tag("status", status.name().toLowerCase(Locale.ROOT)).register(registry);
            }
            attempts.put(consumer, new Counter[] {
                    Counter.builder("game.data.audit.probe.attempts").tag("consumer", label).tag("outcome", "ok").register(registry),
                    Counter.builder("game.data.audit.probe.attempts").tag("consumer", label).tag("outcome", "error").register(registry)});
        });
    }

    public void attempted(AuditOffsetSnapshot.ConsumerName consumer, boolean successful) {
        attempts.get(consumer)[successful ? 0 : 1].increment();
    }
}
