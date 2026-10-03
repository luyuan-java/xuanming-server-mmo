package com.game.scene.attribute;

import java.util.List;
import java.util.Map;

/**
 * 属性加点的纯规则（不碰表、不碰玩家状态，可单测），同 mmorpg {@code attribute_allocation_rules.h}。回答四个问题：
 * <ol>
 *   <li>某池在某等级一共有多少点；</li>
 *   <li>一份「目标已分配」是否合法（只增不减 / 单项上限 / 增量总和不超剩余）；</li>
 *   <li>自动加点怎么把剩余点分下去（有上限池按优先序灌满，无上限池按权重比例）；</li>
 *   <li>玩家分配的点换算成多少二级属性（百分比 + 集中投资公式）。</li>
 * </ol>
 * 点数类字段在协议与表里是 uint32，这里一律按无符号值放进 {@code long}（调用方用 {@link Integer#toUnsignedLong} 转），
 * 所以客户端发来的「负数」（绕成超大正数）照样按超大值参与校验，不会骗过剩余点判断。
 */
public final class AttributeRules {

    /** uint32 上限：总点数 / 已用点数饱和到这里，不回绕（同基线）。 */
    public static final long UINT32_MAX = 0xFFFF_FFFFL;

    /** 加点公式效率系数缺省值（表缺行或配了负数 / 非有限数时用）。 */
    public static final double DEFAULT_EFFICIENCY_BONUS = 0.20;

    private AttributeRules() {
    }

    /**
     * 一个点数池（表 AttributePool 的规则部分）。
     *
     * @param dimensionCap 单维度可分配上限，0 = 不限
     */
    public record PoolRule(int poolId, long unlockLevel, long pointsPerLevel, long basePoints, long dimensionCap) {
    }

    /** 已解锁：等级到了解锁等级；表配 unlock_level=0 视为永不解锁。 */
    public static boolean isUnlocked(PoolRule rule, long level) {
        return level >= rule.unlockLevel() && rule.unlockLevel() > 0;
    }

    /** 总点数 = 解锁时一次性给点 + 每级点数 × (等级 − 解锁等级 + 1) + 额外点；未解锁为 0；超 uint32 饱和。 */
    public static long totalPoints(PoolRule rule, long level, long bonusPoints) {
        if (!isUnlocked(rule, level)) {
            return 0;
        }
        long levels = level - rule.unlockLevel() + 1;
        long perLevel = rule.pointsPerLevel();
        if (perLevel != 0 && levels > UINT32_MAX / perLevel) {
            return UINT32_MAX;
        }
        // 三项各不超过 uint32，和不会溢出 long
        return Math.min(rule.basePoints() + perLevel * levels + bonusPoints, UINT32_MAX);
    }

    /**
     * 当前值（气血 / 法力）随上限变化时按比例保持，活着的至少留 1（加载 / 加点 / 切方案 / 洗点共用；升级另有规则）。
     * 一升一降往返不净得：改属性不能当治疗。唯一例外是极低血量时「至少留 1」让往返最多回升到 floor(旧上限 / 新上限)，有上界、不累积。
     */
    public static long rescaleCurrent(long current, long oldMax, long newMax) {
        if (current == 0 || newMax == 0) {
            return 0;
        }
        if (oldMax == 0 || oldMax == newMax) {
            return Math.min(current, newMax);
        }
        long scaled = (long) ((double) current * (double) newMax / (double) oldMax);
        if (scaled < 1) {
            return 1;
        }
        return Math.min(scaled, newMax);
    }

    /** 校验结论；拒绝时 {@code delta} 为 0。 */
    public enum AllocError {
        OK, POOL_LOCKED, DIMENSION_NOT_IN_POOL, CANNOT_DECREASE, CAP_EXCEEDED, NOT_ENOUGH_POINTS, NOTHING_TO_CHANGE
    }

    /** @param delta 通过时的增量总和（拒绝时 0） */
    public record Validation(AllocError error, long delta) {

        static Validation rejected(AllocError error) {
            return new Validation(error, 0);
        }
    }

    /**
     * 校验「目标已分配」。按 {@code target} 的迭代顺序逐项判，第一处违规即返回（基线按维度号升序，调用方负责排序）。
     *
     * @param current   当前方案里本池各维度的已分配点（池内全部维度都要有键，值可为 0）
     * @param target    目标已分配（只含要改的维度，缺省维度视为不变）
     * @param remaining 本池剩余点
     */
    public static Validation validateAllocation(PoolRule rule, long level, Map<Integer, Long> current,
                                                Map<Integer, Long> target, long remaining) {
        if (!isUnlocked(rule, level)) {
            return Validation.rejected(AllocError.POOL_LOCKED);
        }
        long delta = 0;
        for (Map.Entry<Integer, Long> entry : target.entrySet()) {
            Long have = current.get(entry.getKey());
            if (have == null) {
                return Validation.rejected(AllocError.DIMENSION_NOT_IN_POOL);
            }
            long want = entry.getValue();
            if (want < have) {
                return Validation.rejected(AllocError.CANNOT_DECREASE);
            }
            if (rule.dimensionCap() > 0 && want > rule.dimensionCap()) {
                return Validation.rejected(AllocError.CAP_EXCEEDED);
            }
            // 单项不超过 uint32，请求里的维度数受字段规模校验（≤ 20）约束，long 累加不会溢出
            delta += want - have;
        }
        if (delta == 0) {
            return Validation.rejected(AllocError.NOTHING_TO_CHANGE);
        }
        if (delta > remaining) {
            return Validation.rejected(AllocError.NOT_ENOUGH_POINTS);
        }
        return new Validation(AllocError.OK, delta);
    }

