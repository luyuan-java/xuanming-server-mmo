package com.game.match.matcher;

import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.MatcherRound;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 凑单循环的调度（match-spec §2.5、§9.3 的 {@code match-matcher} 线程、§9.8 的启停）：单线程 {@code scheduleWithFixedDelay}，上一轮结束后等一个间隔
 * （缺省 500 ms）再跑下一轮，轮与轮不重叠。基线的 Go ticker 是固定频率，只在单轮超过间隔时有差别，不可见。
 *
 * <p><b>每一轮都包在 {@code try/catch Throwable} 里</b>：JDK 的调度器遇到一次未捕获的异常就永久停掉后续执行（基线的 safego 是隔离单轮 panic 后继续），
 * 凑单停了排队就永不成局。出错的一轮计 {@code xm_match_matcher_rounds_total{result="error"}}，下一轮照常。
 *
 * <p><b>生命周期</b>（{@link SmartLifecycle}，phase = {@link #DEFAULT_PHASE}：全部单例建好之后最后启动、关闭时最先停止，先于任何 bean 的销毁）：
 * {@link #start} 起线程；{@link #stop} 置停机信号、等当前这一轮结束（至多等 {@code stopTimeout}，超时则中断）。两者都幂等，停了可以再起。
 * 停机只停「产生新的 gather」，已经交给开局管线的不受影响。启停顺序要由别处统一掌握时，直接调这两个方法即可。
 *
 * <p>{@link #notWired} 给出一个「没接上线」的实例：凑单依赖的协作件不在上下文里时用它占位，{@link #start} 只打 ERROR、什么线程都不起。
 */
public final class MatcherRunner implements SmartLifecycle {

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
    private final List<String> missing;
    private final Object lifecycle = new Object();
    private ScheduledExecutorService executor;
    private volatile boolean stopRequested;

    /**
     * @param round       一轮（生产为 {@code QueueMatcher::runRound}）
     * @param interval    两轮之间的间隔（{@code xm.match.matcher.interval}）
     * @param stopTimeout 停机时等当前这一轮结束的上限（一轮里单条队列的操作以凑单锁的 TTL 为界，取比它稍长的值）
     */
    public MatcherRunner(Round round, Duration interval, Duration stopTimeout, MatchMetrics metrics) {
        this(Objects.requireNonNull(round, "round"), interval, stopTimeout, Objects.requireNonNull(metrics, "metrics"), List.of());
    }

    private MatcherRunner(Round round, Duration interval, Duration stopTimeout, MatchMetrics metrics, List<String> missing) {
        this.round = round;
        this.intervalMs = interval.toMillis();
        this.stopTimeoutMs = stopTimeout.toMillis();
        this.metrics = metrics;
        this.missing = List.copyOf(missing);
        if (round != null && (intervalMs < 1 || stopTimeoutMs < 1)) {
            throw new IllegalArgumentException("凑单间隔与停机等待都必须 ≥ 1 ms: interval=" + interval + " stopTimeout=" + stopTimeout);
        }
    }

    /**
     * 没接上线的占位实例：上下文里缺凑单要用的协作件（{@code missing} 是缺的那些的名字）。它永远不会运行。
     * 只应出现在并行开发的骨架阶段——整模块装配完成后仍然见到它，说明排队照收、永不成局。
     */
    public static MatcherRunner notWired(List<String> missing) {
        if (missing.isEmpty()) {
            throw new IllegalArgumentException("没接上线的凑单必须说明缺什么");
        }
        return new MatcherRunner(null, Duration.ZERO, Duration.ZERO, null, missing);
    }

    /** 凑单的协作件是否齐全（为假时 {@link #start} 不起线程）。 */
    public boolean wired() {
        return round != null;
    }

    /** 缺的协作件；齐全时为空。 */
    public List<String> missing() {
        return missing;
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (!wired()) {
                log.error("凑单循环没有启动：上下文里缺 {}。排队照常入队，但永不成局", missing);
                return;
            }
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

    @Override
    public boolean isRunning() {
        synchronized (lifecycle) {
            return executor != null;
        }
    }

    @Override
    public int getPhase() {
        return DEFAULT_PHASE;
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
