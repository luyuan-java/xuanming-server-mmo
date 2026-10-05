package com.game.battle.testing;

import com.game.battle.room.BattleClock;
import com.game.battle.room.BattleScheduler;
import com.game.battle.room.Cancellable;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.RejectedExecutionException;

/**
 * 测试用的 {@link BattleScheduler}：虚拟时间、单线程、完全确定（battle-node-spec §7.6、§13.2）。
 *
 * <ul>
 *   <li>{@link #execute}：进待执行队列，<b>不内联</b>执行（与生产「排在当前任务之后」一致）；{@link #runPending()} 或 {@link #advance} 时执行，
 *       执行中投递的新任务也在同一轮里执行完。</li>
 *   <li>{@link #after} / {@link #every}：按到期时刻（相同时刻按登记先后）执行；{@link #advance(long)} 把虚拟时间逐个推进到每个到期时刻，
 *       执行到期任务后立即跑完待执行队列，最后停在目标时刻。</li>
 *   <li>{@link #clock()}：与虚拟时间同步的墙钟（起点由构造参数给出）。</li>
 *   <li>{@link #inLoop()} 缺省为 true（测试线程就是「逻辑线程」）；{@link #setInLoop(boolean)} 可模拟别的线程来调，验证线程断言。</li>
 * </ul>
 * 非线程安全：只在测试线程上用。
 */
public final class ManualBattleScheduler implements BattleScheduler {

    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final PriorityQueue<Timer> timers =
            new PriorityQueue<>(Comparator.comparingLong((Timer t) -> t.dueMs).thenComparingLong(t -> t.seq));
    private long nowMs;
    private long seq;
    private boolean inLoop = true;
    private boolean shutdown;

    /** @param startEpochMs 虚拟墙钟的起点（Unix 毫秒） */
    public ManualBattleScheduler(long startEpochMs) {
        this.nowMs = startEpochMs;
    }

    /** 虚拟墙钟（{@link BattleClock}），随 {@link #advance} 推进。 */
    public BattleClock clock() {
        return () -> nowMs;
    }

    public long nowMs() {
        return nowMs;
    }

    public void setInLoop(boolean inLoop) {
        this.inLoop = inLoop;
    }

    /** 模拟逻辑线程已关闭：之后的 {@link #execute} / {@link #after} / {@link #every} 抛 {@link RejectedExecutionException}。 */
    public void shutdown() {
        this.shutdown = true;
    }

    @Override
    public boolean inLoop() {
        return inLoop;
    }

    @Override
    public void execute(Runnable task) {
        rejectIfShutdown();
        pending.add(task);
    }

    @Override
    public Cancellable after(long delayMs, Runnable task) {
        rejectIfShutdown();
        Timer timer = new Timer(nowMs + Math.max(0, delayMs), 0, seq++, task);
        timers.add(timer);
        return timer;
    }

    @Override
    public Cancellable every(long periodMs, Runnable task) {
        if (periodMs <= 0) {
            throw new IllegalArgumentException("周期必须为正: " + periodMs);
        }
        rejectIfShutdown();
        Timer timer = new Timer(nowMs + periodMs, periodMs, seq++, task);
        timers.add(timer);
        return timer;
    }

    @Override
    public int pendingTasks() {
        return pending.size();
    }

    /** 还挂着（没取消、没执行完）的计时器个数。 */
    public int scheduledCount() {
        return (int) timers.stream().filter(t -> !t.cancelled).count();
    }

    /** 跑完待执行队列（含执行中新投递的）。返回执行了几个任务。 */
    public int runPending() {
        int ran = 0;
        Runnable task;
        while ((task = pending.poll()) != null) {
            task.run();
            ran++;
        }
        return ran;
    }

    /** 虚拟时间前进 {@code ms} 毫秒，按到期顺序执行其间到期的计时器（每个之后跑完待执行队列）。 */
    public void advance(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("不能倒退: " + ms);
        }
        advanceTo(nowMs + ms);
    }

    /** 虚拟时间推进到 {@code targetMs}（Unix 毫秒）。 */
    public void advanceTo(long targetMs) {
        runPending();
        while (true) {
            Timer next = timers.peek();
            if (next == null || next.dueMs > targetMs) {
                break;
            }
            timers.poll();
            if (next.cancelled) {
                continue;
            }
            nowMs = Math.max(nowMs, next.dueMs);
            if (next.periodMs > 0) {
                next.dueMs += next.periodMs;
                next.seq = seq++;
                timers.add(next);
            } else {
                next.cancelled = true;
            }
            next.task.run();
            runPending();
        }
        nowMs = Math.max(nowMs, targetMs);
    }

    private void rejectIfShutdown() {
        if (shutdown) {
            throw new RejectedExecutionException("逻辑线程已关闭（测试）");
        }
    }

    private static final class Timer implements Cancellable {

        private long dueMs;
        private final long periodMs;
        private long seq;
        private final Runnable task;
        private boolean cancelled;

        Timer(long dueMs, long periodMs, long seq, Runnable task) {
            this.dueMs = dueMs;
            this.periodMs = periodMs;
            this.seq = seq;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
