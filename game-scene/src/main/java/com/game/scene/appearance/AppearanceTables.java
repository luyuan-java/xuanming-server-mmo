package com.game.scene.appearance;

import com.game.table.AppearanceRuleTable;
import com.game.table.AppearanceSeriesTable;
import com.game.table.CharacterAppearanceTable;
import com.game.table.ConfigTables;
import com.game.table.PetCosmeticTable;
import com.game.table.SkillVfxCosmeticTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色形象切换、永久收藏系列、灵宠幻化、技能特效与 1 小时免费试用的不可变配表视图
 * （来自 {@code CharacterAppearance}、{@code AppearanceSeries}、{@code AppearanceRule}、{@code PetCosmetic} 与 {@code SkillVfxCosmetic}）。
 *
 * <p>位索引按永久主键 {@code id - 1} 计算（与 {@code *_mapping.json} 一致）。
 */
public final class AppearanceTables {

    public static final int MIN_TRIAL_DURATION_SECONDS = 60;
    public static final int MAX_TRIAL_DURATION_SECONDS = 86_400;
    public static final int MAX_CARD_ITEM_COUNT = 99;
    public static final long MAX_MALL_PRICE_FEN = 1_000_000L;

    public record Rule(int cardItemId, int cardItemCount, int trialDurationSeconds, long mallSkuId, long mallPriceFen) { }

    public record Series(int seriesId, String seriesKey, String name, int sortOrder,
                         String completionBadgeName, String epilogueTitle) { }

    public record Entry(int configId, int bitIndex, String appearanceId, String name, int sortOrder,
                        boolean starterCreatable, boolean cardUnlockable, int seriesId) { }

    public record PetCosmetic(int configId, int bitIndex, String cosmeticKey, String name,
                              int targetPetTableId, int visualPetTableId, String visualArtKey,
                              int sortOrder, long mallSkuId, String acquireHint, String desc) { }

    public record SkillVfxCosmetic(int configId, int bitIndex, String vfxKey, String name,
                                   int skillTableId, String fxId, String projectileFxId,
                                   String telegraphFxId, String tintHex, int sortOrder,
                                   long mallSkuId, String acquireHint, String desc) { }

    private final Rule rule;
    private final List<Entry> enabledEntries;
    private final Map<String, Entry> enabledByAppearanceId;
    private final Map<String, Entry> allByAppearanceId;
    private final List<Series> enabledSeries;
    private final List<PetCosmetic> enabledPetCosmetics;
    private final Map<Integer, PetCosmetic> enabledPetCosmeticsById;
    private final List<SkillVfxCosmetic> enabledSkillVfxCosmetics;
    private final Map<Integer, SkillVfxCosmetic> enabledSkillVfxCosmeticsById;

    private AppearanceTables(Rule rule, List<Entry> enabledEntries, Map<String, Entry> enabledByAppearanceId,
                             Map<String, Entry> allByAppearanceId, List<Series> enabledSeries,
                             List<PetCosmetic> enabledPetCosmetics, Map<Integer, PetCosmetic> enabledPetCosmeticsById,
                             List<SkillVfxCosmetic> enabledSkillVfxCosmetics,
                             Map<Integer, SkillVfxCosmetic> enabledSkillVfxCosmeticsById) {
        this.rule = rule;
        this.enabledEntries = enabledEntries;
        this.enabledByAppearanceId = enabledByAppearanceId;
        this.allByAppearanceId = allByAppearanceId;
        this.enabledSeries = enabledSeries;
        this.enabledPetCosmetics = enabledPetCosmetics;
        this.enabledPetCosmeticsById = enabledPetCosmeticsById;
        this.enabledSkillVfxCosmetics = enabledSkillVfxCosmetics;
        this.enabledSkillVfxCosmeticsById = enabledSkillVfxCosmeticsById;
    }

