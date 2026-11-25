package com.game.scene.equip;

import com.game.proto.EquipmentArtifactSource;
import com.game.proto.EquipmentEffects;
import com.game.proto.SupportEffectSource;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerBags;
import com.game.table.EquipV1AffixTable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** 只读穿戴实例。同一属性先按全身上限截取，再按实物号依序归属有效贡献。 */
final class FixedEquipmentEffects {
    private FixedEquipmentEffects() {}
    private record Channel(int effect, int parameter) {}
    private record Total(long raw, int cap) {}

    static EquipmentEffects collect(PlayerBags bags, EquipmentTables tables) {
        var result = EquipmentEffects.newBuilder().setSupportSourcesComplete(true);
        Map<Channel,Total> totals = new HashMap<>();
        var items = bags.bag(BagType.EQUIPMENT).items().stream()
                .sorted(Comparator.comparing(BagItem::guid, Long::compareUnsigned)).toList();
        for (var item : items) {
            if (item.size() != 1) continue;
            var template = tables.item(item.configId());
            if (template == null) { result.setSupportSourcesComplete(false); continue; }
            int profile = template.getEquipProfile();
            if (profile < 1 || profile > 3) continue;
            if (!validSelections(item, profile, tables)) { result.setSupportSourcesComplete(false); continue; }
            for (var choice : item.equipment().getFixedAffixesList().stream()
                    .sorted((a,b) -> Integer.compareUnsigned(a.getAffixId(), b.getAffixId())).toList()) {
                var affix = tables.fixedAffix(choice.getAffixId());
                boolean exempt = EquipCraftQualityRules.isPolishExempt(affix.getEffect());
                int tier = EquipCraftQualityRules.effectiveQualityTier(choice.getQualityTier(), exempt);
                int scaledValue = EquipCraftQualityRules.scaleValueByQuality(
                        affix.getValuePermyriad(), tables.qualityRatioPct(tier));
                var channel = new Channel(affix.getEffect(), affix.getEffectParam());
                totals.merge(channel, new Total(scaledValue, affix.getSourceCap()),
                        (a,b) -> new Total(a.raw + b.raw, Math.min(a.cap, b.cap)));
                if (affix.getEffect() == 5 || affix.getEffect() == 6) {
                    result.addSupportSources(SupportEffectSource.newBuilder().setKind(1).setEffectKind(affix.getEffect() - 4)
                            .setItemId(item.guid()).setAffixId(affix.getId()).setRawBonusBps(scaledValue)
                            .setSourceCapBps(affix.getSourceCap()).setName(template.getName() + " · " + affix.getName()));
                }
            }
            if (profile == 3) {
                var artifact = tables.artifact(template.getArtifactId());
                if (!validArtifact(artifact)) {
                    result.setSupportSourcesComplete(false);
                    continue;
                }
                result.addArtifacts(EquipmentArtifactSource.newBuilder().setItemId(item.guid()).setEffectId(artifact.getId())
                        .setName(artifact.getName()).setBonusBps(artifact.getBonusBps()).setCapBps(artifact.getCapBps())
                        .setPerRoundLimit(artifact.getPerRoundLimit()).setBattleLimit(artifact.getBattleLimit())
                        .setActiveRounds(artifact.getActiveRounds()).setHealthThresholdBps(artifact.getHealthThresholdBps()));
            }
        }
        totals.forEach((channel,total) -> {
            int bps = (int) Math.min(total.raw, total.cap);
            switch (channel.effect) {
                case 1 -> result.setPhysicalAttackBps(bps);
                case 2 -> result.setMagicAttackBps(bps);
                case 3 -> result.setMaxHealthBps(bps);
                case 4 -> result.setSpeedBps(bps);
                case 5 -> result.setOutgoingHealBps(bps);
                case 6 -> result.setShieldCapacityBps(bps);
                case 7 -> result.setPhysicalCritBps(bps);
                case 8 -> result.setMagicCritBps(bps);
                case 9 -> result.setPhysicalResistBps(bps);
                case 10 -> result.setMagicResistBps(bps);
                case 11 -> result.putAilmentHitBps(channel.parameter, bps);
                case 12 -> result.putAilmentResistBps(channel.parameter, bps);
                case 13 -> result.putManaReductionBps(channel.parameter, bps);
                default -> throw new IllegalStateException("invalid fixed equipment effect");
            }
        });
        int[] remaining = {0, result.getOutgoingHealBps(), result.getShieldCapacityBps()};
        for (var source : result.getSupportSourcesBuilderList()) {
            int used = Math.min(remaining[source.getEffectKind()], (int) source.getRawBonusBps());
            source.setEffectiveBonusBps(used);
            remaining[source.getEffectKind()] -= used;
            if (used < source.getRawBonusBps()) source.setReason("装备同类来源上限已截取");
        }
        return result.build();
    }

