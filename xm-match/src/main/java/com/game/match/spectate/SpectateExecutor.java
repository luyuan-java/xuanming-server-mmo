package com.game.match.spectate;

import com.game.match.lifecycle.InflightWatches;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 163 观战自己的执行器（spectate-spec §4.9、W10；lead 裁决 2）：每个受理的请求一条<b>虚拟线程</b>（名字 {@code match-spectate-<序号>}），
 * 全局在途上限 {@code xm.match.spectate.max-inflight}（缺省 128）。经 {@code MatchMethodHandler.executor()} 交给派发器；同时就是停机时
 * 「等在途 163」的口（{@link InflightWatches}）。
 *
 * <p><b>为什么不用 {@code match-worker}</b>：一次 163 最坏要同步等两跳各约 3 s 的 battle RPC（换场的 RemoveObserver、登记的 AddObserver）。
 * 放在 16 条固定线程的工作池上，一个慢的 battle 节点就能把排队 157、取消 148、查状态 153、补签 179 挤成过载。虚拟线程挂起时不占载体线程，
 * 163 的流程里每一跳都是「异步 API + 在 future 上限时等」，不在 {@code synchronized} 块里阻塞。
 *
 * <p><b>许可</b>（对着 {@code MatchMethodHandler.executor()} 的四条要求写）：
 * <ul>
 *   <li>{@link #execute} 在调用线程（Dubbo 线程）上<b>当场</b>试拿一个许可：不阻塞、不排队。拿不到（在途已满）或已 {@link #close} →
 *       抛 {@link RejectedExecutionException}，派发器据此回该方法的过载应答（163 是 in-band 16004）。</li>
 *   <li>拿到之后起一条虚拟线程跑任务；许可在任务结束时归还——<b>正常返回、抛 RuntimeException、抛 Error 三条出口都在同一个 {@code finally} 里</b>
 *       （请求自己跑到预算到点也是正常返回）。起不了线程（只可能是内存耗尽）时许可当场归还并按拒收处理：任务一行都没有执行。</li>
 *   <li>受理了的任务恰好执行一次；本类不取消、不打断它。</li>
 * </ul>
 * 自我清退的异步 RemoveObserver 与 164 的异步剔除不经过这里，不占许可。
 *
 * <p><b>指标</b>：{@link #inflight()} 由装配接到 {@code xm_match_spectate_inflight}（每实例的真实在途数）。
 *
 * <p>线程安全。在途计数是无锁的原子量；那把锁只给 {@link #awaitIdle} 的等待者用，临界区里不阻塞。
 */
public final class SpectateExecutor implements Executor, InflightWatches, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpectateExecutor.class);

    /** 虚拟线程名的前缀（后面跟序号）。 */
    public static final String THREAD_PREFIX = "match-spectate-";

    private final int maxInflight;
    private final AtomicInteger inflight = new AtomicInteger();
    private final ThreadFactory threads;
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idle = idleLock.newCondition();
    private volatile boolean closed;

    /**
     * @param maxInflight 同时在途的 163 上限（≥ 1；生产取 {@code xm.match.spectate.max-inflight}）
     */
    public SpectateExecutor(int maxInflight) {
        this(maxInflight, Thread.ofVirtual().name(THREAD_PREFIX, 0).factory());
    }

    /** 测试用：可以换掉线程工厂（模拟「起不了线程」）。 */
    SpectateExecutor(int maxInflight, ThreadFactory threads) {
        if (maxInflight < 1) {
            throw new IllegalArgumentException("163 在途上限必须 ≥ 1: " + maxInflight);
        }
        this.maxInflight = maxInflight;
        this.threads = Objects.requireNonNull(threads, "threads");
    }

    /**
     * 受理一个 163：当场拿许可、起一条虚拟线程。不阻塞调用线程，也不在调用线程上跑 {@code task}。
     *
     * @throws RejectedExecutionException 在途已满、已关闭，或起不了线程——三种情况下 {@code task} 都没有执行，许可没有被占着
     */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (closed) {
            throw new RejectedExecutionException("163 的执行器已关闭");
        }
        if (!tryAcquire()) {
            throw new RejectedExecutionException("163 在途已满（上限 " + maxInflight + "）");
        }
        if (closed) { // 拿许可期间被关闭：按拒收处理，不留一条关闭之后才开始的请求
            release();
            throw new RejectedExecutionException("163 的执行器已关闭");
        }
        try {
            threads.newThread(() -> runOne(task)).start();
        } catch (RuntimeException | OutOfMemoryError e) {
            // 起不了线程（实际只可能是内存耗尽）：任务没有执行，许可当场归还，交给派发器按过载应答
            release();
            throw new RejectedExecutionException("起不了 163 的虚拟线程", e);
        }
    }

    /** 一条 163 线程的线程体：任何出口都恰好归还一次许可。 */
    private void runOne(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            // 派发器交进来的任务已经兜住处理器的 RuntimeException；到这里说明约定被破坏。应答由任务自己负责，这里只保证许可归还
            log.error("[spectate] 163 的任务抛出了异常（许可照常归还）", e);
        } finally {
            // Error（StackOverflowError / NoClassDefFoundError 等）同样先还许可，再原样上抛给线程的未捕获处理器
            release();
        }
    }

    private boolean tryAcquire() {
        for (;;) {
            int current = inflight.get();
            if (current >= maxInflight) {
                return false;
            }
            if (inflight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void release() {
        if (inflight.decrementAndGet() == 0) {
            idleLock.lock();
            try {
                idle.signalAll();
            } finally {
                idleLock.unlock();
            }
        }
    }

    /** 此刻在途的 163 数（已发出、还没归还的许可）。 */
    public int inflight() {
        return inflight.get();
    }

    /** 在途上限。 */
    public int maxInflight() {
        return maxInflight;
    }

    /**
     * {@inheritDoc}
     *
     * <p>只是等在途数归零：不关闭执行器、不打断任何请求。被中断时保留中断标志、立刻回 false。
     */
    @Override
    public boolean awaitIdle(Duration timeout) {
        long remaining = timeout == null ? 0 : Math.max(0, timeout.toNanos());
        idleLock.lock();
        try {
            // 归零的那一次归还要先拿到这把锁才能发信号，而这里在「看计数」与「挂起」之间一直持锁：信号不会丢
            while (inflight.get() > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            idleLock.unlock();
        }
    }

    /**
     * 不再受理新的 163（之后的 {@link #execute} 一律拒收）。<b>不等、不打断</b>在途的请求——停机时的有界等待由 {@code MatchLifecycle}
     * 经 {@link #awaitIdle} 做，这里只是单例销毁时的兜底。幂等。
     */
    @Override
    public void close() {
        closed = true;
    }

    @Override
    public String toString() {
        return "SpectateExecutor[inflight=" + inflight() + "/" + maxInflight + (closed ? ", closed" : "") + "]";
    }
}
