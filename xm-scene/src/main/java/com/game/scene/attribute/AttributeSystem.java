package com.game.scene.attribute;

import com.game.proto.AttributeDimensionInfo;
import com.game.proto.AttributePanelInfo;
import com.game.proto.AttributePoolInfo;
import com.game.proto.AttributeSchemeInfo;
import com.game.proto.DerivedAttributeInfo;
import com.game.scene.attribute.AttributeRules.AllocError;
import com.game.scene.attribute.AttributeRules.PoolRule;
import com.game.scene.attribute.AttributeRules.Validation;
import com.game.scene.audit.AssetAudit;
import com.game.scene.player.PlayerAttributes;
import com.game.scene.player.PlayerAttributes.Derived;
import com.game.scene.player.PlayerAttributes.Scheme;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.table.AttributeAllocRatioTable;
import com.game.table.AttributeAutoPlanTable;
import com.game.table.AttributeDimensionTable;
import com.game.table.AttributeErrorTip;
import com.game.table.AttributePoolTable;
import com.game.table.ClassTable;
import com.game.table.CommonErrorTip;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 角色属性加点（同 mmorpg {@code PlayerAttributeSystem}，设计见其 {@code docs/design/player-attribute-allocation.md}）。
 * 只在场景逻辑线程上调用；{@link PlayerAttributes} 的任何写入只经本类，每个改变数值的写操作之后都重算二级属性。
 * 返回值是 tip：0 = 成功，否则是拒绝码（处理器原样放进应答的 {@code error_message}）。
 *
 * <pre>
 * 二级属性 = 职业初值（只有气血上限 / 法力上限 / 速度）
 *          + Σ（每级自然成长 × 等级）× 该维度每点系数                    ← 所有角色维度
 *          + Σ 玩家分配点的增量：有收益比例行的维度走百分比 + 集中投资公式，没有的按每点系数
 * 增量 = 85 级标准基础属性 × 比例 × E(n) ÷ E(满投点数)，E(n) = n × (1 + bonus × n ÷ 满投点数)
 * </pre>
 * 浮点按基线同序累加，六项各自累加完再向下取整（气血上限至少 1），与基线逐值一致。
 */
public final class AttributeSystem {

    private static final Logger log = LoggerFactory.getLogger(AttributeSystem.class);

    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int POOL_NOT_FOUND = AttributeErrorTip.attribute_error.kAttributePoolNotFound_VALUE;
    static final int POOL_LOCKED = AttributeErrorTip.attribute_error.kAttributePoolLocked_VALUE;
    static final int DIMENSION_NOT_FOUND = AttributeErrorTip.attribute_error.kAttributeDimensionNotFound_VALUE;
    static final int POINTS_NOT_ENOUGH = AttributeErrorTip.attribute_error.kAttributePointsNotEnough_VALUE;
    static final int CANNOT_DECREASE = AttributeErrorTip.attribute_error.kAttributePointsCannotDecrease_VALUE;
    static final int CAP_EXCEEDED = AttributeErrorTip.attribute_error.kAttributeDimensionCapExceeded_VALUE;
    static final int SCHEME_NOT_FOUND = AttributeErrorTip.attribute_error.kAttributeSchemeNotFound_VALUE;
    static final int SCHEME_LIMIT = AttributeErrorTip.attribute_error.kAttributeSchemeLimitReached_VALUE;
    static final int SWITCH_COOLDOWN = AttributeErrorTip.attribute_error.kAttributeSchemeSwitchCooldown_VALUE;
    static final int SCHEME_NAME_INVALID = AttributeErrorTip.attribute_error.kAttributeSchemeNameInvalid_VALUE;
    static final int SCHEME_ALREADY_ACTIVE = AttributeErrorTip.attribute_error.kAttributeSchemeAlreadyActive_VALUE;
    static final int GOLD_NOT_ENOUGH = AttributeErrorTip.attribute_error.kAttributeGoldNotEnough_VALUE;
    static final int NO_AUTO_PLAN = AttributeErrorTip.attribute_error.kAttributeNoAutoPlan_VALUE;
    static final int NOTHING_TO_CHANGE = AttributeErrorTip.attribute_error.kAttributeNothingToChange_VALUE;

