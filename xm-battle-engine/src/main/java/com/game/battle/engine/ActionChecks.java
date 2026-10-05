package com.game.battle.engine;

import com.game.proto.BattleAction;
import com.game.proto.eBattleActionType;
import com.game.table.BagErrorTip;
import com.game.table.CommonErrorTip;
import com.game.table.ItemTable;
import com.game.table.SkillErrorTip;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 行动校验链（基线 {@code CheckActionPrerequisites} 及其各步，{@code engine.cpp:386-602}；规格 §2.5、§4.2–§4.6、§6.1）。
 *
 * <p>提交期（{@code submitAction} / {@code validateAction}）与出手期（技能重验、道具重验）共用这一份代码：
 * 两处判据一旦分叉，就会出现「客户端收到成功、实际没落账」或「提交时说行、出手时落空」（{@code engine.h:43-46}）。
 * 全部零副作用、不耗随机数。返回 1000 或 tip 码（与节点 error_message.id 同域）。
 *
 * <p>不复用 xm-scene 的 {@code SkillRules}：缺许可行时的回码（这里 1002，实时侧 1001）、状态集合、目标语义都不同，
 * 而且引擎是纯库（规格 §11.3 第 11 条）。
 */
final class ActionChecks {

    static final int SUCCESS = CommonErrorTip.common_error.kSuccess_VALUE;
    static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int ENTITY_INVALID = CommonErrorTip.common_error.kThisEntityIsInvalid_VALUE;
    static final int BAG_INSUFFICIENT_ITEMS = BagErrorTip.bag_error.kBagInsufficientItems_VALUE;
    static final int SKILL_INVALID_TARGET_ID = SkillErrorTip.skill_error.kSkillInvalidTargetId_VALUE;
    static final int SKILL_INVALID_TARGET = SkillErrorTip.skill_error.kSkillInvalidTarget_VALUE;
    static final int SKILL_COOLDOWN_NOT_READY = SkillErrorTip.skill_error.kSkillCooldownNotReady_VALUE;
    /** 「当前状态不可施放」；法力不足也借用这个码（基线 tip 表没有专用码，{@code engine.cpp:497-501}）。 */
    static final int SKILL_CANNOT_CAST_IN_STATE = SkillErrorTip.skill_error.kSkillCannotBeCastInCurrentState_VALUE;
    static final int SKILL_STUN_RESTRICTION = SkillErrorTip.skill_error.kSkillCannotBeCastStunRestriction_VALUE;

    private final BattleContext ctx;
    private final ItemLedger ledger;

    ActionChecks(BattleContext ctx, ItemLedger ledger) {
        this.ctx = ctx;
        this.ledger = ledger;
    }

    /**
     * 校验链顶层（{@code engine.cpp:386-440}）：先 {@link #checkState}，再按 action_type 的 <strong>int 值</strong>分派
     * （未知值走 default，与 C++ 的开放枚举一致）。ATTACK / DEFEND 不校验目标（普攻目标失效、甚至指向队友，都由结算期重选）；
     * FLEE 只在 PVE 可用；ITEM 走 {@link #checkItemUse}；SKILL 走技能链；NONE 与未知值 1005。
     */
    int checkActionPrerequisites(BattleUnit actor, BattleAction action) {
        int stateResult = checkState(actor);
        if (stateResult != SUCCESS) {
            return stateResult;
        }
        switch (action.getActionTypeValue()) {
            case eBattleActionType.BATTLE_ACTION_ATTACK_VALUE, eBattleActionType.BATTLE_ACTION_DEFEND_VALUE -> {
                return SUCCESS;
            }
            case eBattleActionType.BATTLE_ACTION_FLEE_VALUE -> {
                return ctx.isPve() ? SUCCESS : INVALID_PARAMETER;
            }
            case eBattleActionType.BATTLE_ACTION_ITEM_VALUE -> {
                return checkItemUse(actor, action);
            }
            case eBattleActionType.BATTLE_ACTION_SKILL_VALUE -> {
                return checkSkill(actor, action);
            }
            default -> {
                return INVALID_PARAMETER;
            }
        }
    }

