package com.game.scene.pet;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.PetDerivedInfo;
import com.game.proto.PetDimensionInfo;
import com.game.proto.PetInfo;
import com.game.proto.PetListInfo;
import com.game.scene.attribute.AttributeRules;
import com.game.scene.attribute.AttributeRules.AllocError;
import com.game.scene.attribute.AttributeRules.PoolRule;
import com.game.scene.attribute.AttributeRules.Validation;
import com.game.scene.audit.AssetAudit;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.BattleGate;
import com.game.scene.pet.PetRules.BaseValues;
import com.game.scene.pet.PetRules.Coefficients;
import com.game.scene.pet.PetRules.Derived;
import com.game.scene.pet.PetRules.DimensionInput;
import com.game.scene.player.ItemGuids;
import com.game.scene.player.NameRules;
import com.game.scene.player.PlayerPets;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.table.AttributeAutoPlanTable;
import com.game.table.AttributeDimensionTable;
import com.game.table.AttributePoolTable;
import com.game.table.CommonErrorTip;
import com.game.table.PetErrorTip;
import com.game.table.PetTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 宝宝系统（基线 PetSystem，只在场景逻辑线程上调用）：列表、出战 / 收回、加点 / 洗点 / 自动加点、改名、GM 发放、主人升级带动。
 * 宝宝的任何写入只经本类；每个改动点数或等级的写操作之后都重算（同步等级 + 按新上限处理当前气血法力）。
 * 二级属性不落库、不缓存，需要时由资质 + 已分配 + 等级现算。
 *
 * <p>返回值 0 = 成功，否则是提示码（pet_error 26000–26016 等）。写操作的前置闸（基线 CheckWritable）：跨节点换图冻结中 1005
 * （已接入，scene-handoff-spec §5.9）；回合制战斗在途 26008（scene-battle-spec §7.13）。
 */
public final class PetService {

    private static final Logger log = LoggerFactory.getLogger(PetService.class);

    static final int OK = 0;
    static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int NOT_FOUND = PetErrorTip.pet_error.kPetNotFound_VALUE;
    static final int TABLE_ROW_MISSING = PetErrorTip.pet_error.kPetTableRowMissing_VALUE;
    static final int SLOT_FULL = PetErrorTip.pet_error.kPetSlotFull_VALUE;
    static final int OWNER_LEVEL_NOT_ENOUGH = PetErrorTip.pet_error.kPetOwnerLevelNotEnough_VALUE;
    static final int ALREADY_ACTIVE = PetErrorTip.pet_error.kPetAlreadyActive_VALUE;
    static final int NOT_ACTIVE = PetErrorTip.pet_error.kPetNotActive_VALUE;
    static final int NAME_INVALID = PetErrorTip.pet_error.kPetNameInvalid_VALUE;
    static final int POINTS_NOT_ENOUGH = PetErrorTip.pet_error.kPetPointsNotEnough_VALUE;
    static final int POINTS_CANNOT_DECREASE = PetErrorTip.pet_error.kPetPointsCannotDecrease_VALUE;
    static final int DIMENSION_CAP_EXCEEDED = PetErrorTip.pet_error.kPetDimensionCapExceeded_VALUE;
    static final int DIMENSION_NOT_FOUND = PetErrorTip.pet_error.kPetDimensionNotFound_VALUE;
    static final int GOLD_NOT_ENOUGH = PetErrorTip.pet_error.kPetGoldNotEnough_VALUE;
    static final int NO_AUTO_PLAN = PetErrorTip.pet_error.kPetNoAutoPlan_VALUE;
    static final int NOTHING_TO_CHANGE = PetErrorTip.pet_error.kPetNothingToChange_VALUE;
    static final int ID_GENERATE_FAILED = PetErrorTip.pet_error.kPetIdGenerateFailed_VALUE;
    /** 回合制战斗在途（基线 kPetInBattle）。 */
    static final int IN_BATTLE = PetErrorTip.pet_error.kPetInBattle_VALUE;

