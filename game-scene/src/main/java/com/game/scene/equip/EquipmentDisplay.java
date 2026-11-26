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
        int profile = row.getEquipProfile();
        int maxEnhance = rules.tables().maxEnhance(profile);
        int level = rules.level(item);
        long currentBonus = rules.bonus(profile, level);
        int currentPity = item.equipment() != null ? item.equipment().getEnhancePityCount() : 0;
        info.setEquipLevel(row.getEquipLevel()).setEquipClass(row.getEquipClass())
                .setEnhanceLevel(level).setEnhanceMaxLevel(maxEnhance).setEnhanceBonusPermyriad((int) currentBonus);
        if (currentPity > 0) {
            info.setEnhancePityCount(currentPity);
        }
        if (item.equipment() != null) {
            if (!item.equipment().getCrafterName().isEmpty()) {
                info.setCrafterName(item.equipment().getCrafterName());
            }
            if (item.equipment().getCrafterPlayerId() != 0) {
                info.setCrafterPlayerId(item.equipment().getCrafterPlayerId());
            }
        }
        var next = item.equipment() == null || level >= maxEnhance ? null : rules.tables().enhance(profile, level + 1);
        if (next != null && rules.isEnhanceRowAvailable(next)) {
            var preview = EquipEnhancePreview.newBuilder().setNextLevel(level + 1).setSuccessRate(next.getSuccessRate())
                    .setCostGold(next.getCostGold())
                    .setBonusPermyriad(next.getBonusPermyriad()).setFailLevelLoss(next.getFailLevelLoss());
            if (next.getPityLimit() > 0) {
                preview.setPityLimit(next.getPityLimit())
                        .setCurrentPityCount(currentPity)
                        .setPityGuaranteedNext(Integer.compareUnsigned(currentPity + 1, next.getPityLimit()) >= 0);
            }
            if (next.getMaterialId() != 0 && next.getMaterialCount() != 0) {
                preview.setMaterialConfigId(next.getMaterialId()).setMaterialCount(next.getMaterialCount());
                var material = rules.tables().item(next.getMaterialId());
                if (material != null) preview.setMaterialName(material.getName()).setMaterialIconKey(material.getIconKey());
            }
            info.setEnhanceNext(preview);
        }
        int sequence = 0;
        for (var base : row.getBaseAttrList()) {
            var attr = rules.tables().attribute(base.getBaseAttrId());
            if (!EquipmentRules.accepted(attr)) continue;
            long original = Integer.toUnsignedLong(base.getBaseAttrValue());
            boolean enhances = EquipmentRules.enhancesBaseAttribute(profile, attr);
            long current = enhances ? EquipmentRules.scale(original, currentBonus) : original;
            var line = line(new EquipmentRules.AttributeValue(base.getBaseAttrId(), current, 0, 0, sequence++))
                    .setEnhanceBonus(current - original);
            if (info.hasEnhanceNext()) {
                line.setEnhanceNextValue(enhances
                        ? EquipmentRules.scale(original, Integer.toUnsignedLong(next.getBonusPermyriad()))
                        : original);
            }
            info.addBaseAttrs(line);
        }
        rules.values(item).stream().filter(v -> v.tier() != 0)
                .sorted(Comparator.comparingInt(EquipmentRules.AttributeValue::tier)
                        .thenComparing(EquipmentRules.AttributeValue::sequence, Integer::compareUnsigned))
                .forEach(v -> info.addAffixes(line(v)));
        if (item.equipment() == null) return;
        fillFixedDisplay(item, row, info);
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

    /** 填充装备 V1 固定词条槽与法宝展示信息（GetBag / 打造规则 / 打造预览共用同一出处）。 */
    public void fillFixedDisplay(BagItem item, com.game.table.ItemTable row, BagItemInfo.Builder info) {
        int profile = row.getEquipProfile();
        info.setEquipProfile(profile);
        info.clearFixedAffixSlots().clearArtifact();
        if (profile == 1 || profile == 2) {
            boolean valid = item != null && item.equipment() != null && FixedEquipmentEffects.validSelections(item, profile, rules.tables());
            long itemEnhanceBonus = (valid && profile == 1) ? rules.v1ItemEnhanceBonusPermyriad(item) : 0L;
            for (int slot = 1; slot <= 2; slot++) {
                EquipFixedAffixSlotInfo.Builder shown = EquipFixedAffixSlotInfo.newBuilder()
                        .setSlot(slot)
                        .setPool(profile == 1 ? slot : 3);
                if (valid) {
                    for (var selected : item.equipment().getFixedAffixesList()) {
                        if (selected.getSlot() == slot) {
                            var affix = rules.tables().fixedAffix(selected.getAffixId());
                            if (affix != null) {
                                shown.setSelected(affixInstanceInfo(affix, selected, profile, itemEnhanceBonus, rules.tables()));
                            }
                        }
                    }
                }
                info.addFixedAffixSlots(shown);
            }
        } else if (profile == 3) {
            var artifact = rules.tables().artifact(row.getArtifactId());
            if (artifact != null && FixedEquipmentEffects.validArtifact(artifact)) {
                info.setArtifact(artifactInfo(artifact));
            }
        }
    }

    public static EquipFixedAffixInfo affixInfo(com.game.table.EquipV1AffixTable row) {
        boolean exempt = EquipCraftQualityRules.isPolishExempt(row.getEffect());
        return EquipFixedAffixInfo.newBuilder()
                .setAffixId(row.getId())
                .setPool(row.getPool())
                .setName(row.getName())
                .setDescription(row.getDescription())
                .setValueBps(row.getValuePermyriad())
                .setSourceCapBps(row.getSourceCap())
                .setEffect(row.getEffect())
                .setEffectParam(row.getEffectParam())
                .setUnit(row.getEffect() >= 7 && row.getEffect() <= 12 ? 2 : 1)
                .setMaterialConfigId(row.getMaterialId())
                .setMaterialCount(row.getMaterialCount())
                .setQualityTier(0)
                .setQualityRatioPct(100)
                .setMaxValueBps(row.getValuePermyriad())
                .setPolishPityCount(0)
                .setPolishPityLimit(exempt ? 0 : EquipCraftQualityRules.POLISH_PITY_LIMIT)
                .setPolishExempt(exempt)
                .setPolishMaterialConfigId(EquipCraftQualityRules.POLISH_SAND_CONFIG_ID)
                .setPolishMaterialCount(EquipCraftQualityRules.POLISH_SAND_COUNT)
                .setPolishGoldCost(EquipCraftQualityRules.POLISH_GOLD_COST)
                .setReforgeMaterialConfigId(EquipCraftQualityRules.REFORGE_SAND_CONFIG_ID)
                .setReforgeMaterialCount(EquipCraftQualityRules.REFORGE_SAND_COUNT)
                .setReforgeGoldCost(EquipCraftQualityRules.REFORGE_GOLD_COST)
                .build();
    }

    public static EquipFixedAffixInfo affixInstanceInfo(com.game.table.EquipV1AffixTable row,
            com.game.player.store.state.EquipmentFixedAffixState selected, EquipmentTables tables) {
        return affixInstanceInfo(row, selected, 0, 0L, tables);
    }

    public static EquipFixedAffixInfo affixInstanceInfo(com.game.table.EquipV1AffixTable row,
            com.game.player.store.state.EquipmentFixedAffixState selected,
            int equipProfile, long enhanceBonusPermyriad, EquipmentTables tables) {
        boolean exempt = EquipCraftQualityRules.isPolishExempt(row.getEffect());
        int tier = EquipCraftQualityRules.effectiveQualityTier(selected.getQualityTier(), exempt);
        int ratioPct = tables.qualityRatioPct(tier);
        int qualityScaled = EquipCraftQualityRules.scaleValueByQuality(row.getValuePermyriad(), ratioPct);
        int effectiveValue = EquipmentRules.scaleV1FixedAffixByEnhance(
                equipProfile, row.getEffect(), qualityScaled, enhanceBonusPermyriad);
        var builder = affixInfo(row).toBuilder()
                .setQualityTier(tier)
                .setQualityName(tables.qualityName(tier))
                .setQualityRatioPct(ratioPct)
                .setValueBps(effectiveValue)
                .setPolishPityCount((exempt || tier >= EquipCraftQualityRules.MAX_QUALITY_TIER)
                        ? 0 : selected.getPolishPityCount());
        if (enhanceBonusPermyriad > 0L
                && EquipmentRules.v1FixedAffixEnhanceSharePermyriad(equipProfile, row.getEffect()) > 0) {
            builder.setSourceCapBps(EquipmentRules.scaleV1FixedAffixByEnhance(
                    equipProfile, row.getEffect(), row.getSourceCap(), enhanceBonusPermyriad));
        }
        return builder.build();
    }

    public static EquipArtifactInfo artifactInfo(com.game.table.EquipArtifactTable row) {
        return EquipArtifactInfo.newBuilder()
                .setEffectId(row.getId())
                .setName(row.getName())
                .setDescription(row.getDescription())
                .setBonusBps(row.getBonusBps())
                .setCapBps(row.getCapBps())
                .setPerRoundLimit(row.getPerRoundLimit())
                .setBattleLimit(row.getBattleLimit())
                .setActiveRounds(row.getActiveRounds())
                .setHealthThresholdBps(row.getHealthThresholdBps())
                .build();
    }
    public void slots(BagLayoutInfo.Builder layout) {
        var seen = new HashSet<Integer>();
        rules.tables().slots().stream().sorted(Comparator.comparing(com.game.table.EquipSlotTable::getId, Integer::compareUnsigned))
                .filter(slot -> Integer.compareUnsigned(slot.getId(), layout.getCapacity()) < 0 && seen.add(slot.getId()))
                .filter(slot -> !slot.getName().isEmpty() || layout.getSlotsList().stream().anyMatch(placed -> placed.getSlot() == slot.getId()))
                .forEach(slot -> layout.addEquipSlots(EquipSlotInfo.newBuilder().setSlot(slot.getId()).setEquipKind(slot.getEquipKind()).setName(slot.getName())));
    }
}
