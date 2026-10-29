package com.game.gateway.store;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 仅显式用于 zone 列；不注册为普通 Integer 的全局映射。 */
public final class UnsignedZoneIdTypeHandler extends BaseTypeHandler<Integer> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Integer bits, JdbcType type)
            throws SQLException {
        statement.setLong(index, Integer.toUnsignedLong(bits));
    }

    private static Integer checked(long value, boolean wasNull) throws SQLException {
        if (wasNull) {
            throw new SQLException("zone_id 不得为 SQL NULL", "22004");
        }
        if (value < 0 || value > 0xffff_ffffL) {
            throw new SQLException("zone_id 超出 uint32 范围", "22003");
        }
        return (int) value;
    }

    @Override
    public Integer getNullableResult(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return checked(value, rows.wasNull());
    }

    @Override
    public Integer getNullableResult(ResultSet rows, int column) throws SQLException {
        long value = rows.getLong(column);
        return checked(value, rows.wasNull());
    }

    @Override
    public Integer getNullableResult(CallableStatement statement, int column) throws SQLException {
        long value = statement.getLong(column);
        return checked(value, statement.wasNull());
    }
}
