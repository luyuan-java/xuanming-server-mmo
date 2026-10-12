package com.game.data.store;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** uint32 ↔ {@code INT UNSIGNED}：Java 把 uint32 按位放在 int 里，≥ 2^31 的值是负数，按无符号 long 绑定、读回后按位放回 int。 */
public final class UnsignedIntTypeHandler extends BaseTypeHandler<Integer> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Integer value, JdbcType jdbcType) throws SQLException {
        ps.setLong(i, Integer.toUnsignedLong(value));
    }

    @Override
    public Integer getNullableResult(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : (int) value;
    }

    @Override
    public Integer getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        long value = rs.getLong(columnIndex);
        return rs.wasNull() ? null : (int) value;
    }

    @Override
    public Integer getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        long value = cs.getLong(columnIndex);
        return cs.wasNull() ? null : (int) value;
    }
}
