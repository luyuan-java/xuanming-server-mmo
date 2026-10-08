package com.game.match.matcher;

import com.game.match.lifecycle.MatcherControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.MatcherRound;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 凑单循环的调度（match-spec §2.5、§9.3 的 {@code match-matcher} 线程、§9.8 的启停）：单线程 {@code scheduleWithFixedDelay}，上一轮结束后等一个间隔
 * （缺省 500 ms）再跑下一轮，轮与轮不重叠。基线的 Go ticker 是固定频率，只在单轮超过间隔时有差别，不可见。
 *
 * <p><b>每一轮都包在 {@code try/catch Throwable} 里</b>：JDK 的调度器遇到一次未捕获的异常就永久停掉后续执行（基线的 safego 是隔离单轮 panic 后继续），
 * 凑单停了排队就永不成局。出错的一轮计 {@code xm_match_matcher_rounds_total{result="error"}}，下一轮照常。
 *
 * <p><b>启停</b>：它就是进程的 {@link MatcherControl}，<b>自己不带任何生命周期</b>（不实现 {@code Lifecycle}、没有 init / destroy 方法）——
 * 凑单必须在 Dubbo 导出之后才开始、在撤导出之前就停下，这个次序只有 {@code MatchLifecycle} 排得出来（启动第 8 步、停机第 1 步）。
 * {@link #start} 起线程；{@link #stop} 置停机信号、等当前这一轮结束（至多等 {@code stopTimeout}，超时则中断）。两者都幂等，停了可以再起，
 * 没起过也能停。停机只停「产生新的 gather」，已经交给开局管线的不受影响。
 */
public final class MatcherRunner implements MatcherControl {

    private static final Logger log = LoggerFactory.getLogger(MatcherRunner.class);

    static final String THREAD_NAME = "match-matcher";

    /** 一轮凑单。{@code stopRequested} 为真时应尽快收手。依赖故障自己收敛成结局；抛出来的都算意外。 */
    @FunctionalInterface
    public interface Round {
        MatcherRound run(BooleanSupplier stopRequested);
    }

    private final Round round;
    private final long intervalMs;
    private final long stopTimeoutMs;
    private final MatchMetrics metrics;
    private final Object lifecycle = new Object();
    private ScheduledExecutorService executor;
    private volatile boolean stopRequested;

    /**
     * @param round       一轮（生产为 {@code QueueMatcher::runRound}）
     * @param interval    两轮之间的间隔（{@code xm.match.matcher.interval}）
     * @param stopTimeout 停机时等当前这一轮结束的上限（一轮里单条队列的操作以凑单锁的 TTL 为界，取比它稍长的值）
     */
    public MatcherRunner(Round round, Duration interval, Duration stopTimeout, MatchMetrics metrics) {
        this.round = Objects.requireNonNull(round, "round");
        this.intervalMs = interval.toMillis();
        this.stopTimeoutMs = stopTimeout.toMillis();
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (intervalMs < 1 || stopTimeoutMs < 1) {
            throw new IllegalArgumentException("凑单间隔与停机等待都必须 ≥ 1 ms: interval=" + interval + " stopTimeout=" + stopTimeout);
        }
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (executor != null) {
                return;
            }
            stopRequested = false;
            executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(THREAD_NAME).daemon(true).factory());
            executor.scheduleWithFixedDelay(this::runRoundSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
            log.info("凑单循环已启动 interval={}ms", intervalMs);
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycle) {
            if (executor == null) {
                return;
            }
            stopRequested = true;
            // shutdown 之后不再排新的一轮；正在跑的那一轮看到停机信号后在队列之间、两次弹组之间收手
            executor.shutdown();
            boolean drained = false;
            try {
                drained = executor.awaitTermination(stopTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!drained) {
                log.warn("凑单循环在 {} ms 内没有停下，中断当前这一轮（已弹出的票据按 matched TTL 自愈）", stopTimeoutMs);
                executor.shutdownNow();
            }
            executor = null;
            log.info("凑单循环已停止");
        }
    }

    /** 调度线程此刻是否起着（{@link #start} 之后、{@link #stop} 之前）。 */
    public boolean isRunning() {
        synchronized (lifecycle) {
            return executor != null;
        }
    }

    /** 一轮：任何异常都不许逃出去（逃出去调度器就永久停转）。 */
    void runRoundSafely() {
        MatcherRound result;
        try {
            result = round.run(() -> stopRequested);
        } catch (Throwable t) {
            log.error("凑单这一轮抛了意料之外的异常，下一轮照常", t);
            result = MatcherRound.ERROR;
        }
        try {
            metrics.matcherRound(result == null ? MatcherRound.ERROR : result);
        } catch (Throwable t) {
            log.error("记凑单轮次指标失败", t);
        }
    }
}
