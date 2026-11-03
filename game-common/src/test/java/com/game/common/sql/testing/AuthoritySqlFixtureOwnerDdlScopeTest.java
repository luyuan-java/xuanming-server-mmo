package com.game.common.sql.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/**
 * 仅验证现有私有owner DDL helper的资源期限范围，不建立fixture、不连接数据库。
 * 这些测试不能红复现prepareInternal的两处调用点漏接；该缺陷有独立真实TiDB失败证据。
 */
class AuthoritySqlFixtureOwnerDdlScopeTest {
    private static final int ORIGINAL_QUERY_SECONDS = 5;
    private static final int ORIGINAL_NETWORK_MILLIS = 5_000;
    private static final String SYNTHETIC_DDL = "synthetic owner DDL; not sent to a database";

    @Test
    void 成功时仅在命令范围临时放宽期限并双恢复() throws Exception {
        var probe = new JdbcProbe();

        Object result = invokeOwnerDdl(probe, () -> probe.statement.execute(SYNTHETIC_DDL));

        assertEquals(Boolean.FALSE, result);
        assertEquals(1, probe.executeCalls);
        assertTemporaryLimits(probe);
        assertEquals(ORIGINAL_QUERY_SECONDS, probe.querySeconds);
        assertEquals(ORIGINAL_NETWORK_MILLIS, probe.networkMillis);
        assertEquals(List.of(probe.installedQuerySeconds, ORIGINAL_QUERY_SECONDS), probe.querySets);
        assertEquals(List.of(probe.installedNetworkMillis, ORIGINAL_NETWORK_MILLIS), probe.networkSets);
    }

