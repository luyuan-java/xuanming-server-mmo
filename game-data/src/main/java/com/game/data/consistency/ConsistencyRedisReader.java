package com.game.data.consistency;

import java.util.List;

/** 只读端口：不能获取业务目录对象，避免其读方法隐式prune。 */
public interface ConsistencyRedisReader {
    record Page(String cursor, List<String> values) { public Page { values = List.copyOf(values); } }
    boolean completeTopology();
    Page scanRankKeys(String cursor, int count, ConsistencyBudget budget);
    Page scanRankZones(String cursor, int count, ConsistencyBudget budget);
    String type(String key, ConsistencyBudget budget);
}
