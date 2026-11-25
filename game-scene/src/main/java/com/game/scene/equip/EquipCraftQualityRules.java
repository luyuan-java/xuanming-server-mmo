package com.game.scene.equip;

/**
 * 装备 5 档词条品相、只升不降第 8 次硬保底打磨、轻量化 3 阶手艺熟练度与主城原子代打纯规则
 * （逐项对齐 C++ {@code equip_craft_quality_rules.h}）。
 */
public final class EquipCraftQualityRules {

    public static final int POLISH_SAND_CONFIG_ID = 3403;
    public static final int POLISH_SAND_COUNT = 1;
    public static final long POLISH_GOLD_COST = 20L;

    public static final int REFORGE_SAND_CONFIG_ID = 3403;
    public static final int REFORGE_SAND_COUNT = 1;
    public static final long REFORGE_GOLD_COST = 20L;

    public static final int GUARANTEE_CHARM_CONFIG_ID = 3405;
    public static final int SCROLL_FRAGMENT_CONFIG_ID = 3406;
    public static final int RARE_SCROLL_PAGE_CONFIG_ID = 3407;
    public static final int RARE_SCROLL_INK_CONFIG_ID = 3408;

    public static final int MIN_QUALITY_TIER = 1;
    public static final int MAX_QUALITY_TIER = 5;
    public static final int POLISH_PITY_LIMIT = 8;
    /** G02 速度词条豁免品相随机，固定满值。 */
    public static final int SPEED_AFFIX_EFFECT = 4;
    public static final int QUALITY_WEIGHT_TOTAL_BPS = 10_000;

    public static final int MASTERY_APPRENTICE = 1;
    public static final int MASTERY_ARTISAN = 2;
    public static final int MASTERY_MASTER = 3;
    public static final int ARTISAN_EXP_THRESHOLD = 20;
    public static final int MASTER_EXP_THRESHOLD = 60;

    public static final long PROXY_FEE_MIN_GOLD = 50L;
    public static final long PROXY_FEE_MAX_GOLD = 100L;
    /** 5% 主城代打摊位银两税销毁。 */
    public static final long PROXY_TAX_BPS = 500L;

    public static final int SCROLL_BONUS_FRAGMENTS_COUNT = 2;
    /** 永久技能稀世占比硬红线 <= 10%。 */
    public static final int MAX_RARE_SKILL_RATIO_BPS = 1000;

    public record PolishResult(int newTier, int newPityCount, boolean guaranteedByPity) {
    }

    private EquipCraftQualityRules() {
    }

    public static boolean isPolishExempt(int effect) {
        return effect == SPEED_AFFIX_EFFECT;
    }

    public static int effectiveQualityTier(int storedTier, boolean exempt) {
        if (exempt || storedTier == 0) {
            // 旧存档 quality_tier==0 按满值 5 档兼容。
            return MAX_QUALITY_TIER;
        }
        return clampTier(storedTier);
    }

    public static int defaultQualityRatioPct(int tier) {
        return switch (tier) {
            case 1 -> 80;
            case 2 -> 85;
            case 3 -> 90;
            case 4 -> 95;
            default -> 100;
        };
    }

    public static String defaultQualityName(int tier) {
        return switch (tier) {
            case 1 -> "凡品";
            case 2 -> "良品";
            case 3 -> "上品";
            case 4 -> "珍品";
            default -> "极品";
        };
    }

    public static int scaleValueByQuality(int maxValueBps, int ratioPct) {
        int clamped = Math.clamp(ratioPct, 1, 100);
        return (int) ((Integer.toUnsignedLong(maxValueBps) * clamped) / 100L);
    }

    public static int rollQualityTier(int[] weightsBps, int rollBps) {
        long acc = 0L;
        long roll = Integer.toUnsignedLong(rollBps);
        for (int i = 0; i < MAX_QUALITY_TIER; i++) {
            acc += Integer.toUnsignedLong(weightsBps[i]);
            if (roll < acc) {
                return i + 1;
            }
        }
        return MAX_QUALITY_TIER;
    }

    /** 只升不降 + 累计第 8 次必达极品（或定星保底符直接必达极品）。 */
    public static PolishResult resolvePolish(int currentTier, int currentPity, int rolledTier, boolean useGuaranteeCharm) {
        int baseTier = clampTier(currentTier);
        int rolled = clampTier(rolledTier);
        if (useGuaranteeCharm || Integer.compareUnsigned(currentPity + 1, POLISH_PITY_LIMIT) >= 0) {
            return new PolishResult(MAX_QUALITY_TIER, 0, true);
        }
        int nextTier = Math.max(baseTier, rolled);
        if (nextTier >= MAX_QUALITY_TIER) {
            return new PolishResult(MAX_QUALITY_TIER, 0, false);
        }
        return new PolishResult(nextTier, currentPity + 1, false);
    }

    public static int masteryTierForExp(int exp) {
        if (Integer.compareUnsigned(exp, MASTER_EXP_THRESHOLD) >= 0) {
            return MASTERY_MASTER;
        }
        if (Integer.compareUnsigned(exp, ARTISAN_EXP_THRESHOLD) >= 0) {
            return MASTERY_ARTISAN;
        }
        return MASTERY_APPRENTICE;
    }

    public static int masteryNextExp(int exp) {
        if (Integer.compareUnsigned(exp, MASTER_EXP_THRESHOLD) >= 0) {
            return 0;
        }
        if (Integer.compareUnsigned(exp, ARTISAN_EXP_THRESHOLD) >= 0) {
            return MASTER_EXP_THRESHOLD;
        }
        return ARTISAN_EXP_THRESHOLD;
    }

    public static String masteryTierName(int tier) {
        return switch (tier) {
            case MASTERY_MASTER -> "宗师";
            case MASTERY_ARTISAN -> "巧匠";
            default -> "学徒";
        };
    }

    public static int forgeTopQualityRateBps(int tier) {
        return switch (tier) {
            case MASTERY_MASTER -> 1000;
            case MASTERY_ARTISAN -> 700;
            default -> 500;
        };
    }

    public static int scrollBonusFragmentRateBps(int tier) {
        return switch (tier) {
            case MASTERY_MASTER -> 1000;
            case MASTERY_ARTISAN -> 500;
            default -> 0;
        };
    }

    public static boolean validProxyFee(long grossFee) {
        return Long.compareUnsigned(grossFee, PROXY_FEE_MIN_GOLD) >= 0
                && Long.compareUnsigned(grossFee, PROXY_FEE_MAX_GOLD) <= 0;
    }

    public static long proxyTaxGold(long grossFee) {
        if (grossFee == 0L) {
            return 0L;
        }
        return Long.divideUnsigned(grossFee * PROXY_TAX_BPS + 9_999L, 10_000L);
    }

    public static long proxyNetGold(long grossFee) {
        long tax = proxyTaxGold(grossFee);
        return Long.compareUnsigned(grossFee, tax) > 0 ? grossFee - tax : 0L;
    }

    private static int clampTier(int tier) {
        if (Integer.compareUnsigned(tier, MIN_QUALITY_TIER) < 0) {
            return MIN_QUALITY_TIER;
        }
        if (Integer.compareUnsigned(tier, MAX_QUALITY_TIER) > 0) {
            return MAX_QUALITY_TIER;
        }
        return tier;
    }
}
