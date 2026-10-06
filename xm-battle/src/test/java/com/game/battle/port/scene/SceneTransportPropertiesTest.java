package com.game.battle.port.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * battle → scene 传输的配置（scene-battle-spec §8、§7.3、§10.4；§13.1 {@code BattleRedisConstantsTest} 末项里属于 xm-battle 的那一条）：
 * 缺省值、非法值，以及启动门禁——{@code xm.battle.scene-rpc-timeout} 必须<b>大于</b> Redis 单条命令的最坏耗时
 * （{@link RedisProperties#worstCaseCommandMillis()}，缺省 4.2 s），否则 scene 已给出确定结论、调用方却先按「结局未知」处理。
 */
class SceneTransportPropertiesTest {

    private static RedisProperties defaultRedis() {
        return new RedisProperties(null, null, null, null, null, null, null);
    }

    private static SceneTransportProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("xm.battle", SceneTransportProperties.class);
    }

    @Test
    void 缺省值_调用超时5秒_停机排空3秒() {
        SceneTransportProperties props = SceneTransportProperties.defaults();

        assertThat(props.sceneRpcTimeout()).isEqualTo(Duration.ofSeconds(5)).isEqualTo(SceneTransportProperties.DEFAULT_SCENE_RPC_TIMEOUT);
        assertThat(props.outboxDrainTimeout()).isEqualTo(Duration.ofSeconds(3))
                .isEqualTo(SceneTransportProperties.DEFAULT_OUTBOX_DRAIN_TIMEOUT);
        assertThat(new SceneTransportProperties(null, null)).isEqualTo(props);
    }

    @Test
    void 缺省调用超时大于Redis缺省最坏耗时_缺省配置能过启动门禁() {
        long worst = defaultRedis().worstCaseCommandMillis();

        assertThat(worst).as("(1 + 1) × 2000 + 200").isEqualTo(4_200);
        assertThat(SceneTransportProperties.DEFAULT_SCENE_RPC_TIMEOUT.toMillis()).isGreaterThan(worst);
        assertThatCode(() -> SceneTransportProperties.defaults().requireAbove(worst)).doesNotThrowAnyException();
    }

    @Test
    void requireAbove_必须严格大于_相等也拒绝_消息带键名与两个数() {
        SceneTransportProperties atWorst = new SceneTransportProperties(Duration.ofMillis(4_200), null);
        SceneTransportProperties justAbove = new SceneTransportProperties(Duration.ofMillis(4_201), null);
        SceneTransportProperties below = new SceneTransportProperties(Duration.ofSeconds(4), null);

        assertThatThrownBy(() -> atWorst.requireAbove(4_200)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("xm.battle.scene-rpc-timeout").hasMessageContaining("4200 ms");
        assertThatThrownBy(() -> below.requireAbove(4_200)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("xm.battle.scene-rpc-timeout").hasMessageContaining("PT4S");
        assertThatCode(() -> justAbove.requireAbove(4_200)).doesNotThrowAnyException();
    }

    @Test
    void 门槛随Redis配置变化_调大Redis超时后缺省5秒不再够() {
        RedisProperties slow = new RedisProperties(null, null, null, null, 3_000, 1, 200);
        RedisProperties noRetry = new RedisProperties(null, null, null, null, 2_000, 0, 200);

        assertThat(slow.worstCaseCommandMillis()).isEqualTo(6_200);
        assertThatThrownBy(() -> SceneTransportProperties.defaults().requireAbove(slow.worstCaseCommandMillis()))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new SceneTransportProperties(Duration.ofSeconds(7), null).requireAbove(slow.worstCaseCommandMillis()))
                .doesNotThrowAnyException();
        assertThat(noRetry.worstCaseCommandMillis()).isEqualTo(2_000);
        assertThatCode(() -> new SceneTransportProperties(Duration.ofSeconds(3), null).requireAbove(noRetry.worstCaseCommandMillis()))
                .doesNotThrowAnyException();
    }

    @Test
    void 调用超时为0_为负_超出int毫秒_拒绝() {
        assertThatThrownBy(() -> new SceneTransportProperties(Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xm.battle.scene-rpc-timeout");
        assertThatThrownBy(() -> new SceneTransportProperties(Duration.ofSeconds(-1), null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xm.battle.scene-rpc-timeout");
        assertThatThrownBy(() -> new SceneTransportProperties(Duration.ofMillis(Integer.MAX_VALUE + 1L), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new SceneTransportProperties(Duration.ofMillis(Integer.MAX_VALUE), null)).doesNotThrowAnyException();
        assertThatCode(() -> new SceneTransportProperties(Duration.ofMillis(1), null)).doesNotThrowAnyException();
    }

    @Test
    void 停机排空为负拒绝_为0允许_表示不等() {
        assertThatThrownBy(() -> new SceneTransportProperties(null, Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xm.battle.outbox-drain-timeout");
        assertThat(new SceneTransportProperties(null, Duration.ZERO).outboxDrainTimeout()).isZero();
    }

    @Test
    void 按配置键绑定_scene_rpc_timeout与outbox_drain_timeout_没配取缺省() {
        SceneTransportProperties bound = bind(Map.of("xm.battle.scene-rpc-timeout", "7s", "xm.battle.outbox-drain-timeout", "500ms"));
        assertThat(bound.sceneRpcTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(bound.outboxDrainTimeout()).isEqualTo(Duration.ofMillis(500));

        SceneTransportProperties partial = bind(Map.of("xm.battle.max-connections", "100"));
        assertThat(partial).as("同前缀下 BattleProperties 的键不影响它").isEqualTo(SceneTransportProperties.defaults());
    }

    @Test
    void 绑定到非法值_绑定失败() {
        assertThatThrownBy(() -> bind(Map.of("xm.battle.scene-rpc-timeout", "0s"))).isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.outbox-drain-timeout", "-1s"))).isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
