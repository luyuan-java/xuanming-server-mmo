package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.CombatAttributes;
import java.util.OptionalLong;

/** 装备战斗属性的纯规则；uint64 位模式不窄化，来源只保存加成，基础暴击 / 抗性另行相加。 */
final class EquipmentCombatRules {
    private EquipmentCombatRules() { }

    static long addSaturated(long base, long bonus) {
        long sum = base + bonus;
        return Long.compareUnsigned(sum, base) < 0 ? -1L : sum;
    }

    static long criticalRate(long base, CombatAttributes combat, int damageType) {
        long bonus = switch (damageType) {
            case CombatDamageRules.PHYSICAL_DAMAGE -> combat.getPhysicalCritRate();
            case CombatDamageRules.MAGIC_DAMAGE -> combat.getMagicCritRate();
            default -> 0;
        };
        return addSaturated(base, bonus);
    }

    static long resistance(long base, CombatAttributes combat, int damageType) {
        long bonus = switch (damageType) {
            case CombatDamageRules.PHYSICAL_DAMAGE -> combat.getPhysicalResist();
            case CombatDamageRules.MAGIC_DAMAGE -> combat.getMagicResist();
            default -> 0;
        };
        return addSaturated(base, bonus);
    }

    static double skillLevel(int level, CombatAttributes combat) {
        return Unsigned.toDouble(addSaturated(Integer.toUnsignedLong(level), combat.getSkillLevelBonus()));
    }

    /** 无混乱类型；灼烧也不属于异常抗性映射。先做 uint64 饱和和减法，再由掷骰钳到 100。 */
    static OptionalLong ailmentResistance(CombatAttributes defender, CombatAttributes caster, int buffType) {
        long specific;
        switch (buffType) {
            case BattleConstants.BUFF_TYPE_POISON -> specific = defender.getResistPoison();
            case BattleConstants.BUFF_TYPE_FREEZE -> specific = defender.getResistFreeze();
            case BattleConstants.BUFF_TYPE_STUN -> specific = defender.getResistSleep();
            case BattleConstants.BUFF_TYPE_SILENCE -> specific = defender.getResistForget();
            default -> { return OptionalLong.empty(); }
        }
        long total = addSaturated(specific, defender.getResistAllAilment());
        long ignored = caster.getIgnoreAilmentResist();
        return OptionalLong.of(Long.compareUnsigned(total, ignored) > 0 ? total - ignored : 0);
    }

    /** ceil(value × percent / 100)，先拆商余数避免 uint64 乘法溢出；仅用于 0..100%。 */
    static long percentCeil(long value, int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("百分比必须在 0..100");
        return Long.divideUnsigned(value, 100) * percent
                + (Long.remainderUnsigned(value, 100) * percent + 99) / 100;
    }
}
