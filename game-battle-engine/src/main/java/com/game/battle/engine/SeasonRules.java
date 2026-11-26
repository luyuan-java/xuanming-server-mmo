package com.game.battle.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 赛季技能与单槽赛季印（方案 A / V4 十季无限循环与表驱动共鸣）纯规则（对齐 C++ {@code season_rules.h}）：
 * 不查表、不取墙钟、不打日志，由 {@code game-battle-engine} 与 {@code game-scene} 共用同一校验口径。
 */
public final class SeasonRules {

    /** 赛季印纯面板轻属性硬安全上限：5%（500 万分比）。 */
    public static final int HARD_STAT_CAP_BPS = 500;
    public static final int HARD_MAX_PROGRESS_POINTS = 1000;
    public static final long HARD_MAX_CATCHUP_PRICE_PER_POINT = 1_000_000_000L;
    /** 0 银两 / 1 灵石 / 2 绑定灵石 / 3 绑定银两。 */
    public static final int MAX_VALID_CURRENCY_TYPE = 3;

    /** 无限期号自动循环单届天数硬边界。 */
    public static final int MIN_CYCLE_DURATION_DAYS = 7;
    public static final int MAX_CYCLE_DURATION_DAYS = 180;
    public static final long MS_PER_DAY = 24L * 3600L * 1000L;

    public record RuleParams(int seasonId, long startsAtMs, long endsAtMs, boolean enabled,
                             int sealMaxPoints, int pveWinPoints, int pvpWinPoints, int pvpOtherPoints,
                             int maxHealthBpsCap, int defenseBpsCap, int catchupCurrencyType,
                             long catchupPricePerPoint, int defaultSealId,
                             int cycleOrder, int cycleDurationDays) {
        public RuleParams(int seasonId, long startsAtMs, long endsAtMs, boolean enabled,
                          int sealMaxPoints, int pveWinPoints, int pvpWinPoints, int pvpOtherPoints,
                          int maxHealthBpsCap, int defenseBpsCap, int catchupCurrencyType,
                          long catchupPricePerPoint, int defaultSealId) {
            this(seasonId, startsAtMs, endsAtMs, enabled, sealMaxPoints, pveWinPoints, pvpWinPoints,
                    pvpOtherPoints, maxHealthBpsCap, defenseBpsCap, catchupCurrencyType,
                    catchupPricePerPoint, defaultSealId, 0, 0);
        }
    }

    public record ResolvedSeason(boolean active, boolean invalidConfig, int seasonId, int seasonTerm,
                                 int cycleIndex, int cyclePoolSize, long termStartMs, long termEndMs,
                                 int ruleIndex) {
        public static final ResolvedSeason INACTIVE = new ResolvedSeason(false, false, 0, 0, 0, 0, 0L, 0L, -1);
        public static final ResolvedSeason INVALID = new ResolvedSeason(false, true, 0, 0, 0, 0, 0L, 0L, -1);
    }

    public record CatchupPlan(int pointsToBuy, long totalPrice) {
    }

    /** 赛季全员对称周环境词缀（零养成门槛、敌我对称、每周自动轮换；0 表示无词缀）。 */
    public static final int WEEKLY_AFFIX_NONE = 0;
    public static final int WEEKLY_AFFIX_SHIELD_OVERLOAD = 1;
    public static final int WEEKLY_AFFIX_SPIRIT_RECOVERY = 2;
    public static final int WEEKLY_AFFIX_OPENING_GUARD = 3;
    public static final int WEEKLY_AFFIX_SHARP_TEMPO = 4;
    public static final int WEEKLY_AFFIX_COUNT = 4;

    public static final int WEEKLY_AFFIX_SHIELD_BONUS_PCT = 15;
    public static final int WEEKLY_AFFIX_DEFEND_MANA_BPS = 300;
    public static final int WEEKLY_AFFIX_OPENING_GUARD_REDUCE_PCT = 8;
    public static final int WEEKLY_AFFIX_OPENING_GUARD_ROUNDS = 2;
    public static final int WEEKLY_AFFIX_SHARP_TEMPO_BONUS_PCT = 5;

    private static final long MS_PER_WEEK = 7L * 24L * 3600L * 1000L;
    private static final long MONDAY_05_CST_OFFSET_MS = (3L * 24L - 3L) * 3600L * 1000L;

