package com.game.battle.engine;

import static com.game.battle.engine.EquipmentCombatRules.addSaturated;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.BattleAction;
import com.game.proto.eBattleActionType;
import com.game.table.SkillTable;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 职业玩家的挂机选技。只读战斗状态；不消费 RNG，不提前扣费或锁定治疗目标。 */
final class AutoClassSkillSelector {
    private enum Purpose { CRITICAL_HEAL, DISPEL, HEAL, RESTORE_MANA, STAT_BUFF, DAMAGE }

    /** gain 只在相同用途内比较，整数均按 protobuf 无符号位模式解释。 */
    private record Candidate(Purpose purpose, long gain, long manaCost, int cooldown, int skillId, long targetId) {
        boolean betterThan(Candidate other) {
            if (purpose != other.purpose) return purpose.ordinal() < other.purpose.ordinal();
            if (gain != other.gain) return Long.compareUnsigned(gain, other.gain) > 0;
            if (manaCost != other.manaCost) return Long.compareUnsigned(manaCost, other.manaCost) < 0;
            if (cooldown != other.cooldown) return Integer.compareUnsigned(cooldown, other.cooldown) < 0;
            if (skillId != other.skillId) return Integer.compareUnsigned(skillId, other.skillId) < 0;
            return Long.compareUnsigned(targetId, other.targetId) < 0;
        }

        BattleAction action() {
            return BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                    .setSkillTableId(skillId).setTargetId(targetId).build();
        }
    }

    private final BattleContext ctx;
    private final ActionChecks checks;
    private final ClassSkillExecution execution;
    private final BuffEngine buffs;
    private final ClassSkillCombat combat;

    AutoClassSkillSelector(BattleContext ctx, ActionChecks checks, ClassSkillExecution execution, BuffEngine buffs) {
        this.ctx = Objects.requireNonNull(ctx);
        this.checks = Objects.requireNonNull(checks);
        this.execution = Objects.requireNonNull(execution);
        this.buffs = Objects.requireNonNull(buffs);
        this.combat = new ClassSkillCombat(ctx.data());
    }

    /** 调用方仅对没有手动待执行动作的 is_auto 玩家调用；空值表示沿用默认普攻。 */
    Optional<BattleAction> select(BattleUnit actor) {
        double basicDamage = basicAttackDamage(actor);
        Candidate best = null;
        for (int skillId : actor.state().getSkillTableIdsList()) {
            var row = ctx.data().skill(skillId);
            if (row.isEmpty() || !BattleRules.isClassSkill(row.get())) continue;
            SkillTable skill = row.get();
            BattleAction probe = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                    .setSkillTableId(skillId).build();
            if (checks.checkActionPrerequisites(actor, probe) != ActionChecks.SUCCESS) continue;
            int rank = ClassSkillExecution.rank(actor, skill);
            boolean hostile = skill.getTargetTeam() == ClassSkillExecution.ENEMY;
            if (!hostile) {
                List<BattleUnit> targets = execution.selectTargets(actor, skill, 0);
                if (!targets.isEmpty()) {
                    double ratio = ClassSkillCombat.healthRatio(targets.getFirst());
                    if (ratio < .30) {
                        best = consider(best, actor, skill, Purpose.CRITICAL_HEAL, healGain(actor, skill, rank, targets), 0);
                    } else if (ratio <= .70) {
                        best = consider(best, actor, skill, Purpose.HEAL, healGain(actor, skill, rank, targets), 0);
                    }
                }
            }
            double baseDamage = hostile ? ctx.data().skillDamage(skillId,
                    EquipmentCombatRules.skillLevel(actor.level(), actor.combat())) : 0;
            for (BattleUnit primary : primaryTargets(actor, skill)) {
                List<BattleUnit> targets = execution.selectTargets(actor, skill, primary.actorId());
                if (hostile) {
                    long damage = damageGain(actor, skill, rank, baseDamage, targets);
                    if (Unsigned.toDouble(damage) > basicDamage) {
                        best = consider(best, actor, skill, Purpose.DAMAGE, damage, primary.actorId());
                    }
                } else {
                    best = consider(best, actor, skill, Purpose.DISPEL, dispelGain(skill, targets), primary.actorId());
                    best = consider(best, actor, skill, Purpose.RESTORE_MANA,
                            manaGain(actor, skill, rank, targets), primary.actorId());
                }
                best = consider(best, actor, skill, Purpose.STAT_BUFF,
                        statGain(skill, rank, targets), primary.actorId());
            }
        }
        return best == null ? Optional.empty() : Optional.of(best.action());
    }

