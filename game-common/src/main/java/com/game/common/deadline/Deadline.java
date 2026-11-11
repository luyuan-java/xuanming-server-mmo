package com.game.common.deadline;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一个请求的整请求预算（基线 RequestBudget = 3500 ms）：本次请求里的 MySQL、Redis 等待共用这一个截止时刻。
 * 用单调时钟（{@link System#nanoTime()}）。
 *
 * <p><b>调用方截止</b>（mmorpg {@code docs/design/client-request-ordering.md} §5、§6.1）：gate 调后端的 Dubbo 超时一到就放行同一会话的下一条请求，
 * 后端在那之后提交就会与下一条交错。所以后端的截止取「本地预算」与「调用方剩余」的较早者（{@link #within}），与 gRPC 截止传播同一口径；
 * 调用方剩余由 game-api 的 {@code CallerDeadline} 从 Dubbo 上下文读出，经 {@link #fromCallerRemaining} 换成本进程的截止。
 */
public final class Deadline {

    /**
     * 调用方截止换成本进程截止时扣掉的余量（毫秒）。调用方只传「还剩多久」，本进程从收到那一刻起算：网络传输、Dubbo 解码与线程切换
     * 这段不在里面，本地截止因此晚于调用方真正放弃的时刻；截止前一刻发出的写也还要时间落地。依据：Google SRE《Addressing Cascading Failures》
     * 对截止传播的建议（向下传时扣掉几百毫秒给传输与调用方收尾）；取 500 与本仓库既有口径一致——game-match 的 4.5 s 请求预算、
     * game-trade 的收藏写入落定时限（{@code TradeLimits.WRITE_SETTLE_HORIZON_MS}）都是 gate 的 5 s 减 500。
     */
    public static final long CALLER_MARGIN_MILLIS = 500;

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static Deadline after(long budgetMillis) {
        return new Deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis));
    }

    /**
     * 调用方传来的剩余时长换成本进程的截止：在本机单调时钟上从现在起算，再扣掉 {@link #CALLER_MARGIN_MILLIS}。只传相对值、不传绝对时刻，
     * 跨机时钟偏差进不来（gRPC 的 {@code grpc-timeout} 同理）。
     *
     * @param callerRemainingNanos 调用方截止此刻还剩多久（纳秒，可以 ≤ 0）；不超过余量时返回的截止一开始就是过期的
     */
    public static Deadline fromCallerRemaining(long callerRemainingNanos) {
        long usable = Math.max(0, callerRemainingNanos - TimeUnit.MILLISECONDS.toNanos(CALLER_MARGIN_MILLIS));
        return new Deadline(System.nanoTime() + usable);
    }

    /**
     * 本地预算（从现在起算）与调用方截止取较早者。调用方没有传截止（旧 gate、单测、进程内直调）时就是 {@link #after(long)}。
     *
     * @param caller 调用方截止（{@link #fromCallerRemaining} 换算过的）
     */
    public static Deadline within(long budgetMillis, Optional<Deadline> caller) {
        Deadline local = after(budgetMillis);
        return caller.map(local::earlierOf).orElse(local);
    }

    /** 两个截止里较早的那个（同一时刻返回本对象）。 */
    public Deadline earlierOf(Deadline other) {
        return other.deadlineNanos - deadlineNanos < 0 ? other : this;
    }

    /** 比本截止提前 {@code millis} 毫秒的截止（必须 ≥ 0）。 */
    public Deadline minusMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must be >= 0: " + millis);
        }
        return new Deadline(deadlineNanos - TimeUnit.MILLISECONDS.toNanos(millis));
    }

    public long remainingMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    public boolean expired() {
        return deadlineNanos - System.nanoTime() <= 0;
    }

    /** 剩余纳秒（≥ 0）。 */
    public long remainingNanos() {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    /**
     * 在剩余预算内等一个异步结果（调用方在工作线程上）。超时 / 异常完成都抛 {@link DependencyException}，原始原因挂在 cause 上。
     * 按纳秒等：{@code CompletableFuture.get(long, TimeUnit)} 的超时不会早于它自己的截止时刻，而那个时刻不早于本截止时刻，所以超时返回之后
     * {@link #expired()} 一定为真。按毫秒截断会在 Linux 上提前不到 1 ms 醒来，紧接着的「预算到期就跳过」判断会以为还有预算
     * （GitHub Actions 上 TeamPushesTest 偶发失败即此）。
     */
    public <T> T await(CompletionStage<T> stage, String what) {
        try {
            return stage.toCompletableFuture().get(Math.max(1, remainingNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new DependencyException(what + " 超过请求预算", e);
        } catch (ExecutionException e) {
            throw new DependencyException(what + " 失败", e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyException(what + " 被中断", e);
        }
    }

    /** 依赖（Redis / MySQL / 在线目录）故障：上层定性为服务的故障码（好友 1003、组队 4030）。 */
    public static final class DependencyException extends RuntimeException {

        public DependencyException(String message, Throwable cause) {
            super(message, cause);
        }

        public DependencyException(String message) {
            super(message);
        }
    }
}
