package com.game.scene.bag;

import com.game.scene.player.ItemCatalog.ItemSpec;
import java.util.List;
import java.util.Map;

/** 测试用：让 bag 包外的测试用小表构造 {@link BagTables}（构造器在生产代码里是包内可见）。 */
public final class TestBagTables {

    private TestBagTables() {
    }

    public static BagTables of(Map<Integer, ItemSpec> items, Map<Integer, List<Integer>> equipSlots) {
        return new BagTables(items, equipSlots);
    }
}
