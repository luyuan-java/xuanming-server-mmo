package com.game.scene.skill;

import com.game.common.combatai.StateMutexMatrix;
import com.game.common.combatai.StateMutexMatrix.StateExitHookRegistry;
import com.game.common.combatai.StateMutexMatrix.StateExitReason;
import com.game.scene.player.PlayerSkillState;
import com.game.scene.skill.SkillTables.SkillDef;
import com.game.table.CommonErrorTip;
import com.game.table.SkillErrorTip;
import java.util.List;
import java.util.Set;
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
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int INVALID_TARGET_ID = SkillErrorTip.skill_error.kSkillInvalidTargetId_VALUE;
    /** 目标在回合制战斗中（基线 kSkillInvalidTarget，skill.cpp:410-414）。 */
    static final int TARGET_IN_BATTLE = SkillErrorTip.skill_error.kSkillInvalidTarget_VALUE;
    /** 施法者在回合制战斗中（基线 kSkillCannotBeCastInCurrentState，skill.cpp:219-229）。 */
    static final int CASTER_IN_BATTLE = SkillErrorTip.skill_error.kSkillCannotBeCastInCurrentState_VALUE;

    /** 目标方式的位序号（基线 kNoTargetSkill / kTargetedSkill / kAreaOfEffectSkill = 1 &lt;&lt; 位序号）。 */
    static final int TARGET_NONE = 0;
    static final int TARGET_SINGLE = 1;
    static final int TARGET_AREA = 2;
    /** 行为状态上限（eActorState: 0=Combat, 1=TeamFollow, 2=Mounted）。 */
    static final int ACTOR_STATE_MAX = 3;

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
     * 其余方式跳过；都跳过也通过。不查距离、可见性、存活。
     *
     * @param targetExists 实体号是否是本节点上的玩家
     */
    static int checkTarget(SkillDef def, long targetId, LongPredicate targetExists) {
        return checkTarget(def, targetId, targetExists, entity -> false);
    }

    /**
     * 同上，另判目标在回合制战斗中 → 7002（与「不是玩家」的 7001 分开；排在目标类型之后，基线 skill.cpp:410-414，scene-battle-spec §7.13）。
     *
     * @param targetInBattle 实体号对应的本节点玩家是否有在途的回合制战斗
     */
    static int checkTarget(SkillDef def, long targetId, LongPredicate targetExists, LongPredicate targetInBattle) {
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
            if (!targetExists.test(targetId)) {
                return INVALID_TARGET_ID;
            }
            return targetInBattle.test(targetId) ? TARGET_IN_BATTLE : OK;
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

    /** 纯只读行为互斥预检（O(1) 位运算，零副作用），用于两阶段施法门禁阶段一。 */
    static int canPerformAction(StateMutexMatrix matrix, long activeMask, int action, int successState) {
        if (matrix == null || !matrix.hasAction(action)) {
            return INVALID_TABLE_ID;
        }
        if (successState != StateMutexMatrix.NO_MUTEX_STATE
                && Integer.compareUnsigned(successState, ACTOR_STATE_MAX) >= 0) {
            return INVALID_PARAMETER;
        }
        return matrix.canPerform(activeMask, action, OK, INVALID_TABLE_ID);
    }

    /**
     * 两阶段行为互斥执行（O(1) 位运算）：先校验入态合法性与互斥冲突，全部通过后按状态编号升序
     * 移除被「打断」的状态并触发退出钩子（先摘状态再回调，防止重入循环），最后进入成功状态。
     */
    static <E> int tryPerformAction(StateMutexMatrix matrix, PlayerSkillState state, int action, int successState,
                                    E owner, StateExitHookRegistry<E> exitHooks) {
        if (matrix == null || !matrix.hasAction(action)) {
            return INVALID_TABLE_ID;
        }
        return matrix.tryPerform(
                state.actionStateMask(),
                action,
                successState,
                ACTOR_STATE_MAX,
                OK,
                INVALID_TABLE_ID,
                INVALID_PARAMETER,
                interruptedState -> {
                    if (state.removeActionState(interruptedState) && exitHooks != null) {
                        exitHooks.notifyExited(owner, interruptedState, StateExitReason.INTERRUPTED);
                    }
                },
                state::addActionState);
    }

    /** 显式移除行为状态；仅当原先处于激活态时派发 {@link StateExitReason#REMOVED} 退出钩子。 */
    static <E> boolean removeActionState(PlayerSkillState state, int actorState, E owner,
                                         StateExitHookRegistry<E> exitHooks) {
        if (!state.removeActionState(actorState)) {
            return false;
        }
        if (exitHooks != null) {
            exitHooks.notifyExited(owner, actorState, StateExitReason.REMOVED);
        }
        return true;
    }

    /**
     * 战斗状态互斥（基线 CombatStateSystem::ValidateSkillUsage）：没有战斗状态直接放行；
     * 动作行缺失 1001；存在互斥状态时按最小状态编号稳定返回提示码。
     */
    static int validateCombatStates(StateMutexMatrix matrix, long combatMask, int action) {
        if (combatMask == 0L) {
            return OK;
        }
        if (matrix == null || !matrix.hasAction(action)) {
            return INVALID_TABLE_ID;
        }
        return matrix.canPerform(combatMask, action, OK, INVALID_TABLE_ID);
    }
}
