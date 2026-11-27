package com.game.team.rules;

import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.RankedPresetTable;
import com.game.table.RankedRuleTable;
import com.game.table.TrialJournalEntryTable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 队伍排位预设校验与试炼誓约准入规则（对齐 {@code RankedRule} id=1、{@code RankedPreset} 与 {@code TrialJournalEntry}）。 */
public final class TeamRankedRules {

    public static final int ROLE_KIND_CHARACTER = 1;
    public static final int ROLE_KIND_PET = 2;
    public static final int DEFAULT_RULE_VERSION = 1;
    public static final int TRIAL_VOW_MASK_ALL = 0x0F;
    private static final TeamRankedRules DEFAULTS = new TeamRankedRules(
            DEFAULT_RULE_VERSION, Set.of(101, 102, 103), Set.of(201, 202, 203), Set.of());

    private final int ruleVersion;
    private final Set<Integer> characterPresets;
    private final Set<Integer> petPresets;
    private final Set<Integer> vowSupportedDungeons;

    public TeamRankedRules(int ruleVersion, Set<Integer> characterPresets, Set<Integer> petPresets) {
        this(ruleVersion, characterPresets, petPresets, Set.of());
    }

    public TeamRankedRules(int ruleVersion, Set<Integer> characterPresets, Set<Integer> petPresets,
                           Set<Integer> vowSupportedDungeons) {
        if (ruleVersion <= 0) {
            throw new IllegalArgumentException("ruleVersion must be positive: " + ruleVersion);
        }
        this.ruleVersion = ruleVersion;
        this.characterPresets = Set.copyOf(Objects.requireNonNull(characterPresets, "characterPresets"));
        this.petPresets = Set.copyOf(Objects.requireNonNull(petPresets, "petPresets"));
        this.vowSupportedDungeons = Set.copyOf(Objects.requireNonNull(vowSupportedDungeons, "vowSupportedDungeons"));
    }

    public static TeamRankedRules defaults() {
        return DEFAULTS;
    }

    public static TeamRankedRules from(ConfigTables config) {
        Objects.requireNonNull(config, "config");
        RankedRuleTable rule = config.rankedRule().find(1)
                .orElseThrow(() -> new IllegalStateException("RankedRule 表缺少 id=1 规则行"));
        Set<Integer> chars = new HashSet<>();
        Set<Integer> pets = new HashSet<>();
        for (RankedPresetTable row : config.rankedPreset().all()) {
            if (row.getRoleKind() == ROLE_KIND_CHARACTER) {
                chars.add(row.getId());
            } else if (row.getRoleKind() == ROLE_KIND_PET) {
                pets.add(row.getId());
            }
        }
        Map<String, TrialJournalEntryTable> permanentBossEntries = new HashMap<>();
        for (TrialJournalEntryTable e : config.trialJournalEntry().all()) {
            if (e.getEnabled() && e.getSeasonId() == 0 && e.getSourceKind() == 1) {
                permanentBossEntries.put(e.getDifficulty() + ":" + e.getVariantKey(), e);
            }
        }
        Set<Integer> vowDungeons = new HashSet<>();
        for (DungeonTable d : config.dungeon().all()) {
            String variant = permanentBossVariant(d.getBossTrialKind());
            if (variant != null && permanentBossEntries.containsKey(d.getDifficulty() + ":" + variant)) {
                vowDungeons.add(d.getId());
            }
        }
        return new TeamRankedRules(rule.getRuleVersion(), chars, pets, vowDungeons);
    }

    private static String permanentBossVariant(int bossTrialKind) {
        return switch (bossTrialKind) {
            case 1 -> "rejuvenation";
            case 2 -> "bulwark";
            case 3 -> "barrier";
            case 4 -> "venom";
            case 8 -> "dual_guard";
            case 12 -> "wild_wind";
            case 13 -> "wild_bell";
            case 14 -> "wild_tide";
            default -> null;
        };
    }

    public static boolean isValidTrialVowMask(int mask) {
        return (mask & ~TRIAL_VOW_MASK_ALL) == 0;
    }

    public boolean supportsTrialVows(int battleConfigId) {
        return vowSupportedDungeons.contains(battleConfigId);
    }

    public int ruleVersion() {
        return ruleVersion;
    }

    /** 校验人物预设、宝宝预设与规则版本是否同时合法。 */
    public boolean isValidSelection(int characterPresetId, int petPresetId, int requestedRuleVersion) {
        return requestedRuleVersion == ruleVersion
                && characterPresets.contains(characterPresetId)
                && petPresets.contains(petPresetId);
    }
}
