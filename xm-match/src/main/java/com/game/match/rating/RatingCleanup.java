package com.game.match.rating;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入账标记的保留期清理（match-spec §5.2「清理」）：每小时分批删掉 {@code match_rating_applied} 里 7 天之前的行。
 * 保留期与对局结果 topic 的保留期一致（{@code BattleResultTopics}，7 天）——标记只为挡住重复投递，重复投递不会跨这么久；
 * 基线是入账标记键的 7 天 TTL（{@code rating.go:42}）。评分行（{@code match_rating}）永不清理。
 *
 * <p>一条专用线程 {@value #THREAD_NAME}；每轮整体包在 try/catch 里（JDK 调度器遇到一次异常就会永久停掉后续执行）。
 * 每批一条自动提交的 {@code DELETE … LIMIT}，两批之间歇 {@value #BATCH_PAUSE_MS} ms，不产生长事务。多个 match 实例各清各的，互不妨碍
 * （READ COMMITTED 下按时间索引删旧行，不会挡住新标记的插入）。
 */
public final class RatingCleanup implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RatingCleanup.class);

    static final String THREAD_NAME = "match-rating-cleanup";
    /** 入账标记保留多久（= 对局结果 topic 的保留期）。 */
    public static final Duration RETENTION = Duration.ofDays(7);
    /** 两轮清理之间的间隔。 */
    static final Duration INTERVAL = Duration.ofHours(1);
    /** 启动后多久跑第一轮（重启后不必等满一小时）。 */
    static final Duration INITIAL_DELAY = Duration.ofMinutes(1);
    /** 一批删多少行。 */
    static final int BATCH = 500;
    static final long BATCH_PAUSE_MS = 100;

    /** 删一批早于给定时刻的标记，返回删掉的行数（生产为 {@link RatingStore#deleteAppliedBefore}）。 */
    @FunctionalInterface
    interface Purger {
        int deleteAppliedBefore(long beforeMs, int limit);
    }

    private final Purger purger;
    private final LongSupplier clockMs;
    private final int batch;
    private final long batchPauseMs;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;

    /** @param clockMs 服务进程时钟（Unix 毫秒），与写 {@code applied_at_ms} 的是同一个 */
    public RatingCleanup(RatingStore store, LongSupplier clockMs) {
        this(store::deleteAppliedBefore, clockMs, BATCH, BATCH_PAUSE_MS);
    }

    RatingCleanup(Purger purger, LongSupplier clockMs, int batch, long batchPauseMs) {
        this.purger = Objects.requireNonNull(purger, "purger");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.batch = batch;
        this.batchPauseMs = batchPauseMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(THREAD_NAME).daemon(true).factory());
    }

    /** 开始按小时清理。 */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::runQuietly, INITIAL_DELAY.toMillis(), INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void runQuietly() {
        try {
            long deleted = purgeOnce();
            if (deleted > 0) {
                log.info("[rating] 入账标记清理：删除 {} 行（{} 之前）", deleted, RETENTION);
            }
        } catch (Throwable e) {
            // 库故障、锁等待超时等：下个周期再试；绝不让异常逃出去停掉调度
            log.warn("[rating] 入账标记清理失败，下个周期再试：{}", e.toString());
        }
    }

    /**
     * 跑一轮：分批删到没有过期行为止（或正在停机）。
     *
     * @return 这一轮删掉的行数
     */
    long purgeOnce() {
        long before = clockMs.getAsLong() - RETENTION.toMillis();
        long total = 0;
        while (!closed) {
            int deleted = purger.deleteAppliedBefore(before, batch);
            total += deleted;
            if (deleted < batch) {
                break;
            }
            try {
                Thread.sleep(batchPauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return total;
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }
}
