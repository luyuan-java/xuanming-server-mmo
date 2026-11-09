package com.game.scene.skill;

import com.game.battle.engine.BattleRules;
import com.game.battle.engine.ClassSkillRules;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.PlayerSkills;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 不可变技能配置视图；持久值与当前版本的生效投影分开。 */
public final class ClassSkillCatalog {
    private final Map<Integer, SkillTable> skills;
    private final Map<Integer, BuffTable> buffs;

    public ClassSkillCatalog(Collection<SkillTable> skills, Collection<BuffTable> buffs) {
        Map<Integer, SkillTable> rows = new LinkedHashMap<>();
        skills.forEach(row -> rows.put(row.getId(), row));
        this.skills = Map.copyOf(rows);
        Map<Integer, BuffTable> buffRows = new LinkedHashMap<>();
        buffs.forEach(row -> buffRows.put(row.getId(), row));
        this.buffs = Map.copyOf(buffRows);
    }

    public static ClassSkillCatalog from(ConfigTables tables) {
        return new ClassSkillCatalog(tables.skill().all(), tables.buff().all());
    }

    /** 只提供旧通用技能的表适配；正式装配一律从完整 ConfigTables 创建。 */
    public static ClassSkillCatalog generic(Collection<Integer> ids) {
        return new ClassSkillCatalog(ids.stream().filter(id -> id != 0)
                .map(id -> SkillTable.newBuilder().setId(id).build()).toList(), List.of());
    }

    public SkillTable row(int id) { return id == 0 ? null : skills.get(id); }
    public List<SkillTable> classRows(int classId) {
        return skills.values().stream().filter(row -> classId != 0 && row.getClassId() == classId)
                .sorted((a, b) -> Integer.compareUnsigned(a.getId(), b.getId())).toList();
    }

    public int effectiveRank(int classId, int level, PlayerSkills owned, int id) {
        SkillTable row = row(id);
        return row == null || row.getClassId() == 0 || row.getClassId() != classId ? 0
                : ClassSkillRules.effectiveRank(owned.rank(id), PlayerLevels.effective(level),
                        row.getUnlockLevel(), row.getMaxRank());
    }

    public boolean usable(int classId, int level, PlayerSkills owned, int id) {
        SkillTable row = row(id);
        if (row == null || !owned.contains(id)) return false;
        return row.getClassId() == 0 ? classRows(classId).isEmpty() : effectiveRank(classId, level, owned, id) != 0;
    }

    public List<Integer> usableIds(int classId, int level, PlayerSkills owned) {
        return owned.entries().stream().map(entry -> entry.getSkillId())
                .filter(id -> usable(classId, level, owned, id)).toList();
    }

    public void grantSeeds(int classId, int level, PlayerSkills owned, List<Integer> seeds) {
        for (int id : seeds) {
            SkillTable row = row(id);
            if (row == null) continue;
            if (row.getClassId() == 0) {
                if (!owned.contains(id)) owned.setRank(id, 0);
            } else if (row.getClassId() == classId && owned.rank(id) == 0
                    && ClassSkillRules.effectiveRank(1, PlayerLevels.effective(level),
                            row.getUnlockLevel(), row.getMaxRank()) != 0) {
                owned.setRank(id, 1);
            }
        }
    }

    public int durationRounds(SkillTable row) {
        for (int id : row.getEffectList()) {
            BuffTable buff = buffs.get(id);
            if (buff != null && buff.getInfiniteDuration() == 0 && buff.getDuration() > 0)
                return BattleRules.roundsFromSeconds(buff.getDuration());
        }
        return 0;
    }

    public double passive(int classId, int level, PlayerSkills owned, ClassSkillRules.PassiveStat stat) {
        double total = 0;
        for (var entry : owned.entries()) {
            SkillTable row = row(entry.getSkillId());
            int rank = effectiveRank(classId, level, owned, entry.getSkillId());
            if (rank != 0 && ClassSkillRules.parsePassiveStat(row.getPassiveStat()) == stat)
                total += row.getPassivePercent() * ClassSkillRules.passiveRankFactor(rank);
        }
        return total;
    }
}
