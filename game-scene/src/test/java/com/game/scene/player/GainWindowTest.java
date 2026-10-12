package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GainWindowTest {

    private static final long SECOND = 1_000_000_000L;
    private static final long WINDOW = 600 * SECOND;

    @Test
    void 剪掉窗口外的_边界上的保留_次数与累计随之减少() {
        GainWindow w = new GainWindow();
        w.record(0, 10, WINDOW);
        w.record(100 * SECOND, 20, WINDOW);
        assertThat(w.count()).isEqualTo(2);
        assertThat(w.total()).isEqualTo(30);

        w.record(600 * SECOND, 1, WINDOW);
        assertThat(w.count()).as("距第一次正好一个窗口：保留").isEqualTo(3);

        w.record(600 * SECOND + 1, 2, WINDOW);
        assertThat(w.count()).isEqualTo(3);
        assertThat(w.total()).isEqualTo(23);
    }

    @Test
    void 累计饱和不回绕_剪枝后按剩余重算() {
        GainWindow w = new GainWindow();
        w.record(0, Long.MAX_VALUE - 1, WINDOW);
        w.record(SECOND, 5, WINDOW);
        assertThat(w.total()).isEqualTo(Long.MAX_VALUE);
        w.record(2 * SECOND, 5, WINDOW);
        assertThat(w.total()).isEqualTo(Long.MAX_VALUE);

        w.record(WINDOW + SECOND / 2, 7, WINDOW);
        assertThat(w.count()).isEqualTo(3);
        assertThat(w.total()).isEqualTo(17);
    }

    @Test
    void 单调时钟取负值也按差值剪枝() {
        GainWindow w = new GainWindow();
        long start = Long.MIN_VALUE + 10;
        w.record(start, 1, WINDOW);
        w.record(start + WINDOW + 1, 1, WINDOW);
        assertThat(w.count()).isEqualTo(1);
    }
}
