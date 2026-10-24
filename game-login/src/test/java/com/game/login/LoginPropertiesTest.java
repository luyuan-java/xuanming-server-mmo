package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginPropertiesTest {

    @Test
    void 缺省值按fail_closed取() {
        LoginProperties props = new LoginProperties(null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(props.mode()).isEqualTo(LoginProperties.MODE_PROD);
        assertThat(props.devMode()).isFalse();
        assertThat(props.devAccountPrefixes()).containsExactly("robot_", "dev_");
        assertThat(props.maxPlayersPerAccount()).isEqualTo(5);
        assertThat(props.workerThreads()).isEqualTo(16);
        assertThat(props.workerQueueCapacity()).isEqualTo(1024);
        assertThat(props.sceneAssignTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.ownerClaimWait()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.enterGameBudget()).as("小于 gate 调 login 的 Dubbo 超时 5s").isEqualTo(Duration.ofSeconds(4));
        assertThat(props.accessTokenTtl()).isEqualTo(Duration.ofHours(2));
        assertThat(props.refreshTokenTtl()).isEqualTo(Duration.ofHours(720));
        assertThat(props.maxDevicesPerAccount()).isEqualTo(3);
        assertThat(props.deviceSessionTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.refreshReuseGrace()).as("基线 TokenConfig.RefreshReuseGrace").isEqualTo(Duration.ofSeconds(30));
        assertThat(props.refreshReuseRevoke()).as("基线 TokenConfig.RefreshReuseRevoke：窗口外重用即吊销").isTrue();
    }

    @Test
    void 重用吊销开关可关成只观测() {
        assertThat(new LoginProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, false)
                .refreshReuseRevoke()).isFalse();
    }

    @Test
    void refresh有效期不得短于access() {
        assertThatThrownBy(() -> new LoginProperties(null, null, null, null, null, null, null, null, Duration.ofHours(2),
                Duration.ofHours(1), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new LoginProperties(null, null, null, null, null, null, null, null, Duration.ofHours(2), Duration.ofHours(2),
                null, null).refreshTokenTtl()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void refresh重放宽限窗口_0到60秒() {
        assertThat(grace(Duration.ZERO).refreshReuseGrace()).as("0 = 严格一次性").isZero();
        assertThat(grace(Duration.ofSeconds(60)).refreshReuseGrace()).isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> grace(Duration.ofSeconds(61))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> grace(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    private static LoginProperties grace(Duration refreshReuseGrace) {
        return new LoginProperties(null, null, null, null, null, null, null, null, null, null, null, null, refreshReuseGrace);
    }

    @Test
    void dev模式() {
        assertThat(new LoginProperties("dev", List.of("qa_"), 3, 4, 8, Duration.ofSeconds(2), Duration.ZERO,
                Duration.ofSeconds(3), null, null, null, null).devMode())
                .isTrue();
    }

    @Test
    void 非法取值拒绝启动() {
        assertThatThrownBy(() -> new LoginProperties("test", null, null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, 0, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, -1, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, null, null, Duration.ZERO, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, null, null, null, Duration.ofSeconds(-1), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, null, null, null, null, Duration.ZERO, null, null, null, null))
                .as("进游戏的时间预算必须为正")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, null, null, null, null, null, Duration.ZERO, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, null, null, null, null, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