    static boolean validArtifact(com.game.table.EquipArtifactTable artifact) {
        return artifact != null && artifact.getId() >= 1 && artifact.getId() <= 8 && artifact.getBonusBps() > 0
                && artifact.getBonusBps() <= 10000 && artifact.getCapBps() >= 0 && artifact.getCapBps() <= 10000
                && artifact.getHealthThresholdBps() >= 0 && artifact.getHealthThresholdBps() <= 10000
                && Integer.compareUnsigned(artifact.getPerRoundLimit(), 1) <= 0
                && Integer.compareUnsigned(artifact.getBattleLimit(), 3) <= 0
                && Integer.compareUnsigned(artifact.getActiveRounds(), 2) <= 0
                && (artifact.getId() == 6 || artifact.getPerRoundLimit() != 0)
                && ((artifact.getId() != 3 && artifact.getId() < 6) || artifact.getBattleLimit() != 0)
                && (artifact.getId() != 6 || (artifact.getCapBps() != 0 && artifact.getActiveRounds() != 0))
                && (artifact.getId() != 4 || artifact.getHealthThresholdBps() != 0);
    }

    static boolean validSelections(BagItem item, int profile, EquipmentTables tables) {
        if (item.equipment() == null || item.equipment().getFixedAffixesCount() > 2) return false;
        var slots = new HashSet<Integer>();
        var ids = new HashSet<Integer>();
        for (var choice : item.equipment().getFixedAffixesList()) {
            int slot = choice.getSlot();
            var affix = tables.fixedAffix(choice.getAffixId());
            if (slot < 1 || slot > 2 || !slots.add(slot) || !ids.add(choice.getAffixId()) || affix == null
                    || profile == 3 || affix.getPool() != (profile == 1 ? slot : 3) || !validAffix(affix, tables)) return false;
        }
        return true;
    }

    static boolean validAffix(EquipV1AffixTable row, EquipmentTables tables) {
        int effect = row.getEffect(), param = row.getEffectParam();
        int max = switch (effect) {
            case 1, 2, 5, 6 -> 3200;
            case 3, 7, 8 -> 800;
            case 4 -> 200;
            case 9, 10 -> 600;
            case 11, 12 -> 1600;
            case 13 -> 1200;
            default -> 0;
        };
        int pool = switch (effect) {
            case 1, 2, 5, 6, 11 -> 1;
            case 7, 8, 13 -> 2;
            default -> 3;
        };
        var material = tables.item(row.getMaterialId());
        return row.getId() != 0 && row.getValuePermyriad() > 0 && row.getSourceCap() >= row.getValuePermyriad()
                && row.getSourceCap() <= max && pool == row.getPool() && (effect <= 10 ? param == 0 : param >= 1 && param <= (effect == 13 ? 12 : 7))
                && material != null && material.getMaxStackSize() != 0 && material.getEquipKind() == 0 && material.getBattleUsable() == 0
                && row.getMaterialCount() != 0;
    }
}
