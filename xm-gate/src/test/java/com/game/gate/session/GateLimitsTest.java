package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.net.limit.MessageLimits;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 会话层阈值记录（批次 5.4 先行件加了重定向收口时限 {@code redirectLinger}）。只构造记录，不起任何东西。
 */
class GateLimitsTest {

    @Test
    void 三个便捷构造器_重定向收口时限缺省60秒_其余取值照旧() {
        GateLimits full = new GateLimits(4, 3, Duration.ZERO, MessageLimits.UNLIMITED, true);
        GateLimits noGm = new GateLimits(8, 50, Duration.ofSeconds(30), MessageLimits.UNLIMITED);
        GateLimits bare = new GateLimits(16, 0, Duration.ofSeconds(1));

        for (GateLimits limits : List.of(full, noGm, bare)) {
            assertThat(limits.redirectLinger()).isEqualTo(Duration.ofSeconds(60)).isEqualTo(GateLimits.DEFAULT_REDIRECT_LINGER);
        }
        assertThat(full.gmCommandsAllowed()).isTrue();
        assertThat(noGm.gmCommandsAllowed()).as("四参数形式拒绝 GM 指令").isFalse();
        assertThat(bare.gmCommandsAllowed()).isFalse();
        assertThat(bare.messageLimits()).isSameAs(MessageLimits.UNLIMITED);
        assertThat(noGm.maxPendingRequests()).isEqualTo(8);
        assertThat(noGm.illegalPacketThreshold()).isEqualTo(50);
        assertThat(noGm.handshakeTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void 重定向收口时限_可显式给_下限15秒本身合法_短于15秒与null拒绝() {
        assertThat(limits(Duration.ofSeconds(15)).redirectLinger()).as("下限本身合法").isEqualTo(Duration.ofSeconds(15));
        assertThat(limits(Duration.ofSeconds(90)).redirectLinger()).isEqualTo(Duration.ofSeconds(90));
        assertThat(GateLimits.MIN_REDIRECT_LINGER).isEqualTo(Duration.ofSeconds(15));

        for (Duration rejected : List.of(Duration.ofMillis(14_999), Duration.ofSeconds(1), Duration.ZERO, Duration.ofSeconds(-60))) {
            assertThatThrownBy(() -> limits(rejected)).as("redirect-linger = %s 应拒绝", rejected)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("xm.gate.redirect-linger");
        }
        assertThatThrownBy(() -> limits(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xm.gate.redirect-linger");
    }

    @Test
    void 原有的校验不受影响() {
        assertThatThrownBy(() -> new GateLimits(0, 3, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateLimits(4, -1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateLimits(4, 3, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateLimits(4, 3, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GateLimits(4, 3, Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static GateLimits limits(Duration redirectLinger) {
        return new GateLimits(4, 3, Duration.ZERO, MessageLimits.UNLIMITED, false, redirectLinger);
    }
}
