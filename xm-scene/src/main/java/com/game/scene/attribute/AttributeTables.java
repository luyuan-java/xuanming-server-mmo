package com.game.scene.attribute;

import com.game.scene.attribute.AttributeRules.PoolRule;
import com.game.table.AttributeAllocRatioTable;
import com.game.table.AttributeAutoPlanTable;
import com.game.table.AttributeDimensionTable;
import com.game.table.AttributePoolTable;
import com.game.table.AttributeRuleTable;
import com.game.table.ClassTable;
import com.game.table.ConfigTables;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 属性加点用到的配表视图（AttributeRule / AttributePool / AttributeDimension / AttributeAllocRatio / AttributeAutoPlan / Class），
 * 按基线 {@code player_attribute.cpp} 的读表口径包装。不可变，加载后任意线程可读。
 *
 * <p>池分角色池与宝宝池（{@code owner_type} 0 / 1）：两边共用表与纯规则，但面板与写入口互不可见——
 * 角色面板不出宝宝池，角色的加点 / 洗点 / 自动加点拿到宝宝池一律按「池不存在」拒绝。所有列表保持表序（同基线）。
 */
public final class AttributeTables {

    /** 角色池（AttributePool.owner_type = 0）。 */
    static final int POOL_OWNER_PLAYER = 0;
    /** 方案名长度缺省（表缺行或配 0 时）。 */
    static final int DEFAULT_SCHEME_NAME_MAX_LEN = 12;

    private final ConfigTables tables;
    private final AttributeRuleTable rule;
    private final List<AttributePoolTable> playerPools;
    private final List<AttributeDimensionTable> playerDimensions;
    private final ClassTable firstClass;

    private AttributeTables(ConfigTables tables) {
        this.tables = tables;
        List<AttributeRuleTable> rules = tables.attributeRule().all();
        this.rule = rules.isEmpty() ? null : rules.get(0);
        List<AttributePoolTable> pools = new ArrayList<>();
        for (AttributePoolTable pool : tables.attributePool().all()) {
            if (pool.getOwnerType() == POOL_OWNER_PLAYER) {
                pools.add(pool);
            }
        }
        this.playerPools = List.copyOf(pools);
        List<AttributeDimensionTable> dimensions = new ArrayList<>();
        for (AttributeDimensionTable dimension : tables.attributeDimension().all()) {
            if (playerPool(dimension.getPoolId()) != null) {
                dimensions.add(dimension);
            }
        }
        this.playerDimensions = List.copyOf(dimensions);
        List<ClassTable> classes = tables.classTable().all();
        this.firstClass = classes.isEmpty() ? null : classes.get(0);
    }

    public static AttributeTables from(ConfigTables tables) {
        return new AttributeTables(tables);
    }

    // ------------------------------------------------------------------ AttributeRule（单行全局规则，取首行）

    /** 方案数上限（表 0 / 缺行按 1）。 */
    int maxSchemes() {
        return rule != null && rule.getMaxSchemes() > 0 ? rule.getMaxSchemes() : 1;
    }

    /** 再开一个方案要花的金币：已有方案数 &lt; 免费数时免费；缺行免费。 */
    long createSchemeCost(int schemeCount) {
        if (rule == null) {
            return 0;
        }
        return Integer.toUnsignedLong(schemeCount) < Integer.toUnsignedLong(rule.getFreeSchemeCount())
                ? 0 : rule.getCreateSchemeCostGold();
    }

    /** 切换冷却秒（0 = 无冷却）。 */
    long switchCooldownSeconds() {
        return rule == null ? 0 : Integer.toUnsignedLong(rule.getSwitchCooldownSeconds());
    }

    /** 方案名最多几个字符（码点数；表 0 / 缺行按 12）。 */
    long schemeNameMaxLen() {
        return rule != null && rule.getSchemeNameMaxLen() != 0
                ? Integer.toUnsignedLong(rule.getSchemeNameMaxLen()) : DEFAULT_SCHEME_NAME_MAX_LEN;
    }

