package com.game.discovery.sql;

import com.game.common.deadline.Deadline;
import java.sql.SQLException;

/** SQL号源只消费领域自己的计数表；响应未知可留空号，不回退到另一号源。 */
public final class AuthorityIds {
    private final AuthorityJdbc jdbc;
    private final String table;
    public AuthorityIds(AuthorityJdbc jdbc, String table) {
        if (!table.matches("(?:mail|rank)_id_sequence"))
            throw new IllegalArgumentException("未知领域号源");
        this.jdbc = jdbc;
        this.table = table;
    }
    public long next(Deadline budget) throws SQLException {
        return reserve(budget, 1);
    }
    /** 一次事务预留有限连续区间；未用号可留空，不逐个跨区往返。 */
    public long reserve(Deadline budget, int count) throws SQLException {
        if (count < 1 || count > 1001)
            throw new IllegalArgumentException("领域ID预留数量无效");
        return jdbc.tx(budget, s -> {
            s.update("INSERT IGNORE INTO " + table + " (sequence_key,next_value) VALUES (1,1)");
            Long value = s.one("SELECT next_value FROM " + table + " WHERE sequence_key=1 FOR UPDATE", r -> AuthorityJdbc.u64(r, 1));
            if (value == null || value == 0 || Long.compareUnsigned(value, -1L - count) >= 0)
                throw new SQLException("领域ID号源耗尽");
            s.update("UPDATE " + table + " SET next_value=? WHERE sequence_key=1", AuthorityJdbc.u64(value + count));
            return value;
        });
    }
}
