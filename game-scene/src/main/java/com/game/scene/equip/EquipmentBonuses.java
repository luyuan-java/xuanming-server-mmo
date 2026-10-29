package com.game.scene.equip;

import com.game.proto.CombatAttributes;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerAttributes.Derived;
import com.game.scene.player.PlayerBags;
import java.util.HashMap;
import java.util.Map;

/** 一次属性重算得到的不可变装备加成；不落库，不与基础暴击/抗性混合。 */
public record EquipmentBonuses(Map<Integer, Long> primary, long allPrimary, Derived flat, CombatAttributes combat) {
    public static final EquipmentBonuses NONE = new EquipmentBonuses(Map.of(), 0, Derived.ZERO, CombatAttributes.getDefaultInstance());
    public EquipmentBonuses { primary = Map.copyOf(primary); }
    public long primary(int dimension) { return EquipmentRules.add(primary.getOrDefault(dimension, 0L), allPrimary); }

    public static EquipmentBonuses collect(PlayerBags bags, EquipmentRules rules) {
        if (rules.tables().attributes().isEmpty()) return NONE;
        Map<Integer, Long> primary = new HashMap<>(); long all = 0;
        long[] derived = new long[7], combat = new long[16];
        for (var item : bags.bag(BagType.EQUIPMENT).items()) for (var value : rules.values(item)) {
            var attribute = rules.tables().attribute(value.attributeId());
            int parameter = attribute.getEffectParam();
            switch (attribute.getEffect()) {
                case 1 -> primary.merge(parameter, value.value(), EquipmentRules::add);
                case 2 -> all = EquipmentRules.add(all, value.value());
                case 3 -> derived[parameter] = EquipmentRules.add(derived[parameter], value.value());
                case 4 -> {
                    derived[3] = EquipmentRules.add(derived[3], value.value());
                    derived[4] = EquipmentRules.add(derived[4], value.value());
                }
                case 5 -> combat[parameter] = EquipmentRules.add(combat[parameter], value.value());
                default -> throw new IllegalStateException("未校验的装备属性效果");
            }
        }
        return new EquipmentBonuses(primary, all, new Derived(derived[1], derived[2], derived[3], derived[4], derived[5], derived[6]),
                CombatAttributes.newBuilder().setPhysicalCritRate(combat[1]).setMagicCritRate(combat[2]).setComboRate(combat[3])
                        .setCounterRate(combat[4]).setReflectRate(combat[5]).setSkillLevelBonus(combat[6]).setIgnoreAilmentResist(combat[7])
                        .setResistPoison(combat[8]).setResistFreeze(combat[9]).setResistSleep(combat[10]).setResistForget(combat[11])
                        .setResistConfusion(combat[12]).setResistAllAilment(combat[13]).setMagicResist(combat[14]).setPhysicalResist(combat[15]).build());
    }
}