    @Test
    void 命令异常仍恢复原期限且保留原异常() {
        var probe = new JdbcProbe();
        var commandFailure = new SQLException("synthetic command failure", "42000");
        probe.commandFailure = commandFailure;

        SQLException failure = assertThrows(SQLException.class,
                () -> invokeOwnerDdl(probe, () -> probe.statement.execute(SYNTHETIC_DDL)));

        assertSame(commandFailure, failure);
        assertEquals(1, probe.executeCalls);
        assertTemporaryLimits(probe);
        assertEquals(ORIGINAL_QUERY_SECONDS, probe.querySeconds);
        assertEquals(ORIGINAL_NETWORK_MILLIS, probe.networkMillis);
        assertEquals(1, probe.queryRestoreAttempts);
        assertEquals(1, probe.networkRestoreAttempts);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void 任一恢复失败都报告结局未知且继续尝试另一项恢复() {
        for (boolean queryFails : List.of(true, false)) {
            var probe = new JdbcProbe();
            probe.failQueryRestore = queryFails;
            probe.failNetworkRestore = !queryFails;

            SQLException failure = assertThrows(SQLException.class,
                    () -> invokeOwnerDdl(probe, () -> probe.statement.execute(SYNTHETIC_DDL)));

            // 08007是helper的资源恢复未确认；不能把已执行命令或剩余一项恢复冒充确定成功。
            assertEquals("08007", failure.getSQLState());
            assertEquals(1, probe.executeCalls);
            assertTemporaryLimits(probe);
            assertEquals(1, probe.queryRestoreAttempts);
            assertEquals(1, probe.networkRestoreAttempts);
            if (queryFails) {
                assertEquals(probe.installedQuerySeconds, probe.querySeconds);
                assertEquals(ORIGINAL_NETWORK_MILLIS, probe.networkMillis);
            } else {
                assertEquals(ORIGINAL_QUERY_SECONDS, probe.querySeconds);
                assertEquals(probe.installedNetworkMillis, probe.networkMillis);
            }
        }
    }

    @Test
    void 命令与双恢复均失败时保留主异常和两项不确定证据() {
        var probe = new JdbcProbe();
        var commandFailure = new SQLException("synthetic command failure", "42000");
        probe.commandFailure = commandFailure;
        probe.failQueryRestore = true;
        probe.failNetworkRestore = true;

        SQLException failure = assertThrows(SQLException.class,
                () -> invokeOwnerDdl(probe, () -> probe.statement.execute(SYNTHETIC_DDL)));

        assertSame(commandFailure, failure);
        assertEquals(1, probe.queryRestoreAttempts);
        assertEquals(1, probe.networkRestoreAttempts);
        assertEquals(2, failure.getSuppressed().length);
        for (Throwable suppressed : failure.getSuppressed()) {
            assertInstanceOf(SQLException.class, suppressed);
            assertEquals("08007", ((SQLException) suppressed).getSQLState());
        }
    }

    private static void assertTemporaryLimits(JdbcProbe probe) {
        // 没有模拟等待；期限按单次60秒预算的剩余量安装，不保证调度暂停后仍恰为60秒。
        assertTrue(probe.installedQuerySeconds > ORIGINAL_QUERY_SECONDS);
        assertTrue(probe.installedQuerySeconds <= 60);
        assertTrue(probe.installedNetworkMillis > ORIGINAL_NETWORK_MILLIS);
        assertTrue(probe.installedNetworkMillis <= 60_000);
    }

    private static Object invokeOwnerDdl(JdbcProbe probe, Callable<?> command) throws Exception {
        Method helper = AuthoritySqlFixture.class.getDeclaredMethod(
                "executeOwnerDdl", Connection.class, Statement.class, Callable.class);
        helper.setAccessible(true);
        try {
            return helper.invoke(null, probe.connection, probe.statement, command);
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new AssertionError("unexpected helper invocation failure", cause);
        }
    }

    private static final class JdbcProbe implements InvocationHandler {
        int querySeconds = ORIGINAL_QUERY_SECONDS;
        int networkMillis = ORIGINAL_NETWORK_MILLIS;
        int installedQuerySeconds;
        int installedNetworkMillis;
        int queryRestoreAttempts;
        int networkRestoreAttempts;
        int executeCalls;
        boolean failQueryRestore;
        boolean failNetworkRestore;
        SQLException commandFailure;
        final List<Integer> querySets = new ArrayList<>();
        final List<Integer> networkSets = new ArrayList<>();
        final Connection connection = (Connection) Proxy.newProxyInstance(
                AuthoritySqlFixture.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
        final Statement statement = (Statement) Proxy.newProxyInstance(
                AuthoritySqlFixture.class.getClassLoader(), new Class<?>[]{Statement.class}, this);

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "synthetic JDBC probe";
                    default -> throw new AssertionError("unexpected Object method");
                };
            }
            return switch (method.getName()) {
                case "getQueryTimeout" -> {
                    assertSame(statement, proxy);
                    yield querySeconds;
                }
                case "setQueryTimeout" -> {
                    assertSame(statement, proxy);
                    int seconds = (Integer) arguments[0];
                    querySets.add(seconds);
                    if (seconds == ORIGINAL_QUERY_SECONDS) {
                        queryRestoreAttempts++;
                        if (failQueryRestore) throw new SQLException("synthetic query restore failure", "HY000");
                    } else {
                        installedQuerySeconds = seconds;
                    }
                    querySeconds = seconds;
                    yield null;
                }
                case "getNetworkTimeout" -> {
                    assertSame(connection, proxy);
                    yield networkMillis;
                }
                case "setNetworkTimeout" -> {
                    assertSame(connection, proxy);
                    assertInstanceOf(java.util.concurrent.Executor.class, arguments[0]);
                    int millis = (Integer) arguments[1];
                    networkSets.add(millis);
                    if (millis == ORIGINAL_NETWORK_MILLIS) {
                        networkRestoreAttempts++;
                        if (failNetworkRestore) throw new SQLException("synthetic network restore failure", "HY000");
                    } else {
                        installedNetworkMillis = millis;
                    }
                    networkMillis = millis;
                    yield null;
                }
                case "execute" -> {
                    assertSame(statement, proxy);
                    assertEquals(SYNTHETIC_DDL, arguments[0]);
                    executeCalls++;
                    assertTemporaryLimits(this);
                    assertEquals(installedQuerySeconds, querySeconds);
                    assertEquals(installedNetworkMillis, networkMillis);
                    if (commandFailure != null) throw commandFailure;
                    yield false;
                }
                default -> throw new AssertionError("unexpected JDBC method: " + method.getName());
            };
        }
    }
}
