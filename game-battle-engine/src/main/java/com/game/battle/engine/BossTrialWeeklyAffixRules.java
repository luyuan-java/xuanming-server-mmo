package com.game.battle.engine;

/**
 * 首领试炼「周常五行词缀」V1 纯规则（逐项对齐 C++ {@code boss_trial_weekly_affix_rules.h}）。
 * 零时钟、零外部依赖、全确定性；仅在真实首领试炼生效，与既有 10/15 份装备材料奖励池完全共享，零新增肝度与付费点。
 */
public final class BossTrialWeeklyAffixRules {

    public static final int AFFIX_NONE = 0;
    /** 金·锋芒破阵周：破绽暴击+15%/主目标+10% | 敌方开局8%金甲护盾。 */
    public static final int AFFIX_METAL = 1;
    /** 木·灵脉涤尘周：驱散/净化回12%法力+全队增伤10% | 首领R2/5/8末回4%血。 */
    public static final int AFFIX_WOOD = 2;
    /** 水·蓄势聚灵周：蓄力/毒火增伤20%且耗蓝-20% | 首领偶数回合末+1层蓄势。 */
    public static final int AFFIX_WATER = 3;
    /** 火·烈焰连携周：灵宠直攻/追击+18%+破绽灼伤3%(封顶1200) | 首领<=60%提前二阶段。 */
    public static final int AFFIX_FIRE = 4;
    /** 土·守御反震周：化劲破绽赐全队8%护盾+下回合全伤+12% | 首领慢重劈基础+12%。 */
    public static final int AFFIX_EARTH = 5;
    public static final int MAX_AFFIX_ID = 5;

    public static final long DAY_MS = 86_400_000L;
    public static final long WEEK_MS = 7L * DAY_MS;
    /** 1970-01-05 05:00:00 UTC+8（= 1970-01-04 21:00:00 UTC），与全服周历基准逐毫秒同源。 */
    public static final long FIRST_MONDAY_05_UTC8_MS = 3L * DAY_MS + 21L * 3_600_000L;

    public static final int METAL_EXPOSED_CRIT_BONUS_PCT = 15;
    public static final int METAL_EXPOSED_PRIMARY_BONUS_PCT = 10;
    public static final long METAL_ENEMY_OPENING_SHIELD_PCT = 8L;

    public static final long WOOD_DISPEL_MANA_REGEN_PCT = 12L;
    public static final int WOOD_TEAM_DAMAGE_BONUS_PCT = 10;
    public static final long WOOD_BOSS_REGEN_HP_PCT = 4L;

    public static final int WATER_CHARGE_OR_PERIODIC_DAMAGE_BONUS_PCT = 20;
    public static final long WATER_MANA_KEEP_PCT = 80L;

    public static final int FIRE_PET_OR_FOLLOWUP_DAMAGE_BONUS_PCT = 18;
    public static final long FIRE_EXPOSED_BURN_MAX_HP_PCT = 3L;
    public static final long FIRE_EXPOSED_BURN_CAP = 1200L;
    public static final long FIRE_BOSS_PHASE_TWO_HP_PCT = 60L;

    public static final long EARTH_PARRY_TEAM_SHIELD_PCT = 8L;
    public static final int EARTH_PARRY_NEXT_ROUND_DAMAGE_BONUS_PCT = 12;
    public static final double EARTH_BOSS_CLEAVE_MULTIPLIER = 1.12;

    private BossTrialWeeklyAffixRules() {
    }

    public static boolean isValidOverride(int affixId) {
        return Integer.compareUnsigned(affixId, MAX_AFFIX_ID) <= 0;
    }

    public static int affixIdForTimestamp(long nowMs) {
        if (Long.compareUnsigned(nowMs, FIRST_MONDAY_05_UTC8_MS) < 0) {
            return AFFIX_NONE;
        }
        long weekIndex = Long.divideUnsigned(nowMs - FIRST_MONDAY_05_UTC8_MS, WEEK_MS);
        return (int) (Long.remainderUnsigned(weekIndex, MAX_AFFIX_ID) + 1L);
    }

    public static int resolveForBattle(boolean isBossTrial, boolean hasTowerContext,
                                       long createdAtMs, int explicitAffixId) {
        if (!isBossTrial || hasTowerContext) {
            return AFFIX_NONE;
        }
        if (explicitAffixId >= 1 && explicitAffixId <= MAX_AFFIX_ID) {
            return explicitAffixId;
        }
        return affixIdForTimestamp(createdAtMs);
    }

    public static boolean isWoodRegenRound(int roundIndex) {
        return Integer.compareUnsigned(roundIndex, 2) >= 0
                && Integer.remainderUnsigned(roundIndex - 2, 3) == 0;
    }

    public static boolean isWaterMomentumRound(int roundIndex) {
        return Integer.compareUnsigned(roundIndex, 2) >= 0
                && Integer.remainderUnsigned(roundIndex, 2) == 0;
    }

    public static long discountWaterManaCost(long originalCost) {
        if (originalCost == 0L) {
            return 0L;
        }
        long discounted = Long.divideUnsigned(originalCost * WATER_MANA_KEEP_PCT, 100L);
        return discounted != 0L ? discounted : 1L;
    }

    public static boolean isHealthAtOrBelowPct(long health, long maxHealth, long pct) {
        if (maxHealth == 0L) {
            return false;
        }
        long threshold = Long.divideUnsigned(maxHealth, 100L) * pct
                + Long.divideUnsigned(Long.remainderUnsigned(maxHealth, 100L) * pct, 100L);
        return Long.compareUnsigned(health, threshold) <= 0;
    }
}
