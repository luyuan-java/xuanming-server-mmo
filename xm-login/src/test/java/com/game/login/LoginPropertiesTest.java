package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginPropertiesTest {

    @Test
    void 缺省值按fail_closed取() {
        LoginProperties props = new LoginProperties(null, null, null, null, null, null, null);
        assertThat(props.mode()).isEqualTo(LoginProperties.MODE_PROD);
        assertThat(props.devMode()).isFalse();
        assertThat(props.devAccountPrefixes()).containsExactly("robot_", "dev_");
        assertThat(props.maxPlayersPerAccount()).isEqualTo(5);
        assertThat(props.workerThreads()).isEqualTo(16);
        assertThat(props.workerQueueCapacity()).isEqualTo(1024);
        assertThat(props.sceneAssignTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.ownerClaimWait()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void dev模式() {
        assertThat(new LoginProperties("dev", List.of("qa_"), 3, 4, 8, Duration.ofSeconds(2), Duration.ZERO).devMode())
                .isTrue();
    }

    @Test
    void 非法取值拒绝启动() {
        assertThatThrownBy(() -> new LoginProperties("test", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, -1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, null, null, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginProperties("dev", null, null, null, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
