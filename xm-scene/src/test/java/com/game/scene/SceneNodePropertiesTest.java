package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.scene.audit.GainAnomalyDetector.Threshold;
import com.game.scene.storage.HandOffSettings;
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
    void 频道计划拉取周期_缺省1秒_可覆盖_超出100ms到1分钟拒绝() {
        assertThat(bind(Map.of()).scene().channelPlanPollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(bind(Map.of("xm.scene.channel-plan-poll-interval", "500ms")).scene().channelPlanPollInterval())
                .isEqualTo(Duration.ofMillis(500));

        assertThatThrownBy(() -> bind(Map.of("xm.scene.channel-plan-poll-interval", "50ms")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.channel-plan-poll-interval", "2m")))
                .isInstanceOf(BindException.class);
    }

    @Test
    void 交出参数_缺省边际15秒语句时限3秒_可覆盖_越界拒启() {
        SceneNodeProperties.SceneSettings s = bind(Map.of()).scene();
        assertThat(s.transferLeaseMargin()).isEqualTo(Duration.ofSeconds(15));
        assertThat(s.transferProbeStatementTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(s.handOff()).isEqualTo(HandOffSettings.DEFAULT);

        SceneNodeProperties.SceneSettings custom = bind(Map.of("xm.scene.transfer-lease-margin", "12s",
                "xm.scene.transfer-probe-statement-timeout", "2s")).scene();
        assertThat(custom.handOff()).isEqualTo(new HandOffSettings(Duration.ofSeconds(12), Duration.ofSeconds(2)));

        // 续约周期 10s < M < 租约 30s − 续约周期 = 20s（不含端点）；语句时限 ≥ 1s 且 < M
        assertThatThrownBy(() -> bind(Map.of("xm.scene.transfer-lease-margin", "10s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.transfer-lease-margin", "20s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.transfer-lease-margin", "11s",
                "xm.scene.transfer-probe-statement-timeout", "11s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.transfer-probe-statement-timeout", "500ms")))
                .isInstanceOf(BindException.class);
        assertThat(bind(Map.of("xm.scene.transfer-lease-margin", "19s")).scene().transferLeaseMargin())
                .isEqualTo(Duration.ofSeconds(19));
    }

    @Test
    void 跨节点换图_scene_manager地址_选目标超时_墓碑存活_缺省与越界拒启() {
        SceneNodeProperties.SceneSettings s = bind(Map.of()).scene();
        assertThat(s.sceneManagerUrl()).isEqualTo("tri://127.0.0.1:20882");
        assertThat(s.switchResolveTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(s.transferTombstoneTtl()).isEqualTo(Duration.ofSeconds(30));

        SceneNodeProperties.SceneSettings custom = bind(Map.of("xm.scene.scene-manager-url", "tri://10.0.0.5:20882",
                "xm.scene.switch-resolve-timeout", "3500ms", "xm.scene.transfer-tombstone-ttl", "45s")).scene();
        assertThat(custom.sceneManagerUrl()).isEqualTo("tri://10.0.0.5:20882");
        assertThat(custom.switchResolveTimeout()).isEqualTo(Duration.ofMillis(3500));
        assertThat(custom.transferTombstoneTtl()).isEqualTo(Duration.ofSeconds(45));

        // 本地兜底超时必须大于 scene-manager 的 Dubbo 超时 3s
        assertThatThrownBy(() -> bind(Map.of("xm.scene.switch-resolve-timeout", "3s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.switch-resolve-timeout", "31s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.scene-manager-url", " "))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.transfer-tombstone-ttl", "500ms")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.transfer-tombstone-ttl", "6m"))).isInstanceOf(BindException.class);
    }

    @Test
    void 镜像副本实例_缺省镜像30秒副本300秒宽限30秒上限200与3_可覆盖_越界拒启() {
        SceneNodeProperties.InstanceSettings s = bind(Map.of()).scene().instance();
        assertThat(s.mirrorIdleTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(s.idleTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(s.reclaimGrace()).isEqualTo(Duration.ofSeconds(30));
        assertThat(s.maxPerNode()).isEqualTo(200);
        assertThat(s.maxPerCreator()).isEqualTo(3);

        SceneNodeProperties.InstanceSettings custom = bind(Map.of("xm.scene.instance.mirror-idle-timeout", "5",
                "xm.scene.instance.idle-timeout", "0", "xm.scene.instance.reclaim-grace", "10s",
                "xm.scene.instance.max-per-node", "10000", "xm.scene.instance.max-per-creator", "100")).scene().instance();
        assertThat(custom.mirrorIdleTimeout()).as("不带单位按秒").isEqualTo(Duration.ofSeconds(5));
        assertThat(custom.idleTimeout()).as("0 = 不自动回收").isZero();
        assertThat(custom.reclaimGrace()).isEqualTo(Duration.ofSeconds(10));
        assertThat(custom.maxPerNode()).isEqualTo(10_000);
        assertThat(custom.maxPerCreator()).isEqualTo(100);

        assertThatThrownBy(() -> bind(Map.of("xm.scene.instance.reclaim-grace", "9s")))
                .as("宽限不短于 10s（软预占 TTL / login 归属等待）").isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.instance.mirror-idle-timeout", "-1s")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.instance.idle-timeout", "-1s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.instance.max-per-node", "0"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.instance.max-per-node", "10001")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.instance.max-per-creator", "0")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.scene.instance.max-per-creator", "101")))
                .isInstanceOf(BindException.class);
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