    /**
     * 自动分配：把 {@code remaining} 点分到 {@code allocated}（就地修改，含已分配）。
     * 有上限池按 {@code order} 逐个灌到上限（不看权重）；无上限池按权重比例取整，余数按优先序逐点补给权重 &gt; 0 的维度
     * （只补一轮）。权重缺省按 1；权重全 0 时什么都不分。结果确定。
     *
     * @param order   维度优先序（表 AttributeAutoPlan.dimension）
     * @param weights 对应权重（表 AttributeAutoPlan.weight，可比 order 短）
     */
    public static void distributePoints(PoolRule rule, List<Integer> order, List<Long> weights, long remaining,
                                        Map<Integer, Long> allocated) {
        if (remaining == 0 || order.isEmpty()) {
            return;
        }
        if (rule.dimensionCap() > 0) {
            for (int dimensionId : order) {
                if (remaining == 0) {
                    break;
                }
                long current = allocated.getOrDefault(dimensionId, 0L);
                if (current >= rule.dimensionCap()) {
                    continue;
                }
                long give = Math.min(rule.dimensionCap() - current, remaining);
                allocated.put(dimensionId, current + give);
                remaining -= give;
            }
            return;
        }
        long[] effective = new long[order.size()];
        long weightSum = 0;
        for (int i = 0; i < order.size(); i++) {
            effective[i] = i < weights.size() ? weights.get(i) : 1;
            weightSum += effective[i];
        }
        if (weightSum == 0) {
            return;
        }
        long distributed = 0;
        for (int i = 0; i < order.size(); i++) {
            // 剩余点与权重都不超过 uint32，乘积不超过 uint64：按无符号乘除，与基线 uint64 运算逐值一致
            long give = Long.divideUnsigned(remaining * effective[i], weightSum);
            allocated.merge(order.get(i), give, Long::sum);
            distributed += give;
        }
        long left = remaining - distributed;
        for (int i = 0; left > 0 && i < order.size(); i++) {
            if (effective[i] == 0) {
                continue;
            }
            allocated.merge(order.get(i), 1L, Long::sum);
            left--;
        }
    }

    // ------------------------------------------------------------------ 加点收益公式

    /**
     * 加点收益公式的常量：有效点数 E(n) = n × (1 + bonus × n ÷ scale)，增量 = 标准基础属性 × 比例 × E(n) ÷ E(scale)。
     *
     * @param efficiencyBonus      集中投资系数 bonus（表 AttributeRule.alloc_efficiency_bonus）
     * @param fullInvestmentPoints scale：该维度满级时最多能分到的点数（不进表，按池表与等级上限现算）
     */
    public record FormulaRule(double efficiencyBonus, long fullInvestmentPoints) {
    }

    /** 单个维度在 {@code maxLevel} 时最多能分到多少点：不限单项 → 满级总点数；限单项 → min(单项上限, 满级总点数)。 */
    public static long fullInvestmentPoints(PoolRule rule, long maxLevel) {
        long total = totalPoints(rule, maxLevel, 0);
        return rule.dimensionCap() > 0 && rule.dimensionCap() < total ? rule.dimensionCap() : total;
    }

    /** 表值 0 合法（纯线性）；负数 / 非有限数是坏表，退回 {@link #DEFAULT_EFFICIENCY_BONUS}。 */
    public static FormulaRule makeFormulaRule(double tableBonus, long fullInvestmentPoints) {
        double bonus = Double.isFinite(tableBonus) && tableBonus >= 0.0 ? tableBonus : DEFAULT_EFFICIENCY_BONUS;
        return new FormulaRule(bonus, fullInvestmentPoints);
    }

    public static double effectivePoints(long allocated, FormulaRule rule) {
        double n = (double) allocated;
        if (rule.fullInvestmentPoints() == 0) {
            return n;
        }
        return n * (1.0 + rule.efficiencyBonus() * n / (double) rule.fullInvestmentPoints());
    }

    /** 满投的有效点数 E(scale) = scale × (1 + bonus)，即公式的除数。 */
    public static double fullInvestmentEffectivePoints(FormulaRule rule) {
        return (double) rule.fullInvestmentPoints() * (1.0 + rule.efficiencyBonus());
    }

    /**
     * 一个维度的已分配点对一项二级属性的增量（未取整，由调用方累加后统一向下取整）。
     * 比例 / 点数 / 标准基础 / 除数任一不为正直接得 0，不做除法。求值顺序与基线一致，逐值相同。
     *
     * @param standardBase 85 级标准基础属性（职业初值 + 满级自然成长，不含加点与装备）
     * @param ratio        满投比例（表 AttributeAllocRatio）
     */
    public static double allocatedIncrement(double standardBase, double ratio, long allocated, FormulaRule rule) {
        double divisor = fullInvestmentEffectivePoints(rule);
        if (ratio <= 0.0 || allocated == 0 || standardBase <= 0.0 || divisor <= 0.0) {
            return 0.0;
        }
        return standardBase * ratio * effectivePoints(allocated, rule) / divisor;
    }
}
