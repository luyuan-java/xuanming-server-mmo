package com.game.match.challenge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * {@code match-push}：切磋推送（156 / 154）的回调执行器（match-spec §9.3、§6.3）。推送的 stage 在 Redis 客户端线程上完成、gather 的 future 在
 * gather 的虚拟线程上完成，两处的回调都<b>不得阻塞</b>——挂在它们上面的后续动作（给结果计数、gather 失败后再推一次 154）一律切到这里，
 * 不占别人的线程。
 *
 * <p>有界：固定 {@value #THREADS} 条线程、队列 {@value #QUEUE}。放上来的任务本身都不阻塞（推送是异步发布、计数是内存操作），所以队列满
 * （或执行器已关闭）时<b>退回到提交线程上直接执行</b>，不丢任务——丢掉的话等在回调后面的 future 永远不会完成。
 * 线程池标准指标（{@code executor_*}）的 {@code name} 标签是 {@value #NAME}。线程安全。
 */
public final class MatchPushExecutor implements Executor, AutoCloseable, MeterBinder {

    /** 线程名前缀与指标 name 标签。 */
    public static final String NAME = "match-push";
    static final int THREADS = 2;
    static final int QUEUE = 1024;

    private final ThreadPoolExecutor pool;

    public MatchPushExecutor() {
        this.pool = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE),
                Thread.ofPlatform().name(NAME + "-", 0).daemon(true).factory(),
                (task, executor) -> task.run());
    }

    @Override
    public void execute(Runnable task) {
        pool.execute(task);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        new ExecutorServiceMetrics(pool, NAME, Tags.empty()).bindTo(registry);
    }

    /** 停止接新任务（之后提交的任务在提交线程上执行）；已排队的任务照常跑完，不等待。 */
    @Override
    public void close() {
        pool.shutdown();
    }
}
