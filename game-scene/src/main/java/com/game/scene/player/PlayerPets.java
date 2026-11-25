package com.game.scene.player;

import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PetEntryPresetState;
import com.game.player.store.state.PetState;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 玩家的宝宝（只在场景逻辑线程上读写；规则在 {@code com.game.scene.pet.PetService}，本类只存状态）。
 * 存档是 {@code player_state.pets}：实例按获得顺序，出战号；二级属性与点数总量不落库（同基线）。
 * 没有宝宝、未出战、没有不认识的字段时整段省略。
 */
public final class PlayerPets {

    /** 一只宝宝（基线 PetInstance）。uint32 / uint64 字段按无符号值放在 long 里。 */
    public static final class Pet {
        private final long petId;
        private final int petTableId;
        private String name;
        private long level;
        /** 维度号 → 已分配点（只存非 0，维度号无符号升序）。 */
        private final TreeMap<Integer, Long> allocated;
        /** 维度号 → 资质（万分比；缺键按 10000）。 */
        private final TreeMap<Integer, Long> aptitude;
        private long health;
        private long mana;
        private final long createdAt;
        private final List<Integer> skillTableIds;
        private final UnknownFieldSet unknownFields;
        private final Map<Integer, Integer> entryAllocated = new TreeMap<>(Integer::compareUnsigned);
        private final Map<Integer, Integer> skillRanks = new TreeMap<>(Integer::compareUnsigned);
        public Map<Integer, Integer> skillRanks() { return Collections.unmodifiableMap(skillRanks); }
        public void setSkillRank(int id, int rank) { skillRanks.put(id, rank); }
        private final Map<Integer, Integer> entryLastTarget = new TreeMap<>(Integer::compareUnsigned);
        private final Map<Integer, PetEntryPresetState> entryPresets = new TreeMap<>(Integer::compareUnsigned);
        private long entryStateVersion, entryLastBaseVersion, tierLastRequestId;
        private int entryLastConfigVersion, cultivationTier, activeEntryPresetSlot,
                tierLastExpectedTier, tierLastResultTier, entryLastPresetSlot, entryLastPresetTag;
        private String entryLastRequestId = "";
        public Map<Integer,Integer> entryAllocated() { return Collections.unmodifiableMap(entryAllocated); }
        public long entryStateVersion() { return entryStateVersion == 0 ? 1 : entryStateVersion; }
        public long rawEntryStateVersion() { return entryStateVersion; }
        public String entryLastRequestId() { return entryLastRequestId; }
        public long entryLastBaseVersion() { return entryLastBaseVersion; }
        public int entryLastConfigVersion() { return entryLastConfigVersion; }
        public Map<Integer,Integer> entryLastTarget() { return Collections.unmodifiableMap(entryLastTarget); }
        public int cultivationTier() { return cultivationTier; }
        public void setCultivationTier(int value) { cultivationTier = value; }
        public int activeEntryPresetSlot() { return activeEntryPresetSlot; }
        public PetEntryPresetState entryPreset(int slotId) { return entryPresets.get(slotId); }
        public Map<Integer, PetEntryPresetState> entryPresets() { return Collections.unmodifiableMap(entryPresets); }
        public long tierLastRequestId() { return tierLastRequestId; }
        public int tierLastExpectedTier() { return tierLastExpectedTier; }
        public int tierLastResultTier() { return tierLastResultTier; }
        public int entryLastPresetSlot() { return entryLastPresetSlot; }
        public int entryLastPresetTag() { return entryLastPresetTag; }

        public void commitEntries(Map<Integer,Integer> values, long version, String requestId, long baseVersion,
                                  int configVersion, Map<Integer,Integer> target) {
            entryAllocated.clear(); entryAllocated.putAll(values); entryStateVersion = version;
            entryLastRequestId = requestId; entryLastBaseVersion = baseVersion; entryLastConfigVersion = configVersion;
            entryLastTarget.clear(); entryLastTarget.putAll(target);
            entryLastPresetSlot = 0; entryLastPresetTag = 0;
        }

        public void commitEntriesWithPreset(int activeSlot, Map<Integer,Integer> values, long version, String requestId,
                                            long baseVersion, int configVersion, Map<Integer,Integer> target) {
            activeEntryPresetSlot = activeSlot;
            commitEntries(values, version, requestId, baseVersion, configVersion, target);
            syncPresetFromAllocated(activeSlot);
        }

