package com.game.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TeamPropertiesTest {

    private static TeamProperties props(Duration requestBudget) {
        return new TeamProperties(null, requestBudget, null, null, null, null, null, null, null, null);
    }

    @Test
    void 缺省值同基线() {
        TeamProperties p = props(null);
        assertThat(p.allowCrossZone()).isFalse();
        assertThat(p.requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThat(p.pushBatchBudget()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.homeZoneTimeout()).isEqualTo(Duration.ofMillis(1500));
        assertThat(p.workerThreads()).isEqualTo(16);
        assertThat(p.workerQueueCapacity()).isEqualTo(1024);
        assertThat(p.pushThreads()).isEqualTo(4);
        assertThat(p.pushQueueCapacity()).isEqualTo(1024);
        assertThat(p.matchEndThreads()).as("整队开战收尾池").isEqualTo(4);
        assertThat(p.matchEndQueueCapacity()).isEqualTo(1024);
        assertThat(p.queryTimeoutCapSeconds()).isEqualTo(4);
    }

    @Test
    void 请求预算必须在500到3500毫秒之间_边界可取() {
        assertThat(props(Duration.ofMillis(500)).requestBudget()).isEqualTo(Duration.ofMillis(500));
        assertThat(props(Duration.ofMillis(500)).queryTimeoutCapSeconds()).isEqualTo(1);
        assertThat(props(Duration.ofMillis(3500)).requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThatThrownBy(() -> props(Duration.ofMillis(499))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> props(Duration.ofMillis(3501))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> props(Duration.ofSeconds(5))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> props(Duration.ZERO)).hasMessageContaining("request-budget");
    }

    @Test
    void 其余项必须为正() {
        assertThatThrownBy(() -> new TeamProperties(null, null, Duration.ZERO, null, null, null, null, null, null, null))
                .hasMessageContaining("push-batch-budget");
        assertThatThrownBy(() -> new TeamProperties(null, null, null, Duration.ofMillis(-1), null, null, null, null, null, null))
                .hasMessageContaining("home-zone-timeout");
        assertThatThrownBy(() -> new TeamProperties(null, null, null, null, 0, null, null, null, null, null))
                .hasMessageContaining("worker-threads");
        assertThatThrownBy(() -> new TeamProperties(null, null, null, null, null, 0, null, null, null, null))
                .hasMessageContaining("worker-queue-capacity");
        assertThatThrownBy(() -> new TeamProperties(null, null, null, null, null, null, -1, null, null, null))
                .hasMessageContaining("push-threads");
        assertThatThrownBy(() -> new TeamProperties(null, null, null, null, null, null, null, 0, null, null))
                .hasMessageContaining("push-queue-capacity");
        assertThatThrownBy(() -> new TeamProperties(null, null, null, null, null, null, null, null, 0, null))
                .hasMessageContaining("match-end-threads");
        assertThatThrownBy(() -> new TeamProperties(null, null, null, null, null, null, null, null, null, -5))
                .hasMessageContaining("match-end-queue-capacity");
        TeamProperties tuned = new TeamProperties(null, null, null, null, null, null, null, null, 2, 16);
        assertThat(tuned.matchEndThreads()).isEqualTo(2);
        assertThat(tuned.matchEndQueueCapacity()).isEqualTo(16);
        assertThat(new TeamProperties(true, null, null, null, null, null, null, null, null, null).allowCrossZone()).isTrue();
    }

    @Test
    void 缺Dubbo调用鉴权密钥拒绝启动() {
        // TeamConfiguration.dubboCallAuth() 用环境变量 XM_DUBBO_SECRET 的值调用它（team-spec §6.1）
        assertThatThrownBy(() -> com.game.common.token.DubboCallAuth.requireFromEnvValue(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("XM_DUBBO_SECRET");
        assertThatThrownBy(() -> com.game.common.token.DubboCallAuth.requireFromEnvValue("  "))
                .isInstanceOf(IllegalStateException.class);
    }
}
