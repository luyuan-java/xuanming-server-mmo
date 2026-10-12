package com.game.scene.player;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.player.Bag.AddResult;
import com.game.scene.player.Bag.Removed;
import com.google.protobuf.UnknownFieldSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 背包容器规则（移植自基线 bag_test.cpp，按 Java 的批量原子入口改写）。物品与槽位同正式表：见 {@link TestCatalog}。 */
class BagTest {

    private final TestCatalog catalog = new TestCatalog();
    private final TestGuids guids = new TestGuids();

    private AddResult add(Bag bag, int configId, long count) {
        return bag.add(Map.of(configId, count), catalog, guids);
    }

    private AddResult add(Bag bag, Map<Integer, Long> counts) {
        return bag.add(counts, catalog, guids);
    }

    private static Map<Integer, Long> counts(long... pairs) {
        Map<Integer, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((int) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** 直接放一个实例（构造僵尸 / 零头 / 指定序号的场景）。 */
    private static BagItem put(Bag bag, long guid, int configId, long size, long seq, int slot) {
        BagItem item = new BagItem(guid, configId, size, seq, UnknownFieldSet.getDefaultInstance());
        bag.place(item, slot);
        bag.raiseAcquireSeq(seq);
        return item;
    }

    private static List<Integer> configsBySlot(Bag bag) {
        return bag.items().stream().map(BagItem::configId).toList();
    }

    private static List<Long> sizesBySlot(Bag bag) {
        return bag.items().stream().map(BagItem::size).toList();
    }

    // ------------------------------------------------------------------ 堆叠与入包

    @Test
    void 不可叠加一件一格_first_fit_回执记写到的guid_序号递增() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        AddResult first = add(bag, 1, 1);
        AddResult second = add(bag, 1, 1);

        assertThat(first.ok()).isTrue();
        assertThat(first.written()).hasSize(1);
        long guid = first.written().get(0).guids().get(0);
        assertThat(bag.item(guid).slot()).isZero();
        assertThat(bag.item(second.written().get(0).guids().get(0)).slot()).isEqualTo(1);
        assertThat(bag.item(guid).acquireSeq()).isNotZero()
                .isLessThan(bag.item(second.written().get(0).guids().get(0)).acquireSeq());
        assertThat(first.evicted()).isEmpty();
    }

    @Test
    void 不可叠加的多件一次切成多个实例_满了整批拒绝6006_零写入() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        assertThat(add(bag, 1, 10).written().get(0).guids()).hasSize(10);
        assertThat(bag.itemCount()).isEqualTo(10);

        AddResult full = add(bag, 1, 1);
        assertThat(full.tip()).isEqualTo(6006);
        assertThat(full.written()).isEmpty();
        assertThat(bag.itemCount()).isEqualTo(10);
    }

    @Test
    void 可叠加按上限切满堆_刚好放满_多一个就整批拒绝不留半批() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        assertThat(add(bag, 10, 9990).ok()).isTrue();
        assertThat(sizesBySlot(bag)).hasSize(10).containsOnly(999L);
        assertThat(add(bag, 10, 1).tip()).isEqualTo(6006);
        assertThat(bag.total(10)).isEqualTo(9990);

