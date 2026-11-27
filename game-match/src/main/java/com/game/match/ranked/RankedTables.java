package com.game.match.ranked;

import com.game.match.support.MatchModes;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleParticipationFact;
import com.game.proto.BattleSettlementData;
import com.game.proto.RankedMatchContext;
import com.game.proto.TrialBattleContext;
import com.game.proto.match.RankedPresetView;
import com.game.proto.match.RankedTier;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.PlayModeTable;
import com.game.table.RankedPresetTable;
import com.game.table.RankedRuleTable;
import com.game.table.SeasonRuleTable;
import com.game.table.TrialJournalEntryTable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 论剑排位首发 V1、玩法白名单与五行试炼志 V2 对局上下文配表只读视图。 */
public final class RankedTables {

    public static final int ROLE_KIND_CHARACTER = 1;
    public static final int ROLE_KIND_PET = 2;
    public static final int BOSS_TRIAL_KIND_GUARD_RESCUE = 5;
    public static final int BOSS_TRIAL_KIND_POLARITY = 6;
    public static final int BOSS_TRIAL_KIND_FESTIVAL_BEAST = 7;
    public static final int BOSS_TRIAL_KIND_PRACTICE_DUMMY = 9;
    public static final int BOSS_TRIAL_KIND_TACTICAL_PUZZLE = 10;
    public static final int BOSS_TRIAL_KIND_CHARACTER_TALE = 11;
    public static final long INITIAL_RATING_CENTI = 150_000L;
    public static final List<Integer> PRIMARY_TEAM_SIZES = List.of(1, 3, 5);
    public static final List<Integer> SPECIAL_TEAM_SIZES = List.of(2, 4);

    private record TrialVariantKey(int seasonId, int sourceKind, int difficulty, String variantKey) {
    }

    private record ResolvedSeason(int seasonId, int seasonTerm, boolean cycleMode) {
        static final ResolvedSeason NONE = new ResolvedSeason(0, 0, false);
    }

    private final RankedRuleTable rule;
    private final Map<Integer, RankedPresetTable> presetsById;
    private final List<RankedPresetView> presetViews;
    private final String configFingerprint;
    private final Map<Long, List<Integer>> allowedTeamSizesByModeConfig;
    private final Map<Integer, Integer> bossTrialKindByDungeon;
    private final Map<Integer, TrialBattleContext> trialContextsByDungeon;
    private final Map<Integer, Map<Integer, TrialBattleContext>> seasonalTrialContextsBySeason;
    private final Map<Integer, TrialBattleContext> rankedTrialBySeason;
    private final List<SeasonRuleTable> seasonRules;

    private RankedTables(RankedRuleTable rule, Map<Integer, RankedPresetTable> presetsById,
                         List<RankedPresetView> presetViews, String configFingerprint,
                         Map<Long, List<Integer>> allowedTeamSizesByModeConfig,
                         Map<Integer, Integer> bossTrialKindByDungeon,
                         Map<Integer, TrialBattleContext> trialContextsByDungeon,
                         Map<Integer, Map<Integer, TrialBattleContext>> seasonalTrialContextsBySeason,
                         Map<Integer, TrialBattleContext> rankedTrialBySeason,
                         List<SeasonRuleTable> seasonRules) {
        this.rule = rule;
        this.presetsById = presetsById;
        this.presetViews = presetViews;
        this.configFingerprint = configFingerprint;
        this.allowedTeamSizesByModeConfig = allowedTeamSizesByModeConfig;
        this.bossTrialKindByDungeon = bossTrialKindByDungeon;
        this.trialContextsByDungeon = trialContextsByDungeon;
        this.seasonalTrialContextsBySeason = seasonalTrialContextsBySeason;
        this.rankedTrialBySeason = rankedTrialBySeason;
        this.seasonRules = seasonRules;
    }

