package com.game.battle.engine;

import static com.game.battle.engine.BattleEffect.*;
import static com.game.common.math.Unsigned.*;
import static com.game.proto.eBattleEventType.*;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.*;
import com.game.table.BuffTable;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 已冻结装备快照的消费者。每件法宝的次数按实例独立记账，重复技能和多个目标共享同一份预算。 */
final class BattleEquipment {
    private final ConfiguredBattle battle;
    private final BattleContext ctx;
    private final java.util.Set<Long> unknownLearnedSkills = new HashSet<>();

    void inspectLearnedSkills(BattlePlayerSnapshot snapshot) {
        boolean unknown = snapshot.getSkillRanksMap().entrySet().stream().anyMatch(e ->
                e.getValue() != 0 && battle.skill(e.getKey()) == null && !SupportEffectRules.retiredSkill(e.getKey()));
        if (unknown) unknownLearnedSkills.add(snapshot.getPlayerId());
    }

    BattleEquipment(ConfiguredBattle battle) {
        this.battle = battle;
        ctx = battle.ctx;
    }

    private static boolean bounded(Map<Integer,Integer> values, int keys, int max) {
        return values.entrySet().stream().allMatch(e -> e.getKey() > 0 && e.getKey() <= keys && Integer.compareUnsigned(e.getValue(),max) <= 0);
    }

    static boolean validPet(PetCultivationEffects p) {
        return java.util.stream.IntStream.of(p.getPhysicalDamageBps(),p.getMagicDamageBps(),p.getOutgoingHealBps(),p.getShieldCapacityBps(),
                p.getPoisonDamageBps(),p.getRetreatHitBps(),p.getRetreatResistBps()).allMatch(i -> Integer.compareUnsigned(i,10000) <= 0)
                && bounded(p.getAilmentHitBpsMap(),7,10000) && bounded(p.getAilmentResistBpsMap(),7,10000);
    }

    /** 哨兵下标：标识单槽赛季印（不进入永久法宝数组，使用独立 {@code season_seal_state} 与 {@code season_seal_id}）。 */
    static final int SEASON_SEAL_INDEX = -1;
    /** 控辅 V1 基础装 +11 克制强化受益后的有效全身上限（施放治疗/护盾 50% 份额 3200->3584，控场命中 25% 份额 1600->1696；速度恒定 200）。 */
    static final int MAX_EFFECTIVE_SUPPORT_BPS = 3584;
    static final int MAX_EFFECTIVE_AILMENT_HIT_BPS = 1696;

    private static boolean validArtifact(EquipmentArtifactSource a) {
        return a.getItemId() > 0 && SeasonRules.validArtifactDefinition(a.getEffectId(), a.getBonusBps(),
                a.getCapBps(), a.getPerRoundLimit(), a.getBattleLimit(), a.getActiveRounds(), a.getHealthThresholdBps());
    }

    private static boolean validSeasonSeal(SeasonSealEffect s) {
        return SeasonRules.validSealDefinition(s.getSeasonId(), s.getSealId(), s.getEffectId(), s.getBonusBps(),
                s.getCapBps(), s.getPerRoundLimit(), s.getBattleLimit(), s.getActiveRounds(), s.getHealthThresholdBps(),
                s.getResonanceSkillId(), s.getResonanceKind(), s.getResonanceParam());
    }

