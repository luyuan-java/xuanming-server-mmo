package com.game.guild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.token.DubboCallAuth;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code xm.guild.*} 的缺省值与启动校验（guild-spec §7.12；基线 config.go:16-19）。 */
class GuildPropertiesTest {

    private static GuildProperties budget(Duration requestBudget) {
        return new GuildProperties(requestBudget, null, null, null, null, null, null);
    }

    @Test
    void 缺省值同基线() {
        GuildProperties p = budget(null);
        assertThat(p.requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThat(p.cacheTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.pushTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.onlineLookupTimeout()).isEqualTo(Duration.ofMillis(800));
        assertThat(p.queryTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.queryTimeoutCapSeconds()).isEqualTo(3);
        assertThat(p.workerThreads()).isEqualTo(16);
        assertThat(p.workerQueueCapacity()).isEqualTo(1024);
    }

    @Test
    void 请求预算必须在500到3500毫秒之间_边界可取() {
        GuildProperties smallest = budget(Duration.ofMillis(500));
        assertThat(smallest.requestBudget()).isEqualTo(Duration.ofMillis(500));
        // 没写的两个上限跟着预算收紧，不必手工改
        assertThat(smallest.onlineLookupTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(smallest.queryTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(smallest.queryTimeoutCapSeconds()).isEqualTo(1);
        assertThat(budget(Duration.ofMillis(3500)).requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThatThrownBy(() -> budget(Duration.ofMillis(499))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ofMillis(3501))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ofSeconds(5))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ZERO)).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ofMillis(-1))).hasMessageContaining("request-budget");
    }

    @Test
    void 语句超时与在线读上限不能超过请求预算() {
        // 预算 500 ms 向上取整是 1 s：query-timeout 1 s 可以、2 s 不行（缺省 3 s 也不行，要显式调小）
        assertThat(new GuildProperties(Duration.ofMillis(500), null, null, Duration.ofMillis(500), Duration.ofSeconds(1), null,
                null).queryTimeoutCapSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> new GuildProperties(Duration.ofMillis(500), null, null, Duration.ofMillis(500),
                Duration.ofSeconds(2), null, null)).hasMessageContaining("query-timeout");
        assertThatThrownBy(() -> new GuildProperties(Duration.ofMillis(1000), null, null, Duration.ofMillis(1001), null,
                null, null)).hasMessageContaining("online-lookup-timeout");
        // 亚秒的语句超时取整到 1 s
        assertThat(new GuildProperties(null, null, null, null, Duration.ofMillis(300), null, null).queryTimeoutCapSeconds())
                .isEqualTo(1);
    }

    @Test
    void 其余项必须为正() {
        assertThatThrownBy(() -> new GuildProperties(null, Duration.ZERO, null, null, null, null, null))
                .hasMessageContaining("cache-ttl");
        assertThatThrownBy(() -> new GuildProperties(null, null, Duration.ofMillis(-1), null, null, null, null))
                .hasMessageContaining("push-timeout");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, Duration.ZERO, null, null, null))
                .hasMessageContaining("online-lookup-timeout");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, null, Duration.ZERO, null, null))
                .hasMessageContaining("query-timeout");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, null, null, 0, null))
                .hasMessageContaining("worker-threads");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, null, null, null, -1))
                .hasMessageContaining("worker-queue-capacity");
    }

    @Test
    void 缺Dubbo调用鉴权密钥拒绝启动() {
        // GuildConfiguration.dubboCallAuth() 用环境变量 XM_DUBBO_SECRET 的值调用它（guild-spec §7.1）
        assertThatThrownBy(() -> DubboCallAuth.requireFromEnvValue(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DubboCallAuth.requireFromEnvValue("")).isInstanceOf(IllegalStateException.class);
    }
}
