package com.game.battle.engine;

import static com.game.battle.engine.BattleEffect.*;
import static com.game.proto.eBattleEventType.*;
import static com.game.proto.eBattleHitKind.*;
import static com.game.common.math.Unsigned.*;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BattleBuffEntry;
import com.game.table.BuffTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 每个效果按原始表顺序求值，只有成功覆盖后才登记护盾法宝收益。 */
final class ConfiguredEffects {
    private final ConfiguredBattle battle;
    private final BattleContext ctx;

    ConfiguredEffects(ConfiguredBattle battle) {
        this.battle = battle;
        this.ctx = battle.ctx;
    }

    double value(BattleUnit source, BattleUnit target, BuffTable row, Hit hit) {
        double basis = switch (row.getValueBasis()) {
            case 1 -> toDouble(ctx.effectivePhysicalAttack(source));
            case 2 -> toDouble(source.magicAttack()) * Math.max(0.1, 1 + battle.modifier(source, MAGIC_ATTACK));
            case 3 -> toDouble(target.maxHealth());
            case 4 -> toDouble(target.maxMana());
            case 5 -> toDouble(hit.paidHealth);
            case 6 -> toDouble(hit.paidMana);
            case 7 -> toDouble(hit.actualDamage);
            default -> 0;
        };
        return SupportEffectRules.baseValue(row.getFlatValue(),row.getPerLevel(),source.level(),basis,row.getRatio(),hit.rank);
    }

