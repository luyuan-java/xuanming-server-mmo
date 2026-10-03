package com.game.scene.player;

import com.google.protobuf.UnknownFieldSet;
import java.util.Comparator;

/**
 * 背包里的一个物品实例（只在场景逻辑线程上读写）。guid、配置、入包序号不变；堆叠数与所在格子由 {@link Bag} 维护。
 * 存档里本版本不认识的字段（以后的强化、绑定……）原样带回。
 */
public final class BagItem {

    /** 实例序：入包先后，平局按 guid（无符号）。并堆、合并分组、淘汰都按它（基线依赖 EnTT 遍历序，未定义）。 */
    static final Comparator<BagItem> ACQUIRE_ORDER = Comparator.comparingLong(BagItem::acquireSeq)
            .thenComparing(BagItem::guid, Long::compareUnsigned);

    private final long guid;
    private final int configId;
    private final long acquireSeq;
    private final UnknownFieldSet unknownFields;
    private long size;
    private int slot;

    BagItem(long guid, int configId, long size, long acquireSeq, UnknownFieldSet unknownFields) {
        this.guid = guid;
        this.configId = configId;
        this.size = size;
        this.acquireSeq = acquireSeq;
        this.unknownFields = unknownFields;
        this.slot = -1;
    }

    public long guid() {
        return guid;
    }

    public int configId() {
        return configId;
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
