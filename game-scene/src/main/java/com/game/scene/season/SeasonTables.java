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
 * 赛季规则、赛季印目录与赛季限定技能的不可变配表视图（支持固定单季与无限期号自动循环双模式）。
 * 校验口径与 {@link SeasonRules} 一致：若配置表存在非法循环排期、越界轻属性、非法共鸣或缺失默认印，则 fail-closed。
 */
public final class SeasonTables {

    private static final Logger log = LoggerFactory.getLogger(SeasonTables.class);

    public record ActiveSeason(SeasonRuleTable rule, SeasonRules.ResolvedSeason resolved) {
    }

    private final boolean valid;
    private final List<SeasonRuleTable> rules;
    private final List<SeasonRules.RuleParams> ruleParams;
    private final List<Integer> allRuleIdsAsc;
    private final Map<Integer, SeasonRuleTable> rulesById;
    private final Map<Integer, SeasonSealTable> sealsById;
    private final Map<Integer, List<SeasonSealTable>> sealsBySeason;
    private final Map<Integer, List<SkillTable>> skillsBySeason;
    private final Map<Integer, SkillTable> skillsById;

    public SeasonTables(List<SeasonRuleTable> rules, List<SeasonSealTable> seals, List<SkillTable> skills) {
        this.rules = List.copyOf(rules);
        List<SeasonRules.RuleParams> params = new ArrayList<>(rules.size());
        Map<Integer, SeasonRuleTable> ruleMap = new LinkedHashMap<>();
        List<Integer> sortedIds = new ArrayList<>(rules.size());
        for (SeasonRuleTable r : rules) {
            params.add(toParams(r));
            if (ruleMap.putIfAbsent(r.getId(), r) == null && r.getId() != 0) {
                sortedIds.add(r.getId());
            }
        }
        sortedIds.sort(Integer::compareUnsigned);
        this.ruleParams = List.copyOf(params);
        this.allRuleIdsAsc = List.copyOf(sortedIds);
        this.rulesById = Collections.unmodifiableMap(ruleMap);

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
        Map<Integer, SkillTable> skillMap = new HashMap<>();
        for (SkillTable skill : skills) {
            skillMap.putIfAbsent(skill.getId(), skill);
            if (skill.getSeasonId() != 0 && ClassSkillCatalog.forSubject(skill, 1)) {
                seasonalSkills.computeIfAbsent(skill.getSeasonId(), ignored -> new ArrayList<>()).add(skill);
            }
        }
        seasonalSkills.values().forEach(list -> list.sort(Comparator.comparing(SkillTable::getId, Integer::compareUnsigned)));
        Map<Integer, List<SkillTable>> immutableSkills = new HashMap<>();
        seasonalSkills.forEach((k, v) -> immutableSkills.put(k, List.copyOf(v)));
        this.skillsBySeason = Collections.unmodifiableMap(immutableSkills);
        this.skillsById = Collections.unmodifiableMap(skillMap);
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

    public SeasonRuleTable ruleById(int seasonId) {
        return seasonId == 0 ? null : rulesById.get(seasonId);
    }

    public List<Integer> allRuleIdsAsc() {
        return allRuleIdsAsc;
    }

    public ActiveSeason resolveActive(long nowMs) {
        if (!valid) {
            return null;
        }
        SeasonRules.ResolvedSeason resolved = SeasonRules.resolveActiveSeason(ruleParams, nowMs);
        if (!resolved.active() || resolved.invalidConfig()
                || resolved.ruleIndex() < 0 || resolved.ruleIndex() >= rules.size()) {
            return null;
        }
        SeasonRuleTable row = rules.get(resolved.ruleIndex());
        if (validSealForSeason(row.getId(), row.getDefaultSealId()) == null) {
            return null;
        }
        return new ActiveSeason(row, resolved);
    }

    public SeasonRuleTable activeSeason(long nowMs) {
        ActiveSeason active = resolveActive(nowMs);
        return active == null ? null : active.rule();
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
                row.getCatchupPricePerPoint(), row.getDefaultSealId(),
                row.getCycleOrder(), row.getCycleDurationDays());
    }

    static boolean isValidSeal(SeasonSealTable seal) {
        return SeasonRules.validSealDefinition(seal.getSeasonId(), seal.getId(), seal.getEffectId(),
                seal.getBonusBps(), seal.getCapBps(), seal.getPerRoundLimit(),
                seal.getBattleLimit(), seal.getActiveRounds(), seal.getHealthThresholdBps(),
                seal.getResonanceSkillId(), seal.getResonanceKind(), seal.getResonanceParam());
    }

    private boolean validate() {
        if (rules.isEmpty()) {
            if (!sealsById.isEmpty()) {
                log.error("season_tables_invalid reason=seals_without_rules");
                return false;
            }
            return true;
        }
        String scheduleErr = SeasonRules.validateSchedule(ruleParams);
        if (scheduleErr != null) {
            log.error("season_tables_invalid error={}", scheduleErr);
            return false;
        }
        for (SeasonRuleTable rule : rules) {
            if (validSealForSeason(rule.getId(), rule.getDefaultSealId()) == null) {
                log.error("season_tables_invalid season_id={} error=missing_default_seal seal_id={}",
                        rule.getId(), rule.getDefaultSealId());
                return false;
            }
        }
        for (SeasonSealTable seal : sealsById.values()) {
            if (!rulesById.containsKey(seal.getSeasonId()) || !isValidSeal(seal)) {
                log.error("season_tables_invalid error=invalid_seal seal_id={} season_id={}",
                        seal.getId(), seal.getSeasonId());
                return false;
            }
            if (seal.getResonanceKind() != SeasonRules.RESONANCE_NONE && !skillsById.isEmpty()) {
                SkillTable linked = skillsById.get(seal.getResonanceSkillId());
                if (linked == null || linked.getSeasonId() != seal.getSeasonId()) {
                    log.error("season_tables_invalid error=resonance_skill_mismatch seal_id={} season_id={} skill_id={}",
                            seal.getId(), seal.getSeasonId(), seal.getResonanceSkillId());
                    return false;
                }
            }
        }
        return true;
    }
}