    public static RankedTables from(ConfigTables config) {
        Objects.requireNonNull(config, "config");
        RankedRuleTable rule = config.rankedRule().find(1)
                .orElseThrow(() -> new IllegalStateException("RankedRule 表缺少 id=1 规则行"));
        List<RankedPresetTable> sorted = config.rankedPreset().all().stream()
                .sorted(Comparator.comparingInt(RankedPresetTable::getSort).thenComparingInt(RankedPresetTable::getId))
                .toList();
        Map<Integer, RankedPresetTable> byId = new LinkedHashMap<>(sorted.size() * 2);
        for (RankedPresetTable row : sorted) {
            byId.put(row.getId(), row);
        }
        RankedPresetTable defChar = byId.get(rule.getDefaultCharacterPresetId());
        RankedPresetTable defPet = byId.get(rule.getDefaultPetPresetId());
        if (defChar == null || defChar.getRoleKind() != ROLE_KIND_CHARACTER
                || defPet == null || defPet.getRoleKind() != ROLE_KIND_PET) {
            throw new IllegalStateException("RankedRule 默认人物/宝宝预设无效");
        }
        if (Integer.compareUnsigned(rule.getSkillBanSlotsPerTeam(), 3) > 0) {
            throw new IllegalStateException("RankedRule.skill_ban_slots_per_team 越界: "
                    + Integer.toUnsignedString(rule.getSkillBanSlotsPerTeam()));
        }
        List<RankedPresetView> views = sorted.stream().map(RankedTables::toView).toList();
        List<RankedPresetTable> byIdAsc = config.rankedPreset().all().stream()
                .sorted(Comparator.comparingInt(RankedPresetTable::getId))
                .toList();
        String fingerprint = computeConfigFingerprint(rule, byIdAsc);
        Map<Integer, Integer> bossKinds = new HashMap<>();
        for (DungeonTable dungeon : config.dungeon().all()) {
            if (dungeon.getBossTrialKind() != 0) {
                bossKinds.put(dungeon.getId(), dungeon.getBossTrialKind());
            }
        }
        for (PlayModeTable row : config.playMode().all()) {
            if (bossKinds.getOrDefault(row.getBattleConfigId(), 0) == BOSS_TRIAL_KIND_FESTIVAL_BEAST) {
                throw new IllegalStateException("PlayMode id=" + row.getId()
                        + " 禁止配置瑞兽迎春专属副本 dungeon=" + row.getBattleConfigId());
            }
        }
        Map<Long, List<Integer>> allowedSizes = parsePlayModeAllowedSizes(config.playMode().all());
        Map<Integer, TrialBattleContext> trialByDungeon = new HashMap<>();
        Map<Integer, Map<Integer, TrialBattleContext>> seasonalBySeason = new HashMap<>();
        Map<Integer, TrialBattleContext> rankedBySeason = new HashMap<>();
        buildTrialContexts(config, config.dungeon().all(),
                config.trialJournalEntry().all(), trialByDungeon, seasonalBySeason, rankedBySeason);
        Map<Integer, Map<Integer, TrialBattleContext>> frozenSeasonal = new HashMap<>(seasonalBySeason.size() * 2);
        seasonalBySeason.forEach((sid, m) -> frozenSeasonal.put(sid, Map.copyOf(m)));
        List<SeasonRuleTable> seasonRules = List.copyOf(config.seasonRule().all());
        return new RankedTables(rule, Map.copyOf(byId), List.copyOf(views), fingerprint,
                Map.copyOf(allowedSizes), Map.copyOf(bossKinds),
                Map.copyOf(trialByDungeon), Map.copyOf(frozenSeasonal),
                Map.copyOf(rankedBySeason), seasonRules);
    }

