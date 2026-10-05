package com.game.guild.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * guild 的阻塞工作线程池（照 xm-friend 的 FriendWorkerPool；guild-spec §7.4）：MySQL 事务、等待 Redis 结果都在这里执行，
 * 不占 Dubbo 线程（AGENTS.md §3）。
 *
 * <p>固定线程数 + 有界队列 + AbortPolicy；队列满时 {@link #execute} 抛 {@link RejectedExecutionException}（派发器回 in-band 14021
 * 「guild service overloaded」，已拍板，不用信封——客户端遇到任何信封错误都会停用整个帮会模块），过载时快速失败而不是把延迟拖过
 * gate 的 5 s Dubbo 超时。关闭时先停止接新任务，最多等 {@code drainTimeout} 再中断剩余任务。线程池标准指标（{@code executor_*}）的
 * {@code name} 标签是池名：请求池 {@value #NAME}；4.5 的资产通道另用同一实现建两个池——同步投递的落库执行器
 * {@value #ASSET_SETTLE_NAME} 与重投循环的 worker {@value #ASSET_WORKER_NAME}（guild-economy-spec §7.4、§8.2）。
 */
public final class GuildWorkerPool implements Executor, AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(GuildWorkerPool.class);

    /** 请求池的线程名前缀与指标 name 标签。 */
    public static final String NAME = "guild-worker";
    /** 同步投递的落库执行器（阻塞 JDBC，700 ms 自有预算）。 */
    public static final String ASSET_SETTLE_NAME = "guild-asset-settle";
    /** 重投循环的 worker（线程数 = workers，即每副本同时在途的资产 RPC 上限）。 */
    public static final String ASSET_WORKER_NAME = "guild-asset-worker";

    private final String name;
    private final ThreadPoolExecutor pool;
    private final Duration drainTimeout;

    public GuildWorkerPool(int threads, int queueCapacity, Duration drainTimeout) {
        this(NAME, threads, queueCapacity, drainTimeout);
    }

    public GuildWorkerPool(String name, int threads, int queueCapacity, Duration drainTimeout) {
        this.name = name;
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name(name + "-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.drainTimeout = drainTimeout;
    }

    @Override
    public void execute(Runnable task) {
        pool.execute(task);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        new ExecutorServiceMetrics(pool, name, Tags.empty()).bindTo(registry);
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("{} 线程池 {} 内未排空，中断剩余任务", name, drainTimeout);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
