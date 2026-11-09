package com.game.common.sql.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 经prepare实际使用的callback接点验证期限范围；不连接SQL、不伪称真实TiDB已通过。 */
class AuthoritySqlFixturePrepareDdlScopeTest {
    @Test
    void CREATE与ALTER逐条临时使用DDL期限且普通DML保留原期限() throws Exception {
        for (int originalQuery : List.of(0, 5)) {
            var probe = new Probe(false, originalQuery);
            initial(probe, (connection, principal) -> {
                assertEquals("owner@localhost", principal);
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE one(id INT)");
                    statement.execute("INSERT INTO one VALUES(1)");
                    statement.execute(" ALTER TABLE one ADD COLUMN n INT");
                    statement.execute("SELECT 1");
                }
            });
            assertEquals(4, probe.executions.size());
            assertDdl(probe.executions.get(0));
            assertEquals(new Limits(originalQuery, 5_000), probe.executions.get(1));
            assertDdl(probe.executions.get(2));
            assertEquals(new Limits(originalQuery, 5_000), probe.executions.get(3));
            probe.assertRestored();
        }
    }

    @Test
    void PreparedStatement无参数执行也使用同一DDL期限() throws Exception {
        var probe = new Probe(true, 5);
        initial(probe, (connection, principal) -> {
            try (var statement = connection.prepareStatement("CREATE TABLE one(id INT)")) {
                assertEquals(1, statement.executeUpdate());
            }
        });
        assertEquals(1, probe.executions.size());
        assertDdl(probe.executions.getFirst());
        probe.assertRestored();
    }

    @Test
    void 非目标DDL与包含CREATE字符串的普通SQL不会放宽期限() throws Exception {
        var probe = new Probe(false, 5);
        initial(probe, (connection, principal) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE one");
                statement.execute("SELECT 'CREATE TABLE one(id INT)'");
                statement.execute("UPDATE one SET id=2");
            }
        });
        assertEquals(List.of(new Limits(5, 5_000), new Limits(5, 5_000), new Limits(5, 5_000)), probe.executions);
        assertEquals(0, probe.querySets);
        assertEquals(0, probe.networkSets);
    }

    @Test
    void 已知SQL拒绝原样抛出且两项期限恢复且不重试() throws Exception {
        var probe = new Probe(false, 5);
        var original = new SQLException("synthetic syntax failure", "42000", 1064);
        probe.executeFailure = original;
        SQLException failure = assertThrows(SQLException.class, () -> initial(probe, (connection, principal) ->
                connection.createStatement().execute("CREATE TABLE one(id INT)")));
        assertSame(original, failure);
        assertEquals(1, probe.executions.size());
        probe.assertRestored();
    }

    @Test
    void 结果未知即使被callback捕获也禁止后续SQL和成功返回() throws Exception {
        var probe = new Probe(false, 5);
        var original = new SQLTimeoutException("synthetic timeout", "HYT00");
        probe.executeFailure = original;
        SQLException failure = assertThrows(SQLException.class, () -> initial(probe, (connection, principal) -> {
            var statement = connection.createStatement();
            assertSame(original, assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE one(id INT)")));
            var rejected = assertThrows(SQLException.class, () -> statement.execute("SELECT 1"));
            assertEquals("08007", rejected.getSQLState());
        }));
        assertSame(original, failure);
        assertEquals(1, probe.executions.size());
        probe.assertRestored();
    }

    @Test
    void 任一期限恢复失败仍尝试另一项且callback不能掩盖UNKNOWN() throws Exception {
        for (boolean queryFailure : List.of(true, false)) {
            var probe = new Probe(false, 5);
            probe.failQueryRestore = queryFailure;
            probe.failNetworkRestore = !queryFailure;
            SQLException failure = assertThrows(SQLException.class, () -> initial(probe, (connection, principal) -> {
                var statement = connection.createStatement();
                assertEquals("08007", assertThrows(SQLException.class,
                        () -> statement.execute("CREATE TABLE one(id INT)")).getSQLState());
            }));
            assertEquals("08007", failure.getSQLState());
            assertEquals(1, probe.executions.size());
            assertEquals(1, probe.queryRestoreAttempts);
            assertEquals(1, probe.networkRestoreAttempts);
            if (queryFailure) assertEquals(5_000, probe.networkMillis);
            else assertEquals(5, probe.querySeconds);
        }
    }

    @Test
    void 回调退出撤销statement及其派生connection且owner连接不被关闭() throws Exception {
        var probe = new Probe(false, 5);
        var saved = new AtomicReference<Statement>();
        var derived = new AtomicReference<Connection>();
        initial(probe, (connection, principal) -> {
            var statement = connection.createStatement();
            saved.set(statement);
            derived.set(statement.getConnection());
            assertSame(connection, derived.get());
            statement.execute("CREATE TABLE one(id INT)");
        });
        assertThrows(SQLException.class, () -> saved.get().execute("CREATE TABLE two(id INT)"));
        assertThrows(SQLException.class, () -> derived.get().createStatement());
        verify(probe.statement).close();
        verify(probe.connection, never()).close();
        assertEquals(1, probe.executions.size());
    }

    @Test
    void 不接受无法分配单条DDL期限的batch() throws Exception {
        for (boolean prepared : List.of(false, true)) {
            var probe = new Probe(prepared, 5);
            initial(probe, (connection, principal) -> {
                if (prepared) {
                    var statement = connection.prepareStatement("ALTER TABLE one ADD COLUMN n INT");
                    assertThrows(SQLFeatureNotSupportedException.class, statement::addBatch);
                } else {
                    var statement = connection.createStatement();
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> statement.addBatch("CREATE TABLE one(id INT)"));
                }
            });
            assertEquals(0, probe.executions.size());
            assertEquals(0, probe.querySets);
            assertEquals(0, probe.networkSets);
        }
    }

    private static void initial(Probe probe, AuthoritySqlFixture.SchemaBuilder builder) throws Exception {
        var helper = AuthoritySqlFixture.class.getDeclaredMethod("createInitialSchema",
                Connection.class, AuthoritySqlFixture.SchemaBuilder.class, String.class);
        helper.setAccessible(true);
        try { helper.invoke(null, probe.connection, builder, "owner@localhost"); }
        catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof Exception failure) throw failure;
            if (wrapper.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(wrapper.getCause());
        }
    }

    private static void assertDdl(Limits limits) {
        assertTrue(limits.querySeconds() > 5 && limits.querySeconds() <= 60);
        assertTrue(limits.networkMillis() > 5_000 && limits.networkMillis() <= 60_000);
    }
    private record Limits(int querySeconds, int networkMillis) {}
    private static final class Probe {
        final Connection connection = mock(Connection.class);
        final Statement statement;
        final int originalQuery;
        final List<Limits> executions = new ArrayList<>();
        int querySeconds;
        int networkMillis = 5_000;
        int querySets;
        int networkSets;
        int queryRestoreAttempts;
        int networkRestoreAttempts;
        boolean failQueryRestore;
        boolean failNetworkRestore;
        SQLException executeFailure;

        Probe(boolean prepared, int originalQuery) throws Exception {
            this.originalQuery = originalQuery;
            this.querySeconds = originalQuery;
            statement = prepared ? mock(PreparedStatement.class) : mock(Statement.class);
            when(connection.createStatement()).thenReturn(statement);
            if (prepared) when(connection.prepareStatement(anyString())).thenReturn((PreparedStatement) statement);
            when(statement.getConnection()).thenReturn(connection);
            when(connection.getNetworkTimeout()).thenAnswer(call -> networkMillis);
            when(statement.getQueryTimeout()).thenAnswer(call -> querySeconds);
            doAnswer(call -> {
                int next = call.getArgument(0);
                querySets++;
                if (next == originalQuery) {
                    queryRestoreAttempts++;
                    if (failQueryRestore) throw new SQLException("synthetic query restore failure", "HY000");
                }
                querySeconds = next;
                return null;
            }).when(statement).setQueryTimeout(anyInt());
            doAnswer(call -> {
                int next = call.getArgument(1);
                networkSets++;
                if (next == 5_000) {
                    networkRestoreAttempts++;
                    if (failNetworkRestore) throw new SQLException("synthetic network restore failure", "HY000");
                }
                networkMillis = next;
                return null;
            }).when(connection).setNetworkTimeout(any(), anyInt());
            doAnswer(call -> { executed(); return false; }).when(statement).execute(anyString());
            if (prepared) doAnswer(call -> { executed(); return 1; }).when((PreparedStatement) statement).executeUpdate();
        }
        private void executed() throws SQLException {
            executions.add(new Limits(querySeconds, networkMillis));
            if (executeFailure != null) throw executeFailure;
        }
        private void assertRestored() {
            assertEquals(originalQuery, querySeconds);
            assertEquals(5_000, networkMillis);
        }
    }
}
