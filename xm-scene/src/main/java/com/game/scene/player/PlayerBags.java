package com.game.scene.player;

import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.scene.player.ItemCatalog.ItemSpec;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家的四个固定背包（只在场景逻辑线程上读写）。
 *
 * <p>加载分两步：构造玩家实例时 {@link #restore} 只原样收下存档（不能抛异常——构造在进场失败处理之外）；
 * 进场景前由背包服务调 {@link #normalize} 按配表规整，结构性损坏（guid 为 0 / 全 1、guid 重复、物品数超过容量、容量离谱）抛异常，
 * 按进场失败处理（fail-closed，资产不会被悄悄丢掉再存回去）。规整的宽容部分同基线：格子越界 / 撞格子的重新落位（记 ERROR），
 * 表里查不到的配置照样保留。本版本不认识的 bag_type 的条目与容量原样隔离、写回。
 *
 * <p>与基线的差异（有意）：基线物品数超过容量、guid 重复、bag_type 越界时<b>丢物品</b>，Java 拒绝进场或原样保留；
 * 装备栏优先用存档里的格子（若正好是本部位的空槽），再退回第一个同部位空槽（基线每次登录可能让同部位两件互换）；
 * 序号为 0 的老数据按出现顺序接在已有最大序号之后补盖（基线可能盖出重复序号）；容量只在不是缺省值时才存（缺省的写 0）；
 * 数量为 0 的条目丢弃、不显示（基线原样还原成 count=0 的「僵尸堆」，数量 0 不是资产）。
 */
public final class PlayerBags {

    private static final Logger log = LoggerFactory.getLogger(PlayerBags.class);

    /** 容量上限（防坏数据撑爆内存；远大于任何合法值）。 */
    static final int MAX_CAPACITY = 10_000;

    private final EnumMap<BagType, Bag> bags = new EnumMap<>(BagType.class);
    /** 本版本不认识的 bag_type 的条目（更新版本写的），原样写回。 */
    private final List<BagItemState> quarantine = new ArrayList<>();
    /** 本版本不认识的背包的容量（capacities 下标 4 起），原样写回。 */
    private final List<Integer> extraCapacities = new ArrayList<>();
    /** 还没规整的原样存档；规整后为 null。 */
    private BagState raw;
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerBags(BagState raw) {
        this.raw = raw;
        for (BagType type : BagType.values()) {
            bags.put(type, new Bag(type, type.defaultCapacity()));
        }
    }

    /** 新号：四个空包，缺省容量。 */
    public static PlayerBags empty() {
        return new PlayerBags(null);
    }

    /** 从存档恢复：只原样收下，不校验（见 {@link #normalize}）。 */
    public static PlayerBags restore(BagState state) {
        return new PlayerBags(state);
    }

    public Bag bag(BagType type) {
        if (raw != null) {
            throw new IllegalStateException("背包还没规整（进场初始化没跑）");
        }
        return bags.get(type);
    }

    /** 已经规整过（或本来就是新号）。 */
    public boolean normalized() {
        return raw == null;
    }

    /**
     * 按配表把存档规整成四个背包（幂等）。
     *
     * @throws IllegalStateException 存档结构性损坏
     */
    public void normalize(ItemCatalog catalog) {
        if (raw == null) {
            return;
        }
        BagState state = raw;
        EnumMap<BagType, Integer> capacities = new EnumMap<>(BagType.class);
        for (BagType type : BagType.values()) {
            int stored = type.code() < state.getCapacitiesCount() ? state.getCapacities(type.code()) : 0;
            if (stored != 0 && Integer.compareUnsigned(stored, MAX_CAPACITY) > 0) {
                throw new IllegalStateException("背包容量离谱 bag_type=" + type.code() + " capacity="
                        + Integer.toUnsignedString(stored));
            }
            capacities.put(type, stored == 0 ? type.defaultCapacity() : stored);
        }
        EnumMap<BagType, List<BagItemState>> entries = new EnumMap<>(BagType.class);
        List<BagItemState> unknownBags = new ArrayList<>();
        Set<Long> guids = new HashSet<>();
        for (BagItemState entry : state.getItemsList()) {
            long guid = entry.getItemUuid();
            if (guid == 0 || guid == -1L) {
                throw new IllegalStateException("物品 guid 非法 " + Long.toUnsignedString(guid));
            }
            if (!guids.add(guid)) {
                throw new IllegalStateException("物品 guid 重复 " + Long.toUnsignedString(guid));
            }
            BagType type = BagType.ofCode(entry.getBagType());
            if (type == null) {
                unknownBags.add(entry);
                continue;
            }
            if (entry.getStackSize() == 0) {
                log.warn("存档里有数量为 0 的物品实例，丢弃 guid={} config={}", Long.toUnsignedString(guid),
                        Integer.toUnsignedString(entry.getConfigId()));
                continue;
            }
            entries.computeIfAbsent(type, t -> new ArrayList<>()).add(entry);
        }
        EnumMap<BagType, Bag> restored = new EnumMap<>(BagType.class);
        for (BagType type : BagType.values()) {
            List<BagItemState> list = entries.getOrDefault(type, List.of());
            int capacity = capacities.get(type);
            if (list.size() > capacity) {
                throw new IllegalStateException("背包物品数超过容量 bag_type=" + type.code() + " items=" + list.size()
                        + " capacity=" + capacity);
            }
            restored.put(type, restoreBag(type, capacity, list, catalog));
        }
        bags.putAll(restored);
        quarantine.addAll(unknownBags);
        if (!unknownBags.isEmpty()) {
            log.warn("存档里有本版本不认识的背包的物品 {} 件，原样保留、写回", unknownBags.size());
        }
        if (state.getCapacitiesCount() > BagType.values().length) {
            extraCapacities.addAll(state.getCapacitiesList().subList(BagType.values().length, state.getCapacitiesCount()));
        }
        unknownFields = state.getUnknownFields();
        raw = null;
    }

    private static Bag restoreBag(BagType type, int capacity, List<BagItemState> entries, ItemCatalog catalog) {
        Bag bag = new Bag(type, capacity);
        long maxSeq = 0;
        for (BagItemState entry : entries) {
            if (Long.compareUnsigned(entry.getAcquireSeq(), maxSeq) > 0) {
                maxSeq = entry.getAcquireSeq();
            }
        }
        List<BagItem> items = new ArrayList<>(entries.size());
        List<Integer> savedSlots = new ArrayList<>(entries.size());
        long nextSeq = maxSeq + 1;
        for (BagItemState entry : entries) {
            long seq = entry.getAcquireSeq() != 0 ? entry.getAcquireSeq() : nextSeq++;
            items.add(new BagItem(entry.getItemUuid(), entry.getConfigId(), Integer.toUnsignedLong(entry.getStackSize()),
                    seq, entry.getUnknownFields()));
            savedSlots.add(entry.getPos());
        }
        // 第一轮：存档里的格子能用就用（装备栏还要是本部位的槽）；第二轮：其余的重新落位
        boolean[] placed = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            int slot = savedSlots.get(i);
            if (bag.isFree(slot) && (type.layout() == BagType.Layout.FLAT
                    || kindSlot(items.get(i), slot, catalog))) {
                bag.place(items.get(i), slot);
                placed[i] = true;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            if (placed[i]) {
                continue;
            }
            BagItem item = items.get(i);
            int slot = -1;
            if (type.layout() == BagType.Layout.SLOTTED) {
                ItemSpec spec = catalog.item(item.configId());
                slot = spec == null ? -1 : bag.firstFreeSlotOfKind(spec.equipKind(), catalog);
                if (slot < 0 && bag.isFree(savedSlots.get(i))) {
                    slot = savedSlots.get(i);
                }
            }
            if (slot < 0) {
                slot = bag.firstFreeCell();
            }
            log.error("物品存档格子不可用，重新落位 bag_type={} {} 存档格子={} 新格子={}", type.code(), item,
                    Integer.toUnsignedString(savedSlots.get(i)), slot);
            bag.place(item, slot);
        }
        bag.raiseAcquireSeq(nextSeq - 1);
        return bag;
    }

    private static boolean kindSlot(BagItem item, int slot, ItemCatalog catalog) {
        ItemSpec spec = catalog.item(item.configId());
        return spec != null && spec.equipKind() != 0 && catalog.equipSlots(spec.equipKind()).contains(slot);
    }

    /** 从没动过（四个空包、缺省容量、没有隔离条目与不认识的字段）：持久化时整段省略。 */
    public boolean isPristine() {
        if (raw != null) {
            return raw.equals(BagState.getDefaultInstance());
        }
        for (Map.Entry<BagType, Bag> e : bags.entrySet()) {
            if (e.getValue().itemCount() != 0 || e.getValue().capacity() != e.getKey().defaultCapacity()) {
                return false;
            }
        }
        return quarantine.isEmpty() && extraCapacities.isEmpty() && unknownFields.asMap().isEmpty();
    }

    /** 存档形态：按 (bag_type, 格子) 升序写出，隔离条目原样排在最后；没规整过就原样返回。 */
    public BagState toState() {
        if (raw != null) {
            return raw;
        }
        BagState.Builder state = BagState.newBuilder().setUnknownFields(unknownFields);
        boolean customCapacity = bags.entrySet().stream()
                .anyMatch(e -> e.getValue().capacity() != e.getKey().defaultCapacity());
        if (customCapacity || !extraCapacities.isEmpty()) {
            // 缺省容量写 0（= 取当前版本的缺省），以后调高缺省对这些包仍然生效；不认识的包的容量原样接在后面
            for (BagType type : BagType.values()) {
                int capacity = bags.get(type).capacity();
                state.addCapacities(capacity == type.defaultCapacity() ? 0 : capacity);
            }
            state.addAllCapacities(extraCapacities);
        }
        for (BagType type : BagType.values()) {
            for (BagItem item : bags.get(type).items()) {
                state.addItems(BagItemState.newBuilder()
                        .setItemUuid(item.guid())
                        .setConfigId(item.configId())
                        .setStackSize((int) item.size())
                        .setPos(item.slot())
                        .setBagType(type.code())
                        .setAcquireSeq(item.acquireSeq())
                        .setUnknownFields(item.unknownFields()));
            }
        }
        state.addAllItems(quarantine);
        return state.build();
    }
}
