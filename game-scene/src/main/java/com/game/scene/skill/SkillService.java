package com.game.scene.skill;

import static com.game.scene.world.SceneMessageIds.push;

import com.game.common.combatai.Fsm;
import com.game.common.combatai.StateMutexMatrix.ExitHook;
import com.game.common.combatai.StateMutexMatrix.StateExitHookRegistry;
import com.game.proto.ReleaseSkillRequest;
import com.game.proto.SelfActorStateFlagsS2C;
import com.game.proto.SkillInterruptedS2C;
import com.game.proto.SkillUsedS2C;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.BattleGate;
import com.game.scene.metrics.SceneMetrics.SkillResult;
import com.game.scene.player.PlayerSkillState;
import com.game.scene.player.PlayerSkillState.Cast;
import com.game.scene.player.PlayerSkillState.Phase;
import com.game.scene.skill.SkillTables.SkillDef;
import com.game.scene.world.SceneClock;
import com.game.scene.world.SceneMessageIds;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.SkillErrorTip;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 放技能（84；基线 SkillSystem::ReleaseSkill + 两阶段施法门禁，只在场景逻辑线程上调用）。
 *
 * <p>校验顺序严格两阶段：
 * <ol>
 *   <li><b>阶段一（纯只读预检，零副作用）</b>：技能存在且已拥有（1001）→ 施法者在回合制战斗中（7004）→ 目标（7001/7002）
 *       → 冷却（7003）→ 结算过期施法阶段并校验进行中的施法是否可打断（7000，不发 33）→ 技能许可（SkillPermission）
 *       → 行为互斥（ActorActionState 64 位位图）→ 战斗状态互斥（ActorActionCombatState 64 位位图）。任意一环拒绝零副作用。</li>
 *   <li><b>阶段二（原子提交）</b>：若存在进行中的旧施法，推 33 并取消 → 执行行为打断与退出钩子并进入「战斗」状态
 *       → 若主角状态位图发生变化，立即单播一次合并后的 {@code SelfActorStateFlagsS2C} 给主角本人
 *       → 广播 70 → 记冷却开始 → 经施法相位 {@link Fsm} 进入前摇。</li>
 * </ol>
 */