    boolean apply(BattleUnit source, BattleUnit target, int id, Hit hit, int depth) {
        if (depth > BattleConstants.MAX_SUB_BUFF_DEPTH) return false;
        BuffTable row = battle.row(id);
        if (row == null) return false;
        int op = row.getBattleOp();
        if (op == 0) {
            battle.legacy.addBuffToActor(target,id,source.actorId(),depth);
            return false;
        }
        var receiver = row.getEffectTarget() == 1 || row.getEffectTarget() == 3 ? source : target;
        boolean hostile = source.teamIndex() != receiver.teamIndex();
        if (receiver.fled() || (receiver.isDead() && op != REVIVE) || (hostile && battle.hasState(receiver,FREEZE))) return false;
        double value = value(source,receiver,row,hit);
        if (op == PERIODIC && row.getStateKind() == POISON && source.isPet())
            value = SupportEffectRules.applyBonus(value,PetCultivationRules.rate(source.state().getPetCultivation().getPoisonDamageBps()));
        if (op == REVIVE) value = SupportEffectRules.applyBonus(value,battle.equipment.healingBonus(source));
        List<Integer> artifacts = new ArrayList<>();
        if (op == SHIELD && (source.isPet() || row.getHitLimit() == 0))
            value = battle.equipment.shield(source,receiver,value,hit,artifacts);
        if (instant(op) && row.getTriggerEvent() == 0) {
            switch (op) {
                case DAMAGE -> {
                    var skill = battle.skill(hit.skill);
                    if (skill == null) return false;
                    long attack = skill.getDamageType() == CombatDamageRules.PHYSICAL_DAMAGE ? ctx.effectivePhysicalAttack(source)
                            : fromDoubleSaturating(toDouble(source.magicAttack()) * Math.max(0.1,1+battle.modifier(source,MAGIC_ATTACK)));
                    var damage = ctx.calculateFinalDamage(source,receiver,ctx.data().skillDamage(hit.skill,Integer.toUnsignedLong(source.level())),
                            attack,skill.getAttackMultiplier(),skill.getDamageType());
                    double amount = damage.value() * ClassSkillRules.damageRankFactor(hit.rank) * hit.scale;
                    if (!row.getRequiredTag().isEmpty() && battle.tagged(receiver,row.getRequiredTag())) amount *= 1 + row.getConditionalBonus();
                    Hit direct = hit.derived(hit.origin);
                    direct.critical = damage.critical();
                    long dealt = battle.impact.damage(source,receiver,amount,skill.getDamageType(),direct,
                            hit.origin == MIRRORED ? BATTLE_HIT_MIRROR : BATTLE_HIT_NORMAL);
                    if (row.getSecondaryRatio() > 0 && dealt != 0 && source.isActive()) {
                        long heal = source.applyHeal(toDouble(dealt)*row.getSecondaryRatio());
                        ctx.events().append(BATTLE_EVENT_HEAL,source.actorId(),source.actorId()).setValue(heal).setTargetHealthAfter(source.health());
                    }
                    return dealt != 0;
                }
                case HEAL -> {
                    var skill = battle.skill(hit.skill);
                    if (skill != null && ClassSkillCombat.healthRatio(receiver) < skill.getExecuteHealThreshold()) value *= 1 + skill.getExecuteHealBonus();
                    battle.impact.heal(source,receiver,value,hit,skill != null && skill.getBattleModel() == 1,false);
                }
                case MANA, DRAIN -> mana(source,receiver,value,op == DRAIN);
                case DISPEL_DEBUFF, DISPEL_BUFF -> dispel(receiver,row);
                case EXTEND, CONSUME -> periodic(source,receiver,row,hit);
                case RETREAT -> {
                    if (!hostile || !receiver.isPet()) break;
                    double chance = Math.clamp((row.getApplicationChance() > 0 ? row.getApplicationChance() : 1)
                            - battle.modifier(receiver,RETREAT_RESIST)
                            + (source.isPet() ? PetCultivationRules.rate(source.state().getPetCultivation().getRetreatHitBps()) : 0)
                            - PetCultivationRules.rate(receiver.state().getPetCultivation().getRetreatResistBps()),0,1);
                    boolean success = chance >= 1 || (chance > 0 && ctx.rng().rand01() < chance);
                    ctx.events().append(BATTLE_EVENT_PET_RETREAT,source.actorId(),receiver.actorId()).setSuccess(success).setValue(1);
                    if (success) receiver.state().setFled(true).setWithdrawUntilRound(ctx.roundIndex()+Math.max(1,row.getDurationRounds())+1);
                }
                default -> { }
            }
            return false;
        }
        if (battle.legacy.isImmuneToBuff(receiver,row)) return false;
        int key = stackKey(row);
        long strength = fromDoubleSaturating(op == MODIFIER || op == AURA ? Math.abs(row.getRatio()) * 1000000 : value);
        BattleBuffEntry previous = null;
        for (var entry : receiver.state().getBuffsList()) {
            var old = battle.row(entry);
            if (old == null || stackKey(old) != key || (row.getRefreshPolicy() == 0 && entry.getCasterId() != source.actorId())) continue;
            long oldStrength = row.getRefreshPolicy() == 2 ? entry.getRemainingCapacity() : entry.getSnapshotValue();
            if (row.getRefreshPolicy() != 0 && Long.compareUnsigned(strength,oldStrength) < 0) return false;
            previous = entry;
            break;
        }
        if (hostile && row.getStateKind() >= 1 && row.getStateKind() <= 7 && (op == CONTROL || op == PERIODIC)) {
            if (!battle.equipment.controlRoll(source,receiver,row,hit)) return false;
        } else if (hostile && row.getApplicationChance() > 0 && row.getApplicationChance() < 1 && ctx.rng().rand01() >= row.getApplicationChance()) {
            ctx.events().append(BATTLE_EVENT_RESIST,source.actorId(),receiver.actorId()).setBuffTableId(id);
            return false;
        }
        if (previous != null) battle.remove(receiver,previous.getBuffId());
        long instance = battle.legacy.nextInstance();
        var added = receiver.state().addBuffsBuilder().setBuffId(instance).setBuffTableId(id).setCasterId(source.actorId())
                .setSourceSkillId(hit.skill).setLayer(1).setSnapshotValue(strength).setRemainingCapacity(fromDoubleSaturating(value))
                .setRemainingHits(row.getHitLimit()).setRemainingTicks(row.getTickCount()).setRemainRounds(row.getDurationRounds())
                .setAppliedRound(ctx.roundIndex()).setTickRound(ctx.roundIndex() + (row.getTickOnApply() != 0 ? 0 : 1))
                .setExtensionsUsed(previous == null ? 0 : previous.getExtensionsUsed());
        if (op == REVIVE) added.setOutgoingHealSnapshot(value);
        if (op == MODIFIER && row.getModifierStat() == ADAPTIVE)
            added.setChosenStat(Long.compareUnsigned(receiver.physicalAttack(),receiver.magicAttack()) >= 0 ? 1 : 2);
        if (op == TEMP_HEALTH) {
            long extra = minUnsigned(fromDoubleSaturating(value),receiver.maxHealth());
            added.setSecondaryValue(extra);
            receiver.state().setMaxHealth(EquipmentCombatRules.addSaturated(receiver.maxHealth(),extra));
            receiver.setHealth(EquipmentCombatRules.addSaturated(receiver.health(),extra));
            ctx.events().append(BATTLE_EVENT_MAX_HEALTH,source.actorId(),receiver.actorId()).setTargetMaxHealthAfter(receiver.maxHealth()).setTargetHealthAfter(receiver.health());
        }
        var event = ctx.events().append(BATTLE_EVENT_BUFF_ADD,source.actorId(),receiver.actorId()).setBuffTableId(id).setBuffInstanceId(instance).setValue(1);
        if (op == SHIELD) {
            event.setShieldAfter(fromDoubleSaturating(value));
            for (int index : artifacts) battle.equipment.use(source,index,receiver,source.state().getEquipmentEffects().getArtifacts(index).getBonusBps());
        }
        if (row.getTriggerEvent() == 0 && op != PURSUIT) {
            for (int child : row.getSubBuffList()) {
                if (receiver.findBuffIndex(instance) < 0) break;
                apply(source,receiver,child,hit,depth+1);
            }
        }
        return false;
    }

