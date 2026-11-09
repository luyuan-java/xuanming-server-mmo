package com.game.data.consistency;

import java.sql.Connection;
import java.sql.SQLException;

/** 只替换 H2 测试连接的数据库毫秒时钟，不改生产查询、业务谓词、绑定参数或结果判定。 */
final class H2ConsistencySql {
    private static final String MYSQL_MILLIS = "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)";
    // H2 的 UNIX_TIMESTAMP 返回 INTEGER 且丢失秒小数，外层 CAST 无法避免先乘 1000 溢出。
    // 沿用 MallNonceStore 的 EPOCH 方案：带时区的数据库 CURRENT_TIMESTAMP(3)，精确毫秒，BIGINT 结果。
    private static final String H2_MILLIS = "CAST(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP(3))*1000 AS BIGINT)";

    private H2ConsistencySql() { }

    static String forDatabase(Connection connection, String sql) throws SQLException {
        return "H2".equals(connection.getMetaData().getDatabaseProductName())
                ? sql.replace(MYSQL_MILLIS, H2_MILLIS) : sql;
    }
}
