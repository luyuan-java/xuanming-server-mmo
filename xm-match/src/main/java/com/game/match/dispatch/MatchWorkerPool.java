package com.game.match.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MatchWorkers} 的实现：固定线程数 + 有界队列 + AbortPolicy（写法同 xm-trade 的 TradeWorkerPool；match-spec §9.3，缺省 16 线程、队列 1024）。
 * 队列满时 {@link #execute} 抛 {@link RejectedExecutionException}，过载时快速失败而不是把延迟拖过 gate 的 5 s Dubbo 超时。
 * 关闭时先停止接新任务，最多等 {@code drainTimeout} 再中断剩余任务。线程池标准指标（{@code executor_*}）的 {@code name} 标签是 {@value #NAME}。
 */
public final class MatchWorkerPool implements MatchWorkers, AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(MatchWorkerPool.class);

    /** 线程名前缀与指标 name 标签。 */
    public static final String NAME = "match-worker";

    private final ThreadPoolExecutor pool;
    private final Duration drainTimeout;

    public MatchWorkerPool(int threads, int queueCapacity, Duration drainTimeout) {
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
