package com.game.scene.equip;

import static org.assertj.core.api.Assertions.*;

import com.game.player.store.state.*;
import com.game.proto.BagItemInfo;
import com.game.proto.CombatAttributes;
import com.game.scene.bag.TestBagTables;
import com.game.scene.player.*;
import com.game.table.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EquipmentRulesTest {
    private static ItemTable weapon() {
        return ItemTable.newBuilder().setId(1).setEquipKind(1).setMaxStackSize(1).setEquipLevel(10).setAffixPool(1).setAffixRule(1).build();
    }
    private static EquipAttributeTable attr(int id) { return EquipAttributeTable.newBuilder().setId(id).setEffect(4).build(); }
    private static EquipAffixPoolTable pool(int attr, int weight) {
        return EquipAffixPoolTable.newBuilder().setId(attr).setPoolId(1).setAttrId(attr).setWeight(weight).build();
    }
    private static EquipAttributeCapTable cap(int attr, int min, int max) {
        return EquipAttributeCapTable.newBuilder().setId(attr).setAttrId(attr).setLevel(10).setMinValue(min).setCap(max).build();
    }
    private static EquipmentTables tables(ItemTable weapon, List<EquipAttributeTable> attributes, EquipAffixRuleTable rule,
                                         List<EquipAffixPoolTable> pool, List<EquipAttributeCapTable> caps) {
        return new EquipmentTables(List.of(weapon), attributes, List.of(rule), pool, caps, List.of(), List.of(), List.of(), List.of());
    }
    private static PlayerBags bag(EquipmentState equipment) {
        var bags = PlayerBags.restore(BagState.newBuilder().addItems(BagItemState.newBuilder().setItemUuid(7).setConfigId(1)
                .setStackSize(1).setBagType(BagType.EQUIPMENT.code()).setEquipment(equipment)).build());
        bags.normalize(TestBagTables.of(Map.of(1, new ItemCatalog.ItemSpec(1, 1, 1)), Map.of(1, List.of(0))));
        return bags;
    }
    @Test void 重复权重合并_蓝不放回_粉黄放回_精确随机消费次序() {
        var rule = EquipAffixRuleTable.newBuilder().setId(1).setBlueMin(2).setBlueMax(2).setPinkRate(10_000).setYellowRate(10_000).build();
        var tables = tables(weapon(), List.of(attr(1), attr(2)), rule, List.of(pool(1, 1), pool(1, 2), pool(2, 1)), List.of(cap(1, 3, 5), cap(2, 7, 9)));
        List<Long> bounds = new ArrayList<>();
        var rules = new EquipmentRules(tables, bound -> { bounds.add(bound); return 0; });
        var result = rules.newInstance(1);
        assertThat(result.getAffixesList()).extracting(EquipmentAffixState::getAttrId).containsExactly(1, 2, 1, 1);
        assertThat(result.getAffixesList()).extracting(EquipmentAffixState::getTier).containsExactly(1, 1, 2, 3);
        assertThat(result.getAffixesList()).extracting(EquipmentAffixState::getSeq).containsExactly(0, 1, 0, 0);
        assertThat(result.getAffixesList()).extracting(EquipmentAffixState::getValue).containsExactly(3, 7, 3, 3);
        assertThat(bounds).containsExactly(1L, 4L, 3L, 1L, 3L, 10_000L, 4L, 3L, 10_000L, 4L, 3L);
    }
    @Test void 空池或缺属性不掷_空段仍标已初始化_普通物品无段() {
        var tables = tables(weapon(), List.of(), EquipAffixRuleTable.newBuilder().setId(1).setBlueMin(-1).build(),
                List.of(pool(99, 1)), List.of(cap(99, 1, 10)));
        var rules = new EquipmentRules(tables, bound -> { throw new AssertionError("不该掷骰"); });
        assertThat(rules.newInstance(1)).isEqualTo(EquipmentState.getDefaultInstance());
        assertThat(rules.newInstance(999)).isNull();
        assertThat(tables.validationIssues()).anyMatch(s -> s.contains("缺属性"));
    }
    @Test void 无符号大区间被候选数夹紧_随机越界夹到最高值_失败率0不掷() {
        var tables = tables(weapon(), List.of(attr(1)), EquipAffixRuleTable.newBuilder().setId(1).setBlueMin(-1).setBlueMax(-1).build(),
                List.of(pool(1, -1)), List.of(cap(1, 0, -1)));
        List<Long> bounds = new ArrayList<>();
        var rules = new EquipmentRules(tables, bound -> { bounds.add(bound); return -1; });
        assertThat(rules.newInstance(1).getAffixesList()).singleElement().satisfies(a -> assertThat(a.getValue()).isEqualTo(-1));
        assertThat(bounds).containsExactly(1L, 4_294_967_295L, 4_294_967_295L);
        bounds.clear(); assertThat(rules.enhanceSuccess(0)).isFalse(); assertThat(bounds).isEmpty();
        assertThat(rules.enhanceSuccess(9999)).isFalse(); assertThat(rules.enhanceSuccess(10_000)).isTrue();
        assertThat(bounds).containsExactly(10_000L, 10_000L);
    }
    @Test void 上限取最大不超装备等级档_读路径夹值但不重写存档() {
        var tables = tables(weapon(), List.of(attr(1)), EquipAffixRuleTable.getDefaultInstance(), List.of(),
                List.of(cap(1, 1, 10), cap(1, 1, 99).toBuilder().setLevel(20).build(), cap(1, 1, 5).toBuilder().setLevel(5).build()));
        var rules = new EquipmentRules(tables, bound -> { throw new AssertionError("读路径不能掷骰"); });
        var raw = EquipmentState.newBuilder().addAffixes(EquipmentAffixState.newBuilder().setAttrId(1).setTier(4).setValue(99)).build();
        var item = bag(raw).bag(BagType.EQUIPMENT).item(7);
        assertThat(rules.values(item)).singleElement().satisfies(v -> { assertThat(v.value()).isEqualTo(10); assertThat(v.cap()).isEqualTo(10); });
        assertThat(item.equipment()).isEqualTo(raw);
        assertThat(tables.cap(1, 4)).isNull();
    }
    @Test void 十五种combat字段按协议编号精确映射_速度与防御按效果名而非proto序号() {
        var builder = weapon().toBuilder(); List<EquipAttributeTable> definitions = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            definitions.add(EquipAttributeTable.newBuilder().setId(i).setEffect(5).setEffectParam(i).build());
            builder.addBaseAttr(Itembase_attr.newBuilder().setBaseAttrId(i).setBaseAttrValue(i * 3));
        }
        definitions.add(EquipAttributeTable.newBuilder().setId(20).setEffect(3).setEffectParam(5).build());
        definitions.add(EquipAttributeTable.newBuilder().setId(21).setEffect(3).setEffectParam(6).build());
        builder.addBaseAttr(Itembase_attr.newBuilder().setBaseAttrId(20).setBaseAttrValue(77));
        builder.addBaseAttr(Itembase_attr.newBuilder().setBaseAttrId(21).setBaseAttrValue(88));
        var rules = new EquipmentRules(tables(builder.build(), definitions, EquipAffixRuleTable.getDefaultInstance(), List.of(), List.of()), bound -> 0);
        var bonus = EquipmentBonuses.collect(bag(EquipmentState.getDefaultInstance()), rules);
        for (int i = 1; i <= 15; i++) assertThat(bonus.combat().getField(CombatAttributes.getDescriptor().findFieldByNumber(i))).isEqualTo((long) i * 3);
        assertThat(bonus.flat().speed()).isEqualTo(77); assertThat(bonus.flat().defense()).isEqualTo(88);
    }
    @Test void 宝石只看前八条_第一条非零占孔_无效记录不回退第二条() {
        var table = new EquipmentTables(List.of(weapon()), List.of(attr(1)), List.of(), List.of(), List.of(), List.of(),
                List.of(EquipGemTable.newBuilder().setId(11).setAttrId(1).setValue(7).build()),
                List.of(EquipGemSocketTable.newBuilder().setEquipKind(1).setSocket(0).build()), List.of());
        var raw = EquipmentState.newBuilder().addGems(EquipmentGemState.newBuilder().setSocket(0).setGemConfigId(99))
                .addGems(EquipmentGemState.newBuilder().setSocket(0).setGemConfigId(11));
        var rules = new EquipmentRules(table, bound -> 0);
        assertThat(rules.gemValues(bag(raw.build()).bag(BagType.EQUIPMENT).item(7))).isEmpty();
        raw.clearGems(); for (int i = 0; i < 8; i++) raw.addGems(EquipmentGemState.newBuilder().setSocket(0));
        raw.addGems(EquipmentGemState.newBuilder().setSocket(0).setGemConfigId(11));
        assertThat(rules.gemValues(bag(raw.build()).bag(BagType.EQUIPMENT).item(7))).isEmpty();
    }
    @Test void 强化只扩大基础并显示下级成本_缺段不显示下级() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.item(id).equipment(f.item(id).equipment().toBuilder().setEnhanceLevel(1).build());
        var info = BagItemInfo.newBuilder(); new EquipmentDisplay(f.rules).item(f.item(id), info);
        assertThat(info.getBaseAttrs(0).getValue()).isEqualTo(42); assertThat(info.getBaseAttrs(0).getEnhanceBonus()).isEqualTo(2);
        assertThat(info.getBaseAttrs(0).getEnhanceNextValue()).isEqualTo(44); assertThat(info.getAffixes(0).getValue()).isEqualTo(100);
        assertThat(info.getEnhanceNext().getCostGold()).isEqualTo(200);
        f.item(id).equipment(null); info.clear(); new EquipmentDisplay(f.rules).item(f.item(id), info);
        assertThat(info.hasEnhanceNext()).isFalse(); assertThat(info.getAffixesCount()).isZero();
    }
    @Test void 放大与加法大值饱和_不回绕_普通值向下取整() {
        assertThat(EquipmentRules.scale(99, 500)).isEqualTo(103);
        assertThat(EquipmentRules.scale(40, 0)).isEqualTo(40);
        assertThat(EquipmentRules.scale(Long.MAX_VALUE, 4_294_967_295L)).isEqualTo(Long.MAX_VALUE);
        assertThat(EquipmentRules.add(Long.MAX_VALUE - 1, 8)).isEqualTo(Long.MAX_VALUE);
    }
    @Test void 具名槽升序去重_无名空槽隐藏但已占槽可卸装() {
        var table = new EquipmentTables(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(EquipSlotTable.newBuilder().setId(2).setName("帽子").build(),
                        EquipSlotTable.newBuilder().setId(0).build(), EquipSlotTable.newBuilder().setId(1).build(),
                        EquipSlotTable.newBuilder().setId(2).setName("重复").build(), EquipSlotTable.newBuilder().setId(99).setName("越界").build()));
        var layout = com.game.proto.BagLayoutInfo.newBuilder().setCapacity(10).addSlots(com.game.proto.BagSlotInfo.newBuilder().setSlot(1).setItemId(7));
        new EquipmentDisplay(new EquipmentRules(table, bound -> 0)).slots(layout);
        assertThat(layout.getEquipSlotsList()).extracting(com.game.proto.EquipSlotInfo::getSlot).containsExactly(1, 2);
        assertThat(layout.getEquipSlots(1).getName()).isEqualTo("帽子");
    }
    @Test void 百分比面板封顶100但战斗快照保留装备原值() {
        var f = new EquipmentFixture(); long id = f.grant(1, 1); f.equipment.equip(f.player, id);
        f.player.attributes().setEquipment(new EquipmentBonuses(Map.of(), 0, PlayerAttributes.Derived.ZERO,
                CombatAttributes.newBuilder().setPhysicalCritRate(150).build()));
        assertThat(f.attributes.buildPanel(f.player).getCombatList()).singleElement().satisfies(row -> assertThat(row.getValue()).isEqualTo(100));
        assertThat(f.player.attributes().combat().getPhysicalCritRate()).isEqualTo(150);
    }
}
