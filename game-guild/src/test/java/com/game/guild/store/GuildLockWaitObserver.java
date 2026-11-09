package com.game.guild.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 测试编排的只读观察器：连接必须在占锁之前取得；诊断失败不冒充已观察到锁等待。 */
final class GuildLockWaitObserver implements AutoCloseable {
    private static final String SQL = "SELECT COUNT(*) FROM information_schema.INNODB_TRX t JOIN information_schema.PROCESSLIST p"
            + " ON p.ID = t.trx_mysql_thread_id WHERE t.trx_state = 'LOCK WAIT' AND p.DB = ?";

    @FunctionalInterface interface Pause { void sleep(long nanos) throws InterruptedException; }

    private Connection connection;
    private final String database;
    private final LongSupplier clock;
    private final Pause pause;
    private final int originalNetworkTimeout;
    private boolean usable = true;

    GuildLockWaitObserver(Connection connection, String database) throws SQLException {
        this(connection, database, System::nanoTime, TimeUnit.NANOSECONDS::sleep);
    }

    GuildLockWaitObserver(Connection connection, String database, LongSupplier clock, Pause pause) throws SQLException {
        this.connection = Objects.requireNonNull(connection);
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        this.pause = Objects.requireNonNull(pause);
        try { originalNetworkTimeout = connection.getNetworkTimeout(); }
        catch (SQLException | RuntimeException | Error failure) {
            try { connection.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    boolean await(int wanted, long limitMillis) {
        if (wanted < 1 || limitMillis < 0 || limitMillis > 10_000) throw new IllegalArgumentException("诊断等待参数超界");
        long started = clock.getAsLong(), budget = TimeUnit.MILLISECONDS.toNanos(limitMillis);
        try {
            while (remaining(started, budget) > 0) {
                if (!usable) { sleep(remaining(started, budget)); return false; }
                try {
                    long left = remaining(started, budget);
                    int networkMillis = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(left));
                    connection.setNetworkTimeout(Runnable::run, networkMillis);
                    try (var query = connection.prepareStatement(SQL)) {
                        left = remaining(started, budget);
                        if (left <= 0) return false;
                        query.setQueryTimeout((int) Math.max(1, (left + 999_999_999L) / 1_000_000_000L));
                        query.setString(1, database);
                        left = remaining(started, budget);
                        if (left <= 0) return false;
                        connection.setNetworkTimeout(Runnable::run,
                                (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                        if (remaining(started, budget) <= 0) return false;
                        try (var rows = query.executeQuery()) {
                            if (!rows.next()) throw new SQLException("诊断COUNT缺少结果");
                            long count = rows.getLong(1);
                            if (rows.wasNull() || rows.next()) throw new SQLException("诊断COUNT结果不唯一");
                            if (count >= wanted && remaining(started, budget) > 0) return true;
                        }
                    }
                } catch (SQLException failure) {
                    // 不在持有测试锁的区间重新连接，也不让诊断连接进入业务NORMAL的归属门禁。
                    usable = false;
                    try { close(); } catch (SQLException cleanup) {
                        failure.addSuppressed(cleanup);
                        throw new AssertionError("诊断连接关闭未确认", failure);
                    }
                    sleep(remaining(started, budget));
                    return false;
                }
                sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(5), remaining(started, budget)));
            }
            return false;
        } finally {
            if (usable && connection != null) {
                try { connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout); }
                catch (SQLException failure) {
                    usable = false;
                    try { close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                    throw new AssertionError("诊断连接期限恢复未确认", failure);
                }
            }
        }
    }

    private long remaining(long started, long budget) { return Math.max(0, budget - (clock.getAsLong() - started)); }
    private void sleep(long nanos) {
        if (nanos <= 0) return;
        try { pause.sleep(nanos); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    @Override public void close() throws SQLException {
        usable = false;
        if (connection == null) return;
        Connection current = connection;
        try { current.close(); connection = null; }
        catch (SQLException failure) {
            try { current.abort(Runnable::run); connection = null; }
            catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
