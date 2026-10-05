package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.scene.audit.GainAnomalyDetector.Threshold;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 走 Spring Boot 真实的构造器绑定：缺省值、按币种覆盖的阈值、校验。 */
class SceneNodePropertiesTest {

    private static SceneNodeProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("xm", SceneNodeProperties.class);
    }

    @Test
    void 什么都不配_封禁重读10秒_异常阈值同基线缺省() {
        SceneNodeProperties.SceneSettings s = bind(Map.of()).scene();

        assertThat(s.gainBlockRefresh()).isEqualTo(Duration.ofSeconds(10));
        assertThat(s.anomaly().defaults()).isEqualTo(Threshold.DEFAULT);
        assertThat(s.anomaly().currencyThresholds()).isEmpty();
        assertThat(s.anomaly().itemThresholds()).isEmpty();
    }

    @Test
    void 资产通道_端口缺省21100_在途上限缺省256_可覆盖_非法值拒绝() {
        SceneNodeProperties.SceneSettings s = bind(Map.of()).scene();
        assertThat(s.assetRpcPort()).isEqualTo(21100);
        assertThat(s.assetOpMaxInflight()).isEqualTo(256);

        SceneNodeProperties.SceneSettings custom = bind(Map.of("xm.scene.asset-rpc-port", "21101",
                "xm.scene.asset-op-max-inflight", "64")).scene();
        assertThat(custom.assetRpcPort()).isEqualTo(21101);
        assertThat(custom.assetOpMaxInflight()).isEqualTo(64);

        assertThatThrownBy(() -> bind(Map.of("xm.scene.asset-rpc-port", "0"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.asset-rpc-port", "65536"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.asset-op-max-inflight", "0"))).isInstanceOf(BindException.class);
    }

    @Test
    void 按币种覆盖_没写的项取内置缺省_非法值拒绝() {
        SceneNodeProperties.SceneSettings s = bind(Map.of(
                "xm.scene.anomaly.max-count", "0",
                "xm.scene.anomaly.currency.0.max-amount", "5000000",
                "xm.scene.anomaly.currency.1.window", "60s",
                "xm.scene.anomaly.item.10.max-count", "200")).scene();

        assertThat(s.anomaly().defaults()).isEqualTo(new Threshold(Duration.ofSeconds(600), 0, 100_000));
        assertThat(s.anomaly().currencyThresholds()).containsOnly(
                Map.entry(0, new Threshold(Duration.ofSeconds(600), 50, 5_000_000)),
                Map.entry(1, new Threshold(Duration.ofSeconds(60), 50, 100_000)));
        assertThat(s.anomaly().itemThresholds()).containsOnly(
                Map.entry(10, new Threshold(Duration.ofSeconds(600), 200, 100_000)));

        assertThat(bind(Map.of("xm.scene.anomaly.window", "600")).scene().anomaly().window())
                .as("不带单位按秒").isEqualTo(Duration.ofSeconds(600));
        assertThat(bind(Map.of("xm.scene.anomaly.currency.2.window", "30")).scene().anomaly().currencyThresholds()
                .get(2).window()).isEqualTo(Duration.ofSeconds(30));
        assertThatThrownBy(() -> bind(Map.of("xm.scene.anomaly.window", "0s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.anomaly.window", "2d"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.anomaly.currency.1.max-count", "-1")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.gain-block-refresh", "500ms"))).isInstanceOf(BindException.class);
    }
}
