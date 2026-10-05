package com.game.trade.dispatch;

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
 * trade 的阻塞工作线程池（照 xm-friend 的 FriendWorkerPool；trade-spec §5.1、§5.4）：MySQL（含归属区查询）都在这里执行，
 * 不占 Dubbo 线程（AGENTS.md §3）。
 *
 * <p>固定线程数 + 有界队列 + AbortPolicy：队列满时 {@link #execute} 抛 {@link RejectedExecutionException}（派发器回 in-band 1003、不带原因串，T7），
 * 过载时快速失败而不是把延迟拖过 gate 的 5 s Dubbo 超时。关闭时先停止接新任务，最多等 {@code drainTimeout} 再中断剩余任务。
 * 线程池标准指标（{@code executor_*}）的 {@code name} 标签是 {@value #NAME}。
 */
public final class TradeWorkerPool implements Executor, AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(TradeWorkerPool.class);

    /** 线程名前缀与指标 name 标签。 */
    public static final String NAME = "trade-worker";

    private final ThreadPoolExecutor pool;
    private final Duration drainTimeout;

    public TradeWorkerPool(int threads, int queueCapacity, Duration drainTimeout) {
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name(NAME + "-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.drainTimeout = drainTimeout;
    }

    @Override
    public void execute(Runnable task) {
        pool.execute(task);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        new ExecutorServiceMetrics(pool, NAME, Tags.empty()).bindTo(registry);
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("{} 线程池 {} 内未排空，中断剩余任务", NAME, drainTimeout);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
