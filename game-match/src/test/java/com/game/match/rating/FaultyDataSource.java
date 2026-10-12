package com.game.match.rating;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 包一层真数据源的测试替身：记下执行过的语句（连同绑定的参数）与提交 / 回滚次数，并能在指定的语句或提交上注入一次 SQL 错误
 * （事务中途失败、死锁、提交结果不明），或在某条语句执行完之后插入一段动作（让两个事务按确定的次序交错）。
 * 不改变真库的任何行为——没有命中故障的调用原样透传，真驱动抛的 {@link SQLException} 也会记进 {@link #errors} 再原样抛出。
 *
 * <pre>
 * FaultyDataSource ds = new FaultyDataSource(db.dataSource);
 * ds.failBefore("UPDATE match_rating SET", () -> new SQLException("Deadlock found", "40001", 1213));   // 下一条这样开头的语句执行前失败
 * ds.failCommit(true, () -> new SQLException("连接断了"));                                              // 下一次提交：先真提交，再让调用方看到异常
 * ds.afterOnce("SELECT rating_centi", () -> latch.await());                                           // 下一条这样开头的语句执行完后停一下
 * assertThat(ds.executed("INSERT INTO match_rating_applied")).isEqualTo(2);
 * </pre>
 */
public final class FaultyDataSource implements DataSource {

    /** 一次语句执行：SQL 原文与按位置绑定的参数。 */
    public record Call(String sql, List<Object> params) {
    }

    /** 插在语句之后的动作（可以抛受检异常，会原样抛给被测代码）。 */
    @FunctionalInterface
    public interface Action {
        void run() throws Exception;
    }

    /** 实际交给真驱动执行的语句，按执行顺序。 */
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    /** 真驱动在执行语句 / 提交时抛出的错误（不含注入的）。 */
    public final List<SQLException> errors = new CopyOnWriteArrayList<>();
    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();
    /** 成功取到连接的次数（0 = 被测代码没有碰库）。 */
    public final AtomicInteger connections = new AtomicInteger();

    private record StatementFault(String sqlPrefix, Supplier<SQLException> error) {
    }

    private record StatementHook(String sqlPrefix, Action action) {
    }

    private record CommitFault(boolean afterCommit, Supplier<SQLException> error) {
    }

    private final DataSource delegate;
    private final List<StatementFault> statementFaults = new CopyOnWriteArrayList<>();
    private final List<StatementHook> statementHooks = new CopyOnWriteArrayList<>();
    private final List<CommitFault> commitFaults = new CopyOnWriteArrayList<>();
    private final List<Supplier<SQLException>> connectionFaults = new CopyOnWriteArrayList<>();

    public FaultyDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** 下一条以 {@code sqlPrefix} 开头的语句在执行<b>之前</b>失败（真库没有执行它）。可以排多条，各生效一次。 */
    public FaultyDataSource failBefore(String sqlPrefix, Supplier<SQLException> error) {
        statementFaults.add(new StatementFault(sqlPrefix, error));
        return this;
    }

    /** 下一条以 {@code sqlPrefix} 开头的语句成功执行<b>之后</b>、返回给被测代码之前，在执行它的线程上跑一次 {@code action}。 */
    public FaultyDataSource afterOnce(String sqlPrefix, Action action) {
        statementHooks.add(new StatementHook(sqlPrefix, action));
        return this;
    }

    /** 下一次提交失败：{@code afterCommit = true} 时真库已经提交、调用方却看到异常（提交结果不明）；false 时没有提交。 */
    public FaultyDataSource failCommit(boolean afterCommit, Supplier<SQLException> error) {
        commitFaults.add(new CommitFault(afterCommit, error));
        return this;
    }

    /** 下一次取连接失败（库不可达）。 */
    public FaultyDataSource failConnection(Supplier<SQLException> error) {
        connectionFaults.add(error);
        return this;
    }

    /** 执行过的语句里以 {@code sqlPrefix} 开头的条数。 */
    public long executed(String sqlPrefix) {
        return calls.stream().filter(call -> call.sql().startsWith(sqlPrefix)).count();
    }

    /** 以 {@code sqlPrefix} 开头的各次执行里第 {@code position}（从 1 起）个参数，按执行顺序。 */
    public List<Object> params(String sqlPrefix, int position) {
        return calls.stream().filter(call -> call.sql().startsWith(sqlPrefix)).map(call -> call.params().get(position - 1)).toList();
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!connectionFaults.isEmpty()) {
            throw connectionFaults.remove(0).get();
        }
        Connection real = delegate.getConnection();
        connections.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, connectionHandler(real));
    }

    private InvocationHandler connectionHandler(Connection real) {
        return (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals("prepareStatement")) {
                PreparedStatement statement = (PreparedStatement) invoke(real, method, args);
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                        statementHandler(statement, (String) args[0]));
            }
            if (name.equals("commit")) {
                CommitFault fault = commitFaults.isEmpty() ? null : commitFaults.remove(0);
                if (fault != null && !fault.afterCommit()) {
                    throw fault.error().get();
                }
                invoke(real, method, args);
                commits.incrementAndGet();
                if (fault != null) {
                    throw fault.error().get();
                }
                return null;
            }
            if (name.equals("rollback")) {
                rollbacks.incrementAndGet();
            }
            return invoke(real, method, args);
        };
    }

    private InvocationHandler statementHandler(PreparedStatement real, String sql) {
        TreeMap<Integer, Object> bound = new TreeMap<>();
        return (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                bound.put(index, args[1]);
                return invoke(real, method, args);
            }
            if (!name.equals("executeUpdate") && !name.equals("executeQuery") && !name.equals("execute")) {
                return invoke(real, method, args);
            }
            for (StatementFault fault : statementFaults) {
                if (sql.startsWith(fault.sqlPrefix()) && statementFaults.remove(fault)) {
                    throw fault.error().get();
                }
            }
            calls.add(new Call(sql, new ArrayList<>(bound.values())));
            Object result = invoke(real, method, args);
            for (StatementHook hook : statementHooks) {
                if (sql.startsWith(hook.sqlPrefix()) && statementHooks.remove(hook)) {
                    hook.action().run();
                }
            }
            return result;
        };
    }

    private Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SQLException sql) {
                errors.add(sql);
            }
            throw e.getCause();
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