    static boolean initialize(BattleActorState.Builder actor, EquipmentEffects equipment) {
        int[] values = {equipment.getPhysicalAttackBps(),equipment.getMagicAttackBps(),equipment.getMaxHealthBps(),
                equipment.getSpeedBps(),equipment.getOutgoingHealBps(),equipment.getShieldCapacityBps(),equipment.getPhysicalCritBps(),
                equipment.getMagicCritBps(),equipment.getPhysicalResistBps(),equipment.getMagicResistBps()};
        int[] limits = {3200,3200,800,200,MAX_EFFECTIVE_SUPPORT_BPS,MAX_EFFECTIVE_SUPPORT_BPS,800,800,600,600};
        for (int i = 0; i < values.length; i++) if (Integer.compareUnsigned(values[i],limits[i]) > 0) return false;
        if (Integer.compareUnsigned(equipment.getSeasonMaxHealthBps(), SeasonRules.HARD_STAT_CAP_BPS) > 0
                || Integer.compareUnsigned(equipment.getSeasonDefenseBps(), SeasonRules.HARD_STAT_CAP_BPS) > 0) return false;
        if (!bounded(equipment.getAilmentHitBpsMap(),7,MAX_EFFECTIVE_AILMENT_HIT_BPS) || !bounded(equipment.getAilmentResistBpsMap(),7,1600)
                || !bounded(equipment.getManaReductionBpsMap(),12,1200) || equipment.getArtifactsCount() > 2) return false;
        var ids = new HashSet<Long>();
        var copy = equipment.toBuilder().clearArtifacts();
        for (var artifact : equipment.getArtifactsList().stream().sorted(Comparator.comparingLong(EquipmentArtifactSource::getItemId)).toList()) {
            if (!validArtifact(artifact) || !ids.add(artifact.getItemId())) return false;
            copy.addArtifacts(artifact);
            actor.addArtifactStatesBuilder().setItemId(artifact.getItemId());
        }
        actor.clearSeasonSealState();
        if (equipment.hasSeasonSeal()) {
            var seal = equipment.getSeasonSeal();
            if (!validSeasonSeal(seal)) return false;
            actor.getSeasonSealStateBuilder().setItemId(Integer.toUnsignedLong(seal.getSealId()));
        }
        if (!validSources(equipment)) copy.clearSupportSources().clearSupportSourcesComplete();
        actor.setEquipmentEffects(copy).setEntryMaxHealth(actor.getMaxHealth()).setEntryMaxMana(actor.getMaxMana());
        return true;
    }

    private record SourceKey(long itemId,int affixId) { }
    private static boolean validSources(EquipmentEffects equipment) {
        if (!equipment.getSupportSourcesComplete()) return false;
        var seen = new HashSet<SourceKey>();
        double heal = 0, shield = 0;
        for (var s : equipment.getSupportSourcesList()) {
            if (s.getKind() != 1 || s.getAffixId() == 0 || s.getItemId() == 0 || !seen.add(new SourceKey(s.getItemId(),s.getAffixId()))
                    || (s.getEffectKind() != 1 && s.getEffectKind() != 2) || s.getConditional() || !Double.isFinite(s.getRawBonusBps())
                    || !Double.isFinite(s.getEffectiveBonusBps()) || s.getRawBonusBps() < 0 || s.getEffectiveBonusBps() < 0
                    || s.getEffectiveBonusBps() > s.getRawBonusBps()) return false;
            if (s.getEffectKind() == 1) heal += s.getEffectiveBonusBps(); else shield += s.getEffectiveBonusBps();
        }
        return heal == equipment.getOutgoingHealBps() && shield == equipment.getShieldCapacityBps();
    }

    int bonusBps(BattleUnit actor, int index) {
        var eq = actor.state().getEquipmentEffects();
        return index == SEASON_SEAL_INDEX ? eq.getSeasonSeal().getBonusBps() : eq.getArtifacts(index).getBonusBps();
    }

    int capBps(BattleUnit actor, int index) {
        var eq = actor.state().getEquipmentEffects();
        return index == SEASON_SEAL_INDEX ? eq.getSeasonSeal().getCapBps() : eq.getArtifacts(index).getCapBps();
    }

    int healthThresholdBps(BattleUnit actor, int index) {
        var eq = actor.state().getEquipmentEffects();
        return index == SEASON_SEAL_INDEX ? eq.getSeasonSeal().getHealthThresholdBps() : eq.getArtifacts(index).getHealthThresholdBps();
    }

    private static boolean canTrigger(int effectId, int activeRounds, int battleLimit, int perRoundLimit,
                                      BattleArtifactState usage, int kind, int roundIndex) {
        return effectId == kind && (activeRounds == 0 || roundIndex <= activeRounds)
                && (battleLimit == 0 || usage.getUsedCount() < battleLimit)
                && (perRoundLimit == 0 || usage.getLastTriggerRound() != roundIndex || usage.getRoundTriggerCount() < perRoundLimit);
    }

