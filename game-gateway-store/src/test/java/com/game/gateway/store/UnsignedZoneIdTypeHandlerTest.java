package com.game.gateway.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.IntegerTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class UnsignedZoneIdTypeHandlerTest {
    private final UnsignedZoneIdTypeHandler handler = new UnsignedZoneIdTypeHandler();

    @Test
    void 三边界均绑定非负long并由三个结果入口还原位模式() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        CallableStatement call = mock(CallableStatement.class);
        for (long value : new long[] {2147483647L, 2147483648L, 4294967295L}) {
            int bits = (int) value;
            handler.setNonNullParameter(statement, 1, bits, JdbcType.INTEGER);
            verify(statement).setLong(1, value);
            when(rows.getLong("zone_id")).thenReturn(value);
            when(rows.getLong(1)).thenReturn(value);
            when(call.getLong(1)).thenReturn(value);
            assertThat(handler.getNullableResult(rows, "zone_id")).isEqualTo(bits);
            assertThat(handler.getNullableResult(rows, 1)).isEqualTo(bits);
            assertThat(handler.getNullableResult(call, 1)).isEqualTo(bits);
        }
        verify(statement, never()).setInt(anyInt(), anyInt());
    }

    @Test
    void SQL空值及越界在三个读取入口都明确失败() throws Exception {
        ResultSet rows = mock(ResultSet.class);
        CallableStatement call = mock(CallableStatement.class);
        for (long value : new long[] {-1L, 4294967296L, Long.MAX_VALUE}) {
            when(rows.getLong("zone_id")).thenReturn(value);
            when(rows.getLong(1)).thenReturn(value);
            when(call.getLong(1)).thenReturn(value);
            assertThatThrownBy(() -> handler.getNullableResult(rows, "zone_id"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uint32");
            assertThatThrownBy(() -> handler.getNullableResult(rows, 1)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> handler.getNullableResult(call, 1)).isInstanceOf(SQLException.class);
        }
        when(rows.wasNull()).thenReturn(true);
        when(call.wasNull()).thenReturn(true);
        assertThatThrownBy(() -> handler.getNullableResult(rows, "zone_id")).hasMessageContaining("SQL NULL");
        assertThatThrownBy(() -> handler.getNullableResult(rows, 1)).hasMessageContaining("SQL NULL");
        assertThatThrownBy(() -> handler.getNullableResult(call, 1)).hasMessageContaining("SQL NULL");
    }

    @Test
    void record构造映射明确且普通Integer映射保持原样() {
        Configuration config = new Configuration();
        config.addMapper(GatewayStoreMapper.class);
        String prefix = GatewayStoreMapper.class.getName() + ".";
        var zone = config.getResultMap(prefix + "zoneRow").getConstructorResultMappings();
        var white = config.getResultMap(prefix + "whitelistRow").getConstructorResultMappings();
        assertThat(zone).hasSize(10);
        assertThat(white).hasSize(3);
        assertThat(zone.get(0).getTypeHandler()).isInstanceOf(UnsignedZoneIdTypeHandler.class);
        assertThat(white.get(0).getTypeHandler()).isInstanceOf(UnsignedZoneIdTypeHandler.class);
        assertThat(zone.get(5).getJavaType()).isEqualTo(Long.class);
        assertThat(zone.get(7).getTypeHandler()).isInstanceOf(IntegerTypeHandler.class);
        assertThat(config.getTypeHandlerRegistry().getTypeHandler(Integer.class)).isInstanceOf(IntegerTypeHandler.class);
        for (String method : new String[] {"selectZones", "selectZone"}) {
            assertThat(config.getMappedStatement(prefix + method).getResultMaps().get(0).getId())
                    .isEqualTo(prefix + "zoneRow");
        }
        for (String method : new String[] {"selectWhitelist", "selectWhitelistEntry"}) {
            assertThat(config.getMappedStatement(prefix + method).getResultMaps().get(0).getId())
                    .isEqualTo(prefix + "whitelistRow");
        }
    }
}
