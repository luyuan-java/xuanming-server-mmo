package com.game.trade.store;

import com.game.common.player.PlayerProfiles.ConnectionSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 不连库的假 JDBC（{@link JdbcListingStoreUnitTest} 用）：记下每条语句的原文、绑定值、查询超时与取连接时的等待上限，按脚本依次回结果
 * （查询回若干行、写语句回改动行数，或抛脚本里的 {@link SQLException}）。只实现 {@link JdbcListingStore} 用到的那几个 JDBC 方法，
 * 其余方法一律抛 {@link UnsupportedOperationException}——存储层多调了什么，测试会立刻知道。
 */
final class FakeJdbc implements ConnectionSource {

    /** 一条发出去的语句。 */
    record Call(String sql, List<Object> args, int queryTimeoutSeconds, boolean query) {
    }

    final List<Call> calls = new ArrayList<>();
    final List<Long> maxWaits = new ArrayList<>();
    /** 脚本：{@code List<Object[]>}（查询的行）、{@link Integer}（写语句改动行数）或 {@link SQLException}（抛出）。 */
    final Deque<Object> script = new ArrayDeque<>();
    int opened;
    int closed;
    int statementsClosed;
    /** 非 null 时取连接直接抛它。 */
    SQLException connectFailure;

    FakeJdbc rows(Object[]... rows) {
        script.add(List.of(rows));
        return this;
    }

    FakeJdbc updated(int rows) {
        script.add(rows);
        return this;
    }

    FakeJdbc fail(SQLException e) {
        script.add(e);
        return this;
    }

    static SQLException sqlError(int code) {
        return new SQLException("脚本错误 " + code, "HY000", code);
    }

    @Override
    public Connection get(long maxWaitMillis) throws SQLException {
        maxWaits.add(maxWaitMillis);
        if (connectFailure != null) {
            throw connectFailure;
        }
        opened++;
        return proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
            case "prepareStatement" -> statement((String) a[0]);
            case "close" -> {
                closed++;
                yield null;
            }
            case "isClosed" -> false;
            case "toString" -> "FakeConnection";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException("Connection." + m.getName());
        });
    }

    private PreparedStatement statement(String sql) {
        Map<Integer, Object> args = new TreeMap<>();
        int[] timeout = {0};
        return proxy(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
            case "setQueryTimeout" -> {
                timeout[0] = (Integer) a[0];
                yield null;
            }
            case "setObject" -> {
                args.put((Integer) a[0], a[1]);
                yield null;
            }
            case "executeQuery" -> {
                calls.add(new Call(sql, List.copyOf(args.values()), timeout[0], true));
                yield resultSet(next());
            }
            case "executeUpdate" -> {
                calls.add(new Call(sql, List.copyOf(args.values()), timeout[0], false));
                yield next();
            }
            case "close" -> {
                statementsClosed++;
                yield null;
            }
            case "toString" -> "FakeStatement[" + sql + "]";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException("PreparedStatement." + m.getName());
        });
    }

    private Object next() throws SQLException {
        Object step = script.poll();
        if (step == null) {
            throw new IllegalStateException("脚本用完了：存储层多发了一条语句");
        }
        if (step instanceof SQLException e) {
            throw e;
        }
        return step;
    }

    @SuppressWarnings("unchecked")
    private static ResultSet resultSet(Object step) {
        List<Object[]> rows = (List<Object[]>) step;
        int[] cursor = {-1};
        return proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
            case "next" -> ++cursor[0] < rows.size();
            case "getObject" -> rows.get(cursor[0])[(Integer) a[0] - 1];
            case "getLong" -> {
                Object v = rows.get(cursor[0])[(Integer) a[0] - 1];
                yield v == null ? 0L : ((Number) v).longValue();
            }
            case "getInt" -> {
                Object v = rows.get(cursor[0])[(Integer) a[0] - 1];
                yield v == null ? 0 : ((Number) v).intValue();
            }
            case "getString" -> (String) rows.get(cursor[0])[(Integer) a[0] - 1];
            case "close" -> null;
            case "toString" -> "FakeResultSet";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException("ResultSet." + m.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
