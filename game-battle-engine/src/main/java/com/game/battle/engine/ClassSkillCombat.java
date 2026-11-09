package com.game.battle.engine;

import com.game.common.math.Unsigned;
import com.game.table.SkillTable;
import java.util.Objects;
import java.util.TreeMap;

/** 职业技能的局内属性与治疗计算；只读取状态，不改属性快照、不消耗随机数。 */
final class ClassSkillCombat {
    enum Stat { PHYSICAL_ATTACK, DEFENSE, SPEED }

    private final BattleData data;

    ClassSkillCombat(BattleData data) {
        this.data = Objects.requireNonNull(data);
    }

    /** 同属性正负方向各取最强一项；层数不重复乘幅度，最终倍率至少为 0.1。 */
    double statMultiplier(BattleUnit actor, Stat stat, double extraPercent) {
        // 比较式保留固定源 std::min/max 的 NaN 规则，不让一条异常幅度污染其他条目。
        double gain = 0.0 < extraPercent ? extraPercent : 0.0;
        double loss = extraPercent < 0.0 ? extraPercent : 0.0;
        boolean found = extraPercent != 0.0;
        for (var entry : actor.state().getBuffsList()) {
            double percent = entry.getStatPercent();
            if (percent == 0.0) continue;
            var row = data.buff(entry.getBuffTableId());
            if (row.isEmpty() || !matches(row.get().getBuffType(), stat)) continue;
            found = true;
            if (gain < percent) gain = percent;
            if (percent < loss) loss = percent;
        }
        if (!found) return 1.0;
        double combined = 1.0 + gain + loss;
        return 0.1 < combined ? combined : 0.1;
    }

    long effectivePhysicalAttack(BattleUnit actor) {
        return effective(actor.physicalAttack(), statMultiplier(actor, Stat.PHYSICAL_ATTACK, 0.0));
    }

    long effectiveDefense(BattleUnit actor) {
        return effective(actor.defense(), statMultiplier(actor, Stat.DEFENSE, 0.0));
    }

    long effectiveSpeed(BattleUnit actor) {
        return effective(actor.speed(), statMultiplier(actor, Stat.SPEED, 0.0));
    }

    private static long effective(long base, double multiplier) {
        // 无职业增减益时直接返回 uint64 原位，不能先换 double 丢掉高位整数精度。
        return multiplier == 1.0 ? base : ClassSkillRules.floorRatio(base, multiplier);
    }

    private static boolean matches(int buffType, Stat stat) {
        return switch (stat) {
            case PHYSICAL_ATTACK -> buffType == 10;
            case DEFENSE -> buffType == 11;
            case SPEED -> buffType == 13;
        };
    }

    /** 技能 map 的迭代顺序不是求和顺序；固定按 uint32 表 id 升序累加。 */
    double outgoingHealBonus(BattleUnit actor) {
        var ranks = new TreeMap<Integer, Integer>(Integer::compareUnsigned);
        ranks.putAll(actor.state().getSkillRanksMap());
        double bonus = 0.0;
        for (var entry : ranks.entrySet()) {
            var row = data.skill(entry.getKey());
            if (row.isEmpty() || ClassSkillRules.parsePassiveStat(row.get().getPassiveStat())
                    != ClassSkillRules.PassiveStat.OUTGOING_HEAL) continue;
            bonus += row.get().getPassivePercent() * ClassSkillRules.passiveRankFactor(entry.getValue());
        }
        return bonus;
    }

    /** 原始治疗量；是否存活、缺口封顶及落血取整仍由单位的治疗原语负责。 */
    double healAmount(BattleUnit caster, SkillTable skill, int rank, BattleUnit target) {
        double amount = (Unsigned.toDouble(caster.magicAttack()) * skill.getHealMultiplier()
                + Integer.toUnsignedLong(caster.level()) * skill.getHealPerLevel())
                * ClassSkillRules.damageRankFactor(rank) * (1.0 + outgoingHealBonus(caster));
        if (skill.getExecuteHealThreshold() > 0 && healthRatio(target) < skill.getExecuteHealThreshold()) {
            amount *= 1.0 + skill.getExecuteHealBonus();
        }
        return amount;
    }

    /** 气血上限为零按满血；不夹超上限快照，保留固定源的目标排序/阈值口径。 */
    static double healthRatio(BattleUnit actor) {
        return actor.maxHealth() == 0 ? 1.0 : Unsigned.toDouble(actor.health()) / Unsigned.toDouble(actor.maxHealth());
    }
}
