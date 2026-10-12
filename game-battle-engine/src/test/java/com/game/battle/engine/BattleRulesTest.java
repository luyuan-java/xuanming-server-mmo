package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.table.SkillTable;
import com.game.table.Skillcost_resource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link BattleRules} 与 {@link BattleConstants} 的规则单测（规格 §0.4、§13.1 逃跑概率、§13.3）。
 * 时间换回合与可施放 / AOE / 耗蓝的期望值由基线源码（{@code turn_battle_constants.h:151-162}、
 * {@code turn_battle_engine.cpp:482-493}、{@code :903-922}）手算，逃跑概率是【复核】级。
 */
class BattleRulesTest {

    private static SkillTable skillWithTypes(Integer... bits) {
        return SkillTable.newBuilder().setId(1).addAllSkillType(List.of(bits)).build();
    }

    private static SkillTable skillWithTargeting(Integer... bits) {
        return SkillTable.newBuilder().setId(1).addAllTargetingMode(List.of(bits)).build();
    }

    private static Skillcost_resource cost(int resourceId, int amount) {
        return Skillcost_resource.newBuilder().setCostResourceId(resourceId).setCostResourceCost(amount).build();
    }

    // ---- 常量 ----

    @Test
    void 常量与基线逐项相同() {
        assertThat(BattleConstants.FLEE_SPEED_FACTOR).isEqualTo(8.333333333333334E-4);
        assertThat(BattleConstants.MONSTER_ACTOR_ID_BASE).isEqualTo(0x8000000100000000L);
        assertThat(BattleConstants.PET_ACTOR_ID_BASE).isEqualTo(0x8000000200000000L);
        assertThat(BattleConstants.ENGINE_LOCAL_ACTOR_ID_FLAG).isEqualTo(Long.MIN_VALUE);
        assertThat(BattleConstants.TARGETING_NO_TARGET_REQUIRED).isEqualTo(1);
        assertThat(BattleConstants.TARGETING_TARGETED_SKILL).isEqualTo(2);
        assertThat(BattleConstants.TARGETING_AREA_OF_EFFECT).isEqualTo(4);
    }

    // ---- 时间换回合 ----

    @ParameterizedTest(name = "{0} ms → {1}")
    @CsvSource({
            "0,       1",
            "500,     1",
            "2000,    1",
            "6000,    1",
            "6001,    2",
            "12000,   2",
            "1800000, 300",
            "3600000, 600",
    })
    void 毫秒换回合(long ms, int rounds) {
        assertThat(BattleRules.roundsFromMillis(ms)).isEqualTo(rounds);
    }

    @Test
    void 毫秒换回合_截断与回绕边界照搬基线() {
        // 6000 × 2^32 ms = 2^32 回合，截成 uint32 后为 0（基线 static_cast<uint32_t>，规格 §11.1 第 13 条）
        assertThat(BattleRules.roundsFromMillis(6000L << 32)).isZero();
        // 再多一回合就是 1
        assertThat(BattleRules.roundsFromMillis((6000L << 32) + 1)).isEqualTo(1);
        // 2^64 − 1：加 5999 回绕成 5998，除以 6000 得 0，取下限 1
        assertThat(BattleRules.roundsFromMillis(-1L)).isEqualTo(1);
        // 副本 time_limit 是 uint32 秒：Integer.toUnsignedLong(timeLimit) * 1000 不溢出
        assertThat(Integer.toUnsignedLong(BattleRules.roundsFromMillis(Integer.toUnsignedLong(-1) * 1000L)))
                .isEqualTo(715827883L);
    }

    @ParameterizedTest(name = "{0} s → {1}")
    @CsvSource({
            "0.0,    1",
            "-0.0,   1",
            "-1.0,   1",
            "0.0005, 1",
            "1.0,    1",
            "2.0,    1",
            "5.0,    1",
            "6.0,    1",
            "7.0,    2",
            "12.0,   2",
            "12.5,   3",
            "1800.0, 300",
    })
    void 秒换回合(double seconds, int rounds) {
        assertThat(BattleRules.roundsFromSeconds(seconds)).isEqualTo(rounds);
    }

    @Test
    void 秒换回合_非有限与越界按有意差异D5() {
        // 基线对这几种输入做 static_cast<uint64_t> 是未定义行为；Java 口径：NaN → 1；≥ 2^64 ms（含 +∞）→ 按 UINT64_MAX 处理 → 1
        assertThat(BattleRules.roundsFromSeconds(Double.NaN)).isEqualTo(1);
        assertThat(BattleRules.roundsFromSeconds(Double.POSITIVE_INFINITY)).isEqualTo(1);
        assertThat(BattleRules.roundsFromSeconds(Double.NEGATIVE_INFINITY)).isEqualTo(1);
        assertThat(BattleRules.roundsFromSeconds(1e30)).isEqualTo(1);
        assertThat(BattleRules.roundsFromSeconds(1.9e16)).as("1.9e19 ms ≥ 2^64").isEqualTo(1);
        // 截断到 uint32 的 0 在秒的路径上同样照搬：25769803776 s = 6000 × 2^32 ms
        assertThat(BattleRules.roundsFromSeconds(25769803776.0)).isZero();
    }

