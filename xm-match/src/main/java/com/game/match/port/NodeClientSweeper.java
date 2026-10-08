package com.game.match.port;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 直连客户端缓存的定时清扫：一条自己的守护线程（{@value #THREAD_NAME}），每隔一个间隔让每个 {@link IdleSweep} 清一次空闲条目。
 * 放在自己的线程上是因为销毁 Dubbo 引用会短暂阻塞，不能占凑单线程或工作线程。
 *
 * <p>每一轮都包在 {@code try/catch Throwable} 里（JDK 的调度器遇到一次未捕获的异常就永久停掉后续执行）；某个缓存出错不影响别的。
 * {@link #start} / {@link #close} 幂等；关闭之后不可再启动。线程安全。
 */
public final class NodeClientSweeper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NodeClientSweeper.class);

    static final String THREAD_NAME = "match-rpc-sweep";

    private final List<IdleSweep> caches;
    private final long intervalMs;
    private final Object lifecycle = new Object();
    private ScheduledExecutorService executor;
    private boolean closed;

    /**
     * @param caches   要清扫的缓存
     * @param interval 两轮之间的间隔
     */
    public NodeClientSweeper(List<? extends IdleSweep> caches, Duration interval) {
        this.caches = List.copyOf(Objects.requireNonNull(caches, "caches"));
        this.intervalMs = interval.toMillis();
        if (intervalMs < 1) {
            throw new IllegalArgumentException("清扫间隔必须 ≥ 1 ms: " + interval);
        }
    }

    /** 起清扫线程（第一轮在一个间隔之后）。 */
    public void start() {
        synchronized (lifecycle) {
            if (closed || executor != null) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(THREAD_NAME).daemon(true).factory());
            executor.scheduleWithFixedDelay(this::sweepOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("直连客户端缓存的清扫已启动 interval={}ms caches={}", intervalMs, caches.stream().map(IdleSweep::name).toList());
        }
    }

    /** 清一轮：任何异常都不许逃出去。返回这一轮清掉的条目总数。 */
    int sweepOnce() {
        int evicted = 0;
        for (IdleSweep cache : caches) {
            try {
                evicted += cache.sweepIdle();
            } catch (Throwable t) {
                log.error("清扫直连客户端缓存时出了意料之外的错误（下一轮照常）", t);
            }
        }
        return evicted;
    }

    /** 清扫线程此刻是否起着。 */
    public boolean isRunning() {
        synchronized (lifecycle) {
            return executor != null;
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            closed = true;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }
}
