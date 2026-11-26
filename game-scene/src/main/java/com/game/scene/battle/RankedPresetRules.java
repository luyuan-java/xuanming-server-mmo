package com.game.scene.battle;

import com.game.battle.engine.BattleRules;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.PetCultivationEffects;
import com.game.proto.RankedMatchContext;
import com.game.proto.RankedPresetSelection;
import com.game.scene.attribute.AttributeRules;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.player.PlayerLevels;
import com.game.scene.world.ScenePlayer;
import com.game.table.AttributeAllocRatioTable;
import com.game.table.AttributeDimensionTable;
import com.game.table.AttributePoolTable;
import com.game.table.AttributeRuleTable;
import com.game.table.ClassTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.PetEntryTable;
import com.game.table.PetRuleTable;
import com.game.table.PetTable;
import com.game.table.RankedPresetTable;
import com.game.table.RankedRuleTable;
import com.game.table.SkillTable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 论剑首发 V1 竞技预设、规范配表指纹与零资产侵入快照纯规则（对齐 C++ {@code ranked_preset_rules.h} 与 Go {@code ranked/catalog.go}）。
 * 严禁读写玩家真实装备、技能、货币、背包、永久宠物属性；零宠物账号仅使用局内临时宝宝（{@code pet_id=0, competitive_temporary=true}）。
 */
public final class RankedPresetRules {

    public static final int RULE_ROW_ID = 1;
    public static final int ROLE_KIND_CHARACTER = 1;
    public static final int ROLE_KIND_PET = 2;
    public static final int EXPECTED_WEIGHT_SUM = 5;
    public static final int PLAYER_POOL_OWNER_TYPE = 0;
    public static final int PET_POOL_OWNER_TYPE = 1;
    public static final long FALLBACK_SPEED = 120L;
    private static final long APTITUDE_BASE = 10_000L;

    private RankedPresetRules() {
    }

    /** 场景侧不可变排位预设配表视图（由 {@link ConfigTables} 一次性构建）。 */
    public static final class Tables {
        private final RankedRuleTable rule;
        private final Map<Integer, RankedPresetTable> presetsById;
        private final List<RankedPresetTable> presetsSortedById;
        private final Map<Integer, DungeonTable> dungeonsById;
        private final Map<Integer, ClassTable> classesById;
        private final AttributePoolTable playerPool;
        private final AttributePoolTable petPool;
        private final List<AttributeDimensionTable> playerDims;
        private final List<AttributeDimensionTable> petDims;
        private final List<AttributeAllocRatioTable> allocRatios;
        private final double efficiencyBonus;
        private final int petEntryBudget;
        private final Map<Integer, PetTable> petsById;
        private final Map<Integer, PetEntryTable> petEntriesById;
        private final Map<Integer, SkillTable> skillsById;
        private final String configFingerprint;
        private final boolean valid;

        private Tables(RankedRuleTable rule,
                       Map<Integer, RankedPresetTable> presetsById,
                       List<RankedPresetTable> presetsSortedById,
                       Map<Integer, DungeonTable> dungeonsById,
                       Map<Integer, ClassTable> classesById,
                       AttributePoolTable playerPool,
                       AttributePoolTable petPool,
                       List<AttributeDimensionTable> playerDims,
                       List<AttributeDimensionTable> petDims,
                       List<AttributeAllocRatioTable> allocRatios,
                       double efficiencyBonus,
                       int petEntryBudget,
                       Map<Integer, PetTable> petsById,
                       Map<Integer, PetEntryTable> petEntriesById,
                       Map<Integer, SkillTable> skillsById) {
            this.rule = rule;
            this.presetsById = Map.copyOf(presetsById);
            this.presetsSortedById = List.copyOf(presetsSortedById);
            this.dungeonsById = Map.copyOf(dungeonsById);
            this.classesById = Map.copyOf(classesById);
            this.playerPool = playerPool;
            this.petPool = petPool;
            this.playerDims = List.copyOf(playerDims);
            this.petDims = List.copyOf(petDims);
            this.allocRatios = List.copyOf(allocRatios);
            this.efficiencyBonus = efficiencyBonus;
            this.petEntryBudget = petEntryBudget;
            this.petsById = Map.copyOf(petsById);
            this.petEntriesById = Map.copyOf(petEntriesById);
            this.skillsById = Map.copyOf(skillsById);
            this.configFingerprint = computeFingerprintInternal(rule, this.presetsSortedById);
            this.valid = validateInternal();
        }

