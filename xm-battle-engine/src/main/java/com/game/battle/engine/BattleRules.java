package com.game.battle.engine;

import com.game.common.math.Unsigned;
import com.game.table.SkillTable;
import com.game.table.Skillcost_resource;

/**
 * 回合制战斗的纯规则函数（无状态、无表依赖）：时间换回合、技能可施放过滤、AOE 判定、耗蓝。
 * 引擎内部与 6.3 scene 出快照时共用同一份实现（规格 D7：可施放过滤只写一处）。
 *
 * <p>基线：{@code turn_battle_constants.h:151-162}（时间换回合）、{@code turn_battle_engine.cpp:482-493}（可施放）、
 * {@code :903-922}（AOE 与耗蓝）、{@code :1014-1031}（逃跑成功率）。数值口径照规格 §10.5：uint64 / uint32 按无符号解释。
 */
public final class BattleRules {

    private BattleRules() {
    }

    /**
     * 毫秒换回合，同 C++ {@code RoundsFromMilliseconds}（返回值是 uint32 位模式）：
     * <ol>
     *   <li>{@code r = (ms + 5999) / 6000}，按 uint64 运算，加法会回绕（{@code ms = 2^64 - 1} 时 r = 0）；</li>
     *   <li>r 为 0 时返回 1；</li>
     *   <li>否则截断为 uint32 —— 结果 ≥ 2^32 时<strong>可能截成 0</strong>（基线行为，照搬，规格 §11.1 第 13 条）。</li>
     * </ol>
     */
    public static int roundsFromMillis(long durationMs) {
        long rounds = Long.divideUnsigned(durationMs + (BattleConstants.ROUND_DURATION_MS - 1), BattleConstants.ROUND_DURATION_MS);
        return rounds == 0 ? 1 : (int) rounds;
    }

    /**
     * 秒换回合，同 C++ {@code RoundsFromSeconds}：{@code s <= 0} 返回 1，否则 {@code roundsFromMillis((uint64)(s × 1000.0))}，截断取整。
     *
     * <p>C++ 对 NaN、无穷与 ≥ 2^64 的毫秒数做 {@code static_cast<uint64_t>} 是未定义行为；Java 按有意差异 D5 给出口径：
     * {@code !(s > 0)}（含 NaN）→ 1；{@code s × 1000 ≥ 2^64}（含 +∞）→ 毫秒数按 UINT64_MAX 处理，结果为 1。
     */
    public static int roundsFromSeconds(double durationSeconds) {
        if (!(durationSeconds > 0)) {
            return 1;
        }
        return roundsFromMillis(Unsigned.fromDoubleSaturating(durationSeconds * 1000.0));
    }

    /**
     * 技能能否在回合制战斗里施放（黑名单）：skill_type 的任一位号是被动(0)、持续施法(2)、开关(3) 就不能；
     * 空列表与其余位号都能。开局过滤玩家技能、提交期校验、6.3 scene 出快照都用它。
     */
    public static boolean isTurnBattleCastableSkill(SkillTable skillRow) {
        for (int skillTypeBit : skillRow.getSkillTypeList()) {
            if (skillTypeBit == BattleConstants.SKILL_TYPE_BIT_PASSIVE
                    || skillTypeBit == BattleConstants.SKILL_TYPE_BIT_TOGGLE
                    || skillTypeBit == BattleConstants.SKILL_TYPE_BIT_CHANNEL) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否 AOE 技能（出手期按它选全体存活敌方）：targeting_mode 里任一位号满足 {@code (1 << bit) == AOE(4)}。
     *
     * <p>位号 ≥ 32 时 C++ 的 {@code 1u << bit} 是未定义行为；x86 与 Java 都按 {@code bit & 31} 移位，运行时结果一致，不另立口径。
     * 注意它与提交期的目标校验不同源（规格 §4.3、§11.1 第 4 条）。
     */
    public static boolean isAreaSkill(SkillTable skillRow) {
        for (int modeBit : skillRow.getTargetingModeList()) {
            if ((1 << modeBit) == BattleConstants.TARGETING_AREA_OF_EFFECT) {
                return true;
            }
        }
        return false;
    }

    /** 技能耗蓝（uint64）：cost_resource 里资源 id 为法力(1) 的各项 cost 之和（uint32 按无符号累加），其余资源忽略。 */
    public static long skillManaCost(SkillTable skillRow) {
        long cost = 0;
        for (Skillcost_resource entry : skillRow.getCostResourceList()) {
            if (entry.getCostResourceId() == BattleConstants.SKILL_COST_RESOURCE_MANA) {
                cost += Integer.toUnsignedLong(entry.getCostResourceCost());
            }
        }
        return cost;
    }

    /**
     * 逃跑成功率（{@code ExecuteFlee} 的公式部分，{@code turn_battle_engine.cpp:1018-1021}）：
     * {@code clamp(0.5 + (0.01/12) × (自身速度 − 存活敌方最高速度), 0.05, 0.95)}，速度按 uint64 换 double。
     * clamp 照 {@code std::clamp} 的比较顺序书写，不用 {@code Math.clamp}。
     */
    static double fleeChance(long selfSpeed, long maxAliveEnemySpeed) {
        double speedDiff = Unsigned.toDouble(selfSpeed) - Unsigned.toDouble(maxAliveEnemySpeed);
        double chance = BattleConstants.FLEE_BASE_CHANCE + BattleConstants.FLEE_SPEED_FACTOR * speedDiff;
        if (chance < BattleConstants.FLEE_MIN_CHANCE) {
            return BattleConstants.FLEE_MIN_CHANCE;
        }
        return BattleConstants.FLEE_MAX_CHANCE < chance ? BattleConstants.FLEE_MAX_CHANCE : chance;
    }
}
