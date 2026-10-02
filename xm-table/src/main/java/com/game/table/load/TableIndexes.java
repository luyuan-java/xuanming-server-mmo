package com.game.table.load;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 生成代码建多值键 / 索引用的小工具。 */
public final class TableIndexes {

    private static final System.Logger LOG = System.getLogger(TableIndexes.class.getName());

    private TableIndexes() {
    }

    /**
     * 把 {@code row} 挂到 {@code key} 下。同一行对同一个键只挂一次（repeated 列里同一个值出现两次时不重复）：
     * 行按表序逐行处理，所以只需看列表末尾。
     */
    public static <K, V> void add(Map<K, List<V>> index, K key, V row) {
        List<V> rows = index.computeIfAbsent(key, k -> new ArrayList<>());
        if (rows.isEmpty() || rows.get(rows.size() - 1) != row) {
            rows.add(row);
        }
    }

    /**
     * 二级唯一键（{@code cfg_key}，无 {@code cfg_multi}）出现重复取值时告警。导表器只校验主键唯一、不校验二级键，
     * 所以这里不能拒绝启动；查找时按表序取第一行（与 mmorpg C++ 的 {@code emplace} 一致，Go 是后写覆盖）。
     */
    public static void warnDuplicateKeys(String sheet, String column, Set<?> duplicates) {
        if (!duplicates.isEmpty()) {
            LOG.log(Level.WARNING, "配置表 {0} 的唯一键 {1} 有重复取值 {2}，按表序取第一行", sheet, column, duplicates);
        }
    }

    /** 冻结成不可变的映射，列表也不可变。 */
    public static <K, V> Map<K, List<V>> freeze(Map<K, List<V>> index) {
        Map<K, List<V>> frozen = new HashMap<>(index.size() * 2);
        index.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Map.copyOf(frozen);
    }
}
