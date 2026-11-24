package com.game.common.startup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClockCheckTest {

    @Test
    void 偏差按往返中点减Date秒中点计算() {
        assertThat(ClockCheck.offsetMillis(1_000_000, 1_000_200, 996)).isEqualTo(3_600);
    }

    @Test
    void 超限时仅精确warn放行其余拒启() {
        long oneYear = 365L * 24 * 3600 * 1000;
        assertThat(ClockCheck.judge(ClockCheck.MAX_OFFSET_MILLIS, null)).isNull();
        assertThat(ClockCheck.judge(-ClockCheck.MAX_OFFSET_MILLIS, null)).isNull();
        assertThat(ClockCheck.judge(ClockCheck.MAX_OFFSET_MILLIS + 1, null)).isNotNull();
        assertThat(ClockCheck.judge(-ClockCheck.MAX_OFFSET_MILLIS - 1, "enforce")).isNotNull();
        assertThat(ClockCheck.judge(-oneYear, "warn")).isNull();
        assertThat(ClockCheck.judge(-oneYear, "WARN")).isNotNull();
    }
}
