package com.game.common.deadline;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一个请求的整请求预算（基线 RequestBudget = 3500 ms）：本次请求里的 MySQL、Redis 等待共用这一个截止时刻。
 * 用单调时钟（{@link System#nanoTime()}）。
 */
public final class Deadline {

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static Deadline after(long budgetMillis) {
        return new Deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis));
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
