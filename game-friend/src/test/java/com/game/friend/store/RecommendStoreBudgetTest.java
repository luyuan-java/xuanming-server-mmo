package com.game.friend.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class RecommendStoreBudgetTest {
    @Test
    void 已过期请求不借连接_主体不符不发SQL() throws SQLException {
        DataSource source = mock(DataSource.class);
        RecommendStore store = new RecommendStore(source, 3, (lo, span) -> 0);
        assertThatThrownBy(() -> store.mutual(1, List.of(), 20, Deadline.after(0))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> store.forActor(1, "original", 7).mutual(2, List.of(), 20, Deadline.after(5000)))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(source);
    }

    @Test
    void JDBC与服务器预算同时受剩余时间和单语句上限约束() throws SQLException {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        List<String> queries = new ArrayList<>();
        when(connection.prepareStatement(anyString())).thenAnswer(call -> { queries.add(call.getArgument(0)); return statement; });
        when(statement.executeQuery()).thenReturn(rows);
        RecommendStore store = new RecommendStore(source, 1, (lo, span) -> 0);
        assertThat(store.mutual(1, List.of(), 20, Deadline.after(30_000))).isEmpty();
        verify(statement).setQueryTimeout(1);
        assertThat(serverBudget(queries.getFirst())).isEqualTo(1000);
        assertThat(store.mutual(1, List.of(), 20, Deadline.after(700))).isEmpty();
        assertThat(serverBudget(queries.getLast())).isBetween(1L, 700L);
        verify(connection, never()).commit(); // 系统视图仍为纯读；外部视图在原会话 P 锁内读取。
    }

    @Test
    void SQL执行后截止即使空结果也按失败处理_随机不再发锚点查询() throws SQLException {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        // 提前生成全部 Mockito 代理，预算只测 SQL 执行阶段。
        Deadline deadline = Deadline.after(500);
        when(statement.executeQuery()).thenAnswer(call -> {
            assertThat(deadline.expired()).isFalse();
            while (!deadline.expired()) LockSupport.parkNanos(100_000);
            return rows;
        });
        RecommendStore store = new RecommendStore(source, 3, (lo, span) -> 0);
        assertThatThrownBy(() -> store.random(1, List.of(), 20, deadline)).isInstanceOf(SQLException.class);
        verify(connection).prepareStatement(anyString());
        verify(statement).executeQuery();
        verify(statement).close();
        verify(connection).close();
    }

    private static long serverBudget(String sql) {
        var match = Pattern.compile("MAX_EXECUTION_TIME\\((\\d+)\\)").matcher(sql);
        assertThat(match.find()).isTrue();
        return Long.parseLong(match.group(1));
    }
}
