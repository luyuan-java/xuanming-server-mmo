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
}