    private static Candidate consider(Candidate best, BattleUnit actor, SkillTable skill, Purpose purpose,
                                      long gain, long targetId) {
        if (gain == 0) return best;
        var candidate = new Candidate(purpose, gain, BattleRules.skillManaCost(skill, actor.maxMana()),
                skill.getCooldownRounds(), skill.getId(), targetId);
        return best == null || candidate.betterThan(best) ? candidate : best;
    }

    private List<BattleUnit> primaryTargets(BattleUnit actor, SkillTable skill) {
        if (skill.getTargetTeam() == ClassSkillExecution.SELF) return List.of(actor);
        boolean ally = skill.getTargetTeam() == ClassSkillExecution.ALLY;
        return ctx.units().stream().filter(unit -> unit.isActive() && (unit.teamIndex() == actor.teamIndex()) == ally).toList();
    }

    private long healGain(BattleUnit actor, SkillTable skill, int rank, List<BattleUnit> targets) {
        long total = 0;
        for (BattleUnit target : targets) {
            total = addSaturated(total, target.landedHeal(ctx.classSkillHealAmount(actor, skill, rank, target)));
        }
        return total;
    }

    private long dispelGain(SkillTable skill, List<BattleUnit> targets) {
        long total = 0;
        for (BattleUnit target : targets) {
            long count = target.state().getBuffsList().stream().filter(buffs::isDebuffEntry).count();
            long removed = Math.min(Integer.toUnsignedLong(skill.getDispelCount()), count);
            // 主目标必须确实可驱散，防止因为次目标有减益而把动作指向无收益的第一人。
            if (removed == 0 && target == targets.getFirst()) return 0;
            total += removed;
        }
        return total;
    }

    private long manaGain(BattleUnit actor, SkillTable skill, int rank, List<BattleUnit> targets) {
        long cost = BattleRules.skillManaCost(skill, actor.maxMana());
        long total = 0;
        boolean lowMana = false;
        for (BattleUnit target : targets) {
            double ratio = target.maxMana() == 0 ? 1 : Unsigned.toDouble(target.mana()) / Unsigned.toDouble(target.maxMana());
            lowMana |= ratio <= .30;
            long before = target.mana();
            if (target == actor) before = Long.compareUnsigned(before, cost) > 0 ? before - cost : 0;
            long missing = Long.compareUnsigned(target.maxMana(), before) > 0 ? target.maxMana() - before : 0;
            long restored = ClassSkillRules.ceilRatio(target.maxMana(), skill.getRestoreManaPercent()
                    * ClassSkillRules.supportRankFactor(rank));
            total = addSaturated(total, Unsigned.minUnsigned(missing, restored));
        }
        return lowMana && Long.compareUnsigned(total, cost) > 0 ? total - cost : 0;
    }

