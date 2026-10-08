package com.game.match.gather;

import com.game.match.metrics.MatchMetrics;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GatherLauncher} 的生产实现（match-spec §9.3、§9.6 第 0 步、M13）：每次 gather 一个<b>虚拟线程</b>（名字 {@code match-gather-<序号>}），
 * 全局信号量限制同时在途的数量（{@code xm.match.gather-max-inflight}，缺省 256）。
 *
 * <p><b>为什么用虚拟线程</b>：一次 gather 是最长约 90 s 的串行 RPC 链。用平台线程池，scene 卡住时会把池子堵满，而且排队等线程的时间会吃掉
 * matched TTL 的预算（TTL 从建票时就开始算）；写成 future 链又失去与基线逐段对照的可读性。管线里每一跳都是「异步 API + 在 future 上限时等」，
 * 不在 {@code synchronized} 块里阻塞，虚拟线程挂起时不占载体线程。
 *
 * <p><b>许可与过载</b>：{@link #launch} 当场试拿一个许可（不等）。拿到 → 跑 {@link GatherPipeline#run}，结束时归还；拿不到 → 结局
 * {@link GatherOutcome#OVERLOADED}：不发号、不选节点、不冻结任何人，只按入口的策略处置票据（{@link GatherPipeline#overloaded}）。处置票据要写 Redis，
 * 所以过载的收尾同样放在一个虚拟线程上（不占许可、只有几次票据写），{@link #launch} 本身任何时候都不阻塞；future 在票据处置完之后才完成——
 * 调用方看到「失败」时，票据已经不挡路了。
 *
 * <p><b>指标</b>：每次 gather 结束（含过载）记一次 {@code xm_match_gathers_total} 与 {@code xm_match_gather_seconds}（从 {@link #launch} 起算，含补偿）；
 * {@code xm_match_gathers_inflight} 取「已发出的许可数」。
 *
 * <p>线程安全。gather 一旦启动不可取消；进程退出时在途的 gather 直接消失（虚拟线程是守护线程）——票据按 matched TTL 自愈，scene 按备战期限解冻。
 */
public final class VirtualThreadGatherLauncher implements GatherLauncher {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadGatherLauncher.class);

    private final GatherPipeline pipeline;
    private final MatchMetrics metrics;
    private final int maxInflight;
    private final Semaphore permits;
    private final ThreadFactory threads = Thread.ofVirtual().name("match-gather-", 0).factory();
    /** 还没结束的 gather 线程数（含过载收尾的）；{@link #awaitIdle} 等它归零。 */
    private final ReentrantLock runningLock = new ReentrantLock();
    private final Condition idle = runningLock.newCondition();
    private int running;

    /**
     * @param maxInflight 同时在途的 gather 上限（≥ 1）
     */
    public VirtualThreadGatherLauncher(GatherPipeline pipeline, MatchMetrics metrics, int maxInflight) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (maxInflight < 1) {
            throw new IllegalArgumentException("gather 在途上限必须 ≥ 1: " + maxInflight);
        }
        this.maxInflight = maxInflight;
        this.permits = new Semaphore(maxInflight);
        metrics.bindInflightGathers(this::inflight);
    }

    @Override
    public CompletableFuture<GatherResult> launch(GatherPlan plan) {
        Objects.requireNonNull(plan, "plan");
        long startedNanos = System.nanoTime();
        CompletableFuture<GatherResult> future = new CompletableFuture<>();
        boolean admitted = permits.tryAcquire();
        started();
        try {
            threads.newThread(() -> runOne(plan, admitted, startedNanos, future)).start();
        } catch (RuntimeException | OutOfMemoryError e) {
            // 起不了线程（实际只可能是内存耗尽）：没有任何副作用，票据按 matched TTL 自愈
            log.error("[gather] 启动 gather 线程失败 mode={} members={}", plan.mode(), Compensation.ids(plan.members()), e);
            finish(plan, admitted, startedNanos, future, GatherResult.failed(GatherOutcome.INTERNAL, 0));
        }
        return future;
    }

    /**
     * 一次 gather 的线程体。<b>任何出口都恰好调一次 {@link #finish}</b>（含管线抛 {@link Error}）：不调的话这次的在途许可永不归还
     * （累计到上限后凑单永久 {@code paused_saturated}、PVE_SOLO 恒回 16004）、future 永不完成（整队调用方挂满开战锁时长、切磋不会补推 154 false）、
     * 在途计数不减（之后每次停机都等满上限）。
     */
    private void runOne(GatherPlan plan, boolean admitted, long startedNanos, CompletableFuture<GatherResult> future) {
        GatherResult result;
        try {
            result = admitted ? pipeline.run(plan) : pipeline.overloaded(plan);
        } catch (RuntimeException e) {
            // 管线约定不抛；到这里说明约定被破坏：不知道冻结了谁，交给 scene 的备战期限与票据 TTL 收尾
            log.error("[gather] 管线抛出了异常（按内部错误收场，不做补偿） mode={} members={}", plan.mode(), Compensation.ids(plan.members()), e);
            result = GatherResult.failed(admitted ? GatherOutcome.INTERNAL : GatherOutcome.OVERLOADED, 0);
        } catch (Error e) {
            // NoClassDefFoundError / StackOverflowError / 断言失败等：同样先收场（还许可、给结果、减计数）再原样抛出，不做补偿
            log.error("[gather] 管线抛出了 Error（按内部错误收场，不做补偿） mode={} members={}", plan.mode(), Compensation.ids(plan.members()), e);
            finish(plan, admitted, startedNanos, future, GatherResult.failed(admitted ? GatherOutcome.INTERNAL : GatherOutcome.OVERLOADED, 0));
            throw e;
        }
        finish(plan, admitted, startedNanos, future, result);
    }

    /**
     * 归还许可 → 记指标 → 完成 future → 更新在途计数。顺序固定：调用方的回调看到结果时，许可已经还了。
     * 后三步用 finally 串起来：记指标或回调抛出 {@link Error} 时，future 照样完成、在途计数照样减（之后 Error 继续上抛）。
     */
    private void finish(GatherPlan plan, boolean admitted, long startedNanos, CompletableFuture<GatherResult> future, GatherResult result) {
        if (admitted) {
            permits.release();
        }
        try {
            metrics.gatherCompleted(plan.mode().getNumber(), result.outcome(), Duration.ofNanos(System.nanoTime() - startedNanos));
        } catch (RuntimeException e) {
            log.warn("[gather] 记指标出错（忽略）", e);
        } finally {
            try {
                future.complete(result);
            } catch (RuntimeException e) {
                // 挂在 future 上的回调抛了异常：与本次 gather 的结局无关
                log.warn("[gather] gather 结果的回调抛出异常（忽略）", e);
            } finally {
                finished();
            }
        }
    }

    @Override
    public int availablePermits() {
        return permits.availablePermits();
    }

    @Override
    public boolean awaitIdle(Duration timeout) {
        long remaining = Math.max(0, timeout.toNanos());
        runningLock.lock();
        try {
            while (running > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            runningLock.unlock();
        }
    }

    /** 此刻在途的 gather 数（已发出的许可；不含过载收尾的）。 */
    public int inflight() {
        return Math.max(0, maxInflight - permits.availablePermits());
    }

    private void started() {
        runningLock.lock();
        try {
            running++;
        } finally {
            runningLock.unlock();
        }
    }

    private void finished() {
        runningLock.lock();
        try {
            if (--running == 0) {
                idle.signalAll();
            }
        } finally {
            runningLock.unlock();
        }
    }

    @Override
    public String toString() {
        return "VirtualThreadGatherLauncher[inflight=" + inflight() + "/" + maxInflight + "]";
    }
}
