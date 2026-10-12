package com.game.scene.player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用物品表：与正式表一致的部分——1、2 不可叠加、部位 1（槽 0、1）；3–8 不可叠加；9 上限 2；10、11 上限 999；
 * 部位 2 只有槽 2。另加测试专用的：上限为 0 的坏数据 {@link #ZERO_STACK}，部位 3 只有槽号 12 的 {@link #FAR_SLOT_ITEM}，
 * 以及上限 999 的物品 0（排序平局用例）。
 */
final class TestCatalog implements ItemCatalog {

    static final int ZERO_STACK = 99;
    static final int FAR_SLOT_ITEM = 98;
    static final int FAR_SLOT = 12;

    private final Map<Integer, ItemSpec> items = new HashMap<>();
    private final Map<Integer, List<Integer>> slots = Map.of(1, List.of(0, 1), 2, List.of(2), 3, List.of(FAR_SLOT));

    TestCatalog() {
        items.put(1, new ItemSpec(1, 1, 1));
        items.put(2, new ItemSpec(2, 1, 1));
        for (int id = 3; id <= 8; id++) {
            items.put(id, new ItemSpec(id, 1, 0));
        }
        items.put(9, new ItemSpec(9, 2, 0));
        items.put(10, new ItemSpec(10, 999, 0));
        items.put(11, new ItemSpec(11, 999, 0));
        items.put(0, new ItemSpec(0, 999, 0));
        items.put(ZERO_STACK, new ItemSpec(ZERO_STACK, 0, 0));
        items.put(FAR_SLOT_ITEM, new ItemSpec(FAR_SLOT_ITEM, 1, 3));
    }

    @Override
    public ItemSpec item(int configId) {
        return items.get(configId);
    }

    @Override
    public List<Integer> equipSlots(int equipKind) {
        return slots.getOrDefault(equipKind, List.of());
    }
}