    private static void buildTrialContexts(ConfigTables config,
                                           List<DungeonTable> dungeons,
                                           List<TrialJournalEntryTable> entries,
                                           Map<Integer, TrialBattleContext> outPermanentByDungeon,
                                           Map<Integer, Map<Integer, TrialBattleContext>> outSeasonalBySeason,
                                           Map<Integer, TrialBattleContext> outRankedBySeason) {
        Map<TrialVariantKey, TrialJournalEntryTable> byVariant = new HashMap<>(entries.size() * 2);
        Set<Integer> seasons = new HashSet<>();
        for (TrialJournalEntryTable entry : entries) {
            if (!entry.getEnabled()) {
                continue;
            }
            if (entry.getId() == 0 || entry.getRuleVersion() == 0) {
                throw new IllegalStateException("TrialJournalEntry 行 id=" + entry.getId() + " 缺少有效 id 或 rule_version");
            }
            TrialVariantKey key = new TrialVariantKey(entry.getSeasonId(), entry.getSourceKind(), entry.getDifficulty(), entry.getVariantKey());
            TrialJournalEntryTable prev = byVariant.putIfAbsent(key, entry);
            if (prev != null) {
                throw new IllegalStateException("TrialJournalEntry 行 " + prev.getId() + " 与 " + entry.getId()
                        + " 的 (season_id=" + entry.getSeasonId() + ", source_kind=" + entry.getSourceKind()
                        + ", difficulty=" + entry.getDifficulty() + ", variant_key=" + entry.getVariantKey() + ") 重复");
            }
            if (entry.getSeasonId() != 0) {
                seasons.add(entry.getSeasonId());
            }
        }
        for (DungeonTable dungeon : dungeons) {
            TrialVariantKey mapped = bossTrialVariant(dungeon.getBossTrialKind(), dungeon.getDifficulty());
            if (mapped == null) {
                continue;
            }
            int guardMonsterId = 0;
            for (int monsterId : dungeon.getMonsterList()) {
                if (monsterId == 0) {
                    continue;
                }
                var monster = config.monster().find(monsterId);
                if (monster.isPresent() && monster.get().getIsBoss() == 0) {
                    guardMonsterId = monsterId;
                    break;
                }
            }
            TrialJournalEntryTable permEntry = byVariant.get(mapped);
            if (permEntry != null) {
                outPermanentByDungeon.put(dungeon.getId(), TrialBattleContext.newBuilder()
                        .setJournalEntryId(permEntry.getId())
                        .setTrialRuleVersion(permEntry.getRuleVersion())
                        .setSeasonId(0)
                        .setGuardMonsterTableId(guardMonsterId)
                        .build());
                continue;
            }
            for (int sid : seasons) {
                TrialJournalEntryTable sEntry = byVariant.get(
                        new TrialVariantKey(sid, mapped.sourceKind(), mapped.difficulty(), mapped.variantKey()));
                if (sEntry == null) {
                    continue;
                }
                outSeasonalBySeason.computeIfAbsent(sid, k -> new HashMap<>()).put(dungeon.getId(),
                        TrialBattleContext.newBuilder()
                                .setJournalEntryId(sEntry.getId())
                                .setTrialRuleVersion(sEntry.getRuleVersion())
                                .setSeasonId(sid)
                                .setGuardMonsterTableId(guardMonsterId)
                                .build());
            }
        }
        for (int sid : seasons) {
            TrialJournalEntryTable rankedEntry = byVariant.get(new TrialVariantKey(sid, 4, 0, "ranked_pvp"));
            if (rankedEntry != null) {
                outRankedBySeason.put(sid, TrialBattleContext.newBuilder()
                        .setJournalEntryId(rankedEntry.getId())
                        .setTrialRuleVersion(rankedEntry.getRuleVersion())
                        .setSeasonId(sid)
                        .build());
            }
        }
    }

    private static TrialVariantKey bossTrialVariant(int bossTrialKind, int difficulty) {
        return switch (bossTrialKind) {
            case 1 -> new TrialVariantKey(0, 1, difficulty, "rejuvenation");
            case 2 -> new TrialVariantKey(0, 1, difficulty, "bulwark");
            case 3 -> new TrialVariantKey(0, 1, difficulty, "barrier");
            case 4 -> new TrialVariantKey(0, 1, difficulty, "venom");
            case 5 -> new TrialVariantKey(0, 2, difficulty, "rescue");
            case 6 -> new TrialVariantKey(0, 3, difficulty, "polarity_5v5");
            case 8 -> new TrialVariantKey(0, 1, difficulty, "dual_guard");
            case 12 -> new TrialVariantKey(0, 1, difficulty, "wild_wind");
            case 13 -> new TrialVariantKey(0, 1, difficulty, "wild_bell");
            case 14 -> new TrialVariantKey(0, 1, difficulty, "wild_tide");
            default -> null;
        };
    }

