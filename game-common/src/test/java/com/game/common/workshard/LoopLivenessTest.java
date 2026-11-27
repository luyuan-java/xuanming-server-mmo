package com.game.common.workshard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoopLivenessTest {

    @Test
    void 正常轮次更新活性_异常轮次不更新且兜住不中断循环_仅有意关闭时摘除() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        LoopLiveness live = new LoopLiveness("trade", "commerce_tick", 300L, clock::get);
        AtomicInteger panicCallbacks = new AtomicInteger();
        AtomicBoolean removed = new AtomicBoolean();
        live.bindCallbacks(panicCallbacks::incrementAndGet, () -> removed.set(true));

        assertThat(live.lastCompletedEpochSeconds()).isEqualTo(1_700_000_000L);

        // 正常一轮 -> 更新完成时间
        clock.addAndGet(5_000L);
        assertThat(live.runGuarded(() -> {})).isTrue();
        assertThat(live.lastCompletedEpochSeconds()).isEqualTo(1_700_000_005L);
        assertThat(live.panicCount()).isZero();
        assertThat(removed.get()).isFalse();

        // 抛未捕获异常 -> 不更新完成时间、不摘除序列、计数并继续允许下一轮
        clock.addAndGet(10_000L);
        assertThat(live.runGuarded(() -> {
            throw new IllegalStateException("simulated panic");
        })).isFalse();
        assertThat(live.lastCompletedEpochSeconds()).isEqualTo(1_700_000_005L);
        assertThat(live.panicCount()).isEqualTo(1L);
        assertThat(panicCallbacks.get()).isEqualTo(1);
        assertThat(removed.get()).isFalse();

        // 下一轮恢复正常 -> 更新完成时间
        clock.addAndGet(5_000L);
        assertThat(live.runGuarded(() -> {})).isTrue();
        assertThat(live.lastCompletedEpochSeconds()).isEqualTo(1_700_000_020L);

        // 有意退出 -> 摘除序列（幂等）
        live.close();
        live.close();
        assertThat(live.isClosed()).isTrue();
        assertThat(removed.get()).isTrue();
    }
}
