package com.game.scene.player;

import com.google.protobuf.UnknownFieldSet;
import com.game.player.store.state.EquipmentState;
import java.util.Comparator;

/**
 * 背包里的一个物品实例（只在场景逻辑线程上读写）。guid、配置、入包序号不变；堆叠数与所在格子由 {@link Bag} 维护。
 * 存档里本版本不认识的字段（以后的强化、绑定……）原样带回。
 */
public final class BagItem {

    /** 实例序：入包先后，平局按 guid；两者均按 uint64 无符号比较，与 C++ 并堆、合并和淘汰同口径。 */
    static final Comparator<BagItem> ACQUIRE_ORDER = Comparator.comparing(BagItem::acquireSeq, Long::compareUnsigned)
            .thenComparing(BagItem::guid, Long::compareUnsigned);

    private final long guid;
    private int configId;
    private final long acquireSeq;
    private boolean bound;
    private final UnknownFieldSet unknownFields;
    private long size;
    private int slot;
    private EquipmentState equipment;

    BagItem(long guid, int configId, long size, long acquireSeq, UnknownFieldSet unknownFields) {
        this(guid, configId, size, acquireSeq, false, unknownFields);
    }

    BagItem(long guid, int configId, long size, long acquireSeq, boolean bound, UnknownFieldSet unknownFields) {
        this.guid = guid;
        this.configId = configId;
        this.size = size;
        this.acquireSeq = acquireSeq;
        this.bound = bound;
        this.unknownFields = unknownFields;
        this.slot = -1;
    }

    /** 仅自用绑定标记：true = 不可交易/寄售/赠送，且与可交易堆严格分堆隔离。 */
    public boolean bound() {
        return bound;
    }

    public void setBound(boolean bound) {
        this.bound = bound;
    }

    /** 不可变存档值；null 与空值严格区分，空值代表已完成初始化。 */
    public EquipmentState equipment() { return equipment; }

    public void equipment(EquipmentState equipment) { this.equipment = equipment; }

    public long guid() {
        return guid;
    }

    public int configId() {
        return configId;
    }

    /** V1 装备原地无损升档专用（仅允许在单件装备校验通过后升至同部位更高档模板，不改 guid/槽位/入包序）。 */
    public void setConfigId(int configId) {
        this.configId = configId;
    }

    /** 堆叠数（uint32 范围内，long 表示）。 */
    public long size() {
        return size;
    }

    public long acquireSeq() {
        return acquireSeq;
    }

    /** 所在格子（0 起）。 */
    public int slot() {
        return slot;
    }

    UnknownFieldSet unknownFields() {
        return unknownFields;
    }

    void size(long size) {
        this.size = size;
    }

    void slot(int slot) {
        this.slot = slot;
    }

    @Override
    public String toString() {
        return "BagItem[guid=" + Long.toUnsignedString(guid) + " config=" + Integer.toUnsignedString(configId) + " size="
                + size + " slot=" + slot + " seq=" + acquireSeq + "]";
    }
}
