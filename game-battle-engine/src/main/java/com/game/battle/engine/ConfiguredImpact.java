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
        List<int[]> artifactContribs = new java.util.ArrayList<>();
        long[] artifactGains = new long[4];
        int artifactCount = 0;
        long seasonSealHealGain = 0L;
        if (equipmentHeal && hit.origin == DIRECT && hit.primary == target.actorId() && hit.healOrdinal == 0) {
            for (int index : battle.equipment.available(source,4)) {
                if (toDouble(target.health()) * 10000 > toDouble(target.maxHealth()) * battle.equipment.healthThresholdBps(source, index)) continue;
                long before = ctx.landedHeal(target, SupportEffectRules.applyBonus(amount,bonus) * received);
                bonus += battle.equipment.bonusBps(source, index)/10000.0;
                long after = ctx.landedHeal(target, SupportEffectRules.applyBonus(amount,bonus) * received);
                if (Long.compareUnsigned(after,before) > 0) {
                    if (index == BattleEquipment.SEASON_SEAL_INDEX) {
                        seasonSealHealGain = after - before;
                    } else if (artifactCount < artifactGains.length) {
                        artifactContribs.add(new int[]{index, artifactCount});
                        artifactGains[artifactCount++] = after - before;
                    }
                }
            }
        }
        long beforeNextRoundHealBoost = 0L;
        boolean applyNextRoundHealBoost = false;
        if (hit.origin == DIRECT
                && ctx.activeNextRoundBoostCasterId == source.actorId()
                && ctx.activeNextRoundBoostKind == SeasonRules.RESONANCE_GUARD_HEAL_BOOST
                && ctx.activeNextRoundBoostPct > 0) {
            beforeNextRoundHealBoost = ctx.landedHeal(target, SupportEffectRules.applyBonus(amount, bonus) * received);
            amount *= (100.0 + Integer.toUnsignedLong(ctx.activeNextRoundBoostPct)) / 100.0;
            applyNextRoundHealBoost = true;
        }
        int wuxingHealPct = ctx.wuxingHealShieldBonusPct(source);
        if (wuxingHealPct > 0) {
            amount *= (100.0 + wuxingHealPct) / 100.0;
        }
        long healed = ctx.applyHeal(target, SupportEffectRules.applyBonus(amount,bonus) * received);
        if (applyNextRoundHealBoost && Long.compareUnsigned(healed, beforeNextRoundHealBoost) > 0) {
            ctx.activeNextRoundBoostExtra = EquipmentCombatRules.addSaturated(
                    ctx.activeNextRoundBoostExtra, healed - beforeNextRoundHealBoost);
        }
        ctx.encounter.recordTrialMonsterHeal(source, target, healed);
        if (healed != 0) ctx.encounter.recordEffectiveContribution(source);
        for (int[] entry : artifactContribs) {
            battle.equipment.use(source, entry[0], target, artifactGains[entry[1]]);
        }
        if (seasonSealHealGain != 0L && battle.equipment.available(source, 4).contains(BattleEquipment.SEASON_SEAL_INDEX)) {
            var seal = source.state().getEquipmentEffects().getSeasonSeal();
            battle.equipment.use(source, BattleEquipment.SEASON_SEAL_INDEX, target, seasonSealHealGain);
            if (seal.getResonanceKind() == SeasonRules.RESONANCE_EMERGENCY_HEAL_REFUND
                    && seal.getResonanceSkillId() == hit.skill
                    && seal.getResonanceParam() > 0) {
                long manaBefore = source.mana();
                long missingMana = Long.compareUnsigned(source.maxMana(), manaBefore) > 0
                        ? source.maxMana() - manaBefore : 0L;
                long refund = minUnsigned(missingMana,
                        SeasonRules.computeManaPctCeil(hit.paidMana, seal.getResonanceParam()));
                if (refund != 0L) {
                    source.setMana(manaBefore + refund);
                    ctx.events().append(BATTLE_EVENT_MANA, source.actorId(), source.actorId())
                            .setSkillTableId(hit.skill)
                            .setValue(refund)
                            .setManaDelta(EventLog.signedManaDelta(refund, true))
                            .setTargetManaAfter(source.mana());
                    battle.equipment.emitSeasonResonance(source, source.actorId(),
                            SeasonRules.RESONANCE_EVENT_EMERGENCY_HEAL_REFUND, refund);
                }
            }
        }
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
            double selfGrowingDirect = battle.modifier(source, SELF_GROWING_DIRECT_DAMAGE);
            double oneShotOutgoing = hit.origin == DIRECT
                    ? battle.modifier(source, type == CombatDamageRules.PHYSICAL_DAMAGE
                            ? NEXT_PHYSICAL_ATTACK : NEXT_MAGIC_ATTACK)
                    : 0.0;
            damage *= Math.max(0, 1 + battle.modifier(source, OUT_DAMAGE) + typedOutgoing + selfGrowingDirect + oneShotOutgoing);
            if (hit.origin == DIRECT && kind != BATTLE_HIT_COUNTER) damage *= 1 + PetCultivationRules.damageBonus(source,type);
            if (singleTarget(source, hit) && ctx.hasTowerContext() && source.teamIndex() == 0
                    && ctx.towerSigilTotals().singleTargetDamagePct > 0) {
                damage *= (100.0 + ctx.towerSigilTotals().singleTargetDamagePct) / 100.0;
            }
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
        } else if (hit.origin == DOT) {
            damage *= Math.max(0.0, 1.0 + battle.modifier(source, PERIODIC_DAMAGE_BONUS));
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
                hit.isMultiTargetSkill, isPrimaryTarget, splashReduced,
                hit.isPreparedCast || hit.origin == DOT,
                hit.origin == PURSUED);
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
        long earthAbsorbed = target.absorbTournamentEarthShield(remaining);
        remaining -= earthAbsorbed;
        absorbed = EquipmentCombatRules.addSaturated(absorbed, earthAbsorbed);
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
        if (dealt != 0 && (hit.origin == DIRECT || hit.origin == REDIRECT) && target.isActive()) battle.trigger(target,source,AFTER_TAKE,type,trigger);
        if (dealt != 0 && hit.origin == DOT && source.isActive()) battle.trigger(source,target,AFTER_PERIODIC,type,trigger);
        // 五行幻境 V1 局内符印受击/暴击联动（403 暴击灼伤封顶 1500、302 受击回蓝、502/503 反震）。
        if (ctx.hasTowerContext() && direct && hit.origin != REFLECTED) {
            var totals = ctx.towerSigilTotals();
            if (hit.critical && source.teamIndex() == 0 && totals.critBurnMaxHpPct > 0 && target.isActive()) {
                long burn = Math.min(WuxingExpansionRules.FIRE_CRIT_BURN_CAP_PER_HIT,
                        Math.max(1L, Long.divideUnsigned(target.maxHealth() * totals.critBurnMaxHpPct, 100L)));
                long burnDealt = minUnsigned(target.health(), burn);
                if (burnDealt > 0L) {
                    target.setHealth(target.health() - burnDealt);
                    ctx.events().append(BATTLE_EVENT_DAMAGE, source.actorId(), target.actorId())
                            .setSkillTableId(hit.skill).setValue(burnDealt)
                            .setTargetHealthAfter(target.health()).setTargetManaAfter(target.mana())
                            .setHitKind(BATTLE_HIT_NORMAL).setDamageOrigin(DOT);
                    if (target.health() == 0L) {
                        ctx.handleDeath(target, source.actorId());
                    }
                }
            }
            if (dealt != 0L && target.teamIndex() == 0 && target.isActive()
                    && totals.onHitManaRegenPct > 0 && Long.compareUnsigned(target.maxMana(), 0L) > 0
                    && Long.compareUnsigned(target.mana(), target.maxMana()) < 0) {
                long manaGain = Math.max(1L, Long.divideUnsigned(target.maxMana() * totals.onHitManaRegenPct, 100L));
                long actual = target.restoreMana(manaGain);
                if (actual > 0L) {
                    ctx.events().append(BATTLE_EVENT_MANA, target.actorId(), target.actorId())
                            .setValue(actual).setManaDelta(EventLog.signedManaDelta(actual, true))
                            .setTargetManaAfter(target.mana());
                }
            }
            if (dealt != 0L && target.teamIndex() == 0 && totals.reflectPct > 0 && source.isActive() && source != target) {
                long reflected = Math.max(1L, Long.divideUnsigned(dealt * totals.reflectPct, 100L));
                damage(target, source, toDouble(reflected), CombatDamageRules.PHYSICAL_DAMAGE,
                        hit.derived(REFLECTED), BATTLE_HIT_NORMAL);
            }
        }
        return dealt;
    }

    private boolean singleTarget(BattleUnit source, Hit hit) {
        var skill = battle.skill(hit.skill);
        return skill == null || (source.isPet() ? skill.getPetMaxTargets() : skill.getMaxTargets()) <= 1;
    }
}
