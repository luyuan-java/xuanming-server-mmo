package com.game.scene.appearance;

import com.game.table.AppearanceRuleTable;
import com.game.table.CharacterAppearanceTable;
import com.game.table.ConfigTables;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色形象切换与 1 小时免费试用的不可变配表视图（来自 {@code CharacterAppearance} 与 {@code AppearanceRule}）。
 *
 * <p>位索引按永久主键 {@code id - 1} 计算（与 {@code characterappearance_mapping.json} 一致：1..11 -> bit 0..10）。
 */
public final class AppearanceTables {

    public static final int MIN_TRIAL_DURATION_SECONDS = 60;
    public static final int MAX_TRIAL_DURATION_SECONDS = 86_400;
    public static final int MAX_CARD_ITEM_COUNT = 99;
    public static final long MAX_MALL_PRICE_FEN = 1_000_000L;

    public record Rule(int cardItemId, int cardItemCount, int trialDurationSeconds, long mallSkuId, long mallPriceFen) { }

    public record Entry(int configId, int bitIndex, String appearanceId, String name, int sortOrder) { }

    private final Rule rule;
    private final List<Entry> enabledEntries;
    private final Map<String, Entry> enabledByAppearanceId;

    private AppearanceTables(Rule rule, List<Entry> enabledEntries, Map<String, Entry> enabledByAppearanceId) {
        this.rule = rule;
        this.enabledEntries = enabledEntries;
        this.enabledByAppearanceId = enabledByAppearanceId;
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

        List<Entry> entries = new ArrayList<>();
        Map<String, Entry> byAppearanceId = new LinkedHashMap<>();
        for (CharacterAppearanceTable row : tables.characterAppearance().all()) {
            if (row.getId() <= 0) {
                throw new IllegalStateException("CharacterAppearance.id 必须为正数: " + row.getId());
            }
            if (row.getAppearanceId().isBlank()) {
                throw new IllegalStateException("CharacterAppearance.appearance_id 不能为空: id=" + row.getId());
            }
            if (row.getEnabled() == 0) {
                continue;
            }
            Entry entry = new Entry(row.getId(), row.getId() - 1, row.getAppearanceId(), row.getName(), row.getSortOrder());
            if (byAppearanceId.putIfAbsent(entry.appearanceId(), entry) != null) {
                throw new IllegalStateException("CharacterAppearance 重复启用 appearance_id=" + entry.appearanceId());
            }
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("配置表 CharacterAppearance 没有启用的形象");
        }
        entries.sort(Comparator.comparingInt(Entry::sortOrder).thenComparingInt(Entry::configId));
        return new AppearanceTables(parsedRule, List.copyOf(entries), Map.copyOf(byAppearanceId));
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
}
