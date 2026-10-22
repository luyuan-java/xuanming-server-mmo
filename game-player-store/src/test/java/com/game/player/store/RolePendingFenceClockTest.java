package com.game.player.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.common.deadline.Deadline;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** JDBC接缝固定数据库时钟，与测试进程墙钟无关；原跨域状态机另由真实SQL夹具覆盖。 */
class RolePendingFenceClockTest {
    private record Fixture(Connection connection, Map<String,PreparedStatement> statements) {}

    private static Fixture fixture(boolean hasClock, BigDecimal clock) throws SQLException {
        var connection=mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(false);
        var statements=new LinkedHashMap<String,PreparedStatement>();
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String query=call.getArgument(0);
            var statement=mock(PreparedStatement.class);
            var rows=mock(ResultSet.class);
            if(query.equals("SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000.000")) {
                when(rows.next()).thenReturn(hasClock,false);
                when(rows.getBigDecimal(1)).thenReturn(clock);
            } else {
                when(rows.next()).thenReturn(false);
            }
            when(statement.executeQuery()).thenReturn(rows);
            statements.put(query,statement);
            return statement;
        });
        return new Fixture(connection,statements);
    }

    @Test void 全部有期限同意使用同一原事务DB毫秒_不取本机墙钟() throws Exception {
        var fixture=fixture(true,new BigDecimal("1000.000"));
        assertFalse(RolePendingFence.blocked(fixture.connection(),7,Deadline.after(3000)));
        var expiry=fixture.statements().entrySet().stream().filter(entry->entry.getKey().contains("expires_ms>?")).toList();
        assertEquals(3,expiry.size());
        for(var entry:expiry) {
            verify(entry.getValue()).setObject(1,7L);
            verify(entry.getValue()).setObject(2,7L);
            verify(entry.getValue()).setObject(3,1000L);
            verify(entry.getValue()).setQueryTimeout(intThat(seconds->seconds>0&&seconds<=3));
        }
        assertEquals(1,fixture.statements().keySet().stream().filter(query->query.startsWith("SELECT UNIX_TIMESTAMP")).count());
    }

    @Test void DB时钟缺行空值越界及非整数均拒绝_不能以本机时间补权() throws Exception {
        var missing=fixture(false,null);
        assertThrows(SQLException.class,()->RolePendingFence.blocked(missing.connection(),7,Deadline.after(3000)));
        for(var clock:Arrays.asList(null,BigDecimal.ZERO,new BigDecimal("-1"),new BigDecimal("1.5"),new BigDecimal("9223372036854775808"))) {
            var fixture=fixture(true,clock);
            assertThrows(SQLException.class,()->RolePendingFence.blocked(fixture.connection(),7,Deadline.after(3000)));
            assertTrue(fixture.statements().keySet().stream().noneMatch(query->query.contains("expires_ms>?")));
        }
    }

    @Test void 原预算耗尽不再发送任何挂起或时钟SQL() throws Exception {
        var fixture=fixture(true,new BigDecimal("1000"));
        assertThrows(SQLTimeoutException.class,()->RolePendingFence.blocked(fixture.connection(),7,Deadline.after(-1)));
        verify(fixture.connection(),never()).prepareStatement(anyString());
    }
}
