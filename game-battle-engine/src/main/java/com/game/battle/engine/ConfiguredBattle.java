package com.game.battle.engine;

import static com.game.battle.engine.BattleEffect.*;
import static com.game.proto.eBattleEventType.*;
import static com.game.proto.eBattleHitKind.*;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.*;
import com.game.table.BuffTable;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 公共技能的选技、施放、监听和回合时钟。状态归当前战斗持有。 */
final class ConfiguredBattle {
    final BattleContext ctx;
    final ConfiguredEffects effects;
    final ConfiguredImpact impact;
    final BattleEquipment equipment;
    final BuffEngine legacy;

    ConfiguredBattle(BattleContext ctx, BuffEngine legacy) {
        this.ctx = ctx;
        this.legacy = legacy;
        equipment = new BattleEquipment(this);
        impact = new ConfiguredImpact(this);
        effects = new ConfiguredEffects(this);
    }

    boolean activeFor(BattleUnit source, BattleUnit target) {
        return hasEffects(source) || hasEffects(target) || source.state().hasEquipmentEffects()
                || target.state().hasEquipmentEffects() || source.state().hasPetCultivation();
    }

    BuffTable row(int id) { return ctx.data().buff(id).orElse(null); }
    BuffTable row(BattleBuffEntry entry) { return row(entry.getBuffTableId()); }
    SkillTable skill(int id) { return ctx.data().skill(id).orElse(null); }

    static boolean subject(BattleActorState.Builder actor, SkillTable row) {
        int mask = actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET ? 2 : 1;
        return row.getBattleModel() == 1 && (row.getCasterMask() & mask) != 0
                && Integer.compareUnsigned(actor.getLevel(), row.getUnlockLevel()) >= 0;
    }

    boolean targetAllowed(BattleUnit actor, BattleUnit target, SkillTable skill) {
        if (target.fled() || (target.isDead() && skill.getAllowDeadTarget() == 0)) return false;
        if (skill.getTargetTeam() == 3) return actor == target;
        if ((skill.getTargetTeam() == 2) != (actor.teamIndex() == target.teamIndex())) return false;
        if (actor == target && skill.getHealthCostMode() == 2) return false;
        for (int id : skill.getEffectList()) {
            BuffTable effect = row(id);
            if (effect == null) continue;
            if (effect.getBattleOp() == RETREAT && !target.isPet()) return false;
            if (effect.getBattleOp() == COPY_TARGET_SKILL && target.isBoss()) return false;
        }
        return true;
    }

    boolean hasValidTarget(BattleUnit caster, SkillTable skill) {
        for (BattleUnit candidate : ctx.units()) {
            if (targetAllowed(caster, candidate, skill)) return true;
        }
        return false;
    }

    boolean isSkillCopyableFor(BattleUnit caster, SkillTable skill) {
        if (skill.getBattleModel() != 1 || skill.getAbilityKind() != 1) return false;
        if (skill.getForbidCopy() != 0 || skill.getHealthCostMode() != 0 || skill.getMaxBattleUses() != 0) return false;
        for (int effectId : skill.getEffectList()) {
            BuffTable effect = row(effectId);
            if (effect == null) continue;
            if (effect.getBattleOp() == COPY_TARGET_SKILL) return false;
            // 依赖已付气血/法力的转换技在零耗蓝镜像中无有效基数，结构上禁止复制。
            if (effect.getValueBasis() == 5 || effect.getValueBasis() == 6) return false;
        }
        return hasValidTarget(caster, skill);
    }

    boolean hasCopyableSkillFor(BattleUnit caster, BattleUnit target) {
        if (target.isBoss()) return false;
        for (int skillId : target.state().getSkillTableIdsList()) {
            SkillTable candidate = skill(skillId);
            if (candidate != null && isSkillCopyableFor(caster, candidate)) return true;
        }
        return false;
    }

    Optional<Integer> selectCopiedSkillId(BattleUnit caster, BattleUnit target) {
        // 优先复制目标本回合选定的可复制主动技能（不依赖双方出手先后）。
        BattleAction plannedAction = ctx.pendingAction(target.actorId());
        if (plannedAction != null && plannedAction.getActionType() == eBattleActionType.BATTLE_ACTION_SKILL) {
            SkillTable planned = skill(plannedAction.getSkillTableId());
            if (planned != null && isSkillCopyableFor(caster, planned)) return Optional.of(planned.getId());
        }
        // 方案 A 兜底：目标本回合未选可复制技能时，从其已学可复制主动技能池按 ID 排序去重后确定性抽取。
        List<Integer> candidates = new ArrayList<>();
        for (int skillId : target.state().getSkillTableIdsList()) {
            SkillTable candidate = skill(skillId);
            if (candidate != null && isSkillCopyableFor(caster, candidate)) candidates.add(skillId);
        }
        candidates.sort(Integer::compareUnsigned);
        List<Integer> unique = new ArrayList<>(candidates.size());
        for (int id : candidates) {
            if (unique.isEmpty() || unique.getLast() != id) unique.add(id);
        }
        if (unique.isEmpty()) return Optional.empty();
        int pick = unique.size() == 1 ? 0 : (int) ctx.rng().randIndex(unique.size());
        return Optional.of(unique.get(pick));
    }

    boolean hasState(BattleUnit target, int state) {
        for (var entry : target.state().getBuffsList()) {
            BuffTable row = row(entry);
            if (row != null && row.getBattleOp() == CONTROL && row.getStateKind() == state) return true;
        }
        return false;
    }

