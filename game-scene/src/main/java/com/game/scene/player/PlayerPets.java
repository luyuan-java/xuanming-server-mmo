package com.game.scene.player;

import com.game.player.store.state.PetEntry;
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

        /** 实例学会的技能（技能书是二期，恒空）。 */
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
            return new Pet(entry.getPetId(), entry.getPetTableId(), entry.getName(),
                    Integer.toUnsignedLong(entry.getLevel()), allocated, aptitude, saturate(entry.getHealth()),
                    saturate(entry.getMana()), entry.getCreatedAt(), entry.getSkillTableIdsList(), entry.getUnknownFields());
        }

        private static long saturate(long unsigned) {
            return unsigned < 0 ? Long.MAX_VALUE : unsigned;
        }
    }

    private final List<Pet> pets = new ArrayList<>();
    private long activePetId;
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

    /** 出战中的宝宝号；0 = 未出战。 */
    public long activePetId() {
        return activePetId;
    }

    public void setActivePetId(long petId) {
        this.activePetId = petId;
    }

    /** 没有宝宝、未出战、没有不认识的字段：持久化时整段省略。 */
    public boolean isPristine() {
        return pets.isEmpty() && activePetId == 0 && unknownFields.asMap().isEmpty();
    }

    public PetState toState() {
        PetState.Builder state = PetState.newBuilder().setActivePetId(activePetId).setUnknownFields(unknownFields);
        for (Pet pet : pets) {
            state.addPets(pet.toEntry());
        }
        return state.build();
    }
}
