package com.game.common.combat;

import com.game.common.math.Unsigned;

/**
 * 伤害公式（基线 {@code cpp/libs/services/battle/system/combat_damage_rules.h}，实时技能与回合制引擎共用）。纯函数，没有表 / 状态依赖。
 *
 * <ul>
 *   <li>原始伤害 = 基础 × (1 + 力量 × 0.1) + 攻击 × 攻击倍率（倍率 0 当 1，负数或非有限当 0）；基础 ≤ 0 或非有限不出伤害；</li>
 *   <li>承受比例 = max(0.4, 等级系数 / (护甲 + 防御 + 等级系数) × (1 − 抗性%))，等级系数 = 360 + 120 × clamp(等级, 1, 85)，
 *       即最多减伤 60%；</li>
 *   <li>扣血 = ceil(伤害)，封顶到当前气血；非有限或 ≤ 0 不扣。</li>
 * </ul>
 * 暴击（×2）、PvP 缩放（×0.3）、防御姿态（×0.5）不在这里，由各自的结算方做（同基线）。
 *
 * <p>uint64 / uint32 入参用 Java 的 long / int 承载，一律按无符号解释（换 double 时按无符号换，见 {@link Unsigned}），运算顺序与基线逐项一致，
 * Java 的 IEEE double 与基线的 SSE2 结果逐位相同。
 */
public final class CombatDamageRules {

    /** 伤害类型：魔法（表缺省 0）。 */
    public static final int MAGIC_DAMAGE = 0;
    /** 伤害类型：物理。 */
    public static final int PHYSICAL_DAMAGE = 1;
    /** 最多减伤 60%。 */
    public static final double MAX_PASSIVE_REDUCTION = 0.60;
    /** 防御单位缩放（Class.init_armor、Monster.armor 已按这个单位填）。 */
    public static final double DEFENSE_UNIT_SCALE = 12.0;
    public static final double LEVEL_FACTOR_BASE = 30.0 * DEFENSE_UNIT_SCALE;
    public static final double LEVEL_FACTOR_PER_LEVEL = 10.0 * DEFENSE_UNIT_SCALE;
    /** 等级系数封顶的等级（与玩家等级上限一致）。 */
    public static final int LEVEL_FACTOR_MAX_LEVEL = 85;

    private static final double MIN_RECEIVED_RATIO = 1.0 - MAX_PASSIVE_REDUCTION;

    private CombatDamageRules() {
    }

    /** 按伤害类型取攻击：物理取物攻，魔法取法攻，其余 0（uint64 原样返回）。 */
    public static long selectAttack(int damageType, long physicalAttack, long magicAttack) {
        if (damageType == PHYSICAL_DAMAGE) {
            return physicalAttack;
        }
        return damageType == MAGIC_DAMAGE ? magicAttack : 0;
    }

    /** 攻击倍率：非有限或负数 → 0；0（含 -0.0）→ 1；其余原样。 */
    public static double attackMultiplier(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < 0.0) {
            return 0.0;
        }
        return multiplier == 0.0 ? 1.0 : multiplier;
    }

    /** 等级系数：360 + 120 × clamp(等级, 1, 85)（等级按 uint32）。 */
    public static double levelFactor(int targetLevel) {
        long level = Integer.toUnsignedLong(targetLevel);
        long clamped = level < 1 ? 1 : Math.min(level, LEVEL_FACTOR_MAX_LEVEL);
        return LEVEL_FACTOR_BASE + LEVEL_FACTOR_PER_LEVEL * (double) clamped;
    }

    /** 承受比例（护甲、防御、抗性百分数都按 uint64；护甲与防御先各自换 double 再相加，不会溢出）。 */
    public static double receivedRatio(long targetArmor, long targetDefense, long resistancePercent, int targetLevel) {
        double factor = levelFactor(targetLevel);
        double combined = Unsigned.toDouble(targetArmor) + Unsigned.toDouble(targetDefense);
        double resistance = clamp(Unsigned.toDouble(resistancePercent) / 100.0, 0.0, 1.0);
        double ratio = factor / (combined + factor) * (1.0 - resistance);
        return MIN_RECEIVED_RATIO < ratio ? ratio : MIN_RECEIVED_RATIO;
    }

    /** 暴击之前的伤害（力量、攻击、护甲、防御、抗性按 uint64）。 */
    public static double damageBeforeCritical(double baseDamage, long strength, long attack, double multiplier,
                                              long targetArmor, long targetDefense, long resistancePercent,
                                              int targetLevel) {
        if (!Double.isFinite(baseDamage) || baseDamage <= 0.0) {
            return 0.0;
        }
        double raw = baseDamage * (1.0 + Unsigned.toDouble(strength) * 0.1)
                + Unsigned.toDouble(attack) * attackMultiplier(multiplier);
        if (!Double.isFinite(raw) || raw <= 0.0) {
            return 0.0;
        }
        return raw * receivedRatio(targetArmor, targetDefense, resistancePercent, targetLevel);
    }

    /** 实际扣的气血（uint64）：向上取整、封顶到当前气血；非有限、≤ 0 或当前气血为 0 → 0。 */
    public static long damageToHealth(double rawDamage, long currentHealth) {
        if (!Double.isFinite(rawDamage) || rawDamage <= 0.0 || currentHealth == 0) {
            return 0;
        }
        double rounded = Math.ceil(rawDamage);
        if (rounded >= Unsigned.toDouble(currentHealth)) {
            return currentHealth;
        }
        return Unsigned.fromDouble(rounded);
    }

    private static double clamp(double value, double low, double high) {
        if (value < low) {
            return low;
        }
        return value > high ? high : value;
    }
}
