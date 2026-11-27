package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 决策清单 B1-1 {@link LiveViewSanityGate} 存活集骤降闸门单测。 */
class LiveViewSanityGateTest {

    @Test
    void 基线非空而存活集为空时恒压住() {
        var gate = LiveViewSanityGate.forSceneManagerSync();
        long t = 1_000_000_000L;
        var d1 = gate.evaluate(Set.of(1, 2), Set.of(), t);
        assertThat(d1.held()).isTrue();
        assertThat(d1.reason()).isEqualTo("empty_live_set");

        var d2 = gate.evaluate(Set.of(1, 2), Set.of(), t + Duration.ofHours(2).toNanos());
        assertThat(d2.held()).isTrue();
        assertThat(d2.reason()).isEqualTo("empty_live_set");
    }

    @Test
    void 基线至少4个且缺席过半时压住T秒_缺席数变化重新计时_稳定满T后放行() {
        var gate = new LiveViewSanityGate(Duration.ofSeconds(60), LiveViewSanityGate.ResetMode.WHEN_ZERO);
        Set<Integer> base = Set.of(1, 2, 3, 4, 5, 6);
        long t0 = 10_000_000_000L;

        // 4/6 缺席（过半） -> 压住
        assertThat(gate.evaluate(base, Set.of(1, 2), t0).held()).isTrue();
        // 59 秒后仍压住
        assertThat(gate.evaluate(base, Set.of(1, 2), t0 + Duration.ofSeconds(59).toNanos()).held()).isTrue();
        // 第 59.5 秒缺席数变为 5/6 -> 从头计时
        long tChange = t0 + Duration.ofSeconds(59).toNanos() + 500_000_000L;
        assertThat(gate.evaluate(base, Set.of(1), tChange).held()).isTrue();
        // 再过 59 秒仍压住
        assertThat(gate.evaluate(base, Set.of(1), tChange + Duration.ofSeconds(59).toNanos()).held()).isTrue();
        // 稳定满 60 秒后放行
        var released = gate.evaluate(base, Set.of(1), tChange + Duration.ofSeconds(60).toNanos());
        assertThat(released.held()).isFalse();
        assertThat(released.reason()).isEqualTo("majority_drop_released");
    }

    @Test
    void 基线不足4个或未过半不触发过半压住() {
        var gate = LiveViewSanityGate.forDeadGateSweep();
        assertThat(gate.evaluate(Set.of(1, 2, 3), Set.of(1), 100L).held()).isFalse();
        assertThat(gate.evaluate(Set.of(1, 2, 3, 4), Set.of(1, 2), 200L).held()).isFalse();
    }
}
