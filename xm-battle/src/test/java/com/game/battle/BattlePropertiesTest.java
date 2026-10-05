package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.battle.room.FingerprintMode;
import com.game.common.RunMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** {@code xm.battle.*} 的缺省值与启动校验（battle-node-spec §8.1、§13.5）。 */
class BattlePropertiesTest {

    private static BattleProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("xm.battle", BattleProperties.class);
    }

    @Test
    void 缺省值同规格() {
        BattleProperties p = BattleProperties.defaults();
        assertThat(p.clientPort()).isEqualTo(12000);
        assertThat(p.clientBindHost()).isEqualTo("0.0.0.0");
        assertThat(p.advertisePort()).isZero();
        assertThat(p.effectiveAdvertisePort()).isEqualTo(12000);
        assertThat(p.rpcPort()).isEqualTo(21200);
        assertThat(p.maxConnections()).isEqualTo(4096);
        assertThat(p.effectiveMaxConnections()).isEqualTo(4096);
        assertThat(p.handshakeTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.illegalPacketThreshold()).isEqualTo(50);
        assertThat(p.tableFingerprintMode()).isEqualTo(FingerprintMode.WARN);
        assertThat(p.rpcMaxInflight()).isEqualTo(256);
        assertThat(p.shutdownFlushTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void 从配置绑定_宽松写法() {
        BattleProperties p = bind(Map.of(
                "xm.battle.client-port", "12001",
                "xm.battle.advertise-port", "32001",
                "xm.battle.max-connections", "0",
                "xm.battle.handshake-timeout", "500ms",
                "xm.battle.illegal-packet-threshold", "0",
                "xm.battle.table-fingerprint-mode", "enforce"));
        assertThat(p.clientPort()).isEqualTo(12001);
        assertThat(p.effectiveAdvertisePort()).isEqualTo(32001);
        assertThat(p.effectiveMaxConnections()).as("0 = 硬上限").isEqualTo(65535);
        assertThat(p.handshakeTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(p.illegalPacketThreshold()).isZero();
        assertThat(p.tableFingerprintMode()).isEqualTo(FingerprintMode.ENFORCE);
    }

    @Test
    void 指纹模式写错拒启() {
        assertThatThrownBy(() -> bind(Map.of("xm.battle.table-fingerprint-mode", "strict"))).isInstanceOf(BindException.class);
    }

    @Test
    void 握手期限必须在0到60秒之间() {
        assertThatThrownBy(() -> bind(Map.of("xm.battle.handshake-timeout", "0s"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.handshake-timeout", "61s"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(bind(Map.of("xm.battle.handshake-timeout", "60s")).handshakeTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void 端口与计数范围() {
        assertThatThrownBy(() -> bind(Map.of("xm.battle.client-port", "0"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.rpc-port", "65536"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.advertise-port", "-1"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.max-connections", "-1"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.max-connections", "65536"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.illegal-packet-threshold", "-1")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.rpc-max-inflight", "0"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.battle.shutdown-flush-timeout", "0s")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void max_connections为0只许dev与test() {
        BattleProperties unlimited = bind(Map.of("xm.battle.max-connections", "0"));
        assertThatThrownBy(() -> unlimited.requireAllowedIn(RunMode.PROD)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> unlimited.requireAllowedIn(RunMode.DEV)).doesNotThrowAnyException();
        assertThatCode(() -> unlimited.requireAllowedIn(RunMode.TEST)).doesNotThrowAnyException();
        assertThatCode(() -> BattleProperties.defaults().requireAllowedIn(RunMode.PROD)).doesNotThrowAnyException();
    }

    @Test
    void 客户端通告地址_缺省取控制面通告地址_空白视同不配_配了去首尾空白() {
        assertThat(BattleProperties.defaults().clientAdvertiseHost()).isNull();
        assertThat(BattleProperties.defaults().effectiveClientAdvertiseHost("xm-battle")).isEqualTo("xm-battle");
        assertThat(bind(Map.of("xm.battle.client-advertise-host", "  ")).effectiveClientAdvertiseHost("xm-battle"))
                .isEqualTo("xm-battle");
        BattleProperties split = bind(Map.of("xm.battle.client-advertise-host", " 203.0.113.7 "));
        assertThat(split.clientAdvertiseHost()).isEqualTo("203.0.113.7");
        assertThat(split.effectiveClientAdvertiseHost("xm-battle")).isEqualTo("203.0.113.7");
    }

    @Test
    void 配置文件把环境变量XM_BATTLE_CLIENT_ADVERTISE_HOST接到client_advertise_host_不设时为空() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));

        StandardEnvironment withEnv = new StandardEnvironment();
        withEnv.getPropertySources().addFirst(new MapPropertySource("env", Map.of("XM_BATTLE_CLIENT_ADVERTISE_HOST", "battle.example.com")));
        yaml.forEach(withEnv.getPropertySources()::addLast);
        BattleProperties fromEnv = Binder.get(withEnv).bindOrCreate("xm.battle", BattleProperties.class);
        assertThat(fromEnv.effectiveClientAdvertiseHost("127.0.0.1")).isEqualTo("battle.example.com");

        StandardEnvironment withoutEnv = new StandardEnvironment();
        withoutEnv.getPropertySources().addFirst(new MapPropertySource("env", Map.of("XM_BATTLE_CLIENT_ADVERTISE_HOST", "")));
        yaml.forEach(withoutEnv.getPropertySources()::addLast);
        BattleProperties fallback = Binder.get(withoutEnv).bindOrCreate("xm.battle", BattleProperties.class);
        assertThat(fallback.clientAdvertiseHost()).isNull();
        assertThat(fallback.effectiveClientAdvertiseHost("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @Test
    void 节点身份校验() {
        assertThat(new BattleIdentity(1, "uuid", "127.0.0.1", 12000).advertisePort()).isEqualTo(12000);
        assertThatThrownBy(() -> new BattleIdentity(0, "uuid", "h", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BattleIdentity(1, " ", "h", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BattleIdentity(1, "uuid", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BattleIdentity(1, "uuid", "h", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