    List<Integer> available(BattleUnit actor, int kind) {
        var list = new ArrayList<Integer>();
        if (!actor.isPlayer() || !actor.isActive()) return list;
        var equipment = actor.state().getEquipmentEffects();
        for (int i = 0; i < equipment.getArtifactsCount(); i++) {
            var a = equipment.getArtifacts(i);
            if (canTrigger(a.getEffectId(), a.getActiveRounds(), a.getBattleLimit(), a.getPerRoundLimit(),
                    actor.state().getArtifactStates(i), kind, ctx.roundIndex()))
                list.add(i);
        }
        if (equipment.hasSeasonSeal() && actor.state().hasSeasonSealState()) {
            var seal = equipment.getSeasonSeal();
            if (canTrigger(seal.getEffectId(), seal.getActiveRounds(), seal.getBattleLimit(), seal.getPerRoundLimit(),
                    actor.state().getSeasonSealState(), kind, ctx.roundIndex()))
                list.add(SEASON_SEAL_INDEX);
        }
        return list;
    }

    void use(BattleUnit source, int index, BattleUnit target, long value) {
        BattleArtifactState.Builder state = index == SEASON_SEAL_INDEX
                ? source.state().getSeasonSealStateBuilder()
                : source.state().getArtifactStatesBuilder(index);
        state.setUsedCount(state.getUsedCount()+1).setRoundTriggerCount(state.getLastTriggerRound() == ctx.roundIndex() ? state.getRoundTriggerCount()+1 : 1)
                .setLastTriggerRound(ctx.roundIndex());
        var ev = ctx.events().append(BATTLE_EVENT_ARTIFACT,source.actorId(),target.actorId()).setValue(value).setSuccess(true);
        if (index == SEASON_SEAL_INDEX) {
            var seal = source.state().getEquipmentEffects().getSeasonSeal();
            ev.setSeasonSealId(seal.getSealId()).setArtifactEffectId(seal.getEffectId());
            var rt = ctx.seasonSealRuntime(source.actorId());
            rt.triggerCount += 1;
            rt.contributionValue = EquipmentCombatRules.addSaturated(rt.contributionValue, value);
        } else {
            var a = source.state().getEquipmentEffects().getArtifacts(index);
            ev.setArtifactItemId(a.getItemId()).setArtifactEffectId(a.getEffectId());
        }
    }

    boolean hasAnyRegularShield(BattleUnit target) {
        if (target == null) return false;
        for (var entry : target.state().getBuffsList()) {
            if (entry.getRemainingCapacity() == 0L) continue;
            var row = battle.row(entry);
            if (row != null && row.getBattleOp() == SHIELD && row.getHitLimit() == 0) {
                return true;
            }
        }
        return false;
    }

    int defendManaDiscountResonancePct(BattleUnit actor, int skillId) {
        if (actor == null || !actor.isPlayer()
                || !actor.state().getEquipmentEffects().hasSeasonSeal()) {
            return 0;
        }
        var seal = actor.state().getEquipmentEffects().getSeasonSeal();
        if (seal.getResonanceKind() != SeasonRules.RESONANCE_DEFEND_MANA_DISCOUNT
                || seal.getResonanceSkillId() != skillId
                || seal.getResonanceParam() == 0) {
            return 0;
        }
        var rt = ctx.findSeasonSealRuntime(actor.actorId());
        return rt != null && rt.defendResonanceReadyRound == ctx.roundIndex() ? seal.getResonanceParam() : 0;
    }

    void emitSeasonResonance(BattleUnit actor, long targetId, int resonanceEffectId, long value) {
        if (actor == null || !actor.state().getEquipmentEffects().hasSeasonSeal()) return;
        var seal = actor.state().getEquipmentEffects().getSeasonSeal();
        var rt = ctx.seasonSealRuntime(actor.actorId());
        rt.resonanceCount += 1;
        if (actor.isPlayer()) {
            ctx.encounter.recordSeasonResonance(actor.actorId(), resonanceEffectId);
        }
        ctx.events().append(BATTLE_EVENT_ARTIFACT, actor.actorId(), targetId)
                .setArtifactItemId(0L)
                .setArtifactEffectId(resonanceEffectId)
                .setSeasonSealId(seal.getSealId())
                .setValue(value)
                .setSuccess(true);
    }

