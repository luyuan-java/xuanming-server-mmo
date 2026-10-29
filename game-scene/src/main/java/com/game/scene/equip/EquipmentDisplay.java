package com.game.scene.equip;

import com.game.proto.*;
import com.game.scene.player.BagItem;
import java.util.Comparator;
import java.util.HashSet;

/** 配表驱动的只读客户端视图，与装备加成共用有效值计算；不初始化或改写存档。 */
public final class EquipmentDisplay {
    private final EquipmentRules rules;
    public EquipmentDisplay(EquipmentRules rules) { this.rules = rules; }

    private EquipAttrLineInfo.Builder line(EquipmentRules.AttributeValue value) {
        var attr = rules.tables().attribute(value.attributeId());
        return EquipAttrLineInfo.newBuilder().setAttrId(value.attributeId()).setName(attr.getName())
                .setValue(value.value()).setCap(value.cap()).setTier(value.tier()).setSeq(value.sequence()).setPercent(attr.getPercent() != 0);
    }
    public void item(BagItem item, BagItemInfo.Builder info) {
        var row = rules.tables().item(item.configId());
        if (row == null) return;
        info.setName(row.getName()).setDescription(row.getDescription()).setIconKey(row.getIconKey());
        var gem = rules.tables().gem(item.configId());
        if (row.getEquipKind() == 0) {
            if (gem != null) {
                info.setGemType(gem.getGemType());
                if (EquipmentRules.accepted(rules.tables().attribute(gem.getAttrId())))
                    info.setGemAttr(line(new EquipmentRules.AttributeValue(gem.getAttrId(), Integer.toUnsignedLong(gem.getValue()), 0, 0, 0)));
            }
            return;
        }
        int level = rules.level(item);
        info.setEquipLevel(row.getEquipLevel()).setEquipClass(row.getEquipClass())
                .setEnhanceLevel(level).setEnhanceMaxLevel(rules.tables().maxEnhance()).setEnhanceBonusPermyriad((int) rules.bonus(level));
        var next = item.equipment() == null || level >= rules.tables().maxEnhance() ? null : rules.tables().enhance(level + 1);
        if (next != null && next.getSuccessRate() != 0) {
            var preview = EquipEnhancePreview.newBuilder().setNextLevel(level + 1).setSuccessRate(next.getSuccessRate())
                    .setCostGold(next.getCostGold())
                    .setBonusPermyriad(next.getBonusPermyriad()).setFailLevelLoss(next.getFailLevelLoss());
            if (next.getMaterialId() != 0 && next.getMaterialCount() != 0) {
                preview.setMaterialConfigId(next.getMaterialId()).setMaterialCount(next.getMaterialCount());
                var material = rules.tables().item(next.getMaterialId());
                if (material != null) preview.setMaterialName(material.getName()).setMaterialIconKey(material.getIconKey());
            }
            info.setEnhanceNext(preview);
        }
        int sequence = 0;
        for (var base : row.getBaseAttrList()) {
            if (!EquipmentRules.accepted(rules.tables().attribute(base.getBaseAttrId()))) continue;
            long original = Integer.toUnsignedLong(base.getBaseAttrValue());
            long current = EquipmentRules.scale(original, rules.bonus(level));
            var line = line(new EquipmentRules.AttributeValue(base.getBaseAttrId(), current, 0, 0, sequence++))
                    .setEnhanceBonus(current - original);
            if (info.hasEnhanceNext()) line.setEnhanceNextValue(EquipmentRules.scale(original, Integer.toUnsignedLong(next.getBonusPermyriad())));
            info.addBaseAttrs(line);
        }
        rules.values(item).stream().filter(v -> v.tier() != 0)
                .sorted(Comparator.comparingInt(EquipmentRules.AttributeValue::tier)
                        .thenComparing(EquipmentRules.AttributeValue::sequence, Integer::compareUnsigned))
                .forEach(v -> info.addAffixes(line(v)));
        if (item.equipment() == null) return;
        var values = rules.gemValues(item); var seen = new HashSet<Integer>();
        for (var socket : rules.tables().sockets(row.getEquipKind())) {
            if (!seen.add(socket.getSocket())) continue;
            var display = EquipGemSocketInfo.newBuilder().setSocket(socket.getSocket()).setGemType(socket.getGemType()).setName(socket.getName());
            item.equipment().getGemsList().stream().filter(g -> g.getSocket() == socket.getSocket() && g.getGemConfigId() != 0).findFirst().ifPresent(g -> {
                display.setGemConfigId(g.getGemConfigId());
                var gemItem = rules.tables().item(g.getGemConfigId());
                if (gemItem != null) display.setGemName(gemItem.getName()).setGemIconKey(gemItem.getIconKey());
                var value = values.get(socket.getSocket());
                if (value != null) display.setAttr(line(value));
            });
            info.addGemSockets(display);
        }
    }
    public void slots(BagLayoutInfo.Builder layout) {
        var seen = new HashSet<Integer>();
        rules.tables().slots().stream().sorted(Comparator.comparing(com.game.table.EquipSlotTable::getId, Integer::compareUnsigned))
                .filter(slot -> Integer.compareUnsigned(slot.getId(), layout.getCapacity()) < 0 && seen.add(slot.getId()))
                .filter(slot -> !slot.getName().isEmpty() || layout.getSlotsList().stream().anyMatch(placed -> placed.getSlot() == slot.getId()))
                .forEach(slot -> layout.addEquipSlots(EquipSlotInfo.newBuilder().setSlot(slot.getId()).setEquipKind(slot.getEquipKind()).setName(slot.getName())));
    }
}
