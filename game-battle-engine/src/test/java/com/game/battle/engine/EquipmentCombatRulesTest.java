package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.CombatAttributes;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class EquipmentCombatRulesTest {
    @Test
    void 各伤害类型只叠相应加成_未知类型只保留基础值() {
        var combat = CombatAttributes.newBuilder().setPhysicalCritRate(11).setMagicCritRate(22)
                .setPhysicalResist(33).setMagicResist(44).build();
        assertThat(EquipmentCombatRules.criticalRate(5, combat, 1)).isEqualTo(16);
        assertThat(EquipmentCombatRules.criticalRate(5, combat, 0)).isEqualTo(27);
        assertThat(EquipmentCombatRules.criticalRate(5, combat, 99)).isEqualTo(5);
        assertThat(EquipmentCombatRules.resistance(5, combat, 1)).isEqualTo(38);
        assertThat(EquipmentCombatRules.resistance(5, combat, 0)).isEqualTo(49);
        assertThat(EquipmentCombatRules.resistance(5, combat, 99)).isEqualTo(5);
    }

    @Test
    void uint64加法饱和_技能等级不以int截断_概率高半区按满率处理() {
        assertThat(EquipmentCombatRules.addSaturated(-2L, 5)).isEqualTo(-1L);
        assertThat(EquipmentCombatRules.addSaturated(Long.MAX_VALUE, 1)).isEqualTo(Long.MIN_VALUE);
        assertThat(EquipmentCombatRules.skillLevel(-1, CombatAttributes.newBuilder().setSkillLevelBonus(7).build()))
                .isEqualTo(4_294_967_302.0);
        assertThat(EquipmentCombatRules.skillLevel(10, CombatAttributes.newBuilder().setSkillLevelBonus(-1L).build()))
                .isEqualTo(0x1p64);
        BattleRandom random = new BattleRandom(42);
        assertThat(random.rollPercent(0)).isFalse();
        assertThat(random.rollPercent(100)).isTrue();
        assertThat(random.rollPercent(-1L)).isTrue();
        assertThat(random.draws()).isZero();
        random.rollPercent(50);
        assertThat(random.draws()).isEqualTo(1);
    }

    @Test
    void 反震比例用精确整数_含奇数与UINT64_MAX不会溢出() {
        for (long damage : new long[] {0, 1, 3, 31, 100, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            BigInteger unsigned = new BigInteger(Long.toUnsignedString(damage));
            for (int percent : new int[] {0, 50, 100}) {
                long expected = unsigned.multiply(BigInteger.valueOf(percent)).add(BigInteger.valueOf(99))
                        .divide(BigInteger.valueOf(100)).longValue();
                assertThat(EquipmentCombatRules.percentCeil(damage, percent)).isEqualTo(expected);
            }
        }
        assertThatThrownBy(() -> EquipmentCombatRules.percentCeil(1, 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 抗异常四类型准确映射_先饱和相加再减忽视_不提前夹到100() {
        var defense = CombatAttributes.newBuilder().setResistPoison(10).setResistFreeze(20).setResistSleep(30)
                .setResistForget(40).setResistConfusion(99).setResistAllAilment(80).build();
        var attack = CombatAttributes.newBuilder().setIgnoreAilmentResist(100).build();
        assertThat(EquipmentCombatRules.ailmentResistance(defense, attack, 50)).hasValue(0);
        assertThat(EquipmentCombatRules.ailmentResistance(defense, attack, 52)).hasValue(0);
        assertThat(EquipmentCombatRules.ailmentResistance(defense, attack, 30)).hasValue(10);
        assertThat(EquipmentCombatRules.ailmentResistance(defense, attack, 31)).hasValue(20);
        assertThat(EquipmentCombatRules.ailmentResistance(defense, attack, 51)).isEmpty();
        assertThat(EquipmentCombatRules.ailmentResistance(defense, attack, 99)).isEmpty();
        var huge = defense.toBuilder().setResistPoison(-1L).setResistAllAilment(100).build();
        assertThat(EquipmentCombatRules.ailmentResistance(huge, attack, 50)).hasValue(-101L);
        assertThat(EquipmentCombatRules.ailmentResistance(huge, attack.toBuilder().setIgnoreAilmentResist(-1L).build(), 50)).hasValue(0);
    }

    @Test
    void 装备抗性仍与护甲防御共用60封顶() {
        long resist = EquipmentCombatRules.resistance(20, CombatAttributes.newBuilder().setPhysicalResist(-1L).build(), 1);
        assertThat(CombatDamageRules.damageBeforeCritical(100, 0, 0, 1, -1L, -1L, resist, 10)).isEqualTo(40);
    }
}
