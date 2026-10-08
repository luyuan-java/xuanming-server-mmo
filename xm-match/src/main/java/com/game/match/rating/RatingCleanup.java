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
 * 入账标记的保留期清理（match-spec §5.2「清理」）：每小时分批删掉 {@code match_rating_applied} 里 {@link #RETENTION}（30 天）之前的行。
 * 标记是「同一局只入账一次」的唯一依据，所以它必须比对应的结果消息活得久（推导见 {@link #RETENTION}）。
 * 基线是入账标记键的 7 天 TTL（{@code rating.go:42}），与 topic 的 7 天保留期之间没有余量。评分行（{@code match_rating}）永不清理。
 *
 * <p>一条专用线程 {@value #THREAD_NAME}；每轮整体包在 try/catch 里（JDK 调度器遇到一次异常就会永久停掉后续执行）。
 * 每批一条自动提交的 {@code DELETE … LIMIT}，两批之间歇 {@value #BATCH_PAUSE_MS} ms，不产生长事务。多个 match 实例各清各的，互不妨碍
 * （READ COMMITTED 下按时间索引删旧行，不会挡住新标记的插入）。
 */
public final class RatingCleanup implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RatingCleanup.class);

    static final String THREAD_NAME = "match-rating-cleanup";
    /** 对局结果 topic 声明的保留期（{@code BattleResultTopics} 的 {@code retention.ms}；那边的常量包内可见，这里另抄一份，有用例对着 topic 规格核对）。 */
    static final Duration TOPIC_RETENTION = Duration.ofDays(7);
    /** topic 多久滚一次段：topic 规格没有声明 {@code segment.ms}，取 broker 的缺省值（{@code log.roll.hours = 168}）。 */
    static final Duration TOPIC_SEGMENT_ROLL = Duration.ofDays(7);
    /** 标记比消息的最长寿命多留的余量：盖住清理周期、broker 的过期检查周期与两边的时钟偏差，还有富余。 */
    static final Duration RETENTION_MARGIN = Duration.ofDays(7);
    /**
     * 入账标记保留多久：30 天。必须 ≥ 一条结果消息在 topic 里的最长寿命 + {@link #RETENTION_MARGIN}（有用例钉住这条不等式）。
     *
     * <p>消息的最长寿命<b>不是</b> topic 的 {@code retention.ms}（7 天）：Kafka 按段删除，一段里最新的那条消息过了保留期整段才删，
     * 低流量时一条消息最长留「保留期 + 滚段周期」≈ 14 天。标记若只留 7 天（基线与原规格的取值），就有一段「标记已删、消息还在」的窗口，
     * 这期间只要从最早位点重放一次——评分消费关闭超过 7 天再打开（消费组空置 7 天后 broker 会删掉它的位点，而清理线程在开关关闭时照常跑）、
     * 改消费组名、人工重置位点——7 到 14 天前已入账的局就会再入账一次（评分与局数各多算一次，不会自愈）。
     * 每局一行，30 天的量依然很小。<b>改 topic 的保留期、或给它声明 {@code segment.ms} 时，要一起重算这个值。</b>
     *
     * <p>残余：按兜底日志人工回灌超过保留期的旧结果仍会重复入账（回灌要在保留期之内做）；broker 把滚段周期调到 16 天以上时不等式不再成立。
     */
    public static final Duration RETENTION = Duration.ofDays(30);
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
