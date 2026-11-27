package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 批次 4T-2B：battle 计划排空预算配置校验（默认 0 关闭，负数拒启，正值开启）。 */
class BattlePlannedDrainTest {

    @Test
    void 战斗服计划排空预算默认关闭_支持显式开启且拒绝负数() {
        BattleProperties defaults = BattleProperties.defaults();
        assertThat(defaults.plannedDrainBudget()).isEqualTo(Duration.ZERO);

        BattleProperties enabled = new BattleProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, Duration.ofSeconds(30));
        assertThat(enabled.plannedDrainBudget()).isEqualTo(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new BattleProperties(
                null, null, null, null, null, null, null, null, null, null, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planned-drain-budget");
    }
}
