package com.game.match.script;

import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.RetentionTarget;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 剧本审核流水保留期清理器（对齐主仓 {@code docs/design/battle-script.md} §6.4）：
 * 按 {@code (reviewed_ms, script_id, revision)} 时间索引连续翻多页删除到期流水，不扫全表；
 * 多副本并发删除幂等无害；每轮采样最老一行超期秒数与清理失败计数供告警。
 */
public final class BattleScriptReviewCleanup implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BattleScriptReviewCleanup.class);

    static final String THREAD_NAME = "match-script-review-cleanup";
    static final Duration INITIAL_DELAY = Duration.ofMinutes(2);
    static final Duration INTERVAL = Duration.ofHours(1);
    static final int DEFAULT_BATCH_SIZE = 500;
    static final int DEFAULT_MAX_PAGES_PER_ROUND = 20;

    private final BattleScriptStore store;
    private final Duration retention;
    private final MatchMetrics metrics;
    private final LongSupplier clockMs;
    private final int batchSize;
    private final int maxPagesPerRound;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;
    private BattleScriptStore.ReviewCursor cursor = BattleScriptStore.ReviewCursor.START;

    public BattleScriptReviewCleanup(BattleScriptStore store, int retentionDays, MatchMetrics metrics, LongSupplier clockMs) {
        this(store, Duration.ofDays(retentionDays), metrics, clockMs, DEFAULT_BATCH_SIZE, DEFAULT_MAX_PAGES_PER_ROUND);
    }

    BattleScriptReviewCleanup(BattleScriptStore store, Duration retention, MatchMetrics metrics,
                              LongSupplier clockMs, int batchSize, int maxPagesPerRound) {
        this.store = Objects.requireNonNull(store, "store");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (!retention.isPositive()) {
            throw new IllegalArgumentException("retention must be positive: " + retention);
        }
        this.metrics = metrics;
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
        this.batchSize = Math.max(1, batchSize);
        this.maxPagesPerRound = Math.max(1, maxPagesPerRound);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(THREAD_NAME).daemon(true).factory());
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::runQuietly, INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runQuietly() {
        try {
            long deleted = purgeOnce();
            if (deleted > 0) {
                log.info("battle script review retention cleanup deleted rows={} retention={}", deleted, retention);
            }
        } catch (Throwable e) {
            if (metrics != null) {
                metrics.retentionPurgeFailed(RetentionTarget.BATTLE_SCRIPT_REVIEW);
            }
            log.error("battle script review retention cleanup failed; will retry next cycle", e);
        }
    }

    /** 连续翻页删除最多 {@code maxPagesPerRound} 页并刷新逾期年龄 gauge；失败抛给调用方记失败计数。 */
    long purgeOnce() throws Exception {
        long totalDeleted = 0L;
        for (int page = 0; page < maxPagesPerRound && !closed; page++) {
            BattleScriptStore.PurgeResult res = store.purgeExpiredReviews(retention, cursor, batchSize);
            cursor = res.next();
            totalDeleted += res.deleted();
            if (metrics != null && res.deleted() > 0) {
                metrics.scriptReviewsPurged(res.deleted());
            }
            if (cursor.equals(BattleScriptStore.ReviewCursor.START)) {
                break;
            }
        }
        long oldestMs = store.oldestReviewMs();
        long overdueSeconds = 0L;
        if (oldestMs > 0L) {
            long now = clockMs.getAsLong();
            long retMs = retention.toMillis();
            if (now > oldestMs && (now - oldestMs) > retMs) {
                overdueSeconds = (now - oldestMs - retMs) / 1000L;
            }
        }
        if (metrics != null) {
            metrics.retentionOverdueAgeSeconds(RetentionTarget.BATTLE_SCRIPT_REVIEW, overdueSeconds);
        }
        return totalDeleted;
    }

    /** 供测试触发带异常捕获的完整一轮。 */
    void runRoundForTest() {
        runQuietly();
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }
}
