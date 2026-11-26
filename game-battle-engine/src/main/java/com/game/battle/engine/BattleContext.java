package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.BattleAction;
import com.game.proto.BattleBuffEntryOrBuilder;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattleSettlementData;
import com.game.proto.TournamentLeylineElement;
import com.game.proto.eBattleEventType;
import com.game.table.BuffTable;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 一局战斗的共享状态：表数据、全部单位、唯一的 RNG、事件日志、回合号、击杀簿、结算累积，外加几个跨职责共用的查询与战斗原语
 * （伤害公式、死亡）。{@link TurnBattleEngine}、{@link ActionChecks}、{@link BuffEngine}、{@link ItemLedger} 只是按职责拆开的文件，
 * 共用这一份状态与同一个 RNG，调用顺序严格照基线 {@code turn_battle_engine.cpp}（规格 §10.2）。
 *
 * <p>容器与顺序（规格 §1.1、§11.3 第 5 条）：
 * <ul>
 *   <li>{@link #units()}：按插入序（玩家 → 宝宝 → 怪物）稳定排列，死亡 / 逃跑的单位不删。目标候选、buff tick、快照、
 *       {@link #findActor} 都按它走，<strong>顺序即语义</strong>；</li>
 *   <li>击杀簿：按实际死亡转移的顺序追加，每条 count = 1；</li>
 *   <li>结算累积：player_id 按<strong>无符号</strong>升序（掉落按它遍历，{@code engine.cpp:1203}）。</li>
 * </ul>
 * 非线程安全，归属一个引擎实例。
 */
final class BattleContext {

    /** 伤害公式的结果：最终伤害（已 ≥ 0 或 NaN）与是否暴击。 */
    record FinalDamage(double value, boolean critical) {
    }

    private final BattleData data;
    private final int matchMode;
    private final boolean pve;
    private final boolean competitivePvp;
    private final BattleRandom rng;
    private final ClassSkillCombat classSkills;
    final ConfiguredBattle configured;
    final EncounterMechanics encounter;
    private final EventLog events = new EventLog();
    private final List<BattleUnit> units = new ArrayList<>();
    private final List<BattleMonsterDefeat> defeatedMonsters = new ArrayList<>();
    private final NavigableMap<Long, BattleSettlementData.Builder> settlements = new TreeMap<>(Long::compareUnsigned);
    /** 论剑 V4 驱散/净化成功计数（按施法者归属玩家统计，含出战宝宝）。 */
    private final NavigableMap<Long, Integer> totalDispelsLandedByPlayer = new TreeMap<>(Long::compareUnsigned);
    /** 回合排速时刻冻结的有效速度快照，供副目标策略「本回合排速最低」稳定读取。 */
    private final Map<Long, Long> roundStartSpeeds = new HashMap<>();
    /** 本回合已提交/补齐的行动只读视图，供 A41 玄镜映法优先复制目标本回合选定技能。 */
    private Map<Long, BattleAction> pendingActionsView = Map.of();
    /** uint32；正在收集 / 结算的回合号，从 1 开始（{@code engine.h:227}）。 */
    private int roundIndex = 1;
    private int maxRounds = BattleConstants.DEFAULT_MAX_ROUNDS;
    private TournamentLeylineElement activeTournamentLeyline = TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED;
    private int activeSeasonWeeklyAffixId = SeasonRules.WEEKLY_AFFIX_NONE;
    /** 本局战前 3 Ban 合并去重后的全局禁招集合（对双方人物与灵宠对称生效）。 */
    private final java.util.TreeSet<Integer> bannedSkillIds = new java.util.TreeSet<>(Integer::compareUnsigned);

    /**
     * @param matchMode 请求里的 match_mode（uint32）；只有 4 / 5 算 PVE，其余一律非 PVE（{@code IsPveMatch}，{@code engine.cpp:1312-1315}）
     * @param seed      请求里的 seed（完整 uint64，{@code engine.cpp:70}）
     */
    BattleContext(BattleData data, int matchMode, long seed) {
        this(data, matchMode, seed, false);
    }

    BattleContext(BattleData data, int matchMode, long seed, boolean competitivePvp) {
        this.data = data;
        this.matchMode = matchMode;
        this.pve = matchMode == BattleConstants.MATCH_MODE_PVE_SOLO || matchMode == BattleConstants.MATCH_MODE_PVE_TEAM;
        this.competitivePvp = competitivePvp;
        this.rng = new BattleRandom(seed);
        this.classSkills = new ClassSkillCombat(data);
        this.configured = new ConfiguredBattle(this, new BuffEngine(this));
        this.encounter = new EncounterMechanics(this);
    }

    BattleData data() {
        return data;
    }

    boolean isPve() {
        return pve;
    }

    boolean isCompetitivePvp() {
        return competitivePvp;
    }

    int maxRounds() {
        return maxRounds;
    }

    boolean isSkillBanned(int skillTableId) {
        return skillTableId != 0 && bannedSkillIds.contains(skillTableId);
    }

    void setBannedSkills(java.util.Collection<Integer> ids) {
        bannedSkillIds.clear();
        if (ids == null) {
            return;
        }
        for (int id : ids) {
            if (id != 0) {
                bannedSkillIds.add(id);
            }
        }
    }

    void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    int escalationStartRound() {
        if (BattleConstants.isGuildWarMatchMode(matchMode)) {
            return BattleConstants.GUILD_WAR_ESCALATION_START_ROUND;
        }
        if (!competitivePvp || Integer.compareUnsigned(maxRounds, BattleConstants.ESCALATION_MIN_ROUND_LIMIT) < 0) {
            return 0;
        }
        return maxRounds - BattleConstants.ESCALATION_TAIL_ROUNDS;
    }

    int escalationStacks() {
        int start = escalationStartRound();
        if (start == 0 || Integer.compareUnsigned(roundIndex, start) < 0) {
            return 0;
        }
        return roundIndex - start + 1;
    }

    long escalationDamagePct() {
        long step = BattleConstants.isGuildWarMatchMode(matchMode)
                ? BattleConstants.GUILD_WAR_ESCALATION_STEP_PCT
                : BattleConstants.ESCALATION_STEP_PCT;
        return 100L + Integer.toUnsignedLong(escalationStacks()) * step;
    }

    long escalationSupportPct() {
        long step = BattleConstants.isGuildWarMatchMode(matchMode)
                ? BattleConstants.GUILD_WAR_ESCALATION_STEP_PCT
                : BattleConstants.ESCALATION_STEP_PCT;
        long penalty = Integer.toUnsignedLong(escalationStacks()) * step;
        return penalty >= 100L - BattleConstants.ESCALATION_SUPPORT_FLOOR_PCT
                ? BattleConstants.ESCALATION_SUPPORT_FLOOR_PCT
                : 100L - penalty;
    }

    TournamentLeylineElement activeTournamentLeyline() {
        return activeTournamentLeyline;
    }

    void setActiveTournamentLeyline(TournamentLeylineElement element) {
        this.activeTournamentLeyline = element == null
                ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED
                : element;
    }

    int activeSeasonWeeklyAffixId() {
        return activeSeasonWeeklyAffixId;
    }

    void setActiveSeasonWeeklyAffixId(int affixId) {
        this.activeSeasonWeeklyAffixId = SeasonRules.isValidWeeklyAffixId(affixId)
                ? affixId
                : SeasonRules.WEEKLY_AFFIX_NONE;
    }

    boolean hasAnyActiveShield(BattleUnit target) {
        if (target == null) {
            return false;
        }
        if (Long.compareUnsigned(target.tournamentEarthShield(), 0L) > 0) {
            return true;
        }
        for (var entry : target.state().getBuffsList()) {
            if (entry.getRemainingCapacity() == 0L) {
                continue;
            }
            var row = data.buff(entry.getBuffTableId());
            if (row.isPresent() && row.get().getBattleOp() == BattleEffect.SHIELD) {
                return true;
            }
        }
        return false;
    }

    long landedHeal(BattleUnit target, double rawHeal) {
        if (activeTournamentLeyline == TournamentLeylineElement.TOURNAMENT_LEYLINE_WOOD
                && rawHeal > 0.0 && Double.isFinite(rawHeal)) {
            rawHeal *= 110.0 / 100.0;
        }
        if (escalationStacks() > 0 && rawHeal > 0.0 && Double.isFinite(rawHeal)) {
            rawHeal *= escalationSupportPct() / 100.0;
        }
        return target.landedHeal(rawHeal);
    }

    long applyHeal(BattleUnit target, double rawHeal) {
        long healed = landedHeal(target, rawHeal);
        if (healed != 0L) {
            target.setHealth(target.health() + healed);
        }
        return healed;
    }

    BattleRandom rng() {
        return rng;
    }

    EventLog events() {
        return events;
    }

    List<BattleUnit> units() {
        return units;
    }

    List<BattleMonsterDefeat> defeatedMonsters() {
        return defeatedMonsters;
    }

    NavigableMap<Long, BattleSettlementData.Builder> settlements() {
        return settlements;
    }

    int roundIndex() {
        return roundIndex;
    }

    /** 回合号加 1（uint32 回绕）。 */
    void advanceRound() {
        roundIndex++;
    }

    // ---- 查询辅助（engine.cpp:1662-1765） ----

    /** 线性扫描（{@code FindActor}，{@code engine.cpp:1662-1678}）；找不到返回 null。 */
    BattleUnit findActor(long actorId) {
        for (BattleUnit unit : units) {
            if (unit.actorId() == actorId) {
                return unit;
            }
        }
        return null;
    }

    /** 该队现有单位数（玩家、宝宝、怪物都算）= 下一个阵位（{@code NextFormationSlot}，{@code engine.cpp:299-309}）。 */
    int nextFormationSlot(int teamIndex) {
        int count = 0;
        for (BattleUnit unit : units) {
            if (unit.teamIndex() == teamIndex) {
                count++;
            }
        }
        return count;
    }

    /** 按插入序取异队、存活、未逃的单位（{@code CollectAliveEnemyIds}，{@code engine.cpp:1747-1756}）；候选顺序稳定，RandIndex 才确定。 */
    long[] collectAliveEnemyIds(BattleUnit actor) {
        long[] ids = new long[units.size()];
        int count = 0;
        for (BattleUnit other : units) {
            if (other.teamIndex() != actor.teamIndex() && other.isActive()) {
                ids[count++] = other.actorId();
            }
        }
        return count == ids.length ? ids : Arrays.copyOf(ids, count);
    }

    /** 存活敌方的最高速度（无符号取大），没有存活敌方时为 0（{@code MaxAliveEnemySpeed}，{@code engine.cpp:1736-1745}）。 */
    long maxAliveEnemySpeed(BattleUnit actor) {
        long maxSpeed = 0;
        for (BattleUnit other : units) {
            if (other.teamIndex() == actor.teamIndex() || !other.isActive()) {
                continue;
            }
            // std::max(maxSpeed, speed) = maxSpeed < speed ? speed : maxSpeed（uint64）
            long speed = effectiveSpeed(other);
            if (Long.compareUnsigned(maxSpeed, speed) < 0) {
                maxSpeed = speed;
            }
        }
        return maxSpeed;
    }

    /** 该队没有存活未逃的单位（宝宝也算），全员逃跑同样算覆灭（{@code SideWiped}，{@code engine.cpp:1758-1765}）。 */
    boolean sideWiped(int teamIndex) {
        for (BattleUnit unit : units) {
            if (unit.teamIndex() == teamIndex && unit.isActive()) {
                return false;
            }
        }
        return true;
    }

    /** 单位身上有没有该类型的 buff；表行缺失的条目忽略（{@code ActorHasBuffOfType}，{@code engine.cpp:1726-1734}）。 */
    boolean actorHasBuffOfType(BattleUnit actor, int buffType) {
        int count = actor.state().getBuffsCount();
        for (int index = 0; index < count; index++) {
            BattleBuffEntryOrBuilder buff = actor.state().getBuffsOrBuilder(index);
            Optional<BuffTable> row = data.buff(buff.getBuffTableId());
            if (row.isPresent() && row.get().getBuffType() == buffType) {
                return true;
            }
        }
        return false;
    }

    // ---- 战斗原语 ----

    long effectivePhysicalAttack(BattleUnit actor) {
        double multiplier = classSkills.statMultiplier(actor, ClassSkillCombat.Stat.PHYSICAL_ATTACK, 0)
                * Math.max(0.1, 1 + configured.modifier(actor, BattleEffect.PHYSICAL_ATTACK));
        if (actor.isBoss() && encounter.isBossDisheartened()) {
            multiplier *= 0.85;
        }
        return multiplier == 1 ? actor.physicalAttack() : ClassSkillRules.floorRatio(actor.physicalAttack(), multiplier);
    }

    long effectiveDefense(BattleUnit actor) {
        double multiplier = classSkills.statMultiplier(actor, ClassSkillCombat.Stat.DEFENSE, 0)
                * Math.max(0.1, 1 + configured.modifier(actor, BattleEffect.DEFENSE)
                + configured.modifier(actor, BattleEffect.SELF_GROWING_DEFENSE));
        if (encounter.isActorSundered(actor.actorId())) {
            multiplier *= 0.80;
        }
        return multiplier == 1 ? actor.defense() : ClassSkillRules.floorRatio(actor.defense(), multiplier);
    }

    long effectiveSpeed(BattleUnit actor) { return effective(actor, actor.speed(), ClassSkillCombat.Stat.SPEED, BattleEffect.SPEED); }

    void snapshotRoundStartSpeeds() {
        roundStartSpeeds.clear();
        for (BattleUnit actor : units) {
            if (actor.isActive()) {
                roundStartSpeeds.put(actor.actorId(), effectiveSpeed(actor));
            }
        }
    }

    long roundStartSpeed(BattleUnit actor) {
        Long frozen = roundStartSpeeds.get(actor.actorId());
        return frozen != null ? frozen : effectiveSpeed(actor);
    }

    long effectiveMagicAttack(BattleUnit actor) {
        double mult = Math.max(0.1, 1.0 + configured.modifier(actor, BattleEffect.MAGIC_ATTACK));
        return Unsigned.fromDoubleSaturating(Unsigned.toDouble(actor.magicAttack()) * mult);
    }

    /** 承受比例越低说明防御越厚，返回 1 - received 作为防御厚度分，便于按「最低物防/法防」升序挑选脆皮。 */
    double effectiveMitigationScore(BattleUnit actor, boolean isMagic) {
        long resistBonus = isMagic ? actor.combat().getMagicResist() : actor.combat().getPhysicalResist();
        int equipResist = isMagic
                ? actor.state().getEquipmentEffects().getMagicResistBps()
                : actor.state().getEquipmentEffects().getPhysicalResistBps();
        long legacyResist = EquipmentCombatRules.addSaturated(actor.resistance(), resistBonus);
        double resistBps = Unsigned.toDouble(legacyResist) * 100.0 + Integer.toUnsignedLong(equipResist);
        double coefficient = 360.0 + 120.0 * Math.clamp(Integer.toUnsignedLong(actor.level()), 1L, 85L);
        double received = Math.max(0.4,
                coefficient / (Unsigned.toDouble(actor.armor()) + Unsigned.toDouble(effectiveDefense(actor)) + coefficient)
                        * (1.0 - Math.clamp(resistBps / 10000.0, 0.0, 1.0)));
        return 1.0 - Math.clamp(received, 0.0, 1.0);
    }

    private long effective(BattleUnit actor, long base, ClassSkillCombat.Stat stat, int modifier) {
        double multiplier = classSkills.statMultiplier(actor, stat, 0) * Math.max(0.1, 1 + configured.modifier(actor, modifier));
        return multiplier == 1 ? base : ClassSkillRules.floorRatio(base, multiplier);
    }

    double outgoingHealBonus(BattleUnit actor, com.game.proto.SupportEffectBreakdown.Builder sources) {
        return classSkills.outgoingHealBonus(actor,sources);
    }

    double outgoingHealBonus(BattleUnit actor) { return classSkills.outgoingHealBonus(actor); }

    double classSkillHealAmount(BattleUnit caster, SkillTable skill, int rank, BattleUnit target) {
        return classSkills.healAmount(caster, skill, rank, target);
    }

    /**
     * 命中判定骨架（{@code RollHit}，{@code engine.cpp:1790-1802}）：命中率固定 {@link BattleConstants#BASE_HIT_RATE} = 100，
     * 短路返回、不耗随机数。二期接表时在这里减去闪避并掷骰（位于暴击掷骰之前，接入要同步刷新回放基线）；骨架照基线保留。
     */
    boolean rollHit(BattleUnit caster, BattleUnit target) {
        if (BattleConstants.BASE_HIT_RATE >= 100) {
            return true;
        }
        return rng.rand01() * 100.0 < (double) BattleConstants.BASE_HIT_RATE;
    }

    /**
     * 最终伤害（{@code CalculateFinalDamage}，{@code engine.cpp:1317-1343}；规格 §3.7）：
     * <ol>
     *   <li>暴击率 = clamp((基础 + 对应伤害类型装备加成) / 100, 0, 1)，加法按 uint64 饱和；</li>
     *   <li>与实时侧共用的暴击前伤害，目标基础抗性叠加对应装备抗性，护甲 / 职业增减益后的有效防御 / 抗性共用 60% 减伤封顶；</li>
     *   <li>非 PVE 先乘 0.3；</li>
     *   <li>暴击率 &gt; 0 时掷一次 Rand01（R4），严格小于即暴击 ×2；暴击率为 0 不耗随机数；</li>
     *   <li>{@code std::max(d, 0.0)}，即 {@code d < 0.0 ? 0.0 : d}（NaN 原样）。</li>
     * </ol>
     */
    FinalDamage calculateFinalDamage(BattleUnit caster, BattleUnit target, double baseDamage, long attack,
                                     double attackMultiplier, int damageType) {
        double finalDamage = damageBeforeCriticalRoll(caster, target, baseDamage, attack, attackMultiplier, damageType);
        double critChance = criticalChance(caster, damageType);
        if (encounter.bossWeeklyAffixId() == BossTrialWeeklyAffixRules.AFFIX_METAL
                && caster.teamIndex() == 0 && target.teamIndex() == 1
                && encounter.isActorExposed(target.actorId())) {
            critChance = Math.min(1.0, critChance + BossTrialWeeklyAffixRules.METAL_EXPOSED_CRIT_BONUS_PCT / 100.0);
        }
        boolean critical = false;
        // 满暴击依然掷一次，零概率短路；纯计算拆分不能平移本局后续 RNG 序列。
        if (critChance > 0.0 && rng.rand01() < critChance) {
            finalDamage *= BattleConstants.CRITICAL_DAMAGE_MULTIPLIER;
            critical = true;
        }
        return new FinalDamage(finalDamage < 0.0 ? 0.0 : finalDamage, critical);
    }

    /** 只算暴击前伤害，不落血、不掷骰，供执行路径及后续选择器共用。 */
    double damageBeforeCriticalRoll(BattleUnit caster, BattleUnit target, double baseDamage, long attack,
                                    double attackMultiplier, int damageType) {
        double penetration = Math.clamp(configured.modifier(caster, BattleEffect.DEFENSE_PENETRATION), 0.0, 0.5);
        double defenseKeep = 1.0 - penetration;
        long effectiveArmor = penetration > 0.0
                ? Unsigned.fromDoubleSaturating(Math.floor(Unsigned.toDouble(target.armor()) * defenseKeep))
                : target.armor();
        long targetDefense = effectiveDefense(target);
        long effectiveDefense = penetration > 0.0
                ? Unsigned.fromDoubleSaturating(Math.floor(Unsigned.toDouble(targetDefense) * defenseKeep))
                : targetDefense;
        double damage = CombatDamageRules.damageBeforeCritical(baseDamage, caster.strength(), attack, attackMultiplier,
                effectiveArmor, effectiveDefense,
                EquipmentCombatRules.resistance(target.resistance(), target.combat(), damageType), target.level());
        var equipment = target.state().getEquipmentEffects();
        int typed = damageType == CombatDamageRules.PHYSICAL_DAMAGE ? equipment.getPhysicalResistBps()
                : damageType == CombatDamageRules.MAGIC_DAMAGE ? equipment.getMagicResistBps() : 0;
        if (typed != 0) {
            long resistance = EquipmentCombatRules.resistance(target.resistance(),target.combat(),damageType);
            double coefficient = 360.0 + 120.0 * Math.clamp(Integer.toUnsignedLong(target.level()),1,85);
            double ratio = Math.max(0.4, coefficient / (Unsigned.toDouble(effectiveArmor) + Unsigned.toDouble(effectiveDefense) + coefficient)
                    * (1 - Math.clamp(Unsigned.toDouble(resistance) / 100 + typed / 10000.0,0,1)));
            damage *= ratio / CombatDamageRules.receivedRatio(effectiveArmor,effectiveDefense,resistance,target.level());
        }
        if (!pve) damage *= BattleConstants.PVP_DAMAGE_SCALE;
        // 锦标赛 V2 五行灵脉伤害缩放（与 C++ 引擎逐项对齐）：金锐破防 +4%、火炽 +6%、土厚减伤 -5%。
        switch (activeTournamentLeyline) {
            case TOURNAMENT_LEYLINE_METAL -> damage *= 104.0 / 100.0;
            case TOURNAMENT_LEYLINE_FIRE -> damage *= 106.0 / 100.0;
            case TOURNAMENT_LEYLINE_EARTH -> damage *= 95.0 / 100.0;
            default -> {
            }
        }
        // 赛季全员对称周环境词缀（对齐 C++ TurnBattleEngine::DamageBeforeCriticalRoll）。
        int openingReducePct = SeasonRules.openingGuardDamageReducePct(activeSeasonWeeklyAffixId, roundIndex);
        if (openingReducePct > 0) {
            damage *= (100.0 - openingReducePct) / 100.0;
        }
        int sharpBonusPct = SeasonRules.sharpTempoUnshieldedDamageBonusPct(
                activeSeasonWeeklyAffixId, hasAnyActiveShield(target));
        if (sharpBonusPct > 0) {
            damage *= (100.0 + sharpBonusPct) / 100.0;
        }
        return damage;
    }

    /** 对应伤害类型的暴击概率；读取装备和基础值，不消耗 RNG。 */
    double criticalChance(BattleUnit caster, int damageType) {
        // std::clamp(v, 0.0, 1.0) = v < 0 ? 0 : (1 < v ? 1 : v)；不用 Math.clamp，以免 NaN / -0.0 行为不同
        long critRate = EquipmentCombatRules.criticalRate(caster.critChance(), caster.combat(), damageType);
        if (activeTournamentLeyline == TournamentLeylineElement.TOURNAMENT_LEYLINE_METAL) {
            critRate = EquipmentCombatRules.addSaturated(critRate, 5L);
        }
        double critChance = Unsigned.toDouble(critRate) / 100.0;
        var equipment = caster.state().getEquipmentEffects();
        critChance += (damageType == CombatDamageRules.PHYSICAL_DAMAGE ? equipment.getPhysicalCritBps()
                : damageType == CombatDamageRules.MAGIC_DAMAGE ? equipment.getMagicCritBps() : 0) / 10000.0;
        return critChance < 0.0 ? 0.0 : (1.0 < critChance ? 1.0 : critChance);
    }

    /** 自动选技使用暴击期望原始伤害；防御姿态、剩余气血夹限由收益计算处理，不提前掷骰。 */
    double expectedDamage(BattleUnit caster, BattleUnit target, double baseDamage, long attack,
                          double attackMultiplier, int damageType) {
        double damage = damageBeforeCriticalRoll(caster, target, baseDamage, attack, attackMultiplier, damageType);
        return damage * (1.0 + criticalChance(caster, damageType) * (BattleConstants.CRITICAL_DAMAGE_MULTIPLIER - 1.0));
    }

    /**
     * 死亡（{@code HandleDeath}，{@code engine.cpp:1373-1396}；规格 §3.10）：只在「活着 → 0 血」的转移上处理一次。
     * <ol>
     *   <li>击杀簿：target 是 team 1 的怪物且 monster_table_id ≠ 0（兜底怪不记）、来源存在且是 team 0、归属者（来源是宝宝时取其主人，
     *       否则就是来源）存在且是 team 0 的玩家，才追加 {@code {monster_table_id, 1}}。来源已死 / 已逃仍算（如施毒者先阵亡）；</li>
     *   <li>置死亡、撤防御、<strong>清空 buff 且不发 BUFF_REMOVE</strong>；</li>
     *   <li>DEATH 事件的 source = target = 死者（不是凶手）。</li>
     * </ol>
     */
    void handleDeath(BattleUnit target, long sourceActorId) {
        if (target.isDead() || target.health() != 0) {
            return;
        }
        BattleUnit source = findActor(sourceActorId);
        BattleUnit owner = source != null && source.isPet() ? findActor(source.ownerPlayerId()) : source;
        if (target.isMonster() && target.teamIndex() == 1 && target.monsterTableId() != 0
                && source != null && source.teamIndex() == 0
                && owner != null && owner.isPlayer() && owner.teamIndex() == 0) {
            defeatedMonsters.add(BattleMonsterDefeat.newBuilder()
                    .setMonsterConfigId(target.monsterTableId())
                    .setCount(1)
                    .build());
        }
        target.state().setIsDead(true);
        target.setDefending(false);
        encounter.recordDeath(target.actorId());
        for (var artifact : target.state().getArtifactStatesBuilderList()) artifact.clearPendingAilmentKind().clearPendingRound();
        if (target.state().hasSeasonSealState()) target.state().getSeasonSealStateBuilder().clearPendingAilmentKind().clearPendingRound();
        if (!configured.hasEffects(target)) target.state().clearBuffs();
        else {
            for (var entry : List.copyOf(target.state().getBuffsList())) {
                var row = configured.row(entry);
                if (row == null || (row.getKeepOnDeath() == 0 && !configured.listener(entry) && !row.containsTag("Passive")))
                    configured.remove(target,entry.getBuffId());
            }
            configured.resetVolatileGrowingStacks(target);
        }
        events.append(eBattleEventType.BATTLE_EVENT_DEATH, target.actorId(), target.actorId());
        if (target.isMonster() && target.teamIndex() == 1 && BattleConstants.isBossTrialKind(encounter.bossTrialKind())) {
            encounter.handleBossTrialMonsterDeath(target);
        }
    }

    void recordDispelLanded(long casterActorId, int count) {
        if (casterActorId == 0L || count <= 0) {
            return;
        }
        BattleUnit caster = findActor(casterActorId);
        if (caster == null) {
            return;
        }
        long ownerId = caster.isPlayer() ? caster.actorId()
                : (caster.isPet() ? caster.ownerPlayerId() : 0L);
        if (ownerId != 0L) {
            totalDispelsLandedByPlayer.put(ownerId, totalDispelsLandedByPlayer.getOrDefault(ownerId, 0) + count);
        }
    }

    int totalDispelsLanded(long playerId) {
        return totalDispelsLandedByPlayer.getOrDefault(playerId, 0);
    }

    void bindPendingActions(Map<Long, BattleAction> view) {
        this.pendingActionsView = view == null ? Map.of() : view;
    }

    BattleAction pendingAction(long actorId) {
        return pendingActionsView.get(actorId);
    }
}
