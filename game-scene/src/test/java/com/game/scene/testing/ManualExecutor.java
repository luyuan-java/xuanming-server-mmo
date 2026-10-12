package com.game.scene.testing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 手动驱动的「场景逻辑线程」：{@link #execute} 只入队，测试在自己的线程上调 {@link #runAll()} / {@link #runNext()} 才真的跑。
 *
 * <p>为什么不用 {@code Runnable::run}：生产的 {@code logic.execute} 一定入队、回调在<b>之后</b>的任务里跑；内联执行会让回调在发起调用的方法
 * （备战、reaper）里重入，掩盖「先发后回」的次序问题。配合 {@link FakeBattleLocks}：假 Redis 的 future 完成时回调只是入队到这里，
 * 测试决定何时、按什么次序让它们跑。
 *
 * <p>只在一个线程上用（测试线程）。
 */
public final class ManualExecutor implements Executor {

    /** 一次 {@link #runAll()} 最多跑的任务数（任务里又入队任务时防死循环）。 */
    private static final int RUN_LIMIT = 100_000;

    private final Deque<Runnable> queue = new ArrayDeque<>();
    private boolean rejecting;
    private long executed;

    @Override
    public void execute(Runnable task) {
        if (rejecting) {
            throw new RejectedExecutionException("逻辑线程已停止（测试）");
        }
        queue.add(task);
    }

    /** 跑到队列空为止（跑的过程中新入队的也跑）；返回这次跑了几个任务。任务抛出的异常原样抛给调用方。 */
    public int runAll() {
        int ran = 0;
        while (!queue.isEmpty()) {
            if (++ran > RUN_LIMIT) {
                throw new IllegalStateException("逻辑队列跑了 " + RUN_LIMIT + " 个任务还没空，任务在互相入队？");
            }
            Runnable task = queue.poll();
            executed++;
            task.run();
        }
        return ran;
    }

    /** 只跑最早入队的一个任务；队列空时返回 false。 */
    public boolean runNext() {
        Runnable task = queue.poll();
        if (task == null) {
            return false;
        }
        executed++;
        task.run();
        return true;
    }

    /** 还没跑的任务数。 */
    public int pending() {
        return queue.size();
    }

    /** 累计跑过的任务数。 */
    public long executed() {
        return executed;
    }

    /** 模拟逻辑线程已停：之后的 {@link #execute} 一律抛 {@link RejectedExecutionException}（已入队的任务不受影响）。 */
    public void rejectNewTasks(boolean rejecting) {
        this.rejecting = rejecting;
    }
}