        public static Tables from(ConfigTables config) {
            RankedRuleTable rule = config.rankedRule().find(RULE_ROW_ID).orElse(null);
            List<RankedPresetTable> sortedPresets = config.rankedPreset().all().stream()
                    .sorted(Comparator.comparingInt(RankedPresetTable::getId))
                    .toList();
            Map<Integer, RankedPresetTable> presetsById = new HashMap<>();
            for (RankedPresetTable row : sortedPresets) {
                presetsById.put(row.getId(), row);
            }
            Map<Integer, DungeonTable> dungeons = new HashMap<>();
            for (DungeonTable row : config.dungeon().all()) {
                dungeons.put(row.getId(), row);
            }
            Map<Integer, ClassTable> classes = new HashMap<>();
            for (ClassTable row : config.classTable().all()) {
                classes.put(row.getId(), row);
            }
            AttributePoolTable playerPool = findPoolByOwner(config.attributePool().all(), PLAYER_POOL_OWNER_TYPE);
            AttributePoolTable petPool = findPoolByOwner(config.attributePool().all(), PET_POOL_OWNER_TYPE);
            List<AttributeDimensionTable> playerDims = playerPool == null ? List.of() : dimsForPool(config.attributeDimension().all(), playerPool.getId());
            List<AttributeDimensionTable> petDims = petPool == null ? List.of() : dimsForPool(config.attributeDimension().all(), petPool.getId());
            List<AttributeAllocRatioTable> allocRatios = List.copyOf(config.attributeAllocRatio().all());
            double effBonus = config.attributeRule().all().stream()
                    .findFirst()
                    .map(AttributeRuleTable::getAllocEfficiencyBonus)
                    .orElse(AttributeRules.DEFAULT_EFFICIENCY_BONUS);
            int entryBudget = config.petRule().all().stream()
                    .findFirst()
                    .map(PetRuleTable::getEntryTotalPoints)
                    .orElse(0);
            Map<Integer, PetTable> pets = new HashMap<>();
            for (PetTable row : config.pet().all()) {
                pets.put(row.getId(), row);
            }
            Map<Integer, PetEntryTable> petEntries = new HashMap<>();
            for (PetEntryTable row : config.petEntry().all()) {
                petEntries.put(row.getId(), row);
            }
            Map<Integer, SkillTable> skills = new HashMap<>();
            for (SkillTable row : config.skill().all()) {
                skills.put(row.getId(), row);
            }
            return new Tables(rule, presetsById, sortedPresets, dungeons, classes,
                    playerPool, petPool, playerDims, petDims, allocRatios,
                    effBonus, entryBudget, pets, petEntries, skills);
        }

        public RankedRuleTable rule() {
            return rule;
        }

        public RankedPresetTable preset(int id) {
            return presetsById.get(id);
        }

        public String configFingerprint() {
            return configFingerprint;
        }

        public boolean isValid() {
            return valid;
        }

