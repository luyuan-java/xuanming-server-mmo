package com.game.data.ops;

import com.game.common.id.LeaseGatedSnowflake;
import com.game.common.id.Snowflake;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * xm-data 发号（data-ops-spec §2.3 硬约束）：直写的快照号、作业号（以后的回档 / 回收流水号）<b>必须</b>取自与 scene 同一个全服租约池
 * {@link NodeTypes#SCENE_GUID}（作用域 0）。雪花号 {@code [符号 1][毫秒 41][worker 10][序号 12]} 不含节点类型位：另开租约类型时
 * worker 会与 scene 重叠、同一毫秒同一序号发出相同的号，{@code transaction_log} / {@code player_snapshot} 按主键幂等落库会把后到的一行
 * <b>静默吞掉</b>。同池占号则 worker 互斥，号域天然不重叠。
 *
 * <p>租约在后台线程上申领（不挡启动：Redis 不可用时审计消费照常），申领失败每 {@link #RETRY} 重试；租约丢失后立即停发、重新申领一个新号。
 * 租约无效（还没领到、续期滞后超过 2/3 TTL、已丢失）时 {@link #tryNext()} 为空，调用方回 503 {@code id_unavailable}——
 * 不自造号、不用自增。停服时最后交还（{@link #close()} 由容器在 Web 服务器停下之后调用）。线程安全。
 */
public final class OpsIds implements SmartLifecycle, AutoCloseable {

    /**
     * 生命周期阶段：比 Web 服务器（优雅停机 {@code DEFAULT_PHASE − 1024}、启停 {@code DEFAULT_PHASE − 2048}）更低——
     * 停机时 Web 服务器先停（在途请求做完），再交还租约；而且在单例销毁之前（Redis 客户端那时还活着，交还能成功）。
     */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final Logger log = LoggerFactory.getLogger(OpsIds.class);

    /** 全服租约的作用域：0 = 全服（同 scene 的 GUID_LEASE_SCOPE）。 */
    public static final int SCOPE = 0;
    /** 租约 TTL（同 scene 的 LEASE_TTL）。 */
    public static final Duration LEASE_TTL = Duration.ofSeconds(15);
    public static final Duration RETRY = Duration.ofSeconds(10);

    /** 一份 worker 租约（生产为 {@link NodeIdLease}；测试换替身）。 */
    public interface WorkerLease extends AutoCloseable {
        int workerId();

        /** 现在能否用这个 worker 发号（同 {@link NodeIdLease#isValid()}）。 */
        boolean isValid();

        /** 已确认丢失（同 {@link NodeIdLease#isLost()}）：不会再变回有效。 */
        boolean isLost();

        @Override
        void close();
    }

    /** 申领一份租约；失败抛异常（号段占满、Redis 不可达）。{@code onLost} 在租约确认丢失时回调一次。 */
    @FunctionalInterface
    public interface LeaseSource {
        WorkerLease acquire(Runnable onLost);
    }

    private record Holder(WorkerLease lease, LeaseGatedSnowflake ids) {
    }

    private final LeaseSource source;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clockMs;
    private final Duration retry;
    private volatile Holder current;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * @param scheduler 申领 / 重试在它上面跑（阻塞 Redis 调用，不是 I/O 线程）
     * @param clockMs   雪花的毫秒时钟
     */
    public OpsIds(LeaseSource source, ScheduledExecutorService scheduler, LongSupplier clockMs, Duration retry) {
        this.source = source;
        this.scheduler = scheduler;
        this.clockMs = clockMs;
        this.retry = retry;
    }

    /** 生产用的租约来源：{@link NodeTypes#SCENE_GUID} 池、作用域 0、worker ∈ [1, {@value Snowflake#MAX_WORKER}]。 */
    public static LeaseSource sceneGuidPool(Supplier<RedissonClient> redis, ScheduledExecutorService renewals,
                                            String instanceId) {
        return onLost -> {
            NodeIdLease lease = NodeIdLease.acquire(redis.get(), renewals, NodeTypes.SCENE_GUID, SCOPE, 1,
                    Snowflake.MAX_WORKER, instanceId, LEASE_TTL, onLost);
            return new WorkerLease() {
                @Override
                public int workerId() {
                    return lease.nodeId();
                }

                @Override
                public boolean isValid() {
                    return lease.isValid();
                }

                @Override
                public boolean isLost() {
                    return lease.isLost();
                }

                @Override
                public void close() {
                    lease.close();
                }
            };
        };
    }

    /** 开始后台申领（立即尝试一次）。 */
    @Override
    public void start() {
        started = true;
        submit(0);
    }

    /** 停止发号并交还租约（同 {@link #close()}）。 */
    @Override
    public void stop() {
        close();
    }

    @Override
    public boolean isRunning() {
        return started && !closed;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** 发一个号；租约无效、时钟回拨超出容忍时为空。 */
    public OptionalLong tryNext() {
        Holder h = current;
        return h == null ? OptionalLong.empty() : h.ids().tryNext();
    }

    /** 当前占着的 worker（排障与测试用）；没有有效租约时为空。 */
    public OptionalInt workerId() {
        Holder h = current;
        return h == null || !h.lease().isValid() ? OptionalInt.empty() : OptionalInt.of(h.lease().workerId());
    }

    private void submit(long delayMs) {
        if (closed) {
            return;
        }
        try {
            scheduler.schedule(this::acquire, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 停服中：调度器已关
        }
    }

    private void acquire() {
        if (closed) {
            return;
        }
        Holder held = current;
        if (held != null) {
            if (!held.lease().isLost()) {
                return;
            }
            // 申领返回之前就已丢失（onLost 回调时还拿不到这一份）：摘掉，重新领
            current = null;
        }
        Holder[] self = new Holder[1];
        WorkerLease lease;
        try {
            lease = source.acquire(() -> onLost(self[0]));
        } catch (RuntimeException e) {
            log.warn("xm-data 全服发号租约（{}）申领失败，{} 秒后重试：{}", NodeTypes.SCENE_GUID, retry.toSeconds(), e.toString());
            submit(retry.toMillis());
            return;
        }
        Holder h = new Holder(lease, new LeaseGatedSnowflake(
                new Snowflake(lease.workerId(), Snowflake.DEFAULT_EPOCH_MS, clockMs), lease::isValid));
        self[0] = h;
        current = h;
        if (closed) {
            // close() 与申领并发：自己交还
            current = null;
            lease.close();
            return;
        }
        log.info("xm-data 全服发号就绪 type={} worker={}", NodeTypes.SCENE_GUID, lease.workerId());
    }

    private void onLost(Holder lost) {
        // 只摘掉丢失的那一份（并发下 current 可能已经换成新的）；申领返回之前就丢失的由下一次 acquire 按 isLost 摘掉
        if (lost != null && current == lost) {
            current = null;
        }
        log.error("xm-data 全服发号租约丢失：停止发号（直写快照回 503 id_unavailable），{} 秒后重新申领", retry.toSeconds());
        submit(retry.toMillis());
    }

    @Override
    public void close() {
        closed = true;
        Holder h = current;
        current = null;
        if (h != null) {
            h.lease().close();
        }
    }
}
