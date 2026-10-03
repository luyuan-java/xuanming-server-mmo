package com.game.scene.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.scene.audit.GainAnomalyDetector.Threshold;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.ManualClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GainAnomalyDetectorTest {

    private final ManualClock clock = new ManualClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ScenePlayer player = WorldTestAccess.player(1001);

    private GainAnomalyDetector detector(Threshold defaults, Map<Integer, Threshold> overrides) {
        return new GainAnomalyDetector(defaults, overrides, Map.of(), clock, new SceneMetrics(meters));
    }

    private double alerts(int type) {
        var counter = meters.find("xm.scene.gain.anomalies").tag("category", "currency")
                .tag("currency_type", Integer.toString(type)).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 次数超过上限的那一次告警一次_线内不重复_回到线内后重新武装() {
        GainAnomalyDetector d = detector(new Threshold(Duration.ofSeconds(600), 3, 0), Map.of());

        assertThat(d.currencyGained(player, 0, 1)).isFalse();
        assertThat(d.currencyGained(player, 0, 1)).isFalse();
        assertThat(d.currencyGained(player, 0, 1)).as("等于上限不算超").isFalse();
        assertThat(d.currencyGained(player, 0, 1)).as("第 4 次超过 3").isTrue();
        assertThat(d.currencyGained(player, 0, 1)).as("仍在线外：不重复告警").isFalse();
        assertThat(alerts(0)).isEqualTo(1);

        clock.advanceMillis(601_000);
        assertThat(d.currencyGained(player, 0, 1)).as("窗口滑过：回到线内，重新武装").isFalse();
        d.currencyGained(player, 0, 1);
        d.currencyGained(player, 0, 1);
        assertThat(d.currencyGained(player, 0, 1)).isTrue();
        assertThat(alerts(0)).isEqualTo(2);
    }

    @Test
    void 累计量超过上限告警_缺省阈值同基线() {
        GainAnomalyDetector d = detector(Threshold.DEFAULT, Map.of());
        assertThat(d.currencyGained(player, 1, 100_000)).isFalse();
        assertThat(d.currencyGained(player, 1, 1)).isTrue();
        assertThat(Threshold.DEFAULT).isEqualTo(new Threshold(Duration.ofSeconds(600), 50, 100_000));
    }

    @Test
    void 某一维填0只关这一维_两维都0关闭_按币种覆盖_币种之间互不影响() {
        GainAnomalyDetector d = detector(new Threshold(Duration.ofSeconds(60), 0, 10),
                Map.of(2, Threshold.OFF, 1, new Threshold(Duration.ofSeconds(60), 1, 0)));

        assertThat(d.currencyGained(player, 0, 5)).as("次数维关闭：单次不算超").isFalse();
        assertThat(d.currencyGained(player, 0, 6)).isTrue();
        assertThat(d.currencyGained(player, 2, Long.MAX_VALUE)).as("币种 2 关闭").isFalse();
        assertThat(d.currencyGained(player, 1, 1)).isFalse();
        assertThat(d.currencyGained(player, 1, 1)).isTrue();
        assertThat(alerts(0)).isEqualTo(1);
        assertThat(alerts(1)).isEqualTo(1);
        assertThat(alerts(2)).isZero();
    }

    @Test
    void 窗口跟着玩家实例_换实例从零开始() {
        GainAnomalyDetector d = detector(new Threshold(Duration.ofSeconds(60), 1, 0), Map.of());
        d.currencyGained(player, 0, 1);
        assertThat(d.currencyGained(WorldTestAccess.player(1001), 0, 1)).isFalse();
        assertThat(d.currencyGained(player, 0, 1)).isTrue();
    }

    @Test
    void 非法阈值拒绝() {
        assertThatThrownBy(() -> new Threshold(Duration.ZERO, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Threshold(Duration.ofSeconds(1), -1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Threshold(Duration.ofSeconds(1), 1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 窗口过期清空后单次越线的获取重新告警() {
        GainAnomalyDetector d = detector(Threshold.DEFAULT, Map.of());
        assertThat(d.currencyGained(player, 1, 100_001)).isTrue();

        clock.advanceMillis(601_000);
        assertThat(d.currencyGained(player, 1, 150_000)).as("上一轮已过期，这是新的一轮").isTrue();
        assertThat(d.currencyGained(player, 1, 1)).as("仍在线外").isFalse();
        assertThat(alerts(1)).isEqualTo(2);
    }

    @Test
    void 窗口超过一天或不到一秒拒绝() {
        assertThatThrownBy(() -> new Threshold(Duration.ofDays(2), 50, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Threshold(Duration.ofMillis(999), 50, 100_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Threshold(Duration.ofDays(1), 50, 100_000).window()).isEqualTo(Duration.ofDays(1));
    }

    private double itemAlerts() {
        var counter = meters.find("xm.scene.gain.anomalies").tag("category", "item").tag("currency_type", "none")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void 物品与币种的窗口分开计数_号相同也互不影响_物品告警不带配置号() {
        GainAnomalyDetector d = new GainAnomalyDetector(new Threshold(Duration.ofSeconds(60), 1, 0), Map.of(),
                Map.of(7, Threshold.OFF), clock, new SceneMetrics(meters));

        assertThat(d.currencyGained(player, 0, 1)).isFalse();
        assertThat(d.itemGained(player, 0, 1)).as("物品 0 与金币 0 各算各的").isFalse();
        assertThat(d.itemGained(player, 0, 1)).isTrue();
        assertThat(d.itemGained(player, 7, 1_000_000)).as("物品 7 覆盖为关闭").isFalse();
        assertThat(d.itemGained(player, 7, 1_000_000)).isFalse();
        assertThat(alerts(0)).isZero();
        assertThat(itemAlerts()).isEqualTo(1);
        assertThat(meters.find("xm.scene.gain.anomalies").tag("category", "item").counters())
                .allSatisfy(c -> assertThat(c.getId().getTag("currency_type")).isEqualTo("none"));
    }
}
