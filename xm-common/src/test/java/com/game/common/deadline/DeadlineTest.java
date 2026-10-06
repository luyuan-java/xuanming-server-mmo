package com.game.common.deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link Deadline}：剩余时间、到期判断，await 超时之后一定已到期（不会按毫秒截断提前醒来），以及 await 的中断分支
 * （立刻返回、恢复中断标志、抛 {@link Deadline.DependencyException}）。
 */
class DeadlineTest {

    @Test
    void 刚建好的预算未到期_剩余不超过预算() {
        Deadline d = Deadline.after(1_000);
        assertThat(d.expired()).isFalse();
        assertThat(d.remainingMillis()).isBetween(0L, 1_000L);
        assertThat(d.remainingNanos()).isPositive();
    }

    @Test
    void 零预算立即到期_剩余为零() {
        Deadline d = Deadline.after(0);
        assertThat(d.expired()).isTrue();
        assertThat(d.remainingMillis()).isZero();
        assertThat(d.remainingNanos()).isZero();
    }

    @Test
    void await拿到结果就返回() {
        assertThat(Deadline.after(1_000).await(CompletableFuture.completedFuture(7), "取值")).isEqualTo(7);
    }

    @Test
    void await异常完成_抛依赖故障并挂原因() {
        IllegalStateException cause = new IllegalStateException("坏了");
        assertThatThrownBy(() -> Deadline.after(1_000).await(CompletableFuture.failedFuture(cause), "取值"))
                .isInstanceOf(Deadline.DependencyException.class).hasCause(cause);
    }

    /** 等待线程的一次 await 的结局（在那条线程自己身上采集）。 */
    private record Waited(Throwable thrown, boolean interruptedAfter, long elapsedMillis) {
    }

    /**
     * 在一条专用线程上 await 一个永不完成的 future（预算 {@code budgetMillis}），返回那条线程与采集结局的 future。
     * 用专用线程：中断标志不会漏到 JUnit 的线程上去。
     */
    private static Thread awaiter(long budgetMillis, boolean interruptBeforeAwait, CompletableFuture<Waited> outcome) {
        Thread thread = new Thread(() -> {
            if (interruptBeforeAwait) {
                Thread.currentThread().interrupt();
            }
            long start = System.nanoTime();
            Throwable thrown = null;
            try {
                Deadline.after(budgetMillis).await(new CompletableFuture<Integer>(), "等不到");
            } catch (Throwable t) {
                thrown = t;
            }
            outcome.complete(new Waited(thrown, Thread.currentThread().isInterrupted(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
        }, "deadline-await-test");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * 中断分支：等待中的线程被中断 → 立刻返回（不把剩余预算等完），抛 {@link Deadline.DependencyException}（原因是那个 InterruptedException），
     * 并且<b>恢复中断标志</b>——调用方（工作线程的外层循环 / 线程池）还要靠它知道自己被要求停下。
     */
    @Test
    void await等待中被中断_立刻返回不等完预算_恢复中断标志_抛依赖故障() throws Exception {
        long budgetMillis = 60_000;
        CompletableFuture<Waited> outcome = new CompletableFuture<>();
        Thread thread = awaiter(budgetMillis, false, outcome);
        thread.start();
        // 等它真的停在定时等待里再中断（最多等 10 s；等不到也照样中断——中断在进入等待之前到达同样走这条分支）
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < waitUntil) {
            Thread.onSpinWait();
        }
        assertThat(thread.getState()).as("等待线程应已停在 await 的定时等待里").isEqualTo(Thread.State.TIMED_WAITING);
        assertThat(outcome).as("没被中断之前不会返回").isNotDone();

        thread.interrupt();
        Waited waited = outcome.get(10, TimeUnit.SECONDS);

        assertThat(waited.thrown()).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("等不到")
                .hasMessageContaining("被中断").hasCauseInstanceOf(InterruptedException.class);
        assertThat(waited.interruptedAfter()).as("中断标志已恢复").isTrue();
        assertThat(waited.elapsedMillis()).as("立刻返回，没有把 60 s 预算等完").isLessThan(budgetMillis / 2);
    }

    /** 中断分支的另一种来路：调用 await 时线程已经带着中断标志 → 同样立刻抛、标志仍在（不是被 await 吞掉）。 */
    @Test
    void await调用时线程已被中断_立刻抛依赖故障_中断标志仍在() throws Exception {
        long budgetMillis = 60_000;
        CompletableFuture<Waited> outcome = new CompletableFuture<>();
        awaiter(budgetMillis, true, outcome).start();

        Waited waited = outcome.get(10, TimeUnit.SECONDS);

        assertThat(waited.thrown()).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("被中断")
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(waited.interruptedAfter()).isTrue();
        assertThat(waited.elapsedMillis()).isLessThan(budgetMillis / 2);
    }

    @Test
    void await超时之后预算一定已到期_反复多次不提前醒来() {
        // 按毫秒截断等待时，Linux 上的定时等待会在截止前不到 1 ms 醒来，之后 expired() 还是假（TeamPushes 的「批预算用完就跳过」因此多发一条）
        for (int i = 0; i < 50; i++) {
            Deadline d = Deadline.after(3);
            assertThatThrownBy(() -> d.await(new CompletableFuture<Integer>(), "等不到"))
                    .isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("超过请求预算");
            assertThat(d.expired()).as("第 %d 次：await 超时返回时预算必须已经用完", i).isTrue();
        }
    }
}
