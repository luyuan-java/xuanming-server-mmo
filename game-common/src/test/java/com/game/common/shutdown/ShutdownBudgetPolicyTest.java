package com.game.common.shutdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ShutdownBudgetPolicyTest {

    @Test
    void 服务停机预算满足两道不等式放行_违背时拒启() {
        var valid = new ShutdownBudgetPolicy.ServiceBudget(
                Duration.ofSeconds(5), Duration.ofSeconds(12), Duration.ofSeconds(8),
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(45));
        assertThat(valid.isValid()).isTrue();
        valid.validateOrThrow("game-login");

        var tooShortForceExit = new ShutdownBudgetPolicy.ServiceBudget(
                Duration.ofSeconds(5), Duration.ofSeconds(12), Duration.ofSeconds(8),
                Duration.ofSeconds(5), Duration.ofSeconds(25), Duration.ofSeconds(5), Duration.ofSeconds(45));
        assertThat(tooShortForceExit.isValid()).isFalse();
        assertThatThrownBy(() -> tooShortForceExit.validateOrThrow("game-login"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forceExitTimeout");

        var tooShortPodGrace = new ShutdownBudgetPolicy.ServiceBudget(
                Duration.ofSeconds(5), Duration.ofSeconds(12), Duration.ofSeconds(8),
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(35));
        assertThat(tooShortPodGrace.isValid()).isFalse();
        assertThatThrownBy(() -> tooShortPodGrace.validateOrThrow("game-login"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("podGracePeriod");
    }

    @Test
    void 战斗服计划排空与Pod宽限期同源推导() {
        var budget = new ShutdownBudgetPolicy.BattleDrainBudget(
                Duration.ofSeconds(300), Duration.ofSeconds(15), Duration.ofSeconds(15));
        assertThat(budget.roomDrainBudget()).isEqualTo(Duration.ofSeconds(300));
        assertThat(budget.podGracePeriod()).isEqualTo(Duration.ofSeconds(330));
    }
}