    public static boolean isValidWeeklyAffixId(int affixId) {
        return Integer.compareUnsigned(affixId, 1) >= 0
                && Integer.compareUnsigned(affixId, WEEKLY_AFFIX_COUNT) <= 0;
    }

    /** 计算当前权威赛季周次（1 起）：有明确开季/开届时间时按 7 天递增；首测常开（startsAtMs == 0）对齐 UTC+8 周一 05:00。 */
    public static int computeSeasonWeekIndex(long startsAtMs, long nowMs) {
        if (startsAtMs != 0L) {
            if (Long.compareUnsigned(nowMs, startsAtMs) <= 0) {
                return 1;
            }
            return (int) Long.divideUnsigned(nowMs - startsAtMs, MS_PER_WEEK) + 1;
        }
        return (int) Long.divideUnsigned(nowMs + MONDAY_05_CST_OFFSET_MS, MS_PER_WEEK) + 1;
    }

    public static int weeklyAffixForWeek(int seasonOrTermId, int weekIndex) {
        if (seasonOrTermId == 0 || weekIndex == 0) {
            return WEEKLY_AFFIX_NONE;
        }
        long sum = Integer.toUnsignedLong(seasonOrTermId - 1) + Integer.toUnsignedLong(weekIndex - 1);
        return (int) Long.remainderUnsigned(sum, WEEKLY_AFFIX_COUNT) + 1;
    }

    public static int weeklyAffixAt(int seasonOrTermId, long startsAtMs, long nowMs) {
        if (seasonOrTermId == 0) {
            return WEEKLY_AFFIX_NONE;
        }
        return weeklyAffixForWeek(seasonOrTermId, computeSeasonWeekIndex(startsAtMs, nowMs));
    }

    public static boolean isSealEffectRecommendedForAffix(int affixId, int effectId) {
        return switch (affixId) {
            case WEEKLY_AFFIX_SHIELD_OVERLOAD -> effectId == 1 || effectId == 2 || effectId == 5;
            case WEEKLY_AFFIX_SPIRIT_RECOVERY -> effectId == 7 || effectId == 6 || effectId == 4;
            case WEEKLY_AFFIX_OPENING_GUARD -> effectId == 6 || effectId == 7 || effectId == 5;
            case WEEKLY_AFFIX_SHARP_TEMPO -> effectId == 1 || effectId == 2 || effectId == 4;
            default -> false;
        };
    }

    public static int shieldOverloadBonusPct(int affixId) {
        return affixId == WEEKLY_AFFIX_SHIELD_OVERLOAD ? WEEKLY_AFFIX_SHIELD_BONUS_PCT : 0;
    }

    public static int defendBonusManaRegenBps(int affixId) {
        return affixId == WEEKLY_AFFIX_SPIRIT_RECOVERY ? WEEKLY_AFFIX_DEFEND_MANA_BPS : 0;
    }

    public static int openingGuardDamageReducePct(int affixId, int roundIndex) {
        return affixId == WEEKLY_AFFIX_OPENING_GUARD
                && Integer.compareUnsigned(roundIndex, 1) >= 0
                && Integer.compareUnsigned(roundIndex, WEEKLY_AFFIX_OPENING_GUARD_ROUNDS) <= 0
                ? WEEKLY_AFFIX_OPENING_GUARD_REDUCE_PCT
                : 0;
    }

    public static int sharpTempoUnshieldedDamageBonusPct(int affixId, boolean targetHasShield) {
        return affixId == WEEKLY_AFFIX_SHARP_TEMPO && !targetHasShield
                ? WEEKLY_AFFIX_SHARP_TEMPO_BONUS_PCT
                : 0;
    }

    /** 赛季玩法 V4：8 种表驱动「技印共鸣」原语（零面板属性、零速度控制，硬上限校验）。 */
    public static final int RESONANCE_NONE = 0;
    public static final int RESONANCE_SHARP_MANA_REFUND = 1;
    public static final int RESONANCE_BREAK_EXTEND_DEBUFF = 2;
    public static final int RESONANCE_GUARD_SHIELD_BOOST = 3;
    public static final int RESONANCE_DEFEND_MANA_DISCOUNT = 4;
    public static final int RESONANCE_EMERGENCY_HEAL_REFUND = 5;
    public static final int RESONANCE_FIRST_SHIELD_MANA = 6;
    public static final int RESONANCE_GUARD_HEAL_BOOST = 7;
    public static final int RESONANCE_DEFEND_SHIELD_BOOST = 8;
    public static final int RESONANCE_EVENT_BASE = 100;

