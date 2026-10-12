package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayerBagsTest {

    private final TestCatalog catalog = new TestCatalog();

    private static BagItemState item(long guid, int config, int size, int pos, int bagType, long seq) {
        return BagItemState.newBuilder().setItemUuid(guid).setConfigId(config).setStackSize(size).setPos(pos)
                .setBagType(bagType).setAcquireSeq(seq).build();
    }

    private static UnknownFieldSet unknown(int field, long value) {
        return UnknownFieldSet.newBuilder()
                .addField(field, UnknownFieldSet.Field.newBuilder().addVarint(value).build()).build();
    }

    private PlayerBags restored(BagState state) {
        PlayerBags bags = PlayerBags.restore(state);
        bags.normalize(catalog);
        return bags;
    }

    @Test
    void 新号四个空包缺省容量_是pristine_存档为空() {
        PlayerBags bags = PlayerBags.empty();
        assertThat(bags.bag(BagType.INVENTORY).capacity()).isEqualTo(100);
        assertThat(bags.bag(BagType.WAREHOUSE).capacity()).isEqualTo(200);
        assertThat(bags.bag(BagType.EQUIPMENT).capacity()).isEqualTo(10);
        assertThat(bags.bag(BagType.TEMPORARY).capacity()).isEqualTo(200);
        assertThat(bags.isPristine()).isTrue();
        assertThat(bags.toState()).isEqualTo(BagState.getDefaultInstance());
    }

    @Test
    void Java写的存档原样往返_含各层不认识的字段() {
        BagState state = BagState.newBuilder()
                .addItems(item(1L << 60 | 1, 9, 2, 3, 0, 5).toBuilder().setUnknownFields(unknown(77, 9)).build())
                .addItems(item(1L << 60 | 2, 10, 500, 7, 0, 6))
                .addItems(item(1L << 60 | 3, 10, 3, 121, 1, 7))
                .addItems(item(1L << 60 | 4, 1, 1, 1, 2, 8))
                .addItems(item(1L << 60 | 5, 11, 4, 0, 3, 9))
                .setUnknownFields(unknown(88, 1))
                .build();

        PlayerBags bags = restored(state);

        assertThat(bags.toState()).isEqualTo(state);
        assertThat(bags.isPristine()).isFalse();
        assertThat(bags.bag(BagType.INVENTORY).item(1L << 60 | 2).slot()).isEqualTo(7);
        assertThat(bags.bag(BagType.EQUIPMENT).item(1L << 60 | 4).slot()).as("本部位的空槽：用存档里的").isEqualTo(1);
    }

    @Test
    void 规整前存档原样返回_规整前取包是编程错误() {
        BagState state = BagState.newBuilder().addItems(item(5, 9, 1, 0, 0, 1)).build();
        PlayerBags bags = PlayerBags.restore(state);
        assertThat(bags.toState()).isSameAs(state);
        assertThat(bags.isPristine()).isFalse();
        assertThatThrownBy(() -> bags.bag(BagType.INVENTORY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 老数据序号为0按出现顺序接在最大序号之后_新入包的序号更大() {
        BagState state = BagState.newBuilder()
                .addItems(item(1, 9, 1, 0, 0, 0))
                .addItems(item(2, 9, 1, 1, 0, 70))
                .addItems(item(3, 9, 1, 2, 0, 0))
                .build();

        PlayerBags bags = restored(state);
        Bag bag = bags.bag(BagType.INVENTORY);

        assertThat(bag.item(1).acquireSeq()).isEqualTo(71);
        assertThat(bag.item(3).acquireSeq()).isEqualTo(72);
        TestGuids guids = new TestGuids();
        long fresh = bag.add(Map.of(1, 1L), catalog, guids).written().get(0).guids().get(0);
        assertThat(bag.item(fresh).acquireSeq()).isGreaterThan(72);
    }

    @Test
    void 格子越界或撞了重新落位_东西不丢() {
        BagState colliding = BagState.newBuilder()
                .addAllCapacities(List.of(5, 0, 0, 0))
                .addItems(item(1, 3, 1, 99, 0, 1))
                .addItems(item(2, 4, 1, 2, 0, 2))
                .addItems(item(3, 5, 1, 2, 0, 3))
                .build();

        Bag bag = restored(colliding).bag(BagType.INVENTORY);

        assertThat(bag.capacity()).isEqualTo(5);
        assertThat(bag.itemCount()).isEqualTo(3);
        assertThat(bag.item(2).slot()).as("先到的保留存档格子").isEqualTo(2);
        assertThat(bag.item(1).slot()).isBetween(0, 4).isNotEqualTo(2);
        assertThat(bag.item(3).slot()).isBetween(0, 4).isNotIn(2, bag.item(1).slot());
    }

    @Test
    void 装备栏_存档格子不是本部位的槽就按当前表落位_查不到表的保留存档格子() {
        BagState state = BagState.newBuilder()
                .addItems(item(1, 1, 1, 7, 2, 1))
                .addItems(item(2, 987654, 1, 5, 2, 2))
                .build();

        Bag bag = restored(state).bag(BagType.EQUIPMENT);

        assertThat(bag.item(1).slot()).isZero();
        assertThat(bag.item(2).slot()).isEqualTo(5);
    }

    @Test
    void 容量非缺省时存下来_0表示缺省() {
        BagState state = BagState.newBuilder().addAllCapacities(List.of(64, 0, 20, 256))
                .addItems(item(999, 9, 1, 3, 0, 1)).build();

        PlayerBags bags = restored(state);

        assertThat(bags.bag(BagType.INVENTORY).capacity()).isEqualTo(64);
        assertThat(bags.bag(BagType.WAREHOUSE).capacity()).isEqualTo(200);
        assertThat(bags.bag(BagType.EQUIPMENT).capacity()).isEqualTo(20);
        assertThat(bags.bag(BagType.TEMPORARY).capacity()).isEqualTo(256);
        assertThat(bags.toState().getCapacitiesList()).as("缺省的写 0").containsExactly(64, 0, 20, 256);
    }

    @Test
    void 数量为0的条目丢弃_不认识的背包原样隔离写回() {
        BagItemState future = item(7, 9, 3, 0, 9, 4).toBuilder().setUnknownFields(unknown(50, 1)).build();
        BagState state = BagState.newBuilder()
                .addItems(item(5, 9, 0, 0, 0, 1))
                .addItems(future)
                .addItems(item(6, 9, 1, 1, 0, 2))
                .build();

        PlayerBags bags = restored(state);

        assertThat(bags.bag(BagType.INVENTORY).itemCount()).isEqualTo(1);
        assertThat(bags.bag(BagType.INVENTORY).item(5)).isNull();
        assertThat(bags.toState().getItemsList()).containsExactly(item(6, 9, 1, 1, 0, 2), future);
        assertThat(bags.isPristine()).isFalse();
    }

    @Test
    void 结构性损坏拒绝_存档原样留着() {
        assertCorrupt(BagState.newBuilder().addItems(item(0, 9, 1, 0, 0, 1)).build());
        assertCorrupt(BagState.newBuilder().addItems(item(-1L, 9, 1, 0, 0, 1)).build());
        assertCorrupt(BagState.newBuilder().addItems(item(5, 9, 1, 0, 0, 1)).addItems(item(5, 9, 1, 0, 1, 1)).build());
        assertCorrupt(BagState.newBuilder().addAllCapacities(List.of(1))
                .addItems(item(5, 9, 1, 0, 0, 1)).addItems(item(6, 9, 1, 1, 0, 2)).build());
        assertCorrupt(BagState.newBuilder().addAllCapacities(List.of(-1)).build());
    }

    private void assertCorrupt(BagState state) {
        PlayerBags bags = PlayerBags.restore(state);
        assertThatThrownBy(() -> bags.normalize(catalog)).isInstanceOf(IllegalStateException.class);
        assertThat(bags.normalized()).isFalse();
        assertThat(bags.toState()).isSameAs(state);
    }

    @Test
    void 规整后的存档再规整一遍不变_含重新落位与补盖序号的结果() {
        BagState messy = BagState.newBuilder()
                .addItems(item(5, 9, 1, 150, 0, 0))
                .addItems(item(6, 9, 1, 3, 0, 7))
                .addItems(item(7, 1, 1, 7, 2, 0))
                .build();
        BagState once = restored(messy).toState();
        assertThat(once).isNotEqualTo(messy);
        assertThat(restored(once).toState()).isEqualTo(once);
    }

    @Test
    void 不认识的背包的容量原样接在后面写回() {
        BagItemState future = item(9, 9, 3, 0, 4, 1);
        BagState state = BagState.newBuilder().addAllCapacities(List.of(0, 0, 0, 0, 80)).addItems(future).build();

        PlayerBags bags = restored(state);

        assertThat(bags.toState()).isEqualTo(state);
        assertThat(bags.isPristine()).isFalse();
        PlayerBags onlyCapacity = restored(BagState.newBuilder().addAllCapacities(List.of(0, 0, 0, 0, 80)).build());
        assertThat(onlyCapacity.isPristine()).isFalse();
        assertThat(onlyCapacity.toState().getCapacitiesList()).containsExactly(0, 0, 0, 0, 80);
    }
}
