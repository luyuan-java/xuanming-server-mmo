package com.game.scene.attribute;

import static com.game.scene.attribute.AttributeRules.UINT32_MAX;
import static com.game.scene.attribute.AttributeRules.allocatedIncrement;
import static com.game.scene.attribute.AttributeRules.distributePoints;
import static com.game.scene.attribute.AttributeRules.effectivePoints;
import static com.game.scene.attribute.AttributeRules.fullInvestmentEffectivePoints;
import static com.game.scene.attribute.AttributeRules.fullInvestmentPoints;
import static com.game.scene.attribute.AttributeRules.makeFormulaRule;
import static com.game.scene.attribute.AttributeRules.rescaleCurrent;
import static com.game.scene.attribute.AttributeRules.totalPoints;
import static com.game.scene.attribute.AttributeRules.validateAllocation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.game.scene.attribute.AttributeRules.AllocError;
import com.game.scene.attribute.AttributeRules.FormulaRule;
import com.game.scene.attribute.AttributeRules.PoolRule;
import com.game.scene.attribute.AttributeRules.Validation;
import com.game.scene.player.PlayerLevels;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 加点纯规则，用例逐条对照 mmorpg {@code cpp/tests/turn_battle_engine_test/attribute_allocation_rules_test.cpp}
 * （同一组数字：客户端坏数据、只增不减、总量换算、自动加点、气血随上限、加点收益公式）。
 */
class AttributeRulesTest {

    private static final int CONSTITUTION = 101;
    private static final int SPIRIT = 102;
    private static final int STRENGTH = 103;
    private static final int AGILITY = 104;
    private static final long LEVEL = 30;

    /** 镜像 AttributePool 行 1（属性点）：1 级解锁、每级 5 点、单项不限。 */
    private static PoolRule attributePool() {
        return new PoolRule(1, 1, 5, 0, 0);
    }

    /** 有单项上限的通用夹具（不对应表行）：1 级解锁、每级 1 点、单项上限 50。 */
    private static PoolRule cappedPool() {
        return new PoolRule(90, 1, 1, 0, 50);
    }

    /** 高等级才解锁的通用夹具：60 级解锁、每级 1 点。 */
    private static PoolRule lockedPool() {
        return new PoolRule(91, 60, 1, 0, 0);
    }

    private static Map<Integer, Long> attributeCurrent(long constitution, long spirit, long strength, long agility) {
        Map<Integer, Long> current = new TreeMap<>();
        current.put(CONSTITUTION, constitution);
        current.put(SPIRIT, spirit);
        current.put(STRENGTH, strength);
        current.put(AGILITY, agility);
        return current;
    }

    private static Map<Integer, Long> attributeCurrent(long constitution) {
        return attributeCurrent(constitution, 0, 0, 0);
    }

    private static Map<Integer, Long> cappedCurrent(long first) {
        Map<Integer, Long> current = new TreeMap<>();
        current.put(9001, first);
        for (int id = 9002; id <= 9005; id++) {
            current.put(id, 0L);
        }
        return current;
    }

