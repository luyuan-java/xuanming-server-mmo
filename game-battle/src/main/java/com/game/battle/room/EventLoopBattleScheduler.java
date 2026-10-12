package com.game.battle.room;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BattleScheduler} 的生产实现：包装 battle 逻辑线程——直连面 {@code ServerBootstrap} child group（单线程
 * {@code NioEventLoopGroup(1, "battle-logic")}）里唯一的 {@link EventLoop}（battle-node-spec §7.3）。
 *
 * <p>计时器用 {@code EventLoop.schedule / scheduleAtFixedRate}；任务包一层：已取消的不执行（双保险，Netty 自己也会跳过已取消的任务），
 * 抛出的异常打 ERROR 后吞掉（周期任务继续）。线程安全。
 */
public final class EventLoopBattleScheduler implements BattleScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventLoopBattleScheduler.class);

    private final EventLoop loop;

    public EventLoopBattleScheduler(EventLoop loop) {
        this.loop = Objects.requireNonNull(loop, "loop");
    }

    /** 被包装的 EventLoop（直连面把 child group 设成只含它的单线程组；DirectClose 的延迟强关也挂在它上面）。 */
    public EventLoop eventLoop() {
        return loop;
    }

    @Override
    public boolean inLoop() {
        return loop.inEventLoop();
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        loop.execute(() -> runGuarded(task, "投递任务"));
    }

    @Override
    public Cancellable after(long delayMs, Runnable task) {
        Objects.requireNonNull(task, "task");
        GuardedTask guarded = new GuardedTask(task, "延迟任务");
        guarded.future = loop.schedule(guarded, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        return guarded;
    }

    @Override
    public Cancellable every(long periodMs, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (periodMs <= 0) {
            throw new IllegalArgumentException("周期必须为正: " + periodMs);
        }
        GuardedTask guarded = new GuardedTask(task, "周期任务");
        guarded.future = loop.scheduleAtFixedRate(guarded, periodMs, periodMs, TimeUnit.MILLISECONDS);
        return guarded;
    }

    @Override
    public int pendingTasks() {
        return loop instanceof SingleThreadEventExecutor executor ? executor.pendingTasks() : 0;
    }

    private static void runGuarded(Runnable task, String what) {
        try {
            task.run();
        } catch (Throwable t) {
            log.error("battle 逻辑线程上的{}抛出异常（已吞掉，逻辑线程与其余计时器照常运行）", what, t);
        }
    }

    /** 带取消标记的任务包装：取消后即使 Netty 已把它挪进待执行队列也不会再执行。 */
    private static final class GuardedTask implements Runnable, Cancellable {

        private final Runnable task;
        private final String what;
        private volatile boolean cancelled;
        private volatile ScheduledFuture<?> future;

        GuardedTask(Runnable task, String what) {
            this.task = task;
            this.what = what;
        }

        @Override
        public void run() {
            if (!cancelled) {
                runGuarded(task, what);
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            ScheduledFuture<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
        }
    }
}