        Bag empty = new Bag(BagType.INVENTORY, 10);
        assertThat(add(empty, 10, 9991).tip()).isEqualTo(6006);
        assertThat(empty.itemCount()).isZero();
        assertThat(add(empty, 10, 1000).ok()).isTrue();
        assertThat(empty.itemCount()).as("max+1 占两格").isEqualTo(2);
    }

    @Test
    void 上限为2的物品交替加1和2_格数始终是总量除以2向上取整_放不下时零头也不并() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        long total = 0;
        for (int i = 0; ; i++) {
            long n = i % 2 == 0 ? 1 : 2;
            AddResult result = add(bag, 9, n);
            if (!result.ok()) {
                assertThat(result.tip()).isEqualTo(6006);
                break;
            }
            total += n;
            assertThat(bag.itemCount()).isEqualTo((int) ((total + 1) / 2));
        }
        assertThat(total).isEqualTo(19);
        assertThat(bag.itemCount()).isEqualTo(10);
        assertThat(bag.total(9)).as("失败那次的 1 个没有并进零头").isEqualTo(19);
    }

    @Test
    void 回执先列并入的既有堆_再列新切的实例_纯并堆也记旧堆的guid() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        long old = add(bag, 10, 998).written().get(0).guids().get(0);

        AddResult merge = add(bag, 10, 1);
        assertThat(merge.written().get(0).guids()).containsExactly(old);

        AddResult spill = add(bag, 10, 5);
        assertThat(spill.written().get(0).guids()).hasSize(1).doesNotContain(old);
        assertThat(spill.written().get(0).count()).isEqualTo(5);
        assertThat(bag.total(10)).isEqualTo(1004);
    }

    @Test
    void 既有零头的空位先用_空格计算与基线一致() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        add(bag, 10, 1);
        assertThat(add(bag, 10, 998).ok()).as("998 全部并进零头，不占新格").isTrue();
        assertThat(bag.itemCount()).isEqualTo(1);

        Bag edge = new Bag(BagType.INVENTORY, 10);
        add(edge, 10, 1);
        assertThat(add(edge, counts(10, 998 + 9 * 999L)).ok()).isTrue();
        Bag over = new Bag(BagType.INVENTORY, 10);
        add(over, 10, 1);
        assertThat(add(over, counts(10, 998 + 9 * 999L + 1)).tip()).isEqualTo(6006);

        Bag mixed = new Bag(BagType.INVENTORY, 10);
        add(mixed, 10, 1);
        assertThat(add(mixed, counts(1, 9, 10, 1997)).tip()).isEqualTo(6006);
        assertThat(add(mixed, counts(1, 9, 10, 998)).ok()).isTrue();
    }

    @Test
    void 不同的不可叠加配置一起占格_满堆没有空位() {
        assertThat(add(new Bag(BagType.INVENTORY, 10), counts(1, 4, 2, 6)).ok()).isTrue();
        assertThat(add(new Bag(BagType.INVENTORY, 10), counts(1, 5, 2, 6)).tip()).isEqualTo(6006);

        Bag bag = new Bag(BagType.INVENTORY, 10);
        add(bag, 10, 999);
        assertThat(add(bag, 10, 8992).tip()).isEqualTo(6006);
        assertThat(add(bag, 10, 8991).ok()).isTrue();
    }

    @Test
    void 参数与表错误_数量非法6004_查不到表1001_上限为0回1002_空请求成功无改动() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        assertThat(add(bag, counts(1, 1, 10, 0)).tip()).isEqualTo(6004);
        assertThat(add(bag, 1, -1).tip()).isEqualTo(6004);
        assertThat(add(bag, 1, 0x1_0000_0000L).tip()).isEqualTo(6004);
        assertThat(add(bag, counts(12345, 0)).tip()).as("数量先于查表判").isEqualTo(6004);
        assertThat(add(bag, 12345, 1).tip()).isEqualTo(1001);
        assertThat(add(bag, TestCatalog.ZERO_STACK, 1).tip()).isEqualTo(1002);
        assertThat(add(bag, counts(1, 1, 12345, 1)).tip()).as("整批原子：前一项也不写").isEqualTo(1001);
        assertThat(bag.itemCount()).isZero();

        AddResult empty = add(bag, Map.of());
        assertThat(empty.ok()).isTrue();
        assertThat(empty.written()).isEmpty();
    }

    @Test
    void 多个配置按配置号升序写入() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        AddResult result = add(bag, counts(11, 1, 2, 1, 10, 1));
        assertThat(result.written()).extracting(Bag.Written::configId).containsExactly(2, 10, 11);
        assertThat(configsBySlot(bag)).containsExactly(2, 10, 11);
    }

    // ------------------------------------------------------------------ 铸号（一次铸齐，铸不出零写入）

    @Test
    void 发号源不可用_要铸号的一律6004零写入_纯并堆不要号() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        add(bag, 10, 996);
        guids.budget = 0;

        assertThat(add(bag, 10, 2).ok()).as("纯并堆").isTrue();
        assertThat(bag.total(10)).isEqualTo(998);
        assertThat(add(bag, 10, 2).tip()).as("1 并 + 1 新").isEqualTo(6004);
        assertThat(bag.total(10)).isEqualTo(998);
        assertThat(add(bag, 1, 1).tip()).isEqualTo(6004);
        assertThat(add(bag, counts(10, 1, 11, 1)).tip()).as("整批：能并的也不并").isEqualTo(6004);
        assertThat(bag.total(10)).isEqualTo(998);
        assertThat(bag.itemCount()).isEqualTo(1);

        guids.budget = Long.MAX_VALUE;
        assertThat(add(bag, 1, 1).ok()).isTrue();
    }

    @Test
    void 号的预算按新实例数精确计算() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        guids.budget = 3;
        assertThat(add(bag, 1, 5).tip()).isEqualTo(6004);
        assertThat(bag.itemCount()).isZero();
        assertThat(guids.budget).as("失败不消耗预算").isEqualTo(3);
        assertThat(add(bag, 1, 3).ok()).isTrue();
        assertThat(guids.budget).isZero();

        Bag stacks = new Bag(BagType.INVENTORY, 10);
        guids.budget = Long.MAX_VALUE;
        add(stacks, counts(10, 1));
        add(stacks, counts(10, 999));
        guids.budget = 1;
        assertThat(add(stacks, counts(10, 998 + 999 + 1)).tip()).as("要 2 个新实例").isEqualTo(6004);
        guids.budget = 2;
        assertThat(add(stacks, counts(10, 998 + 999 + 1)).ok()).isTrue();
    }

    // ------------------------------------------------------------------ 临时格淘汰（FIFO）

    @Test
    void 不淘汰的包满了直接拒绝() {
        Bag bag = new Bag(BagType.INVENTORY, 1);
        add(bag, 1, 1);
        AddResult result = add(bag, 2, 1);
        assertThat(result.tip()).isEqualTo(6006);
        assertThat(result.evicted()).isEmpty();
        assertThat(bag.itemCount()).isEqualTo(1);
    }

    @Test
    void 临时格满了挤掉最早的_回执带真实数量() {
        Bag bag = new Bag(BagType.TEMPORARY, 3);
        long a = add(bag, 1, 1).written().get(0).guids().get(0);
        long b = add(bag, 2, 1).written().get(0).guids().get(0);
        long c = add(bag, 1, 1).written().get(0).guids().get(0);

        AddResult result = add(bag, 2, 1);

        assertThat(result.ok()).isTrue();
        assertThat(result.evicted()).containsExactly(new Removed(a, 1, 1));
        assertThat(bag.itemCount()).isEqualTo(3);
        assertThat(bag.item(b)).isNotNull();
        assertThat(bag.item(c)).isNotNull();
    }

    @Test
    void 临时格腾不够一件都不动() {
        Bag bag = new Bag(BagType.TEMPORARY, 2);
        add(bag, 1, 1);
        AddResult result = add(bag, 2, 3);
        assertThat(result.tip()).isEqualTo(6006);
        assertThat(result.evicted()).isEmpty();
        assertThat(bag.itemCount()).isEqualTo(1);
    }

    @Test
    void 淘汰算到不动点_被挤掉的零头不能再并入() {
        Bag bag = new Bag(BagType.TEMPORARY, 2);
        add(bag, 9, 1);
        add(bag, 1, 1);

        AddResult result = add(bag, 9, 3);

        assertThat(result.ok()).isTrue();
        assertThat(result.evicted()).hasSize(2);
        assertThat(bag.total(9)).isEqualTo(3);
        assertThat(bag.itemCount()).isEqualTo(2);
    }

    @Test
    void 最早的不是零头时零头照常并入() {
        Bag bag = new Bag(BagType.TEMPORARY, 2);
        add(bag, 1, 1);
        add(bag, 9, 1);

        AddResult result = add(bag, 9, 3);

        assertThat(result.ok()).isTrue();
        assertThat(result.evicted()).extracting(Removed::configId).containsExactly(1);
        assertThat(bag.total(9)).isEqualTo(4);
    }

    @Test
    void 先进先出按入包序号而不是guid() {
        Bag bag = new Bag(BagType.TEMPORARY, 2);
        put(bag, 900001, 1, 1, 2, 0);
        put(bag, 900002, 1, 1, 1, 1);

        AddResult result = add(bag, 2, 1);

        assertThat(result.evicted()).extracting(Removed::guid).containsExactly(900002L);
    }

    @Test
    void 发号源不可用时先判号_一件都不淘汰() {
        Bag bag = new Bag(BagType.TEMPORARY, 2);
        add(bag, 1, 2);
        guids.budget = 0;
        AddResult result = add(bag, 1, 1);
        assertThat(result.tip()).isEqualTo(6004);
        assertThat(result.evicted()).isEmpty();
        assertThat(bag.itemCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 装备栏（按部位分桶）

    @Test
    void 同部位两个槽_第三件拒绝_不同配置共享部位预算() {
        Bag bag = new Bag(BagType.EQUIPMENT, 10);
        long first = add(bag, 2, 1).written().get(0).guids().get(0);
        long second = add(bag, 1, 1).written().get(0).guids().get(0);
        assertThat(bag.item(first).slot()).isZero();
        assertThat(bag.item(second).slot()).isEqualTo(1);
        assertThat(add(bag, 1, 1).tip()).isEqualTo(6006);
        assertThat(bag.itemCount()).isEqualTo(2);
    }

    @Test
    void 部位预算整批算_零写入() {
        assertThat(add(new Bag(BagType.EQUIPMENT, 10), 1, 3).tip()).isEqualTo(6006);
        Bag bag = new Bag(BagType.EQUIPMENT, 10);
        assertThat(add(bag, counts(1, 2, 2, 1)).tip()).isEqualTo(6006);
        assertThat(bag.itemCount()).isZero();
        assertThat(add(bag, counts(1, 1, 2, 1)).ok()).isTrue();
        assertThat(configsBySlot(bag)).containsExactly(1, 2);

        Bag worn = new Bag(BagType.EQUIPMENT, 10);
        add(worn, 1, 1);
        assertThat(add(worn, 1, 2).tip()).isEqualTo(6006);
        assertThat(add(worn, 1, 1).ok()).isTrue();
    }

    @Test
    void 部位为0的物品进不了装备栏_扁平包不看部位() {
        Bag equipment = new Bag(BagType.EQUIPMENT, 10);
        assertThat(add(equipment, 10, 1).tip()).isEqualTo(6006);
        assertThat(equipment.itemCount()).isZero();
        assertThat(add(equipment, counts(3, 1, 12345, 1)).tip()).as("查表先于部位判定（同基线）").isEqualTo(1001);
        assertThat(add(equipment, counts(3, 1, TestCatalog.ZERO_STACK, 1)).tip()).isEqualTo(1002);

        Bag flat = new Bag(BagType.INVENTORY, 10);
        assertThat(add(flat, 1, 3).ok()).isTrue();
        assertThat(flat.itemCount()).isEqualTo(3);
    }

    @Test
    void 槽号不小于容量的槽用不上() {
        Bag bag = new Bag(BagType.EQUIPMENT, 10);
        assertThat(add(bag, TestCatalog.FAR_SLOT_ITEM, 1).tip()).isEqualTo(6006);
        Bag wide = new Bag(BagType.EQUIPMENT, 20);
        assertThat(add(wide, TestCatalog.FAR_SLOT_ITEM, 1).ok()).isTrue();
        assertThat(wide.items().get(0).slot()).isEqualTo(TestCatalog.FAR_SLOT);
    }

    @Test
    void 装备栏不重排() {
        Bag bag = new Bag(BagType.EQUIPMENT, 10);
        add(bag, 2, 1);
        add(bag, 1, 1);
        assertThat(bag.mergeAndCompact(true, catalog).changed()).isFalse();
        assertThat(configsBySlot(bag)).containsExactly(2, 1);
    }

    // ------------------------------------------------------------------ 整理

    @Test
    void 同配置零头合并成满堆加至多一个零头_空实例回收_回执数量为0() {
        Bag bag = new Bag(BagType.INVENTORY, 40);
        for (int i = 0; i < 20; i++) {
            put(bag, 100 + i, 10, 1, i + 1, i);
            put(bag, 200 + i, 11, 1, 21 + i, 20 + i);
        }

        Bag.SortResult result = bag.mergeAndCompact(true, catalog);

        assertThat(result.changed()).isTrue();
        assertThat(bag.itemCount()).isEqualTo(2);
        assertThat(sizesBySlot(bag)).containsExactly(20L, 20L);
        assertThat(result.retired()).hasSize(38).allSatisfy(r -> assertThat(r.size()).isZero());
        assertThat(bag.item(100)).as("实例序最早的留下").isNotNull();
    }

    @Test
    void 已经最优时不动_满堆在前零头在后_配置号升序() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        put(bag, 1, 10, 999, 1, 0);
        put(bag, 2, 10, 999, 2, 1);
        put(bag, 3, 10, 3, 3, 2);
        put(bag, 4, 11, 999, 4, 3);
        assertThat(bag.mergeAndCompact(true, catalog).changed()).isFalse();

        Bag mixed = new Bag(BagType.INVENTORY, 10);
        put(mixed, 1, 11, 1, 1, 0);
        put(mixed, 2, 11, 999, 2, 1);
        put(mixed, 3, 10, 1, 3, 2);
        put(mixed, 4, 10, 999, 4, 3);
        put(mixed, 5, 9, 1, 5, 4);
        put(mixed, 6, 9, 2, 6, 5);
        assertThat(mixed.mergeAndCompact(true, catalog).changed()).isTrue();
        assertThat(configsBySlot(mixed)).containsExactly(9, 9, 10, 10, 11, 11);
        assertThat(sizesBySlot(mixed)).containsExactly(2L, 1L, 999L, 1L, 999L, 1L);
        assertThat(mixed.mergeAndCompact(true, catalog).changed()).as("第二次是空操作").isFalse();
    }

    @Test
    void 不可叠加的不合并() {
        Bag bag = new Bag(BagType.INVENTORY, 30);
        for (int i = 0; i < 10; i++) {
            put(bag, 100 + i, 10, 1, i + 1, i);
            put(bag, 200 + i, 1, 1, 11 + i, 10 + i);
        }
        bag.mergeAndCompact(true, catalog);
        assertThat(bag.itemCount()).isEqualTo(11);
    }

    @Test
    void 只合并不重排_格子不挪_零头合并留空洞() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        put(bag, 1, 11, 1, 1, 0);
        put(bag, 2, 10, 1, 2, 1);
        put(bag, 3, 10, 1, 3, 2);

        Bag.SortResult result = bag.mergeAndCompact(false, catalog);

        assertThat(result.retired()).extracting(Removed::guid).containsExactly(3L);
        assertThat(bag.total(10)).isEqualTo(2);
        assertThat(bag.item(1).slot()).isZero();
        assertThat(bag.item(2).slot()).isEqualTo(1);

        Bag unordered = new Bag(BagType.INVENTORY, 10);
        put(unordered, 1, 11, 1, 1, 0);
        put(unordered, 2, 10, 1, 2, 1);
        assertThat(unordered.mergeAndCompact(false, catalog).changed()).isFalse();
        assertThat(unordered.mergeAndCompact(true, catalog).changed()).isTrue();
        assertThat(configsBySlot(unordered)).containsExactly(10, 11);
    }

    @Test
    void 稀疏的格子整理后压实_再整理一次不变() {
        Bag bag = new Bag(BagType.INVENTORY, 100);
        put(bag, 992, 1, 1, 1, 7);
        put(bag, 991, 2, 1, 2, 17);

        assertThat(bag.mergeAndCompact(true, catalog).changed()).isTrue();
        assertThat(bag.item(992).slot()).isZero();
        assertThat(bag.item(991).slot()).isEqualTo(1);
        assertThat(bag.mergeAndCompact(true, catalog).changed()).isFalse();
    }

    @Test
    void 同配置同数量的平局按入包先后_结果确定() {
        Bag bag = new Bag(BagType.INVENTORY, 10);
        put(bag, 50, 1, 1, 3, 0);
        put(bag, 40, 1, 1, 1, 1);
        put(bag, 60, 1, 1, 2, 2);
        put(bag, 10, 0, 1, 4, 3);

        bag.mergeAndCompact(true, catalog);

        assertThat(bag.items()).extracting(BagItem::guid).containsExactly(10L, 40L, 60L, 50L);
    }
}
