package com.game.discovery.workshard;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * 后台循环活性与 64 片分工覆盖率的 Micrometer 指标绑定：
 * <ul>
 *   <li>{@value #METRIC_LAST_COMPLETED}（tags: {@code service}, {@code loop}）：每轮正常完成后更新，有意关闭时摘除；</li>
 *   <li>{@value #METRIC_LOOP_PANIC}（tags: {@code service}, {@code loop}）：每轮兜住未捕获异常时递增，供通用告警触发；</li>
 *   <li>{@value #METRIC_OWNED_SHARDS}（tags: {@code service}, {@code kind}）：本副本当前负责片数（0..64），各副本求和低于 64 即缺片告警。</li>
 * </ul>
 */
public final class LoopLivenessMeters {

    public static final String METRIC_LAST_COMPLETED = "game.loop.last_completed.epoch_seconds";
    public static final String METRIC_LOOP_PANIC = "game.loop.panic.total";
    public static final String METRIC_OWNED_SHARDS = "game.workshard.owned_shards";

    private LoopLivenessMeters() {
    }

    /** 绑定一个后台循环的活性 Gauge 与 panic Counter，有意退出时自动摘除 Gauge。 */
    public static LoopLiveness bind(MeterRegistry registry, LoopLiveness liveness) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(liveness, "liveness");
        Counter panicCounter = Counter.builder(METRIC_LOOP_PANIC)
                .description("后台循环单轮兜住未捕获异常次数")
                .tag("service", liveness.service())
                .tag("loop", liveness.loop())
                .register(registry);
        Gauge gauge = Gauge.builder(METRIC_LAST_COMPLETED, liveness, LoopLiveness::lastCompletedEpochSeconds)
                .description("后台循环最近一次完整跑完一轮的 Unix 秒")
                .tag("service", liveness.service())
                .tag("loop", liveness.loop())
                .register(registry);
        liveness.bindCallbacks(panicCounter::increment, () -> registry.remove(gauge));
        return liveness;
    }

    /** 绑定一类 64 片分工工作当前本副本承担的片数（供 sum < 64 缺片告警使用）。 */
    public static Gauge bindOwnedShards(MeterRegistry registry, String service, String kind, WorkShard.Membership membership) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(membership, "membership");
        return Gauge.builder(METRIC_OWNED_SHARDS, membership, m -> m.ownedCount(kind))
                .description("本副本在 64 片 HRW 分工中当前负责的片数（全集群求和应等于 64）")
                .tag("service", service)
                .tag("kind", kind)
                .register(registry);
    }
}
