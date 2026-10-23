package com.game.common.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

/** 仅验接口边界和原件比较；真实行锁由跨域SQL回归验证。 */
class PlayerSessionAuthorityTest {
    @Test void 不接受零修订高半区或自动提交边界_不借当前SQL补填原身份() throws Exception {
        var connection = mock(Connection.class); when(connection.getAutoCommit()).thenReturn(false);
        assertThrows(SecurityException.class, () -> PlayerSessionAuthority.lock(connection,1,"account",0,2));
        assertThrows(SecurityException.class, () -> PlayerSessionAuthority.lock(connection,1,"account",Long.MIN_VALUE,2));
        when(connection.getAutoCommit()).thenReturn(true);
        assertThrows(SecurityException.class, () -> PlayerSessionAuthority.lock(connection,1,"account",1,2));
        verify(connection,never()).prepareStatement(anyString());
    }
    @Test void 最大合法修订可核对_实际SQL高半区显式拒绝而不溢出() throws Exception {
        try(var routes=mockStatic(PlayerRouteAuthority.class)){
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var row = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(false); when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(row); when(row.next()).thenReturn(true);
        when(row.getString(1)).thenReturn("account"); when(row.getString(2)).thenReturn(Long.toString(Long.MAX_VALUE));
        when(row.getString(3)).thenReturn("0");
        assertDoesNotThrow(() -> PlayerSessionAuthority.lock(connection,1,"account",Long.MAX_VALUE,2));
        when(row.getString(2)).thenReturn("18446744073709551615");
        assertTrue(assertThrows(SecurityException.class, () -> PlayerSessionAuthority.lock(connection,1,"account",1,2))
            .getMessage().contains("1..LongMax"));
        verify(row,never()).getLong(2);
        routes.verify(()->PlayerRouteAuthority.lock(connection,java.util.List.of(1L),2),times(2));
        }
    }

    @Test void 路由提交锁方言_TiDB和H2显式排他_MySQL保真实共享() throws Exception {
        for (var database : java.util.List.of(
                new String[]{"MySQL", "8.0.11-TiDB-v8.5.2", " FOR UPDATE"},
                new String[]{"TiDB", "v8.5.2", " FOR UPDATE"},
                new String[]{"H2", "2.3.232", " FOR UPDATE"},
                new String[]{"MySQL", "8.4.13", " LOCK IN SHARE MODE"})) {
            var fixture = routeMetadata(database[0], database[1]);
            PlayerRouteAuthority.requireMeta(fixture.connection(), 2, true);
            verify(fixture.connection()).prepareStatement(
                    "SELECT generation,writable,fence_shards FROM route_meta WHERE id=1" + database[2]);
            verify(fixture.statement()).setQueryTimeout(2);
        }
    }

    @Test void 未知产品或版本与元数据故障在路由SQL前拒绝_不猜共享或启用noop() throws Exception {
        for (var database : java.util.List.of(
                new String[]{"unknown", "8.5.2"},
                new String[]{"MySQL", null}, new String[]{"MySQL", " "},
                new String[]{null, "8.4.13"})) {
            var fixture = routeMetadata(database[0], database[1]);
            assertThrows(SQLException.class, () -> PlayerRouteAuthority.requireMeta(fixture.connection(), 2, true));
            verify(fixture.connection(), never()).prepareStatement(anyString());
        }
        var fixture = routeMetadata("MySQL", "8.4.13");
        when(fixture.connection().getMetaData().getDatabaseProductVersion()).thenThrow(new SQLException("元数据读取失败"));
        assertThrows(SQLException.class, () -> PlayerRouteAuthority.requireMeta(fixture.connection(), 2, true));
        verify(fixture.connection(), never()).prepareStatement(anyString());
    }

    @Test void 启动只读meta校验不误取事务锁_保留原无锁读契约() throws Exception {
        var fixture = routeMetadata("unknown", null);
        PlayerRouteAuthority.requireMeta(fixture.connection(), 3, false);
        verify(fixture.connection()).prepareStatement("SELECT generation,writable,fence_shards FROM route_meta WHERE id=1");
        verify(fixture.connection(), never()).getMetaData();
        verify(fixture.statement()).setQueryTimeout(3);
    }

    private record RouteMetadata(Connection connection, PreparedStatement statement) {}
    private static RouteMetadata routeMetadata(String product, String version) throws Exception {
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(metadata.getDatabaseProductVersion()).thenReturn(version);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("e0bded15-6e55-438c-b067-55ac20decc4e");
        when(rows.getString(2)).thenReturn("1"); when(rows.getInt(3)).thenReturn(PlayerRouteAuthority.FENCE_SHARDS);
        return new RouteMetadata(connection, statement);
    }
}
