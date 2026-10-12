package com.game.guild.asset;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 资产通道在进程里的后台运行件：重投循环（通道开启时）、客户端缓存清扫（通道开启时）与账本清理（{@code cleanup-enabled} 时）的启停
 * （基线 svc/asset_op.go 的 AssetPipeline + guild.go:378-393；guild-economy-spec §2.15、§7.7）。
 *
 * <p><b>顺序</b>（{@link SmartLifecycle}，phase = {@link #DEFAULT_PHASE}：最后启动、最先停止）：
 * <ul>
 *   <li>启动：所有单例（配表与经济表校验、建表、版本与影响行数语义检查、哨兵行、雪花租约、通道装配——签名器缺密钥即拒启、重建排行）都已就绪，
 *       Store 的终结回调（推送）在构造时就已绑定，之后才启动循环与清理。Spring 的 Dubbo 在上下文刷新完成事件里导出，紧随其后——循环先起无害：
 *       它不依赖本进程的 Dubbo 提供方；资产通道客户端缓存的 Dubbo 模型在第一次调用时才建，那时 Spring 的 Dubbo 早已初始化
 *       （{@code IsolatedDubboModule} 关于缺省框架模型的约束）。</li>
 *   <li>停止：Spring 先发上下文关闭事件（Dubbo 注销并等在途调用），再停本组件：循环不再领新行、<b>等 worker 做完手上那一行</b>（落库 700 ms 不受取消），
 *       清理停在当前行；之后才销毁落库执行器、worker 池、资产通道客户端、调度器、Redis 与连接池（bean 依赖逆序）。</li>
 * </ul>
 * 线程安全。
 */
public final class GuildAssetRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GuildAssetRuntime.class);

    /** 客户端缓存清扫的间隔。 */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private final AssetOpLoop loop;
    private final AssetOpCleanup cleanup;
    private final SceneEndpointSweeper sweeper;
    private final Duration stopTimeout;
    private final Object lifecycle = new Object();
    private volatile boolean running;
    /** 持 {@link #lifecycle}。 */
    private ScheduledExecutorService sweepScheduler;

    /** 不清扫客户端缓存（测试、通道关闭）。 */
    public GuildAssetRuntime(AssetOpLoop loop, AssetOpCleanup cleanup, Duration stopTimeout) {
        this(loop, cleanup, null, stopTimeout);
    }

    /**
     * @param loop        重投循环；null = 通道关闭
     * @param cleanup     账本清理；null = 清理关闭
     * @param sweeper     资产通道客户端缓存清扫；null = 不清扫
     * @param stopTimeout 停止时等循环 / 清理的上限
     */
    public GuildAssetRuntime(AssetOpLoop loop, AssetOpCleanup cleanup, SceneEndpointSweeper sweeper, Duration stopTimeout) {
        this.loop = loop;
        this.cleanup = cleanup;
        this.sweeper = sweeper;
        this.stopTimeout = stopTimeout;
    }

    /** 重投循环（同步投递也用它的 {@link AssetOpLoop#processOne}）；空 = 资产通道关闭。 */
    public Optional<AssetOpLoop> loop() {
        return Optional.ofNullable(loop);
    }

    public Optional<AssetOpCleanup> cleanup() {
        return Optional.ofNullable(cleanup);
    }

    @Override
    public void start() {
        if (loop != null) {
            loop.start();
        }
        if (cleanup != null) {
            cleanup.start();
        }
        if (sweeper != null) {
            synchronized (lifecycle) {
                if (sweepScheduler == null) {
                    sweepScheduler = Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform().name("guild-asset-sweep").daemon(true).factory());
                    sweepScheduler.scheduleWithFixedDelay(this::sweepQuietly, SWEEP_INTERVAL.toMillis(), SWEEP_INTERVAL.toMillis(),
                            TimeUnit.MILLISECONDS);
                }
            }
        }
        running = true;
    }

    private void sweepQuietly() {
        try {
            sweeper.sweep();
        } catch (Throwable t) {
            log.error("[AssetOp] 清扫资产通道客户端缓存出错（下一轮照常）", t);
        }
    }

    @Override
    public void stop() {
        running = false;
        ScheduledExecutorService s;
        synchronized (lifecycle) {
            s = sweepScheduler;
            sweepScheduler = null;
        }
        if (s != null) {
            s.shutdownNow();
        }
        if (loop != null) {
            loop.stop(stopTimeout);
        }
        if (cleanup != null) {
            cleanup.stop(stopTimeout);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
    }
}