    public static AppearanceTables from(ConfigTables tables) {
        List<AppearanceRuleTable> rules = tables.appearanceRule().all();
        if (rules.isEmpty()) {
            throw new IllegalStateException("配置表 AppearanceRule 为空");
        }
        AppearanceRuleTable r = rules.getFirst();
        Rule parsedRule = new Rule(
                r.getCardItemId(),
                r.getCardItemCount(),
                r.getTrialDurationSeconds(),
                r.getMallSkuId(),
                r.getMallPriceFen());
        validateRule(parsedRule, tables);

        List<Series> seriesList = new ArrayList<>();
        Set<Integer> enabledSeriesIds = new HashSet<>();
        Set<String> enabledSeriesKeys = new HashSet<>();
        for (AppearanceSeriesTable s : tables.appearanceSeries().all()) {
            if (s.getId() <= 0 || s.getSeriesKey().isBlank() || s.getName().isBlank()) {
                throw new IllegalStateException("AppearanceSeries 非法行: id=" + s.getId());
            }
            if (s.getEnabled() == 0) {
                continue;
            }
            if (!enabledSeriesKeys.add(s.getSeriesKey())) {
                throw new IllegalStateException("AppearanceSeries 重复启用 series_key=" + s.getSeriesKey());
            }
            enabledSeriesIds.add(s.getId());
            seriesList.add(new Series(
                    s.getId(),
                    s.getSeriesKey(),
                    s.getName(),
                    s.getSortOrder(),
                    s.getCompletionBadgeName(),
                    s.getEpilogueTitle()));
        }
        seriesList.sort(Comparator.comparingInt(Series::sortOrder).thenComparingInt(Series::seriesId));

        List<Entry> entries = new ArrayList<>();
        Map<String, Entry> byAppearanceId = new LinkedHashMap<>();
        Map<String, Entry> allById = new LinkedHashMap<>();
        int starterCount = 0;
        for (CharacterAppearanceTable row : tables.characterAppearance().all()) {
            if (row.getId() <= 0) {
                throw new IllegalStateException("CharacterAppearance.id 必须为正数: " + row.getId());
            }
            if (row.getAppearanceId().isBlank()) {
                throw new IllegalStateException("CharacterAppearance.appearance_id 不能为空: id=" + row.getId());
            }
            if (!biographyFieldsValid(row.getArchiveName(), row.getNickname(), row.getBiographySummary(),
                    row.getStoryTitle(), row.getStoryBody())) {
                throw new IllegalStateException("CharacterAppearance 人物志字段不完整: id=" + row.getId());
            }
            Entry entry = new Entry(
                    row.getId(),
                    row.getId() - 1,
                    row.getAppearanceId(),
                    row.getName(),
                    row.getSortOrder(),
                    row.getStarterCreatable() != 0,
                    row.getCardUnlockable() != 0,
                    row.getSeriesId());
            allById.putIfAbsent(entry.appearanceId(), entry);
            if (row.getEnabled() == 0) {
                continue;
            }
            if (entry.seriesId() != 0 && !enabledSeriesIds.contains(entry.seriesId())) {
                throw new IllegalStateException("CharacterAppearance id=" + entry.configId()
                        + " 引用了不存在或未启用的 series_id=" + entry.seriesId());
            }
            if (byAppearanceId.putIfAbsent(entry.appearanceId(), entry) != null) {
                throw new IllegalStateException("CharacterAppearance 重复启用 appearance_id=" + entry.appearanceId());
            }
            if (entry.starterCreatable()) {
                starterCount++;
            }
            entries.add(entry);
        }
        if (entries.isEmpty() || starterCount == 0) {
            throw new IllegalStateException("配置表 CharacterAppearance 缺少启用或初始建角形象: enabled="
                    + entries.size() + " starter=" + starterCount);
        }
        entries.sort(Comparator.comparingInt(Entry::sortOrder).thenComparingInt(Entry::configId));

        List<PetCosmetic> petCosmetics = new ArrayList<>();
        Map<Integer, PetCosmetic> petCosmeticsById = new LinkedHashMap<>();
        Set<String> petCosmeticKeys = new HashSet<>();
        for (PetCosmeticTable row : tables.petCosmetic().all()) {
            if (row.getId() <= 0 || row.getCosmeticKey().isBlank() || row.getName().isBlank()) {
                throw new IllegalStateException("PetCosmetic 非法行: id=" + row.getId());
            }
            if (row.getVisualArtKey().isBlank() && row.getVisualPetTableId() == 0) {
                throw new IllegalStateException("PetCosmetic 缺少视觉资源键或复用灵宠 ID: id=" + row.getId());
            }
            if (row.getTargetPetTableId() != 0 && !tables.pet().contains(row.getTargetPetTableId())) {
                throw new IllegalStateException("PetCosmetic target_pet_table_id 不存在: id=" + row.getId());
            }
            if (row.getVisualPetTableId() != 0 && !tables.pet().contains(row.getVisualPetTableId())) {
                throw new IllegalStateException("PetCosmetic visual_pet_table_id 不存在: id=" + row.getId());
            }
            if (!petCosmeticKeys.add(row.getCosmeticKey())) {
                throw new IllegalStateException("PetCosmetic 重复 cosmetic_key=" + row.getCosmeticKey());
            }
            if (row.getEnabled() == 0) {
                continue;
            }
            PetCosmetic item = new PetCosmetic(
                    row.getId(), row.getId() - 1, row.getCosmeticKey(), row.getName(),
                    row.getTargetPetTableId(), row.getVisualPetTableId(), row.getVisualArtKey(),
                    row.getSortOrder(), row.getMallSkuId(), row.getAcquireHint(), row.getDesc());
            petCosmetics.add(item);
            petCosmeticsById.put(item.configId(), item);
        }
        petCosmetics.sort(Comparator.comparingInt(PetCosmetic::sortOrder).thenComparingInt(PetCosmetic::configId));

        List<SkillVfxCosmetic> skillVfxs = new ArrayList<>();
        Map<Integer, SkillVfxCosmetic> skillVfxsById = new LinkedHashMap<>();
        Set<String> skillVfxKeys = new HashSet<>();
        for (SkillVfxCosmeticTable row : tables.skillVfxCosmetic().all()) {
            if (row.getId() <= 0 || row.getVfxKey().isBlank() || row.getName().isBlank()
                    || row.getFxId().isBlank() || row.getSkillTableId() <= 0) {
                throw new IllegalStateException("SkillVfxCosmetic 非法行: id=" + row.getId());
            }
            if (!tables.skill().contains(row.getSkillTableId())) {
                throw new IllegalStateException("SkillVfxCosmetic skill_table_id 不存在: id=" + row.getId());
            }
            if (!validTintHex(row.getTintHex())) {
                throw new IllegalStateException("SkillVfxCosmetic tint_hex 非法: id=" + row.getId());
            }
            if (!skillVfxKeys.add(row.getVfxKey())) {
                throw new IllegalStateException("SkillVfxCosmetic 重复 vfx_key=" + row.getVfxKey());
            }
            if (row.getEnabled() == 0) {
                continue;
            }
            SkillVfxCosmetic item = new SkillVfxCosmetic(
                    row.getId(), row.getId() - 1, row.getVfxKey(), row.getName(),
                    row.getSkillTableId(), row.getFxId(), row.getProjectileFxId(),
                    row.getTelegraphFxId(), row.getTintHex(), row.getSortOrder(),
                    row.getMallSkuId(), row.getAcquireHint(), row.getDesc());
            skillVfxs.add(item);
            skillVfxsById.put(item.configId(), item);
        }
        skillVfxs.sort(Comparator.comparingInt(SkillVfxCosmetic::sortOrder).thenComparingInt(SkillVfxCosmetic::configId));

        return new AppearanceTables(parsedRule, List.copyOf(entries), Map.copyOf(byAppearanceId),
                Map.copyOf(allById), List.copyOf(seriesList),
                List.copyOf(petCosmetics), Map.copyOf(petCosmeticsById),
                List.copyOf(skillVfxs), Map.copyOf(skillVfxsById));
    }

