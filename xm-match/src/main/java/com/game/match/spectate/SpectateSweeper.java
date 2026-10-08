package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.lifecycle.SweeperControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.IndexEviction;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可观战索引的兜底清扫（spectate-spec §4.7；基线 {@code matcher.go:201-204} → {@code spectate.go:395-408}，挂在凑单循环上每 500 ms 一次）。
 * 索引的成员没有 TTL：battle 正常打完不通知 match，成员靠三条路离开——163 登记时 battle 回「房间不存在」的懒剔除、164 读到过期 / 缺记录时的懒剔除、
 * 以及这里：<b>定时摘掉分数早于「Redis 时间 − 360 s」的成员</b>（那一场必已按 300 s 的期限收尾）。读路径（随机选场、列表）自己按分数挡住过期成员，
 * 所以清扫间隔只影响残留成员的数量，缺省 10 s 一轮（{@code xm.match.spectate.sweep-interval}，W9）。
 *
 * <p>一轮两条 Redis 命令，各自最多等 {@link #OP_BUDGET_MS}：
 * <ol>
 *   <li>{@link SpectateStore#sweep}（只摘成员，落点记录靠自己的 TTL 过期）→ 摘掉的条数计进 {@code xm_match_watchable_index_evictions_total{reason="sweep"}}；</li>
 *   <li>{@link SpectateStore#watchableCount} → 采样 {@code xm_match_watchable_battles}（读失败时不动它，停在上一次的读数）。</li>
 * </ol>
 * 两条互不依赖：摘失败了照样采样。<b>每一轮都包在 {@code try/catch Throwable} 里</b>——JDK 的调度器遇到一次未捕获的异常就永久停掉后续执行，
 * 清扫停了残留成员就只能等读路径碰到它们。多实例各跑各的：两条命令都幂等，不抢锁（看板上这个 gauge 取 max，不能 sum）。
 *
 * <p><b>启停</b>：它就是进程的 {@link SweeperControl}，<b>自己不带任何生命周期</b>（构造时不起线程、不碰 Redis，不实现 {@code Lifecycle}）——
 * 由 {@code MatchLifecycle} 在 Dubbo 导出之后起、停机时在「等在途 163」之后停。{@link #start} 只是排上定时任务（单线程
 * {@code scheduleWithFixedDelay}：上一轮结束后等一个间隔再跑下一轮，轮与轮不重叠；第一轮在一个间隔之后）；{@link #stop} 不再排新的一轮，
 * 并<b>当场中断</b>手上这一轮（等 Redis 的那一下随即以依赖异常收场，这一轮看到停机信号不再发下一条命令），之后不再碰 Redis。
 * 两者都幂等、不抛异常；没起过也能停；停了可以再起。
 *
 * <p><b>为什么停机不等手上这一轮自己做完</b>（lead 裁决 4：加了观战之后停机不得比原来长）：停清扫排在「排空工作池 ∥ 等在途 163」
 * （至多 10 s）与「等在途 gather」（至多 10 s）之间，是串在中间的一步——它等多久，最坏停机时长就多多久，而这三步的预算是 20 s
 * （与加观战之前相同；它是设计预算，不是 Spring 会强制的截断，见 {@code MatchLifecycle} 的类注释）。
 * 一轮通常是毫秒级，但 Redis 卡住时一条命令会等满 {@link #OP_BUDGET_MS}。清扫是幂等的只删操作、多实例各跑各的，做一半被打断无害
 * （命令已经发出的话 Redis 照样执行；没摘完的下一个实例、下一次启动再摘），所以直接中断。中断之后只等线程退出，那是微秒级的事；
 * {@code stopTimeout} 只防「这一轮不理会中断」的违约情形，到点放弃（线程是守护线程，不妨碍进程退出）。
 */
public final class SpectateSweeper implements SweeperControl {

    private static final Logger log = LoggerFactory.getLogger(SpectateSweeper.class);

    static final String THREAD_NAME = "match-spectate-sweeper";
    /** 一轮里每条 Redis 命令的等待上限。到点只是不等了：两条命令都幂等，迟到执行也无害。 */
    static final long OP_BUDGET_MS = 3_000;
    /**
     * 停机时中断了当前一轮之后，等清扫线程退出的上限（生产值）。守约的一轮被中断后立刻收手，用不到这段时间；
     * 它只防「这一轮不理会中断」把停机拖住（同 {@code MatchLifecycle.WATCH_DRAIN_GRACE} 的用意），不计入停机的最坏时长。
     */
    static final Duration STOP_TIMEOUT = Duration.ofSeconds(1);

    private final SpectateStore store;
    private final MatchMetrics metrics;
    private final long intervalMs;
    private final long stopTimeoutMs;
    private final Object lifecycle = new Object();
    private ScheduledExecutorService executor;
    private volatile boolean stopRequested;

    /**
     * @param interval 两轮之间的间隔（{@code xm.match.spectate.sweep-interval}；≥ 1 ms）
     */
    public SpectateSweeper(SpectateStore store, MatchMetrics metrics, Duration interval) {
        this(store, metrics, interval, STOP_TIMEOUT);
    }

    /**
     * @param stopTimeout 停机时中断当前这一轮之后，等清扫线程退出的上限（≥ 1 ms；生产为 {@link #STOP_TIMEOUT}）
     */
    SpectateSweeper(SpectateStore store, MatchMetrics metrics, Duration interval, Duration stopTimeout) {
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.intervalMs = Objects.requireNonNull(interval, "interval").toMillis();
        this.stopTimeoutMs = Objects.requireNonNull(stopTimeout, "stopTimeout").toMillis();
        if (intervalMs < 1 || stopTimeoutMs < 1) {
            throw new IllegalArgumentException("清扫间隔与停机等待都必须 ≥ 1 ms: interval=" + interval + " stopTimeout=" + stopTimeout);
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
            log.info("观战清扫已启动 interval={}ms", intervalMs);
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycle) {
            if (executor == null) {
                return;
            }
            stopRequested = true;
            // 不再排新的一轮，并当场中断正在跑的那一轮（见类注释「为什么停机不等手上这一轮自己做完」）：它等 Redis 的那一下随即以依赖异常收场，
            // 看到停机信号就不再发下一条命令
            try {
                executor.shutdownNow();
            } catch (RuntimeException e) { // 不该发生；停机路径上不往外抛
                log.error("中断观战清扫的当前一轮时出错（线程是守护线程，不妨碍退出）", e);
            }
            boolean ended = false;
            try {
                ended = executor.awaitTermination(stopTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) { // 不该发生；停机路径上不往外抛
                log.error("等观战清扫线程退出时出错（按没退出处理）", e);
            }
            if (!ended) {
                log.warn("观战清扫线程被中断后 {} ms 内没有退出，不再等它（清扫是幂等的只删操作；线程是守护线程，不妨碍进程退出）", stopTimeoutMs);
            }
            executor = null;
            log.info("观战清扫已停止");
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
        try {
            sweepOnce();
        } catch (Throwable t) {
            log.error("观战清扫这一轮抛了意料之外的异常，下一轮照常", t);
        }
    }

    private void sweepOnce() {
        if (stopRequested) {
            return;
        }
        try {
            long removed = store.sweep(Deadline.after(OP_BUDGET_MS));
            metrics.watchableIndexEvicted(IndexEviction.SWEEP, removed);
            if (removed > 0) {
                log.info("[spectate] 清扫可观战索引：摘掉 {} 个过期成员", removed);
            }
        } catch (Deadline.DependencyException e) {
            if (stopRequested) {
                log.info("[spectate] 停机中断了这一轮清扫（幂等的只删操作，没做完无害）: {}", e.toString());
                return;
            }
            log.warn("[spectate] 清扫可观战索引失败（下一轮再试；读路径自己按分数过滤，不受影响）: {}", e.toString());
        }
        if (stopRequested) {
            return;
        }
        try {
            metrics.watchableBattles(store.watchableCount(Deadline.after(OP_BUDGET_MS)));
        } catch (Deadline.DependencyException e) {
            if (stopRequested) {
                log.info("[spectate] 停机中断了这一轮的索引大小采样（gauge 停在上一次的读数）: {}", e.toString());
                return;
            }
            log.warn("[spectate] 采样可观战索引的大小失败（gauge 停在上一次的读数）: {}", e.toString());
        }
    }
}
