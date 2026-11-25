package com.game.scene.skill;

import com.game.battle.engine.BattleRules;
import com.game.battle.engine.ClassSkillRules;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.PlayerSkills;
import com.game.scene.player.PlayerPets.Pet;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import com.game.table.PetTable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 不可变技能配置视图；持久值与当前版本的生效投影分开。 */
public final class ClassSkillCatalog {
    private final Map<Integer, SkillTable> skills;
    private final Map<Integer, BuffTable> buffs;
    private final Map<Integer, PetTable> species;
    private final List<SkillTable> characterRows;
    private final List<SkillTable> petRows;

    public ClassSkillCatalog(Collection<SkillTable> skills, Collection<BuffTable> buffs) {
        this(skills, buffs, List.of());
    }

    private ClassSkillCatalog(Collection<SkillTable> skills, Collection<BuffTable> buffs, Collection<PetTable> pets) {
        Map<Integer, SkillTable> rows = new LinkedHashMap<>();
        skills.forEach(row -> rows.put(row.getId(), row));
        this.skills = Map.copyOf(rows);
        Map<Integer, BuffTable> buffRows = new LinkedHashMap<>();
        buffs.forEach(row -> buffRows.put(row.getId(), row));
        this.buffs = Map.copyOf(buffRows);
        this.species = pets.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(PetTable::getId, p -> p));
        characterRows = subjectRows(1);
        petRows = subjectRows(2);
    }

    public static ClassSkillCatalog from(ConfigTables tables) {
        return new ClassSkillCatalog(tables.skill().all(), tables.buff().all(), tables.pet().all());
    }

    /** 只提供旧通用技能的表适配；正式装配一律从完整 ConfigTables 创建。 */
    public static ClassSkillCatalog generic(Collection<Integer> ids) {
        return new ClassSkillCatalog(ids.stream().filter(id -> id != 0)
                .map(id -> SkillTable.newBuilder().setId(id).build()).toList(), List.of());
    }

    public SkillTable row(int id) { return id == 0 ? null : skills.get(id); }
    public BuffTable buff(int id) { return buffs.get(id); }
    public PetTable species(int id) { return species.get(id); }
    public static boolean forSubject(SkillTable row, int mask) {
        return row.getBattleModel() == 1 && (row.getCasterMask() & mask) != 0;
    }
    private List<SkillTable> subjectRows(int mask) {
        return skills.values().stream().filter(row -> forSubject(row, mask))
                .sorted((a,b) -> Integer.compareUnsigned(a.getId(), b.getId())).toList();
    }
    public List<SkillTable> petRows() { return petRows; }
    public static boolean ranked(SkillTable row) { return row.getBattleModel() == 1 || row.getClassId() != 0; }
    public static boolean passive(SkillTable row) {
        return row.getBattleModel() == 1 ? row.getAbilityKind() != 1 : !row.getPassiveStat().isEmpty();
    }
    public static boolean characterCanLearn(SkillTable row, int classId) {
        return row.getBattleModel() != 0 ? forSubject(row, 1) : row.getClassId() != 0 && row.getClassId() == classId;
    }
    public static int petStoredRank(Pet pet, PetTable species, int id) {
        return pet.skillRanks().getOrDefault(id, species.getSkillList().contains(id) || pet.skillTableIds().contains(id) ? 1 : 0);
    }
    public Map<Integer,Integer> petSkills(Pet pet, PetTable species) {
        var candidates = new java.util.TreeSet<Integer>(Integer::compareUnsigned);
        candidates.addAll(species.getSkillList());
        candidates.addAll(pet.skillTableIds());
        candidates.addAll(pet.skillRanks().keySet());
        var ranks = new java.util.TreeMap<Integer,Integer>(Integer::compareUnsigned);
        for (int id : candidates) {
            var skill = row(id);
            if (skill == null) continue;
            if (skill.getBattleModel() == 0 && skill.getClassId() == 0) ranks.put(id, petStoredRank(pet, species, id));
            if (forSubject(skill, 2)) {
                int rank = ClassSkillRules.effectiveRank(petStoredRank(pet, species, id),
                        petLevel(pet, species), skill.getUnlockLevel(), skill.getMaxRank());
                if (rank != 0) ranks.put(id, rank);
            }
        }
        return ranks;
    }
    public static int petLevel(Pet pet, PetTable species) {
        long cap = Integer.toUnsignedLong(species.getLevelCap());
        return (int) Math.max(1, cap == 0 ? pet.level() : Math.min(cap, pet.level()));
    }
    public List<SkillTable> classRows(int classId) {
        if (!characterRows.isEmpty()) return characterRows;
        return skills.values().stream().filter(row -> classId != 0 && row.getClassId() == classId)
                .sorted((a, b) -> Integer.compareUnsigned(a.getId(), b.getId())).toList();
    }

    public int effectiveRank(int classId, int level, PlayerSkills owned, int id) {
        SkillTable row = row(id);
        return row == null || row.getSeasonId() != 0 || !characterCanLearn(row, classId) ? 0
                : ClassSkillRules.effectiveRank(owned.rank(id), PlayerLevels.effective(level),
                        row.getUnlockLevel(), row.getMaxRank());
    }

    public boolean usable(int classId, int level, PlayerSkills owned, int id) {
        SkillTable row = row(id);
        if (row == null || row.getSeasonId() != 0 || !owned.contains(id)) return false;
        if (row.getBattleModel() != 0 || ranked(row)) return effectiveRank(classId, level, owned, id) != 0;
        return skills.values().stream().noneMatch(skill -> classId != 0 && skill.getClassId() == classId);
    }

    public List<Integer> usableIds(int classId, int level, PlayerSkills owned) {
        return owned.entries().stream().map(entry -> entry.getSkillId())
                .filter(id -> usable(classId, level, owned, id)).toList();
    }

    public void grantSeeds(int classId, int level, PlayerSkills owned, List<Integer> seeds) {
        for (int id : seeds) {
            SkillTable row = row(id);
            if (row == null || row.getSeasonId() != 0) continue;
            if (row.getBattleModel() == 0 && !ranked(row)) {
                if (!owned.contains(id)) owned.setRank(id, 0);
            } else if (characterCanLearn(row, classId) && owned.rank(id) == 0
                    && ClassSkillRules.effectiveRank(1, PlayerLevels.effective(level),
                            row.getUnlockLevel(), row.getMaxRank()) != 0) {
                owned.setRank(id, 1);
            }
        }
    }

    public int durationRounds(SkillTable row) {
        for (int id : row.getEffectList()) {
            BuffTable buff = buffs.get(id);
            if (buff == null || buff.getInfiniteDuration() != 0) continue;
            if (buff.getBattleOp() != 0 && buff.getDurationRounds() > 0) return buff.getDurationRounds();
            if (buff.getDuration() > 0) return BattleRules.roundsFromSeconds(buff.getDuration());
        }
        return 0;
    }

    public double passive(int classId, int level, PlayerSkills owned, ClassSkillRules.PassiveStat stat) {
        return passive(classId, level, owned, stat, 0);
    }

    public double passive(int classId, int level, PlayerSkills owned, ClassSkillRules.PassiveStat stat, int activeSeasonId) {
        double total = 0;
        var equippedNonActive = SkillLoadoutRules.collectEquippedNonActiveSkills(this, classId, level, owned);
        for (var entry : owned.entries()) {
            SkillTable row = row(entry.getSkillId());
            if (row == null || row.getSeasonId() != 0) continue;
            if (row.getBattleModel() == 1 && row.getAbilityKind() > 1 && !equippedNonActive.contains(row.getId())) continue;
            int rank = effectiveRank(classId, level, owned, entry.getSkillId());
            if (rank != 0 && ClassSkillRules.parsePassiveStat(row.getPassiveStat()) == stat)
                total += row.getPassivePercent() * ClassSkillRules.passiveRankFactor(rank);
        }
        if (activeSeasonId != 0) {
            int effLevel = PlayerLevels.effective(level);
            for (SkillTable row : classRows(classId)) {
                if (row.getSeasonId() != activeSeasonId || !forSubject(row, 1) || row.getPassiveStat().isEmpty()) {
                    continue;
                }
                int rank = com.game.battle.engine.SeasonRules.effectiveSeasonalSkillRank(
                        effLevel, row.getUnlockLevel(), row.getMaxRank());
                if (rank != 0 && ClassSkillRules.parsePassiveStat(row.getPassiveStat()) == stat) {
                    total += row.getPassivePercent() * ClassSkillRules.passiveRankFactor(rank);
                }
            }
        }
        return total;
    }
}