    private long statGain(SkillTable skill, int rank, List<BattleUnit> targets) {
        double percent = skill.getBuffPercent() * ClassSkillRules.supportRankFactor(rank);
        boolean hostile = skill.getTargetTeam() == ClassSkillExecution.ENEMY;
        if (hostile ? percent >= 0 : percent <= 0) return 0;
        long total = 0;
        for (BattleUnit target : targets) {
            for (int effect : skill.getEffectList()) {
                var row = ctx.data().buff(effect);
                if (row.isEmpty() || buffs.isImmuneToBuff(target, row.get())) continue;
                ClassSkillCombat.Stat stat = switch (row.get().getBuffType()) {
                    case 10 -> ClassSkillCombat.Stat.PHYSICAL_ATTACK;
                    case 11 -> ClassSkillCombat.Stat.DEFENSE;
                    case 13 -> ClassSkillCombat.Stat.SPEED;
                    default -> null;
                };
                if (stat == null) continue;
                long base = switch (stat) {
                    case PHYSICAL_ATTACK -> target.physicalAttack();
                    case DEFENSE -> target.defense();
                    case SPEED -> target.speed();
                };
                long before = ClassSkillRules.floorRatio(base, combat.statMultiplier(target, stat, 0));
                long after = ClassSkillRules.floorRatio(base, combat.statMultiplier(target, stat, percent));
                if (stat == ClassSkillCombat.Stat.DEFENSE && percent > 0 && ClassSkillCombat.healthRatio(target) > .60) continue;
                if (stat == ClassSkillCombat.Stat.SPEED && !speedFlipsOrder(target, before, after)) continue;
                total = addSaturated(total, Long.compareUnsigned(before, after) > 0 ? before - after : after - before);
            }
        }
        return total;
    }

    private boolean speedFlipsOrder(BattleUnit target, long before, long after) {
        for (BattleUnit other : ctx.units()) {
            if (!other.isActive() || other.teamIndex() == target.teamIndex()) continue;
            long speed = ctx.effectiveSpeed(other);
            if (actsFirst(target, before, other, speed) != actsFirst(target, after, other, speed)) return true;
        }
        return false;
    }

    private static boolean actsFirst(BattleUnit actor, long speed, BattleUnit other, long otherSpeed) {
        return speed != otherSpeed ? Long.compareUnsigned(speed, otherSpeed) > 0
                : Long.compareUnsigned(actor.actorId(), other.actorId()) < 0;
    }

    private long damageGain(BattleUnit actor, SkillTable skill, int rank, double baseDamage, List<BattleUnit> targets) {
        long attack = CombatDamageRules.selectAttack(skill.getDamageType(), ctx.effectivePhysicalAttack(actor), actor.magicAttack());
        long total = 0;
        for (BattleUnit target : targets) {
            double expected = ctx.expectedDamage(actor, target, baseDamage, attack, skill.getAttackMultiplier(), skill.getDamageType())
                    * ClassSkillRules.damageRankFactor(rank);
            total = addSaturated(total, landedDamage(target, expected));
        }
        return total;
    }

    private double basicAttackDamage(BattleUnit actor) {
        List<BattleUnit> enemies = ctx.units().stream()
                .filter(unit -> unit.isActive() && unit.teamIndex() != actor.teamIndex()).toList();
        if (enemies.isEmpty()) return 0;
        double comboChance = Math.min(Unsigned.toDouble(actor.combat().getComboRate()) / 100, 1);
        double comboFactor = 1;
        double chainChance = 1;
        for (int hit = 1; hit <= BattleConstants.MAX_COMBO_EXTRA_HITS; hit++) {
            chainChance *= comboChance;
            comboFactor += chainChance * BattleConstants.COMBO_DAMAGE_PERCENT / 100.0;
        }
        long total = 0;
        for (BattleUnit enemy : enemies) {
            double expected = ctx.expectedDamage(actor, enemy, BattleConstants.BASIC_ATTACK_BASE_DAMAGE,
                    ctx.effectivePhysicalAttack(actor), 1, CombatDamageRules.PHYSICAL_DAMAGE) * comboFactor;
            total = addSaturated(total, landedDamage(enemy, expected));
        }
        return Unsigned.toDouble(total) / enemies.size();
    }

    private static long landedDamage(BattleUnit target, double damage) {
        return CombatDamageRules.damageToHealth(target.isDefending() ? damage * .5 : damage, target.health());
    }

}