    static long floorBps(long amount, int bps) {
        return Long.divideUnsigned(amount,10000)*bps + Long.remainderUnsigned(amount,10000)*bps/10000;
    }

    private double discount(BattleUnit source, SkillTable skill) {
        int category = SupportEffectRules.manaCategory(skill,battle::row);
        return source.state().getEquipmentEffects().getManaReductionBpsOrDefault(category,0) - 10000*battle.modifier(source,MANA_COST);
    }

    long manaCost(BattleUnit source, SkillTable skill) {
        long base = SupportEffectRules.discountMana(ClassSkillRules.manaCost(source.maxMana(), skill.getManaPercent()),
                discount(source, skill));
        long cost = ctx.encounter.adjustWeeklySkillManaCost(source, skill, base);
        int discountPct = defendManaDiscountResonancePct(source, skill.getId());
        if (discountPct > 0) {
            cost = SeasonRules.computeManaDiscounted(cost, discountPct);
        }
        return cost;
    }

    double healingBonus(BattleUnit source) {
        return ctx.outgoingHealBonus(source) + battle.modifier(source,OUT_HEAL)
                + source.state().getEquipmentEffects().getOutgoingHealBps()/10000.0 + PetCultivationRules.healBonus(source);
    }

    double shield(BattleUnit source, BattleUnit target, double base, Hit hit, List<Integer> contributions,
                  long[] outSeasonSealGain) {
        if (outSeasonSealGain != null && outSeasonSealGain.length > 0) outSeasonSealGain[0] = 0L;
        double modifier = battle.modifier(source,OUT_SHIELD);
        if (source.isPet()) return SupportEffectRules.applyBonus(base,modifier + PetCultivationRules.rate(source.state().getPetCultivation().getShieldCapacityBps()));
        if (!source.isPlayer()) return base;
        double bonus = modifier + source.state().getEquipmentEffects().getShieldCapacityBps()/10000.0;
        if (hit.origin == DIRECT && hit.primary == target.actorId() && hit.shieldOrdinal == 0) {
            for (int index : available(source,5)) {
                double previous = bonus;
                bonus += bonusBps(source, index)/10000.0;
                long beforeFloor = fromDoubleSaturating(Math.floor(Math.max(0.0, base*(1+previous))));
                long afterFloor = fromDoubleSaturating(Math.floor(Math.max(0.0, base*(1+bonus))));
                if (Long.compareUnsigned(afterFloor, beforeFloor) > 0) {
                    if (index == SEASON_SEAL_INDEX) {
                        if (outSeasonSealGain != null && outSeasonSealGain.length > 0) {
                            outSeasonSealGain[0] = afterFloor - beforeFloor;
                        }
                    } else {
                        contributions.add(index);
                    }
                }
            }
        }
        return SupportEffectRules.applyBonus(base,bonus);
    }

    void commitSeasonSealShield(BattleUnit source, BattleUnit target, long seasonSealGain, int castSkillId) {
        if (seasonSealGain == 0L || !available(source, 5).contains(SEASON_SEAL_INDEX)) {
            return;
        }
        var seal = source.state().getEquipmentEffects().getSeasonSeal();
        use(source, SEASON_SEAL_INDEX, target, seasonSealGain);
        if (seal.getResonanceKind() == SeasonRules.RESONANCE_FIRST_SHIELD_MANA
                && seal.getResonanceSkillId() == castSkillId
                && seal.getResonanceParam() > 0) {
            long manaBefore = source.mana();
            long missingMana = Long.compareUnsigned(source.maxMana(), manaBefore) > 0
                    ? source.maxMana() - manaBefore : 0L;
            long gain = minUnsigned(missingMana, floorBps(source.state().getEntryMaxMana(), seal.getResonanceParam()));
            if (gain != 0L) {
                source.setMana(manaBefore + gain);
                ctx.events().append(BATTLE_EVENT_MANA, source.actorId(), source.actorId())
                        .setSkillTableId(castSkillId)
                        .setValue(gain)
                        .setManaDelta(EventLog.signedManaDelta(gain, true))
                        .setTargetManaAfter(source.mana());
                emitSeasonResonance(source, source.actorId(),
                        SeasonRules.RESONANCE_EVENT_FIRST_SHIELD_MANA, gain);
            }
        }
    }