        public void commitEntryPresetSwitch(int previousSlot, int targetSlot, int requestedTag, int keepTagSentinel,
                                            long version, String requestId, long baseVersion, int configVersion) {
            syncPresetFromAllocated(previousSlot);
            PetEntryPresetState existing = entryPresets.get(targetSlot);
            int nextTag = requestedTag != keepTagSentinel ? requestedTag : (existing == null ? 0 : existing.getTag());
            PetEntryPresetState updated = (existing == null
                    ? PetEntryPresetState.newBuilder().setSlotId(targetSlot)
                    : existing.toBuilder()).setTag(nextTag).build();
            entryPresets.put(targetSlot, updated);
            activeEntryPresetSlot = targetSlot;
            entryAllocated.clear();
            updated.getAllocatedMap().forEach((id, pts) -> {
                if (pts != 0) entryAllocated.put(id, pts);
            });
            entryStateVersion = version;
            entryLastRequestId = requestId;
            entryLastBaseVersion = baseVersion;
            entryLastConfigVersion = configVersion;
            entryLastPresetSlot = targetSlot;
            entryLastPresetTag = requestedTag;
            entryLastTarget.clear();
        }

        public void commitTierAscension(int newTier, long requestId, int expectedTier) {
            cultivationTier = newTier;
            if (entryStateVersion != -1L) {
                entryStateVersion = entryStateVersion() + 1;
            }
            tierLastRequestId = requestId;
            tierLastExpectedTier = expectedTier;
            tierLastResultTier = newTier;
        }

        private void syncPresetFromAllocated(int slotId) {
            PetEntryPresetState existing = entryPresets.get(slotId);
            PetEntryPresetState.Builder builder = existing == null
                    ? PetEntryPresetState.newBuilder().setSlotId(slotId)
                    : existing.toBuilder().clearAllocated();
            entryAllocated.forEach((id, pts) -> {
                if (pts != 0) builder.putAllocated(id, pts);
            });
            entryPresets.put(slotId, builder.build());
        }


        public Pet(long petId, int petTableId, long level, long createdAt) {
            this(petId, petTableId, "", level, new TreeMap<>(Integer::compareUnsigned),
                    new TreeMap<>(Integer::compareUnsigned), 0, 0, createdAt, List.of(), UnknownFieldSet.getDefaultInstance());
        }

        private Pet(long petId, int petTableId, String name, long level, TreeMap<Integer, Long> allocated,
                    TreeMap<Integer, Long> aptitude, long health, long mana, long createdAt, List<Integer> skillTableIds,
                    UnknownFieldSet unknownFields) {
            this.petId = petId;
            this.petTableId = petTableId;
            this.name = name;
            this.level = level;
            this.allocated = allocated;
            this.aptitude = aptitude;
            this.health = health;
            this.mana = mana;
            this.createdAt = createdAt;
            this.skillTableIds = List.copyOf(skillTableIds);
            this.unknownFields = unknownFields;
        }

        public long petId() {
            return petId;
        }

        public int petTableId() {
            return petTableId;
        }

        /** 玩家改的名字；空 = 用种类名。 */
        public String name() {
            return name;
        }

        public void rename(String name) {
            this.name = name;
        }

        public long level() {
            return level;
        }

        public void setLevel(long level) {
            this.level = level;
        }

        public long allocated(int dimensionId) {
            return allocated.getOrDefault(dimensionId, 0L);
        }

        /** 设已分配点；0 即删除该键（保持只存非 0）。 */
        public void setAllocated(int dimensionId, long points) {
            if (points == 0) {
                allocated.remove(dimensionId);
            } else {
                allocated.put(dimensionId, points);
            }
        }

        public Map<Integer, Long> allocatedView() {
            return Collections.unmodifiableMap(allocated);
        }

        public void clearAllocated() {
            allocated.clear();
        }

        /** 资质（万分比）；缺键为 0，由调用方按基准 10000 处理。 */
        public long aptitude(int dimensionId) {
            return aptitude.getOrDefault(dimensionId, 0L);
        }

        public boolean hasAptitude(int dimensionId) {
            return aptitude.containsKey(dimensionId);
        }

        public void setAptitude(int dimensionId, long value) {
            aptitude.put(dimensionId, value);
        }

        public long health() {
            return health;
        }

        public void setHealth(long health) {
            this.health = health;
        }

        public long mana() {
            return mana;
        }

        public void setMana(long mana) {
            this.mana = mana;
        }

        public long createdAt() {
            return createdAt;
        }

