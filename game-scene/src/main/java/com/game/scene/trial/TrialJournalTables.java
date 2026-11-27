package com.game.scene.trial;

import com.game.battle.engine.TrialJournalRules;
import com.game.table.CharacterAppearanceTable;
import com.game.table.ConfigTables;
import com.game.table.EquipArtifactTable;
import com.game.table.ItemTable;
import com.game.table.PetCosmeticTable;
import com.game.table.SkillVfxCosmeticTable;
import com.game.table.TrialJournalEntryTable;
import com.game.table.TrialJournalMilestoneTable;
import com.game.table.TrialJournalSealTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 五行试炼志 V2 只读配表视图（{@code TrialJournalEntry} / {@code TrialJournalSeal} / {@code TrialJournalMilestone}
 * + 仅自用法宝目录与专属形象校验）。永久战法印与里程碑位序与 C++ {@code cfg_bit_index} 映射表完全一致。
 */
public final class TrialJournalTables {

    /** 与 C++ {@code trialjournalseal_mapping.json} 逐项一致的永久位序表（严禁复用或重排旧 ID）。 */
    private static final Map<Integer, Integer> FIXED_SEAL_BIT_INDEX = buildFixedSealBitIndex();
    /** 与 C++ {@code trialjournalmilestone_mapping.json} 逐项一致的永久位序表（严禁复用或重排旧 ID）。 */
    private static final Map<Integer, Integer> FIXED_MILESTONE_BIT_INDEX = buildFixedMilestoneBitIndex();

