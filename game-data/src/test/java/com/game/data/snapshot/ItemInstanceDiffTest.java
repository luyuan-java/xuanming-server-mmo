package com.game.data.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.EquipmentAffixState;
import com.game.player.store.state.EquipmentGemState;
import com.game.player.store.state.EquipmentState;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ItemInstanceDiffTest {
    private static BagItemState item(long uuid) {
        return BagItemState.newBuilder().setItemUuid(uuid).setConfigId(501).setStackSize(1).build();
    }

    private static UnknownFieldSet unknown(long value) {
        return UnknownFieldSet.newBuilder().addField(99,
                UnknownFieldSet.Field.newBuilder().addVarint(value).build()).build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> changes(Map<String, Object> instance) {
        return (List<Map<String, Object>>) instance.get("changes");
    }

    @Test void 数量相同仍显示强化宝石词条穿脱及同长度未知字段变化() {
        var equipment = EquipmentState.newBuilder().setEnhanceLevel(1).setUnknownFields(unknown(7))
                .addAffixes(EquipmentAffixState.newBuilder().setAttrId(3).setTier(1).setValue(4))
                .addGems(EquipmentGemState.newBuilder().setSocket(0).setGemConfigId(11).setUnknownFields(unknown(7)));
        var old = item(-2L).toBuilder().setEquipment(equipment).setUnknownFields(unknown(7)).build();
        var now = old.toBuilder().setBagType(1).setPos(3).setUnknownFields(unknown(8));
        now.getEquipmentBuilder().setEnhanceLevel(2).setUnknownFields(unknown(8))
                .setAffixes(0, equipment.getAffixes(0).toBuilder().setValue(5))
                .setGems(0, equipment.getGems(0).toBuilder().setGemConfigId(12).setUnknownFields(unknown(8)));
        BagState snapshot = BagState.newBuilder().addItems(old).build();
        BagState current = BagState.newBuilder().addItems(now).build();
        var result = ItemInstanceDiff.compare(snapshot, current);

        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        var row = result.instances().getFirst();
        assertThat(row).containsEntry("itemUuid", "18446744073709551614");
        String prefix = "bag.items{18446744073709551614}.";
        assertThat(changes(row)).extracting(change -> change.get("path")).containsExactlyInAnyOrder(
                prefix + "bag_type", prefix + "pos", prefix + "equipment.enhance_level",
                prefix + "equipment.affixes[0].value", prefix + "equipment.gems[0].gem_config_id",
                prefix + "equipment.gems[0].<unknown>", prefix + "equipment.<unknown>", prefix + "<unknown>");
        assertThat(snapshot.getItems(0)).isEqualTo(old);
        assertThat(current.getItems(0)).isEqualTo(now.build());
    }

    @Test void 空装备段的presence变化也可见_列表重排不算变化() {
        var old = item(1);
        var now = old.toBuilder().setEquipment(EquipmentState.getDefaultInstance()).build();
        var snapshot = BagState.newBuilder().addItems(old).addItems(item(2)).build();
        var current = BagState.newBuilder().addItems(item(2)).addItems(now).build();
        var result = ItemInstanceDiff.compare(snapshot, current);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(changes(result.instances().getFirst())).singleElement().satisfies(change ->
                assertThat(change).containsEntry("path", "bag.items{1}.equipment")
                        .containsEntry("snapshot", "<unset>").containsEntry("current", "<set>"));
        assertThat(ItemInstanceDiff.compare(snapshot, BagState.newBuilder().addItems(item(2)).addItems(old).build())
                .instances()).isEmpty();
    }

    @Test void 数量变更也显示_新增和删除仍交给原有数量差异() {
        var snapshot = BagState.newBuilder().addItems(item(1)).addItems(item(2)).build();
        var current = BagState.newBuilder().addItems(item(1).toBuilder().setStackSize(2)).addItems(item(3)).build();
        var result = ItemInstanceDiff.compare(snapshot, current);
        assertThat(result.changedCount()).isEqualTo(1);
        assertThat(changes(result.instances().getFirst())).singleElement().satisfies(change ->
                assertThat(change).containsEntry("path", "bag.items{1}.stack_size")
                        .containsEntry("snapshot", "1").containsEntry("current", "2"));
    }

    @Test void 路径明细共享上限_截断仍报告全部变化实例数量() {
        var snapshot = BagState.newBuilder();
        var current = BagState.newBuilder();
        for (int i = 1; i <= 600; i++) {
            snapshot.addItems(item(i));
            current.addItems(item(i).toBuilder().setPos(1));
        }
        var result = ItemInstanceDiff.compare(snapshot.build(), current.build());
        assertThat(result.changedCount()).isEqualTo(600);
        assertThat(result.instances()).hasSize(StateDiff.MAX_CHANGES);
        assertThat(result.truncated()).isTrue();
    }
}
