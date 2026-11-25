package com.game.battle.engine;

import static com.game.battle.engine.BattleEffect.*;
import static com.game.common.math.Unsigned.*;
import static com.game.proto.eBattleEventType.*;
import static com.game.proto.eBattleHitKind.*;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BattleBuffEntry;
import com.game.proto.eBattleHitKind;
import java.util.Comparator;
import java.util.List;

/** 同一落点的顺序固定为闪避、增减伤、分担、吸收、法宝、扣血、死亡与受击监听。 */
final class ConfiguredImpact {
    private final ConfiguredBattle battle;
    private final BattleContext ctx;

    ConfiguredImpact(ConfiguredBattle battle) {
        this.battle = battle;
        ctx = battle.ctx;
    }

    void weaponHit(BattleUnit source, BattleUnit target, int type, double scale, Hit hit, eBattleHitKind kind) {
        var incoming = hit.origin == MIRRORED ? battle.skill(hit.skill) : null;
        double base = incoming == null ? BattleConstants.BASIC_ATTACK_BASE_DAMAGE : ctx.data().skillDamage(hit.skill,Integer.toUnsignedLong(source.level()));
        long attack = type == CombatDamageRules.PHYSICAL_DAMAGE ? ctx.effectivePhysicalAttack(source) : source.magicAttack();
        var damage = ctx.calculateFinalDamage(source,target,base,attack,incoming == null ? 1 : incoming.getAttackMultiplier(),type);
        hit.critical = damage.critical();
        damage(source,target,damage.value()*scale,type,hit,kind);
    }

    void heal(BattleUnit source, BattleUnit target, double amount, Hit hit, boolean equipmentHeal, boolean snapshot) {
        double bonus = snapshot ? 0 : equipmentHeal ? battle.equipment.healingBonus(source) : battle.modifier(source,OUT_HEAL);
        double received = Math.max(0,1+battle.modifier(target,IN_HEAL));
        if (equipmentHeal && hit.origin == DIRECT && hit.primary == target.actorId() && hit.healOrdinal == 0) {
            for (int index : battle.equipment.available(source,4)) {
                if (toDouble(target.health()) * 10000 > toDouble(target.maxHealth()) * battle.equipment.healthThresholdBps(source, index)) continue;
                long before = ctx.landedHeal(target, SupportEffectRules.applyBonus(amount,bonus) * received);
                bonus += battle.equipment.bonusBps(source, index)/10000.0;
                long after = ctx.landedHeal(target, SupportEffectRules.applyBonus(amount,bonus) * received);
                if (Long.compareUnsigned(after,before) > 0) battle.equipment.use(source,index,target,after-before);
            }
        }
        long healed = ctx.applyHeal(target, SupportEffectRules.applyBonus(amount,bonus) * received);
        ctx.encounter.recordTrialMonsterHeal(source, target, healed);
        if (healed != 0) ctx.encounter.recordEffectiveContribution(source);
        ctx.events().append(BATTLE_EVENT_HEAL,source.actorId(),target.actorId()).setSkillTableId(hit.skill).setValue(healed).setTargetHealthAfter(target.health());
    }

