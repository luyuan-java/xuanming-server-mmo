package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BattleAction;
import com.game.proto.BattleEventItem;
import com.game.proto.eBattleEventType;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.List;

/** 职业主动技能的目标与执行规则；只操作当前战斗上下文，选择阶段不消耗随机数。 */
final class ClassSkillExecution {
    static final int ENEMY = 1;
    static final int ALLY = 2;
    static final int SELF = 3;

    enum Outcome { EXECUTED, SKIPPED, FALLBACK_ATTACK }

    private final BattleContext ctx;
    private final BuffEngine buffs;
    private final ActionChecks checks;

    ClassSkillExecution(BattleContext ctx, BuffEngine buffs, ActionChecks checks) {
        this.ctx = ctx;
        this.buffs = buffs;
        this.checks = checks;
    }

    static int rank(BattleUnit actor, SkillTable skill) {
        if (!BattleRules.isClassSkill(skill) || skill.getClassId() != actor.state().getClassId()) return 0;
        return actor.state().getSkillRanksOrDefault(skill.getId(), 0);
    }

    /** 指定的阵亡/逃离单位仍保留阵营，允许出手时按规则补位；错阵营永远不能借补位通过。 */
    static int validateTarget(BattleContext ctx, BattleUnit actor, SkillTable skill, long targetId) {
        return switch (skill.getTargetTeam()) {
            case SELF -> targetId == 0 || targetId == actor.actorId()
                    ? ActionChecks.SUCCESS : ActionChecks.SKILL_INVALID_TARGET;
            case ENEMY, ALLY -> {
                if (targetId == 0) yield ActionChecks.SUCCESS;
                BattleUnit target = ctx.findActor(targetId);
                if (target == null) yield ActionChecks.SKILL_INVALID_TARGET_ID;
                boolean sameTeam = actor.teamIndex() == target.teamIndex();
                yield sameTeam == (skill.getTargetTeam() == ALLY)
                        ? ActionChecks.SUCCESS : ActionChecks.SKILL_INVALID_TARGET;
            }
            default -> ActionChecks.INVALID_TABLE_DATA;
        };
    }

    List<BattleUnit> selectTargets(BattleUnit actor, SkillTable skill, long primaryTargetId) {
        if (skill.getTargetTeam() == SELF) return actor.isActive() ? List.of(actor) : List.of();
        if (skill.getTargetTeam() != ENEMY && skill.getTargetTeam() != ALLY) return List.of();
        boolean allies = skill.getTargetTeam() == ALLY;
        var candidates = new ArrayList<BattleUnit>();
        for (BattleUnit candidate : ctx.units()) {
            if (candidate.isActive() && (actor.teamIndex() == candidate.teamIndex()) == allies) candidates.add(candidate);
        }
        boolean healing = skill.getHealMultiplier() > 0 || skill.getHealPerLevel() > 0;
        candidates.sort((left, right) -> {
            boolean leftPrimary = primaryTargetId != 0 && left.actorId() == primaryTargetId;
            boolean rightPrimary = primaryTargetId != 0 && right.actorId() == primaryTargetId;
            if (leftPrimary != rightPrimary) return leftPrimary ? -1 : 1;
            if (healing) {
                int byHealth = Double.compare(ClassSkillCombat.healthRatio(left), ClassSkillCombat.healthRatio(right));
                if (byHealth != 0) return byHealth;
            }
            int bySlot = Integer.compareUnsigned(left.state().getFormationSlot(), right.state().getFormationSlot());
            return bySlot != 0 ? bySlot : Long.compareUnsigned(left.actorId(), right.actorId());
        });
        long maximum = Math.max(1, Integer.toUnsignedLong(skill.getMaxTargets()));
        return List.copyOf(candidates.subList(0, (int) Math.min(maximum, candidates.size())));
    }

