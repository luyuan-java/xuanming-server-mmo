package com.game.scene.bag;

import com.game.scene.player.BagType;
import com.game.scene.player.ItemCatalog;
import com.game.table.ConfigTables;
import com.game.table.EquipSlotTable;
import com.game.table.ItemTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 背包用到的配表视图（Item / EquipSlot）。不可变，加载后任意线程可读。
 *
 * <p>EquipSlot 每行一个装备槽（{@code id} = 槽号 = 存档里的格子号，{@code equip_kind} = 部位），同部位多行 = 多个槽；
 * 按部位建索引、保持表序（同基线 FindFreeSlotForKind 按表文件行序取第一个空槽）。槽号不小于装备栏缺省容量、部位为 0 的行
 * 永远用不上——同基线静默不用，只在加载时告警（不拒绝启动：表是从 mmorpg 同步来的契约）。
 */
public final class BagTables implements ItemCatalog {

    private static final Logger log = LoggerFactory.getLogger(BagTables.class);

    private final Map<Integer, ItemSpec> items;
    private final Map<Integer, List<Integer>> slotsByKind;

    BagTables(Map<Integer, ItemSpec> items, Map<Integer, List<Integer>> slotsByKind) {
        this.items = Map.copyOf(items);
        Map<Integer, List<Integer>> slots = new HashMap<>();
        slotsByKind.forEach((kind, list) -> slots.put(kind, List.copyOf(list)));
        this.slotsByKind = Map.copyOf(slots);
    }

    public static BagTables from(ConfigTables tables) {
        Map<Integer, ItemSpec> items = new HashMap<>();
        for (ItemTable row : tables.item().all()) {
            items.put(row.getId(), new ItemSpec(row.getId(), row.getMaxStackSize(), row.getEquipKind()));
            if (row.getMaxStackSize() == 0) {
                log.warn("物品表 max_stack_size 为 0，这种物品入包一律回 1002 id={}", Integer.toUnsignedString(row.getId()));
            }
        }
        Map<Integer, List<Integer>> slotsByKind = new HashMap<>();
        for (EquipSlotTable row : tables.equipSlot().all()) {
            if (row.getEquipKind() == 0 || Integer.compareUnsigned(row.getId(), BagType.EQUIPMENT.defaultCapacity()) >= 0) {
                log.warn("装备槽表的这一行永远用不上（部位为 0 或槽号不小于装备栏容量 {}） id={} equip_kind={}",
                        BagType.EQUIPMENT.defaultCapacity(), Integer.toUnsignedString(row.getId()),
                        Integer.toUnsignedString(row.getEquipKind()));
            }
            if (row.getEquipKind() != 0) {
                slotsByKind.computeIfAbsent(row.getEquipKind(), k -> new ArrayList<>()).add(row.getId());
            }
        }
        return new BagTables(items, slotsByKind);
    }

    @Override
    public ItemSpec item(int configId) {
        return items.get(configId);
    }

    @Override
    public List<Integer> equipSlots(int equipKind) {
        return slotsByKind.getOrDefault(equipKind, List.of());
    }
}
