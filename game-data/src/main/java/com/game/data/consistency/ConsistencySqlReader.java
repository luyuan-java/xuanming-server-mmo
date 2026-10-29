package com.game.data.consistency;

import java.util.List;
import java.util.Map;

/** SQL 入口只接内部固定计划；请求不能传 SQL、表名或 schema。 */
public interface ConsistencySqlReader {
    List<Map<String, Object>> query(String sql, List<Object> parameters, int maxRows, ConsistencyBudget budget);
}
