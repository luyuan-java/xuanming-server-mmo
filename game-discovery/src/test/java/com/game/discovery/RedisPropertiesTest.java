package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
    void 连接池缺省值与Redisson库缺省相同_显式写出不改变行为() {
        RedisProperties props = new RedisProperties(null, null, null, null, null, null, null);
        SingleServerConfig library = new Config().useSingleServer();

        assertThat(props.connectionPoolSize()).isEqualTo(64)
                .as("升级 Redisson 后库缺省变了：决定是否跟随，并同步 architecture.md §6.1 的连接总账")
                .isEqualTo(library.getConnectionPoolSize());
        assertThat(props.connectionMinimumIdleSize()).isEqualTo(24)
                .isEqualTo(library.getConnectionMinimumIdleSize());
        assertThat(props.subscriptionConnectionPoolSize()).isEqualTo(50)
                .isEqualTo(library.getSubscriptionConnectionPoolSize());
        assertThat(library.getSubscriptionConnectionMinimumIdleSize())
                .as("订阅池上限的下限 1 依赖订阅最小空闲的库缺省 1").isEqualTo(1);
        assertThat(props.maxConnections()).isEqualTo(64 + 50);
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
    void 连接池三项写进Redisson单机配置() {
        // 三个值都与库缺省不同，接线漏掉任何一项都会读到库缺省而失败
        RedisProperties props = new RedisProperties(null, null, null, null, null, null, null, 16, 4, 8);

        SingleServerConfig server = RedisAutoConfiguration.configure(new Config().useSingleServer(), props);

        assertThat(server.getConnectionPoolSize()).isEqualTo(16);
        assertThat(server.getConnectionMinimumIdleSize()).isEqualTo(4);
        assertThat(server.getSubscriptionConnectionPoolSize()).isEqualTo(8);
        assertThat(server.getSubscriptionConnectionMinimumIdleSize()).as("不开放，保持库缺省").isEqualTo(1);
        assertThat(props.maxConnections()).isEqualTo(16 + 8);
    }

    @Test
    void 配置键按宽松绑定落到全参构造() {
        RedisProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "game.redis.timeout-ms", "1500",
                "game.redis.connection-pool-size", "32",
                "game.redis.connection-minimum-idle-size", "0",
                "game.redis.subscription-connection-pool-size", "1")))
                .bind("game.redis", Bindable.of(RedisProperties.class)).get();

        assertThat(props.timeoutMs()).isEqualTo(1500);
        assertThat(props.connectionPoolSize()).isEqualTo(32);
        assertThat(props.connectionMinimumIdleSize()).isZero();
        assertThat(props.subscriptionConnectionPoolSize()).isEqualTo(1);
        assertThat(props.address()).as("没给的键取缺省").isEqualTo("redis://127.0.0.1:6379");
    }

    @Test
    void 只配地址时连接池取缺省() {
        RedisProperties props = new Binder(new MapConfigurationPropertySource(Map.of(
                "game.redis.address", "redis://10.0.0.2:6379")))
                .bind("game.redis", Bindable.of(RedisProperties.class)).get();

        assertThat(props.connectionPoolSize()).isEqualTo(RedisProperties.DEFAULT_CONNECTION_POOL_SIZE);
        assertThat(props.connectionMinimumIdleSize()).isEqualTo(RedisProperties.DEFAULT_CONNECTION_MINIMUM_IDLE_SIZE);
        assertThat(props.subscriptionConnectionPoolSize()).isEqualTo(RedisProperties.DEFAULT_SUBSCRIPTION_CONNECTION_POOL_SIZE);
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

    @Test
    void 连接池非法取值拒绝启动() {
        assertThatThrownBy(() -> pool(0, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection-pool-size");
        assertThatThrownBy(() -> pool(-1, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool(null, null, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subscription-connection-pool-size");
        assertThatThrownBy(() -> pool(null, -1, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection-minimum-idle-size");
        assertThatThrownBy(() -> pool(8, 9, null)).as("最小空闲大于池上限").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection-minimum-idle-size");
        assertThatThrownBy(() -> pool(16, null, null)).as("只调小池上限、最小空闲仍是缺省 24")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 连接池边界取值接受() {
        assertThat(pool(1, 0, 1).maxConnections()).isEqualTo(2);
        assertThat(pool(8, 8, null).connectionMinimumIdleSize()).as("最小空闲可以等于池上限").isEqualTo(8);
    }

    @Test
    void 绑定非法连接池在建客户端之前失败() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "game.redis.connection-pool-size", "8", "game.redis.connection-minimum-idle-size", "9")));

        assertThatThrownBy(() -> binder.bind("game.redis", Bindable.of(RedisProperties.class)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static RedisProperties pool(Integer size, Integer minIdle, Integer subscriptionSize) {
        return new RedisProperties(null, null, null, null, null, null, null, size, minIdle, subscriptionSize);
    }
}
