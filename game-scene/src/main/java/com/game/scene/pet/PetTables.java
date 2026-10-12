package com.game.scene.pet;

import com.game.scene.attribute.AttributeRules.PoolRule;
import com.game.table.AttributeAutoPlanTable;
import com.game.table.AttributeDimensionTable;
import com.game.table.AttributePoolTable;
import com.game.table.ConfigTables;
import com.game.table.PetRuleTable;
import com.game.table.PetTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 宝宝用到的配表视图（Pet / PetRule，外加 AttributePool / AttributeDimension / AttributeAutoPlan 里属于宝宝池的行）。
 * 不可变，加载后任意线程可读。
 *
 * <p>宝宝维度<b>按维度号升序</b>：这是对外契约——Pet 表 aptitude_min / aptitude_max 的四个槽位按这个顺序一一对应
 * （401 体质 / 402 灵力 / 403 力量 / 404 敏捷）；按表行顺序的话策划插一行就让全部存量宝宝的资质静默错位（同基线）。
 */
public final class PetTables {

    /** 宝宝池（AttributePool.owner_type = 1）。 */
    static final int POOL_OWNER_PET = 1;
    /** 可携带数缺省（表缺行或配 0）。 */
    static final long DEFAULT_MAX_PETS = 1;
    /** 宝宝名长度缺省（码点数；表缺行或配 0）。 */
    static final long DEFAULT_NAME_MAX_LEN = 8;

    private final Map<Integer, PetTable> pets;
    private final PetRuleTable rule;
    private final AttributePoolTable pool;
    private final List<AttributeDimensionTable> dimensions;
    private final AttributeAutoPlanTable autoPlan;

    PetTables(Map<Integer, PetTable> pets, PetRuleTable rule, AttributePoolTable pool,
              List<AttributeDimensionTable> dimensions, AttributeAutoPlanTable autoPlan) {
        this.pets = Map.copyOf(pets);
        this.rule = rule;
        this.pool = pool;
        this.dimensions = List.copyOf(dimensions);
        this.autoPlan = autoPlan;
    }

    public static PetTables from(ConfigTables tables) {
        Map<Integer, PetTable> pets = new HashMap<>();
        for (PetTable row : tables.pet().all()) {
            pets.put(row.getId(), row);
        }
        List<PetRuleTable> rules = tables.petRule().all();
        AttributePoolTable pool = null;
        for (AttributePoolTable row : tables.attributePool().all()) {
            if (row.getOwnerType() == POOL_OWNER_PET) {
                pool = row;
                break;
            }
        }
        List<AttributeDimensionTable> dimensions = new ArrayList<>();
        AttributeAutoPlanTable plan = null;
        if (pool != null) {
            dimensions.addAll(tables.attributeDimension().findAllByPoolId(pool.getId()));
            dimensions.sort(Comparator.comparingLong(row -> Integer.toUnsignedLong(row.getId())));
            // 宝宝没有职业：只用 class_id = 0 的通用方案里属于宝宝池的第一条
            for (AttributeAutoPlanTable row : tables.attributeAutoPlan().findAllByClassId(0)) {
                if (row.getPoolId() == pool.getId()) {
                    plan = row;
                    break;
                }
            }
        }
        return new PetTables(pets, rules.isEmpty() ? null : rules.get(0), pool, dimensions, plan);
    }

    /** 种类行；表里没有为 null。 */
    PetTable pet(int petTableId) {
        return pets.get(petTableId);
    }

    /** 宝宝池；表里没有 owner_type = 1 的池为 null（宝宝属性无法计算）。 */
    AttributePoolTable pool() {
        return pool;
    }

    PoolRule poolRule() {
        return new PoolRule(pool.getId(), Integer.toUnsignedLong(pool.getUnlockLevel()),
                Integer.toUnsignedLong(pool.getPointsPerLevel()), Integer.toUnsignedLong(pool.getBasePoints()),
                Integer.toUnsignedLong(pool.getDimensionCap()));
    }

    /** 宝宝维度（维度号升序）；没有宝宝池时为空。 */
    List<AttributeDimensionTable> dimensions() {
        return dimensions;
    }

    /** 宝宝池的自动加点方案（class_id = 0）；没有为 null。 */
    AttributeAutoPlanTable autoPlan() {
        return autoPlan;
    }

    long maxPets() {
        return rule != null && rule.getMaxPets() != 0 ? Integer.toUnsignedLong(rule.getMaxPets()) : DEFAULT_MAX_PETS;
    }

    long nameMaxLen() {
        return rule != null && rule.getNameMaxLen() != 0 ? Integer.toUnsignedLong(rule.getNameMaxLen())
                : DEFAULT_NAME_MAX_LEN;
    }

    /** 改名金币（uint64；缺行为 0 = 免费）。 */
    long renameCostGold() {
        return rule != null ? rule.getRenameCostGold() : 0;
    }

    /** 列表里要不要带改名金币（基线只有规则行存在时才设）。 */
    boolean hasRule() {
        return rule != null;
    }
}