    public static boolean validTintHex(String hex) {
        if (hex == null || hex.isEmpty()) {
            return true;
        }
        if (hex.length() != 7 || hex.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < 7; i++) {
            char c = hex.charAt(i);
            boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 人物志五列契约校验（与 C++ {@code player_appearance::BiographyFieldsValid} 同口径）：
     * 五列全空合法（无人物志）；只要任一列非空白，四项核心字段（档案名、小传、篇名、正文）必须同时非空白。
     */
    public static boolean biographyFieldsValid(String archiveName, String nickname, String biographySummary,
                                               String storyTitle, String storyBody) {
        boolean hasArchive = archiveName != null && !archiveName.isBlank();
        boolean hasNickname = nickname != null && !nickname.isBlank();
        boolean hasSummary = biographySummary != null && !biographySummary.isBlank();
        boolean hasTitle = storyTitle != null && !storyTitle.isBlank();
        boolean hasBody = storyBody != null && !storyBody.isBlank();
        if (!hasArchive && !hasNickname && !hasSummary && !hasTitle && !hasBody) {
            return true;
        }
        return hasArchive && hasSummary && hasTitle && hasBody;
    }

    private static void validateRule(Rule rule, ConfigTables tables) {
        if (rule.cardItemId() <= 0 || !tables.item().contains(rule.cardItemId())) {
            throw new IllegalStateException("AppearanceRule.card_item_id 不存在: " + rule.cardItemId());
        }
        if (rule.cardItemCount() <= 0 || rule.cardItemCount() > MAX_CARD_ITEM_COUNT) {
            throw new IllegalStateException("AppearanceRule.card_item_count 越界: " + rule.cardItemCount());
        }
        if (rule.trialDurationSeconds() < MIN_TRIAL_DURATION_SECONDS
                || rule.trialDurationSeconds() > MAX_TRIAL_DURATION_SECONDS) {
            throw new IllegalStateException("AppearanceRule.trial_duration_seconds 越界: " + rule.trialDurationSeconds());
        }
        if (rule.mallPriceFen() < 0 || rule.mallPriceFen() > MAX_MALL_PRICE_FEN) {
            throw new IllegalStateException("AppearanceRule.mall_price_fen 越界: " + rule.mallPriceFen());
        }
    }

    public Rule rule() {
        return rule;
    }

    public List<Entry> enabledEntries() {
        return enabledEntries;
    }

    public Entry findEnabled(String appearanceId) {
        return appearanceId == null ? null : enabledByAppearanceId.get(appearanceId);
    }

    public Entry findAny(String appearanceId) {
        return appearanceId == null ? null : allByAppearanceId.get(appearanceId);
    }

    public List<Series> enabledSeries() {
        return enabledSeries;
    }

    public List<PetCosmetic> enabledPetCosmetics() {
        return enabledPetCosmetics;
    }

    public PetCosmetic findEnabledPetCosmetic(int configId) {
        return enabledPetCosmeticsById.get(configId);
    }

    public List<SkillVfxCosmetic> enabledSkillVfxCosmetics() {
        return enabledSkillVfxCosmetics;
    }

    public SkillVfxCosmetic findEnabledSkillVfxCosmetic(int configId) {
        return enabledSkillVfxCosmeticsById.get(configId);
    }
}