    private static final int SEASON_MIN_CYCLE_DAYS = 7;
    private static final int SEASON_MAX_CYCLE_DAYS = 180;
    private static final long SEASON_MS_PER_DAY = 24L * 3600L * 1000L;

    private static ResolvedSeason resolveActiveSeason(List<SeasonRuleTable> rules, long nowMs) {
        if (rules == null || rules.isEmpty()) {
            return ResolvedSeason.NONE;
        }
        boolean anyCycle = false;
        for (SeasonRuleTable r : rules) {
            if (r.getId() == 0 || (r.getEndsAtMs() != 0L && Long.compareUnsigned(r.getEndsAtMs(), r.getStartsAtMs()) <= 0)) {
                return ResolvedSeason.NONE;
            }
            if (r.getCycleDurationDays() != 0) {
                if (Integer.compareUnsigned(r.getCycleDurationDays(), SEASON_MIN_CYCLE_DAYS) < 0
                        || Integer.compareUnsigned(r.getCycleDurationDays(), SEASON_MAX_CYCLE_DAYS) > 0) {
                    return ResolvedSeason.NONE;
                }
                anyCycle = true;
            }
        }
        if (!anyCycle) {
            for (int i = 0; i < rules.size(); i++) {
                SeasonRuleTable a = rules.get(i);
                if (!a.getEnabled()) continue;
                for (int j = i + 1; j < rules.size(); j++) {
                    SeasonRuleTable b = rules.get(j);
                    if (!b.getEnabled()) continue;
                    boolean aBeforeB = a.getEndsAtMs() != 0L && Long.compareUnsigned(a.getEndsAtMs(), b.getStartsAtMs()) <= 0;
                    boolean bBeforeA = b.getEndsAtMs() != 0L && Long.compareUnsigned(b.getEndsAtMs(), a.getStartsAtMs()) <= 0;
                    if (!aBeforeB && !bBeforeA) {
                        return ResolvedSeason.NONE;
                    }
                }
            }
            for (SeasonRuleTable r : rules) {
                if (!r.getEnabled()) continue;
                if (Long.compareUnsigned(nowMs, r.getStartsAtMs()) < 0) continue;
                if (r.getEndsAtMs() != 0L && Long.compareUnsigned(nowMs, r.getEndsAtMs()) >= 0) continue;
                return new ResolvedSeason(r.getId(), 1, false);
            }
            return ResolvedSeason.NONE;
        }
        List<SeasonRuleTable> pool = new ArrayList<>();
        int sharedDuration = 0;
        for (SeasonRuleTable r : rules) {
            if (r.getCycleOrder() == 0) {
                if (r.getEnabled() || r.getCycleDurationDays() != 0) {
                    return ResolvedSeason.NONE;
                }
                continue;
            }
            if (!r.getEnabled()
                    || Integer.compareUnsigned(r.getCycleDurationDays(), SEASON_MIN_CYCLE_DAYS) < 0
                    || Integer.compareUnsigned(r.getCycleDurationDays(), SEASON_MAX_CYCLE_DAYS) > 0) {
                return ResolvedSeason.NONE;
            }
            if (sharedDuration == 0) {
                sharedDuration = r.getCycleDurationDays();
            } else if (sharedDuration != r.getCycleDurationDays()) {
                return ResolvedSeason.NONE;
            }
            pool.add(r);
        }
        if (pool.isEmpty()) {
            return ResolvedSeason.NONE;
        }
        pool.sort(Comparator.comparingInt(SeasonRuleTable::getCycleOrder));
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).getCycleOrder() != i + 1) {
                return ResolvedSeason.NONE;
            }
        }
        long anchorMs = pool.getFirst().getStartsAtMs();
        if (anchorMs == 0L || Long.compareUnsigned(nowMs, anchorMs) < 0) {
            return ResolvedSeason.NONE;
        }
        long periodMs = Integer.toUnsignedLong(sharedDuration) * SEASON_MS_PER_DAY;
        long elapsed = Long.divideUnsigned(nowMs - anchorMs, periodMs);
        SeasonRuleTable active = pool.get((int) Long.remainderUnsigned(elapsed, pool.size()));
        return new ResolvedSeason(active.getId(), (int) (elapsed + 1L), true);
    }

    /** 返回指定时刻活跃主题赛季 ID（与 C++ {@code PlayerSeasonSystem::ActiveSeasonId} 及 Go {@code Catalog.ActiveSeasonIdAt} 同口径）。 */
    public int activeSeasonIdAt(long nowMs) {
        return resolveActiveSeason(seasonRules, nowMs).seasonId();
    }

    /** 五行试炼志 V3 全部四项自选誓约合法位掩码（bit0 禁控 / bit1 单宝 / bit2 无宠 / bit3 紧缩回合）。 */
    public static final int TRIAL_VOW_MASK_ALL = 0x0F;

    public static boolean isValidTrialVowMask(int mask) {
        return (mask & ~TRIAL_VOW_MASK_ALL) == 0;
    }

    public static boolean isPermanentBossTrialKind(int bossTrialKind) {
        return (bossTrialKind >= 1 && bossTrialKind <= 4)
                || bossTrialKind == 8
                || (bossTrialKind >= 12 && bossTrialKind <= 14);
    }

    /** 报告该副本模板是否支持挂载试炼誓约（仅常驻卷一/卷二/卷四首领试炼，对齐 Go {@code Catalog.SupportsTrialVows}）。 */
    public boolean supportsTrialVows(int battleConfigId) {
        int kind = bossTrialKindByDungeon.getOrDefault(battleConfigId, 0);
        if (!isPermanentBossTrialKind(kind)) {
            return false;
        }
        TrialBattleContext ctx = trialContextsByDungeon.get(battleConfigId);
        return ctx != null && ctx.getJournalEntryId() != 0 && ctx.getSeasonId() == 0;
    }

    /**
     * 返回本局应冻结进 {@code CreateBattleRequest.trial_context} 的试炼志上下文（对齐 Go {@code Catalog.TrialContextFor}）。
     * 覆盖卷一/卷二/卷四永久首领试炼、卷三当季护卫救援与物法易势（允许免费预设）、卷三当季人宠会武；帮会活动战/切磋/普通副本/跨季返回空。
     */
    public Optional<TrialBattleContext> trialContextFor(int mode, int configId,
                                                        BattleActivityContext activityContext,
                                                        RankedMatchContext rankedCtx) {
        return trialContextFor(mode, configId, activityContext, rankedCtx, System.currentTimeMillis());
    }

    public Optional<TrialBattleContext> trialContextFor(int mode, int configId,
                                                        BattleActivityContext activityContext,
                                                        RankedMatchContext rankedCtx,
                                                        long nowMs) {
        if (activityContext != null) {
            return Optional.empty();
        }
        if (mode == MatchModes.PVE_SOLO || mode == MatchModes.PVE_TEAM || mode == MatchModes.POLARITY_5V5) {
            if (rankedCtx != null && rankedCtx.getSeasonId() != 0) {
                return Optional.empty();
            }
            TrialBattleContext perm = trialContextsByDungeon.get(configId);
            if (perm != null) {
                return Optional.of(perm);
            }
            int targetSeason = 0;
            if (!seasonRules.isEmpty()) {
                targetSeason = resolveActiveSeason(seasonRules, nowMs).seasonId();
            } else if (seasonalTrialContextsBySeason.size() == 1) {
                targetSeason = seasonalTrialContextsBySeason.keySet().iterator().next();
            }
            if (targetSeason == 0) {
                return Optional.empty();
            }
            Map<Integer, TrialBattleContext> byDungeon = seasonalTrialContextsBySeason.get(targetSeason);
            return byDungeon == null ? Optional.empty() : Optional.ofNullable(byDungeon.get(configId));
        }
        if (mode == MatchModes.PVP_TEAM) {
            if (!seasonRules.isEmpty()) {
                ResolvedSeason active = resolveActiveSeason(seasonRules, nowMs);
                if (active.seasonId() == 0) {
                    return Optional.empty();
                }
                if (rankedCtx != null && rankedCtx.getSeasonId() != 0) {
                    if (!active.cycleMode() && rankedCtx.getSeasonId() != active.seasonId()) {
                        return Optional.empty();
                    }
                    if (active.cycleMode() && rankedCtx.getSeasonId() != active.seasonId()
                            && rankedCtx.getSeasonId() != active.seasonTerm()) {
                        return Optional.empty();
                    }
                }
                return Optional.ofNullable(rankedTrialBySeason.get(active.seasonId()));
            }
            if (rankedCtx != null && rankedCtx.getSeasonId() != 0) {
                return Optional.ofNullable(rankedTrialBySeason.get(rankedCtx.getSeasonId()));
            }
            if (rankedTrialBySeason.size() == 1) {
                return Optional.ofNullable(rankedTrialBySeason.values().iterator().next());
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    static Map<Long, List<Integer>> parsePlayModeAllowedSizes(List<PlayModeTable> rows) {
        Map<Long, List<Integer>> result = new HashMap<>();
        for (PlayModeTable row : rows) {
            int mode = row.getMatchMode();
            int teamSizeMax = row.getTeamSizeMax();
            List<Integer> rawAllowed = row.getAllowedTeamSizesList();
            if (!rawAllowed.isEmpty()) {
                if (mode == MatchModes.PVE_SOLO) {
                    throw new IllegalStateException("PlayMode id=" + row.getId() + ": PVE_SOLO 不允许配置 allowed_team_sizes");
                }
                if (mode != MatchModes.PVE_TEAM && mode != MatchModes.PVP_TEAM) {
                    throw new IllegalStateException("PlayMode id=" + row.getId() + ": 固定人数模式不允许配置 allowed_team_sizes");
                }
                Set<Integer> seen = new HashSet<>();
                List<Integer> cleaned = new ArrayList<>(rawAllowed.size());
                for (int size : rawAllowed) {
                    if (size < 1 || size > teamSizeMax) {
                        throw new IllegalStateException("PlayMode id=" + row.getId()
                                + ": allowed_team_sizes 项 " + size + " 超出 1..team_size_max(" + teamSizeMax + ")");
                    }
                    if (!seen.add(size)) {
                        throw new IllegalStateException("PlayMode id=" + row.getId()
                                + ": allowed_team_sizes 含重复人数档 " + size);
                    }
                    cleaned.add(size);
                }
                cleaned.sort(Integer::compareTo);
                result.put(modeConfigKey(mode, row.getBattleConfigId()), List.copyOf(cleaned));
            } else if ((mode == MatchModes.PVE_TEAM || mode == MatchModes.PVP_TEAM) && teamSizeMax >= 1) {
                List<Integer> all = new ArrayList<>(teamSizeMax);
                for (int s = 1; s <= teamSizeMax; s++) {
                    all.add(s);
                }
                result.put(modeConfigKey(mode, row.getBattleConfigId()), List.copyOf(all));
            }
        }
        return result;
    }

    private static long modeConfigKey(int mode, int configId) {
        return (Integer.toUnsignedLong(mode) << 32) | Integer.toUnsignedLong(configId);
    }

    /** 返回 (mode, configId) 当前全部开放的人数档（升序）；未配置时返回空列表。 */
    public List<Integer> allowedTeamSizes(int mode, int configId) {
        return allowedTeamSizesByModeConfig.getOrDefault(modeConfigKey(mode, configId), List.of());
    }

    /** 判断指定人数档是否在 PlayMode 白名单内（若表中未登记该组合则回落按默认放行）。 */
    public boolean isAllowedTeamSize(int mode, int configId, int size) {
        List<Integer> allowed = allowedTeamSizes(mode, configId);
        return allowed.isEmpty() || allowed.contains(size);
    }

    /** 返回 Dungeon 表的 boss_trial_kind（0=常规，1..4=首领试炼，5=护卫救援，6=物法易势）。 */
    public int bossTrialKind(int configId) {
        return bossTrialKindByDungeon.getOrDefault(configId, 0);
    }

    /** 娱乐轮换免费 3×3 预设判定：物法易势 5V5 或 护卫救援（Dungeon 109, boss_trial_kind=5）。 */
    public boolean usesFreePreset(int mode, int configId) {
        return mode == MatchModes.POLARITY_5V5 || bossTrialKind(configId) == BOSS_TRIAL_KIND_GUARD_RESCUE;
    }

    /** 归元城残局考题（10）与人物奇谈（11）允许单人按票据选配免费 3×3 预设；演武木桩（9）禁止预设。 */
    public boolean allowsOptionalFreePreset(int configId) {
        int kind = bossTrialKind(configId);
        return kind == BOSS_TRIAL_KIND_TACTICAL_PUZZLE || kind == BOSS_TRIAL_KIND_CHARACTER_TALE;
    }

    public String configFingerprint() {
        return configFingerprint;
    }

    public static final int RANKED_MIN_ROUNDS_FOR_LOSS = 3;
    public static final int RANKED_MIN_MANUAL_ROUNDS_FOR_LOSS = 2;

    /** 判定单名玩家在排位中是否满足有效参赛（与 C++/Go evaluateRankedParticipation 逐条一致）。 */
    public static boolean evaluateParticipation(BattleParticipationFact fact, boolean fled) {
        if (fled || fact == null) {
            return false;
        }
        if (fact.getManualActionAccepted()) {
            return true;
        }
        if (fact.getEnteredRoom() && fact.getExplicitAutoEnabled()) {
            return true;
        }
        return fact.getEnteredRoom() && fact.getNoActionOpportunity();
    }

    public static boolean evaluateParticipation(BattleParticipationFact fact, BattleSettlementData settlement,
                                                int totalRounds, boolean playerWon, boolean fled) {
        if (totalRounds == 0 || fled || (settlement != null && settlement.getFled())) {
            return false;
        }
        boolean noActionOpportunity = fact != null && fact.getEnteredRoom() && fact.getNoActionOpportunity();
        boolean baseParticipated = fact != null
                ? evaluateParticipation(fact, false)
                : (settlement != null && settlement.getParticipated());
        if (!baseParticipated) {
            return false;
        }
        if (playerWon || noActionOpportunity) {
            return true;
        }
        if (settlement == null) {
            return false;
        }
        return Integer.compareUnsigned(totalRounds, RANKED_MIN_ROUNDS_FOR_LOSS) >= 0
                && Integer.compareUnsigned(settlement.getManualActionRounds(), RANKED_MIN_MANUAL_ROUNDS_FOR_LOSS) >= 0
                && settlement.getEffectiveCombatContribution();
    }

    private static String computeConfigFingerprint(RankedRuleTable rule, List<RankedPresetTable> presetsByIdAsc) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("ranked-v1\n");
        sb.append("rule:id=1,ver=").append(Integer.toUnsignedString(rule.getRuleVersion()))
                .append(",cfg=").append(Integer.toUnsignedString(rule.getBattleConfigId()))
                .append(",cls=").append(Integer.toUnsignedString(rule.getReferenceClassId()))
                .append(",lvl=").append(Integer.toUnsignedString(rule.getCompetitiveLevel()))
                .append(",defc=").append(Integer.toUnsignedString(rule.getDefaultCharacterPresetId()))
                .append(",defp=").append(Integer.toUnsignedString(rule.getDefaultPetPresetId()))
                .append(",bans=").append(Integer.toUnsignedString(rule.getSkillBanSlotsPerTeam()))
                .append(",banmin=").append(Integer.toUnsignedString(rule.getSkillBanMinRatingCenti()))
                .append('\n');
        for (RankedPresetTable p : presetsByIdAsc) {
            sb.append("preset:").append(Integer.toUnsignedString(p.getId()))
                    .append(",kind=").append(Integer.toUnsignedString(p.getRoleKind()))
                    .append(",pet=").append(Integer.toUnsignedString(p.getPetTableId()))
                    .append(",w=").append(Integer.toUnsignedString(p.getWeightCon()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightSpi()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightStr()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightAgi()))
                    .append(",sk=");
            for (int i = 0; i < p.getSkillIdsCount(); i++) {
                if (i > 0) sb.append('|');
                sb.append(Integer.toUnsignedString(p.getSkillIds(i)));
            }
            sb.append(",pe=");
            int pairCount = Math.min(p.getPetEntryIdsCount(), p.getPetEntryPointsCount());
            for (int i = 0; i < pairCount; i++) {
                if (i > 0) sb.append('|');
                sb.append(Integer.toUnsignedString(p.getPetEntryIds(i)))
                        .append(':')
                        .append(Integer.toUnsignedString(p.getPetEntryPoints(i)));
            }
            sb.append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public RankedRuleTable rule() {
        return rule;
    }

    public int ruleVersion() {
        return rule.getRuleVersion();
    }

    public int winMilestoneMatches() {
        return rule.getMilestoneWinMatches() > 0 ? rule.getMilestoneWinMatches() : 6;
    }

    public List<RankedPresetView> presetViews() {
        return presetViews;
    }

    public boolean isValidPair(int characterPresetId, int petPresetId) {
        RankedPresetTable ch = presetsById.get(characterPresetId);
        RankedPresetTable pet = presetsById.get(petPresetId);
        return ch != null && ch.getRoleKind() == ROLE_KIND_CHARACTER
                && pet != null && pet.getRoleKind() == ROLE_KIND_PET;
    }

    /** 硬上限排位分差窗口（永不退化为 +Inf）。 */
    public int spreadPoints(long waitedSeconds) {
        if (waitedSeconds < Integer.toUnsignedLong(rule.getSpreadTier1Seconds())) {
            return rule.getSpreadTier1Points();
        }
        if (waitedSeconds < Integer.toUnsignedLong(rule.getSpreadTier2Seconds())) {
            return rule.getSpreadTier2Points();
        }
        return rule.getSpreadTier3Points();
    }

    /** 服务端原精度段位映射；未亮段调用方返回 UNSPECIFIED。 */
    public RankedTier tierFor(long ratingCenti, boolean qualified) {
        if (!qualified) {
            return RankedTier.RANKED_TIER_UNSPECIFIED;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierWushuangCenti())) {
            return RankedTier.RANKED_TIER_WUSHUANG;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierTianshuCenti())) {
            return RankedTier.RANKED_TIER_TIANSHU;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierYunzhangCenti())) {
            return RankedTier.RANKED_TIER_YUNZHANG;
        }
        if (ratingCenti >= Integer.toUnsignedLong(rule.getTierQingfengCenti())) {
            return RankedTier.RANKED_TIER_QINGFENG;
        }
        return RankedTier.RANKED_TIER_CHUSHI;
    }

    private static RankedPresetView toView(RankedPresetTable row) {
        return RankedPresetView.newBuilder()
                .setPresetId(row.getId())
                .setRoleKind(row.getRoleKind())
                .setCode(row.getCode())
                .setName(row.getName())
                .setRoleTag(row.getRoleTag())
                .setPetTableId(row.getPetTableId())
                .addWeights(row.getWeightCon())
                .addWeights(row.getWeightSpi())
                .addWeights(row.getWeightStr())
                .addWeights(row.getWeightAgi())
                .addAllSkillIds(row.getSkillIdsList())
                .addAllPetEntryIds(row.getPetEntryIdsList())
                .addAllPetEntryPoints(row.getPetEntryPointsList())
                .setSort(row.getSort())
                .setDesc(row.getDesc())
                .build();
    }
}
