package com.game.login.dispatch;

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
 * login 的阻塞工作线程池：MySQL 等阻塞 I/O 都在这里执行，不占 Dubbo 的线程。
 *
 * <p>固定线程数 + 有界队列；队列满时 {@link #execute} 抛 {@link RejectedExecutionException}（调用方回「服务不可用」），
 * 过载时快速失败而不是无限堆积、把延迟拖过客户端 15s 预算。
 *
 * <p>关闭：先停止接新任务，最多等 {@code drainTimeout} 让在途任务跑完，再中断剩余任务。
 * 在途任务的阻塞 I/O 有 socket 超时兜底，所以正常情况下都能在等待期内结束。
 *
 * <p>指标：作为 {@link MeterBinder} 由 Spring Boot 自动绑定到注册表，导出 Micrometer 标准的线程池指标
 * （{@code executor.queued} / {@code executor.active} / {@code executor.completed} / {@code executor.queue.remaining} …，
 * 标签 {@code name=login-worker}）；队列满被拒的请求见 {@code xm.login.requests{result=overloaded}}。
 */
public final class LoginWorkerPool implements Executor, AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(LoginWorkerPool.class);

    /** 线程池指标的 {@code name} 标签。 */
    static final String METRICS_NAME = "login-worker";

    private final ThreadPoolExecutor pool;
    private final Duration drainTimeout;

    public LoginWorkerPool(int threads, int queueCapacity, Duration drainTimeout) {
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("login-worker-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.drainTimeout = drainTimeout;
    }

    @Override
    public void execute(Runnable task) {
        pool.execute(task);
    }

    /** 只绑定线程池的只读状态，不包装执行器（{@link #execute} 的拒绝语义不变）。 */
    @Override
    public void bindTo(MeterRegistry registry) {
        new ExecutorServiceMetrics(pool, METRICS_NAME, Tags.empty()).bindTo(registry);
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(drainTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("login 工作线程池 {} 内未排空，中断剩余任务", drainTimeout);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
