package com.game.scene.skill;

import com.game.scene.skill.SkillTables.SkillDef;
import com.game.scene.skill.SkillTables.StateCell;
import com.game.table.CommonErrorTip;
import com.game.table.SkillErrorTip;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongPredicate;

/**
 * 放技能的纯规则（基线 SkillSystem::ValidateTarget、ActorActionStateSystem::TryPerformAction、
 * CombatStateSystem::ValidateSkillUsage、SkillSystem::CheckBuff）。返回 0 = 通过，否则是拒绝码。
 *
 * <p>表里的提示码按基线口径：{@code kSuccess}(1000) 是放行。填 0 的格子基线会把 0 当错误码原样回（线上与成功分不出），
 * Java 版 fail-closed 回 1002 kInvalidTableData（同回合制引擎的口径；PARITY 记为差异，现有数据走不到）。
 */
final class SkillRules {

    static final int OK = 0;
    static final int TABLE_SUCCESS = CommonErrorTip.common_error.kSuccess_VALUE;
    static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    static final int INVALID_TARGET_ID = SkillErrorTip.skill_error.kSkillInvalidTargetId_VALUE;

    /** 目标方式的位序号（基线 kNoTargetSkill / kTargetedSkill / kAreaOfEffectSkill = 1 &lt;&lt; 位序号）。 */
    static final int TARGET_NONE = 0;
    static final int TARGET_SINGLE = 1;
    static final int TARGET_AREA = 2;
    /** 行为互斥表的模式：0 互斥、1 允许、2 打断（基线 eActionStateMode）。 */
    static final int ACTION_MUTEX = 0;
    static final int ACTION_INTERRUPT = 2;
    /** 战斗状态表的模式：0 允许、1 互斥（基线 eCombatStateMode，与行为互斥表相反）。 */
    static final int COMBAT_MUTEX = 1;

    private SkillRules() {
    }

    /** 表里的提示码：1000 放行；0 当配置错（1002）；其余原样拒绝。 */
    static int tableTip(int tip) {
        if (tip == TABLE_SUCCESS) {
            return OK;
        }
        return tip == 0 ? INVALID_TABLE_DATA : tip;
    }

    /**
     * 目标闸（基线 ValidateTarget）：目标方式非空而目标号为 0 → 7001（范围技能、认不出的方式也一样）；
     * 然后按目标方式的顺序，无目标 / 范围 → 通过，指定目标 → 目标实体要在本节点上（任意场景，自己也行），
     * 其余方式跳过；都跳过也通过。不查距离、可见性、存活。目标在回合制战斗中（7002）随 6.3 接入。
     *
     * @param targetExists 实体号是否是本节点上的玩家
     */
    static int checkTarget(SkillDef def, long targetId, LongPredicate targetExists) {
        if (!def.targetingModes().isEmpty() && targetId == 0) {
            return INVALID_TARGET_ID;
        }
        for (int mode : def.targetingModes()) {
            if (mode == TARGET_NONE || mode == TARGET_AREA) {
                return OK;
            }
            if (mode != TARGET_SINGLE) {
                continue;
            }
            return targetExists.test(targetId) ? OK : INVALID_TARGET_ID;
        }
        return OK;
    }

    /**
     * 技能许可（基线 CheckBuff → CanUseSkillInCurrentState）：每个战斗状态 × 技能的每个类型位，查 SkillPermission[状态]
     * 的第「位序号」格（原样位序号，不是掩码）。行缺失 1001，位序号超出格数 1002，格子按 {@link #tableTip} 判。
     */
    static int checkSkillPermission(SkillTables tables, SkillDef def, Set<Integer> combatStates) {
        for (int state : combatStates) {
            List<Integer> row = tables.skillPermissionRow(state);
            if (row == null) {
                return INVALID_TABLE_ID;
            }
            for (int bit : def.skillTypes()) {
                if (Integer.compareUnsigned(bit, row.size()) >= 0) {
                    return INVALID_TABLE_DATA;
                }
                int tip = tableTip(row.get(bit));
                if (tip != OK) {
                    return tip;
                }
            }
        }
        return OK;
    }

    /**
     * 行为互斥（基线 TryPerformAction）：行缺失 1001；没有任何状态时直接加上成功状态；否则按状态号升序检查冲突
     * （互斥格按 {@link #tableTip} 判，越界的状态放行；状态号同基线按 uint32 比），再删掉「打断」格对应的状态，最后加上成功状态。
     * 只有通过才改状态。
     */
    static int tryPerformAction(List<StateCell> row, TreeSet<Integer> states, int successState) {
        if (row == null) {
            return INVALID_TABLE_ID;
        }
        for (int state : states) {
            if (Integer.compareUnsigned(state, row.size()) >= 0) {
                continue;
            }
            StateCell cell = row.get(state);
            if (cell.mode() == ACTION_MUTEX) {
                int tip = tableTip(cell.tip());
                if (tip != OK) {
                    return tip;
                }
            }
        }
        states.removeIf(state -> Integer.compareUnsigned(state, row.size()) < 0
                && row.get(state).mode() == ACTION_INTERRUPT);
        states.add(successState);
        return OK;
    }

    /**
     * 战斗状态互斥（基线 ValidateSkillUsage）：没有战斗状态放行；行缺失 1001；按状态号升序，
     * 第一个互斥格就返回它的提示（1000 = 放行并结束检查），越界的状态跳过。
     */
    static int validateCombatStates(List<StateCell> row, Set<Integer> combatStates) {
        if (combatStates.isEmpty()) {
            return OK;
        }
        if (row == null) {
            return INVALID_TABLE_ID;
        }
        for (int state : combatStates) {
            if (Integer.compareUnsigned(state, row.size()) >= 0) {
                continue;
            }
            StateCell cell = row.get(state);
            if (cell.mode() == COMBAT_MUTEX) {
                return tableTip(cell.tip());
            }
        }
        return OK;
    }
}