        private boolean validateInternal() {
            if (rule == null || rule.getRuleVersion() == 0 || rule.getBattleConfigId() == 0
                    || rule.getReferenceClassId() == 0 || rule.getCompetitiveLevel() == 0
                    || Integer.compareUnsigned(rule.getSkillBanSlotsPerTeam(), 3) > 0) {
                return false;
            }
            DungeonTable dungeon = dungeonsById.get(rule.getBattleConfigId());
            if (dungeon == null || dungeon.getMonsterCount() != 0
                    || dungeon.getMaxTeamSize() < 5 || dungeon.getAllowPetCapture() != 0) {
                return false;
            }
            if (!classesById.containsKey(rule.getReferenceClassId())) {
                return false;
            }
            if (playerPool == null || playerDims.size() != 4 || petPool == null || petDims.size() != 4) {
                return false;
            }
            if (petEntryBudget <= 0) {
                return false;
            }
            int charCount = 0;
            int petCount = 0;
            boolean hasDefChar = false;
            boolean hasDefPet = false;
            for (RankedPresetTable preset : presetsSortedById) {
                if (preset.getRoleKind() != ROLE_KIND_CHARACTER && preset.getRoleKind() != ROLE_KIND_PET) {
                    return false;
                }
                int wSum = preset.getWeightCon() + preset.getWeightSpi() + preset.getWeightStr() + preset.getWeightAgi();
                if (wSum != EXPECTED_WEIGHT_SUM || preset.getSkillIdsCount() == 0) {
                    return false;
                }
                int requiredMask = preset.getRoleKind() == ROLE_KIND_CHARACTER ? 1 : 2;
                for (int skillId : preset.getSkillIdsList()) {
                    SkillTable skill = skillsById.get(skillId);
                    if (skill == null || skill.getBattleModel() != 1 || (skill.getCasterMask() & requiredMask) == 0) {
                        return false;
                    }
                }
                if (preset.getRoleKind() == ROLE_KIND_CHARACTER) {
                    charCount++;
                    if (preset.getId() == rule.getDefaultCharacterPresetId()) {
                        hasDefChar = true;
                    }
                } else {
                    petCount++;
                    if (preset.getId() == rule.getDefaultPetPresetId()) {
                        hasDefPet = true;
                    }
                    if (!petsById.containsKey(preset.getPetTableId())) {
                        return false;
                    }
                    if (preset.getPetEntryIdsCount() != preset.getPetEntryPointsCount()) {
                        return false;
                    }
                    int entrySum = 0;
                    for (int i = 0; i < preset.getPetEntryIdsCount(); i++) {
                        int entryId = preset.getPetEntryIds(i);
                        int pts = preset.getPetEntryPoints(i);
                        PetEntryTable entry = petEntriesById.get(entryId);
                        if (entry == null || pts <= 0 || pts > entry.getCap()) {
                            return false;
                        }
                        entrySum += pts;
                    }
                    if (entrySum != petEntryBudget) {
                        return false;
                    }
                }
            }
            return charCount > 0 && petCount > 0 && hasDefChar && hasDefPet;
        }

        private AttributeAllocRatioTable findAllocRatio(int dimensionId, int classId) {
            AttributeAllocRatioTable fallback = null;
            for (AttributeAllocRatioTable row : allocRatios) {
                if (row.getDimensionId() != dimensionId) {
                    continue;
                }
                if (classId != 0 && row.getClassId() == classId) {
                    return row;
                }
                if (row.getClassId() == 0) {
                    fallback = row;
                }
            }
            return fallback;
        }

        private static AttributePoolTable findPoolByOwner(List<AttributePoolTable> all, int ownerType) {
            AttributePoolTable best = null;
            for (AttributePoolTable row : all) {
                if (row.getOwnerType() != ownerType) {
                    continue;
                }
                if (best == null || Integer.compareUnsigned(row.getId(), best.getId()) < 0) {
                    best = row;
                }
            }
            return best;
        }

        private static List<AttributeDimensionTable> dimsForPool(List<AttributeDimensionTable> all, int poolId) {
            return all.stream()
                    .filter(d -> d.getPoolId() == poolId)
                    .sorted(Comparator.comparingInt(AttributeDimensionTable::getId))
                    .toList();
        }
    }

