package com.game.scene.skill;

import static com.game.scene.world.SceneMessageIds.push;

import com.game.proto.ReleaseSkillRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 放技能（84；基线 SkillSystem::ReleaseSkill + CheckSkillPrerequisites，只在场景逻辑线程上调用）。
 *
 * <p>校验顺序同基线：技能存在且已拥有（1001）→ 施法者在回合制战斗中（7004）→ 目标（7001；目标在回合制战斗中 7002，scene-battle-spec §7.13）
 * → 冷却（7003）→ 施法阶段（前摇 / 引导 / 后摇进行中：新技能可打断则推 33 并取消旧施法，否则 7000）→（等级，基线空实现）
 * → 技能许可（SkillPermission）→ 行为互斥（ActorActionState；放技能加上「战斗」状态，永不移除）→ 战斗状态互斥 →（物品，基线空实现）。
 * 通过后广播 70，记冷却开始，通用 / 引导技能进入前摇。给施法者的顺序是 33 → 70 → 84 应答。
 *
 * <p><b>冷却、后摇、引导按设计意图生效</b>（与基线线上形态不同，见 PARITY）：基线这几样的代码都在，但线上从不给玩家挂冷却表与
 * 技能上下文，于是只有前摇这一段生效。Java 让它们都生效；技能命中（伤害、效果 buff）两版都不生效、不接——
 * 接通要先设计死亡状态 / 通知 / 复活（两版一起做）。
 * 阶段按截止时刻在下次放技能时顺次结算（{@link #settle}），不起定时器。
 */
public final class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    static final int OK = SkillRules.OK;
    static final int UNINTERRUPTIBLE = SkillErrorTip.skill_error.kSkillUnInterruptible_VALUE;
    static final int COOLDOWN_NOT_READY = SkillErrorTip.skill_error.kSkillCooldownNotReady_VALUE;
    /** 行为：放技能（ActorActionState / ActorActionCombatState 的行号）。 */
    static final int ACTION_USE_SKILL = 0;
    /** 行为状态：战斗。 */
    static final int STATE_COMBAT = 0;

    private final SkillTables tables;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private final int notifySkillUsed;
    private final int notifySkillInterrupted;

    public SkillService(SkillTables tables, SceneClock clock, SceneMetrics metrics, SceneMessageIds ids) {
        this.tables = tables;
        this.clock = clock;
        this.metrics = metrics;
        this.notifySkillUsed = ids.notifySkillUsed();
        this.notifySkillInterrupted = ids.notifySkillInterrupted();
    }

    /**
     * 放技能。
     *
     * @return 0 成功（已广播 70）；否则拒绝码（不广播 70；打断后才被后面的闸拒绝时 33 已经发出，同基线）
     */
    public int release(SceneWorld world, ScenePlayer caster, ReleaseSkillRequest request) {
        long now = clock.nanoTime();
        int skillTableId = request.getSkillTableId();
        SkillDef def = tables.skill(skillTableId);
        if (def == null || !caster.hasSkill(skillTableId)) {
            log.debug("放技能被拒：技能不存在或未拥有 player={} skill_table_id={}", caster.playerId(), skillTableId);
            return reject(SkillResult.UNKNOWN_SKILL, SkillRules.INVALID_TABLE_ID);
        }
        if (caster.inBattle()) {
            // 施法者在回合制战斗中 → 7004（查表之后、目标之前，基线 skill.cpp:219-229；scene-battle-spec §7.13）
            metrics.battleGateReject(BattleGate.SKILL);
            return reject(SkillResult.CASTER_IN_BATTLE, SkillRules.CASTER_IN_BATTLE);
        }
        int target = SkillRules.checkTarget(def, request.getTargetId(), entity -> world.playerByEntity(entity) != null,
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
        state.cast(settle(state.cast(), now));
        if (state.cast() != null) {
            if (!def.immediate()) {
                return reject(SkillResult.UNINTERRUPTIBLE, UNINTERRUPTIBLE);
            }
            // 33 带的是打断者（新技能）的配置号，target_entity / reason_code / skill_id 不填，同基线
            world.broadcastToSelfAndWatchers(caster, push(notifySkillInterrupted, SkillInterruptedS2C.newBuilder()
                    .setEntity(caster.entity())
                    .setSkillTableId(skillTableId)
                    .build()));
            metrics.skillInterrupted();
            state.cast(null);
        }
        int tip = SkillRules.checkSkillPermission(tables, def, state.combatStates());
        if (tip == OK) {
            tip = SkillRules.tryPerformAction(tables.actionStateRow(ACTION_USE_SKILL), state.actionStates(), STATE_COMBAT);
        }
        if (tip == OK) {
            tip = SkillRules.validateCombatStates(tables.combatStateRow(ACTION_USE_SKILL), state.combatStates());
        }
        if (tip != OK) {
            log.debug("放技能被状态表拒绝 player={} skill_table_id={} tip={}", caster.playerId(), skillTableId, tip);
            return reject(SkillResult.STATE_REJECTED, tip);
        }
        world.broadcastToSelfAndWatchers(caster, push(notifySkillUsed, SkillUsedS2C.newBuilder()
                .setEntity(caster.entity())
                .addTargetEntity(request.getTargetId())
                .setSkillTableId(skillTableId)
                .setPosition(request.getPosition())
                .build()));
        state.startCooldown(def.cooldownId(), now);
        if (def.general() || def.channel()) {
            state.cast(new Cast(skillTableId, Phase.CASTING, now + def.castPointNanos(),
                    def.channel() ? def.channelFinishNanos() : -1, def.recoveryNanos()));
        }
        metrics.skillRelease(SkillResult.OK);
        return OK;
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
     * 按截止时刻顺次结算施法阶段：前摇结束 →（引导技能）引导 → 后摇 → 结束。每段从上一段的截止时刻起算。
     *
     * @return 此刻仍在进行中的施法；没有为 null
     */
    static Cast settle(Cast cast, long nowNanos) {
        while (cast != null && nowNanos - cast.phaseEndsAtNanos() >= 0) {
            cast = switch (cast.phase()) {
                // 基线对冻结的施法者在施法点早退（HandleGeneralSkillSpell / HandleChannelSkillSpell 不武装后摇与引导）；
                // Java 不照做（scene-handoff-spec §5.9 的 84 ALLOW）：施法运行态不持久化、伤害 / buff 两版都未生效，冻结至多约 15 s，
                // 之后实例随交出移除（目标节点上运行态清空）或原地解冻，照常结算不会与冻结快照分叉
                case CASTING -> cast.channelNanos() >= 0
                        ? cast.next(Phase.CHANNELING, cast.channelNanos())
                        : cast.next(Phase.RECOVERY, cast.recoveryNanos());
                case CHANNELING -> cast.next(Phase.RECOVERY, cast.recoveryNanos());
                case RECOVERY -> null;
            };
        }
        return cast;
    }
}
