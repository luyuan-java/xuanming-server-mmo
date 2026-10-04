package com.game.friend.dispatch;

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
 * friend 的阻塞工作线程池：MySQL 与等待 Redis 结果都在这里执行，不占 Dubbo 线程（AGENTS.md §3）。
 *
 * <p>固定线程数 + 有界队列；队列满时 {@link #execute} 抛 {@link RejectedExecutionException}（调用方回 in-band 1003），
 * 过载时快速失败而不是把延迟拖过 gate 的 5 s Dubbo 超时。关闭时先停止接新任务，最多等 {@code drainTimeout} 再中断剩余任务。
 * 线程池标准指标的 {@code name} 标签是 {@code friend-worker}。
 */
public final class FriendWorkerPool implements Executor, AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(FriendWorkerPool.class);

    static final String METRICS_NAME = "friend-worker";

    private final ThreadPoolExecutor pool;
    private final Duration drainTimeout;

    public FriendWorkerPool(int threads, int queueCapacity, Duration drainTimeout) {
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("friend-worker-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.drainTimeout = drainTimeout;
    }

    @Override
    public void execute(Runnable task) {
        pool.execute(task);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        new ExecutorServiceMetrics(pool, METRICS_NAME, Tags.empty()).bindTo(registry);
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("friend 工作线程池 {} 内未排空，中断剩余任务", drainTimeout);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