    /**
     * SKILL 校验链（{@code engine.cpp:403-436}；规格 §4.2），顺序固定、命中第一条即返回：
     * 表行缺失（含 id 0）1001 → 不在本人技能列表里 1005 → 黑名单类型 7004 → 目标 7001 → 冷却 7003 → 等级（桩）→
     * 沉默许可 1002 或格值 → 法力 7004。
     */
    private int checkSkill(BattleUnit actor, BattleAction action) {
        Optional<SkillTable> skillRow = ctx.data().skill(action.getSkillTableId());
        if (skillRow.isEmpty()) {
            return INVALID_TABLE_ID;
        }
        SkillTable row = skillRow.get();
        // 快照携带的技能列表即战斗内可用技能全集（线性查找）
        if (!actor.state().getSkillTableIdsList().contains(action.getSkillTableId())) {
            return INVALID_PARAMETER;
        }
        // 第二道闸：开局已剔除不可施放的类型，这里防有人绕过 skill_table_ids
        if (!BattleRules.isTurnBattleCastableSkill(row)) {
            return SKILL_CANNOT_CAST_IN_STATE;
        }
        int result = validateSkillTarget(row, action.getTargetId());
        if (result != SUCCESS) {
            return result;
        }
        result = checkCooldown(actor, row);
        if (result != SUCCESS) {
            return result;
        }
        result = checkPlayerLevel(actor, row);
        if (result != SUCCESS) {
            return result;
        }
        result = checkBuff(actor, row);
        if (result != SUCCESS) {
            return result;
        }
        return checkSkillCost(actor, row);
    }

    /**
     * 道具校验（{@code CheckItemUse}，{@code engine.cpp:442-480}；规格 §6.1），调用前已过 {@link #checkState}：
     * 表行缺失 1001 → 不能在战斗中使用 1005 → 回血回蓝两列都是 0 1005 → 本人副本没有余量 6007 → PVP 已用满 5 次 1005 →
     * 目标（非 0、非自己时）不存在或已死 / 已逃 7001、是敌方 7002。沉默不限制道具；目标可以是同队的玩家或宝宝。
     */
    int checkItemUse(BattleUnit actor, BattleAction action) {
        Optional<ItemTable> itemRow = ctx.data().item(action.getItemTableId());
        if (itemRow.isEmpty()) {
            return INVALID_TABLE_ID;
        }
        ItemTable row = itemRow.get();
        if (row.getBattleUsable() == 0) {
            return INVALID_PARAMETER;
        }
        if (row.getBattleHealHp() == 0 && row.getBattleHealMp() == 0) {
            return INVALID_PARAMETER;
        }
        if (ledger.findItemEntry(actor.actorId(), action.getItemTableId()) == null) {
            return BAG_INSUFFICIENT_ITEMS;
        }
        if (!ctx.isPve() && ledger.reachedPvpUseLimit(actor.actorId())) {
            return INVALID_PARAMETER;
        }
        long targetId = action.getTargetId();
        if (targetId != 0 && targetId != actor.actorId()) {
            BattleUnit target = ctx.findActor(targetId);
            if (target == null || !target.isActive()) {
                return SKILL_INVALID_TARGET_ID;
            }
            if (target.teamIndex() != actor.teamIndex()) {
                return SKILL_INVALID_TARGET;
            }
        }
        return SUCCESS;
    }

