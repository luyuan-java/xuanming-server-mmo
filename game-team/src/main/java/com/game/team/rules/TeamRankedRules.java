package com.game.team.rules;

import com.game.table.ConfigTables;
import com.game.table.RankedPresetTable;
import com.game.table.RankedRuleTable;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 队伍排位预设校验规则（对齐 {@code RankedRule} id=1 与 {@code RankedPreset} 正式预设集合）。 */
public final class TeamRankedRules {

    public static final int ROLE_KIND_CHARACTER = 1;
    public static final int ROLE_KIND_PET = 2;
    public static final int DEFAULT_RULE_VERSION = 1;
    private static final TeamRankedRules DEFAULTS = new TeamRankedRules(
            DEFAULT_RULE_VERSION, Set.of(101, 102, 103), Set.of(201, 202, 203));

    private final int ruleVersion;
    private final Set<Integer> characterPresets;
    private final Set<Integer> petPresets;

    public TeamRankedRules(int ruleVersion, Set<Integer> characterPresets, Set<Integer> petPresets) {
        if (ruleVersion <= 0) {
            throw new IllegalArgumentException("ruleVersion must be positive: " + ruleVersion);
        }
        this.ruleVersion = ruleVersion;
        this.characterPresets = Set.copyOf(Objects.requireNonNull(characterPresets, "characterPresets"));
        this.petPresets = Set.copyOf(Objects.requireNonNull(petPresets, "petPresets"));
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
        return new TeamRankedRules(rule.getRuleVersion(), chars, pets);
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
