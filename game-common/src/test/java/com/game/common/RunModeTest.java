package com.game.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunModeTest {

    @Test
    void 只有dev与test放行GM_其余一律按prod() {
        assertThat(RunMode.parse("dev").allowsGmCommands()).isTrue();
        assertThat(RunMode.parse(" TEST ").allowsGmCommands()).isTrue();
        assertThat(RunMode.parse("prod")).isEqualTo(RunMode.PROD);
        assertThat(RunMode.parse(null)).isEqualTo(RunMode.PROD);
        assertThat(RunMode.parse("")).isEqualTo(RunMode.PROD);
        assertThat(RunMode.parse("devel")).as("写错按 prod").isEqualTo(RunMode.PROD);
        assertThat(RunMode.PROD.allowsGmCommands()).isFalse();
    }

    @Test
    void 别名同基线_写错的值不认识_未配置算认识() {
        assertThat(RunMode.parse("Development")).isEqualTo(RunMode.DEV);
        assertThat(RunMode.parse("local")).isEqualTo(RunMode.DEV);
        assertThat(RunMode.parse("testing")).isEqualTo(RunMode.TEST);
        assertThat(RunMode.parse("LIVE")).isEqualTo(RunMode.PROD);
        assertThat(RunMode.isRecognized("release")).isTrue();
        assertThat(RunMode.isRecognized(null)).isTrue();
        assertThat(RunMode.isRecognized("  ")).isTrue();
        assertThat(RunMode.isRecognized("develop")).isFalse();
    }
}