    /** 方案自动命名：方案一 ~ 方案九，再往后用数字（基线 DefaultSchemeName）。 */
    private static final String[] SCHEME_ORDINALS = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};

    /**
     * 重算的原因：决定当前气血 / 法力怎么跟随上限变化。
     * <ul>
     *   <li>{@code LEVEL_CHANGED}（升级 / 降级）：上限抬高按绝对增量补当前值，降级只夹；</li>
     *   <li>其它：按比例保持（{@link AttributeRules#rescaleCurrent}），切方案 / 洗点 / 重新加点都不能当回血手段。</li>
     * </ul>
     * {@code LOAD} 与 {@code LEVEL_CHANGED} 还会先做「已分配 &gt; 总量」的收敛。
     */
    enum RecalcReason { LOAD, LEVEL_CHANGED, ALLOCATE, SCHEME_SWITCH, RESET }

    /** 自动加点结果：成功时 {@code suggested} 是该池全部维度的建议目标已分配（含已分配部分）。 */
    public record AutoAllocation(int tipId, Map<Integer, Long> suggested) {
    }

    /** 开新方案结果：成功时带新方案 id。 */
    public record SchemeCreation(int tipId, int schemeId) {
    }

    private final AttributeTables tables;
    private final SceneClock clock;
    private final AssetAudit audit;

    public AttributeSystem(AttributeTables tables, SceneClock clock, AssetAudit audit) {
        this.tables = tables;
        this.clock = clock;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 玩家实例建好后调用一次（进场与接管都会走到）：清掉表里已不存在的维度（改表后老存档自愈、点数返还）、
     * 按当前等级收敛超量分配、算出二级属性，并把当前气血 / 法力回满（Java 版暂不持久化当前值，见 {@link PlayerAttributes}）。
     */
    public void initializeOnLoad(ScenePlayer player) {
        PlayerAttributes attributes = player.attributes();
        for (Scheme scheme : attributes.schemes()) {
            for (Integer dimensionId : List.copyOf(scheme.allocatedView().keySet())) {
                if (!tables.dimensionExists(dimensionId)) {
                    scheme.setAllocated(dimensionId, 0);
                }
            }
        }
        recalculate(player, RecalcReason.LOAD);
        // 基线 TopUpToDerivedMax（新号 / 阵亡者加载后回满）
        Derived derived = attributes.derived();
        if (derived.maxHealth() > 0) {
            attributes.setHealth(derived.maxHealth());
        }
        if (derived.maxMana() > 0) {
            attributes.setMana(derived.maxMana());
        }
    }

    // ------------------------------------------------------------------ 重算

    void recalculate(ScenePlayer player, RecalcReason reason) {
        PlayerAttributes attributes = player.attributes();
        int level = level(player);
        if (reason == RecalcReason.LOAD || reason == RecalcReason.LEVEL_CHANGED) {
            convergeOverAllocation(player, attributes, level);
        }
        Scheme scheme = attributes.activeScheme();
        ClassTable classRow = tables.classRow(player.classId());

        Accumulator acc = Accumulator.classInitial(classRow);
        Accumulator standard = standardBaseAtLevelCap(classRow);
        for (AttributeDimensionTable dimension : tables.playerDimensions()) {
            double natural = (double) Integer.toUnsignedLong(dimension.getBasePerLevel()) * level;
            if (natural > 0.0) {
                acc.addLinear(dimension, natural);
            }
            long allocated = scheme != null ? scheme.allocated(dimension.getId()) : 0;
            if (allocated == 0) {
                continue;
            }
            AttributeAllocRatioTable ratio = tables.allocRatio(dimension.getId(), player.classId());
            AttributePoolTable pool = tables.poolOf(dimension);
            if (ratio != null && pool != null) {
                acc.addAllocatedByFormula(standard, ratio, allocated, tables.formulaRule(pool, PlayerLevels.MAX_LEVEL));
            } else {
                acc.addLinear(dimension, (double) allocated);
            }
        }

        Derived old = attributes.derived();
        Derived derived = new Derived(Math.max(floor(acc.maxHealth), 1), floor(acc.maxMana),
                floor(acc.physicalAttack), floor(acc.magicAttack), floor(acc.speed), floor(acc.defense));
        attributes.setDerived(derived);

        long health = attributes.health();
        long mana = attributes.mana();
        if (reason == RecalcReason.LEVEL_CHANGED) {
            if (health > 0 && derived.maxHealth() > old.maxHealth() && old.maxHealth() > 0) {
                health += derived.maxHealth() - old.maxHealth();
            }
            if (derived.maxMana() > old.maxMana() && old.maxMana() > 0) {
                mana += derived.maxMana() - old.maxMana();
            }
            health = Math.min(health, derived.maxHealth());
            if (derived.maxMana() > 0) {
                mana = Math.min(mana, derived.maxMana());
            }
        } else {
            health = AttributeRules.rescaleCurrent(health, old.maxHealth(), derived.maxHealth());
            mana = AttributeRules.rescaleCurrent(mana, old.maxMana(), derived.maxMana());
        }
        attributes.setHealth(health);
        attributes.setMana(mana);
    }

    /**
     * 已分配 &gt; 总量的收敛：等级下降（GM / 回档 / 上限下调后读存档压回）或改表缩点后，某方案某池的已分配可能超过总量。
     * 整池清零返还（不按比例裁剪：结果不可预期），所有方案都检查，只管角色池。
     */
    private void convergeOverAllocation(ScenePlayer player, PlayerAttributes attributes, int level) {
        for (AttributePoolTable pool : tables.playerPools()) {
            long total = AttributeRules.totalPoints(AttributeTables.toRule(pool), level, 0);
            for (Scheme scheme : attributes.schemes()) {
                if (usedPoints(scheme, pool.getId()) <= total) {
                    continue;
                }
                for (AttributeDimensionTable dimension : tables.dimensionsOf(pool.getId())) {
                    scheme.setAllocated(dimension.getId(), 0);
                }
                log.warn("已分配超总量，整池清零返还 player={} pool={} scheme={} total={}",
                        player.playerId(), pool.getId(), scheme.id(), total);
            }
        }
    }

    /**
     * 85 级标准基础属性 = 职业初值 + 每个角色维度的自然成长 × 等级上限（不含加点、不含装备）。加点公式的基准，
     * 按表现算而不单独配（改职业初值或自然成长时自动跟着变）。
     */
    private Accumulator standardBaseAtLevelCap(ClassTable classRow) {
        Accumulator acc = Accumulator.classInitial(classRow);
        for (AttributeDimensionTable dimension : tables.playerDimensions()) {
            if (dimension.getBasePerLevel() == 0) {
                continue;
            }
            acc.addLinear(dimension, (double) Integer.toUnsignedLong(dimension.getBasePerLevel()) * PlayerLevels.MAX_LEVEL);
        }
        return acc;
    }

    private static long floor(double value) {
        return value <= 0.0 ? 0 : (long) Math.floor(value);
    }

    /** 六项二级属性的临时累加器。 */
    private static final class Accumulator {
        double maxHealth;
        double maxMana;
        double physicalAttack;
        double magicAttack;
        double speed;
        double defense;

        static Accumulator classInitial(ClassTable classRow) {
            Accumulator acc = new Accumulator();
            if (classRow != null) {
                acc.maxHealth = (double) classRow.getInitHealth();
                acc.maxMana = (double) classRow.getInitMana();
                acc.speed = (double) classRow.getInitSpeed();
            }
            return acc;
        }

        void addLinear(AttributeDimensionTable dimension, double points) {
            maxHealth += dimension.getMaxHealth() * points;
            maxMana += dimension.getMaxMana() * points;
            physicalAttack += dimension.getPhysicalAttack() * points;
            magicAttack += dimension.getMagicAttack() * points;
            speed += dimension.getSpeed() * points;
            defense += dimension.getDefense() * points;
        }

        void addAllocatedByFormula(Accumulator standard, AttributeAllocRatioTable ratio, long allocated,
                                   AttributeRules.FormulaRule rule) {
            maxHealth += AttributeRules.allocatedIncrement(standard.maxHealth, ratio.getMaxHealth(), allocated, rule);
            maxMana += AttributeRules.allocatedIncrement(standard.maxMana, ratio.getMaxMana(), allocated, rule);
            physicalAttack += AttributeRules.allocatedIncrement(standard.physicalAttack, ratio.getPhysicalAttack(),
                    allocated, rule);
            magicAttack += AttributeRules.allocatedIncrement(standard.magicAttack, ratio.getMagicAttack(), allocated, rule);
            speed += AttributeRules.allocatedIncrement(standard.speed, ratio.getSpeed(), allocated, rule);
            defense += AttributeRules.allocatedIncrement(standard.defense, ratio.getDefense(), allocated, rule);
        }
    }

    // ------------------------------------------------------------------ 面板

    /** 全量面板（客户端零配表：维度名 / 说明 / 上限 / 剩余点 / 二级属性都在这里）。只读，不改状态。 */
    public AttributePanelInfo buildPanel(ScenePlayer player) {
        PlayerAttributes attributes = player.attributes();
        Scheme scheme = attributes.activeScheme();
        int level = level(player);
        AttributePanelInfo.Builder panel = AttributePanelInfo.newBuilder()
                .setLevel(level)
                .setActiveSchemeId(attributes.activeSchemeId())
                .setMaxSchemes(tables.maxSchemes())
                .setCreateSchemeCostGold(tables.createSchemeCost(attributes.schemes().size()));
        long cooldown = tables.switchCooldownSeconds();
        if (cooldown > 0) {
            panel.setSwitchCooldownUntil(attributes.lastSwitchTime() + cooldown);
        }
        for (AttributePoolTable pool : tables.playerPools()) {
            PoolRule rule = AttributeTables.toRule(pool);
            long total = AttributeRules.totalPoints(rule, level, 0);
            long used = scheme != null ? usedPoints(scheme, pool.getId()) : 0;
            panel.addPools(AttributePoolInfo.newBuilder()
                    .setPoolId(pool.getId())
                    .setName(pool.getName())
                    .setTotal((int) total)
                    .setRemaining((int) (total > used ? total - used : 0))
                    .setDimensionCap(pool.getDimensionCap())
                    .setUnlocked(AttributeRules.isUnlocked(rule, level))
                    .setUnlockLevel(pool.getUnlockLevel())
                    .setResetCostGold(resetCost(pool, level)));
        }
        for (AttributeDimensionTable dimension : tables.playerDimensions()) {
            long allocated = scheme != null ? scheme.allocated(dimension.getId()) : 0;
            AttributeDimensionInfo.Builder info = AttributeDimensionInfo.newBuilder()
                    .setDimensionId(dimension.getId())
                    .setPoolId(dimension.getPoolId())
                    .setName(dimension.getName())
                    .setDesc(dimension.getDesc())
                    .setAllocated((int) allocated)
                    // 面板值是点数：自然成长 + 已分配 + 外部加成（Java 版尚无外部加成）
                    .setValue(Integer.toUnsignedLong(dimension.getBasePerLevel()) * level + allocated)
                    .setSort(dimension.getSort());
            AttributePoolTable pool = tables.poolOf(dimension);
            if (pool != null) {
                info.setCap(pool.getDimensionCap());
            }
            panel.addDimensions(info);
        }
        for (Scheme s : attributes.schemes()) {
            panel.addSchemes(AttributeSchemeInfo.newBuilder().setSchemeId(s.id()).setName(s.name()));
        }
        Derived derived = attributes.derived();
        panel.setDerived(DerivedAttributeInfo.newBuilder()
                .setMaxHealth(derived.maxHealth())
                .setMaxMana(derived.maxMana())
                .setPhysicalAttack(derived.physicalAttack())
                .setMagicAttack(derived.magicAttack())
                .setSpeed(derived.speed())
                .setDefense(derived.defense())
                .setHealth(attributes.health())
                .setMana(attributes.mana()));
        return panel.build();
    }

    // ------------------------------------------------------------------ 写操作

    /**
     * 确认加点：{@code target} 是该池「目标已分配」（全量、幂等；缺省维度视为不变；值按 uint32 原样传入）。
     * 拒绝码按基线顺序：池 25000 → 方案 25006 → 未解锁 25001 → 逐维（维度号升序）不属本池 25002 / 减少 25004 / 超上限 25005
     * → 无变化 25014 → 超剩余 25003。
     */
    public int allocate(ScenePlayer player, int poolId, Map<Integer, Integer> target) {
        int writable = checkWritable(player);
        if (writable != 0) {
            return writable;
        }
        AttributePoolTable pool = tables.playerPool(poolId);
        if (pool == null) {
            return POOL_NOT_FOUND;
        }
        Scheme scheme = player.attributes().activeScheme();
        if (scheme == null) {
            return SCHEME_NOT_FOUND;
        }
        Map<Integer, Long> current = new LinkedHashMap<>();
        for (AttributeDimensionTable dimension : tables.dimensionsOf(poolId)) {
            current.put(dimension.getId(), scheme.allocated(dimension.getId()));
        }
        // 基线按 std::map（维度号的无符号升序）逐项校验，第一处违规定拒绝码
        Map<Integer, Long> sortedTarget = new TreeMap<>(Integer::compareUnsigned);
        target.forEach((dimension, points) -> sortedTarget.put(dimension, Integer.toUnsignedLong(points)));
        int level = level(player);
        long remaining = remaining(scheme, pool, level);
        Validation validation = AttributeRules.validateAllocation(AttributeTables.toRule(pool), level, current,
                sortedTarget, remaining);
        if (validation.error() != AllocError.OK) {
            return tipOf(validation.error());
        }
        sortedTarget.forEach(scheme::setAllocated);
        recalculate(player, RecalcReason.ALLOCATE);
        log.debug("加点 player={} pool={} scheme={} delta={} remaining={}", player.playerId(), poolId, scheme.id(),
                validation.delta(), remaining - validation.delta());
        return 0;
    }

    /** 洗点：清空当前方案里该池全部分配，点数返还；按表扣金币（先扣费成功再清点，扣费失败什么都不动）。 */
    public int reset(ScenePlayer player, int poolId) {
        int writable = checkWritable(player);
        if (writable != 0) {
            return writable;
        }
        AttributePoolTable pool = tables.playerPool(poolId);
        if (pool == null) {
            return POOL_NOT_FOUND;
        }
        Scheme scheme = player.attributes().activeScheme();
        if (scheme == null) {
            return SCHEME_NOT_FOUND;
        }
        if (usedPoints(scheme, poolId) == 0) {
            return NOTHING_TO_CHANGE;
        }
        long cost = resetCost(pool, level(player));
        int paid = payGold(player, cost, AssetAudit.Reason.ATTRIBUTE_RESET);
        if (paid != 0) {
            return paid;
        }
        for (AttributeDimensionTable dimension : tables.dimensionsOf(poolId)) {
            scheme.setAllocated(dimension.getId(), 0);
        }
        recalculate(player, RecalcReason.RESET);
        log.debug("洗点 player={} pool={} scheme={} cost_gold={}", player.playerId(), poolId, scheme.id(), cost);
        return 0;
    }

    /**
     * 自动加点：按职业方案（缺则通用兜底）把该池剩余点算成建议「目标已分配」，只算不落。不受写前置约束（同基线）。
     * 拒绝码顺序：池 25000 → 未解锁 25001 → 方案 25006 → 没有可用方案 25013 → 没有剩余点 25014。
     */
    public AutoAllocation autoAllocate(ScenePlayer player, int poolId) {
        AttributePoolTable pool = tables.playerPool(poolId);
        if (pool == null) {
            return new AutoAllocation(POOL_NOT_FOUND, Map.of());
        }
        int level = level(player);
        PoolRule rule = AttributeTables.toRule(pool);
        if (!AttributeRules.isUnlocked(rule, level)) {
            return new AutoAllocation(POOL_LOCKED, Map.of());
        }
        Scheme scheme = player.attributes().activeScheme();
        if (scheme == null) {
            return new AutoAllocation(SCHEME_NOT_FOUND, Map.of());
        }
        AttributeAutoPlanTable plan = tables.autoPlan(player.classId(), poolId);
        if (plan == null || plan.getDimensionCount() == 0) {
            return new AutoAllocation(NO_AUTO_PLAN, Map.of());
        }
        Map<Integer, Long> suggested = new LinkedHashMap<>();
        for (AttributeDimensionTable dimension : tables.dimensionsOf(poolId)) {
            suggested.put(dimension.getId(), scheme.allocated(dimension.getId()));
        }
        List<Integer> order = new ArrayList<>();
        List<Long> weights = new ArrayList<>();
        for (int i = 0; i < plan.getDimensionCount(); i++) {
            int dimensionId = plan.getDimension(i);
            if (!suggested.containsKey(dimensionId)) {
                continue; // 表配错池的维度直接忽略
            }
            order.add(dimensionId);
            weights.add(i < plan.getWeightCount() ? Integer.toUnsignedLong(plan.getWeight(i)) : 1L);
        }
        long remaining = remaining(scheme, pool, level);
        if (remaining == 0) {
            return new AutoAllocation(NOTHING_TO_CHANGE, Map.of());
        }
        AttributeRules.distributePoints(rule, order, weights, remaining, suggested);
        return new AutoAllocation(0, suggested);
    }

    /** 开新方案：空名按「方案N」（N = 现有方案数 + 1）；超出免费数扣金币；不切换当前方案、不重算。 */
    public SchemeCreation createScheme(ScenePlayer player, String name) {
        int writable = checkWritable(player);
        if (writable != 0) {
            return new SchemeCreation(writable, 0);
        }
        PlayerAttributes attributes = player.attributes();
        int count = attributes.schemes().size();
        if (count >= tables.maxSchemes()) {
            return new SchemeCreation(SCHEME_LIMIT, 0);
        }
        String finalName = name.isEmpty() ? defaultSchemeName(count + 1) : name;
        if (!isSchemeNameValid(finalName)) {
            return new SchemeCreation(SCHEME_NAME_INVALID, 0);
        }
        long cost = tables.createSchemeCost(count);
        int paid = payGold(player, cost, AssetAudit.Reason.ATTRIBUTE_SCHEME_CREATE);
        if (paid != 0) {
            return new SchemeCreation(paid, 0);
        }
        int schemeId = attributes.addScheme(finalName);
        log.debug("开新方案 player={} scheme={} cost_gold={}", player.playerId(), schemeId, cost);
        return new SchemeCreation(0, schemeId);
    }

    /** 切换方案：不存在 25006 → 已是当前 25010 → 冷却中 25008（按墙钟 Unix 秒）→ 切换并按比例重算。 */
    public int switchScheme(ScenePlayer player, int schemeId) {
        int writable = checkWritable(player);
        if (writable != 0) {
            return writable;
        }
        PlayerAttributes attributes = player.attributes();
        if (attributes.scheme(schemeId) == null) {
            return SCHEME_NOT_FOUND;
        }
        if (attributes.activeSchemeId() == schemeId) {
            return SCHEME_ALREADY_ACTIVE;
        }
        long now = Math.floorDiv(clock.epochMillis(), 1000);
        long cooldown = tables.switchCooldownSeconds();
        if (cooldown > 0 && attributes.lastSwitchTime() + cooldown > now) {
            return SWITCH_COOLDOWN;
        }
        attributes.switchTo(schemeId, now);
        recalculate(player, RecalcReason.SCHEME_SWITCH);
        log.debug("切换方案 player={} scheme={}", player.playerId(), schemeId);
        return 0;
    }

    /** 改名：先校验名字（不合法 25009，不做默认命名）再找方案（25006）；不查重名。 */
    public int renameScheme(ScenePlayer player, int schemeId, String name) {
        int writable = checkWritable(player);
        if (writable != 0) {
            return writable;
        }
        if (!isSchemeNameValid(name)) {
            return SCHEME_NAME_INVALID;
        }
        Scheme scheme = player.attributes().scheme(schemeId);
        if (scheme == null) {
            return SCHEME_NOT_FOUND;
        }
        scheme.rename(name);
        return 0;
    }

    /**
     * GM 设等级（1..85，越界 1005）。等级不变也照常走一遍（同基线）。成功后已按「等级变化」重算；
     * 推 170 等连带由调用方按基线顺序做（见 {@code AttributeFeature}）。
     */
    public int gmSetLevel(ScenePlayer player, int level) {
        int writable = checkWritable(player);
        if (writable != 0) {
            return writable;
        }
        if (!PlayerLevels.isValid(level)) {
            return INVALID_PARAMETER;
        }
        int old = player.level();
        player.setLevel(level);
        log.info("GM 设等级 player={} {} -> {}", player.playerId(), old, level);
        recalculate(player, RecalcReason.LEVEL_CHANGED);
        return 0;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 写操作统一前置（基线 CheckWritable，加点 / 洗点 / 开方案 / 切方案 / 改名 / GM 设等级都过这里，自动加点不过）。
     * 基线在这里拒绝两种情形：跨 zone / 跨节点交接冻结中（1005）与回合制战斗在途（25011 kAttributeInBattle）。
     * Java 版两样都还不存在（交接冻结随路线图 5.2 / 5.4、回合制战斗随 6.3），接入时在这里补上；目前恒放行。
     */
    private int checkWritable(ScenePlayer player) {
        return 0;
    }

    /** 扣金币（cost 为 uint64）：0 不扣；余额不足回 25012（基线 CanAfford）；扣成功记一条流水。 */
    private int payGold(ScenePlayer player, long cost, AssetAudit.Reason reason) {
        if (cost == 0) {
            return 0;
        }
        Wallet wallet = player.wallet();
        if (Long.compareUnsigned(wallet.balance(Wallet.GOLD), cost) < 0) {
            return GOLD_NOT_ENOUGH;
        }
        Wallet.Change change = wallet.deduct(Wallet.GOLD, cost);
        if (!change.ok()) {
            return change.tipId();
        }
        audit.currencyChanged(player.playerId(), Wallet.GOLD, -cost, change.before(), change.after(), reason);
        return 0;
    }

    /** 当前等级下重置本池的金币：低于免费等级免费（0 = 不设免费等级）。 */
    private static long resetCost(AttributePoolTable pool, int level) {
        long freeBelow = Integer.toUnsignedLong(pool.getResetFreeBelowLevel());
        if (freeBelow > 0 && level < freeBelow) {
            return 0;
        }
        return pool.getResetCostGold();
    }

    private long remaining(Scheme scheme, AttributePoolTable pool, int level) {
        long total = AttributeRules.totalPoints(AttributeTables.toRule(pool), level, 0);
        long used = usedPoints(scheme, pool.getId());
        return total > used ? total - used : 0;
    }

    /** 方案里某池已用点数（超 uint32 饱和）。 */
    private long usedPoints(Scheme scheme, int poolId) {
        long used = 0;
        for (AttributeDimensionTable dimension : tables.dimensionsOf(poolId)) {
            used += scheme.allocated(dimension.getId());
        }
        return Math.min(used, AttributeRules.UINT32_MAX);
    }

    private static int level(ScenePlayer player) {
        return PlayerLevels.effective(player.level());
    }

    private static int tipOf(AllocError error) {
        return switch (error) {
            case OK -> 0;
            case POOL_LOCKED -> AttributeSystem.POOL_LOCKED;
            case DIMENSION_NOT_IN_POOL -> DIMENSION_NOT_FOUND;
            case CANNOT_DECREASE -> AttributeSystem.CANNOT_DECREASE;
            case CAP_EXCEEDED -> AttributeSystem.CAP_EXCEEDED;
            case NOT_ENOUGH_POINTS -> POINTS_NOT_ENOUGH;
            case NOTHING_TO_CHANGE -> AttributeSystem.NOTHING_TO_CHANGE;
        };
    }

    static String defaultSchemeName(int ordinal) {
        if (ordinal >= 1 && ordinal <= SCHEME_ORDINALS.length) {
            return "方案" + SCHEME_ORDINALS[ordinal - 1];
        }
        return "方案" + ordinal;
    }

    /**
     * 方案名：非空、码点数不超表定上限、不含控制字符（U+0000–U+001F、U+007F）、至少一个可见码点
     * （全空格 / 零宽空格 / U+3000 / U+FEFF 之类不算，与客户端 IsNullOrWhiteSpace 对齐）。
     * 非法 UTF-8 在协议解析层就被拒（请求整条丢弃），到不了这里。
     */
    boolean isSchemeNameValid(String name) {
        if (name.isEmpty()) {
            return false;
        }
        long codePoints = 0;
        boolean visible = false;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (cp < 0x20 || cp == 0x7F) {
                return false;
            }
            i += Character.charCount(cp);
            codePoints++;
            if (!isInvisible(cp)) {
                visible = true;
            }
        }
        return visible && codePoints <= tables.schemeNameMaxLen();
    }

    private static boolean isInvisible(int cp) {
        return cp == 0x20 || cp == 0xA0 || (cp >= 0x2000 && cp <= 0x200F) || cp == 0x2028 || cp == 0x2029
                || cp == 0x202F || cp == 0x205F || cp == 0x2060 || cp == 0x3000 || cp == 0xFEFF;
    }
}
