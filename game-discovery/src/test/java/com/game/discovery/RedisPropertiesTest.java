package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

class RedisPropertiesTest {

    @Test
    void 默认值_单条命令最坏阻塞低于客户端5秒超时() {
        RedisProperties props = new RedisProperties(null, null, " ", null, null, null, null);

        assertThat(props.address()).isEqualTo("redis://127.0.0.1:6379");
        assertThat(props.database()).isEqualTo(12);
        assertThat(props.password()).isNull();
        assertThat(props.connectTimeoutMs()).isEqualTo(2000);
        assertThat(props.timeoutMs()).isEqualTo(2000);
        assertThat(props.retryAttempts()).isEqualTo(1);
        assertThat(props.retryDelayMs()).isEqualTo(200);
        assertThat(props.worstCaseCommandMillis()).isEqualTo(4200).isLessThan(5000);
    }

    @Test
    void 超时与重试写进Redisson单机配置() {
        RedisProperties props = new RedisProperties("redis://10.0.0.1:6380", 3, "pw", 1500, 1200, 2, 50);

        SingleServerConfig server = RedisAutoConfiguration.configure(new Config().useSingleServer(), props);

        assertThat(server.getAddress()).isEqualTo("redis://10.0.0.1:6380");
        assertThat(server.getDatabase()).isEqualTo(3);
        assertThat(server.getPassword()).isEqualTo("pw");
        assertThat(server.getConnectTimeout()).isEqualTo(1500);
        assertThat(server.getTimeout()).isEqualTo(1200);
        assertThat(server.getRetryAttempts()).isEqualTo(2);
        assertThat(server.getRetryDelay().calcDelay(0)).isEqualTo(Duration.ofMillis(50));
        assertThat(server.getRetryDelay().calcDelay(5)).as("固定间隔，不指数退避").isEqualTo(Duration.ofMillis(50));
        assertThat(props.worstCaseCommandMillis()).isEqualTo(3 * 1200 + 2 * 50);
    }

    @Test
    void 非法取值拒绝启动() {
        assertThatThrownBy(() -> new RedisProperties(null, null, null, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisProperties(null, null, null, null, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisProperties(null, null, null, null, null, -1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisProperties(null, null, null, null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