    /** 重算的原因（同角色）：升级按绝对增量补当前值、降级只夹；其余按比例保持。 */
    enum RecalcReason {
        LOAD, LEVEL_CHANGED, ALLOCATE, RESET
    }

    /** 自动加点的结果：{@code suggested} 是维度号 → 建议的目标已分配点（含已分配）。 */
    public record Suggestion(int tip, Map<Integer, Long> suggested) {
    }

    /** 发放的结果：成功时带新宝宝号。 */
    public record Grant(int tip, long petId) {
    }

    private final PetTables tables;
    private final CurrencyService currency;
    private final ItemGuids guids;
    private final SceneClock clock;
    private final RandomGenerator random;
    private final SceneMetrics metrics;

    /**
     * @param guids  宝宝号源（与物品同一个全服号源，同基线 item 号段）
     * @param random 资质随机数（只在逻辑线程上用）
     */
    public PetService(PetTables tables, CurrencyService currency, ItemGuids guids, SceneClock clock,
                      RandomGenerator random) {
        this(tables, currency, guids, clock, random, SceneMetrics.noop());
    }

    /** @param metrics 战斗在途闸的拒绝计数（{@code xm.scene.battle.gate.rejects{gate=pet}}） */
    public PetService(PetTables tables, CurrencyService currency, ItemGuids guids, SceneClock clock,
                      RandomGenerator random, SceneMetrics metrics) {
        this.tables = tables;
        this.currency = currency;
        this.guids = guids;
        this.clock = clock;
        this.random = random;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ 加载与重算

    /** 进场景前：纠正悬空的出战号、清掉表里已不属于宝宝池的维度、同步等级并按新上限处理当前值。 */
    public void initializeOnLoad(ScenePlayer player) {
        PlayerPets pets = player.pets();
        if (pets.activePetId() != 0 && pets.find(pets.activePetId()) == null) {
            log.warn("出战号指向不存在的宝宝，已清空 player={} active_pet_id={}", player.playerId(),
                    Long.toUnsignedString(pets.activePetId()));
            pets.setActivePetId(0);
        }
        if (tables.pool() != null) {
            for (Pet pet : pets.pets()) {
                for (Integer dimensionId : List.copyOf(pet.allocatedView().keySet())) {
                    if (dimension(dimensionId) == null) {
                        pet.setAllocated(dimensionId, 0);
                    }
                }
            }
        }
        recalculateAll(player, RecalcReason.LOAD);
    }

    /** 主人等级变化（GM 175 成功后，等级没变也走）：全部宝宝按「升级」重算。推 184 由调用方做。 */
    public void onOwnerLevelChanged(ScenePlayer player) {
        recalculateAll(player, RecalcReason.LEVEL_CHANGED);
    }

    void recalculateAll(ScenePlayer player, RecalcReason reason) {
        PlayerPets pets = player.pets();
        if (pets.size() == 0) {
            return;
        }
        if (tables.pool() == null) {
            log.error("AttributePool 缺 owner_type=1 的宝宝池，宝宝属性无法重算");
            return;
        }
        for (Pet pet : pets.pets()) {
            recalculateOne(player, pet, reason, null);
        }
    }

    /**
     * 单只重算：同步等级、收敛超额分配（整池清零返还）、按新上限处理当前气血法力。
     *
     * @param previous 加点 / 洗点时调用方在改动之前取的旧二级属性（改完再算旧值就等于新值了）；加载 / 升级传 null，按旧等级现算
     */
    private void recalculateOne(ScenePlayer player, Pet pet, RecalcReason reason, Derived previous) {
        PetTable row = tables.pet(pet.petTableId());
        if (row == null) {
            log.warn("宝宝种类行缺失，跳过重算（实例保留） player={} pet_id={} pet_table_id={}", player.playerId(),
                    Long.toUnsignedString(pet.petId()), Integer.toUnsignedString(pet.petTableId()));
            return;
        }
        long newLevel = PetRules.effectiveLevel(ownerLevel(player), Integer.toUnsignedLong(row.getLevelCap()));
        Derived oldDerived = previous != null ? previous : derived(pet, row);
        pet.setLevel(newLevel);
        long total = AttributeRules.totalPoints(tables.poolRule(), newLevel, 0);
        if (usedPoints(pet) > total) {
            pet.clearAllocated();
            log.warn("宝宝已分配超总量，清零返还 player={} pet_id={} total={}", player.playerId(),
                    Long.toUnsignedString(pet.petId()), total);
        }
        Derived newDerived = derived(pet, row);
        long health = pet.health();
        long mana = pet.mana();
        if (reason == RecalcReason.LEVEL_CHANGED) {
            if (health > 0 && newDerived.maxHealth() > oldDerived.maxHealth() && oldDerived.maxHealth() > 0) {
                health += newDerived.maxHealth() - oldDerived.maxHealth();
            }
            if (newDerived.maxMana() > oldDerived.maxMana() && oldDerived.maxMana() > 0) {
                mana += newDerived.maxMana() - oldDerived.maxMana();
            }
            health = Math.min(health, newDerived.maxHealth());
            if (newDerived.maxMana() > 0) {
                mana = Math.min(mana, newDerived.maxMana());
            }
        } else {
            health = AttributeRules.rescaleCurrent(health, oldDerived.maxHealth(), newDerived.maxHealth());
            mana = AttributeRules.rescaleCurrent(mana, oldDerived.maxMana(), newDerived.maxMana());
        }
        pet.setHealth(health);
        pet.setMana(mana);
    }

    // ------------------------------------------------------------------ 列表

    /** 面板全量（客户端零配表：维度名 / 说明 / 上限 / 剩余点 / 资质 / 成长率都在这里）。 */
    public PetListInfo buildList(ScenePlayer player) {
        PlayerPets pets = player.pets();
        PetListInfo.Builder list = PetListInfo.newBuilder()
                .setActivePetId(pets.activePetId())
                .setMaxPets((int) tables.maxPets());
        if (tables.hasRule()) {
            list.setRenameCostGold(tables.renameCostGold());
        }
        AttributePoolTable pool = tables.pool();
        if (pool == null) {
            return list.build();
        }
        for (Pet pet : pets.pets()) {
            list.addPets(info(pets, pet, pool));
        }
        return list.build();
    }

    private PetInfo info(PlayerPets pets, Pet pet, AttributePoolTable pool) {
        PetInfo.Builder info = PetInfo.newBuilder()
                .setPetId(pet.petId())
                .setPetTableId(pet.petTableId())
                .setLevel((int) pet.level())
                .setIsActive(pets.activePetId() == pet.petId())
                .setPoolId(pool.getId());
        PetTable row = tables.pet(pet.petTableId());
        info.setName(!pet.name().isEmpty() ? pet.name() : (row != null ? row.getName() : ""));
        if (row != null) {
            info.setQuality(row.getQuality()).setModelId(row.getModelId()).setDesc(row.getDesc());
            info.addAllSkillTableIds(skills(pet, row));
        }
        long total = AttributeRules.totalPoints(tables.poolRule(), pet.level(), 0);
        long used = usedPoints(pet);
        info.setTotalPoints((int) total)
                .setRemainingPoints((int) (total > used ? total - used : 0))
                .setResetCostGold(resetCost(pet, pool));
        List<DimensionInput> inputs = inputs(pet);
        info.setGrowth((int) PetRules.growthPermyriad(inputs));
        for (int i = 0; i < inputs.size(); i++) {
            AttributeDimensionTable dimension = tables.dimensions().get(i);
            DimensionInput input = inputs.get(i);
            info.addDimensions(PetDimensionInfo.newBuilder()
                    .setDimensionId(dimension.getId())
                    .setPoolId(dimension.getPoolId())
                    .setName(dimension.getName())
                    .setDesc(dimension.getDesc())
                    .setAllocated((int) input.allocated())
                    .setValue(PetRules.dimensionValue(input, pet.level()))
                    .setCap(pool.getDimensionCap())
                    .setSort(dimension.getSort())
                    .setAptitude((int) input.aptitude()));
        }
        PetDerivedInfo.Builder derivedInfo = PetDerivedInfo.newBuilder();
        if (row != null) {
            Derived derived = derived(pet, row);
            derivedInfo.setMaxHealth(derived.maxHealth()).setMaxMana(derived.maxMana())
                    .setPhysicalAttack(derived.physicalAttack()).setMagicAttack(derived.magicAttack())
                    .setSpeed(derived.speed()).setDefense(derived.defense());
        }
        derivedInfo.setHealth(pet.health()).setMana(pet.mana());
        return info.setDerived(derivedInfo).build();
    }

    // ------------------------------------------------------------------ 写操作

    /** 出战（同时只能一只；max_pets 管的是携带量）。 */
    public int summon(ScenePlayer player, long petId) {
        int writable = checkWritable(player);
        if (writable != OK) {
            return writable;
        }
        PlayerPets pets = player.pets();
        Pet pet = pets.find(petId);
        if (pet == null) {
            return NOT_FOUND;
        }
        if (pets.activePetId() == petId) {
            return ALREADY_ACTIVE;
        }
        PetTable row = tables.pet(pet.petTableId());
        if (row == null) {
            return TABLE_ROW_MISSING;
        }
        if (ownerLevel(player) < Integer.toUnsignedLong(row.getUnlockLevel())) {
            return OWNER_LEVEL_NOT_ENOUGH;
        }
        pets.setActivePetId(petId);
        log.info("宝宝出战 player={} pet_id={}", player.playerId(), Long.toUnsignedString(petId));
        return OK;
    }

    public int recall(ScenePlayer player) {
        int writable = checkWritable(player);
        if (writable != OK) {
            return writable;
        }
        PlayerPets pets = player.pets();
        if (pets.activePetId() == 0) {
            return NOT_ACTIVE;
        }
        log.info("宝宝收回 player={} pet_id={}", player.playerId(), Long.toUnsignedString(pets.activePetId()));
        pets.setActivePetId(0);
        return OK;
    }

    /**
     * 确认加点：{@code target} 是该宝宝的「目标已分配」（全量、幂等；缺省维度视为不变），校验同角色（按维度号升序判第一处违规）。
     */
    public int allocate(ScenePlayer player, long petId, Map<Integer, Long> target) {
        int writable = checkWritable(player);
        if (writable != OK) {
            return writable;
        }
        if (tables.pool() == null) {
            return INVALID_TABLE_DATA;
        }
        Pet pet = player.pets().find(petId);
        if (pet == null) {
            return NOT_FOUND;
        }
        PetTable row = tables.pet(pet.petTableId());
        if (row == null) {
            return TABLE_ROW_MISSING;
        }
        Map<Integer, Long> current = new TreeMap<>(Integer::compareUnsigned);
        for (AttributeDimensionTable dimension : tables.dimensions()) {
            current.put(dimension.getId(), pet.allocated(dimension.getId()));
        }
        TreeMap<Integer, Long> ordered = new TreeMap<>(Integer::compareUnsigned);
        ordered.putAll(target);
        Validation validation = AttributeRules.validateAllocation(tables.poolRule(), pet.level(), current, ordered,
                remaining(pet));
        if (validation.error() != AllocError.OK) {
            return allocError(validation.error());
        }
        Derived before = derived(pet, row);
        ordered.forEach(pet::setAllocated);
        recalculateOne(player, pet, RecalcReason.ALLOCATE, before);
        log.info("宝宝加点 player={} pet_id={} delta={}", player.playerId(), Long.toUnsignedString(petId),
                validation.delta());
        return OK;
    }

    /** 洗点：清空全部分配、点数返还；先扣金币（按宝宝等级，低于免费等级免费），扣失败什么都不动。 */
    public int reset(ScenePlayer player, long petId) {
        int writable = checkWritable(player);
        if (writable != OK) {
            return writable;
        }
        AttributePoolTable pool = tables.pool();
        if (pool == null) {
            return INVALID_TABLE_DATA;
        }
        Pet pet = player.pets().find(petId);
        if (pet == null) {
            return NOT_FOUND;
        }
        PetTable row = tables.pet(pet.petTableId());
        if (row == null) {
            return TABLE_ROW_MISSING;
        }
        if (usedPoints(pet) == 0) {
            return NOTHING_TO_CHANGE;
        }
        int paid = payGold(player, resetCost(pet, pool), AssetAudit.Reason.PET_RESET);
        if (paid != OK) {
            return paid;
        }
        Derived before = derived(pet, row);
        pet.clearAllocated();
        recalculateOne(player, pet, RecalcReason.RESET, before);
        log.info("宝宝洗点 player={} pet_id={}", player.playerId(), Long.toUnsignedString(petId));
        return OK;
    }

    /** 自动加点：按宝宝池的通用方案算建议「目标已分配」，只算不落（不过写闸，同基线）。 */
    public Suggestion autoAllocate(ScenePlayer player, long petId) {
        if (tables.pool() == null) {
            return new Suggestion(INVALID_TABLE_DATA, Map.of());
        }
        Pet pet = player.pets().find(petId);
        if (pet == null) {
            return new Suggestion(NOT_FOUND, Map.of());
        }
        AttributeAutoPlanTable plan = tables.autoPlan();
        if (plan == null || plan.getDimensionCount() == 0) {
            return new Suggestion(NO_AUTO_PLAN, Map.of());
        }
        TreeMap<Integer, Long> suggested = new TreeMap<>(Integer::compareUnsigned);
        for (AttributeDimensionTable dimension : tables.dimensions()) {
            suggested.put(dimension.getId(), pet.allocated(dimension.getId()));
        }
        List<Integer> order = new ArrayList<>();
        List<Long> weights = new ArrayList<>();
        for (int i = 0; i < plan.getDimensionCount(); i++) {
            int dimensionId = plan.getDimension(i);
            if (!suggested.containsKey(dimensionId)) {
                continue;
            }
            order.add(dimensionId);
            weights.add(i < plan.getWeightCount() ? Integer.toUnsignedLong(plan.getWeight(i)) : 1L);
        }
        long remaining = remaining(pet);
        if (remaining == 0) {
            return new Suggestion(NOTHING_TO_CHANGE, Map.of());
        }
        AttributeRules.distributePoints(tables.poolRule(), order, weights, remaining, suggested);
        return new Suggestion(OK, suggested);
    }

    /** 改名：名字非法 26007 → 找宝宝 → 名字没变 26015（不收钱）→ 扣改名金币 → 改。 */
    public int rename(ScenePlayer player, long petId, String name) {
        int writable = checkWritable(player);
        if (writable != OK) {
            return writable;
        }
        if (!NameRules.isValidDisplayName(name, tables.nameMaxLen())) {
            return NAME_INVALID;
        }
        Pet pet = player.pets().find(petId);
        if (pet == null) {
            return NOT_FOUND;
        }
        String currentName = pet.name();
        if (currentName.isEmpty()) {
            PetTable row = tables.pet(pet.petTableId());
            currentName = row != null ? row.getName() : "";
        }
        if (currentName.equals(name)) {
            return NOTHING_TO_CHANGE;
        }
        int paid = payGold(player, tables.renameCostGold(), AssetAudit.Reason.PET_RENAME);
        if (paid != OK) {
            return paid;
        }
        pet.rename(name);
        return OK;
    }

    /** GM 发放（基线 GrantPet）：铸号、掷资质、按主人等级定级、满血满蓝出生。 */
    public Grant grant(ScenePlayer player, int petTableId) {
        int writable = checkWritable(player);
        if (writable != OK) {
            return new Grant(writable, 0);
        }
        PetTable row = tables.pet(petTableId);
        if (row == null) {
            return new Grant(TABLE_ROW_MISSING, 0);
        }
        if (tables.pool() == null) {
            return new Grant(INVALID_TABLE_DATA, 0);
        }
        PlayerPets pets = player.pets();
        if (pets.size() >= tables.maxPets()) {
            return new Grant(SLOT_FULL, 0);
        }
        long[] minted = guids.tryMint(1);
        if (minted == null) {
            log.error("铸宝宝号失败（全服号源不可用） player={}", player.playerId());
            return new Grant(ID_GENERATE_FAILED, 0);
        }
        Pet pet = new Pet(minted[0], petTableId,
                PetRules.effectiveLevel(ownerLevel(player), Integer.toUnsignedLong(row.getLevelCap())),
                clock.epochMillis() / 1000);
        List<AttributeDimensionTable> dimensions = tables.dimensions();
        for (int i = 0; i < dimensions.size(); i++) {
            pet.setAptitude(dimensions.get(i).getId(), rollAptitude(row, i));
        }
        Derived derived = derived(pet, row);
        pet.setHealth(derived.maxHealth());
        pet.setMana(derived.maxMana());
        pets.add(pet);
        log.info("发放宝宝 player={} pet_id={} pet_table_id={} level={}", player.playerId(),
                Long.toUnsignedString(pet.petId()), Integer.toUnsignedString(petTableId), pet.level());
        return new Grant(OK, pet.petId());
    }

    /**
     * 资质：第 i 个宝宝维度在 [aptitude_min[i], aptitude_max[i]] 闭区间独立随机。两边都缺配 → 基准 10000；
     * 单边缺配 → 取另一边的定值（0 资质等于这一维完全不产出，不会是策划想要的）；配反了交换。
     */
    private long rollAptitude(PetTable row, int index) {
        long low = index < row.getAptitudeMinCount() ? Integer.toUnsignedLong(row.getAptitudeMin(index))
                : PetRules.APTITUDE_BASE;
        long high = index < row.getAptitudeMaxCount() ? Integer.toUnsignedLong(row.getAptitudeMax(index))
                : PetRules.APTITUDE_BASE;
        if (low == 0 && high == 0) {
            low = PetRules.APTITUDE_BASE;
            high = PetRules.APTITUDE_BASE;
        } else if (low == 0) {
            low = high;
        } else if (high == 0) {
            high = low;
        }
        if (low > high) {
            long swap = low;
            low = high;
            high = swap;
        }
        return low == high ? low : random.nextLong(low, high + 1);
    }

    // ------------------------------------------------------------------ 回合制战斗（scene-battle-spec §5、§7.14）

    /**
     * 出战宝宝的战斗快照（基线 {@code PetSystem::BuildBattleSnapshot}，{@code pet.cpp:867-912}）：没有出战宝宝、宝宝找不到、缺表行或缺池（WARN）、
     * 宝宝气血为 0（死宝宝不参战）→ 空表；否则恰好一只。属性按现算的二级属性：{@code base_attributes} 只填气血（夹到上限）、法力（上限 &gt; 0 才夹）、
     * 速度；技能 = 种类自带 ∪ 已学（<b>不做可施放过滤</b>，B4，引擎只让宝宝普攻）。
     */
    public List<BattlePetSnapshot> buildBattleSnapshot(ScenePlayer player) {
        PlayerPets pets = player.pets();
        long activeId = pets.activePetId();
        if (activeId == 0) {
            return List.of();
        }
        Pet pet = pets.find(activeId);
        if (pet == null) {
            return List.of();
        }
        PetTable row = tables.pet(pet.petTableId());
        if (row == null || tables.pool() == null) {
            log.warn("出战宝宝缺种类行或宝宝池，不带进战斗 player={} pet_id={} pet_table_id={}", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(pet.petId()), Integer.toUnsignedString(pet.petTableId()));
            return List.of();
        }
        if (pet.health() == 0) {
            return List.of();
        }
        Derived derived = derived(pet, row);
        long health = Math.min(pet.health(), derived.maxHealth());
        long mana = derived.maxMana() > 0 ? Math.min(pet.mana(), derived.maxMana()) : pet.mana();
        return List.of(BattlePetSnapshot.newBuilder()
                .setPetId(pet.petId())
                .setOwnerPlayerId(player.playerId())
                .setPetName(!pet.name().isEmpty() ? pet.name() : row.getName())
                .setPetTableId(pet.petTableId())
                .setLevel((int) pet.level())
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(health).setMana(mana).setSpeed(derived.speed()))
                .setMaxHealth(derived.maxHealth())
                .setMaxMana(derived.maxMana())
                .setPhysicalAttack(derived.physicalAttack())
                .setMagicAttack(derived.magicAttack())
                .setDefense(derived.defense())
                .addAllSkillTableIds(skills(pet, row))
                .build());
    }

