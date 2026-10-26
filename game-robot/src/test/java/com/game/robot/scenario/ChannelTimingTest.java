package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 用确定性的单调时钟时间点验证窗口；不启动服务、不依靠真实睡眠。 */
class ChannelTimingTest {
    private static long ms(long millis) { return Duration.ofMillis(millis).toNanos(); }

    @Test void 默认十秒在首次写入有界完成后留出拒绝和不续期窗口() {
        var window = ChannelTiming.window(ms(1000), ms(1100), 10);
        assertThat(window.earliestExpiry()).isEqualTo(ms(11000));
        assertThat(window.latestWrite()).isEqualTo(ms(4100));
        assertThat(window.probeAt()).isEqualTo(ms(7100));
        assertThat(window.returnAt()).isEqualTo(ms(14350));
        assertThat(window.canProbe(ms(7100))).isTrue();
        assertThat(window.beforeExpiry(ms(7200))).isTrue();
        assertThat(window.excludesRenewal(ms(7100), ms(14400))).isTrue();
    }

    @Test void 拒绝时续期不会因等待起点后移而被隐藏() {
        var window = ChannelTiming.window(0, ms(100), 10);
        long rejectedAt = window.probeAt();
        long wrongRenewedExpiry = rejectedAt + window.ttl();
        assertThat(window.returnAt()).isLessThan(wrongRenewedExpiry);
        assertThat(window.returnAt()).isEqualTo(ms(13350)); // 固定首次区间；不加到拒绝时刻上。
        assertThat(window.excludesRenewal(rejectedAt, wrongRenewedExpiry - 1)).isTrue();
        assertThat(window.excludesRenewal(rejectedAt, wrongRenewedExpiry)).isFalse();
        assertThat(window.excludesRenewal(rejectedAt, wrongRenewedExpiry + ms(100))).isFalse();
    }

    @Test void 冷却写入前和剩余不足两秒都不发拒绝探测() {
        var window = ChannelTiming.window(0, ms(100), 10);
        assertThat(window.canProbe(window.latestWrite())).isFalse();
        assertThat(window.canProbe(window.probeAt() - 1)).isFalse();
        assertThat(window.canProbe(window.earliestExpiry() - ChannelTiming.MIN_REMAINING)).isTrue();
        assertThat(window.canProbe(window.earliestExpiry() - ChannelTiming.MIN_REMAINING + 1)).isFalse();
    }

    @Test void 短冷却和跨节点慢到达明确没有可信拒绝窗口() {
        for (int ttl : new int[] {1, 5, 9}) {
            var shortWindow = ChannelTiming.window(0, ms(50), ttl);
            assertThat(shortWindow.canProbe(shortWindow.probeAt())).isFalse();
        }
        var slow = ChannelTiming.window(0, ms(6000), 10);
        assertThat(slow.canProbe(slow.probeAt())).isFalse();
        assertThat(slow.beforeExpiry(ms(12000))).isFalse(); // 此时切回可能合法，不能报冷却失效。
        assertThat(slow.returnAt()).isEqualTo(ms(19250));
    }

    @Test void 回包或返回晚到不冒充完整冷却验收_等到固定截止不重启TTL() {
        var window = ChannelTiming.window(0, ms(100), 10);
        assertThat(window.beforeExpiry(window.earliestExpiry() - 1)).isTrue();
        assertThat(window.beforeExpiry(window.earliestExpiry())).isFalse();
        assertThat(window.excludesRenewal(window.probeAt(), window.returnAt() - 1)).isFalse();
        assertThat(ChannelTiming.waitNanos(window.returnAt(), ms(7000))).isEqualTo(ms(6350));
        assertThat(ChannelTiming.waitNanos(window.returnAt(), ms(15000))).isZero();
    }

    @Test void 单调时钟可为负_非法区间及无冷却不建立窗口() {
        var window = ChannelTiming.window(ms(-1000), ms(-900), 10);
        assertThat(window.canProbe(window.probeAt())).isTrue();
        assertThatThrownBy(() -> ChannelTiming.window(1, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelTiming.window(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChannelTiming.window(0, 0, 61)).isInstanceOf(IllegalArgumentException.class);
    }
}