    boolean hasEffects(BattleUnit target) {
        for (var entry : target.state().getBuffsList()) {
            BuffTable row = row(entry);
            if (row != null && row.getBattleOp() != 0) return true;
        }
        return false;
    }

    boolean tagged(BattleUnit actor, String tag) {
        if (tag.isEmpty()) return true;
        return actor.state().getBuffsList().stream().map(this::row).anyMatch(r -> r != null && r.containsTag(tag));
    }

    boolean listener(BattleBuffEntry entry) {
        SkillTable skill = skill(entry.getSourceSkillId());
        return skill != null && skill.getBattleModel() == 1 && (skill.getAbilityKind() == 2 || skill.getAbilityKind() == 3)
                && skill.getInitEffectList().contains(entry.getBuffTableId());
    }

    double modifier(BattleUnit actor, int stat) { return modifier(actor, stat, null); }

    double modifier(BattleUnit actor, int stat, SupportEffectBreakdown.Builder sources) {
        double gain = 0, loss = 0;
        int bestGain = -1, bestLoss = -1;
        for (var entry : actor.state().getBuffsList()) {
            BuffTable effect = row(entry);
            if (effect == null || (effect.getBattleOp() != MODIFIER && effect.getBattleOp() != AURA)
                    || effect.getModifierStat() != stat) continue;
            BattleUnit source = ctx.findActor(entry.getCasterId());
            boolean available = effect.getRequireLivingCaster() == 0 || (source != null && source.isActive());
            double value = effect.getRatio() * (effect.getBattleOp() == AURA ? entry.getStacks() : 1);
            int index = -1;
            if (sources != null) {
                index = sources.getSourcesCount();
                SkillTable skill = skill(entry.getSourceSkillId());
                sources.addSourcesBuilder().setKind(listener(entry) ? 2 : 3).setEffectKind(stat == OUT_SHIELD ? 2 : 1)
                        .setBuffInstanceId(entry.getBuffId()).setSkillTableId(entry.getSourceSkillId())
                        .setName(skill == null ? "状态 #" + entry.getBuffTableId() : skill.getName())
                        .setRawBonusBps(value * 10000).setRemainRounds(entry.getRemainRounds())
                        .setInfinite(effect.getInfiniteDuration() != 0)
                        .setReason(available ? "同类更强修正优先生效" : "要求来源仍存活，当前未生效");
            }
            if (available && value > gain) { gain = value; bestGain = index; }
            if (available && value < loss) { loss = value; bestLoss = index; }
        }
        if (bestGain >= 0) sources.getSourcesBuilder(bestGain).setEffectiveBonusBps(gain * 10000).clearReason();
        if (bestLoss >= 0) sources.getSourcesBuilder(bestLoss).setEffectiveBonusBps(loss * 10000).clearReason();
        return gain + loss;
    }

    boolean isPhysicalForbidden(BattleUnit actor) {
        return modifier(actor, FORBID_PHYSICAL) > 0.0;
    }

    boolean isMagicForbidden(BattleUnit actor) {
        return modifier(actor, FORBID_MAGIC) > 0.0;
    }

    int check(BattleUnit actor, BattleAction action, SkillTable skill) {
        if (!subject(actor.state(), skill) || actor.state().getSkillRanksOrDefault(skill.getId(), 0) == 0
                || skill.getAbilityKind() != 1 || hasState(actor, SEAL_ACTIVE)
                || ((skill.getActionTags() & 1) != 0 && (hasState(actor, SEAL_PHYSICAL) || isPhysicalForbidden(actor)))
                || ((skill.getActionTags() & 2) != 0 && (hasState(actor, SEAL_MAGIC) || isMagicForbidden(actor)))) return ActionChecks.SKILL_CANNOT_CAST_IN_STATE;
        if (actor.state().getChargingSkillId() != 0) {
            for (int effectId : skill.getEffectList()) {
                BuffTable effect = row(effectId);
                if (effect != null && effect.getBattleOp() == PREPARE_CAST) {
                    return ActionChecks.SKILL_CANNOT_CAST_IN_STATE;
                }
            }
        }
        if (actor.state().getSkillCooldownRoundsOrDefault(skill.getId(), 0) != 0) return ActionChecks.SKILL_COOLDOWN_NOT_READY;
        if ((skill.getMaxBattleUses() != 0 && Integer.compareUnsigned(actor.state().getAbilityUseCountsOrDefault(skill.getId(), 0),
                skill.getMaxBattleUses()) >= 0) || (skill.getHealthCostMode() == 1 && Long.compareUnsigned(actor.health(), 1) <= 0)
                || Long.compareUnsigned(equipment.manaCost(actor, skill), actor.mana()) > 0) return ActionChecks.SKILL_CANNOT_CAST_IN_STATE;
        if (action.getTargetId() != 0) {
            BattleUnit target = ctx.findActor(action.getTargetId());
            return target != null && targetAllowed(actor, target, skill) ? ActionChecks.SUCCESS : ActionChecks.SKILL_INVALID_TARGET;
        }
        return ctx.units().stream().anyMatch(t -> targetAllowed(actor,t,skill)) ? ActionChecks.SUCCESS : ActionChecks.SKILL_INVALID_TARGET;
    }

