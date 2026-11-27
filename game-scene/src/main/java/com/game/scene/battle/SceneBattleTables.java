package com.game.scene.battle;

import com.game.battle.engine.BattleRules;
import com.game.battle.engine.BattleTableFingerprint;
import com.game.table.BuffTable;
import com.game.table.ClassTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.ItemTable;
import com.game.table.SkillTable;
import com.game.table.SkillVfxCosmeticTable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * scene 出战斗快照、应用结算要用的配表视图（启动时从同一份配表快照建一次；scene 没有表热更）：
 * 战斗配表指纹（{@code BattleTableFingerprint.compute}，快照与应答两处同值，§7.5 第 5 步）、技能可施放判据
 * （{@code BattleRules.isTurnBattleCastableSkill}，与引擎共用，engine-spec D7）、道具 {@code battle_usable}（快照与结算消耗共用一个判据，
 * 基线 {@code IsBattleUsableItem}）、职业初值（快照的护甲 / 力量 / 暴击 / 抗性，D8）及副本元信息（首领试炼困难门禁与普通首通判定）。不可变、线程安全。
 *
 * @param fingerprint 战斗配表指纹（32 位小写 hex）
 */
public record SceneBattleTables(String fingerprint, Set<Integer> skills, Set<Integer> castableSkills,
                                Set<Integer> battleUsableItems, Map<Integer, ClassTable> classes,
                                RankedPresetRules.Tables ranked, Map<Integer, DungeonTable> dungeons,
                                Map<Integer, SkillVfxCosmeticTable> skillVfxCosmetics,
                                Map<Integer, BuffTable> buffs) {

    public SceneBattleTables(String fingerprint, Set<Integer> skills, Set<Integer> castableSkills,
                             Set<Integer> battleUsableItems, Map<Integer, ClassTable> classes) {
        this(fingerprint, skills, castableSkills, battleUsableItems, classes, null, Map.of(), Map.of(), Map.of());
    }

    public SceneBattleTables(String fingerprint, Set<Integer> skills, Set<Integer> castableSkills,
                             Set<Integer> battleUsableItems, Map<Integer, ClassTable> classes,
                             RankedPresetRules.Tables ranked) {
        this(fingerprint, skills, castableSkills, battleUsableItems, classes, ranked, Map.of(), Map.of(), Map.of());
    }

    public SceneBattleTables(String fingerprint, Set<Integer> skills, Set<Integer> castableSkills,
                             Set<Integer> battleUsableItems, Map<Integer, ClassTable> classes,
                             RankedPresetRules.Tables ranked, Map<Integer, DungeonTable> dungeons) {
        this(fingerprint, skills, castableSkills, battleUsableItems, classes, ranked, dungeons, Map.of(), Map.of());
    }

    public SceneBattleTables(String fingerprint, Set<Integer> skills, Set<Integer> castableSkills,
                             Set<Integer> battleUsableItems, Map<Integer, ClassTable> classes,
                             RankedPresetRules.Tables ranked, Map<Integer, DungeonTable> dungeons,
                             Map<Integer, SkillVfxCosmeticTable> skillVfxCosmetics) {
        this(fingerprint, skills, castableSkills, battleUsableItems, classes, ranked, dungeons, skillVfxCosmetics, Map.of());
    }

    public SceneBattleTables {
        skills = Set.copyOf(skills);
        castableSkills = Set.copyOf(castableSkills);
        battleUsableItems = Set.copyOf(battleUsableItems);
        classes = Map.copyOf(classes);
        dungeons = dungeons == null ? Map.of() : Map.copyOf(dungeons);
        skillVfxCosmetics = skillVfxCosmetics == null ? Map.of() : Map.copyOf(skillVfxCosmetics);
        buffs = buffs == null ? Map.of() : Map.copyOf(buffs);
    }

    public static SceneBattleTables from(ConfigTables tables) {
        Set<Integer> skills = new HashSet<>();
        Set<Integer> castable = new HashSet<>();
        for (SkillTable row : tables.skill().all()) {
            skills.add(row.getId());
            if (BattleRules.isTurnBattleCastableSkill(row)) {
                castable.add(row.getId());
            }
        }
        Set<Integer> usable = new HashSet<>();
        for (ItemTable row : tables.item().all()) {
            if (row.getBattleUsable() != 0) {
                usable.add(row.getId());
            }
        }
        Map<Integer, ClassTable> classes = new HashMap<>();
        for (ClassTable row : tables.classTable().all()) {
            classes.put(row.getId(), row);
        }
        Map<Integer, DungeonTable> dungeons = new HashMap<>();
        for (DungeonTable row : tables.dungeon().all()) {
            dungeons.put(row.getId(), row);
        }
        Map<Integer, SkillVfxCosmeticTable> skillVfxs = new HashMap<>();
        for (SkillVfxCosmeticTable row : tables.skillVfxCosmetic().all()) {
            if (row.getEnabled() != 0 && row.getId() > 0) {
                skillVfxs.put(row.getId(), row);
            }
        }
        Map<Integer, BuffTable> buffs = new HashMap<>();
        for (BuffTable row : tables.buff().all()) {
            if (row.getId() != 0) {
                buffs.put(row.getId(), row);
            }
        }
        return new SceneBattleTables(BattleTableFingerprint.compute(tables), skills, castable, usable, classes,
                RankedPresetRules.Tables.from(tables), dungeons, skillVfxs, buffs);
    }

    /** Buff 表行（用于带进回合战斗的实时 Buff 过滤与剩余时长换算）；没有为 null。 */
    public BuffTable buff(int buffTableId) {
        return buffTableId == 0 ? null : buffs.get(buffTableId);
    }

    /** 已启用的技能特效外观行；未启用或不存在为 null。 */
    public SkillVfxCosmeticTable skillVfxCosmetic(int configId) {
        return skillVfxCosmetics.get(configId);
    }

    /** 副本行（首领试炼/护卫救援/物法易势判定）；没有为 null。 */
    public DungeonTable dungeon(int configId) {
        return dungeons.get(configId);
    }

    /** 技能表里有这一行。 */
    public boolean skillExists(int skillTableId) {
        return skills.contains(skillTableId);
    }

    /** 技能能在回合制战斗里施放（表里没有的按不能）。 */
    public boolean castable(int skillTableId) {
        return castableSkills.contains(skillTableId);
    }

    /** 道具能在战斗里用（{@code ItemTable.battle_usable ≠ 0}；表里没有的按不能）——快照与结算消耗共用（基线 {@code IsBattleUsableItem}）。 */
    public boolean battleUsable(int itemConfigId) {
        return battleUsableItems.contains(itemConfigId);
    }

    /** 职业行；没有为 null。 */
    public ClassTable classRow(int classId) {
        return classes.get(classId);
    }
}