    Outcome execute(BattleUnit actor, BattleAction action, SkillTable skill) {
        if (checks.checkActionPrerequisites(actor, action) != ActionChecks.SUCCESS) {
            return skill.getTargetTeam() == ENEMY ? Outcome.FALLBACK_ATTACK : Outcome.SKIPPED;
        }
        List<BattleUnit> targets = selectTargets(actor, skill, action.getTargetId());
        if (targets.isEmpty()) return Outcome.SKIPPED;
        int rank = rank(actor, skill);
        if (skill.getCooldownRounds() != 0) {
            // uint32 回合口径；本回合末先衰减一次，随后才经历配置数量的完整冷却回合。
            actor.state().putSkillCooldownRounds(skill.getId(), skill.getCooldownRounds() + 1);
        }
        ctx.events().append(eBattleEventType.BATTLE_EVENT_SKILL, actor.actorId(), targets.getFirst().actorId())
                .setSkillTableId(skill.getId());
        consumeMana(actor, skill);
        double baseDamage = skill.getTargetTeam() == ENEMY
                ? ctx.data().skillDamage(skill.getId(), EquipmentCombatRules.skillLevel(actor.level(), actor.combat())) : 0;
        try {
            for (int index = 0; index < targets.size(); index++) {
                BattleUnit target = targets.get(index);
                if (!target.isActive()) continue;
                ctx.events().setHitIndex(index);
                apply(actor, target, skill, rank, baseDamage);
            }
        } finally {
            ctx.events().setHitIndex(0);
        }
        return Outcome.EXECUTED;
    }

    private void consumeMana(BattleUnit actor, SkillTable skill) {
        long cost = BattleRules.skillManaCost(skill, actor.maxMana());
        if (cost == 0) return;
        long before = actor.mana();
        actor.setMana(Long.compareUnsigned(before, cost) > 0 ? before - cost : 0);
        long spent = before - actor.mana();
        event(eBattleEventType.BATTLE_EVENT_MANA, actor, actor, skill).setValue(spent)
                .setManaDelta(EventLog.signedManaDelta(spent, false));
    }

    private void apply(BattleUnit actor, BattleUnit target, SkillTable skill, int rank, double baseDamage) {
        if (skill.getTargetTeam() == ENEMY) {
            if (!ctx.rollHit(actor, target)) {
                event(eBattleEventType.BATTLE_EVENT_MISS, actor, target, skill).setValue(0);
                return;
            }
            if (baseDamage > 0) {
                long attack = CombatDamageRules.selectAttack(skill.getDamageType(), ctx.effectivePhysicalAttack(actor), actor.magicAttack());
                BattleContext.FinalDamage damage = ctx.calculateFinalDamage(actor, target, baseDamage, attack,
                        skill.getAttackMultiplier(), skill.getDamageType());
                long dealt = target.applyDamage(damage.value() * ClassSkillRules.damageRankFactor(rank));
                event(eBattleEventType.BATTLE_EVENT_DAMAGE, actor, target, skill).setValue(dealt).setIsCritical(damage.critical());
                if (target.health() == 0) ctx.handleDeath(target, actor.actorId());
            }
        }
        if (target.isDead()) return;
        if (skill.getHealMultiplier() > 0 || skill.getHealPerLevel() > 0) {
            long healed = target.applyHeal(ctx.classSkillHealAmount(actor, skill, rank, target));
            event(eBattleEventType.BATTLE_EVENT_HEAL, actor, target, skill).setValue(healed);
        }
        if (skill.getRestoreManaPercent() > 0) {
            long amount = ClassSkillRules.ceilRatio(target.maxMana(), skill.getRestoreManaPercent() * ClassSkillRules.supportRankFactor(rank));
            long restored = target.restoreMana(amount);
            event(eBattleEventType.BATTLE_EVENT_MANA, actor, target, skill).setValue(restored)
                    .setManaDelta(EventLog.signedManaDelta(restored, true));
        }
        if (skill.getDispelCount() != 0) buffs.dispelDebuffs(target, skill.getDispelCount());
        double percent = skill.getBuffPercent() * ClassSkillRules.supportRankFactor(rank);
        for (int effect : skill.getEffectList()) buffs.addBuffToActor(target, effect, actor.actorId(), 0, percent);
    }

    private BattleEventItem.Builder event(eBattleEventType type, BattleUnit source, BattleUnit target, SkillTable skill) {
        return ctx.events().append(type, source.actorId(), target.actorId()).setSkillTableId(skill.getId())
                .setTargetHealthAfter(target.health()).setTargetManaAfter(target.mana());
    }

}