    /** BATTLE_EVENT_ARTIFACT（artifact_item_id=0, season_seal_id>0）共鸣子事件号 101..108。 */
    public static final int RESONANCE_EVENT_SHARP_MANA_REFUND = 101;
    public static final int RESONANCE_EVENT_MYSTIC_EXTEND_DEBUFF = 102;
    public static final int RESONANCE_EVENT_GUARD_SHIELD_BOOST = 103;
    public static final int RESONANCE_EVENT_BREATH_HALF_MANA = 104;
    public static final int RESONANCE_EVENT_EMERGENCY_HEAL_REFUND = 105;
    public static final int RESONANCE_EVENT_FIRST_SHIELD_MANA = 106;
    public static final int RESONANCE_EVENT_GUARD_HEAL_BOOST = 107;
    public static final int RESONANCE_EVENT_DEFEND_SHIELD_BOOST = 108;

    /** V3 兼容别名。 */
    public static final int SEASON_1_ID = 1;
    public static final int SEAL_POZHEN_RUIYIN = 101;
    public static final int SEAL_POZHEN_XUANYIN = 102;
    public static final int SEAL_GUZHEN_SHOUYIN = 103;
    public static final int SEAL_JUXI_YUANYIN = 104;
    public static final int SKILL_POZHEN_LIEFENG = 15001;
    public static final int SKILL_POZHEN_LINGLEI = 15002;
    public static final int SKILL_GUZHEN_LINGZHANG = 15003;
    public static final int SKILL_POZHEN_XUSHI = 15004;
    public static final int RESONANCE_SHARP_SHATTER_MANA_REFUND_PCT = 30;
    public static final int RESONANCE_MYSTIC_DEBUFF_BONUS_ROUNDS = 1;
    public static final int RESONANCE_GUARD_SHIELD_BONUS_PCT = 10;

    /** 向上取整百分比返蓝（溢出安全），与 C++ {@code seasonrules::ComputeManaPctCeil} 逐位一致。 */
    public static long computeManaPctCeil(long paidMana, int pct) {
        if (paidMana == 0L || pct <= 0) {
            return 0L;
        }
        long bounded = Math.min(Integer.toUnsignedLong(pct), 100L);
        long q = Long.divideUnsigned(paidMana, 100L);
        long r = Long.remainderUnsigned(paidMana, 100L);
        return q * bounded + Long.divideUnsigned(r * bounded + 99L, 100L);
    }

    /** 百分比省蓝后实付耗蓝（向下取整，discountPct=50 时与 baseCost/2 逐位一致）。 */
    public static long computeManaDiscounted(long baseCost, int discountPct) {
        if (baseCost == 0L) {
            return 0L;
        }
        long pct = Integer.toUnsignedLong(discountPct);
        long keepPct = pct >= 100L ? 0L : 100L - pct;
        long q = Long.divideUnsigned(baseCost, 100L);
        long r = Long.remainderUnsigned(baseCost, 100L);
        return q * keepPct + Long.divideUnsigned(r * keepPct, 100L);
    }

    public static long resonanceSharpManaRefund(long paidMana) {
        return computeManaPctCeil(paidMana, RESONANCE_SHARP_SHATTER_MANA_REFUND_PCT);
    }

    public static long resonanceHalfManaCost(long baseCost) {
        return computeManaDiscounted(baseCost, 50);
    }

    /** 校验表驱动共鸣原语与硬上限；resonanceKind == 0 支持热更一键紧急关闭。 */
    public static boolean validSealResonance(int effectId, int resonanceSkillId,
                                             int resonanceKind, int resonanceParam) {
        if (resonanceKind == RESONANCE_NONE) {
            return true;
        }
        if (resonanceSkillId == 0 || resonanceParam == 0) {
            return false;
        }
        long param = Integer.toUnsignedLong(resonanceParam);
        return switch (resonanceKind) {
            case RESONANCE_SHARP_MANA_REFUND -> (effectId == 1 || effectId == 2) && param <= 40L;
            case RESONANCE_BREAK_EXTEND_DEBUFF -> (effectId == 1 || effectId == 2) && param <= 1L;
            case RESONANCE_GUARD_SHIELD_BOOST -> effectId == 6 && param <= 15L;
            case RESONANCE_DEFEND_MANA_DISCOUNT -> effectId == 7 && param <= 50L;
            case RESONANCE_EMERGENCY_HEAL_REFUND -> effectId == 4 && param <= 35L;
            case RESONANCE_FIRST_SHIELD_MANA -> effectId == 5 && param <= 300L;
            case RESONANCE_GUARD_HEAL_BOOST -> effectId == 6 && param <= 15L;
            case RESONANCE_DEFEND_SHIELD_BOOST -> effectId == 7 && param <= 12L;
            default -> false;
        };
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
        if (rule.cycleDurationDays() != 0
                && (Integer.compareUnsigned(rule.cycleDurationDays(), MIN_CYCLE_DURATION_DAYS) < 0
                || Integer.compareUnsigned(rule.cycleDurationDays(), MAX_CYCLE_DURATION_DAYS) > 0)) {
            return "cycle_duration_days must be 0 or in [7, 180]";
        }
        return null;
    }