        /** 旧存档的已学 ID；缺少显式重数时按一重解释。 */
        public List<Integer> skillTableIds() {
            return skillTableIds;
        }

        PetEntry toEntry() {
            PetEntry.Builder entry = PetEntry.newBuilder()
                    .setPetId(petId)
                    .setPetTableId(petTableId)
                    .setName(name)
                    .setLevel((int) level)
                    .setHealth(health)
                    .setMana(mana)
                    .setCreatedAt(createdAt)
                    .addAllSkillTableIds(skillTableIds)
                    .putAllEntryAllocated(entryAllocated).setEntryStateVersion(entryStateVersion)
                    .setEntryLastRequestId(entryLastRequestId).setEntryLastBaseVersion(entryLastBaseVersion)
                    .setEntryLastConfigVersion(entryLastConfigVersion).putAllEntryLastTarget(entryLastTarget)
                    .setCultivationTier(cultivationTier).putAllSkillRanks(skillRanks)
                    .setActiveEntryPresetSlot(activeEntryPresetSlot)
                    .addAllEntryPresets(entryPresets.values())
                    .setTierLastRequestId(tierLastRequestId)
                    .setTierLastExpectedTier(tierLastExpectedTier)
                    .setTierLastResultTier(tierLastResultTier)
                    .setEntryLastPresetSlot(entryLastPresetSlot)
                    .setEntryLastPresetTag(entryLastPresetTag)
                    .setUnknownFields(unknownFields);
            allocated.forEach((dimension, points) -> entry.putAllocated(dimension, (int) (long) points));
            aptitude.forEach((dimension, value) -> entry.putAptitude(dimension, (int) (long) value));
            return entry.build();
        }

        static Pet of(PetEntry entry) {
            TreeMap<Integer, Long> allocated = new TreeMap<>(Integer::compareUnsigned);
            entry.getAllocatedMap().forEach((dimension, points) -> {
                if (points != 0) {
                    allocated.put(dimension, Integer.toUnsignedLong(points));
                }
            });
            TreeMap<Integer, Long> aptitude = new TreeMap<>(Integer::compareUnsigned);
            entry.getAptitudeMap().forEach((dimension, value) -> aptitude.put(dimension, Integer.toUnsignedLong(value)));
            Pet restored = new Pet(entry.getPetId(), entry.getPetTableId(), entry.getName(),
                    Integer.toUnsignedLong(entry.getLevel()), allocated, aptitude, saturate(entry.getHealth()),
                    saturate(entry.getMana()), entry.getCreatedAt(), entry.getSkillTableIdsList(), entry.getUnknownFields());
            restored.commitEntries(entry.getEntryAllocatedMap(), entry.getEntryStateVersion(), entry.getEntryLastRequestId(),
                    entry.getEntryLastBaseVersion(), entry.getEntryLastConfigVersion(), entry.getEntryLastTargetMap());
            restored.setCultivationTier(entry.getCultivationTier());
            restored.skillRanks.putAll(entry.getSkillRanksMap());
            restored.activeEntryPresetSlot = entry.getActiveEntryPresetSlot();
            for (PetEntryPresetState preset : entry.getEntryPresetsList()) {
                if (preset.getSlotId() != 0) {
                    restored.entryPresets.putIfAbsent(preset.getSlotId(), preset);
                }
            }
            restored.tierLastRequestId = entry.getTierLastRequestId();
            restored.tierLastExpectedTier = entry.getTierLastExpectedTier();
            restored.tierLastResultTier = entry.getTierLastResultTier();
            restored.entryLastPresetSlot = entry.getEntryLastPresetSlot();
            restored.entryLastPresetTag = entry.getEntryLastPresetTag();
            return restored;
        }

        private static long saturate(long unsigned) {
            return unsigned < 0 ? Long.MAX_VALUE : unsigned;
        }
    }

    private final List<Pet> pets = new ArrayList<>();
    private long activePetId;
    private long lastPurchaseSequence;
    private int lastPurchasePetTableId;
    private long lastPurchasePetId;
    private long lastPurchasePriceGold;
    private boolean starterPetGranted;
    private final UnknownFieldSet unknownFields;

    private PlayerPets(UnknownFieldSet unknownFields) {
        this.unknownFields = unknownFields;
    }

    public static PlayerPets empty() {
        return new PlayerPets(UnknownFieldSet.getDefaultInstance());
    }

