package com.game.scene.player;

import com.game.scene.player.ItemCatalog.ItemSpec;
import com.game.table.BagErrorTip;
import com.game.table.CommonErrorTip;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 一个背包（只在场景逻辑线程上读写）：实例（guid → 物品）+ 格子（格子号 → 物品），两层始终一致，实例数 ≤ 容量。
 * 规则同 mmorpg {@code bag_system.cpp}，入口只有生产在用的那几个：批量入包（规划 → 铸号 → 淘汰 → 写入）、整理（合并 + 重排）。
 *
 * <ul>
 *   <li>堆叠：同配置先填既有未满堆，剩余按堆叠上限切新实例；不可叠加物品每件一个实例。</li>
 *   <li>扁平布局一个实例一格、first-fit；装备栏按部位分桶（Item.equip_kind ↔ EquipSlot 行，槽号 &lt; 容量且空着），
 *       同部位的几种配置共享槽位预算，部位为 0 的物品进不了装备栏。</li>
 *   <li>临时格满了淘汰最早入包的实例：先算到不动点（被挤掉的可能正是要并入的未满堆）再动手；腾不够一件都不动。</li>
 *   <li>整批原子：任何检查不过都在改状态之前返回（零写入、零淘汰）。号先一次铸齐，铸不出来回 6004。</li>
 * </ul>
 *
 * <p>与基线的差异（有意）：遍历顺序确定（基线依赖 EnTT 遍历序，未定义）——并堆、合并分组、淘汰都按 {@link BagItem#ACQUIRE_ORDER}，
 * 批量按配置号升序；扣空的实例当场回收，不留数量为 0 的「僵尸堆」。
 */
public final class Bag {

    static final int OK = 0;
    /** 物品表里没有这个配置（基线 LookupItemOrReturnError 回的码）。 */
    public static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    /** 表数据错误（堆叠上限为 0）。 */
    public static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    /** 参数错误（数量为 0 或越界）、物品 guid 发不出来。 */
    public static final int INVALID_PARAM = BagErrorTip.bag_error.kBagAddItemInvalidParam_VALUE;
    /** 放不下（基线批量入包的满包码，名字起错了但语义就是空间不足）。 */
    public static final int NO_SPACE = BagErrorTip.bag_error.kBagItemNotStacked_VALUE;

    private static final long MAX_COUNT = 0xFFFF_FFFFL;

    private final BagType type;
    private final BagItem[] cells;
    private final Map<Long, BagItem> byGuid = new HashMap<>();
    private long nextAcquireSeq = 1;

    Bag(BagType type, int capacity) {
        this.type = type;
        this.cells = new BagItem[capacity];
    }

    public BagType type() {
        return type;
    }

    public int capacity() {
        return cells.length;
    }

    public int itemCount() {
        return byGuid.size();
    }

    public int freeCells() {
        return cells.length - byGuid.size();
    }

    public BagItem item(long guid) {
        return byGuid.get(guid);
    }

    /** 全部实例，按格子号升序。 */
    public List<BagItem> items() {
        List<BagItem> out = new ArrayList<>(byGuid.size());
        for (BagItem item : cells) {
            if (item != null) {
                out.add(item);
            }
        }
        return out;
    }

    /** 某配置的持有总量。 */
    public long total(int configId) {
        long total = 0;
        for (BagItem item : byGuid.values()) {
            if (item.configId() == configId) {
                total += item.size();
            }
        }
        return total;
    }

    // ================================================================ 入包

    /**
     * 一个配置的写入回执。
     *
     * @param guids 写到的实例：先是并入的既有堆（按实例序），再是新切出来的实例；第一个即基线 PrimaryWrittenGuid
     */
    public record Written(int configId, long count, List<Long> guids) {
    }

    /** 被淘汰（销毁）的实例：数量是销毁前的真实堆叠数。 */
    public record Removed(long guid, int configId, long size) {
    }

    /** 入包结果：{@code tip} 为 0 表示整批写入；否则什么都没改（{@code written} 与 {@code evicted} 都为空）。 */
    public record AddResult(int tip, List<Written> written, List<Removed> evicted) {

        static AddResult rejected(int tip) {
            return new AddResult(tip, List.of(), List.of());
        }

        public boolean ok() {
            return tip == OK;
        }
    }

    /** 一个配置的规划：并入哪些既有堆、切几个新实例。 */
    private record Need(int configId, long count, ItemSpec spec, List<BagItem> fillTargets, int newInstances) {
    }

    private record Plan(int tip, List<Need> needs, int newInstances) {
    }

    /**
     * 批量入包（基线 BagService::AddItems 的容器部分：ReserveForBatchAdd + 逐配置 AddItem）。整批原子。
     *
     * @param counts 配置号 → 数量（每项 1 .. 2^32−1）
     * @return 0 = 成功；6004 数量非法或发不出号；1001 表里没有；1002 堆叠上限为 0；6006 放不下（含装备栏部位不符）
     */
    public AddResult add(Map<Integer, Long> counts, ItemCatalog catalog, ItemGuids guids) {
        TreeMap<Integer, Long> ordered = new TreeMap<>(Integer::compareUnsigned);
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            long count = e.getValue();
            if (count <= 0 || count > MAX_COUNT) {
                return AddResult.rejected(INVALID_PARAM);
            }
            ordered.put(e.getKey(), count);
        }
        if (ordered.isEmpty()) {
            return new AddResult(OK, List.of(), List.of());
        }
        Map<Integer, ItemSpec> specs = new LinkedHashMap<>();
        for (int configId : ordered.keySet()) {
            ItemSpec spec = catalog.item(configId);
            if (spec == null) {
                return AddResult.rejected(INVALID_TABLE_ID);
            }
            if (spec.maxStackSize() == 0) {
                return AddResult.rejected(INVALID_TABLE_DATA);
            }
            specs.put(configId, spec);
        }
        if (type.layout() == BagType.Layout.SLOTTED) {
            // 部位为 0 的物品进不了装备栏（基线 CanReserve 判否 → 6006）；同基线排在全部查表（1001 / 1002）之后
            for (ItemSpec spec : specs.values()) {
                if (spec.equipKind() == 0) {
                    return AddResult.rejected(NO_SPACE);
                }
            }
        }

        // 淘汰的不动点：先假设不淘汰；放不下时挤掉最早的若干个再重新规划（被挤掉的未满堆不能再并入），直到够用或无可再挤
        List<BagItem> fifo = type.evictsOldest() ? sortedItems(BagItem.ACQUIRE_ORDER) : List.of();
        int victimCount = 0;
        Plan plan;
        while (true) {
            Set<BagItem> victims = identitySet(fifo.subList(0, victimCount));
            plan = plan(ordered, specs, victims);
            if (fits(plan, victims.size(), catalog)) {
                break;
            }
            if (!type.evictsOldest()) {
                return AddResult.rejected(NO_SPACE);
            }
            int deficit = plan.newInstances() - freeCells();
            if (deficit > fifo.size() || deficit <= victimCount) {
                return AddResult.rejected(NO_SPACE);
            }
            victimCount = deficit;
        }

        long[] minted = new long[0];
        if (plan.newInstances() > 0) {
            minted = guids.tryMint(plan.newInstances());
            if (minted == null || minted.length != plan.newInstances()) {
                return AddResult.rejected(INVALID_PARAM);
            }
        }

        // 以下才改状态：先淘汰，再并堆，再切新实例
        List<Removed> evicted = new ArrayList<>(victimCount);
        for (BagItem victim : fifo.subList(0, victimCount)) {
            evicted.add(new Removed(victim.guid(), victim.configId(), victim.size()));
            remove(victim);
        }
        List<Written> written = new ArrayList<>(plan.needs().size());
        int nextGuid = 0;
        for (Need need : plan.needs()) {
            List<Long> touched = new ArrayList<>();
            long remaining = need.count();
            long max = need.spec().maxStack();
            for (BagItem target : need.fillTargets()) {
                if (remaining == 0) {
                    break;
                }
                long add = Math.min(max - target.size(), remaining);
                target.size(target.size() + add);
                remaining -= add;
                touched.add(target.guid());
            }
            while (remaining > 0) {
                long size = Math.min(max, remaining);
                BagItem item = new BagItem(minted[nextGuid++], need.configId(), size, nextAcquireSeq++,
                        UnknownFieldSet.getDefaultInstance());
                place(item, slotForNew(need.spec(), catalog));
                remaining -= size;
                touched.add(item.guid());
            }
            written.add(new Written(need.configId(), need.count(), List.copyOf(touched)));
        }
        return new AddResult(OK, List.copyOf(written), List.copyOf(evicted));
    }

    private Plan plan(TreeMap<Integer, Long> counts, Map<Integer, ItemSpec> specs, Set<BagItem> excluded) {
        List<Need> needs = new ArrayList<>(counts.size());
        long total = 0;
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            ItemSpec spec = specs.get(e.getKey());
            long max = spec.maxStack();
            List<BagItem> targets = new ArrayList<>();
            long room = 0;
            if (spec.stackable()) {
                for (BagItem item : sortedItems(BagItem.ACQUIRE_ORDER)) {
                    if (item.configId() == e.getKey() && item.size() < max && !excluded.contains(item)) {
                        targets.add(item);
                        room += max - item.size();
                    }
                }
            }
            long overflow = Math.max(0, e.getValue() - room);
            long instances = (overflow + max - 1) / max;
            total += instances;
            needs.add(new Need(e.getKey(), e.getValue(), spec, targets, (int) Math.min(instances, Integer.MAX_VALUE)));
        }
        return new Plan(OK, needs, (int) Math.min(total, Integer.MAX_VALUE));
    }

    private boolean fits(Plan plan, int victims, ItemCatalog catalog) {
        if (plan.newInstances() > freeCells() + victims) {
            return false;
        }
        if (type.layout() != BagType.Layout.SLOTTED) {
            return true;
        }
        // 装备栏：按部位汇总需求，每个部位的空槽要够（同部位的几种配置共享预算；不能用空格数判满）
        Map<Integer, Integer> demand = new HashMap<>();
        for (Need need : plan.needs()) {
            demand.merge(need.spec().equipKind(), need.newInstances(), Integer::sum);
        }
        for (Map.Entry<Integer, Integer> e : demand.entrySet()) {
            if (freeSlotsOfKind(e.getKey(), catalog) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private int slotForNew(ItemSpec spec, ItemCatalog catalog) {
        if (type.layout() == BagType.Layout.SLOTTED) {
            return firstFreeSlotOfKind(spec.equipKind(), catalog);
        }
        return firstFreeCell();
    }

    // ================================================================ 整理

    /**
     * 整理结果。
     *
     * @param retired 合并后变空、被回收的实例（数量恒为 0，同基线：堆叠量并进了别的实例，没有资产损失）
     */
    public record SortResult(boolean changed, List<Removed> retired) {

        static final SortResult UNCHANGED = new SortResult(false, List.of());
    }

    /**
     * 合并同配置的零头堆、回收空实例，{@code reorder} 时再按（配置号升序、堆叠数降序）重铺到 0..n-1（基线 MergeAndCompact）。
     * 装备栏不重排。已经最优时什么都不改、返回 changed=false（狂点整理不刷流水、不触发存盘）。
     */
    public SortResult mergeAndCompact(boolean reorder, ItemCatalog catalog) {
        boolean willReorder = reorder && type.compactable();
        Map<Integer, List<BagItem>> partials = partialStacks(catalog);
        boolean mergeable = partials.values().stream().anyMatch(group -> group.size() >= 2);
        boolean hasEmpty = byGuid.values().stream().anyMatch(item -> item.size() == 0);
        if (!mergeable && !hasEmpty && (!willReorder || isCompactAndOrdered())) {
            return SortResult.UNCHANGED;
        }
        for (Map.Entry<Integer, List<BagItem>> e : partials.entrySet()) {
            long max = catalog.item(e.getKey()).maxStack();
            long pool = 0;
            for (BagItem item : e.getValue()) {
                pool += item.size();
            }
            for (BagItem item : e.getValue()) {
                long size = Math.min(max, pool);
                item.size(size);
                pool -= size;
            }
        }
        List<Removed> retired = new ArrayList<>();
        for (BagItem item : sortedItems(BagItem.ACQUIRE_ORDER)) {
            if (item.size() == 0) {
                retired.add(new Removed(item.guid(), item.configId(), 0));
                remove(item);
            }
        }
        if (willReorder) {
            Comparator<BagItem> byConfig = Comparator.comparing(BagItem::configId, Integer::compareUnsigned);
            Comparator<BagItem> bySizeDesc = Comparator.comparingLong(BagItem::size);
            List<BagItem> order = sortedItems(byConfig.thenComparing(bySizeDesc.reversed())
                    .thenComparing(BagItem.ACQUIRE_ORDER));
            java.util.Arrays.fill(cells, null);
            for (int slot = 0; slot < order.size(); slot++) {
                order.get(slot).slot(slot);
                cells[slot] = order.get(slot);
            }
        }
        return new SortResult(true, List.copyOf(retired));
    }

    /** 可叠加、表里有、未满（含空）的堆，按配置分组，组内按实例序。 */
    private Map<Integer, List<BagItem>> partialStacks(ItemCatalog catalog) {
        Map<Integer, List<BagItem>> groups = new TreeMap<>(Integer::compareUnsigned);
        for (BagItem item : sortedItems(BagItem.ACQUIRE_ORDER)) {
            ItemSpec spec = catalog.item(item.configId());
            if (spec != null && spec.stackable() && item.size() < spec.maxStack()) {
                groups.computeIfAbsent(item.configId(), k -> new ArrayList<>()).add(item);
            }
        }
        return groups;
    }

    /** 格子正好占满 0..n-1，且相邻格满足「配置号不降、同配置堆叠数不升」（基线 IsCompact + IsOrderedByConfigThenSize）。 */
    private boolean isCompactAndOrdered() {
        int n = byGuid.size();
        for (int slot = 0; slot < n; slot++) {
            if (cells[slot] == null) {
                return false;
            }
            if (slot > 0) {
                BagItem prev = cells[slot - 1];
                BagItem cur = cells[slot];
                int byConfig = Integer.compareUnsigned(prev.configId(), cur.configId());
                if (byConfig > 0 || (byConfig == 0 && prev.size() < cur.size())) {
                    return false;
                }
            }
        }
        return true;
    }

    // ================================================================ 格子（包内可见：还原规整用）

    /** 物品能否放进 {@code slot}（在容量内且空着）。 */
    boolean isFree(int slot) {
        return slot >= 0 && slot < cells.length && cells[slot] == null;
    }

    /** 第一个空格；没有为 -1。 */
    int firstFreeCell() {
        for (int slot = 0; slot < cells.length; slot++) {
            if (cells[slot] == null) {
                return slot;
            }
        }
        return -1;
    }

    /** 某部位第一个空槽（按 EquipSlot 表序，槽号 &lt; 容量）；部位 0 或没有空槽为 -1。 */
    int firstFreeSlotOfKind(int equipKind, ItemCatalog catalog) {
        if (equipKind == 0) {
            return -1;
        }
        for (int slot : catalog.equipSlots(equipKind)) {
            if (isFree(slot)) {
                return slot;
            }
        }
        return -1;
    }

    int freeSlotsOfKind(int equipKind, ItemCatalog catalog) {
        int free = 0;
        for (int slot : catalog.equipSlots(equipKind)) {
            if (isFree(slot)) {
                free++;
            }
        }
        return free;
    }

    /** 放进一个新实例（调用方保证格子可用）。 */
    void place(BagItem item, int slot) {
        if (!isFree(slot) || byGuid.containsKey(item.guid())) {
            throw new IllegalStateException("背包两层不一致：放不进 " + item + " 到格子 " + slot);
        }
        item.slot(slot);
        cells[slot] = item;
        byGuid.put(item.guid(), item);
    }

    /** 还原时用：入包序号水位抬到已有序号之后。 */
    void raiseAcquireSeq(long seq) {
        if (Long.compareUnsigned(seq, nextAcquireSeq) >= 0) {
            nextAcquireSeq = seq + 1;
        }
    }

    private void remove(BagItem item) {
        cells[item.slot()] = null;
        byGuid.remove(item.guid());
        item.slot(-1);
    }

    private List<BagItem> sortedItems(Comparator<BagItem> order) {
        List<BagItem> list = new ArrayList<>(byGuid.values());
        list.sort(order);
        return list;
    }

    private static Set<BagItem> identitySet(List<BagItem> items) {
        Set<BagItem> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(items);
        return set;
    }
}