    List<BattleUnit> targets(BattleUnit actor, SkillTable skill, long primary) {
        int limit = skill.getTargetTeam() == 3 ? 1 : Math.max(1, actor.isPet() ? skill.getPetMaxTargets() : skill.getMaxTargets());
        List<BattleUnit> chosen = new ArrayList<>(), candidates = new ArrayList<>();
        for (BattleUnit target : ctx.units()) {
            if (targetAllowed(actor, target, skill)) (target.actorId() == primary ? chosen : candidates).add(target);
        }
        int policy = skill.getSecondaryTargetPolicy();
        if (policy == POLICY_RANDOM) {
            fillTargets(chosen, candidates, limit);
            return chosen;
        }
        // 策略模式副目标排序：保留主目标，其余名额按策略最优值选取；并列集合内用确定性 RNG 打破平局。
        while (chosen.size() < limit && !candidates.isEmpty()) {
            List<Integer> bestIndices = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (bestIndices.isEmpty()) {
                    bestIndices.add(i);
                    continue;
                }
                int cmp = compareSecondaryTarget(policy, candidates.get(i), candidates.get(bestIndices.getFirst()));
                if (cmp > 0) {
                    bestIndices.clear();
                    bestIndices.add(i);
                } else if (cmp == 0) {
                    bestIndices.add(i);
                }
            }
            if (bestIndices.isEmpty()) break;
            int chosenPos = bestIndices.size() == 1
                    ? bestIndices.getFirst()
                    : bestIndices.get((int) ctx.rng().randIndex(bestIndices.size()));
            chosen.add(candidates.remove(chosenPos));
        }
        return chosen;
    }

    private int compareSecondaryTarget(int policy, BattleUnit lhs, BattleUnit rhs) {
        return switch (policy) {
            case POLICY_HIGHEST_PHYSICAL_ATTACK ->
                    Long.compareUnsigned(ctx.effectivePhysicalAttack(lhs), ctx.effectivePhysicalAttack(rhs));
            case POLICY_HIGHEST_MAGIC_ATTACK ->
                    Long.compareUnsigned(ctx.effectiveMagicAttack(lhs), ctx.effectiveMagicAttack(rhs));
            case POLICY_LOWEST_HEALTH_VALUE -> Long.compareUnsigned(rhs.health(), lhs.health());
            case POLICY_LOWEST_HEALTH_RATIO -> {
                double a = lhs.maxHealth() != 0L
                        ? Unsigned.toDouble(lhs.health()) / Unsigned.toDouble(lhs.maxHealth()) : 1.0;
                double b = rhs.maxHealth() != 0L
                        ? Unsigned.toDouble(rhs.health()) / Unsigned.toDouble(rhs.maxHealth()) : 1.0;
                yield Math.abs(a - b) <= 1e-12 ? 0 : (a < b ? 1 : -1);
            }
            case POLICY_LOWEST_PHYSICAL_DEFENSE -> {
                double a = ctx.effectiveMitigationScore(lhs, false);
                double b = ctx.effectiveMitigationScore(rhs, false);
                yield Math.abs(a - b) <= 1e-9 ? 0 : (a < b ? 1 : -1);
            }
            case POLICY_LOWEST_MAGIC_DEFENSE -> {
                double a = ctx.effectiveMitigationScore(lhs, true);
                double b = ctx.effectiveMitigationScore(rhs, true);
                yield Math.abs(a - b) <= 1e-9 ? 0 : (a < b ? 1 : -1);
            }
            case POLICY_LOWEST_ROUND_SPEED ->
                    Long.compareUnsigned(ctx.roundStartSpeed(rhs), ctx.roundStartSpeed(lhs));
            default -> 0;
        };
    }

    void fillTargets(List<BattleUnit> chosen, List<BattleUnit> candidates, int limit) {
        while (chosen.size() < limit && !candidates.isEmpty()) {
            int index = candidates.size() == 1 ? 0 : (int) ctx.rng().randIndex(candidates.size());
            chosen.add(candidates.remove(index));
        }
    }

    void initializeForActor(BattleUnit actor) {
        for (int id : actor.state().getSkillRanksMap().keySet().stream().sorted(Integer::compareUnsigned).toList()) {
            SkillTable skill = skill(id);
            if (skill == null || !subject(actor.state(), skill)) continue;
            actor.state().addRuntimeSkills(id);
            Hit hit = new Hit(id, actor.state().getSkillRanksOrThrow(id), DIRECT, 0);
            var targets = skill.getAbilityKind() == 4 ? targets(actor, skill, actor.isPet() ? actor.ownerPlayerId() : actor.actorId()) : List.of(actor);
            for (var target : targets) for (int effect : skill.getInitEffectList()) effects.apply(actor, target, effect, hit, 0);
        }
    }

    void initialize() {
        for (BattleUnit actor : ctx.units()) {
            initializeForActor(actor);
        }
    }

    void cast(BattleUnit actor, SkillTable skill, long selected, int origin, double scale) {
        cast(actor, skill, selected, origin, scale, 0);
    }

    void cast(BattleUnit actor, SkillTable skill, long selected, int origin, double scale, int rankOverride) {
        List<BattleUnit> targets = origin == CONFUSED ? List.of(ctx.findActor(selected)) : targets(actor, skill, selected);
        if (targets.isEmpty()) return;
        int rank = rankOverride > 0 ? rankOverride : actor.state().getSkillRanksOrDefault(skill.getId(), 0);
        Hit cast = new Hit(skill.getId(), origin == MIRRORED ? Math.max(1,rank) : rank, origin, targets.getFirst().actorId());
        cast.scale = scale;
        int subjectMaxTargets = actor.isPet()
                ? (skill.getPetMaxTargets() > 0 ? skill.getPetMaxTargets() : skill.getMaxTargets())
                : skill.getMaxTargets();
        boolean explicitPrimarySelected = selected != 0L && targets.getFirst().actorId() == selected;
        cast.primaryFocusBonus = ctx.isCompetitivePvp()
                && origin == DIRECT
                && actor.isPlayer()
                && subjectMaxTargets > 1
                && explicitPrimarySelected
                && targets.size() < subjectMaxTargets;
        cast.isMultiTargetSkill = (actor.isPet() ? skill.getPetMaxTargets() : skill.getMaxTargets()) > 1;
        ctx.events().append(BATTLE_EVENT_SKILL, actor.actorId(), cast.primary).setSkillTableId(skill.getId())
                .setHitKind(origin == MIRRORED ? BATTLE_HIT_MIRROR : BATTLE_HIT_NORMAL);
        if (origin == DIRECT || origin == CONFUSED) {
            if (actor.isPlayer() && skill.getSeasonId() != 0) {
                ctx.encounter.markAntiControlOrSeasonSkill(actor.actorId());
            }
            cast.paidMana = equipment.manaCost(actor, skill);
            if (cast.paidMana != 0) {
                actor.setMana(actor.mana() - cast.paidMana);
                ctx.events().append(BATTLE_EVENT_MANA, actor.actorId(), actor.actorId()).setSkillTableId(skill.getId())
                        .setValue(cast.paidMana).setManaDelta(EventLog.signedManaDelta(cast.paidMana, false))
                        .setTargetHealthAfter(actor.health()).setTargetManaAfter(actor.mana());
            }
            if (skill.getCooldownRounds() > 0) actor.state().putSkillCooldownRounds(skill.getId(), skill.getCooldownRounds());
            actor.state().putAbilityUseCounts(skill.getId(), actor.state().getAbilityUseCountsOrDefault(skill.getId(), 0) + 1);
            if (skill.getHealthCostMode() != 0) {
                long after = skill.getHealthCostMode() == 1 ? 1 : 0;
                cast.paidHealth = actor.health() - after;
                actor.setHealth(after);
                ctx.events().append(BATTLE_EVENT_DAMAGE, actor.actorId(), actor.actorId()).setSkillTableId(skill.getId())
                        .setValue(cast.paidHealth).setDamageOrigin(COST).setTargetHealthAfter(after);
                if (after == 0) ctx.handleDeath(actor, actor.actorId());
            }
        }
        int effectsBegin = ctx.events().eventCount();
        boolean castSupportEffect = false;
        for (int i = 0; i < targets.size(); i++) {
            BattleUnit target = targets.get(i);
            if (target.fled()) continue;
            ctx.events().setHitIndex(i);
            Hit hit = cast.derived(origin);
            hit.primaryFocusBonus = cast.primaryFocusBonus;
            final int targetOrdinal = i;
            var eligible = skill.getEffectList().stream().map(this::row)
                    .filter(r -> r != null
                            && !(targetOrdinal > 0 && r.getEffectTarget() == 1)
                            && (r.getBattleOp() != CONSUME || (tagged(target,r.getRequiredTag())
                            && tagged(target,r.getSecondRequiredTag())))).toList();
            for (var effect : eligible) {
                effects.apply(actor, target, effect.getId(), hit, 0);
                switch (effect.getBattleOp()) {
                    case DAMAGE -> hit.damageOrdinal++;
                    case HEAL -> {
                        hit.healOrdinal++;
                        castSupportEffect = true;
                    }
                    case SHIELD -> {
                        hit.shieldOrdinal++;
                        castSupportEffect = true;
                    }
                    case REVIVE -> castSupportEffect = true;
                    default -> { }
                }
            }
        }
        ctx.events().setHitIndex(0);
        boolean hitDirectly = false;
        boolean landedDirectStrike = false;
        for (int idx = effectsBegin; idx < ctx.events().eventCount(); idx++) {
            BattleEventItem ev = ctx.events().eventAt(idx);
            if (ev.getEventType() == BATTLE_EVENT_DAMAGE && ev.getSourceId() == actor.actorId()
                    && ev.getSkillTableId() == skill.getId() && ev.getDamageOrigin() == DIRECT) {
                if (ev.getValue() != 0L) hitDirectly = true;
                if (ev.getValue() != 0L || ev.getAbsorbed() != 0L) landedDirectStrike = true;
            }
        }
        if (origin == DIRECT && landedDirectStrike && actor.isActive()) {
            consumeOneShotAttackModifiers(actor, skill.getDamageType());
        }
        if (origin == DIRECT && hitDirectly && actor.isActive()) {
            trigger(actor, targets.getFirst(), AFTER_SKILL, skill.getDamageType(), cast);
        }
        if (origin == DIRECT && castSupportEffect && actor.isActive()) {
            trigger(actor, actor, AFTER_SUPPORT_CAST, 0, cast);
        }
    }

    Optional<BattleAction> auto(BattleUnit actor, ActionChecks checks) {
        for (int id : actor.state().getSkillTableIdsList()) {
            SkillTable skill = skill(id);
            if (skill == null || skill.getBattleModel() != 1 || skill.getAbilityKind() != 1) continue;
            for (var target : ctx.units()) {
                if (!targetAllowed(actor,target,skill)) continue;
                boolean useful = skill.getTargetTeam() == 1;
                for (int effectId : skill.getEffectList()) {
                    var effect = row(effectId);
                    if (effect == null) continue;
                    if (effect.getBattleOp() == HEAL) useful = ClassSkillCombat.healthRatio(target) < 0.7;
                    else if (effect.getBattleOp() == MANA) useful = Unsigned.toDouble(target.mana()) < Unsigned.toDouble(target.maxMana()) * 0.4;
                    else if (effect.getBattleOp() == COPY_TARGET_SKILL) useful = !legacy.isImmuneToBuff(target, effect) && hasCopyableSkillFor(actor, target);
                    else if (!instant(effect.getBattleOp())) useful |= target.state().getBuffsList().stream().noneMatch(e -> e.getBuffTableId() == effectId);
                }
                BattleAction action = action(id, target.actorId());
                if (useful && checks.checkActionPrerequisites(actor, action) == ActionChecks.SUCCESS) return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    private static BattleAction action(int skill, long target) {
        return BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(skill).setTargetId(target).build();
    }

    boolean control(BattleUnit actor, BattleAction action, ActionChecks checks) {
        if (hasState(actor, FREEZE) || hasState(actor, SLEEP)) return true;
        if (!hasState(actor, CONFUSION)) return action.getActionType() == eBattleActionType.BATTLE_ACTION_ATTACK
                && (hasState(actor, SEAL_PHYSICAL) || isPhysicalForbidden(actor));
        var allies = ctx.units().stream().filter(t -> t != actor && t.teamIndex() == actor.teamIndex() && t.isActive()).toList();
        if (!allies.isEmpty()) {
            var ally = allies.get(allies.size() == 1 ? 0 : (int)ctx.rng().randIndex(allies.size()));
            for (int id : actor.state().getSkillTableIdsList()) {
                SkillTable skill = skill(id);
                if (skill != null && skill.getBattleModel() == 1 && (skill.getActionTags() & 3) != 0
                        && !((skill.getActionTags() & 1) != 0 && (hasState(actor, SEAL_PHYSICAL) || isPhysicalForbidden(actor)))
                        && !((skill.getActionTags() & 2) != 0 && (hasState(actor, SEAL_MAGIC) || isMagicForbidden(actor)))
                        && checks.checkActionPrerequisites(actor, action(id, 0)) == ActionChecks.SUCCESS) {
                    cast(actor,skill,ally.actorId(), CONFUSED, 1);
                    return true;
                }
            }
            if (!hasState(actor,SEAL_PHYSICAL) && !isPhysicalForbidden(actor)) {
                ctx.events().append(BATTLE_EVENT_ATTACK,actor.actorId(),ally.actorId());
                Hit hit = new Hit(0,1,CONFUSED,0);
                impact.weaponHit(actor,ally,CombatDamageRules.PHYSICAL_DAMAGE,1,hit,BATTLE_HIT_NORMAL);
                return true;
            }
        }
        actor.setDefending(true);
        ctx.events().append(BATTLE_EVENT_DEFEND,actor.actorId(),actor.actorId());
        equipment.defend(actor);
        return true;
    }

    static boolean matches(BuffTable row, int type) {
        return row.getDamageFilter() == 0 || (row.getDamageFilter() == 1 && type == CombatDamageRules.PHYSICAL_DAMAGE)
                || (row.getDamageFilter() == 2 && type == CombatDamageRules.MAGIC_DAMAGE);
    }

    void trigger(BattleUnit owner, BattleUnit other, int trigger, int type, Hit hit) {
        boolean redirectedTake = trigger == AFTER_TAKE && hit.origin == REDIRECT;
        boolean controlAttempt = trigger == AFTER_CONTROL_ATTEMPT;
        if (!owner.isActive() || (hit.origin != DIRECT && trigger != AFTER_PERIODIC && !redirectedTake && !controlAttempt)) return;
        for (var initial : List.copyOf(owner.state().getBuffsList())) {
            int index = owner.findBuffIndex(initial.getBuffId());
            if (!owner.isActive() || index < 0) continue;
            var saved = owner.state().getBuffs(index);
            BuffTable row = row(saved);
            if (row == null || row.getBattleOp() == 0) continue;
            boolean triggerMatches = row.getTriggerEvent() == trigger
                    || (row.getTriggerEvent() == AFTER_DIRECT_ATTACK && (trigger == AFTER_BASIC || trigger == AFTER_SKILL));
            if (!triggerMatches || !matches(row,type)) continue;
            // A36 援护分担转伤仅允许触发 GrowingAura 叠层，绝不递归触发反击、反伤或回蓝。
            if (redirectedTake && row.getBattleOp() != AURA) continue;
            if (trigger == AFTER_PERIODIC && !row.getRequiredTag().isEmpty()) {
                if (!(row.getRequiredTag().equals("Poison") && hit.periodicState == POISON)
                        && !(row.getRequiredTag().equals("Burn") && hit.periodicState == BURN)) continue;
            } else if (trigger == AFTER_PERIODIC) {
                if (hit.periodicState != POISON && hit.periodicState != BURN) continue;
            } else if (!tagged(other,row.getRequiredTag()) && (row.getSecondRequiredTag().isEmpty() || !tagged(other,row.getSecondRequiredTag()))) continue;
            if (row.getBattleOp() == AURA && trigger != AFTER_SUPPORT_CAST && owner.teamIndex() == other.teamIndex()) continue;
            int count = saved.getLastTriggerRound() == ctx.roundIndex() ? saved.getTriggerCount() : 0;
            if (row.getPerRoundLimit() > 0 && count >= row.getPerRoundLimit()) continue;
            if (row.getTriggerChance() > 0 && row.getTriggerChance() < 1 && ctx.rng().rand01() >= row.getTriggerChance()) continue;
            owner.state().getBuffsBuilder(index).setLastTriggerRound(ctx.roundIndex()).setTriggerCount(count + 1);
            Hit child = hit.derived(hit.origin);
            child.skill = saved.getSourceSkillId();
            child.rank = owner.state().getSkillRanksOrDefault(child.skill, 1);
            switch (row.getBattleOp()) {
                case MIRROR -> {
                    if (!other.isActive() || hasState(owner, FREEZE) || hasState(owner, SLEEP)) break;
                    if ((type == CombatDamageRules.PHYSICAL_DAMAGE && isPhysicalForbidden(owner))
                            || (type == CombatDamageRules.MAGIC_DAMAGE && isMagicForbidden(owner))) break;
                    SkillTable incoming = skill(hit.skill);
                    if (incoming != null && (other.isBoss() || incoming.getForbidCopy() != 0)) break;
                    double scale = row.getRatio() > 0 ? row.getRatio() : 1;
                    if (incoming != null && incoming.getBattleModel() == 1) cast(owner, incoming, other.actorId(), MIRRORED, scale);
                    else impact.weaponHit(owner,other,type,scale,hit.derived(MIRRORED),BATTLE_HIT_MIRROR);
                }
                case REFLECT -> impact.damage(owner,other,Unsigned.toDouble(hit.actualDamage) * row.getSecondaryRatio(),type,child.derived(REFLECTED),BATTLE_HIT_REFLECT);
                case PURSUIT -> {
                    if (!other.isActive()) break;
                    var chosen = new ArrayList<>(List.of(other));
                    var candidates = new ArrayList<>(ctx.units().stream().filter(t -> t != other && t.teamIndex() != owner.teamIndex() && t.isActive()).toList());
                    fillTargets(chosen,candidates, owner.isPet() ? row.getPetTargetCount() : row.getTargetCount());
                    for (var target : chosen) impact.weaponHit(owner,target,row.getDamageFilter() == 2 ? CombatDamageRules.MAGIC_DAMAGE : CombatDamageRules.PHYSICAL_DAMAGE,
                            row.getRatio() > 0 ? row.getRatio() : 1,child.derived(PURSUED),BATTLE_HIT_PURSUIT);
                }
                case CONDITIONAL_HEAL -> impact.heal(owner, owner,effects.value(owner,owner,row,child),child,false,false);
                case CONDITIONAL_MANA, DRAIN -> {
                    var target = row.getBattleOp() == DRAIN ? other : owner;
                    if (target.isActive()) effects.mana(owner,target,effects.value(owner,target,row,child),row.getBattleOp() == DRAIN);
                }
                case CHILDREN, EVASION_CHARGE -> {
                    for (int id : row.getSubBuffList()) effects.apply(owner,owner,id,child,1);
                    for (int id : row.getTargetSubBuffList()) effects.apply(owner,other,id,child,1);
                }
                case AURA -> {
                    int cap = Math.max(1, row.getStackLimit());
                    if (saved.getStacks() < cap) {
                        owner.state().getBuffsBuilder(index).setStacks(saved.getStacks() + 1);
                    }
                }
                default -> { }
            }
            index = owner.findBuffIndex(saved.getBuffId());
            if (index >= 0 && saved.getRemainingHits() > 0) {
                if (saved.getRemainingHits() == 1) remove(owner, saved.getBuffId());
                else owner.state().getBuffsBuilder(index).setRemainingHits(saved.getRemainingHits() - 1);
            }
        }
    }

    void remove(BattleUnit target, long id) {
        int index = target.findBuffIndex(id);
        if (index < 0) return;
        var entry = target.state().getBuffs(index);
        BuffTable row = row(entry);
        if (row != null && row.getBattleOp() == TEMP_HEALTH) {
            long max = Long.compareUnsigned(target.maxHealth(),entry.getSecondaryValue()) > 0 ? target.maxHealth() - entry.getSecondaryValue() : 1;
            target.state().setMaxHealth(max);
            target.setHealth(Unsigned.minUnsigned(max,target.health()));
            ctx.events().append(BATTLE_EVENT_MAX_HEALTH,entry.getCasterId(),target.actorId()).setTargetMaxHealthAfter(max).setTargetHealthAfter(target.health());
        }
        target.state().removeBuffs(index);
        if (row != null && row.getBattleOp() == PREPARE_CAST) {
            syncChargingSkillId(target);
        }
        ctx.events().append(BATTLE_EVENT_BUFF_REMOVE, entry.getCasterId(),target.actorId()).setBuffTableId(entry.getBuffTableId()).setBuffInstanceId(id);
    }

    boolean tryConsumeControlImmunity(BattleUnit caster, BattleUnit target, BuffTable row) {
        for (int i = 0; i < target.state().getBuffsCount(); i++) {
            BattleBuffEntry saved = target.state().getBuffs(i);
            BuffTable guard = row(saved);
            if (guard == null || guard.getBattleOp() != CONTROL_IMMUNITY) {
                continue;
            }
            long guardId = saved.getBuffId();
            if (saved.getRemainingHits() <= 1) {
                remove(target, guardId);
            } else {
                target.state().getBuffsBuilder(i).setRemainingHits(saved.getRemainingHits() - 1);
            }
            if (target.isPlayer()) {
                ctx.encounter.markAntiControlOrSeasonSkill(target.actorId());
            }
            ctx.events().append(BATTLE_EVENT_RESIST, caster.actorId(), target.actorId())
                    .setBuffTableId(row.getId());
            return true;
        }
        return false;
    }

    void incrementHitVulnerability(BattleUnit target) {
        int roundIndex = ctx.roundIndex();
        long currentGroup = Integer.toUnsignedLong(ctx.events().groupId());
        for (int i = 0; i < target.state().getBuffsCount(); i++) {
            BattleBuffEntry saved = target.state().getBuffs(i);
            BuffTable row = row(saved);
            if (row == null || row.getBattleOp() != HIT_VULNERABILITY) {
                continue;
            }
            // 同一行动 group_id 对同一目标至多叠 1 层，防止连击或多段技能单次叠满。
            if (saved.getLastTriggerRound() == roundIndex && saved.getSecondaryValue() == currentGroup) {
                continue;
            }
            int cap = Math.max(1, row.getStackLimit());
            if (Integer.compareUnsigned(saved.getStacks(), cap) >= 0) {
                continue;
            }
            target.state().getBuffsBuilder(i)
                    .setStacks(saved.getStacks() + 1)
                    .setLastTriggerRound(roundIndex)
                    .setSecondaryValue(currentGroup);
        }
    }

    double hitVulnerabilityBonus(BattleUnit target) {
        double bonus = 0.0;
        for (BattleBuffEntry entry : target.state().getBuffsList()) {
            BuffTable row = row(entry);
            if (row == null || row.getBattleOp() != HIT_VULNERABILITY) {
                continue;
            }
            if (entry.getStacks() != 0 && Double.isFinite(row.getRatio())) {
                bonus += row.getRatio() * Integer.toUnsignedLong(entry.getStacks());
            }
        }
        return bonus;
    }

    void syncChargingSkillId(BattleUnit actor) {
        int chargingId = 0;
        for (BattleBuffEntry entry : actor.state().getBuffsList()) {
            BuffTable row = row(entry);
            if (row != null && row.getBattleOp() == PREPARE_CAST) {
                chargingId = entry.getSourceSkillId();
                break;
            }
        }
        actor.state().setChargingSkillId(chargingId);
    }

    boolean executePreparedCastIfReady(BattleUnit actor) {
        int prepareIndex = -1;
        for (int i = 0; i < actor.state().getBuffsCount(); i++) {
            BattleBuffEntry entry = actor.state().getBuffs(i);
            BuffTable row = row(entry);
            if (row != null && row.getBattleOp() == PREPARE_CAST
                    && Integer.compareUnsigned(entry.getAppliedRound(), ctx.roundIndex()) < 0) {
                prepareIndex = i;
                break;
            }
        }
        if (prepareIndex < 0) {
            return false;
        }
        BattleBuffEntry saved = actor.state().getBuffs(prepareIndex);
        BuffTable prepareRow = row(saved);
        SkillTable skillRow = skill(saved.getSourceSkillId());
        boolean blocked = prepareRow == null || skillRow == null
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_STUN)
                || ctx.actorHasBuffOfType(actor, BattleConstants.BUFF_TYPE_FREEZE)
                || hasState(actor, FREEZE)
                || hasState(actor, SLEEP)
                || hasState(actor, CONFUSION)
                || hasState(actor, SEAL_ACTIVE)
                || ((skillRow.getActionTags() & 1) != 0 && (hasState(actor, SEAL_PHYSICAL) || isPhysicalForbidden(actor)))
                || ((skillRow.getActionTags() & 2) != 0 && (hasState(actor, SEAL_MAGIC) || isMagicForbidden(actor)));

        // 无论释放或被打断，蓄力状态均在本回合窗口消耗清除（打断不退蓝、不退冷却）。
        remove(actor, saved.getBuffId());
        if (blocked) {
            return false;
        }

        List<BattleUnit> chosen = targets(actor, skillRow, saved.getSecondaryValue());
        if (chosen.isEmpty()) {
            return true;
        }

        int rank = Math.max(1, actor.state().getSkillRanksOrDefault(skillRow.getId(), 1));
        Hit cast = new Hit(skillRow.getId(), rank, DIRECT, chosen.getFirst().actorId());
        cast.isPreparedCast = true;
        int subjectMaxTargets = actor.isPet()
                ? (skillRow.getPetMaxTargets() > 0 ? skillRow.getPetMaxTargets() : skillRow.getMaxTargets())
                : skillRow.getMaxTargets();
        cast.primaryFocusBonus = ctx.isCompetitivePvp()
                && actor.isPlayer()
                && subjectMaxTargets > 1
                && saved.getSecondaryValue() != 0L
                && chosen.getFirst().actorId() == saved.getSecondaryValue()
                && chosen.size() < subjectMaxTargets;
        cast.isMultiTargetSkill = subjectMaxTargets > 1;

        ctx.events().append(BATTLE_EVENT_SKILL, actor.actorId(), cast.primary)
                .setSkillTableId(skillRow.getId())
                .setHitKind(BATTLE_HIT_NORMAL);

        int effectsBegin = ctx.events().eventCount();
        List<Integer> payloadBuffs = prepareRow.getSubBuffList();
        for (int i = 0; i < chosen.size(); i++) {
            BattleUnit target = chosen.get(i);
            if (target.fled()) continue;
            ctx.events().setHitIndex(i);
            Hit targetHit = cast.derived(DIRECT);
            targetHit.primaryFocusBonus = cast.primaryFocusBonus;
            for (int childBuffId : payloadBuffs) {
                effects.apply(actor, target, childBuffId, targetHit, 1);
                BuffTable def = row(childBuffId);
                if (def != null && def.getBattleOp() == DAMAGE) {
                    targetHit.damageOrdinal++;
                }
            }
        }
        ctx.events().setHitIndex(0);

        boolean hitDirectly = false;
        for (int idx = effectsBegin; idx < ctx.events().eventCount(); idx++) {
            BattleEventItem ev = ctx.events().eventAt(idx);
            if (ev.getEventType() == BATTLE_EVENT_DAMAGE && ev.getSourceId() == actor.actorId()
                    && ev.getSkillTableId() == skillRow.getId()
                    && ev.getValue() != 0L && ev.getDamageOrigin() == DIRECT) {
                hitDirectly = true;
                break;
            }
        }
        if (hitDirectly && actor.isActive()) {
            consumeOneShotAttackModifiers(actor, skillRow.getDamageType());
            trigger(actor, chosen.getFirst(), AFTER_SKILL, skillRow.getDamageType(), cast);
        }
        return true;
    }

    void consumeOneShotAttackModifiers(BattleUnit source, int damageType) {
        int targetStat = damageType == CombatDamageRules.PHYSICAL_DAMAGE
                ? NEXT_PHYSICAL_ATTACK : NEXT_MAGIC_ATTACK;
        List<Long> toRemove = new ArrayList<>();
        for (BattleBuffEntry entry : source.state().getBuffsList()) {
            BuffTable row = row(entry);
            if (row != null && row.getBattleOp() == MODIFIER && row.getModifierStat() == targetStat) {
                toRemove.add(entry.getBuffId());
            }
        }
        for (long buffId : toRemove) {
            remove(source, buffId);
        }
    }

    void beginRound() {
        for (var actor : ctx.units()) {
            actor.state().clearRoundEffectUsage();
            for (var entry : actor.state().getBuffsBuilderList()) {
                BuffTable row = row(entry.getBuffTableId());
                if (row == null || row.getBattleOp() != AURA || row.getTriggerEvent() != ROUND_START
                        || ctx.roundIndex() < Math.max(1,row.getStartRound())) continue;
                var source = ctx.findActor(entry.getCasterId());
                if (row.getRequireLivingCaster() == 0 || (source != null && source.isActive()))
                    entry.setStacks(Math.min(Math.max(1,row.getStackLimit()),entry.getStacks()+1));
            }
        }
    }

    void resetVolatileGrowingStacks(BattleUnit actor) {
        for (var entry : actor.state().getBuffsBuilderList()) {
            BuffTable row = row(entry.getBuffTableId());
            if (row != null && row.getBattleOp() == AURA && row.getKeepOnDeath() == 0 && entry.getStacks() > 0) {
                entry.setStacks(0);
            }
        }
    }

    void endRound() {
        // 全场先结算持续伤害，再检查复苏，最后推进时钟。死亡和驱散可能使早先拍下的实例失效。
        for (int phase = 0; phase < 3; phase++) for (var actor : ctx.units()) {
            if (actor.fled() || !hasEffects(actor)) continue;
            ctx.events().beginGroup();
            for (var initial : List.copyOf(actor.state().getBuffsList())) {
                int index = actor.findBuffIndex(initial.getBuffId());
                if (index < 0) continue;
                var saved = actor.state().getBuffs(index);
                var row = row(saved);
                if (row == null || row.getBattleOp() == 0 || saved.getTickRound() > ctx.roundIndex()) continue;
                int op = row.getBattleOp();
                var caster = ctx.findActor(saved.getCasterId());
                if (caster == null) caster = actor;
                var hit = new Hit(saved.getSourceSkillId(),1,DIRECT,0);
                if (phase == 0 && op == PERIODIC && !actor.isDead() && saved.getRemainingTicks() > 0) {
                    hit.origin = DOT;
                    hit.periodicState = row.getStateKind();
                    impact.damage(caster,actor,Unsigned.toDouble(saved.getSnapshotValue()),row.getDamageFilter() == 1 ?
                            CombatDamageRules.PHYSICAL_DAMAGE : CombatDamageRules.MAGIC_DAMAGE,hit,BATTLE_HIT_NORMAL);
                } else if (phase == 1 && op == REVIVE && saved.getRemainingTicks() > 0) {
                    if (actor.isDead()) {
                        actor.state().setIsDead(false);
                        long health = Unsigned.minUnsigned(actor.maxHealth(),ClassSkillRules.floorRatio(actor.maxHealth(),
                                row.getSecondaryRatio() > 0 ? row.getSecondaryRatio() : 0.2));
                        actor.setHealth(health == 0 ? 1 : health);
                        ctx.events().append(BATTLE_EVENT_REVIVE,saved.getCasterId(),actor.actorId()).setBuffInstanceId(saved.getBuffId())
                                .setBuffTableId(row.getId()).setTargetHealthAfter(actor.health());
                        remove(actor,saved.getBuffId());
                    } else {
                        impact.heal(caster,actor,saved.hasOutgoingHealSnapshot() ? saved.getOutgoingHealSnapshot() : Unsigned.toDouble(saved.getSnapshotValue()),
                                hit,false,saved.hasOutgoingHealSnapshot());
                    }
                } else if (phase == 2) {
                    if (op == PERIODIC || op == REVIVE) {
                        if (saved.getRemainingTicks() == 0) remove(actor,saved.getBuffId());
                    } else if (row.getDurationRounds() > 0) {
                        if (saved.getRemainRounds() <= 1) remove(actor,saved.getBuffId());
                        else actor.state().getBuffsBuilder(index).setRemainRounds(saved.getRemainRounds()-1);
                    }
                    continue;
                } else continue;
                index = actor.findBuffIndex(initial.getBuffId());
                if (index >= 0) actor.state().getBuffsBuilder(index).setRemainingTicks(saved.getRemainingTicks() - 1);
            }
        }
        for (var actor : ctx.units()) {
            for (var artifact : actor.state().getArtifactStatesBuilderList()) {
                if (artifact.getPendingRound() != 0 && artifact.getPendingRound() <= ctx.roundIndex()) artifact.clearPendingAilmentKind().clearPendingRound();
            }
            if (actor.state().hasSeasonSealState()) {
                var seal = actor.state().getSeasonSealStateBuilder();
                if (seal.getPendingRound() != 0 && seal.getPendingRound() <= ctx.roundIndex()) seal.clearPendingAilmentKind().clearPendingRound();
            }
        }
    }
}