    private static Map<Integer, Integer> buildFixedSealBitIndex() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        int[] ids = {
                1011, 1012, 1013, 1021, 1022, 1023, 1031, 1032, 1033, 1041, 1042, 1043,
                2011, 2012, 2013, 2021, 2022, 2023, 2031, 2032, 2033, 2041, 2042, 2043,
                3011, 3012, 3021, 3022, 3031, 3032,
                1051, 1052, 1053,
                4011, 4012, 4013, 4021, 4022, 4023, 4031, 4032, 4033
        };
        for (int i = 0; i < ids.length; i++) {
            m.put(ids[i], i);
        }
        return Map.copyOf(m);
    }

    private static Map<Integer, Integer> buildFixedMilestoneBitIndex() {
        Map<Integer, Integer> m = new LinkedHashMap<>();
        int[] ids = {101, 102, 103, 104, 201, 202, 203, 901, 301, 302, 105, 401, 402};
        for (int i = 0; i < ids.length; i++) {
            m.put(ids[i], i);
        }
        return Map.copyOf(m);
    }

    public record RedeemableArtifact(ItemTable item, EquipArtifactTable artifact) {
    }

    private final List<TrialJournalEntryTable> entries;
    private final Map<Integer, TrialJournalEntryTable> entryById;
    private final Map<Integer, List<TrialJournalSealTable>> sealsByEntryId;
    private final List<TrialJournalMilestoneTable> milestones;
    private final Map<Integer, TrialJournalMilestoneTable> milestoneById;
    private final Map<Integer, Integer> sealBitIndex;
    private final Map<Integer, Integer> milestoneBitIndex;
    private final List<RedeemableArtifact> redeemableArtifacts;
    private final Map<Integer, RedeemableArtifact> redeemableByItemId;
    private final Map<String, CharacterAppearanceTable> appearanceById;
    private final Map<Integer, PetCosmeticTable> petCosmeticById;
    private final Map<Integer, SkillVfxCosmeticTable> skillVfxById;

    private TrialJournalTables(List<TrialJournalEntryTable> entries,
                               Map<Integer, TrialJournalEntryTable> entryById,
                               Map<Integer, List<TrialJournalSealTable>> sealsByEntryId,
                               List<TrialJournalMilestoneTable> milestones,
                               Map<Integer, TrialJournalMilestoneTable> milestoneById,
                               Map<Integer, Integer> sealBitIndex,
                               Map<Integer, Integer> milestoneBitIndex,
                               List<RedeemableArtifact> redeemableArtifacts,
                               Map<Integer, RedeemableArtifact> redeemableByItemId,
                               Map<String, CharacterAppearanceTable> appearanceById,
                               Map<Integer, PetCosmeticTable> petCosmeticById,
                               Map<Integer, SkillVfxCosmeticTable> skillVfxById) {
        this.entries = entries;
        this.entryById = entryById;
        this.sealsByEntryId = sealsByEntryId;
        this.milestones = milestones;
        this.milestoneById = milestoneById;
        this.sealBitIndex = sealBitIndex;
        this.milestoneBitIndex = milestoneBitIndex;
        this.redeemableArtifacts = redeemableArtifacts;
        this.redeemableByItemId = redeemableByItemId;
        this.appearanceById = appearanceById;
        this.petCosmeticById = petCosmeticById;
        this.skillVfxById = skillVfxById;
    }

    public static TrialJournalTables from(ConfigTables config) {
        Objects.requireNonNull(config, "config");
        List<TrialJournalEntryTable> entryRows = List.copyOf(config.trialJournalEntry().all());
        List<TrialJournalSealTable> sealRows = List.copyOf(config.trialJournalSeal().all());
        List<TrialJournalMilestoneTable> milestoneRows = List.copyOf(config.trialJournalMilestone().all());

        if (entryRows.isEmpty() && (!sealRows.isEmpty() || !milestoneRows.isEmpty())) {
            throw new IllegalStateException("TrialJournalEntry 为空但 Seal/Milestone 非空");
        }
        if (!entryRows.isEmpty()) {
            if (!config.item().contains(TrialJournalRules.BOUND_REFORGE_SAND_ITEM_ID)
                    || !config.item().contains(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID)) {
                throw new IllegalStateException("Item 表缺少试炼志绑定重铸砂(3403)或自用法宝拓印(3404)");
            }
        }

        Map<String, CharacterAppearanceTable> appearances = new HashMap<>();
        for (CharacterAppearanceTable row : config.characterAppearance().all()) {
            if (!row.getAppearanceId().isEmpty()) {
                appearances.put(row.getAppearanceId(), row);
            }
        }
        Map<Integer, PetCosmeticTable> petCosmetics = new HashMap<>();
        for (PetCosmeticTable row : config.petCosmetic().all()) {
            petCosmetics.put(row.getId(), row);
        }
        Map<Integer, SkillVfxCosmeticTable> skillVfxs = new HashMap<>();
        for (SkillVfxCosmeticTable row : config.skillVfxCosmetic().all()) {
            skillVfxs.put(row.getId(), row);
        }

        Map<Integer, TrialJournalEntryTable> entryMap = new LinkedHashMap<>(entryRows.size() * 2);
        for (TrialJournalEntryTable entry : entryRows) {
            if (entry.getId() == 0 || entry.getChapterId() == 0 || entry.getRuleVersion() == 0) {
                throw new IllegalStateException("TrialJournalEntry 非法行 id=" + entry.getId());
            }
            entryMap.put(entry.getId(), entry);
        }

        Map<Integer, Integer> sealBits = new HashMap<>(FIXED_SEAL_BIT_INDEX);
        int nextSealBit = sealBits.size();
        Map<Integer, List<TrialJournalSealTable>> byEntry = new HashMap<>();
        for (TrialJournalSealTable seal : sealRows) {
            TrialJournalEntryTable entry = entryMap.get(seal.getEntryId());
            if (entry == null) {
                throw new IllegalStateException("TrialJournalSeal id=" + seal.getId()
                        + " 引用不存在的 entry_id=" + seal.getEntryId());
            }
            if (!TrialJournalRules.isValidSealSlotKind(seal.getSealSlotKind())
                    || !TrialJournalRules.isValidConditionType(seal.getConditionType())) {
                throw new IllegalStateException("TrialJournalSeal id=" + seal.getId()
                        + " slot_kind 或 condition_type 非法");
            }
            if (!sealBits.containsKey(seal.getId())) {
                sealBits.put(seal.getId(), nextSealBit++);
            }
            byEntry.computeIfAbsent(seal.getEntryId(), k -> new ArrayList<>()).add(seal);
        }
        Map<Integer, List<TrialJournalSealTable>> frozenByEntry = new HashMap<>(byEntry.size() * 2);
        for (var e : byEntry.entrySet()) {
            List<TrialJournalSealTable> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt(TrialJournalSealTable::getId));
            frozenByEntry.put(e.getKey(), List.copyOf(list));
        }

        Map<Integer, Integer> milestoneBits = new HashMap<>(FIXED_MILESTONE_BIT_INDEX);
        int nextMilestoneBit = milestoneBits.size();
        Map<Integer, TrialJournalMilestoneTable> milestoneMap = new LinkedHashMap<>(milestoneRows.size() * 2);
        for (TrialJournalMilestoneTable m : milestoneRows) {
            if (m.getId() == 0 || m.getRequiredSeals() == 0) {
                throw new IllegalStateException("TrialJournalMilestone 非法行 id=" + m.getId());
            }
            if (!milestoneBits.containsKey(m.getId())) {
                milestoneBits.put(m.getId(), nextMilestoneBit++);
            }
            if (!isValidExclusiveAppearance(m.getGrantAppearanceId(), appearances)) {
                throw new IllegalStateException("TrialJournalMilestone id=" + m.getId()
                        + " 的 grant_appearance_id=" + m.getGrantAppearanceId() + " 缺失、停用或允许卡解锁");
            }
            if (!isValidExclusivePetCosmetic(m.getGrantPetCosmeticId(), petCosmetics)) {
                throw new IllegalStateException("TrialJournalMilestone id=" + m.getId()
                        + " 的 grant_pet_cosmetic_id=" + m.getGrantPetCosmeticId() + " 缺失、停用或属于可购买外观");
            }
            if (!isValidExclusiveSkillVfx(m.getGrantSkillVfxId(), skillVfxs)) {
                throw new IllegalStateException("TrialJournalMilestone id=" + m.getId()
                        + " 的 grant_skill_vfx_id=" + m.getGrantSkillVfxId() + " 缺失、停用或属于可购买外观");
            }
            milestoneMap.put(m.getId(), m);
        }

        List<RedeemableArtifact> artifacts = new ArrayList<>();
        Map<Integer, RedeemableArtifact> artifactMap = new HashMap<>();
        for (ItemTable item : config.item().all()) {
            if (item.getMaxStackSize() != 1 || item.getEquipProfile() != 3 || item.getEquipKind() != 16
                    || item.getArtifactId() < TrialJournalRules.MIN_ARTIFACT_EFFECT_ID
                    || item.getArtifactId() > TrialJournalRules.MAX_ARTIFACT_EFFECT_ID) {
                continue;
            }
            var artRow = config.equipArtifact().find(item.getArtifactId());
            if (artRow.isEmpty()) {
                continue;
            }
            RedeemableArtifact ra = new RedeemableArtifact(item, artRow.get());
            artifacts.add(ra);
            artifactMap.put(item.getId(), ra);
        }
        artifacts.sort(Comparator.comparingInt((RedeemableArtifact r) -> r.item().getArtifactId())
                .thenComparingInt(r -> r.item().getId()));

        return new TrialJournalTables(
                entryRows, Map.copyOf(entryMap), Map.copyOf(frozenByEntry),
                milestoneRows, Map.copyOf(milestoneMap),
                Map.copyOf(sealBits), Map.copyOf(milestoneBits),
                List.copyOf(artifacts), Map.copyOf(artifactMap),
                Map.copyOf(appearances), Map.copyOf(petCosmetics), Map.copyOf(skillVfxs));
    }

    private static boolean isValidExclusiveAppearance(String appearanceId,
                                                      Map<String, CharacterAppearanceTable> appearances) {
        if (appearanceId == null || appearanceId.isEmpty()) {
            return true;
        }
        CharacterAppearanceTable row = appearances.get(appearanceId);
        return row != null && row.getEnabled() != 0 && row.getCardUnlockable() == 0;
    }

    private static boolean isValidExclusivePetCosmetic(int cosmeticId,
                                                       Map<Integer, PetCosmeticTable> petCosmetics) {
        if (cosmeticId == 0) {
            return true;
        }
        PetCosmeticTable row = petCosmetics.get(cosmeticId);
        return row != null && row.getEnabled() != 0 && row.getCardItemId() == 0
                && row.getMallSkuId() == 0L && row.getSpiritShopGoodsId() == 0;
    }

    private static boolean isValidExclusiveSkillVfx(int cosmeticId,
                                                    Map<Integer, SkillVfxCosmeticTable> skillVfxs) {
        if (cosmeticId == 0) {
            return true;
        }
        SkillVfxCosmeticTable row = skillVfxs.get(cosmeticId);
        return row != null && row.getEnabled() != 0 && row.getCardItemId() == 0
                && row.getMallSkuId() == 0L && row.getSpiritShopGoodsId() == 0;
    }

    public boolean isValidExclusiveAppearance(String appearanceId) {
        return isValidExclusiveAppearance(appearanceId, appearanceById);
    }

    public boolean isValidExclusivePetCosmetic(int cosmeticId) {
        return isValidExclusivePetCosmetic(cosmeticId, petCosmeticById);
    }

    public boolean isValidExclusiveSkillVfx(int cosmeticId) {
        return isValidExclusiveSkillVfx(cosmeticId, skillVfxById);
    }

    public String appearanceDisplayName(String appearanceId) {
        if (appearanceId == null || appearanceId.isEmpty()) {
            return "";
        }
        CharacterAppearanceTable row = appearanceById.get(appearanceId);
        return row == null ? "" : row.getName();
    }

    public String petCosmeticDisplayName(int cosmeticId) {
        if (cosmeticId == 0) {
            return "";
        }
        PetCosmeticTable row = petCosmeticById.get(cosmeticId);
        return row == null ? "" : row.getName();
    }

    public String skillVfxDisplayName(int cosmeticId) {
        if (cosmeticId == 0) {
            return "";
        }
        SkillVfxCosmeticTable row = skillVfxById.get(cosmeticId);
        return row == null ? "" : row.getName();
    }

    public List<TrialJournalEntryTable> entries() {
        return entries;
    }

    public TrialJournalEntryTable entry(int entryId) {
        return entryById.get(entryId);
    }

    public List<TrialJournalSealTable> sealsForEntry(int entryId) {
        return sealsByEntryId.getOrDefault(entryId, List.of());
    }

    public List<TrialJournalMilestoneTable> milestones() {
        return milestones;
    }

    public TrialJournalMilestoneTable milestone(int milestoneId) {
        return milestoneById.get(milestoneId);
    }

    /** 返回战法印永久紧凑位序，不存在返回 -1。 */
    public int sealBitIndex(int sealId) {
        return sealBitIndex.getOrDefault(sealId, -1);
    }

    /** 返回里程碑永久紧凑位序，不存在返回 -1。 */
    public int milestoneBitIndex(int milestoneId) {
        return milestoneBitIndex.getOrDefault(milestoneId, -1);
    }

    public List<RedeemableArtifact> redeemableArtifacts() {
        return redeemableArtifacts;
    }

    public RedeemableArtifact redeemableArtifact(int itemConfigId) {
        return redeemableByItemId.get(itemConfigId);
    }
}
