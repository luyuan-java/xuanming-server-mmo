package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.BattleBuffEntryOrBuilder;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattleSettlementData;
import com.game.proto.eBattleEventType;
import com.game.table.BuffTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private final boolean pve;
    private final BattleRandom rng;
    private final EventLog events = new EventLog();
    private final List<BattleUnit> units = new ArrayList<>();
    private final List<BattleMonsterDefeat> defeatedMonsters = new ArrayList<>();
    private final NavigableMap<Long, BattleSettlementData.Builder> settlements = new TreeMap<>(Long::compareUnsigned);
    /** uint32；正在收集 / 结算的回合号，从 1 开始（{@code engine.h:227}）。 */
    private int roundIndex = 1;

    /**
     * @param matchMode 请求里的 match_mode（uint32）；只有 4 / 5 算 PVE，其余一律非 PVE（{@code IsPveMatch}，{@code engine.cpp:1312-1315}）
     * @param seed      请求里的 seed（完整 uint64，{@code engine.cpp:70}）
     */
    BattleContext(BattleData data, int matchMode, long seed) {
        this.data = data;
        this.pve = matchMode == BattleConstants.MATCH_MODE_PVE_SOLO || matchMode == BattleConstants.MATCH_MODE_PVE_TEAM;
        this.rng = new BattleRandom(seed);
    }

    BattleData data() {
        return data;
    }

    boolean isPve() {
        return pve;
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
            if (Long.compareUnsigned(maxSpeed, other.speed()) < 0) {
                maxSpeed = other.speed();
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
     *   <li>暴击率 = clamp(critchance / 100, 0, 1)，critchance 是 uint64；</li>
     *   <li>与实时侧共用的暴击前伤害（目标等级：玩家 / 宝宝取快照等级，怪物取参考等级）；</li>
     *   <li>非 PVE 先乘 0.3；</li>
     *   <li>暴击率 &gt; 0 时掷一次 Rand01（R4），严格小于即暴击 ×2；暴击率为 0 不耗随机数；</li>
     *   <li>{@code std::max(d, 0.0)}，即 {@code d < 0.0 ? 0.0 : d}（NaN 原样）。</li>
     * </ol>
     */
    FinalDamage calculateFinalDamage(BattleUnit caster, BattleUnit target, double baseDamage, long attack,
                                     double attackMultiplier) {
        // std::clamp(v, 0.0, 1.0) = v < 0 ? 0 : (1 < v ? 1 : v)；不用 Math.clamp，以免 NaN / -0.0 行为不同
        double critChance = Unsigned.toDouble(caster.critChance()) / 100.0;
        critChance = critChance < 0.0 ? 0.0 : (1.0 < critChance ? 1.0 : critChance);
        double finalDamage = CombatDamageRules.damageBeforeCritical(baseDamage, caster.strength(), attack, attackMultiplier,
                target.armor(), target.defense(), target.resistance(), target.level());
        if (!pve) {
            finalDamage *= BattleConstants.PVP_DAMAGE_SCALE;
        }
        boolean critical = false;
        if (critChance > 0.0 && rng.rand01() < critChance) {
            finalDamage *= 2;
            critical = true;
        }
        return new FinalDamage(finalDamage < 0.0 ? 0.0 : finalDamage, critical);
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
        target.state().clearBuffs();
        events.append(eBattleEventType.BATTLE_EVENT_DEATH, target.actorId(), target.actorId());
    }
}
