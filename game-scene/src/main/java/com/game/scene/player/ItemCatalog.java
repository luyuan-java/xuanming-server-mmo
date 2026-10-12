package com.game.scene.player;

import java.util.List;

/** 背包规则要读的物品配表（Item / EquipSlot）。不可变，任意线程可读；生产实现是 {@code BagTables}。 */
public interface ItemCatalog {

    /**
     * 一种物品的规则。
     *
     * @param maxStackSize 堆叠上限（1 = 不可叠加，0 = 表数据错误；按无符号看）
     * @param equipKind    部位（0 = 不是装备）
     */
    record ItemSpec(int configId, int maxStackSize, int equipKind) {

        public boolean stackable() {
            return Integer.compareUnsigned(maxStackSize, 1) > 0;
        }

        /** 堆叠上限（无符号，long 表示）。 */
        public long maxStack() {
            return Integer.toUnsignedLong(maxStackSize);
        }
    }

    /** 物品规则；表里没有为 null。 */
    ItemSpec item(int configId);

    /** 某部位的装备槽号（EquipSlot.id），按表序；没有为空列表。 */
    List<Integer> equipSlots(int equipKind);
}
