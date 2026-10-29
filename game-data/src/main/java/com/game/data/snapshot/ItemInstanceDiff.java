package com.game.data.snapshot;

import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 同 UUID 的完整实例差异：强化、宝石、位置及未知字段都属于回档将覆盖的内容。 */
public final class ItemInstanceDiff {
    public record Result(int changedCount, List<Map<String, Object>> instances, boolean truncated) { }

    private ItemInstanceDiff() { }

    /** 按 UUID 匹配，不把存档中的列表重排算成改动；路径级明细总共至多 500 条。 */
    public static Result compare(BagState snapshot, BagState current) {
        Map<Long, BagItemState> before = byUuid(snapshot);
        Map<Long, BagItemState> after = byUuid(current);
        StateDiff diff = new StateDiff();
        List<Map<String, Object>> instances = new ArrayList<>();
        int changedCount = 0;
        for (var entry : before.entrySet()) {
            BagItemState now = after.get(entry.getKey());
            if (now == null || entry.getValue().equals(now)) continue;
            changedCount++;
            int start = diff.changes().size();
            String uuid = Long.toUnsignedString(entry.getKey());
            diff.compare("bag.items{" + uuid + "}", entry.getValue(), now);
            if (diff.changes().size() == start) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemUuid", uuid);
            item.put("changes", diff.changes().subList(start, diff.changes().size()).stream().map(change -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", change.path());
                row.put("snapshot", change.snapshot());
                row.put("current", change.current());
                return row;
            }).toList());
            instances.add(item);
        }
        return new Result(changedCount, List.copyOf(instances), diff.truncated());
    }

    private static Map<Long, BagItemState> byUuid(BagState bag) {
        Map<Long, BagItemState> out = new TreeMap<>(Long::compareUnsigned);
        bag.getItemsList().forEach(item -> out.putIfAbsent(item.getItemUuid(), item));
        return out;
    }
}