    // ---- 可施放过滤 / AOE / 耗蓝 ----

    @Test
    void 被动_持续施法_开关技能不可施放() {
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(0))).isFalse();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(2))).isFalse();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(3))).isFalse();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(5, 3))).as("任一位号命中黑名单").isFalse();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(11, 3))).isFalse();
    }

    @Test
    void 黑名单以外都可施放_空列表也可() {
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes())).isTrue();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(1))).isTrue();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(1, 1))).isTrue();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(4))).isTrue();
        assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(5))).isTrue();
        for (int bit = 6; bit <= 10; bit++) {
            assertThat(BattleRules.isTurnBattleCastableSkill(skillWithTypes(bit))).as("位号 %d", bit).isTrue();
        }
    }

    @Test
    void 目标模式含位号2才是AOE() {
        assertThat(BattleRules.isAreaSkill(skillWithTargeting(1, 2))).as("真表技能 1").isTrue();
        assertThat(BattleRules.isAreaSkill(skillWithTargeting(2, 2))).as("真表技能 2").isTrue();
        assertThat(BattleRules.isAreaSkill(skillWithTargeting(3))).as("真表技能 13").isFalse();
        assertThat(BattleRules.isAreaSkill(skillWithTargeting(1, 1))).isFalse();
        assertThat(BattleRules.isAreaSkill(skillWithTargeting(0))).isFalse();
        assertThat(BattleRules.isAreaSkill(skillWithTargeting())).isFalse();
    }

    @Test
    void 目标位号超过31时与x86一样按32取模() {
        // C++ 的 1u << 34 是未定义行为；x86 的移位指令只取低 5 位，与 Java 的 int 移位相同（规格 §11.2，不另立口径）
        assertThat(BattleRules.isAreaSkill(skillWithTargeting(34))).isTrue();
        assertThat(BattleRules.isAreaSkill(skillWithTargeting(33))).isFalse();
    }

    @Test
    void 耗蓝只认法力资源() {
        // 真表技能 1：cost_resource = [1:40, 2:20, 0:0, 0:0]
        SkillTable realSkill1 = SkillTable.newBuilder().setId(1)
                .addCostResource(cost(1, 40)).addCostResource(cost(2, 20))
                .addCostResource(cost(0, 0)).addCostResource(cost(0, 0)).build();
        assertThat(BattleRules.skillManaCost(realSkill1)).isEqualTo(40);
        assertThat(BattleRules.skillManaCost(SkillTable.newBuilder().setId(2).build())).isZero();
        assertThat(BattleRules.skillManaCost(SkillTable.newBuilder().setId(3).addCostResource(cost(2, 20)).build())).isZero();
        assertThat(BattleRules.skillManaCost(SkillTable.newBuilder().setId(4)
                .addCostResource(cost(1, 10)).addCostResource(cost(1, 5)).build())).as("多项累加").isEqualTo(15);
        assertThat(BattleRules.skillManaCost(SkillTable.newBuilder().setId(5).addCostResource(cost(1, -1)).build()))
                .as("uint32 按无符号").isEqualTo(4294967295L);
    }

    // ---- 逃跑成功率 ----

    @ParameterizedTest(name = "自身 {0} 对 敌方 {1} → {2}")
    @CsvSource({
            "600,  60,   0.95",
            "1440, 12,   0.95",
            "120,  60,   0.55",
            "120,  240,  0.4",
            "60,   60,   0.5",
            "0,    1200, 0.05",
    })
    void 逃跑成功率(long self, long enemy, double chance) {
        // 【复核】
        assertThat(BattleRules.fleeChance(self, enemy)).isEqualTo(chance);
    }

    @Test
    void 逃跑成功率_夹紧前的原值() {
        // 【复核】(0.01/12.0) × 540 恰为 0.45，加 0.5 恰为 0.95；1440 对 12 的原值是 1.6900000000000002，夹到 0.95
        assertThat(BattleConstants.FLEE_SPEED_FACTOR * 540.0).isEqualTo(0.45);
        assertThat(BattleConstants.FLEE_BASE_CHANCE + BattleConstants.FLEE_SPEED_FACTOR * 540.0).isEqualTo(0.95);
        assertThat(BattleConstants.FLEE_BASE_CHANCE + BattleConstants.FLEE_SPEED_FACTOR * 1428.0).isEqualTo(1.6900000000000002);
    }

    @Test
    void 逃跑成功率_速度按无符号() {
        assertThat(BattleRules.fleeChance(-1L, 0)).as("自身速度 2^64−1").isEqualTo(BattleConstants.FLEE_MAX_CHANCE);
        assertThat(BattleRules.fleeChance(0, -1L)).as("敌方速度 2^64−1").isEqualTo(BattleConstants.FLEE_MIN_CHANCE);
    }
}