    /** 从存档恢复（不校验；悬空的出战号由宝宝服务加载时纠正）。 */
    public static PlayerPets restore(PetState state) {
        PlayerPets restored = new PlayerPets(state.getUnknownFields());
        for (PetEntry entry : state.getPetsList()) {
            restored.pets.add(Pet.of(entry));
        }
        restored.activePetId = state.getActivePetId();
        restored.lastPurchaseSequence = state.getLastPurchaseSequence();
        restored.lastPurchasePetTableId = state.getLastPurchasePetTableId();
        restored.lastPurchasePetId = state.getLastPurchasePetId();
        restored.lastPurchasePriceGold = state.getLastPurchasePriceGold();
        restored.starterPetGranted = state.getStarterPetGranted();
        return restored;
    }

    /** 全部宝宝（获得顺序，只读视图）。 */
    public List<Pet> pets() {
        return Collections.unmodifiableList(pets);
    }

    public int size() {
        return pets.size();
    }

    /** 按号找；没有为 null。 */
    public Pet find(long petId) {
        for (Pet pet : pets) {
            if (pet.petId() == petId) {
                return pet;
            }
        }
        return null;
    }

    public void add(Pet pet) {
        pets.add(pet);
    }

    /** 从完整存档实例接收，保留资质、已分配点、技能与未知字段。 */
    public void putTransferred(PetEntry entry) {
        if (entry.getPetId() == 0 || find(entry.getPetId()) != null) throw new IllegalArgumentException("宝宝实例身份冲突");
        pets.add(Pet.of(entry));
    }

    public void takeTransferred(long petId) {
        if (petId == 0 || activePetId == petId || !pets.removeIf(pet -> pet.petId() == petId)) {
            throw new IllegalArgumentException("宝宝不存在或仍在出战");
        }
    }

    public void commitPrepared(PlayerPets candidate) {
        pets.clear();
        pets.addAll(candidate.pets);
        activePetId = candidate.activePetId;
        lastPurchaseSequence = candidate.lastPurchaseSequence;
        lastPurchasePetTableId = candidate.lastPurchasePetTableId;
        lastPurchasePetId = candidate.lastPurchasePetId;
        lastPurchasePriceGold = candidate.lastPurchasePriceGold;
        starterPetGranted = candidate.starterPetGranted;
    }

    public boolean starterPetGranted() { return starterPetGranted; }

    public void markStarterPetGranted() { this.starterPetGranted = true; }

    public long lastPurchaseSequence() { return lastPurchaseSequence; }
    public int lastPurchasePetTableId() { return lastPurchasePetTableId; }
    public long lastPurchasePetId() { return lastPurchasePetId; }
    public long lastPurchasePriceGold() { return lastPurchasePriceGold; }

    public void recordPurchase(long sequence, int petTableId, long petId, long priceGold) {
        lastPurchaseSequence = sequence;
        lastPurchasePetTableId = petTableId;
        lastPurchasePetId = petId;
        lastPurchasePriceGold = priceGold;
    }

    public boolean acquisitionStateValid() {
        if (!unknownFields.asMap().isEmpty()) return false;
        var ids = new java.util.HashSet<Long>();
        for (Pet pet : pets) {
            if (!pet.unknownFields.asMap().isEmpty() || pet.petId() == 0 || pet.petTableId() == 0
                    || pet.level() == 0 || !ids.add(pet.petId())) return false;
        }
        return activePetId == 0 || ids.contains(activePetId);
    }

    /** 出战中的宝宝号；0 = 未出战。 */
    public long activePetId() {
        return activePetId;
    }

    public void setActivePetId(long petId) {
        this.activePetId = petId;
    }

    /** 没有宝宝、未出战、未标记起步首宠、没有不认识的字段：持久化时整段省略。 */
    public boolean isPristine() {
        return pets.isEmpty() && activePetId == 0 && !starterPetGranted
                && lastPurchaseSequence == 0 && lastPurchasePetTableId == 0
                && lastPurchasePetId == 0 && lastPurchasePriceGold == 0 && unknownFields.asMap().isEmpty();
    }

    public PetState toState() {
        PetState.Builder state = PetState.newBuilder().setActivePetId(activePetId).setUnknownFields(unknownFields)
                .setLastPurchaseSequence(lastPurchaseSequence).setLastPurchasePetTableId(lastPurchasePetTableId)
                .setLastPurchasePetId(lastPurchasePetId).setLastPurchasePriceGold(lastPurchasePriceGold)
                .setStarterPetGranted(starterPetGranted);
        for (Pet pet : pets) {
            state.addPets(pet.toEntry());
        }
        return state.build();
    }
}
