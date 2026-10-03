package com.game.scene.gainblock;

import com.game.scene.metrics.SceneMetrics;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把全服产出封禁名单从 Redis 同步到本节点。读 Redis 是阻塞 I/O，不上逻辑线程：启动时那一次在启动线程上，之后都在专用的单线程
 * {@code scene-gain-block} 上。读取与交出在同一把锁里串行，所以投递给逻辑线程的快照按读取先后到达，旧的盖不过新的；
 * 读到的快照交给 {@code onLoaded}（调用方负责投递到逻辑线程）。
 *
 * <p>触发：启动时同步读一次（读不到就拒绝启动——场景节点本来就离不开 Redis）；之后收到变更通知就重读（Redis pub/sub，
 * 通知可能丢，所以另有周期重读兜底）。重读失败沿用上次的名单、计数并告警（只在从成功变失败、从失败变成功时各记一行，不刷屏），
 * {@code xm.scene.gain.block.sync.age} 随之增长。
 */
public final class GainBlockSync {

    private static final Logger log = LoggerFactory.getLogger(GainBlockSync.class);

    /** 名单来源（阻塞调用；失败抛异常）。 */
    @FunctionalInterface
    public interface Source {
        GlobalGainBlocks load();
    }

    private final Source source;
    private final Consumer<GlobalGainBlocks> onLoaded;
    private final SceneMetrics metrics;
    private final LongSupplier nanoTime;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    /** 上次成功同步的单调时刻；{@link Long#MIN_VALUE} = 还没成功过（同步时长报 NaN，不误触告警）。 */
    private volatile long lastSuccessNanos = Long.MIN_VALUE;
    private volatile int entries;
    private boolean failing;
    private GlobalGainBlocks last;
    /** 生命周期（起 / 停同步线程）单独一把锁：停止不必等一次卡在 Redis 上的重读结束，中断能打断它。 */
    private final Object lifecycle = new Object();
    private volatile ScheduledExecutorService executor;

    public GainBlockSync(Source source, Consumer<GlobalGainBlocks> onLoaded, SceneMetrics metrics, LongSupplier nanoTime) {
        this.source = source;
        this.onLoaded = onLoaded;
        this.metrics = metrics;
        this.nanoTime = nanoTime;
    }

    /** 启动时同步读一次并交出；失败直接抛（调用方据此拒绝启动）。 */
    public synchronized GlobalGainBlocks loadNow() {
        GlobalGainBlocks blocks = source.load();
        accept(blocks);
        return blocks;
    }

    /** 起同步线程，按 {@code interval} 周期重读。 */
    public void start(Duration interval) {
        synchronized (lifecycle) {
            if (executor != null) {
                return;
            }
            ScheduledExecutorService e =
                    Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("scene-gain-block", true));
            long millis = interval.toMillis();
            e.scheduleWithFixedDelay(this::refreshQuietly, millis, millis, TimeUnit.MILLISECONDS);
            executor = e;
        }
    }

    /** 本节点当前名单的条目数（指标用，任意线程）。 */
    public int entries() {
        return entries;
    }

    /** 距上次成功同步的秒数；还没成功过为 NaN（指标用，任意线程）。 */
    public double syncAgeSeconds() {
        long last = lastSuccessNanos;
        return last == Long.MIN_VALUE ? Double.NaN : (nanoTime.getAsLong() - last) / 1e9;
    }

    /**
     * 请求尽快重读（任意线程，含 Redisson 的网络线程；不阻塞）。连续的请求合并成一次：标记在重读开始前清掉，
     * 所以重读期间又来的变更会再触发一次。
     */
    public void requestRefresh() {
        ScheduledExecutorService e = executor;
        if (e == null || !refreshQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            e.execute(() -> {
                refreshQueued.set(false);
                refreshQuietly();
            });
        } catch (RejectedExecutionException ex) {
            refreshQueued.set(false);
        }
    }

    /** 停同步线程：中断正在进行的重读（Redisson 的同步调用被中断即抛出），不等它读完。 */
    public void stop() {
        ScheduledExecutorService e;
        synchronized (lifecycle) {
            e = executor;
            executor = null;
        }
        if (e != null) {
            e.shutdownNow();
        }
    }

    /** 同步线程上：重读并交出，失败沿用上次（包内可见供测试直接驱动）。 */
    synchronized void refreshQuietly() {
        try {
            accept(source.load());
            if (failing) {
                failing = false;
                log.info("全服产出封禁名单同步恢复");
            }
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // 停止时被中断：不是同步故障
                return;
            }
            metrics.gainBlockSyncFailed();
            if (!failing) {
                failing = true;
                log.warn("全服产出封禁名单同步失败，沿用上次的名单（{}），恢复前不再重复告警", last, e);
            }
        }
    }

    private void accept(GlobalGainBlocks blocks) {
        lastSuccessNanos = nanoTime.getAsLong();
        entries = blocks.size();
        if (!blocks.equals(last)) {
            log.info("全服产出封禁名单 币种={} 物品={}", blocks.currencies(), blocks.items());
            last = blocks;
        }
        onLoaded.accept(blocks);
    }
}
