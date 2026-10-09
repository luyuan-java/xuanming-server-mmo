package com.game.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.gate.session.GateLimits;
import com.game.net.limit.MessageLimits;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

class GatePropertiesTest {

    private static GateProperties withPorts(Integer clientPort, Integer advertisePort) {
        return new GateProperties(clientPort, advertisePort, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void advertisePortDefaultsToClientPort() {
        assertThat(withPorts(12000, null).advertisePort()).isEqualTo(12000);
        assertThat(withPorts(12000, 0).advertisePort()).isEqualTo(12000);
        assertThat(withPorts(null, null).advertisePort()).isEqualTo(11000);
    }

    @Test
    void advertisePortCanDifferFromListenPort() {
        GateProperties props = withPorts(11000, 30011);
        assertThat(props.clientPort()).isEqualTo(11000);
        assertThat(props.advertisePort()).isEqualTo(30011);
    }

    @Test
    void rejectsOutOfRangeAdvertisePort() {
        assertThatThrownBy(() -> withPorts(11000, 65536)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withPorts(11000, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static GateProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("xm.gate", GateProperties.class);
    }

    /**
     * 批次 5.4 先行件：{@code xm.gate.redirect-linger}（已重定向会话的收口时限）缺省 60 s，短于 15 s 拒启。
     * 走 Spring Boot 真实的构造器绑定，只构造记录、不起容器。
     */
    @Test
    void 重定向收口时限_缺省60秒_可覆盖_下限15秒本身合法_短于15秒拒启() {
        assertThat(bind(Map.of()).redirectLinger()).isEqualTo(Duration.ofSeconds(60));
        assertThat(withPorts(null, null).redirectLinger()).as("直接构造给 null 同样取缺省").isEqualTo(Duration.ofSeconds(60));
        assertThat(bind(Map.of("xm.gate.redirect-linger", "90s")).redirectLinger()).isEqualTo(Duration.ofSeconds(90));
        assertThat(bind(Map.of("xm.gate.redirect-linger", "15s")).redirectLinger()).as("下限本身合法")
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(bind(Map.of("xm.gate.redirect-linger", "2m")).redirectLinger()).isEqualTo(Duration.ofMinutes(2));

        for (String rejected : List.of("14999ms", "14s", "1s", "0s", "0", "-60s")) {
            assertThatThrownBy(() -> bind(Map.of("xm.gate.redirect-linger", rejected)))
                    .as("redirect-linger = %s 应拒启", rejected)
                    .isInstanceOf(BindException.class)
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause().hasMessageContaining("xm.gate.redirect-linger");
        }
    }

    /** 配置绑定出来的值原样进会话层的阈值记录：两处校验的下限是同一个常量，不会一处放行、另一处拒绝。 */
    @Test
    void 绑定出来的收口时限能原样构造会话层阈值_其余键的缺省不受影响() {
        GateProperties props = bind(Map.of("xm.gate.redirect-linger", "15s"));

        GateLimits limits = new GateLimits(props.maxPendingRequests(), props.illegalPacketThreshold(), props.handshakeTimeout(),
                MessageLimits.UNLIMITED, false, props.redirectLinger());

        assertThat(limits.redirectLinger()).isEqualTo(Duration.ofSeconds(15)).isEqualTo(GateLimits.MIN_REDIRECT_LINGER);
        assertThat(props.clientPort()).isEqualTo(11000);
        assertThat(props.handshakeTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.maxPendingRequests()).isEqualTo(64);
        assertThat(props.shutdownDrainTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    /** 仓库里的缺省配置文件写着这个键，取值就是代码里的缺省（改了一边没改另一边时这里红）。 */
    @Test
    void 缺省配置文件里的redirect_linger等于代码缺省() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("xm.gate.redirect-linger")).isEqualTo("60s");
        assertThat(bind(Map.of("xm.gate.redirect-linger", properties.getProperty("xm.gate.redirect-linger"))).redirectLinger())
                .isEqualTo(GateLimits.DEFAULT_REDIRECT_LINGER);
    }
}