    /** 已死或已逃 1009；身上有眩晕(30) 或冰冻(52) 类型的 buff 7006（{@code CheckState}，{@code engine.cpp:593-602}）。 */
    int checkState(BattleUnit actor) {
        if (actor.isDead() || actor.fled()) {
            return ENTITY_INVALID;
        }
        if (ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_STUN)
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_FREEZE)) {
            return SKILL_STUN_RESTRICTION;
        }
        return SUCCESS;
    }

    /**
     * 目标校验（{@code ValidateSkillTarget}，{@code engine.cpp:505-533}；规格 §4.3）：
     * <ol>
     *   <li>零目标闸：targeting_mode 非空且目标为 0 → 7001（无目标技能、AOE 技能也会被拦）；</li>
     *   <li>按 targeting_mode 的顺序逐个处理位号（{@code mode = 1 << 位号}）：无目标(1) / AOE(4) 立即通过、<strong>不查目标</strong>；
     *       不是指向性(2) 跳过；指向性：目标不存在或已死 / 已逃 → 7001，否则通过（不查阵营，也不禁止选自己）；</li>
     *   <li>所有位号都认不出：通过。</li>
     * </ol>
     * 只看<strong>第一个认得出的位号</strong>，与出手期按「任一位号是 AOE」判群攻不同源（真表技能 1 的 [1, 2]，规格 §11.1 第 4 条）。
     * 位号 ≥ 32 时 C++ {@code 1u << bit} 是未定义行为，x86 与 Java 都按低 5 位移位，结果一致。
     */
    private int validateSkillTarget(SkillTable row, long targetId) {
        List<Integer> modes = row.getTargetingModeList();
        if (!modes.isEmpty() && targetId == 0) {
            return SKILL_INVALID_TARGET_ID;
        }
        for (int modeBit : modes) {
            int targetingMode = 1 << modeBit;
            if (targetingMode == BattleConstants.TARGETING_NO_TARGET_REQUIRED
                    || targetingMode == BattleConstants.TARGETING_AREA_OF_EFFECT) {
                return SUCCESS;
            }
            if (targetingMode != BattleConstants.TARGETING_TARGETED_SKILL) {
                continue;
            }
            BattleUnit target = ctx.findActor(targetId);
            if (target == null || !target.isActive()) {
                return SKILL_INVALID_TARGET_ID;
            }
            return SUCCESS;
        }
        return SUCCESS;
    }

    /**
     * 冷却（{@code CheckCooldown}，{@code engine.cpp:535-550}；规格 §4.4）：冷却 map 里任一项同时满足「剩余回合 ≠ 0、该技能有表行、
     * 其 cooldown_id 等于待施放技能的 cooldown_id」→ 7003。结果只是「存在与否」，与遍历顺序无关。
     */
    private int checkCooldown(BattleUnit actor, SkillTable row) {
        for (Map.Entry<Integer, Integer> cooling : actor.state().getSkillCooldownRoundsMap().entrySet()) {
            if (cooling.getValue() == 0) {
                continue;
            }
            Optional<SkillTable> coolingRow = ctx.data().skill(cooling.getKey());
            if (coolingRow.isPresent() && coolingRow.get().getCooldownId() == row.getCooldownId()) {
                return SKILL_COOLDOWN_NOT_READY;
            }
        }
        return SUCCESS;
    }

    /** 等级校验：恒通过（桩，与实时侧同口径，{@code engine.cpp:552-558}；规格 §11.1 第 10 条）。 */
    private int checkPlayerLevel(BattleUnit actor, SkillTable row) {
        return SUCCESS;
    }

    /**
     * 沉默许可（{@code CheckBuff}，{@code engine.cpp:560-591}；规格 §4.5）：
     * <ol>
     *   <li>身上没有沉默(31) 类型的 buff → 通过；</li>
     *   <li>SkillPermission 第 1 行（沉默）缺失 → 1002；</li>
     *   <li>对技能的每个 skill_type 位号依次：位号 ≥ 行宽 → 1002；格值 0（没填）→ 1002；格值不是 1000 → 原样返回格值；</li>
     *   <li>全部通过或 skill_type 为空 → 1000。</li>
     * </ol>
     * 有意差异 D5：C++ 把位号 {@code static_cast<int32_t>} 后当下标，≥ 2^31 时是负下标（未定义行为）；Java 按无符号比较判 1002，
     * 与 xm-scene 一致。
     */
    private int checkBuff(BattleUnit actor, SkillTable row) {
        if (!ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_SILENCE)) {
            return SUCCESS;
        }
        Optional<SkillPermissionTable> permissionRow = ctx.data().skillPermission(BattleConstants.COMBAT_STATE_SILENCE);
        if (permissionRow.isEmpty()) {
            return INVALID_TABLE_DATA;
        }
        SkillPermissionTable permission = permissionRow.get();
        for (int skillTypeBit : row.getSkillTypeList()) {
            if (Integer.compareUnsigned(skillTypeBit, permission.getSkillTypeCount()) >= 0) {
                return INVALID_TABLE_DATA;
            }
            int cell = permission.getSkillType(skillTypeBit);
            // 0 是「这格没填」而不是任何 tip 码；原样回 0 会让客户端读成成功却没落账，坏表按坏表报
            if (cell == 0) {
                return INVALID_TABLE_DATA;
            }
            if (cell != SUCCESS) {
                return cell;
            }
        }
        return SUCCESS;
    }

    /** 法力：技能耗蓝（uint64）&gt; 当前法力（无符号比较）→ 7004（{@code CheckSkillCost}，{@code engine.cpp:495-503}）。 */
    private int checkSkillCost(BattleUnit actor, SkillTable row) {
        if (Long.compareUnsigned(BattleRules.skillManaCost(row), actor.mana()) > 0) {
            return SKILL_CANNOT_CAST_IN_STATE;
        }
        return SUCCESS;
    }
}