    /** 加点公式常量：bonus 取表（缺行按缺省），满投点数按池 + 等级上限现算（不进表）。 */
    AttributeRules.FormulaRule formulaRule(AttributePoolTable pool, int maxLevel) {
        double bonus = rule != null ? rule.getAllocEfficiencyBonus() : AttributeRules.DEFAULT_EFFICIENCY_BONUS;
        return AttributeRules.makeFormulaRule(bonus, AttributeRules.fullInvestmentPoints(toRule(pool), maxLevel));
    }

    // ------------------------------------------------------------------ 池与维度

    /** 角色池（表序）。 */
    List<AttributePoolTable> playerPools() {
        return playerPools;
    }

    /** 角色池；不存在或是宝宝池为 null。 */
    AttributePoolTable playerPool(int poolId) {
        Optional<AttributePoolTable> pool = tables.attributePool().find(poolId);
        return pool.isPresent() && pool.get().getOwnerType() == POOL_OWNER_PLAYER ? pool.get() : null;
    }

    /** 角色维度（所属池是角色池；表序）。 */
    List<AttributeDimensionTable> playerDimensions() {
        return playerDimensions;
    }

    /** 某池的全部维度（表序）。 */
    List<AttributeDimensionTable> dimensionsOf(int poolId) {
        return tables.attributeDimension().findAllByPoolId(poolId);
    }

    /** 维度在表里（不分角色 / 宝宝；改表删掉的维度加载时从方案里清掉）。 */
    boolean dimensionExists(int dimensionId) {
        return tables.attributeDimension().contains(dimensionId);
    }

    /** 维度所属池（表里有就返回，不分角色 / 宝宝）。 */
    AttributePoolTable poolOf(AttributeDimensionTable dimension) {
        return tables.attributePool().find(dimension.getPoolId()).orElse(null);
    }

    static PoolRule toRule(AttributePoolTable pool) {
        return new PoolRule(pool.getId(), Integer.toUnsignedLong(pool.getUnlockLevel()),
                Integer.toUnsignedLong(pool.getPointsPerLevel()), Integer.toUnsignedLong(pool.getBasePoints()),
                Integer.toUnsignedLong(pool.getDimensionCap()));
    }

    // ------------------------------------------------------------------ 收益比例 / 自动加点 / 职业

    /**
     * 维度对该职业的加点收益比例行：职业专属行优先，没有退到 class_id=0 兜底（多行时取表序最后一行，同基线）；都没有为 null。
     */
    AttributeAllocRatioTable allocRatio(int dimensionId, int classId) {
        AttributeAllocRatioTable fallback = null;
        for (AttributeAllocRatioTable row : tables.attributeAllocRatio().findAllByDimensionId(dimensionId)) {
            if (classId != 0 && row.getClassId() == classId) {
                return row;
            }
            if (row.getClassId() == 0) {
                fallback = row;
            }
        }
        return fallback;
    }

    /** 自动加点方案：职业专属优先，缺则 class_id=0 通用兜底；同一职业取表序第一条属于该池的。没有为 null。 */
    AttributeAutoPlanTable autoPlan(int classId, int poolId) {
        for (int candidate : new int[] {classId, 0}) {
            for (AttributeAutoPlanTable row : tables.attributeAutoPlan().findAllByClassId(candidate)) {
                if (row.getPoolId() == poolId) {
                    return row;
                }
            }
        }
        return null;
    }

    /** 职业行：职业号为 0 或表里没有时取首行（同基线 ResolveClassRow）；表空为 null。 */
    ClassTable classRow(int classId) {
        if (classId != 0) {
            Optional<ClassTable> row = tables.classTable().find(classId);
            if (row.isPresent()) {
                return row.get();
            }
        }
        return firstClass;
    }
}
