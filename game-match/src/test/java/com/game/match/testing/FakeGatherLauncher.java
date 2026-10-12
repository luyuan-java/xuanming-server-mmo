package com.game.match.testing;

import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link GatherLauncher} 的测试替身：只记下交来的 {@link GatherPlan}、按脚本给结果，不碰票据、不发任何 RPC。给 gather 的调用方（凑单、排队的 PVE_SOLO、
 * 切磋、整队、活动）的组件测试用。
 *
 * <pre>
 * FakeGatherLauncher gather = new FakeGatherLauncher();          // 缺省：立即成功，battle_id 从 9001 起（活动入口用 plan 预设的号）
 * gather.nextResult(GatherResult.failed(GatherOutcome.PREPARE_FAILED, 0));   // 下一次 launch 的结果（可以排多个）
 * gather.hold();                                                  // 之后的 launch 返回未完成的 future……
 * gather.complete(0, GatherResult.success(77));                   // ……由测试在想要的时刻完成（断言「先推 154 再开局」这类顺序）
 * gather.permits(0);                                              // availablePermits() 回 0；launch 立即回 overloaded
 * assertThat(gather.plans).singleElement().satisfies(plan -> assertThat(plan.members()).containsExactly(1001L, 1002L));
 * </pre>
 * 与真实现一样：{@code launch} 不抛、返回的 future 永不异常完成。线程安全。
 */
public final class FakeGatherLauncher implements GatherLauncher {

    /** 交来的 plan，按 launch 顺序。 */
    public final List<GatherPlan> plans = new CopyOnWriteArrayList<>();
    /** 每次 launch 返回的 future，与 {@link #plans} 同下标。 */
    public final List<CompletableFuture<GatherResult>> futures = new CopyOnWriteArrayList<>();
    private final Deque<GatherResult> scripted = new ArrayDeque<>();
    private final AtomicLong nextBattleId = new AtomicLong(9001);
    private volatile boolean hold;
    private volatile int permits = 256;
    private volatile boolean idle = true;

    /** 排一个结果给下一次 launch（先进先出；排完之后回到缺省的「立即成功」）。 */
    public synchronized FakeGatherLauncher nextResult(GatherResult result) {
        scripted.add(result);
        return this;
    }

    /** 之后的 launch 都返回未完成的 future，等测试调 {@link #complete}。 */
    public FakeGatherLauncher hold() {
        this.hold = true;
        return this;
    }

    /** 取消 {@link #hold}（已经返回的未完成 future 仍要测试自己完成）。 */
    public FakeGatherLauncher release() {
        this.hold = false;
        return this;
    }

    /** 完成第 {@code index} 次 launch 的 future。 */
    public FakeGatherLauncher complete(int index, GatherResult result) {
        futures.get(index).complete(result);
        return this;
    }

    /** {@link #availablePermits()} 的返回值；为 0 时 launch 立即回 {@link GatherOutcome#OVERLOADED}（不看脚本）。 */
    public FakeGatherLauncher permits(int permits) {
        this.permits = permits;
        return this;
    }

    /** {@link #awaitIdle} 的返回值（缺省 true）。 */
    public FakeGatherLauncher idle(boolean idle) {
        this.idle = idle;
        return this;
    }

    @Override
    public CompletableFuture<GatherResult> launch(GatherPlan plan) {
        CompletableFuture<GatherResult> future = new CompletableFuture<>();
        GatherResult immediate = null;
        synchronized (this) {
            plans.add(plan);
            futures.add(future);
            if (permits <= 0) {
                immediate = GatherResult.failed(GatherOutcome.OVERLOADED, 0);
            } else if (!hold) {
                GatherResult next = scripted.poll();
                immediate = next != null ? next
                        : GatherResult.success(plan.presetBattleId() != 0 ? plan.presetBattleId() : nextBattleId.getAndIncrement());
            }
        }
        if (immediate != null) {
            future.complete(immediate);
        }
        return future;
    }

    @Override
    public int availablePermits() {
        return Math.max(0, permits);
    }

    @Override
    public boolean awaitIdle(Duration timeout) {
        return idle;
    }
}
