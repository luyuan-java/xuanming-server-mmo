package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.battle.BattleRedis;
import com.game.scene.audit.GainAnomalyDetector.Threshold;
import com.game.scene.storage.HandOffSettings;
import java.time.Duration;
import java.util.List;
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

    /**
     * scene-battle-spec §8 / §7.2 常量表：reaper 间隔缺省 30 s = 跨进程常量 {@code REAPER_INTERVAL}，只许调小（0 &lt; 值 ≤ 30 s）——
     * 调大就破坏「FIGHTING 判废宽限 + 间隔 &lt; 锁余量」（第一次 rescue 最晚在期限 + 40 s，此时锁一定还在）。本机切片设 2 s。
     */
    @Test
    void 回合制战斗_reaper间隔缺省30秒_只许调小_0与负数与超过30秒拒启() {
        assertThat(bind(Map.of()).scene().battle().reaperInterval()).isEqualTo(Duration.ofSeconds(30))
                .isEqualTo(BattleRedis.REAPER_INTERVAL);
        assertThat(BattleRedis.FIGHTING_EXPIRY_GRACE.plus(BattleRedis.REAPER_INTERVAL))
                .as("校验要保住的不等式：宽限 + 间隔 < 锁余量").isLessThan(Duration.ofSeconds(BattleRedis.LOCK_EXTRA_TTL_SEC));

        assertThat(reaperInterval("2s")).as("本机切片").isEqualTo(Duration.ofSeconds(2));
        assertThat(reaperInterval("30s")).as("上界本身合法").isEqualTo(Duration.ofSeconds(30));
        assertThat(reaperInterval("29999ms")).isEqualTo(Duration.ofMillis(29_999));
        assertThat(reaperInterval("1ms")).as("下界是开区间，任何正数都行").isEqualTo(Duration.ofMillis(1));
        assertThat(reaperInterval("5")).as("不带单位按秒").isEqualTo(Duration.ofSeconds(5));

        for (String rejected : List.of("0", "0s", "0ms", "-1s", "-1", "30001ms", "31s", "31", "1m", "1h")) {
            assertThatThrownBy(() -> reaperInterval(rejected)).as("reaper-interval = %s 应拒启", rejected)
                    .isInstanceOf(BindException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("xm.scene.battle.reaper-interval");
        }
    }

    private static Duration reaperInterval(String value) {
        return bind(Map.of("xm.scene.battle.reaper-interval", value)).scene().battle().reaperInterval();
    }

    @Test
    void 回合制战斗入口_在途上限缺省256_与资产通道各自独立_可覆盖_小于1拒启() {
        SceneNodeProperties.SceneSettings s = bind(Map.of()).scene();
        assertThat(s.battleRpcMaxInflight()).isEqualTo(256);

        SceneNodeProperties.SceneSettings custom = bind(Map.of("xm.scene.battle-rpc-max-inflight", "8")).scene();
        assertThat(custom.battleRpcMaxInflight()).isEqualTo(8);
        assertThat(custom.assetOpMaxInflight()).as("两个上限互不影响").isEqualTo(256);
        assertThat(bind(Map.of("xm.scene.asset-op-max-inflight", "64")).scene().battleRpcMaxInflight()).isEqualTo(256);
        assertThat(bind(Map.of("xm.scene.battle-rpc-max-inflight", "1")).scene().battleRpcMaxInflight()).isEqualTo(1);

        assertThatThrownBy(() -> bind(Map.of("xm.scene.battle-rpc-max-inflight", "0"))).isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("xm.scene.battle-rpc-max-inflight");
        assertThatThrownBy(() -> bind(Map.of("xm.scene.battle-rpc-max-inflight", "-1"))).isInstanceOf(BindException.class);
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

    /**
     * 批次 5.4 的第一条启动校验：{@code xm.scene.travel.team-check-timeout} 缺省 2 s，必须为正且不超过 5 s
     * （226 的应答是延迟的同步应答，上界 = 它 + 1 s，要远小于客户端 15 s 的请求超时）。只构造记录，不起容器。
     */
    @Test
    void 跨zone传送_在队检查上限缺省2秒_可覆盖_0与负数与超过5秒拒启() {
        assertThat(bind(Map.of()).scene().travel().teamCheckTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(teamCheckTimeout("1500ms")).isEqualTo(Duration.ofMillis(1500));
        assertThat(teamCheckTimeout("5s")).as("上界本身合法").isEqualTo(Duration.ofSeconds(5));
        assertThat(teamCheckTimeout("1ms")).as("下界是开区间，任何正数都行").isEqualTo(Duration.ofMillis(1));

        for (String rejected : List.of("0", "0s", "0ms", "-1s", "5001ms", "6s", "1m")) {
            assertThatThrownBy(() -> teamCheckTimeout(rejected)).as("team-check-timeout = %s 应拒启", rejected)
                    .isInstanceOf(BindException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("xm.scene.travel.team-check-timeout");
        }
        assertThatThrownBy(() -> new SceneNodeProperties.TravelSettings(null)).as("直接构造给 null 也拒")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Duration teamCheckTimeout(String value) {
        return bind(Map.of("xm.scene.travel.team-check-timeout", value)).scene().travel().teamCheckTimeout();
    }

    /**
     * 批次 5.4 的第二条启动校验：在队检查 + 选目标 + 交出的安全边际 + 5 s 必须小于 60 s（缺省 2 + 4 + 15 + 5 = 26 s）。
     * 三个键各自的上界（5 s、30 s、&lt; 20 s）加起来恰好到不了 60 s，所以经配置绑定造不出违反它的组合——这里直接对着校验函数断言不等式本身
     * （边界两侧各一条），再确认「三个键都取到各自上界」的配置仍然起得来（校验没有把合法配置误拒）。
     */
    @Test
    void 跨zone传送_时间预算_三个时限加5秒必须小于60秒_各键取到上界时仍合法() {
        Duration s2 = Duration.ofSeconds(2);
        Duration s4 = Duration.ofSeconds(4);
        Duration s15 = Duration.ofSeconds(15);
        SceneNodeProperties.SceneSettings.requireTravelBudget(s2, s4, s15);
        SceneNodeProperties.SceneSettings.requireTravelBudget(Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofMillis(19_999));

        assertThatThrownBy(() -> SceneNodeProperties.SceneSettings.requireTravelBudget(Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(20))).as("5 + 30 + 20 + 5 = 60，不小于 60")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xm.scene.travel.team-check-timeout")
                .hasMessageContaining("xm.scene.switch-resolve-timeout")
                .hasMessageContaining("xm.scene.transfer-lease-margin")
                .hasMessageContaining("60");
        assertThatThrownBy(() -> SceneNodeProperties.SceneSettings.requireTravelBudget(Duration.ofSeconds(2),
                Duration.ofSeconds(40), s15)).as("2 + 40 + 15 + 5 = 62").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SceneNodeProperties.SceneSettings.requireTravelBudget(Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofMillis(20_001))).isInstanceOf(IllegalArgumentException.class);

        SceneNodeProperties.SceneSettings atBounds = bind(Map.of("xm.scene.travel.team-check-timeout", "5s",
                "xm.scene.switch-resolve-timeout", "30s", "xm.scene.transfer-lease-margin", "19999ms")).scene();
        assertThat(atBounds.travel().teamCheckTimeout().plus(atBounds.switchResolveTimeout())
                .plus(atBounds.transferLeaseMargin()).plus(SceneNodeProperties.SceneSettings.TRAVEL_SLACK))
                .as("三个键都在各自上界时仍小于预算").isLessThan(SceneNodeProperties.SceneSettings.TRAVEL_BUDGET);
        assertThat(SceneNodeProperties.SceneSettings.TRAVEL_BUDGET).isEqualTo(Duration.ofSeconds(60));
        assertThat(SceneNodeProperties.SceneSettings.TRAVEL_SLACK).isEqualTo(Duration.ofSeconds(5));
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
