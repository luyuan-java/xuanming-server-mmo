package com.game.team.dispatch;

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
 * team 的阻塞线程池（照 xm-friend 的 FriendWorkerPool，team-spec §6.4）。两个实例：
 * <ul>
 *   <li>{@value #WORKER}：处理客户端请求（等 Redis 结果、读 MySQL 都在这里，不占 Dubbo 线程，AGENTS.md §3）；</li>
 *   <li>{@value #PUSH}：提交后的推送批（展示资料要读 MySQL，所以允许阻塞），不与请求争线程。</li>
 * </ul>
 *
 * <p>固定线程数 + 有界队列；队列满时 {@link #execute} 抛 {@link RejectedExecutionException}（请求回 in-band 4030，推送批整批放弃），
 * 过载时快速失败而不是把延迟拖过 gate 的 5 s Dubbo 超时。关闭时先停止接新任务，最多等 {@code drainTimeout} 再中断剩余任务。
 * 线程池标准指标（{@code executor_*}）的 {@code name} 标签是池名。
 */
public final class TeamWorkerPool implements Executor, AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(TeamWorkerPool.class);

    /** 请求工作池的名字（线程名前缀与指标 name 标签）。 */
    public static final String WORKER = "team-worker";
    /** 推送池的名字。 */
    public static final String PUSH = "team-push";

    private final String name;
    private final ThreadPoolExecutor pool;
    private final Duration drainTimeout;

    public TeamWorkerPool(String name, int threads, int queueCapacity, Duration drainTimeout) {
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
