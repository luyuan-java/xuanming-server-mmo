package com.game.data.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 控制 JDBC 阶段耗时，验证提交不能在原预算将尽时重新获得完整三十秒。 */
class ZoneMergeBudgetTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement query = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicLong networkMillis = new AtomicLong();
    private final String runId = UUID.randomUUID().toString();
    private final ZoneMergeStore store = new ZoneMergeStore(source, clock::get);

    @BeforeEach void prepare() throws SQLException {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getNetworkTimeout()).thenReturn(60_000);
        doAnswer(call -> { networkMillis.set(call.getArgument(1, Integer.class)); return null; })
                .when(connection).setNetworkTimeout(any(), anyInt());
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(anyString())).thenAnswer(call -> switch (call.getArgument(0, String.class)) {
            case "run_id", "generation" -> runId;
            case "source_zone" -> "1";
            case "target_zone" -> "2";
            default -> "0";
        });
        when(rows.getBytes(anyString())).thenReturn(new byte[32]);
    }

    @Test void 读完原件只余一百毫秒时提交沿用剩余网络预算() throws SQLException {
        when(query.executeQuery()).thenAnswer(call -> {
            clock.set(Duration.ofMillis(29_900).toNanos());
            return rows;
        });
        doAnswer(call -> { assertThat(networkMillis.get()).isEqualTo(100); return null; })
                .when(connection).commit();
        assertThat(store.read(runId).getRunId()).isEqualTo(runId);
        var order = inOrder(connection);
        order.verify(connection).setNetworkTimeout(any(), org.mockito.ArgumentMatchers.eq(100));
        order.verify(connection).commit();
        order.verify(connection).setNetworkTimeout(any(), org.mockito.ArgumentMatchers.eq(60_000));
    }

    @Test void 预算在读取中耗尽时不提交且回滚不能重新等三十秒() throws SQLException {
        when(query.executeQuery()).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(30).toNanos());
            return rows;
        });
        doAnswer(call -> { assertThat(networkMillis.get()).isEqualTo(1); return null; })
                .when(connection).rollback();
        assertThatThrownBy(() -> store.read(runId)).isInstanceOf(SQLTimeoutException.class);
        verify(connection, never()).commit();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test void 获取连接耗尽预算后不能开始业务SQL() throws SQLException {
        when(source.getConnection()).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(30).toNanos());
            return connection;
        });
        assertThatThrownBy(() -> store.read(runId)).isInstanceOf(SQLTimeoutException.class);
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).commit();
    }

    @Test void 准备语句耗尽预算时关闭已创建语句且不执行() throws SQLException {
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(30).toNanos());
            return query;
        });
        assertThatThrownBy(() -> store.read(runId)).isInstanceOf(SQLTimeoutException.class);
        verify(query).close();
        verify(query, never()).executeQuery();
        verify(connection, never()).commit();
    }

    @Test void 原连接更短的网络时限不会被批次放宽() throws SQLException {
        when(connection.getNetworkTimeout()).thenReturn(50);
        doAnswer(call -> { assertThat(networkMillis.get()).isEqualTo(50); return null; })
                .when(connection).commit();
        assertThat(store.read(runId).getRunId()).isEqualTo(runId);
        verify(connection, never()).setNetworkTimeout(any(), org.mockito.ArgumentMatchers.intThat(value -> value > 50));
    }

    @Test void 不能设置清理时限时丢弃连接并保留原失败() throws SQLException {
        var original = new SQLException("读取失败");
        var closed = new AtomicBoolean();
        when(query.executeQuery()).thenAnswer(call -> {
            clock.set(Duration.ofSeconds(30).toNanos());
            throw original;
        });
        doAnswer(call -> {
            int timeout = call.getArgument(1, Integer.class);
            if (timeout == 1) throw new SQLException("不能收紧网络时限");
            networkMillis.set(timeout);
            return null;
        }).when(connection).setNetworkTimeout(any(), anyInt());
        doAnswer(call -> { closed.set(true); return null; }).when(connection).abort(any());
        when(connection.isClosed()).thenAnswer(call -> closed.get());
        assertThatThrownBy(() -> store.read(runId)).isSameAs(original);
        assertThat(original.getSuppressed()).hasSize(1);
        verify(connection).abort(any());
        verify(connection, never()).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }
}
