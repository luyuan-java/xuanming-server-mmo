package com.game.common.deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** {@link Deadline}：剩余时间、到期判断，以及 await 超时之后一定已到期（不会按毫秒截断提前醒来）。 */
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