    void defend(BattleUnit actor) {
        long missing = Long.compareUnsigned(actor.maxMana(),actor.mana()) > 0 ? actor.maxMana()-actor.mana() : 0;
        long total = 0;
        for (int index : available(actor,7)) {
            long gain = minUnsigned(missing-total,floorBps(actor.state().getEntryMaxMana(),bonusBps(actor, index)));
            if (gain == 0) continue;
            total += gain;
            use(actor,index,actor,gain);
            if (index == SEASON_SEAL_INDEX) {
                var seal = actor.state().getEquipmentEffects().getSeasonSeal();
                if (seal.getResonanceKind() == SeasonRules.RESONANCE_DEFEND_MANA_DISCOUNT
                        || seal.getResonanceKind() == SeasonRules.RESONANCE_DEFEND_SHIELD_BOOST) {
                    ctx.seasonSealRuntime(actor.actorId()).defendResonanceReadyRound = ctx.roundIndex() + 1;
                }
            }
        }
        if (total == 0) return;
        actor.setMana(actor.mana()+total);
        ctx.events().append(BATTLE_EVENT_MANA,actor.actorId(),actor.actorId()).setValue(total).setManaDelta(EventLog.signedManaDelta(total,true)).setTargetManaAfter(actor.mana());
    }

    long reduce(BattleUnit source, BattleUnit target, long amount, Hit hit) {
        if (source.teamIndex() == target.teamIndex() || (hit.origin != DIRECT && hit.origin != MIRRORED && hit.origin != PURSUED)) return amount;
        long remaining = amount;
        for (int index : available(target,6)) {
            long diff = minUnsigned(remaining,minUnsigned(floorBps(amount,bonusBps(target, index)),floorBps(target.state().getEntryMaxHealth(),capBps(target, index))));
            if (diff == 0) continue;
            remaining -= diff;
            use(target,index,target,diff);
            if (index == SEASON_SEAL_INDEX) {
                var seal = target.state().getEquipmentEffects().getSeasonSeal();
                if (seal.getResonanceKind() == SeasonRules.RESONANCE_GUARD_SHIELD_BOOST
                        || seal.getResonanceKind() == SeasonRules.RESONANCE_GUARD_HEAL_BOOST) {
                    var rt = ctx.seasonSealRuntime(target.actorId());
                    if (!rt.guardResonanceConsumed) {
                        rt.guardResonanceReadyRound = ctx.roundIndex() + 1;
                    }
                }
            }
        }
        return remaining;
    }