    private static Map<Integer, Long> target(Object... pairs) {
        Map<Integer, Long> target = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            target.put((Integer) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return target;
    }

    // ------------------------------------------------------------------ 客户端坏数据

    @Test
    void 绕成超大正数的负数目标_按点数不足拒绝_增量不外泄() {
        for (int negative : new int[] {-1, -5, -100, Integer.MIN_VALUE}) {
            Validation v = validateAllocation(attributePool(), LEVEL, attributeCurrent(20),
                    target(CONSTITUTION, Integer.toUnsignedLong(negative)), 10);
            assertThat(v.error()).as("negative=%d", negative).isEqualTo(AllocError.NOT_ENOUGH_POINTS);
            assertThat(v.delta()).isZero();
        }
    }

    @Test
    void 两项增量在32位下会绕回_64位累加照样拒绝() {
        Validation v = validateAllocation(attributePool(), LEVEL, attributeCurrent(0),
                target(CONSTITUTION, UINT32_MAX, SPIRIT, 10), 10);
        assertThat(v).isEqualTo(new Validation(AllocError.NOT_ENOUGH_POINTS, 0));
    }

    @Test
    void 多个超大目标_剩余点给到最大也拒绝() {
        Validation v = validateAllocation(attributePool(), LEVEL, attributeCurrent(0),
                target(CONSTITUTION, UINT32_MAX, SPIRIT, UINT32_MAX, STRENGTH, UINT32_MAX, AGILITY, UINT32_MAX), UINT32_MAX);
        assertThat(v.error()).isEqualTo(AllocError.NOT_ENOUGH_POINTS);
    }

    @Test
    void 单项上限逐维生效_超大值先撞上限_恰好到上限放行() {
        assertThat(validateAllocation(cappedPool(), LEVEL, cappedCurrent(48), target(9001, 51), 100).error())
                .isEqualTo(AllocError.CAP_EXCEEDED);
        assertThat(validateAllocation(cappedPool(), LEVEL, cappedCurrent(48), target(9001, UINT32_MAX), 100).error())
                .isEqualTo(AllocError.CAP_EXCEEDED);
        assertThat(validateAllocation(cappedPool(), LEVEL, cappedCurrent(48), target(9001, 50), 100))
                .isEqualTo(new Validation(AllocError.OK, 2));
    }

    @Test
    void 不属于本池的维度一律拒绝() {
        for (int dimension : new int[] {9001, 999, 0}) {
            assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(0), target(dimension, 1), 10))
                    .isEqualTo(new Validation(AllocError.DIMENSION_NOT_IN_POOL, 0));
        }
    }

    // ------------------------------------------------------------------ 业务规则

    @Test
    void 只增不减_挪点等于免费洗点同样拒绝() {
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20), target(CONSTITUTION, 19), 10).error())
                .isEqualTo(AllocError.CANNOT_DECREASE);
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20, 5, 0, 0),
                target(CONSTITUTION, 25, SPIRIT, 0), 10).error()).isEqualTo(AllocError.CANNOT_DECREASE);
    }

    @Test
    void 剩余点边界_多维增量合计后比较() {
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20), target(CONSTITUTION, 30), 10))
                .isEqualTo(new Validation(AllocError.OK, 10));
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20), target(CONSTITUTION, 31), 10).error())
                .isEqualTo(AllocError.NOT_ENOUGH_POINTS);
        Map<Integer, Long> multi = target(CONSTITUTION, 23, SPIRIT, 8, AGILITY, 4);
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20, 5, 0, 0), multi, 10))
                .isEqualTo(new Validation(AllocError.OK, 10));
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20, 5, 0, 0), multi, 9).error())
                .isEqualTo(AllocError.NOT_ENOUGH_POINTS);
    }

    @Test
    void 无变化与空目标都不当成功() {
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20), target(CONSTITUTION, 20), 10).error())
                .isEqualTo(AllocError.NOTHING_TO_CHANGE);
        assertThat(validateAllocation(attributePool(), LEVEL, attributeCurrent(20), target(), 10).error())
                .isEqualTo(AllocError.NOTHING_TO_CHANGE);
    }

    @Test
    void 未解锁池拒绝() {
        Map<Integer, Long> current = new TreeMap<>(Map.of(9101, 0L, 9102, 0L, 9103, 0L, 9104, 0L));
        assertThat(validateAllocation(lockedPool(), 59, current, target(9101, 1), 10).error())
                .isEqualTo(AllocError.POOL_LOCKED);
        assertThat(validateAllocation(lockedPool(), 60, current, target(9101, 1), 10))
                .isEqualTo(new Validation(AllocError.OK, 1));
    }

    // ------------------------------------------------------------------ 总量换算

    @Test
    void 满级总量_属性点425() {
        assertThat(PlayerLevels.MAX_LEVEL).isEqualTo(85);
        assertThat(totalPoints(attributePool(), 85, 0)).isEqualTo(425);
        assertThat(totalPoints(cappedPool(), 85, 0)).isEqualTo(85);
        assertThat(totalPoints(lockedPool(), 85, 0)).isEqualTo(26);
        assertThat(totalPoints(attributePool(), 1, 0)).isEqualTo(5);
        assertThat(totalPoints(lockedPool(), 59, 0)).isZero();
        assertThat(totalPoints(lockedPool(), 60, 0)).isEqualTo(1);
    }

    @Test
    void 总量饱和到uint32上限不回绕_解锁等级0视为未解锁() {
        assertThat(totalPoints(attributePool(), 85, UINT32_MAX)).isEqualTo(UINT32_MAX);
        assertThat(totalPoints(new PoolRule(1, 1, UINT32_MAX, 0, 0), UINT32_MAX, 0)).isEqualTo(UINT32_MAX);
        assertThat(totalPoints(new PoolRule(1, 0, 5, 0, 0), 85, 0)).isZero();
    }

    // ------------------------------------------------------------------ 自动加点

    @Test
    void 自动加点按权重取整_余数按优先序补_建议能原样通过校验() {
        Map<Integer, Long> current = attributeCurrent(20, 5, 0, 0);
        Map<Integer, Long> suggested = new TreeMap<>(current);
        distributePoints(attributePool(), List.of(STRENGTH, AGILITY, CONSTITUTION), List.of(3L, 1L, 1L), 7, suggested);
        assertThat(suggested).containsEntry(STRENGTH, 5L).containsEntry(AGILITY, 1L)
                .containsEntry(CONSTITUTION, 21L).containsEntry(SPIRIT, 5L);
        assertThat(validateAllocation(attributePool(), LEVEL, current, suggested, 7))
                .isEqualTo(new Validation(AllocError.OK, 7));
    }

    @Test
    void 权重0不分也不补余数() {
        Map<Integer, Long> allocated = attributeCurrent(0);
        distributePoints(attributePool(), List.of(CONSTITUTION, SPIRIT, STRENGTH), List.of(0L, 1L, 1L), 3, allocated);
        assertThat(allocated).containsEntry(CONSTITUTION, 0L).containsEntry(SPIRIT, 2L).containsEntry(STRENGTH, 1L);
    }

    @Test
    void 权重缺省按1() {
        Map<Integer, Long> allocated = attributeCurrent(0);
        distributePoints(attributePool(), List.of(STRENGTH, CONSTITUTION, AGILITY, SPIRIT), List.of(3L, 1L, 1L), 5,
                allocated);
        assertThat(allocated).containsEntry(STRENGTH, 3L).containsEntry(CONSTITUTION, 1L)
                .containsEntry(AGILITY, 1L).containsEntry(SPIRIT, 0L);
    }

    @Test
    void 有上限池按优先序灌满_不超上限不超剩余() {
        Map<Integer, Long> allocated = cappedCurrent(49);
        distributePoints(cappedPool(), List.of(9001, 9002, 9003), List.of(), 3, allocated);
        assertThat(allocated).containsEntry(9001, 50L).containsEntry(9002, 2L).containsEntry(9003, 0L);
    }

    @Test
    void 权重全0或剩余0什么都不分() {
        Map<Integer, Long> allocated = attributeCurrent(1, 2, 3, 4);
        Map<Integer, Long> before = new TreeMap<>(allocated);
        distributePoints(attributePool(), List.of(CONSTITUTION, SPIRIT), List.of(0L, 0L), 10, allocated);
        assertThat(allocated).isEqualTo(before);
        distributePoints(attributePool(), List.of(CONSTITUTION), List.of(1L), 0, allocated);
        assertThat(allocated).isEqualTo(before);
    }

    @Test
    void 超大剩余与权重按无符号乘除不溢出() {
        Map<Integer, Long> allocated = new TreeMap<>();
        distributePoints(attributePool(), List.of(1, 2), List.of(UINT32_MAX, UINT32_MAX), UINT32_MAX, allocated);
        assertThat(allocated.get(1) + allocated.get(2)).isEqualTo(UINT32_MAX);
        assertThat(allocated.get(1)).isEqualTo(UINT32_MAX / 2 + 1);
    }

    // ------------------------------------------------------------------ 当前气血 / 法力

    @Test
    void 改属性不能当治疗_往返不净得_极低血量回升有上界() {
        long back = rescaleCurrent(rescaleCurrent(200, 3950, 1100), 1100, 3950);
        assertThat(back).isBetween(1L, 200L);
        long lowDown = rescaleCurrent(1, 3950, 1100);
        assertThat(lowDown).isEqualTo(1);
        long lowBack = rescaleCurrent(lowDown, 1100, 3950);
        assertThat(lowBack).isEqualTo(3);
        assertThat(rescaleCurrent(rescaleCurrent(lowBack, 3950, 1100), 1100, 3950)).isEqualTo(lowBack);
    }

    @Test
    void 活人至少留1_死人保持0_新上限0归零_旧上限未知只夹不补() {
        assertThat(rescaleCurrent(1, 1000, 10)).isEqualTo(1);
        assertThat(rescaleCurrent(0, 1000, 5000)).isZero();
        assertThat(rescaleCurrent(500, 1000, 0)).isZero();
        assertThat(rescaleCurrent(800, 0, 500)).isEqualTo(500);
        assertThat(rescaleCurrent(300, 1000, 1000)).isEqualTo(300);
    }

    // ------------------------------------------------------------------ 加点收益公式

    private static final double STD_PHYSICAL = 4250.0;
    private static final double STD_MAGIC = 3400.0;
    private static final double STD_MANA = 4200.0;
    private static final double STD_SPEED = 3300.0;
    private static final double STD_HEALTH = 4750.0;
    private static final double STD_DEFENSE = 5100.0;
    private static final long FULL_POINTS = 425;

    private static FormulaRule primaryRule() {
        return makeFormulaRule(0.20, FULL_POINTS);
    }

    @Test
    void 满投点数按池与等级上限现算() {
        assertThat(fullInvestmentPoints(attributePool(), 85)).isEqualTo(425);
        assertThat(fullInvestmentPoints(cappedPool(), 85)).isEqualTo(50);
        assertThat(fullInvestmentPoints(lockedPool(), 85)).isEqualTo(26);
    }

    @Test
    void 有效点数端点_满投正好等于除数() {
        assertThat(effectivePoints(0, primaryRule())).isZero();
        assertThat(effectivePoints(FULL_POINTS, primaryRule())).isEqualTo(510.0);
        assertThat(fullInvestmentEffectivePoints(primaryRule())).isEqualTo(510.0);
    }

    @Test
    void 集中投资比分散投多约百分之14点6() {
        double concentrated = effectivePoints(FULL_POINTS, primaryRule());
        double spread = 4.0 * effectivePoints(106, primaryRule());
        assertThat(spread).isCloseTo(445.15, within(0.01));
        assertThat(concentrated / spread).isCloseTo(1.146, within(0.001));
    }

    @Test
    void 满投拿满策划表定比例() {
        FormulaRule rule = primaryRule();
        assertThat(allocatedIncrement(STD_PHYSICAL, 0.25, FULL_POINTS, rule)).isCloseTo(1062.5, within(1e-6));
        assertThat(allocatedIncrement(STD_PHYSICAL, 0.30, FULL_POINTS, rule)).isCloseTo(1275.0, within(1e-6));
        assertThat(allocatedIncrement(STD_MAGIC, 0.225, FULL_POINTS, rule)).isCloseTo(765.0, within(1e-6));
        assertThat(allocatedIncrement(STD_MAGIC, 0.27, FULL_POINTS, rule)).isCloseTo(918.0, within(1e-6));
        assertThat(allocatedIncrement(STD_MANA, 0.15, FULL_POINTS, rule)).isCloseTo(630.0, within(1e-6));
        assertThat(allocatedIncrement(STD_SPEED, 0.1667, FULL_POINTS, rule)).isCloseTo(550.11, within(1e-6));
        assertThat(allocatedIncrement(STD_SPEED, 0.20, FULL_POINTS, rule)).isCloseTo(660.0, within(1e-6));
        assertThat(allocatedIncrement(STD_HEALTH, 0.2083, FULL_POINTS, rule)).isCloseTo(989.425, within(1e-6));
        assertThat(allocatedIncrement(STD_HEALTH, 0.25, FULL_POINTS, rule)).isCloseTo(1187.5, within(1e-6));
        assertThat(allocatedIncrement(STD_DEFENSE, 0.10, FULL_POINTS, rule)).isCloseTo(510.0, within(1e-6));
        assertThat(allocatedIncrement(STD_DEFENSE, 0.12, FULL_POINTS, rule)).isCloseTo(612.0, within(1e-6));
    }

    @Test
    void 投一半拿不到一半收益() {
        double half = allocatedIncrement(STD_PHYSICAL, 0.25, 212, primaryRule());
        double full = allocatedIncrement(STD_PHYSICAL, 0.25, FULL_POINTS, primaryRule());
        assertThat(half).isLessThan(full * 0.5);
        assertThat(half / full).isCloseTo(0.457, within(0.002));
    }

    @Test
    void 比例0或点数0或满投点数0都得0() {
        assertThat(allocatedIncrement(STD_PHYSICAL, 0.0, FULL_POINTS, primaryRule())).isZero();
        assertThat(allocatedIncrement(STD_PHYSICAL, 0.25, 0, primaryRule())).isZero();
        assertThat(allocatedIncrement(0.0, 0.25, FULL_POINTS, primaryRule())).isZero();
        assertThat(allocatedIncrement(STD_PHYSICAL, 0.25, 10, makeFormulaRule(0.20, 0))).isZero();
    }

    @Test
    void 效率系数取表_负数与非有限数退回缺省_0是纯线性() {
        assertThat(makeFormulaRule(-0.5, FULL_POINTS).efficiencyBonus()).isEqualTo(0.20);
        assertThat(makeFormulaRule(Double.NaN, FULL_POINTS).efficiencyBonus()).isEqualTo(0.20);
        assertThat(makeFormulaRule(Double.POSITIVE_INFINITY, FULL_POINTS).efficiencyBonus()).isEqualTo(0.20);
        FormulaRule linear = makeFormulaRule(0.0, FULL_POINTS);
        assertThat(effectivePoints(212, linear)).isEqualTo(212.0);
        assertThat(allocatedIncrement(STD_PHYSICAL, 0.25, FULL_POINTS, linear)).isCloseTo(1062.5, within(1e-6));
    }

    @Test
    void 改等级上限后满投仍正好拿满比例() {
        FormulaRule rule = makeFormulaRule(0.20, fullInvestmentPoints(attributePool(), 100));
        assertThat(rule.fullInvestmentPoints()).isEqualTo(500);
        assertThat(allocatedIncrement(5000.0, 0.25, 500, rule)).isCloseTo(1250.0, within(1e-6));
    }

    @Test
    void 有单项上限的池在自己的上限处拿满比例() {
        FormulaRule rule = makeFormulaRule(0.20, fullInvestmentPoints(cappedPool(), 85));
        assertThat(allocatedIncrement(STD_MAGIC, 0.25, 50, rule)).isCloseTo(850.0, within(1e-6));
    }

    @Test
    void 每分配1点_这一维涨的每一项二级属性都至少加1() {
        double[][] cases = {
                {STD_HEALTH, 0.2083}, {STD_HEALTH, 0.25}, {STD_DEFENSE, 0.10}, {STD_DEFENSE, 0.12},
                {STD_MAGIC, 0.225}, {STD_MAGIC, 0.27}, {STD_MANA, 0.15}, {STD_PHYSICAL, 0.25}, {STD_PHYSICAL, 0.30},
                {STD_SPEED, 0.1667}, {STD_SPEED, 0.20},
        };
        for (double[] c : cases) {
            double previous = 0.0;
            for (long n = 1; n <= FULL_POINTS; n++) {
                double current = Math.floor(allocatedIncrement(c[0], c[1], n, primaryRule()));
                assertThat(current).as("标准 %s 比例 %s 第 %d 点", c[0], c[1], n).isGreaterThanOrEqualTo(previous + 1.0);
                previous = current;
            }
        }
    }
}