public final class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    static final int OK = SkillRules.OK;
    static final int UNINTERRUPTIBLE = SkillErrorTip.skill_error.kSkillUnInterruptible_VALUE;
    static final int COOLDOWN_NOT_READY = SkillErrorTip.skill_error.kSkillCooldownNotReady_VALUE;
    /** 行为：放技能（ActorActionState / ActorActionCombatState 的行号）。 */
    public static final int ACTION_USE_SKILL = 0;
    /** 行为状态（eActorState）：0 战斗、1 跟随、2 骑乘。 */
    public static final int STATE_COMBAT = 0;
    public static final int STATE_TEAM_FOLLOW = 1;
    public static final int STATE_MOUNTED = 2;
    /** 战斗异常状态（eActorCombatState）：1 沉默。 */
    public static final int COMBAT_STATE_SILENCE = 1;

    private enum CastFsmState {
        IDLE, CASTING, CHANNELING, RECOVERY
    }

    private enum CastFsmEvent {
        START_CAST, ADVANCE_TO_CHANNEL, ADVANCE_TO_RECOVERY, FINISH, INTERRUPT
    }

    private final SkillTables tables;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private final int notifySkillUsed;
    private final int notifySkillInterrupted;
    private final int notifySelfActorStateFlags;
    private final StateExitHookRegistry<ScenePlayer> actionExitHooks = new StateExitHookRegistry<>();

    public SkillService(SkillTables tables, SceneClock clock, SceneMetrics metrics, SceneMessageIds ids) {
        this.tables = tables;
        this.clock = clock;
        this.metrics = metrics;
        this.notifySkillUsed = ids.notifySkillUsed();
        this.notifySkillInterrupted = ids.notifySkillInterrupted();
        this.notifySelfActorStateFlags = ids.notifySelfActorStateFlags();
    }

    /** 注册行为状态退出 / 打断钩子（例如打断战斗时取消在途施法、打断跟随退出跟随）。 */
    public void registerActionStateExitHook(int actorState, ExitHook<ScenePlayer> hook) {
        actionExitHooks.register(actorState, hook);
    }

    public void clearActionStateExitHook(int actorState) {
        actionExitHooks.unregister(actorState);
    }

    public void clearAllActionStateExitHooks() {
        actionExitHooks.clear();
    }

    /**
     * 放技能（两阶段零副作用门禁）。
     *
     * @return 0 成功（已广播 70，并在状态位变化时先向主角单播状态互斥位图）；否则拒绝码（零副作用，不推 33/70）
     */
    public int release(SceneWorld world, ScenePlayer caster, ReleaseSkillRequest request) {
        long now = clock.nanoTime();
        int skillTableId = request.getSkillTableId();
        SkillDef def = tables.skill(skillTableId);
        if (def == null || !caster.hasSkill(skillTableId)) {
            log.debug("放技能被拒：技能不存在或未拥有 player={} skill_table_id={}", caster.playerId(), skillTableId);
            return reject(SkillResult.UNKNOWN_SKILL, SkillRules.INVALID_TABLE_ID);
        }
        if (caster.frozen()) {
            return reject(SkillResult.STATE_REJECTED, SkillRules.CASTER_IN_BATTLE);
        }
        if (caster.inBattle()) {
            metrics.battleGateReject(BattleGate.SKILL);
            return reject(SkillResult.CASTER_IN_BATTLE, SkillRules.CASTER_IN_BATTLE);
        }
        int target = SkillRules.checkTarget(def, request.getTargetId(),
                entity -> {
                    ScenePlayer targetPlayer = world.playerByEntity(entity);
                    return targetPlayer != null && targetPlayer.scene() != null
                            && targetPlayer.scene() == caster.scene() && !targetPlayer.frozen();
                },
                entity -> {
                    ScenePlayer targetPlayer = world.playerByEntity(entity);
                    return targetPlayer != null && targetPlayer.inBattle();
                });
        if (target == SkillRules.TARGET_IN_BATTLE) {
            metrics.battleGateReject(BattleGate.SKILL);
            return reject(SkillResult.TARGET_IN_BATTLE, target);
        }
        if (target != OK) {
            return reject(SkillResult.INVALID_TARGET, target);
        }

        PlayerSkillState state = caster.skillState();
        if (coolingDown(state, def, now)) {
            return reject(SkillResult.COOLDOWN, COOLDOWN_NOT_READY);
        }

        // 阶段一：纯只读预检（过期施法自然结算不发网包；所有状态/许可/互斥校验全通过才进入提交）。
        state.cast(settle(state.cast(), now));
        boolean needsInterruptActiveCast = state.cast() != null;
        if (needsInterruptActiveCast && !def.immediate()) {
            return reject(SkillResult.UNINTERRUPTIBLE, UNINTERRUPTIBLE);
        }
        int tip = SkillRules.checkSkillPermission(tables, def, state.combatStates());
        if (tip == OK) {
            tip = SkillRules.canPerformAction(tables.actionStateMatrix(), state.actionStateMask(),
                    ACTION_USE_SKILL, STATE_COMBAT);
        }
        if (tip == OK) {
            tip = SkillRules.validateCombatStates(tables.combatStateMatrix(), state.combatStateMask(),
                    ACTION_USE_SKILL);
        }
        if (tip != OK) {
            log.debug("放技能被状态表拒绝 player={} skill_table_id={} tip={}", caster.playerId(), skillTableId, tip);
            return reject(SkillResult.STATE_REJECTED, tip);
        }

        // 阶段二：原子提交副作用。
        if (needsInterruptActiveCast) {
            interruptActiveCast(world, caster, skillTableId);
        }
        int commitTip = SkillRules.tryPerformAction(tables.actionStateMatrix(), state,
                ACTION_USE_SKILL, STATE_COMBAT, caster, actionExitHooks);
        if (commitTip != OK) {
            log.warn("阶段二提交行为状态意外失败 player={} skill_table_id={} tip={}",
                    caster.playerId(), skillTableId, commitTip);
            return reject(SkillResult.STATE_REJECTED, commitTip);
        }
        syncSelfStateFlagsIfChanged(world, caster);
        long broadcastTargetId = 0L;
        if (request.getTargetId() != 0) {
            ScenePlayer targetPlayer = world.playerByEntity(request.getTargetId());
            if (targetPlayer != null && targetPlayer.scene() != null
                    && targetPlayer.scene() == caster.scene() && !targetPlayer.frozen()) {
                broadcastTargetId = request.getTargetId();
                if (targetPlayer != caster) {
                    com.game.proto.Rotation facing = lookAtYawDegrees(caster.position(), targetPlayer.position());
                    if (facing != null) {
                        caster.setRotation(facing);
                        caster.markTransformDirty();
                    }
                }
            }
        }

        world.broadcastToSelfAndWatchers(caster, push(notifySkillUsed, SkillUsedS2C.newBuilder()
                .setEntity(caster.entity())
                .addTargetEntity(broadcastTargetId)
                .setSkillTableId(skillTableId)
                .setPosition(request.getPosition())
                .build()));
        state.startCooldown(def.cooldownId(), now);
        if (def.general() || def.channel()) {
            Fsm<CastFsmState, CastFsmEvent, Void> fsm = newCastFsm(CastFsmState.IDLE);
            if (fsm.trigger(CastFsmEvent.START_CAST, null) && fsm.current() == CastFsmState.CASTING) {
                state.cast(new Cast(skillTableId, Phase.CASTING, now + def.castPointNanos(),
                        def.channel() ? def.channelFinishNanos() : -1, def.recoveryNanos()));
            }
        }
        metrics.skillRelease(SkillResult.OK);
        return OK;
    }

    /** 执行任意行为的状态互斥与打断（供坐骑、跟随等子系统复用），整轮合并推送一次最新位图给主角。 */
    public int tryPerformAction(SceneWorld world, ScenePlayer player, int action, int successState) {
        int tip = SkillRules.tryPerformAction(tables.actionStateMatrix(), player.skillState(),
                action, successState, player, actionExitHooks);
        if (tip == OK) {
            syncSelfStateFlagsIfChanged(world, player);
        }
        return tip;
    }

    /** 显式添加行为状态并在位图变化时立即同步给主角。 */
    public boolean addActionState(SceneWorld world, ScenePlayer player, int actorState) {
        boolean added = player.skillState().addActionState(actorState);
        if (added) {
            syncSelfStateFlagsIfChanged(world, player);
        }
        return added;
    }

    /** 显式移除行为状态（先摘态、再触发退出钩子、再即时单播给主角）。 */
    public boolean removeActionState(SceneWorld world, ScenePlayer player, int actorState) {
        boolean removed = SkillRules.removeActionState(player.skillState(), actorState, player, actionExitHooks);
        if (removed) {
            syncSelfStateFlagsIfChanged(world, player);
        }
        return removed;
    }

    /** 挂上来源 buff 的战斗异常状态（仅首次 0 → 1 激活时即时单播给主角）。 */
    public boolean addCombatState(SceneWorld world, ScenePlayer player, int combatState, long sourceBuffId) {
        boolean becameActive = player.skillState().addCombatState(combatState, sourceBuffId);
        if (becameActive) {
            syncSelfStateFlagsIfChanged(world, player);
        }
        return becameActive;
    }

    /** 移除来源 buff 的战斗异常状态（仅最后一个来源移除 1 → 0 时即时单播给主角）。 */
    public boolean removeCombatState(SceneWorld world, ScenePlayer player, int combatState, long sourceBuffId) {
        boolean cleared = player.skillState().removeCombatState(combatState, sourceBuffId);
        if (cleared) {
            syncSelfStateFlagsIfChanged(world, player);
        }
        return cleared;
    }

    /** 中止玩家进行中的施法（若存在则经相位 FSM 切回 IDLE、推 33 并记指标）。 */
    public boolean interruptActiveCast(SceneWorld world, ScenePlayer player, int reasonSkillTableId) {
        PlayerSkillState state = player.skillState();
        Cast current = state.cast();
        if (current == null) {
            return false;
        }
        Fsm<CastFsmState, CastFsmEvent, Void> fsm = newCastFsm(toFsmState(current.phase()));
        if (!fsm.trigger(CastFsmEvent.INTERRUPT, null)) {
            return false;
        }
        if (world != null) {
            world.broadcastToSelfAndWatchers(player, push(notifySkillInterrupted, SkillInterruptedS2C.newBuilder()
                    .setEntity(player.entity())
                    .setSkillTableId(reasonSkillTableId)
                    .build()));
        }
        metrics.skillInterrupted();
        state.cast(null);
        return true;
    }

    /**
     * 当主角行为状态位图或战斗异常状态位图较上次推送发生变化时，立即单播 {@code SelfActorStateFlagsS2C} 给主角本人。
     * 跨节点交接冻结期间不推网络包，待解冻或重进场落地再补推。
     */
    public void syncSelfStateFlagsIfChanged(SceneWorld world, ScenePlayer player) {
        if (world == null || player == null || player.frozen()) {
            return;
        }
        PlayerSkillState state = player.skillState();
        long seq = state.nextStateSyncSeqIfChanged();
        if (seq == 0L) {
            return;
        }
        world.sendTo(player, push(notifySelfActorStateFlags, SelfActorStateFlagsS2C.newBuilder()
                .setActionStateMask(state.actionStateMask())
                .setCombatStateMask(state.combatStateMask())
                .setStateSeq(seq)
                .build()));
    }

    /** 进场 / 重连落地后补推：重置基准为 0（对齐客户端入场清零），非零状态立即推一次。 */
    public void resendStateFlagsAfterEntry(SceneWorld world, ScenePlayer player) {
        if (player == null) {
            return;
        }
        player.skillState().resetSyncedStateBaseline();
        syncSelfStateFlagsIfChanged(world, player);
    }

    private int reject(SkillResult result, int tip) {
        metrics.skillRelease(result);
        return tip;
    }

    /** 冷却组还在冷却：开始过、表里有时长、没走完（时长按当前表查，同基线）。 */
    private boolean coolingDown(PlayerSkillState state, SkillDef def, long now) {
        Long start = state.cooldownStart(def.cooldownId());
        long duration = tables.cooldownNanos(def.cooldownId());
        return start != null && duration > 0 && now - start < duration;
    }

    /**
     * 按截止时刻经施法相位 {@link Fsm} 顺次结算：前摇结束 →（引导技能）引导 → 后摇 → 结束。
     * 每段从上一段的截止时刻起算。
     *
     * @return 此刻仍在进行中的施法；没有为 null
     */
    static Cast settle(Cast cast, long nowNanos) {
        if (cast == null) {
            return null;
        }
        Fsm<CastFsmState, CastFsmEvent, Void> fsm = newCastFsm(toFsmState(cast.phase()));
        while (cast != null && nowNanos - cast.phaseEndsAtNanos() >= 0) {
            CastFsmEvent event = switch (cast.phase()) {
                case CASTING -> cast.channelNanos() >= 0
                        ? CastFsmEvent.ADVANCE_TO_CHANNEL
                        : CastFsmEvent.ADVANCE_TO_RECOVERY;
                case CHANNELING -> CastFsmEvent.ADVANCE_TO_RECOVERY;
                case RECOVERY -> CastFsmEvent.FINISH;
            };
            if (!fsm.trigger(event, null)) {
                return null;
            }
            cast = switch (fsm.current()) {
                case CHANNELING -> cast.next(Phase.CHANNELING, cast.channelNanos());
                case RECOVERY -> cast.next(Phase.RECOVERY, cast.recoveryNanos());
                case IDLE, CASTING -> null;
            };
        }
        return cast;
    }

    /**
     * 计算地面偏航角（度）：{@code Rotation.z = deg(atan2(dy, dx))}，x/y 恒 0；目标在正上/正下方（无水平分量）返回 null 保留原朝向。
     */
    public static com.game.proto.Rotation lookAtYawDegrees(com.game.scene.world.Vec3 from, com.game.scene.world.Vec3 to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        if (Math.hypot(dx, dy) <= 1e-6) {
            return null;
        }
        return com.game.proto.Rotation.newBuilder().setZ((float) Math.toDegrees(Math.atan2(dy, dx))).build();
    }

    private static CastFsmState toFsmState(Phase phase) {
        return switch (phase) {
            case CASTING -> CastFsmState.CASTING;
            case CHANNELING -> CastFsmState.CHANNELING;
            case RECOVERY -> CastFsmState.RECOVERY;
        };
    }

    private static Fsm<CastFsmState, CastFsmEvent, Void> newCastFsm(CastFsmState initial) {
        Fsm<CastFsmState, CastFsmEvent, Void> fsm = new Fsm<>(initial);
        fsm.addEvent(Fsm.EventDesc.of(CastFsmEvent.START_CAST, List.of(CastFsmState.IDLE), CastFsmState.CASTING));
        fsm.addEvent(Fsm.EventDesc.of(CastFsmEvent.ADVANCE_TO_CHANNEL, List.of(CastFsmState.CASTING),
                CastFsmState.CHANNELING));
        fsm.addEvent(Fsm.EventDesc.of(CastFsmEvent.ADVANCE_TO_RECOVERY,
                List.of(CastFsmState.CASTING, CastFsmState.CHANNELING), CastFsmState.RECOVERY));
        fsm.addEvent(Fsm.EventDesc.of(CastFsmEvent.FINISH, List.of(CastFsmState.RECOVERY), CastFsmState.IDLE));
        fsm.addEvent(Fsm.EventDesc.of(CastFsmEvent.INTERRUPT,
                List.of(CastFsmState.CASTING, CastFsmState.CHANNELING, CastFsmState.RECOVERY), CastFsmState.IDLE));
        return fsm;
    }
}
