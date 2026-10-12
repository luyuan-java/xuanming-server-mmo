package com.game.scene.battle;

import com.game.battle.engine.BattleRules;
import com.game.battle.engine.BattleTableFingerprint;
import com.game.table.ClassTable;
import com.game.table.ConfigTables;
import com.game.table.ItemTable;
import com.game.table.SkillTable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * scene 出战斗快照、应用结算要用的配表视图（启动时从同一份配表快照建一次；scene 没有表热更）：
 * 战斗配表指纹（{@code BattleTableFingerprint.compute}，快照与应答两处同值，§7.5 第 5 步）、技能可施放判据
 * （{@code BattleRules.isTurnBattleCastableSkill}，与引擎共用，engine-spec D7）、道具 {@code battle_usable}（快照与结算消耗共用一个判据，
 * 基线 {@code IsBattleUsableItem}）、职业初值（快照的护甲 / 力量 / 暴击 / 抗性，D8）。不可变、线程安全。
 *
 * @param fingerprint 战斗配表指纹（32 位小写 hex）
 */
public record SceneBattleTables(String fingerprint, Set<Integer> skills, Set<Integer> castableSkills,
                                Set<Integer> battleUsableItems, Map<Integer, ClassTable> classes) {

    public SceneBattleTables {
        skills = Set.copyOf(skills);
        castableSkills = Set.copyOf(castableSkills);
        battleUsableItems = Set.copyOf(battleUsableItems);
        classes = Map.copyOf(classes);
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
        return new SceneBattleTables(BattleTableFingerprint.compute(tables), skills, castable, usable, classes);
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