    /** 赛季印严禁使用 effect_id = 3（控命）与 8（控抗），仅开放 1/2/4/5/6/7 六类零速度零控率轻机制。 */
    public static boolean validSealEffect(int seasonId, int sealId, int effectId,
                                          int bonusBps, int capBps, int perRoundLimit,
                                          int battleLimit, int activeRounds, int healthThresholdBps) {
        if (seasonId == 0 || sealId == 0 || effectId == 3 || effectId == 8) {
            return false;
        }
        return validArtifactDefinition(effectId, bonusBps, capBps, perRoundLimit,
                battleLimit, activeRounds, healthThresholdBps);
    }

    public static boolean validSealDefinition(int seasonId, int sealId, int effectId,
                                              int bonusBps, int capBps, int perRoundLimit,
                                              int battleLimit, int activeRounds, int healthThresholdBps,
                                              int resonanceSkillId, int resonanceKind, int resonanceParam) {
        return validSealEffect(seasonId, sealId, effectId, bonusBps, capBps, perRoundLimit,
                battleLimit, activeRounds, healthThresholdBps)
                && validSealResonance(effectId, resonanceSkillId, resonanceKind, resonanceParam);
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

    /** 校验整张 SeasonRule 排期（固定单季窗模式 vs 无限期号自动循环模式）；合法返回 null。 */
    public static String validateSchedule(List<RuleParams> rules) {
        boolean anyCycle = false;
        for (RuleParams rule : rules) {
            String err = validateRule(rule);
            if (err != null) {
                return err;
            }
            if (rule.cycleDurationDays() != 0) {
                anyCycle = true;
            }
        }
        if (!anyCycle) {
            for (int i = 0; i < rules.size(); i++) {
                if (!rules.get(i).enabled()) {
                    continue;
                }
                for (int j = i + 1; j < rules.size(); j++) {
                    if (!rules.get(j).enabled()) {
                        continue;
                    }
                    if (windowsOverlap(rules.get(i).startsAtMs(), rules.get(i).endsAtMs(),
                            rules.get(j).startsAtMs(), rules.get(j).endsAtMs())) {
                        return "overlapping enabled seasons in fixed-window mode";
                    }
                }
            }
            return null;
        }
        List<RuleParams> pool = new ArrayList<>();
        int sharedDuration = 0;
        for (RuleParams rule : rules) {
            if (rule.cycleOrder() == 0) {
                if (rule.enabled() || rule.cycleDurationDays() != 0) {
                    return "non-pool rule cannot be enabled or set cycle_duration_days in cycle mode";
                }
                continue;
            }
            if (!rule.enabled()) {
                return "cycle pool rule must be enabled";
            }
            if (Integer.compareUnsigned(rule.cycleDurationDays(), MIN_CYCLE_DURATION_DAYS) < 0
                    || Integer.compareUnsigned(rule.cycleDurationDays(), MAX_CYCLE_DURATION_DAYS) > 0) {
                return "cycle pool rule cycle_duration_days out of range [7, 180]";
            }
            if (sharedDuration == 0) {
                sharedDuration = rule.cycleDurationDays();
            } else if (sharedDuration != rule.cycleDurationDays()) {
                return "cycle pool rules must share identical cycle_duration_days";
            }
            pool.add(rule);
        }
        if (pool.isEmpty()) {
            return "cycle mode requires at least one rule with cycle_order > 0";
        }
        pool.sort(Comparator.comparing(RuleParams::cycleOrder, Integer::compareUnsigned));
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).cycleOrder() != i + 1) {
                return "cycle_order must form contiguous 1..N sequence without duplicates";
            }
        }
        if (pool.get(0).startsAtMs() == 0L) {
            return "cycle mode requires pool[0].starts_at_ms > 0 as anchor timestamp";
        }
        return null;
    }

    /** 确定性解析当前时刻活跃赛季（固定单季 vs 无限期号自动循环；配置非法返回 INVALID）。 */
    public static ResolvedSeason resolveActiveSeason(List<RuleParams> rules, long nowMs) {
        if (rules == null || rules.isEmpty()) {
            return ResolvedSeason.INACTIVE;
        }
        if (validateSchedule(rules) != null) {
            return ResolvedSeason.INVALID;
        }
        boolean anyCycle = false;
        for (RuleParams r : rules) {
            if (r.cycleDurationDays() != 0) {
                anyCycle = true;
                break;
            }
        }
        if (!anyCycle) {
            for (int i = 0; i < rules.size(); i++) {
                RuleParams r = rules.get(i);
                if (!isWindowActive(r.startsAtMs(), r.endsAtMs(), r.enabled(), nowMs)) {
                    continue;
                }
                return new ResolvedSeason(true, false, r.seasonId(), 1, 1, 0, r.startsAtMs(), r.endsAtMs(), i);
            }
            return ResolvedSeason.INACTIVE;
        }
        record PoolEntry(int order, int idx) { }
        List<PoolEntry> pool = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).cycleOrder() != 0) {
                pool.add(new PoolEntry(rules.get(i).cycleOrder(), i));
            }
        }
        pool.sort(Comparator.comparing(PoolEntry::order, Integer::compareUnsigned));
        RuleParams anchorRule = rules.get(pool.get(0).idx());
        long anchorMs = anchorRule.startsAtMs();
        if (Long.compareUnsigned(nowMs, anchorMs) < 0) {
            return ResolvedSeason.INACTIVE;
        }
        long periodMs = Integer.toUnsignedLong(anchorRule.cycleDurationDays()) * MS_PER_DAY;
        long elapsedIdx = Long.divideUnsigned(nowMs - anchorMs, periodMs);
        long poolSize = pool.size();
        int pickedRuleIndex = pool.get((int) Long.remainderUnsigned(elapsedIdx, poolSize)).idx();
        long term64 = elapsedIdx + 1L;
        long cycle64 = Long.divideUnsigned(elapsedIdx, poolSize) + 1L;
        int seasonTerm = Long.compareUnsigned(term64, 0xFFFF_FFFFL) > 0 ? -1 : (int) term64;
        int cycleIndex = Long.compareUnsigned(cycle64, 0xFFFF_FFFFL) > 0 ? -1 : (int) cycle64;
        long termStartMs = anchorMs + elapsedIdx * periodMs;
        long termEndMs = termStartMs + periodMs;
        return new ResolvedSeason(true, false, rules.get(pickedRuleIndex).seasonId(),
                seasonTerm, cycleIndex, pool.size(), termStartMs, termEndMs, pickedRuleIndex);
    }

    /** 永久纪念徽章动态位图读取纯函数（word = (season_id - 1) / 64, bit = (season_id - 1) % 64）。 */
    public static boolean isHonorBitSet(List<Long> words, int seasonId) {
        if (words == null || seasonId == 0) {
            return false;
        }
        long zeroBased = Integer.toUnsignedLong(seasonId - 1);
        long wordIdxLong = Long.divideUnsigned(zeroBased, 64L);
        if (wordIdxLong >= words.size()) {
            return false;
        }
        int bit = (int) Long.remainderUnsigned(zeroBased, 64L);
        return (words.get((int) wordIdxLong) & (1L << bit)) != 0L;
    }

    /** 动态位图置位并返回本次是否首次解锁。 */
    public static boolean setHonorBit(List<Long> words, int seasonId) {
        if (words == null || seasonId == 0) {
            return false;
        }
        long zeroBased = Integer.toUnsignedLong(seasonId - 1);
        int wordIdx = (int) Long.divideUnsigned(zeroBased, 64L);
        int bit = (int) Long.remainderUnsigned(zeroBased, 64L);
        while (words.size() <= wordIdx) {
            words.add(0L);
        }
        long mask = 1L << bit;
        long prev = words.get(wordIdx);
        boolean newlySet = (prev & mask) == 0L;
        words.set(wordIdx, prev | mask);
        return newlySet;
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
