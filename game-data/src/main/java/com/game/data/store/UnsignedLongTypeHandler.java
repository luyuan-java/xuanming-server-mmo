package com.game.data.store;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * uint64 ↔ {@code BIGINT UNSIGNED}：Java 把 uint64 按位放在 long 里，≥ 2^63 的值是负数，直接 setLong 会被 MySQL 严格模式拒绝（22003）、
 * 直接 getLong 读不回来。写时负数按无符号十进制绑定，读时按十进制取回再按位放回 long。
 */
public final class UnsignedLongTypeHandler extends BaseTypeHandler<Long> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Long value, JdbcType jdbcType) throws SQLException {
        if (value >= 0) {
            ps.setLong(i, value);
        } else {
            ps.setBigDecimal(i, new BigDecimal(Long.toUnsignedString(value)));
        }
    }

    @Override
    public Long getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return bits(rs.getBigDecimal(columnName));
    }

    @Override
    public Long getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return bits(rs.getBigDecimal(columnIndex));
    }

    @Override
    public Long getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return bits(cs.getBigDecimal(columnIndex));
    }

    private static Long bits(BigDecimal value) {
        return value == null ? null : value.toBigInteger().longValue();
    }
}
