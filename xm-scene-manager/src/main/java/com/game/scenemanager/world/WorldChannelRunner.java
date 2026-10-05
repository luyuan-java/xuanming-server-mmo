package com.game.scenemanager.world;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 控制面的两条线程（scene-channels-spec §4.1）：{@code scene-manager-world} 每 {@code tick} 跑一拍 {@link WorldChannelCoordinator#tick()}；
 * {@code scene-manager-world-renew} 每 TTL/3 续期全部领导锁。两者分开，一拍再慢（Redis 抖动、计划很大）也不拖住续期。
 *
 * <p>每次执行单独兜住异常（{@link ScheduledExecutorService} 遇到未捕获异常会静默停掉后续执行——对应基线 safego.Loop 每轮单独 recover，
 * world_autoscale.go:136-141）。{@link #close()}：先停 tick 并等在途的一拍结束，再停续期，最后属主校验放掉全部锁。
 */
public final class WorldChannelRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorldChannelRunner.class);
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final WorldChannelCoordinator coordinator;
    private final ScheduledExecutorService tickExecutor;
    private final ScheduledExecutorService renewExecutor;

    public WorldChannelRunner(WorldChannelCoordinator coordinator, Duration tick, Duration renewInterval) {
        this.coordinator = coordinator;
        this.tickExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("scene-manager-world").daemon(true).factory());
        this.renewExecutor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("scene-manager-world-renew").daemon(true).factory());
        long renewMs = Math.max(1, renewInterval.toMillis());
        renewExecutor.scheduleAtFixedRate(() -> guarded("续期", coordinator::renewLeaders), renewMs, renewMs,
                TimeUnit.MILLISECONDS);
        // 启动后立即跑第一拍（对应基线启动即 fullSync）；之后两拍之间隔 tick（上一拍慢了不叠拍）
        tickExecutor.scheduleWithFixedDelay(() -> guarded("一拍", coordinator::tick), 0, Math.max(1, tick.toMillis()),
                TimeUnit.MILLISECONDS);
        log.info("频道计划控制面已启动 tick={} renew={} token={}", tick, renewInterval, coordinator.token());
    }

    private static void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (Throwable e) {
            log.error("频道计划控制面{}异常（下一轮照常）", what, e);
        }
    }

    @Override
    public void close() {
        tickExecutor.shutdown();
        try {
            if (!tickExecutor.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("频道计划的一拍 {} 内没结束，强行停止（写入被令牌围栏兜住）", CLOSE_WAIT);
                tickExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            tickExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        renewExecutor.shutdownNow();
        coordinator.releaseAll();
        log.info("频道计划控制面已停止，领导锁已释放");
    }
}
