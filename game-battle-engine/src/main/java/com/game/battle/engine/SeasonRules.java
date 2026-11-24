package com.game.battle.engine;

/**
 * 赛季技能与单槽赛季印（方案 A）纯规则（对齐 C++ {@code season_rules.h}）：
 * 不查表、不取墙钟、不打日志，由 {@code game-battle-engine} 与 {@code game-scene} 共用同一校验口径。
 */
public final class SeasonRules {

    /** 赛季印纯面板轻属性硬安全上限：5%（500 万分比）。 */
    public static final int HARD_STAT_CAP_BPS = 500;
    public static final int HARD_MAX_PROGRESS_POINTS = 1000;
    public static final long HARD_MAX_CATCHUP_PRICE_PER_POINT = 1_000_000_000L;
    /** 0 银两 / 1 灵石 / 2 绑定灵石。 */
    public static final int MAX_VALID_CURRENCY_TYPE = 2;

    public record RuleParams(int seasonId, long startsAtMs, long endsAtMs, boolean enabled,
                             int sealMaxPoints, int pveWinPoints, int pvpWinPoints, int pvpOtherPoints,
                             int maxHealthBpsCap, int defenseBpsCap, int catchupCurrencyType,
                             long catchupPricePerPoint, int defaultSealId) {
    }

    public record CatchupPlan(int pointsToBuy, long totalPrice) {
    }

    private SeasonRules() {
    }

    public static boolean isWindowActive(long startsAtMs, long endsAtMs, boolean enabled, long nowMs) {
        if (!enabled) {
            return false;
        }
        if (endsAtMs != 0 && Long.compareUnsigned(endsAtMs, startsAtMs) <= 0) {
            return false;
        }
        if (Long.compareUnsigned(nowMs, startsAtMs) < 0) {
            return false;
        }
        return endsAtMs == 0 || Long.compareUnsigned(nowMs, endsAtMs) < 0;
    }

    public static boolean windowsOverlap(long startA, long endA, long startB, long endB) {
        boolean aBeforeB = endA != 0 && Long.compareUnsigned(endA, startB) <= 0;
        boolean bBeforeA = endB != 0 && Long.compareUnsigned(endB, startA) <= 0;
        return !aBeforeB && !bBeforeA;
    }

    /** 校验一条赛季规则；合法返回 null，非法返回英文原因。 */
    public static String validateRule(RuleParams rule) {
        if (rule == null || rule.seasonId() == 0) {
            return "season_id must be non-zero";
        }
        if (rule.endsAtMs() != 0 && Long.compareUnsigned(rule.endsAtMs(), rule.startsAtMs()) <= 0) {
            return "ends_at_ms must be 0 or greater than starts_at_ms";
        }
        if (rule.sealMaxPoints() == 0 || Integer.compareUnsigned(rule.sealMaxPoints(), HARD_MAX_PROGRESS_POINTS) > 0) {
            return "seal_max_points out of range [1, 1000]";
        }
        if (Integer.compareUnsigned(rule.maxHealthBpsCap(), HARD_STAT_CAP_BPS) > 0
                || Integer.compareUnsigned(rule.defenseBpsCap(), HARD_STAT_CAP_BPS) > 0) {
            return "season stat cap exceeds hard limit 500 bps";
        }
        if (Integer.compareUnsigned(rule.catchupCurrencyType(), MAX_VALID_CURRENCY_TYPE) > 0) {
            return "catchup_currency_type invalid";
        }
        if (Long.compareUnsigned(rule.catchupPricePerPoint(), HARD_MAX_CATCHUP_PRICE_PER_POINT) > 0) {
            return "catchup_price_per_point exceeds limit";
        }
        if (rule.defaultSealId() == 0) {
            return "default_seal_id must be non-zero";
        }
        return null;
    }

    /** 校验单槽赛季印的机制定义（与法宝机制共用同一合法边界）。 */
    public static boolean validSealEffect(int seasonId, int sealId, int effectId,
                                          int bonusBps, int capBps, int perRoundLimit,
                                          int battleLimit, int activeRounds, int healthThresholdBps) {
        if (seasonId == 0 || sealId == 0) {
            return false;
        }
        return validArtifactDefinition(effectId, bonusBps, capBps, perRoundLimit,
                battleLimit, activeRounds, healthThresholdBps);
    }

    public static boolean validArtifactDefinition(int id, int bonusBps, int capBps, int perRoundLimit,
                                                  int battleLimit, int activeRounds, int healthThresholdBps) {
        return id >= 1 && id <= 8
                && bonusBps > 0 && Integer.compareUnsigned(bonusBps, 10000) <= 0
                && Integer.compareUnsigned(capBps, 10000) <= 0
                && Integer.compareUnsigned(healthThresholdBps, 10000) <= 0
                && Integer.compareUnsigned(perRoundLimit, 1) <= 0
                && Integer.compareUnsigned(battleLimit, 3) <= 0
                && Integer.compareUnsigned(activeRounds, 2) <= 0
                && (id == 6 || perRoundLimit > 0)
                && ((id != 3 && id < 6) || battleLimit > 0)
                && (id != 6 || (capBps > 0 && activeRounds > 0))
                && (id != 4 || healthThresholdBps != 0);
    }

    /** 进度点线性映射到轻属性万分比（向下取整，绝不超过 500 bps）。 */
    public static int scaledBonusBps(int progressPoints, int maxPoints, int capBps) {
        if (maxPoints == 0 || capBps == 0 || progressPoints == 0) {
            return 0;
        }
        long cappedCap = Math.min(Integer.toUnsignedLong(capBps), HARD_STAT_CAP_BPS);
        long max = Integer.toUnsignedLong(maxPoints);
        long clamped = Math.min(Integer.toUnsignedLong(progressPoints), max);
        return (int) ((cappedCap * clamped) / max);
    }

    /** 技能是否允许在当前赛季生效：season_id == 0 为常驻永久技能；否则必须严格匹配当前活跃赛季。 */
    public static boolean isSkillActiveInSeason(int skillSeasonId, int activeSeasonId) {
        return skillSeasonId == 0 || (activeSeasonId != 0 && skillSeasonId == activeSeasonId);
    }

    /** 赛季限定技能免费自动生效重数：随角色生效等级直达上限，不消耗银两修习。 */
    public static int effectiveSeasonalSkillRank(int actorLevel, int unlockLevel, int maxRank) {
        return ClassSkillRules.rankLimit(actorLevel, unlockLevel, maxRank);
    }

    /** 计算追赶点数与总价；requestedPoints == 0 表示一键补满剩余点数；不可补返回 null。 */
    public static CatchupPlan computeCatchupPlan(int currentPoints, int maxPoints,
                                                 int requestedPoints, long pricePerPoint) {
        long max = Integer.toUnsignedLong(maxPoints);
        long current = Integer.toUnsignedLong(currentPoints);
        if (max == 0 || current >= max || pricePerPoint == 0) {
            return null;
        }
        long remaining = max - current;
        long buy = requestedPoints == 0 ? remaining : Integer.toUnsignedLong(requestedPoints);
        if (buy == 0 || buy > remaining) {
            return null;
        }
        if (Long.compareUnsigned(pricePerPoint, Long.divideUnsigned(-1L, buy)) > 0) {
            return null;
        }
        return new CatchupPlan((int) buy, pricePerPoint * buy);
    }
}
