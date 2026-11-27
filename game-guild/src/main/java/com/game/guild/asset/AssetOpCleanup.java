package com.game.guild.asset;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产指令账本的定期清理（基线 asset_store.go:970-1007 RunCleanup、guild.go:390-393 / :426-443；guild-economy-spec §2.11）：每个副本都跑
 * （删除幂等），首轮随机延迟 [0, interval)（多副本错开），之后每 interval 一轮，每轮单独兜底；与资产通道开关互相独立
 * （{@code game.guild.asset-op.cleanup-enabled}）。删什么、怎么分批、保留期规则都在 {@link GuildAssetStore#cleanupOnce}；删掉的行数经 Store 的
 * Listener 计 {@code game_guild_asset_cleanup_deleted_total{table}}。
 *
 * <p>单独一条调度线程 {@code guild-asset-cleanup}（阻塞 JDBC，与重投循环互不占用）。{@link #stop} 让正在跑的那一轮做完当前这一行
 * （中断之后 Store 停止并报错），再关线程。线程安全。
 */
public final class AssetOpCleanup {

    private static final Logger log = LoggerFactory.getLogger(AssetOpCleanup.class);

    private final GuildAssetStore store;
    private final Duration interval;
    private final Duration terminalRetention;
    private final Duration counterRetention;
    private final LongSupplier clockMs;
    private volatile com.game.common.workshard.WorkShard.Membership workShard;
    private volatile com.game.common.workshard.LoopLiveness liveness;
    private final Object lifecycle = new Object();
    /** 持 {@link #lifecycle}。 */
    private ScheduledExecutorService scheduler;

    public AssetOpCleanup(GuildAssetStore store, Duration interval, Duration terminalRetention, Duration counterRetention,
                          LongSupplier clockMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.interval = Objects.requireNonNull(interval, "interval");
        this.terminalRetention = Objects.requireNonNull(terminalRetention, "terminalRetention");
        this.counterRetention = Objects.requireNonNull(counterRetention, "counterRetention");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("清理间隔必须为正: " + interval);
        }
    }

    /** 复用资产重投成员表按 player_id 分 64 片（类别键与重投分开）；未挂载或空表时全片清理。 */
    public AssetOpCleanup withWorkShard(com.game.common.workshard.WorkShard.Membership membership) {
        this.workShard = membership;
        return this;
    }

    public AssetOpCleanup withLiveness(com.game.common.workshard.LoopLiveness liveness) {
        this.liveness = liveness;
        return this;
    }

    /** 启动：首轮随机延迟 [0, interval)。重复调用是空操作。 */
    public void start() {
        long firstDelay;
        synchronized (lifecycle) {
            if (scheduler != null) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("guild-asset-cleanup").daemon(true).factory());
            firstDelay = ThreadLocalRandom.current().nextLong(interval.toMillis());
            scheduler.scheduleWithFixedDelay(this::runOnceGuarded, firstDelay, interval.toMillis(), TimeUnit.MILLISECONDS);
        }
        log.info("[GuildAsset] 账本清理已启动 interval={} 首轮延迟={}ms terminal_retention={} counter_retention={}", interval,
                firstDelay, terminalRetention, counterRetention);
    }

    private void runOnceGuarded() {
        var live = this.liveness;
        if (live != null) {
            live.runGuarded(this::runOnce);
            return;
        }
        runOnce();
    }

    /** 跑一轮（失败只记日志，下一轮照常）。 */
    void runOnce() {
        try {
            var membership = this.workShard;
            var filter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                    : membership.sqlFilter("guild_asset_cleanup", "player_id");
            GuildAssetStore.CleanupReport report = filter.isAll()
                    ? store.cleanupOnce(clockMs.getAsLong(), terminalRetention, counterRetention)
                    : store.cleanupOnce(clockMs.getAsLong(), terminalRetention, counterRetention, filter);
            if (report.assetOpsDeleted() > 0 || report.countersDeleted() > 0 || report.clientRequestsDeleted() > 0) {
                log.info("[GuildAsset] 清理一轮：终态指令行 {}，计数行 {}，幂等键去重记录 {}", report.assetOpsDeleted(),
                        report.countersDeleted(), report.clientRequestsDeleted());
            }
        } catch (Throwable t) {
            log.error("[GuildAsset] 清理出错（本轮作废，下一轮照常）", t);
        }
    }

    /** 停止（等当前一轮至多 {@code timeout}，之后中断）。幂等。 */
    public void stop(Duration timeout) {
        ScheduledExecutorService s;
        synchronized (lifecycle) {
            s = scheduler;
            scheduler = null;
        }
        if (liveness != null) {
            liveness.close();
        }
        if (s == null) {
            return;
        }
        s.shutdown();
        try {
            if (!s.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                s.shutdownNow();
            }
        } catch (InterruptedException e) {
            s.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
