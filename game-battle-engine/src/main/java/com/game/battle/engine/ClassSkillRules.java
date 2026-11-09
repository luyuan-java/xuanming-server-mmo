package com.game.battle.engine;

import com.game.common.math.Unsigned;
import java.math.BigInteger;

/**
 * 职业技能的共享纯规则：生效重数、倍率、比例整数运算与修习计划。
 *
 * <p>行为来源为固定提交 {@code 993b6da9} 的 {@code class_skill_rules.h}。不读表、不改玩家状态、
 * 不消费随机数。协议的 int/long 一律是 uint32/uint64 位模式，返回值也遵守该约定；
 * 调用方只夹取生效值，不得用它覆盖需要跨版本保留的存储重数。
 */
public final class ClassSkillRules {
    private static final long RATIO_SCALE = 1_000_000;
    private static final BigInteger SCALE = BigInteger.valueOf(RATIO_SCALE);
    private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);
    private static final long MAX_PPM = Long.divideUnsigned(-1L, RATIO_SCALE);

    private ClassSkillRules() { }

    /** 配表 passive_stat 的封闭语义；未知名字由表校验处理，本规则返回 NONE。 */
    public enum PassiveStat {
        NONE, DEFENSE, PHYSICAL_ATTACK, MAGIC_ATTACK, MAX_HEALTH, MAX_MANA, SPEED, CRIT, OUTGOING_HEAL
    }

    /** 名字精确匹配，不修剪、不忽略大小写；空值按未配置处理。 */
    public static PassiveStat parsePassiveStat(String name) {
        if (name == null) return PassiveStat.NONE;
        return switch (name) {
            case "defense" -> PassiveStat.DEFENSE;
            case "physicalAttack" -> PassiveStat.PHYSICAL_ATTACK;
            case "magicAttack" -> PassiveStat.MAGIC_ATTACK;
            case "maxHp" -> PassiveStat.MAX_HEALTH;
            case "maxMp" -> PassiveStat.MAX_MANA;
            case "speed" -> PassiveStat.SPEED;
            case "crit" -> PassiveStat.CRIT;
            case "outgoingHeal" -> PassiveStat.OUTGOING_HEAL;
            default -> PassiveStat.NONE;
        };
    }

    /** 到解锁等级开放一重，此后每两级加一重，最多 max 重；max=0 表示未配置。 */
    public static int rankLimit(int level, int unlock, int max) {
        long playerLevel = Integer.toUnsignedLong(level);
        long unlockLevel = Integer.toUnsignedLong(unlock);
        if (max == 0 || playerLevel < unlockLevel) return 0;
        return (int) Math.min(Integer.toUnsignedLong(max), 1 + (playerLevel - unlockLevel) / 2);
    }

    /** 未学仍为零；生效重数同时受存储值、当前角色等级和当前表上限约束。 */
    public static int effectiveRank(int stored, int level, int unlock, int max) {
        int limit = rankLimit(level, unlock, max);
        return Integer.compareUnsigned(stored, limit) < 0 ? stored : limit;
    }

    /** 主动伤害与治疗每多一重加 2%；零重数的算式按一重，施放资格由调用方先检查。 */
    public static double damageRankFactor(int rank) {
        return rankFactor(rank, 0.02);
    }

    /** 主动辅助的属性幅度与回蓝每多一重加 2%。 */
    public static double supportRankFactor(int rank) {
        return rankFactor(rank, 0.02);
    }

    /** 被动每多一重加 5%。 */
    public static double passiveRankFactor(int rank) {
        return rankFactor(rank, 0.05);
    }

    private static double rankFactor(int rank, double growth) {
        long extraRanks = rank == 0 ? 0 : Integer.toUnsignedLong(rank) - 1;
        return 1.0 + growth * extraRanks;
    }

    /** 比例先四舍五入到百万分比，再做整数乘除并向上取整；结果在 uint64 上限饱和。 */
    public static long ceilRatio(long value, double ratio) {
        return multiplyRatio(value, ratio, true);
    }

    /** 与 ceilRatio 共用比例量化，结果向下取整；非正、NaN、无穷比例均为零。 */
    public static long floorRatio(long value, double ratio) {
        return multiplyRatio(value, ratio, false);
    }

    private static long multiplyRatio(long value, double ratio, boolean roundUp) {
        if (!(ratio > 0.0) || !Double.isFinite(ratio) || value == 0) return 0;
        // 正数 Math.round 与源 std::round 的半数进位一致；极大有限比例乘百万变成无穷时也按上限夹取。
        long ppm = Unsigned.minUnsigned(Math.round(ratio * RATIO_SCALE), MAX_PPM);
        if (ppm == 0) return 0;
        BigInteger unsignedValue = value >= 0 ? BigInteger.valueOf(value)
                : BigInteger.valueOf(value & Long.MAX_VALUE).setBit(Long.SIZE - 1);
        BigInteger numerator = unsignedValue.multiply(BigInteger.valueOf(ppm));
        if (roundUp) numerator = numerator.add(SCALE.subtract(BigInteger.ONE));
        return numerator.divide(SCALE).min(UINT64_MAX).longValue();
    }

    /**
     * 正耗蓝比例至少收一点，即使最大法力为零或比例量化为零。
     * 源规则中 +∞ 也先通过正值检查、随后比例运算得零，因此耗蓝为 1；NaN/非正值为零。
     */
    public static long manaCost(long maxMana, double ratio) {
        if (!(ratio > 0.0)) return 0;
        long cost = ceilRatio(maxMana, ratio);
        return cost == 0 ? 1 : cost;
    }

    /**
     * 修至 nextRank 的价格：100×重数² + 10×解锁等级。
     * 正式表最高十重；超出内容域的输入仍按源 uint64 算术回绕，不把成本擅自改成饱和值。
     */
    public static long upgradeCost(int nextRank, int unlockLevel) {
        long rank = Integer.toUnsignedLong(nextRank);
        return 100L * rank * rank + 10L * Integer.toUnsignedLong(unlockLevel);
    }

    /** 一次客户端修习只允许一重或十重。 */
    public static boolean isValidUpgradeCount(int count) {
        return count == 1 || count == 10;
    }

    /** NONE 表示按请求次数完成；其余是部分成功或没有成功时的停止原因。 */
    public enum UpgradeStop { NONE, MAX_RANK, LEVEL_LIMIT, GOLD }

    /** 只描述计划，不扣余额；finalRank 保留超出本版本表上限的原存储位模式。 */
    public record UpgradePlan(int steps, int finalRank, long totalCost, UpgradeStop stop) { }

    /**
     * 逐重计算，停止优先级为满重、等级、余额。余额按扣完前几重后的剩余值判断。
     *
     * <p>调用方应先用 isValidUpgradeCount 映射客户端错误码；此纯 API 对非法次数也拒绝，
     * 避免把未校验的 uint32 请求变成无界循环。其余参数均按无符号位模式处理。
     */
    public static UpgradePlan planUpgrade(int currentRank, int maxRank, int rankLimit, int unlockLevel,
                                          long balance, int count) {
        if (!isValidUpgradeCount(count)) throw new IllegalArgumentException("职业技能修习次数只允许 1 或 10");
        int rank = currentRank;
        long spent = 0;
        for (int steps = 0; steps < count; steps++) {
            if (Integer.compareUnsigned(rank, maxRank) >= 0) {
                return new UpgradePlan(steps, rank, spent, UpgradeStop.MAX_RANK);
            }
            if (Integer.compareUnsigned(rank, rankLimit) >= 0) {
                return new UpgradePlan(steps, rank, spent, UpgradeStop.LEVEL_LIMIT);
            }
            long cost = upgradeCost(rank + 1, unlockLevel);
            if (Long.compareUnsigned(cost, balance - spent) > 0) {
                return new UpgradePlan(steps, rank, spent, UpgradeStop.GOLD);
            }
            spent += cost;
            rank++;
        }
        return new UpgradePlan(count, rank, spent, UpgradeStop.NONE);
    }
}
