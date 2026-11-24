package com.game.scene.season;

import com.game.battle.engine.SeasonRules;
import com.game.scene.skill.ClassSkillCatalog;
import com.game.table.ConfigTables;
import com.game.table.SeasonRuleTable;
import com.game.table.SeasonSealTable;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 赛季规则、赛季印目录与赛季限定技能的不可变配表视图。
 * 校验口径与 {@link SeasonRules} 一致：若配置表存在重叠启用窗口、越界轻属性或缺失默认印，则 fail-closed。
 */
public final class SeasonTables {

    private static final Logger log = LoggerFactory.getLogger(SeasonTables.class);

    private final boolean valid;
    private final List<SeasonRuleTable> rules;
    private final Map<Integer, SeasonSealTable> sealsById;
    private final Map<Integer, List<SeasonSealTable>> sealsBySeason;
    private final Map<Integer, List<SkillTable>> skillsBySeason;

    public SeasonTables(List<SeasonRuleTable> rules, List<SeasonSealTable> seals, List<SkillTable> skills) {
        this.rules = List.copyOf(rules);
        Map<Integer, SeasonSealTable> byId = new LinkedHashMap<>();
        Map<Integer, List<SeasonSealTable>> bySeason = new HashMap<>();
        for (SeasonSealTable seal : seals) {
            byId.putIfAbsent(seal.getId(), seal);
            bySeason.computeIfAbsent(seal.getSeasonId(), ignored -> new ArrayList<>()).add(seal);
        }
        bySeason.values().forEach(list -> list.sort(Comparator.comparing(SeasonSealTable::getId, Integer::compareUnsigned)));
        this.sealsById = Collections.unmodifiableMap(byId);
        Map<Integer, List<SeasonSealTable>> immutableBySeason = new HashMap<>();
        bySeason.forEach((k, v) -> immutableBySeason.put(k, List.copyOf(v)));
        this.sealsBySeason = Collections.unmodifiableMap(immutableBySeason);

        Map<Integer, List<SkillTable>> seasonalSkills = new HashMap<>();
        for (SkillTable skill : skills) {
            if (skill.getSeasonId() != 0 && ClassSkillCatalog.forSubject(skill, 1)) {
                seasonalSkills.computeIfAbsent(skill.getSeasonId(), ignored -> new ArrayList<>()).add(skill);
            }
        }
        seasonalSkills.values().forEach(list -> list.sort(Comparator.comparing(SkillTable::getId, Integer::compareUnsigned)));
        Map<Integer, List<SkillTable>> immutableSkills = new HashMap<>();
        seasonalSkills.forEach((k, v) -> immutableSkills.put(k, List.copyOf(v)));
        this.skillsBySeason = Collections.unmodifiableMap(immutableSkills);
        this.valid = validate();
    }

    public static SeasonTables from(ConfigTables tables) {
        return new SeasonTables(tables.seasonRule().all(), tables.seasonSeal().all(), tables.skill().all());
    }

    public static SeasonTables empty() {
        return new SeasonTables(List.of(), List.of(), List.of());
    }

    public boolean isValid() {
        return valid;
    }

    public SeasonRuleTable activeSeason(long nowMs) {
        if (!valid) {
            return null;
        }
        SeasonRuleTable active = null;
        for (SeasonRuleTable row : rules) {
            if (!SeasonRules.isWindowActive(row.getStartsAtMs(), row.getEndsAtMs(), row.getEnabled(), nowMs)) {
                continue;
            }
            if (SeasonRules.validateRule(toParams(row)) != null
                    || validSealForSeason(row.getId(), row.getDefaultSealId()) == null) {
                return null;
            }
            if (active != null) {
                return null;
            }
            active = row;
        }
        return active;
    }

    public SeasonSealTable validSealForSeason(int seasonId, int sealId) {
        if (seasonId == 0 || sealId == 0) {
            return null;
        }
        SeasonSealTable row = sealsById.get(sealId);
        if (row == null || row.getSeasonId() != seasonId || !isValidSeal(row)) {
            return null;
        }
        return row;
    }

    public List<SeasonSealTable> validSealsOf(int seasonId) {
        List<SeasonSealTable> all = sealsBySeason.getOrDefault(seasonId, List.of());
        List<SeasonSealTable> out = new ArrayList<>(all.size());
        for (SeasonSealTable row : all) {
            if (isValidSeal(row)) {
                out.add(row);
            }
        }
        return out;
    }

    public List<SkillTable> seasonSkillsOf(int seasonId) {
        return skillsBySeason.getOrDefault(seasonId, List.of());
    }

    static SeasonRules.RuleParams toParams(SeasonRuleTable row) {
        return new SeasonRules.RuleParams(
                row.getId(), row.getStartsAtMs(), row.getEndsAtMs(), row.getEnabled(),
                row.getSealMaxPoints(), row.getPveWinPoints(), row.getPvpWinPoints(), row.getPvpOtherPoints(),
                row.getMaxHealthBpsCap(), row.getDefenseBpsCap(), row.getCatchupCurrencyType(),
                row.getCatchupPricePerPoint(), row.getDefaultSealId());
    }

    static boolean isValidSeal(SeasonSealTable seal) {
        return SeasonRules.validSealEffect(seal.getSeasonId(), seal.getId(), seal.getEffectId(),
                seal.getBonusBps(), seal.getCapBps(), seal.getPerRoundLimit(),
                seal.getBattleLimit(), seal.getActiveRounds(), seal.getHealthThresholdBps());
    }

    private boolean validate() {
        if (rules.isEmpty()) {
            if (!sealsById.isEmpty()) {
                log.error("season_tables_invalid reason=seals_without_rules");
                return false;
            }
            return true;
        }
        Map<Integer, SeasonRuleTable> rulesById = new HashMap<>();
        List<SeasonRuleTable> enabled = new ArrayList<>();
        for (SeasonRuleTable rule : rules) {
            String err = SeasonRules.validateRule(toParams(rule));
            if (err != null) {
                log.error("season_tables_invalid season_id={} error={}", rule.getId(), err);
                return false;
            }
            if (validSealForSeason(rule.getId(), rule.getDefaultSealId()) == null) {
                log.error("season_tables_invalid season_id={} error=missing_default_seal seal_id={}",
                        rule.getId(), rule.getDefaultSealId());
                return false;
            }
            rulesById.put(rule.getId(), rule);
            if (rule.getEnabled()) {
                enabled.add(rule);
            }
        }
        for (int i = 0; i < enabled.size(); i++) {
            for (int j = i + 1; j < enabled.size(); j++) {
                SeasonRuleTable a = enabled.get(i);
                SeasonRuleTable b = enabled.get(j);
                if (SeasonRules.windowsOverlap(a.getStartsAtMs(), a.getEndsAtMs(), b.getStartsAtMs(), b.getEndsAtMs())) {
                    log.error("season_tables_invalid error=overlapping_seasons a={} b={}", a.getId(), b.getId());
                    return false;
                }
            }
        }
        for (SeasonSealTable seal : sealsById.values()) {
            if (!rulesById.containsKey(seal.getSeasonId()) || !isValidSeal(seal)) {
                log.error("season_tables_invalid error=invalid_seal seal_id={} season_id={}",
                        seal.getId(), seal.getSeasonId());
                return false;
            }
        }
        return true;
    }
}