    /**
     * 战斗结算回写宝宝（基线 {@code PetSystem::ApplyBattleSettlement}，{@code pet.cpp:914-941}）：找不到 pet_id → WARN 忽略；缺表行 / 缺池 → 静默跳过；
     * 气血按现算上限夹，法力上限 &gt; 0 才夹；{@code is_dead} 或夹后气血为 0 → 回满（气血、法力回到上限）。
     * <b>不经过 {@link #checkWritable}</b>（结算应用时战斗冻结还没摘，§7.11 f 步）。推 184 由调用方做（有条目时一次）。
     */
    public void applyBattleSettlement(ScenePlayer player, List<BattlePetSettlementData> entries) {
        PlayerPets pets = player.pets();
        for (BattlePetSettlementData entry : entries) {
            Pet pet = pets.find(entry.getPetId());
            if (pet == null) {
                log.warn("战斗结算里的宝宝不在玩家名下，忽略 player={} pet_id={}", Long.toUnsignedString(player.playerId()),
                        Long.toUnsignedString(entry.getPetId()));
                continue;
            }
            PetTable row = tables.pet(pet.petTableId());
            if (row == null || tables.pool() == null) {
                continue;
            }
            Derived derived = derived(pet, row);
            long settled = entry.getHealth() < 0 ? Long.MAX_VALUE : entry.getHealth();
            long settledMana = entry.getMana() < 0 ? Long.MAX_VALUE : entry.getMana();
            long health = Math.min(settled, derived.maxHealth());
            long mana = derived.maxMana() > 0 ? Math.min(settledMana, derived.maxMana()) : settledMana;
            if (entry.getIsDead() || health == 0) {
                health = derived.maxHealth();
                mana = derived.maxMana();
            }
            pet.setHealth(health);
            pet.setMana(mana);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 写操作统一前置（基线 CheckWritable）：跨节点换图的交出事务在途（{@link ScenePlayer#frozen()}）回 1005——客户端入口已按冻结策略
     * 收拢（scene-handoff-spec §5.9），这里是纵深防御；选目标中（RESOLVING）不冻结。回合制战斗在途回 26008（scene-battle-spec §7.13）。
     * 自动加点只算不落，不过这道闸（同基线）。战斗结算的宝宝回写（{@link #applyBattleSettlement}）不经过这里：应用时战斗冻结还没摘。
     */
    private int checkWritable(ScenePlayer player) {
        if (player.frozen()) {
            return INVALID_PARAMETER;
        }
        if (player.inBattle()) {
            metrics.battleGateReject(BattleGate.PET);
            return IN_BATTLE;
        }
        return OK;
    }

    /** 扣金币（uint64）：0 不扣；余额不足回 26013；扣成功记一条流水（货币服务内部还有封禁等校验）。 */
    private int payGold(ScenePlayer player, long cost, AssetAudit.Reason reason) {
        if (cost == 0) {
            return OK;
        }
        if (Long.compareUnsigned(player.wallet().balance(Wallet.GOLD), cost) < 0) {
            return GOLD_NOT_ENOUGH;
        }
        Wallet.Change change = currency.deduct(player, Wallet.GOLD, cost, reason);
        return change.ok() ? OK : change.tipId();
    }

    private static long ownerLevel(ScenePlayer player) {
        long level = Integer.toUnsignedLong(player.level());
        return level > 0 ? level : 1;
    }

    private AttributeDimensionTable dimension(int dimensionId) {
        for (AttributeDimensionTable dimension : tables.dimensions()) {
            if (dimension.getId() == dimensionId) {
                return dimension;
            }
        }
        return null;
    }

    /** 已用点数（饱和到 uint32）。 */
    private static long usedPoints(Pet pet) {
        long used = 0;
        for (long points : pet.allocatedView().values()) {
            used += points;
        }
        return Math.min(used, AttributeRules.UINT32_MAX);
    }

    private long remaining(Pet pet) {
        long total = AttributeRules.totalPoints(tables.poolRule(), pet.level(), 0);
        long used = usedPoints(pet);
        return total > used ? total - used : 0;
    }

    /** 洗点金币：宝宝等级低于免费等级免费（0 = 不设免费等级）。 */
    private static long resetCost(Pet pet, AttributePoolTable pool) {
        long freeBelow = Integer.toUnsignedLong(pool.getResetFreeBelowLevel());
        if (freeBelow > 0 && pet.level() < freeBelow) {
            return 0;
        }
        return pool.getResetCostGold();
    }

    /** 宝宝可用技能 = 种类自带 ∪ 实例已学（面板与战斗快照共用；种类自带不抄进实例）。 */
    private static List<Integer> skills(Pet pet, PetTable row) {
        List<Integer> skills = new ArrayList<>();
        for (int skill : row.getSkillList()) {
            if (skill != 0) {
                skills.add(skill);
            }
        }
        for (int skill : pet.skillTableIds()) {
            if (skill != 0 && !skills.contains(skill)) {
                skills.add(skill);
            }
        }
        return skills;
    }

    private List<DimensionInput> inputs(Pet pet) {
        List<DimensionInput> inputs = new ArrayList<>(tables.dimensions().size());
        for (AttributeDimensionTable dimension : tables.dimensions()) {
            long aptitude = pet.hasAptitude(dimension.getId()) ? pet.aptitude(dimension.getId()) : PetRules.APTITUDE_BASE;
            inputs.add(new DimensionInput(dimension.getId(), new Coefficients(
                    Integer.toUnsignedLong(dimension.getBasePerLevel()), dimension.getMaxHealth(), dimension.getMaxMana(),
                    dimension.getPhysicalAttack(), dimension.getMagicAttack(), dimension.getSpeed(), dimension.getDefense()),
                    pet.allocated(dimension.getId()), aptitude));
        }
        return inputs;
    }

    private Derived derived(Pet pet, PetTable row) {
        return PetRules.computeDerived(new BaseValues(row.getInitHealth(), row.getInitMana(), row.getInitSpeed()),
                inputs(pet), pet.level());
    }

    private static int allocError(AllocError error) {
        return switch (error) {
            case OK -> OK;
            case POOL_LOCKED -> OWNER_LEVEL_NOT_ENOUGH;
            case DIMENSION_NOT_IN_POOL -> DIMENSION_NOT_FOUND;
            case CANNOT_DECREASE -> POINTS_CANNOT_DECREASE;
            case CAP_EXCEEDED -> DIMENSION_CAP_EXCEEDED;
            case NOT_ENOUGH_POINTS -> POINTS_NOT_ENOUGH;
            case NOTHING_TO_CHANGE -> NOTHING_TO_CHANGE;
        };
    }
}