    /** 最大余数法按 4 维权重分配总点数：先 floor(total * w / sumW)，余数降序补 1，同余数按 dimensionId 升序。 */
    public static int[] allocateWeightedPoints(int totalPoints, int[] dimIds, int[] weights) {
        int sumW = 0;
        for (int i = 0; i < 4; i++) {
            sumW += weights[i];
        }
        int[] result = new int[4];
        if (sumW == 0 || totalPoints <= 0) {
            return result;
        }
        long[] rem = new long[4];
        int assigned = 0;
        for (int i = 0; i < 4; i++) {
            long num = Integer.toUnsignedLong(totalPoints) * Integer.toUnsignedLong(weights[i]);
            result[i] = (int) (num / sumW);
            rem[i] = num % sumW;
            assigned += result[i];
        }
        int leftover = Math.max(0, totalPoints - assigned);
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> {
            if (rem[a] != rem[b]) {
                return Long.compare(rem[b], rem[a]);
            }
            return Integer.compareUnsigned(dimIds[a], dimIds[b]);
        });
        for (int i = 0; i < 4 && leftover > 0; i++, leftover--) {
            result[order[i]] += 1;
        }
        return result;
    }

    /** 与 Go {@code ranked.ComputeConfigFingerprint}、C++ {@code rankedpreset::ComputeConfigFingerprint} 逐字节一致。 */
    public static String computeConfigFingerprint(ConfigTables config) {
        return Tables.from(config).configFingerprint();
    }

    private static String computeFingerprintInternal(RankedRuleTable rule, List<RankedPresetTable> sortedPresets) {
        if (rule == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("ranked-v1\n");
        sb.append("rule:id=1,ver=").append(Integer.toUnsignedString(rule.getRuleVersion()))
                .append(",cfg=").append(Integer.toUnsignedString(rule.getBattleConfigId()))
                .append(",cls=").append(Integer.toUnsignedString(rule.getReferenceClassId()))
                .append(",lvl=").append(Integer.toUnsignedString(rule.getCompetitiveLevel()))
                .append(",defc=").append(Integer.toUnsignedString(rule.getDefaultCharacterPresetId()))
                .append(",defp=").append(Integer.toUnsignedString(rule.getDefaultPetPresetId()))
                .append(",bans=").append(Integer.toUnsignedString(rule.getSkillBanSlotsPerTeam()))
                .append(",banmin=").append(Integer.toUnsignedString(rule.getSkillBanMinRatingCenti()))
                .append('\n');
        for (RankedPresetTable p : sortedPresets) {
            sb.append("preset:").append(Integer.toUnsignedString(p.getId()))
                    .append(",kind=").append(Integer.toUnsignedString(p.getRoleKind()))
                    .append(",pet=").append(Integer.toUnsignedString(p.getPetTableId()))
                    .append(",w=").append(Integer.toUnsignedString(p.getWeightCon()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightSpi()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightStr()))
                    .append('/').append(Integer.toUnsignedString(p.getWeightAgi()))
                    .append(",sk=");
            for (int i = 0; i < p.getSkillIdsCount(); i++) {
                if (i > 0) sb.append('|');
                sb.append(Integer.toUnsignedString(p.getSkillIds(i)));
            }
            sb.append(",pe=");
            int pairCount = Math.min(p.getPetEntryIdsCount(), p.getPetEntryPointsCount());
            for (int i = 0; i < pairCount; i++) {
                if (i > 0) sb.append('|');
                sb.append(Integer.toUnsignedString(p.getPetEntryIds(i)))
                        .append(':')
                        .append(Integer.toUnsignedString(p.getPetEntryPoints(i)));
            }
            sb.append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 构筑论剑竞技隔离快照：只读预设与参考职业表，绝不读取玩家真实装备/技能/血蓝/真实宝宝数值/背包道具。
     * 校验失败（配表非法、rule_version 或 config_fingerprint 不匹配、预设非法）返回 {@link Optional#empty()}（fail-closed）。
     */
    public static Optional<BattlePlayerSnapshot> buildCompetitiveSnapshot(ScenePlayer player,
                                                                          SceneBattleTables battleTables,
                                                                          RankedMatchContext rankedCtx,
                                                                          RankedPresetSelection selection,
                                                                          BattleRouting routing,
                                                                          long nowEpochSec) {
        Tables tables = battleTables.ranked();
        if (tables == null || !tables.isValid() || rankedCtx == null || !rankedCtx.getUseFreePreset()) {
            return Optional.empty();
        }
        RankedRuleTable rule = tables.rule();
        if (rankedCtx.getRuleVersion() != 0 && rankedCtx.getRuleVersion() != rule.getRuleVersion()) {
            return Optional.empty();
        }
        if (!rankedCtx.getConfigFingerprint().isEmpty()
                && !rankedCtx.getConfigFingerprint().equals(tables.configFingerprint())) {
            return Optional.empty();
        }
        int charPresetId = (selection != null && selection.getCharacterPresetId() != 0)
                ? selection.getCharacterPresetId() : rule.getDefaultCharacterPresetId();
        int petPresetId = (selection != null && selection.getPetPresetId() != 0)
                ? selection.getPetPresetId() : rule.getDefaultPetPresetId();
        RankedPresetTable charPreset = tables.preset(charPresetId);
        RankedPresetTable petPreset = tables.preset(petPresetId);
        if (charPreset == null || charPreset.getRoleKind() != ROLE_KIND_CHARACTER
                || petPreset == null || petPreset.getRoleKind() != ROLE_KIND_PET) {
            return Optional.empty();
        }

        ClassTable classRow = tables.classesById.get(rule.getReferenceClassId());
        if (classRow == null) {
            return Optional.empty();
        }
        int level = PlayerLevels.clampStored(rule.getCompetitiveLevel());
        AttributeRules.PoolRule playerPoolRule = toPoolRule(tables.playerPool);
        int totalPoints = (int) AttributeRules.totalPoints(playerPoolRule, level, 0L);
        int[] charDimIds = {
                tables.playerDims.get(0).getId(),
                tables.playerDims.get(1).getId(),
                tables.playerDims.get(2).getId(),
                tables.playerDims.get(3).getId()
        };
        int[] charWeights = {
                charPreset.getWeightCon(),
                charPreset.getWeightSpi(),
                charPreset.getWeightStr(),
                charPreset.getWeightAgi()
        };
        int[] charAlloc = allocateWeightedPoints(totalPoints, charDimIds, charWeights);

        double stdHp = unsignedToDouble(classRow.getInitHealth());
        double stdMp = unsignedToDouble(classRow.getInitMana());
        double stdPatk = 0.0;
        double stdMatk = 0.0;
        double stdSpd = unsignedToDouble(classRow.getInitSpeed());
        double stdDef = 0.0;
        for (AttributeDimensionTable dim : tables.playerDims) {
            if (dim.getBasePerLevel() == 0) {
                continue;
            }
            double pts = (double) Integer.toUnsignedLong(dim.getBasePerLevel()) * PlayerLevels.MAX_LEVEL;
            stdHp += dim.getMaxHealth() * pts;
            stdMp += dim.getMaxMana() * pts;
            stdPatk += dim.getPhysicalAttack() * pts;
            stdMatk += dim.getMagicAttack() * pts;
            stdSpd += dim.getSpeed() * pts;
            stdDef += dim.getDefense() * pts;
        }
        AttributeRules.FormulaRule formulaRule = AttributeRules.makeFormulaRule(
                tables.efficiencyBonus, AttributeRules.fullInvestmentPoints(playerPoolRule, PlayerLevels.MAX_LEVEL));

        double hp = unsignedToDouble(classRow.getInitHealth());
        double mp = unsignedToDouble(classRow.getInitMana());
        double patk = 0.0;
        double matk = 0.0;
        double spd = unsignedToDouble(classRow.getInitSpeed());
        double def = 0.0;
        for (int i = 0; i < 4; i++) {
            AttributeDimensionTable dim = tables.playerDims.get(i);
            double natural = (double) Integer.toUnsignedLong(dim.getBasePerLevel()) * level;
            hp += dim.getMaxHealth() * natural;
            mp += dim.getMaxMana() * natural;
            patk += dim.getPhysicalAttack() * natural;
            matk += dim.getMagicAttack() * natural;
            spd += dim.getSpeed() * natural;
            def += dim.getDefense() * natural;
            if (charAlloc[i] == 0) {
                continue;
            }
            AttributeAllocRatioTable ratio = tables.findAllocRatio(dim.getId(), rule.getReferenceClassId());
            if (ratio != null) {
                hp += AttributeRules.allocatedIncrement(stdHp, ratio.getMaxHealth(), charAlloc[i], formulaRule);
                mp += AttributeRules.allocatedIncrement(stdMp, ratio.getMaxMana(), charAlloc[i], formulaRule);
                patk += AttributeRules.allocatedIncrement(stdPatk, ratio.getPhysicalAttack(), charAlloc[i], formulaRule);
                matk += AttributeRules.allocatedIncrement(stdMatk, ratio.getMagicAttack(), charAlloc[i], formulaRule);
                spd += AttributeRules.allocatedIncrement(stdSpd, ratio.getSpeed(), charAlloc[i], formulaRule);
                def += AttributeRules.allocatedIncrement(stdDef, ratio.getDefense(), charAlloc[i], formulaRule);
            } else {
                double allocD = charAlloc[i];
                hp += dim.getMaxHealth() * allocD;
                mp += dim.getMaxMana() * allocD;
                patk += dim.getPhysicalAttack() * allocD;
                matk += dim.getMagicAttack() * allocD;
                spd += dim.getSpeed() * allocD;
                def += dim.getDefense() * allocD;
            }
        }
        long maxHealth = Math.max(floorToLong(hp), 1L);
        long maxMana = floorToLong(mp);
        long physicalAttack = floorToLong(patk);
        long magicAttack = floorToLong(matk);
        long speed = floorToLong(spd);
        if (speed == 0L) {
            speed = FALLBACK_SPEED;
        }
        long defense = floorToLong(def);

        BaseAttributesComp baseAttrs = BaseAttributesComp.newBuilder()
                .setHealth(maxHealth)
                .setMana(maxMana)
                .setStrength(classRow.getInitStrength())
                .setArmor(classRow.getInitArmor())
                .setResistance(classRow.getInitResistance())
                .setCritchance(classRow.getInitCritchance())
                .setSpeed(speed)
                .build();

        // 零宠物账号默认使用预设种类的外观与名字；有出战宝宝时仅借用其外观种类与名称，数值与词条完全来自 petPreset。
        int cosmeticPetTableId = 0;
        String cosmeticPetName = "";
        if (player.pets().activePetId() != 0L) {
            Pet active = player.pets().find(player.pets().activePetId());
            if (active != null) {
                cosmeticPetTableId = active.petTableId();
                cosmeticPetName = active.name();
            }
        }
        BattlePetSnapshot petSnapshot = buildCompetitivePetSnapshot(
                tables, rule, petPreset, player.playerId(), cosmeticPetTableId, cosmeticPetName);
        if (petSnapshot == null) {
            return Optional.empty();
        }

        BattlePlayerSnapshot.Builder snap = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(player.playerId())
                .setPlayerName(player.name())
                .setAppearanceId(player.effectiveAppearanceId(nowEpochSec))
                .setGender(player.gender())
                .setClassId(rule.getReferenceClassId())
                .setLevel(level)
                .setBaseAttributes(baseAttrs)
                .setMaxHealth(maxHealth)
                .setMaxMana(maxMana)
                .setPhysicalAttack(physicalAttack)
                .setMagicAttack(magicAttack)
                .setDefense(defense)
                .setRouting(routing)
                .setTeamIndex(0)
                .setTableFingerprint(battleTables.fingerprint())
                .setPetCaptureSlots(0)
                .addPets(petSnapshot);
        for (int skillId : charPreset.getSkillIdsList()) {
            SkillTable skill = tables.skillsById.get(skillId);
            if (skill == null) {
                return Optional.empty();
            }
            snap.putSkillRanks(skillId, 1);
            if (BattleRules.isTurnBattleCastableSkill(skill)) {
                snap.addSkillTableIds(skillId);
            }
        }
        return Optional.of(snap.build());
    }

    private static BattlePetSnapshot buildCompetitivePetSnapshot(Tables tables,
                                                                 RankedRuleTable rule,
                                                                 RankedPresetTable petPreset,
                                                                 long ownerPlayerId,
                                                                 int cosmeticPetTableId,
                                                                 String cosmeticPetName) {
        PetTable species = tables.petsById.get(petPreset.getPetTableId());
        if (species == null) {
            return null;
        }
        long levelCap = Integer.toUnsignedLong(species.getLevelCap());
        long petLevel = Math.max(1L, Integer.toUnsignedLong(rule.getCompetitiveLevel()));
        if (levelCap > 0L) {
            petLevel = Math.min(petLevel, levelCap);
        }
        int totalPoints = (int) AttributeRules.totalPoints(toPoolRule(tables.petPool), petLevel, 0L);
        int[] petDimIds = {
                tables.petDims.get(0).getId(),
                tables.petDims.get(1).getId(),
                tables.petDims.get(2).getId(),
                tables.petDims.get(3).getId()
        };
        int[] petWeights = {
                petPreset.getWeightCon(),
                petPreset.getWeightSpi(),
                petPreset.getWeightStr(),
                petPreset.getWeightAgi()
        };
        int[] petAlloc = allocateWeightedPoints(totalPoints, petDimIds, petWeights);

        double maxHealth = unsignedToDouble(species.getInitHealth());
        double maxMana = unsignedToDouble(species.getInitMana());
        double physicalAttack = 0.0;
        double magicAttack = 0.0;
        double speed = unsignedToDouble(species.getInitSpeed());
        double defense = 0.0;
        for (int i = 0; i < 4; i++) {
            AttributeDimensionTable dim = tables.petDims.get(i);
            long dimVal = Integer.toUnsignedLong(dim.getBasePerLevel()) * petLevel + Integer.toUnsignedLong(petAlloc[i]);
            if (dimVal == 0L) {
                continue;
            }
            long apt = APTITUDE_BASE;
            if (i < species.getAptitudeMinCount() && i < species.getAptitudeMaxCount()) {
                long lo = Integer.toUnsignedLong(species.getAptitudeMin(i));
                long hi = Integer.toUnsignedLong(species.getAptitudeMax(i));
                if (hi > 0L) {
                    apt = lo + (hi >= lo ? (hi - lo) / 2L : 0L);
                }
            }
            double weighted = (double) dimVal * ((double) (apt > 0L ? apt : APTITUDE_BASE) / (double) APTITUDE_BASE);
            maxHealth += dim.getMaxHealth() * weighted;
            maxMana += dim.getMaxMana() * weighted;
            physicalAttack += dim.getPhysicalAttack() * weighted;
            magicAttack += dim.getMagicAttack() * weighted;
            speed += dim.getSpeed() * weighted;
            defense += dim.getDefense() * weighted;
        }
        long derivedHp = Math.max(floorToLong(maxHealth), 1L);
        long derivedMp = floorToLong(maxMana);
        long derivedPatk = floorToLong(physicalAttack);
        long derivedMatk = floorToLong(magicAttack);
        long derivedSpd = floorToLong(speed);
        long derivedDef = floorToLong(defense);

        BattlePetSnapshot.Builder out = BattlePetSnapshot.newBuilder()
                .setPetId(0L)
                .setCompetitiveTemporary(true)
                .setOwnerPlayerId(ownerPlayerId)
                .setCombatPetTableId(petPreset.getPetTableId())
                .setLevel((int) petLevel)
                .setMaxHealth(derivedHp)
                .setMaxMana(derivedMp)
                .setPhysicalAttack(derivedPatk)
                .setMagicAttack(derivedMatk)
                .setDefense(derivedDef)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(derivedHp)
                        .setMana(derivedMp)
                        .setSpeed(derivedSpd > 0L ? derivedSpd : FALLBACK_SPEED));
        PetTable cosmeticRow = cosmeticPetTableId != 0 ? tables.petsById.get(cosmeticPetTableId) : null;
        if (cosmeticRow != null) {
            out.setPetTableId(cosmeticPetTableId);
            out.setPetName(cosmeticPetName != null && !cosmeticPetName.isEmpty() ? cosmeticPetName : cosmeticRow.getName());
        } else {
            out.setPetTableId(petPreset.getPetTableId());
            out.setPetName(species.getName());
        }

        PetCultivationEffects.Builder cult = out.getPetCultivationBuilder();
        int pairCount = Math.min(petPreset.getPetEntryIdsCount(), petPreset.getPetEntryPointsCount());
        for (int i = 0; i < pairCount; i++) {
            PetEntryTable entry = tables.petEntriesById.get(petPreset.getPetEntryIds(i));
            if (entry == null) {
                return null;
            }
            int bps = petPreset.getPetEntryPoints(i) * entry.getPerPointBps();
            accumulatePetCultivationEntry(cult, entry, bps);
        }
        for (int skillId : petPreset.getSkillIdsList()) {
            SkillTable skill = tables.skillsById.get(skillId);
            if (skill == null) {
                return null;
            }
            out.putSkillRanks(skillId, 1);
            if (BattleRules.isTurnBattleCastableSkill(skill)) {
                out.addSkillTableIds(skillId);
            }
        }
        return out.build();
    }

    private static void accumulatePetCultivationEntry(PetCultivationEffects.Builder effects, PetEntryTable row, int bps) {
        switch (row.getChannel()) {
            case 1 -> effects.setPhysicalDamageBps(effects.getPhysicalDamageBps() + bps);
            case 2 -> effects.setMagicDamageBps(effects.getMagicDamageBps() + bps);
            case 3 -> effects.setOutgoingHealBps(effects.getOutgoingHealBps() + bps);
            case 4 -> effects.setShieldCapacityBps(effects.getShieldCapacityBps() + bps);
            case 5 -> effects.putAilmentHitBps(row.getStateKind(),
                    effects.getAilmentHitBpsOrDefault(row.getStateKind(), 0) + bps);
            case 6 -> effects.putAilmentResistBps(row.getStateKind(),
                    effects.getAilmentResistBpsOrDefault(row.getStateKind(), 0) + bps);
            case 7 -> effects.setPoisonDamageBps(effects.getPoisonDamageBps() + bps);
            case 8 -> effects.setRetreatHitBps(effects.getRetreatHitBps() + bps);
            case 9 -> effects.setRetreatResistBps(effects.getRetreatResistBps() + bps);
            default -> {
            }
        }
    }

    private static AttributeRules.PoolRule toPoolRule(AttributePoolTable row) {
        return new AttributeRules.PoolRule(
                row.getId(),
                Integer.toUnsignedLong(row.getUnlockLevel()),
                Integer.toUnsignedLong(row.getPointsPerLevel()),
                Integer.toUnsignedLong(row.getBasePoints()),
                Integer.toUnsignedLong(row.getDimensionCap()));
    }

    private static long floorToLong(double value) {
        if (value <= 0.0 || !Double.isFinite(value)) {
            return 0L;
        }
        return (long) Math.floor(value);
    }

    private static double unsignedToDouble(long value) {
        return value >= 0 ? (double) value : (double) ((value >>> 1) | (value & 1L)) * 2.0;
    }
}
