package com.game.scene.equip;

import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagService;
import com.game.scene.bag.TestBagTables;
import com.game.scene.battle.BattleFixture;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.*;
import com.game.scene.testing.RecordingAssetAudit;
import com.game.scene.world.ScenePlayer;
import com.game.table.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 明确的小装备配置，角色/战斗基础仍用正式配表及真实进场恢复流程。 */
final class EquipmentFixture {
    final BattleFixture world = new BattleFixture();
    final ScenePlayer player = world.enter(11, 1001);
    final AtomicLong guids = new AtomicLong(10_000);
    long randomValue;
    int rolls;
    final EquipmentTables tables;
    final EquipmentRules rules;
    final BagService bags;
    final CurrencyService currency;
    final AttributeService attributes;
    final EquipmentService equipment;

    EquipmentFixture() { this(new RecordingAssetAudit()); }
    EquipmentFixture(AssetAudit audit) {
        this(audit, 1);
    }
    EquipmentFixture(AssetAudit audit, int equipLevel) {
        var weapon = ItemTable.newBuilder().setId(1).setMaxStackSize(1).setEquipKind(1).setEquipLevel(equipLevel)
                .setName("测试剑").setAffixPool(1).setAffixRule(1)
                .addBaseAttr(Itembase_attr.newBuilder().setBaseAttrId(1).setBaseAttrValue(40)).build();
        tables = new EquipmentTables(List.of(weapon, weapon.toBuilder().setId(2).build(),
                        ItemTable.newBuilder().setId(10).setMaxStackSize(999).setName("强化石").build(),
                        ItemTable.newBuilder().setId(11).setMaxStackSize(999).setName("宝石").build()),
                List.of(EquipAttributeTable.newBuilder().setId(1).setName("伤害").setEffect(4).build(),
                        EquipAttributeTable.newBuilder().setId(2).setName("气血").setEffect(3).setEffectParam(1).build(),
                        EquipAttributeTable.newBuilder().setId(3).setName("物理必杀").setEffect(5).setEffectParam(1).setPercent(1).build()),
                List.of(EquipAffixRuleTable.newBuilder().setId(1).setBlueMin(1).setBlueMax(1).build()),
                List.of(EquipAffixPoolTable.newBuilder().setId(1).setPoolId(1).setAttrId(2).setWeight(1).build()),
                List.of(EquipAttributeCapTable.newBuilder().setId(1).setAttrId(2).setLevel(1).setMinValue(100).setCap(100).build()),
                List.of(EquipEnhanceTable.newBuilder().setId(1).setBonusPermyriad(500).setSuccessRate(10_000)
                                .setCostGold(100).setMaterialId(10).setMaterialCount(2).build(),
                        EquipEnhanceTable.newBuilder().setId(2).setBonusPermyriad(1000).setSuccessRate(1).setFailLevelLoss(1)
                                .setCostGold(200).setMaterialId(10).setMaterialCount(1).build()),
                List.of(EquipGemTable.newBuilder().setId(11).setGemType(1).setAttrId(3).setValue(7).build()),
                List.of(EquipGemSocketTable.newBuilder().setId(1).setEquipKind(1).setSocket(0).setGemType(1).setName("剑孔").build()),
                List.of(EquipSlotTable.newBuilder().setId(0).setEquipKind(1).setName("武器一").build(),
                        EquipSlotTable.newBuilder().setId(1).setEquipKind(1).setName("武器二").build()));
        rules = new EquipmentRules(tables, bound -> { rolls++; return Math.min(bound - 1, randomValue); });
        var bagTables = TestBagTables.of(Map.of(1, new ItemCatalog.ItemSpec(1, 1, 1), 2, new ItemCatalog.ItemSpec(2, 1, 1),
                10, new ItemCatalog.ItemSpec(10, 999, 0), 11, new ItemCatalog.ItemSpec(11, 999, 0)), Map.of(1, List.of(0, 1)), rules);
        bags = new BagService(bagTables, count -> {
            long[] minted = new long[count]; for (int i = 0; i < count; i++) minted[i] = guids.incrementAndGet(); return minted;
        }, audit, GainAnomalyDetector.off(), SceneMetrics.noop());
        currency = new CurrencyService(audit, GainAnomalyDetector.off(), SceneMetrics.noop(), world.clock);
        attributes = new AttributeService(AttributeTables.from(BattleFixture.CONFIG), world.clock, currency, SceneMetrics.noop(), rules);
        attributes.initializeOnLoad(player);
        equipment = new EquipmentService(rules, bags, currency, attributes, world.sceneMetrics);
    }
    long grant(int config, long count) {
        var result = bags.addItems(player, BagType.INVENTORY, Map.of(config, count), AssetAudit.Reason.SYSTEM_GRANT, 0, "");
        if (!result.ok()) throw new IllegalStateException("夹具入包失败 " + result.tip());
        return result.written().getFirst().guids().getFirst();
    }
    BagItem item(long guid) {
        BagItem item = player.bags().bag(BagType.INVENTORY).item(guid);
        return item == null ? player.bags().bag(BagType.EQUIPMENT).item(guid) : item;
    }
    void fund() { currency.add(player, Wallet.GOLD, 1000, AssetAudit.Reason.SYSTEM_GRANT); grant(10, 10); }
}