    long damage(BattleUnit source, BattleUnit target, double damage, int type, Hit hit, eBattleHitKind kind) {
        if (!target.isActive() || battle.hasState(target,FREEZE)) return 0;
        boolean direct = hit.origin == DIRECT || hit.origin == PURSUED || hit.origin == MIRRORED || hit.origin == CONFUSED;
        if (direct) {
            double dodge = Math.clamp(battle.modifier(target,EVASION),0,0.75);
            if (dodge > 0 && ctx.rng().rand01() < dodge) {
                ctx.events().append(BATTLE_EVENT_MISS,source.actorId(),target.actorId()).setSkillTableId(hit.skill).setTargetHealthAfter(target.health());
                if (hit.origin == DIRECT) battle.trigger(target,source,AFTER_DODGE,type,hit);
                return 0;
            }
            double typedOutgoing = type == CombatDamageRules.PHYSICAL_DAMAGE
                    ? battle.modifier(source, OUT_PHYSICAL_DAMAGE)
                    : battle.modifier(source, OUT_MAGIC_DAMAGE);
            double oneShotOutgoing = hit.origin == DIRECT
                    ? battle.modifier(source, type == CombatDamageRules.PHYSICAL_DAMAGE
                            ? NEXT_PHYSICAL_ATTACK : NEXT_MAGIC_ATTACK)
                    : 0.0;
            damage *= Math.max(0, 1 + battle.modifier(source, OUT_DAMAGE) + typedOutgoing + oneShotOutgoing);
            if (hit.origin == DIRECT && kind != BATTLE_HIT_COUNTER) damage *= 1 + PetCultivationRules.damageBonus(source,type);
            damage *= Math.max(0,1+battle.modifier(target,IN_DAMAGE)+battle.hitVulnerabilityBonus(target));
            if (type == CombatDamageRules.MAGIC_DAMAGE) damage *= Math.max(0,1-battle.modifier(target,MAGIC_REDUCTION));
            if (kind == BATTLE_HIT_COMBO || hit.damageOrdinal > 0) damage *= Math.max(0,1-battle.modifier(target,LATER_REDUCTION));
            for (var entry : List.copyOf(source.state().getBuffsList())) {
                var row = battle.row(entry);
                if (row == null || row.getBattleOp() != MODIFIER) continue;
                if (row.getModifierStat() == ADAPTIVE && entry.getChosenStat() == (type == CombatDamageRules.PHYSICAL_DAMAGE ? 1 : 2)) damage *= 1+row.getRatio();
                if (row.getModifierStat() == NEXT_DAMAGE && hit.origin == DIRECT && singleTarget(source,hit)) {
                    damage *= 1+row.getRatio();
                    battle.remove(source,entry.getBuffId());
                }
            }
        }
        if (ctx.actorHasBuffOfType(target,BattleConstants.BUFF_TYPE_INVINCIBILITY)) damage = 0;
        if (target.isDefending() && hit.origin != REFLECTED && hit.origin != REDIRECT) damage *= 0.5;
        if (ctx.escalationStacks() > 0 && hit.origin != REDIRECT && hit.origin != COST) {
            damage *= ctx.escalationDamagePct() / 100.0;
        }
        long remaining = CombatDamageRules.damageToHealth(damage,-1L);
        boolean isPrimaryTarget = hit.primary == 0L || target.actorId() == hit.primary;
        boolean[] splashReduced = new boolean[1];
        remaining = ctx.encounter.applyDamageModifiers(source, target, remaining, type,
                direct && hit.origin == DIRECT && kind != BATTLE_HIT_COUNTER,
                hit.isMultiTargetSkill, isPrimaryTarget, splashReduced);
        var defenses = direct ? target.state().getBuffsList().stream().filter(e -> {
            var row = battle.row(e);
            return row != null && ConfiguredBattle.matches(row,type);
        }).sorted(Comparator.<BattleBuffEntry>comparingInt(e -> battle.row(e).getEffectPriority())
                .thenComparing(BattleBuffEntry::getBuffId,Long::compareUnsigned)).toList() : List.<BattleBuffEntry>of();
        if (singleTarget(source,hit)) for (var entry : defenses) {
            if (remaining == 0) break;
            var row = battle.row(entry);
            if (row.getBattleOp() != SHARE || target.findBuffIndex(entry.getBuffId()) < 0) continue;
            var protector = ctx.findActor(entry.getCasterId());
            if (protector == null || protector == target || !protector.isActive() || battle.hasState(protector,FREEZE)) continue;
            long budget = row.getSecondaryRatio() > 0 ? ClassSkillRules.floorRatio(protector.maxHealth(),row.getSecondaryRatio()) : -1L;
            long spent = protector.state().getRoundEffectUsageOrDefault(entry.getSourceSkillId(),0);
            long share = minUnsigned(minUnsigned(remaining,ClassSkillRules.floorRatio(remaining,Math.clamp(row.getRatio(),0,0.8))),
                    Long.compareUnsigned(budget,spent) > 0 ? budget-spent : 0);
            protector.state().putRoundEffectUsage(entry.getSourceSkillId(),EquipmentCombatRules.addSaturated(spent,share));
            remaining -= share;
            damage(source,protector,toDouble(share),type,hit.derived(REDIRECT),BATTLE_HIT_NORMAL);
        }
        long shieldDamage = remaining, absorbed = 0;
        for (var entry : defenses) {
            if (remaining == 0) break;
            int index = target.findBuffIndex(entry.getBuffId());
            if (index < 0) continue;
            var saved = target.state().getBuffs(index);
            var row = battle.row(saved);
            if (row.getBattleOp() == MANA_SHIELD) {
                double conversion = row.getSecondaryRatio() > 0 ? row.getSecondaryRatio() : 1;
                long cover = minUnsigned(ClassSkillRules.floorRatio(remaining,Math.clamp(row.getRatio(),0,1)),fromDoubleSaturating(toDouble(target.mana())/conversion));
                long cost = minUnsigned(target.mana(),ClassSkillRules.ceilRatio(cover,conversion));
                target.setMana(target.mana()-cost);
                remaining -= cover;
                absorbed += cover;
                ctx.events().append(BATTLE_EVENT_MANA,target.actorId(),target.actorId()).setValue(cost).setManaDelta(EventLog.signedManaDelta(cost,false)).setTargetManaAfter(target.mana());
            } else if (row.getBattleOp() == SHIELD) {
                long cover = minUnsigned(remaining,saved.getRemainingCapacity());
                remaining -= cover;
                absorbed += cover;
                long capacity = saved.getRemainingCapacity()-cover;
                var live = target.state().getBuffsBuilder(index).setRemainingCapacity(capacity);
                if (saved.getRemainingHits() > 0) live.setRemainingHits(saved.getRemainingHits()-1);
                ctx.events().append(BATTLE_EVENT_SHIELD,saved.getCasterId(),target.actorId()).setBuffTableId(row.getId()).setBuffInstanceId(saved.getBuffId())
                        .setAbsorbed(cover).setShieldAfter(capacity);
                if (capacity == 0 || saved.getRemainingHits() == 1) battle.remove(target,saved.getBuffId());
            }
        }
        remaining = battle.equipment.reduce(source,target,remaining,hit);
        long dealt = minUnsigned(target.health(),remaining);
        target.setHealth(target.health()-dealt);
        if (hit.origin == DIRECT) {
            ctx.encounter.recordTrialDirectDamage(source, target, type, EquipmentCombatRules.addSaturated(dealt, absorbed));
        }
        if (dealt != 0 || absorbed != 0) ctx.encounter.recordEffectiveContribution(source);
        var dmgEvent = ctx.events().append(BATTLE_EVENT_DAMAGE,source.actorId(),target.actorId()).setSkillTableId(hit.skill).setValue(dealt).setAbsorbed(absorbed)
                .setTargetHealthAfter(target.health()).setTargetManaAfter(target.mana()).setHitKind(kind).setDamageOrigin(hit.origin)
                .setInstigatorId(hit.instigator).setIsCritical(hit.critical);
        if (hit.isPrimaryFocus) {
            dmgEvent.setIsPrimaryFocus(true);
        }
        if (splashReduced[0]) {
            dmgEvent.setIsSplashReduced(true);
        }
        if (dealt != 0) for (var entry : List.copyOf(target.state().getBuffsList())) {
            var row = battle.row(entry);
            if (row != null && row.getBattleOp() == CONTROL && row.getStateKind() == SLEEP) battle.remove(target,entry.getBuffId());
        }
        if (target.health() == 0) ctx.handleDeath(target,source.actorId());
        if (EquipmentCombatRules.addSaturated(dealt, absorbed) != 0L
                && (hit.origin == DIRECT || hit.origin == PURSUED)
                && target.isActive()) {
            battle.incrementHitVulnerability(target);
        }
        battle.equipment.breakShields(source,target,shieldDamage,type,hit,defenses);
        Hit trigger = hit.derived(hit.origin);
        trigger.actualDamage = dealt;
        if (dealt != 0 && hit.origin == DIRECT && target.isActive()) battle.trigger(target,source,AFTER_TAKE,type,trigger);
        if (dealt != 0 && hit.origin == DOT && source.isActive()) battle.trigger(source,target,AFTER_PERIODIC,type,trigger);
        return dealt;
    }

    private boolean singleTarget(BattleUnit source, Hit hit) {
        var skill = battle.skill(hit.skill);
        return skill == null || (source.isPet() ? skill.getPetMaxTargets() : skill.getMaxTargets()) <= 1;
    }
}