    private static int stackKey(BuffTable row) { return row.getStackKey() == 0 ? row.getId() : row.getStackKey(); }

    void mana(BattleUnit source, BattleUnit target, double value, boolean drain) {
        long amount = fromDoubleSaturating(value);
        long delta = drain ? minUnsigned(target.mana(),amount) : target.restoreMana(amount);
        if (drain) target.setMana(target.mana()-delta);
        ctx.events().append(BATTLE_EVENT_MANA,source.actorId(),target.actorId()).setValue(delta)
                .setTargetManaAfter(target.mana()).setManaDelta(EventLog.signedManaDelta(delta,!drain));
    }

    private void dispel(BattleUnit target, BuffTable row) {
        var candidates = target.state().getBuffsList().stream()
                .filter(e -> battle.row(e) != null && !battle.listener(e) && battle.legacy.isDebuffEntry(e) == (row.getBattleOp() == DISPEL_DEBUFF))
                .sorted(Comparator.comparing(e -> battle.row(e).getBattleOp() != CONTROL)).toList();
        int count = row.getEffectCount() == 0 ? candidates.size() : Math.min(candidates.size(),row.getEffectCount());
        for (int i = 0; i < count; i++) battle.remove(target,candidates.get(i).getBuffId());
    }

    private void periodic(BattleUnit source, BattleUnit target, BuffTable row, Hit hit) {
        for (var original : List.copyOf(target.state().getBuffsList())) {
            int index = target.findBuffIndex(original.getBuffId());
            if (index < 0) continue;
            var saved = target.state().getBuffs(index);
            var effect = battle.row(saved);
            if (effect == null || effect.getBattleOp() != PERIODIC || !(effect.containsTag(row.getRequiredTag())
                    || (!row.getSecondRequiredTag().isEmpty() && effect.containsTag(row.getSecondRequiredTag())))) continue;
            var entry = target.state().getBuffsBuilder(index);
            if (row.getBattleOp() == EXTEND) {
                if (saved.getExtensionsUsed() == 0)
                    entry.setRemainingTicks(saved.getRemainingTicks()+Math.max(1,row.getEffectCount())).setExtensionsUsed(1);
                continue;
            }
            int ticks = Math.min(saved.getRemainingTicks(),Math.max(1,row.getEffectCount()));
            entry.setRemainingTicks(saved.getRemainingTicks()-ticks);
            var caster = ctx.findActor(saved.getCasterId());
            var periodic = hit.derived(DOT);
            periodic.instigator = source.actorId();
            periodic.skill = saved.getSourceSkillId();
            periodic.periodicState = effect.getStateKind();
            for (int tick = 0; tick < ticks && !target.isDead(); tick++)
                battle.impact.damage(caster == null ? source : caster,target,toDouble(saved.getSnapshotValue()),
                        CombatDamageRules.MAGIC_DAMAGE,periodic,BATTLE_HIT_NORMAL);
            index = target.findBuffIndex(saved.getBuffId());
            if (index >= 0 && target.state().getBuffs(index).getRemainingTicks() == 0) battle.remove(target,saved.getBuffId());
        }
    }
}
