package com.game.common.combat;

import static com.game.common.combat.CombatDamageRules.attackMultiplier;
import static com.game.common.combat.CombatDamageRules.damageBeforeCritical;
import static com.game.common.combat.CombatDamageRules.damageToHealth;
import static com.game.common.combat.CombatDamageRules.levelFactor;
import static com.game.common.combat.CombatDamageRules.receivedRatio;
import static com.game.common.combat.CombatDamageRules.selectAttack;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 逐条移植基线 {@code cpp/tests/turn_battle_engine_test/combat_damage_rules_test.cpp}（10 个用例），外加回合制引擎单测里
 * 只走纯公式的 6 个数值（{@code turn_battle_engine_test.cpp}）。基线的 EXPECT_DOUBLE_EQ 在这里用逐位相等（运算顺序一致）；
 * 防御单位缩放那条两边算式不同，同基线按 4 ULP 比。等级系数封顶与玩家等级上限一致的检查在 xm-scene
 * （{@code PlayerLevelsTest}，那边看得见两个常量）。
 */
class CombatDamageRulesTest {

    private static final long UINT64_MAX = -1L;

    @Test
    void 手算点上的公式() {
        assertThat(receivedRatio(120, 480, 5, 10)).isEqualTo(130.0 / 180.0 * 0.95);
        assertThat(damageBeforeCritical(10.0, 20, 100, 1.0, 120, 480, 5, 10)).isEqualTo(130.0 * (130.0 / 180.0 * 0.95));
    }

    @Test
    void 减伤单调_最多百分之六十() {
        double previous = 1.0;
        for (long defense : new long[] {0, 600, 1560, 12000, 1200000}) {
            double ratio = receivedRatio(0, defense, 0, 10);
            assertThat(ratio).isLessThanOrEqualTo(previous).isGreaterThanOrEqualTo(0.4);
            previous = ratio;
        }
        assertThat(receivedRatio(0, 0, 0, 10)).isEqualTo(1.0);
        assertThat(receivedRatio(0, 1560, 0, 10)).isEqualTo(0.5);
        assertThat(receivedRatio(0, 0, 100, 10)).isEqualTo(0.4);
        assertThat(receivedRatio(0, 0, 250, 10)).as("抗性夹到 100%").isEqualTo(0.4);
        assertThat(receivedRatio(0, 0, UINT64_MAX, 10)).as("抗性按 uint64").isEqualTo(0.4);
    }

    @Test
    void 巨大防御不溢出() {
        assertThat(receivedRatio(UINT64_MAX, UINT64_MAX, 0, 85)).isEqualTo(0.4);
    }

    @Test
    void 目标等级夹到1到85_按无符号() {
        assertThat(levelFactor(1)).isEqualTo(480.0);
        assertThat(levelFactor(0)).isEqualTo(levelFactor(1));
        assertThat(levelFactor(85)).isEqualTo(10560.0);
        assertThat(levelFactor(200)).isEqualTo(levelFactor(85));
        assertThat(levelFactor(-1)).as("uint32 全 1 是极大等级").isEqualTo(levelFactor(85));
    }

    @Test
    void 基础伤害非正或非有限不出伤害() {
        for (double base : new double[] {0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThat(damageBeforeCritical(base, 50, 100000, 1.0, 0, 0, 0, 10)).as("base=%s", base).isEqualTo(0.0);
        }
    }

    @Test
    void 攻击倍率缺省为1_拒绝坏值() {
        assertThat(attackMultiplier(0.0)).isEqualTo(1.0);
        assertThat(attackMultiplier(-0.0)).isEqualTo(1.0);
        assertThat(attackMultiplier(2.5)).isEqualTo(2.5);
        assertThat(attackMultiplier(-1.0)).isEqualTo(0.0);
        assertThat(attackMultiplier(Double.NaN)).isEqualTo(0.0);
        assertThat(damageBeforeCritical(10.0, 0, 100, 0.0, 0, 0, 0, 10)).isEqualTo(110.0);
        assertThat(damageBeforeCritical(10.0, 0, 100, 2.0, 0, 0, 0, 10)).isEqualTo(210.0);
        assertThat(damageBeforeCritical(10.0, 0, 100, -3.0, 0, 0, 0, 10)).isEqualTo(10.0);
    }

    @Test
    void 按伤害类型取攻击() {
        assertThat(selectAttack(1, 300, 700)).isEqualTo(300);
        assertThat(selectAttack(0, 300, 700)).isEqualTo(700);
        assertThat(selectAttack(7, 300, 700)).isZero();
    }

    @Test
    void 扣血向上取整并封顶() {
        assertThat(damageToHealth(10.2, 100)).isEqualTo(11);
        assertThat(damageToHealth(10.0, 100)).isEqualTo(10);
        assertThat(damageToHealth(250.0, 100)).isEqualTo(100);
        assertThat(damageToHealth(1e300, 100)).isEqualTo(100);
        assertThat(damageToHealth(5.0, 0)).isZero();
        for (double raw : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThat(damageToHealth(raw, 100)).as("raw=%s", raw).isZero();
        }
        assertThat(damageToHealth(1e300, UINT64_MAX)).as("uint64 气血按无符号比较").isEqualTo(UINT64_MAX);
        assertThat(Long.toUnsignedString(damageToHealth(1e19, UINT64_MAX))).as("2^63 以上的伤害按 uint64 原样给出")
                .isEqualTo("10000000000000000000");
    }

    @Test
    void 防御单位缩放保持承受比例() {
        assertThat(CombatDamageRules.DEFENSE_UNIT_SCALE).isEqualTo(12.0);
        long[][] pairs = {{10, 40}, {2, 0}, {100, 0}, {10, 150}};
        for (int level : new int[] {1, 10, 85}) {
            double old = 30.0 + 10.0 * level;
            for (long[] pair : pairs) {
                double expected = Math.max(0.4, old / (pair[0] + pair[1] + old));
                assertThat(receivedRatio(pair[0] * 12, pair[1] * 12, 0, level))
                        .as("level=%d armor=%d defense=%d", level, pair[0], pair[1])
                        .isCloseTo(expected, org.assertj.core.data.Offset.offset(4 * Math.ulp(expected)));
            }
        }
    }

    @Test
    void 回合制引擎单测里只走纯公式的数值() {
        double formula = damageBeforeCritical(50, 4, 0, 1, 24, 0, 0, 10);
        assertThat(formula).isEqualTo(68.939393939393938);
        assertThat(damageToHealth(formula, 1000)).isEqualTo(69);
        assertThat(damageToHealth(damageBeforeCritical(10, 4, 50, 1.0, 24, 0, 0, 10), 1000)).isEqualTo(64);
        assertThat(damageToHealth(damageBeforeCritical(50, 4, 20, 1, 24, 0, 0, 10), 1000)).isEqualTo(89);
        assertThat(damageToHealth(damageBeforeCritical(10, 5, 0, 1, 0, 0, 0, 10), 1000)).isEqualTo(15);
        assertThat(damageToHealth(damageBeforeCritical(10, 5, 0, 1, 0, 1560, 0, 10), 1000)).isEqualTo(8);
        assertThat(damageToHealth(damageBeforeCritical(10, 5, 0, 1, 0, 1_000_000, 0, 10), 1000)).isEqualTo(6);
        assertThat(damageToHealth(damageBeforeCritical(10, 21, 0, 1, 0, 0, 0, 10) * 0.3, 1000)).isEqualTo(10);
        assertThat(damageToHealth(damageBeforeCritical(50, 4, 30, 2.0, 24, 0, 0, 10), 1000)).isEqualTo(129);
    }
}