    void breakShields(BattleUnit source, BattleUnit target, long amount, int type, Hit hit, List<BattleBuffEntry> defenses) {
        if (!target.isActive() || hit.origin != DIRECT || hit.primary != target.actorId() || hit.damageOrdinal != 0 || amount == 0) return;
        int effect = type == CombatDamageRules.PHYSICAL_DAMAGE ? 1 : type == CombatDamageRules.MAGIC_DAMAGE ? 2 : 0;
        int total = 0;
        for (int index : available(source,effect)) {
            long before = floorBps(amount, Math.min(10000,total));
            total += bonusBps(source, index);
            long left = floorBps(amount, Math.min(10000,total))-before, removed = 0;
            for (var entry : defenses) {
                var row = battle.row(entry);
                if (row.getBattleOp() != SHIELD || row.getHitLimit() != 0) continue;
                int i = target.findBuffIndex(entry.getBuffId());
                if (i < 0 || left == 0) continue;
                var live = target.state().getBuffsBuilder(i);
                long part = minUnsigned(left,live.getRemainingCapacity());
                if (part == 0) continue;
                left -= part;
                removed += part;
                live.setRemainingCapacity(live.getRemainingCapacity()-part);
                var ev = ctx.events().append(BATTLE_EVENT_SHIELD,source.actorId(),target.actorId()).setBuffTableId(row.getId()).setBuffInstanceId(entry.getBuffId())
                        .setAbsorbed(part).setShieldAfter(live.getRemainingCapacity()).setArtifactEffectId(effect);
                if (index == SEASON_SEAL_INDEX) ev.setSeasonSealId(source.state().getEquipmentEffects().getSeasonSeal().getSealId());
                else ev.setArtifactItemId(source.state().getEquipmentEffects().getArtifacts(index).getItemId());
                if (live.getRemainingCapacity() == 0) battle.remove(target,entry.getBuffId());
            }
            if (removed != 0) {
                use(source,index,target,removed);
                if (source.isPlayer()) ctx.encounter.markShieldBreak(source);
                if (index == SEASON_SEAL_INDEX) {
                    var seal = source.state().getEquipmentEffects().getSeasonSeal();
                    if (seal.getResonanceSkillId() == hit.skill && seal.getResonanceParam() > 0) {
                        if (seal.getResonanceKind() == SeasonRules.RESONANCE_SHARP_MANA_REFUND
                                && !hasAnyRegularShield(target)) {
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
                                emitSeasonResonance(source, source.actorId(),
                                        SeasonRules.RESONANCE_EVENT_SHARP_MANA_REFUND, refund);
                            }
                        } else if (seal.getResonanceKind() == SeasonRules.RESONANCE_BREAK_EXTEND_DEBUFF) {
                            ctx.pendingMysticExtendCasterId = source.actorId();
                            ctx.pendingMysticExtendTargetId = target.actorId();
                            ctx.pendingMysticExtendSkillId = hit.skill;
                            ctx.pendingMysticExtendBonusRounds = seal.getResonanceParam();
                        }
                    }
                }
            }
        }
    }

    boolean controlRoll(BattleUnit source, BattleUnit target, BuffTable row, Hit hit) {
        int kind = row.getStateKind();
        if (!Double.isFinite(row.getApplicationChance()) || row.getApplicationChance() <= 0) return false;
        var skill = battle.skill(hit.skill);
        boolean normal = hit.origin == DIRECT && hit.primary != 0;
        boolean primary = normal && hit.primary == target.actorId() && skill != null && skill.getEffectList().stream().map(battle::row)
                .filter(r -> r != null && r.getStateKind() >= 1 && r.getStateKind() <= 7 && (r.getBattleOp() == CONTROL || r.getBattleOp() == PERIODIC))
                .findFirst().map(r -> r.getId() == row.getId()).orElse(false);
        var equipment = source.state().getEquipmentEffects();
        double accuracy = equipment.getAilmentHitBpsOrDefault(kind,0);
        if (source.isPet()) accuracy += source.state().getPetCultivation().getAilmentHitBpsOrDefault(kind,0);
        double resist = target.state().getEquipmentEffects().getAilmentResistBpsOrDefault(kind,0);
        if (target.isPet()) resist += target.state().getPetCultivation().getAilmentResistBpsOrDefault(kind,0);
        var combat = target.combat();
        resist += 100*toDouble(switch (kind) {
            case POISON -> combat.getResistPoison(); case FREEZE -> combat.getResistFreeze(); case SLEEP -> combat.getResistSleep();
            case CONFUSION -> combat.getResistConfusion(); default -> combat.getResistForget();
        });
        if (kind == CONFUSION || kind == SLEEP) resist += 10000*battle.modifier(target,CONTROL_RESIST);
        if (kind >= 1 && kind <= 6) resist += 10000*battle.modifier(target,ALL_CONTROL_RESIST);
        if (ctx.activeTournamentLeyline() == com.game.proto.TournamentLeylineElement.TOURNAMENT_LEYLINE_WATER) {
            resist += 800.0;
        }
        int wuxingControlResistPct = ctx.wuxingControlResistPct(target);
        if (wuxingControlResistPct > 0) {
            resist += wuxingControlResistPct * 100.0;
        }
        boolean consumed = false;
        if (primary) {
            for (int i=0; i<equipment.getArtifactsCount(); i++) {
                var a = equipment.getArtifacts(i);
                var state = source.state().getArtifactStatesBuilder(i);
                if (a.getEffectId() == 3 && state.getPendingRound() == ctx.roundIndex() && state.getPendingAilmentKind() == kind) {
                    accuracy += a.getBonusBps();
                    state.clearPendingRound().clearPendingAilmentKind();
                    consumed = true;
                }
            }
            if (equipment.hasSeasonSeal() && source.state().hasSeasonSealState()) {
                var seal = equipment.getSeasonSeal();
                var state = source.state().getSeasonSealStateBuilder();
                if (seal.getEffectId() == 3 && state.getPendingRound() == ctx.roundIndex() && state.getPendingAilmentKind() == kind) {
                    accuracy += seal.getBonusBps();
                    state.clearPendingRound().clearPendingAilmentKind();
                    consumed = true;
                }
            }
        }
        if (normal && kind <= 6) for (int i : available(target,8)) {
            int bps = bonusBps(target, i);
            resist += bps;
            use(target,i,target,bps);
            if (target.isPlayer()) ctx.encounter.markAntiControlOrSeasonSkill(target.actorId());
        }
        resist = Math.max(0,resist + 100*toDouble(combat.getResistAllAilment()) - 100*toDouble(source.combat().getIgnoreAilmentResist()));
        double chance = Math.clamp(row.getApplicationChance() + (accuracy-resist)/10000,0.05,kind == POISON ? 0.95 : 0.85);
        if (ctx.rng().rand01() < chance) return true;
        ctx.events().append(BATTLE_EVENT_RESIST,source.actorId(),target.actorId()).setBuffTableId(row.getId());
        if (primary && !consumed) for (int i : available(source,3)) {
            var state = i == SEASON_SEAL_INDEX ? source.state().getSeasonSealStateBuilder() : source.state().getArtifactStatesBuilder(i);
            if (state.getPendingRound() == 0) {
                state.setPendingRound(ctx.roundIndex()+1).setPendingAilmentKind(kind);
                use(source,i,source,kind);
            }
        }
        return false;
    }

    SupportEffectSummary summary(BattleUnit actor) {
        var equipment = actor.state().getEquipmentEffects();
        var summary = SupportEffectRules.begin(equipment).setInBattle(true).setRoundIndex(ctx.roundIndex());
        var healing = summary.getHealingBuilder();
        var shield = summary.getShieldBuilder();
        double healBonus = equipment.getOutgoingHealBps()/10000.0 + ctx.outgoingHealBonus(actor,healing) + battle.modifier(actor,OUT_HEAL,healing);
        double shieldBonus = equipment.getShieldCapacityBps()/10000.0 + battle.modifier(actor,OUT_SHIELD,shield);
        healing.setTotalBonusBps(healBonus*10000);
        shield.setTotalBonusBps(shieldBonus*10000);
        boolean complete = equipment.getSupportSourcesComplete() && !unknownLearnedSkills.contains(actor.actorId())
                && actor.state().getBuffsList().stream().allMatch(e -> battle.row(e) != null);
        var skills = actor.state().getSkillRanksMap().keySet().stream().sorted(Integer::compareUnsigned).toList();
        for (int id : skills) {
            var skill = battle.skill(id);
            if (skill == null) {
                if (!SupportEffectRules.retiredSkill(id)) complete = false;
            } else if (skill.getBattleModel() == 1 && (skill.getEffectList().stream().anyMatch(e -> battle.row(e) == null)
                    || skill.getInitEffectList().stream().anyMatch(e -> battle.row(e) == null))) complete = false;
        }
        healing.setComplete(complete);
        shield.setComplete(complete);
        for (var part : List.of(healing,shield)) {
            var available = available(actor, part == healing ? 4 : 5);
            for (var detail : part.getSourcesBuilderList()) {
                if (detail.getConditional() && available.stream().noneMatch(i -> i >= 0 && equipment.getArtifacts(i).getItemId() == detail.getItemId()))
                    detail.setReason("当前次数/回合或角色状态不满足法宝条件，不计常驻总计");
            }
        }
        for (int id : skills) {
            var skill = battle.skill(id);
            if (skill == null || skill.getBattleModel() != 1) continue;
            for (int buffId : skill.getEffectList()) {
                var effect = battle.row(buffId);
                if (effect == null) continue;
                var preview = SupportEffectRules.preview(skill,effect,actor.level(),actor.state().getSkillRanksOrThrow(id),actor.maxMana(),
                        effect.getBattleOp() == SHIELD ? shieldBonus : healBonus,discount(actor,skill),complete);
                if (preview.isPresent()) { summary.addPreviews(preview.get()); break; }
            }
        }
        return summary.build();
    }
}
