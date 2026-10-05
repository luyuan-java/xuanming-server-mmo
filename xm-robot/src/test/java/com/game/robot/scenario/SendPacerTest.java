package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 探针发 63 前按 gate 的滑动窗口限频（缺省每秒 3 条）等够：要等多久的计算。 */
class SendPacerTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void 窗口里放得下就不等() {
        assertThat(SendPacer.waitNanos(List.of(), 0, 3, 3, SECOND)).isZero();
        assertThat(SendPacer.waitNanos(List.of(0L, 10L), 20, 1, 3, SECOND)).isZero();
        assertThat(SendPacer.waitNanos(List.of(0L, 10L, 20L), SECOND + 20, 3, 3, SECOND)).as("都已滑出窗口").isZero();
        assertThat(SendPacer.waitNanos(List.of(0L, 10L, 20L), SECOND, 1, 3, SECOND)).as("最老的一条恰好滑出").isZero();
    }

    @Test
    void 放不下时等到最老的几条滑出窗口() {
        assertThat(SendPacer.waitNanos(List.of(0L, 100L, 200L), 300, 1, 3, SECOND)).isEqualTo(SECOND - 300);
        assertThat(SendPacer.waitNanos(List.of(0L, 100L, 200L), 300, 2, 3, SECOND)).as("连发两条：等前两条都滑出")
                .isEqualTo(100 + SECOND - 300);
        assertThat(SendPacer.waitNanos(List.of(0L, 100L), 300, 2, 3, SECOND)).isEqualTo(SECOND - 300);
    }

    @Test
    void 一次要发的条数超过窗口上限是用法错误() {
        assertThatThrownBy(() -> SendPacer.waitNanos(List.of(), 0, 4, 3, SECOND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SendPacer.waitNanos(List.of(), 0, 0, 3, SECOND)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 记录只留窗口上限条_按连接分开() throws Exception {
        SendPacer pacer = new SendPacer();
        Object first = new Object();
        Object second = new Object();
        for (int i = 0; i < 5; i++) {
            pacer.record(first);
        }

        long start = System.nanoTime();
        pacer.await(second, SendPacer.MAX_IN_WINDOW);

        assertThat(System.nanoTime() - start).as("另一条连接的窗口是空的，不等").isLessThan(SendPacer.WINDOW.toNanos() / 2);
    }
}
