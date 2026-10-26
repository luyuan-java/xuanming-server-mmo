package com.game.discovery.sql;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/** 权威业务库的每物理连接策略；由各服务静态@Bean在Druid属性绑定后、初始化前安装。 */
public final class StrongTiDbConnectionPolicy implements BeanPostProcessor, Ordered {
    public static final String SESSION_SQL = "SET SESSION tidb_snapshot='',tidb_read_staleness=0,"
            + "tidb_low_resolution_tso=OFF,tidb_read_consistency='strict',tidb_replica_read='leader',"
            + "tidb_txn_mode='pessimistic',transaction_isolation='READ-COMMITTED',"
            + "sql_mode=CONCAT(@@SESSION.sql_mode,',STRICT_TRANS_TABLES')";
    private final boolean requireTiDb;
    private final String domain;
    public StrongTiDbConnectionPolicy(String domain, boolean requireTiDb) {
        this.domain = java.util.Objects.requireNonNull(domain);
        this.requireTiDb = requireTiDb;
    }
    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (!requireTiDb || !(bean instanceof DataSource)) return bean;
        if (!(bean instanceof DruidDataSource pool) || pool.isInited()) {
            throw new IllegalStateException(domain + "权威库必须使用尚未初始化的Druid池，不能保证物理连接策略时拒启");
        }
        var init = pool.getConnectionInitSqls() == null ? new ArrayList<String>() : new ArrayList<>(pool.getConnectionInitSqls());
        // 玩家库与邮件/交易库可能共用同一个池；多个领域复用此策略时只安装一次。
        if (!init.contains(SESSION_SQL)) init.add(SESSION_SQL);
        pool.setConnectionInitSqls(init);
        if (pool.getProxyFilters().stream().noneMatch(StatementBudgetFilter.class::isInstance)) {
            pool.getProxyFilters().add(new StatementBudgetFilter());
        }
        return bean;
    }
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    /** 在建表/暴露服务前，用同一借出的物理会话复核；MySQL开发分支不发送TiDB变量。 */
    public static void verify(Connection connection, boolean requireTiDb) throws SQLException {
        verify(connection, requireTiDb, null);
    }

    /** 证明查询与后续数据读取共享调用方的单调截止时刻；每次数据库往返前重新收紧网络超时。 */
    public static void verify(Connection connection, boolean requireTiDb, Deadline deadline) throws SQLException {
        int previousTimeout = deadline == null ? -1 : connection.getNetworkTimeout();
        try {
            verifyWithinBudget(connection, requireTiDb, deadline);
        } finally {
            if (previousTimeout >= 0) {
                try {
                    if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, previousTimeout);
                } catch (SQLException | RuntimeException ignored) {
                    // 超时后连接可能已坏；Druid 在归还时丢弃它，不覆盖原始失败。
                }
            }
        }
    }

    private static void verifyWithinBudget(Connection connection, boolean requireTiDb, Deadline deadline) throws SQLException {
        constrainNetworkTimeout(connection, deadline);
        String version;
        try (var statement=connection.createStatement();var rows=statement.executeQuery("SELECT VERSION()")) {
            if (!rows.next()) throw new SQLException("无法确认权威库类型");
            version=rows.getString(1);
        }
        boolean tidb=version!=null && version.toLowerCase(Locale.ROOT).contains("tidb");
        if (requireTiDb && !tidb) throw new SQLException("权威库要求TiDB");
        if (!tidb) return;
        constrainNetworkTimeout(connection, deadline);
        try (var statement=connection.createStatement();var rows=statement.executeQuery("SELECT @@SESSION.tidb_snapshot,@@SESSION.tidb_read_staleness,@@SESSION.tidb_low_resolution_tso,@@SESSION.tidb_read_consistency,@@SESSION.tidb_replica_read,@@SESSION.tidb_txn_mode,@@SESSION.transaction_isolation")) {
            if (!rows.next() || !"".equals(rows.getString(1)) || rows.getLong(2)!=0 || rows.getBoolean(3)
                    || !"strict".equalsIgnoreCase(rows.getString(4)) || !"leader".equalsIgnoreCase(rows.getString(5))
                    || !"pessimistic".equalsIgnoreCase(rows.getString(6)) || !"READ-COMMITTED".equalsIgnoreCase(rows.getString(7))) {
                throw new SQLException("权威库会话不是悲观RC强读（snapshot/staleness/lowres/strict/leader）");
            }
        }
        if (deadline != null && deadline.expired()) throw new SQLTimeoutException("权威库强读核对超过请求预算");
    }

    private static void constrainNetworkTimeout(Connection connection, Deadline deadline) throws SQLException {
        if (deadline == null) return;
        long remaining = deadline.remainingMillis();
        if (remaining < 1) throw new SQLTimeoutException("权威库强读核对用完请求预算");
        connection.setNetworkTimeout(Runnable::run, (int) Math.min(remaining, Integer.MAX_VALUE));
    }


    /**
     * 只包住调用者拥有的新事务的同步业务段；提交/回滚必须在关闭作用域以后执行。
     * 非TiDB不发送会话变量。TiDB仅支持当前玩家Mapper的同步、非流式PreparedStatement；
     * 作用域不得跨线程、嵌套、换物理连接，也不允许通过unwrap绕过过滤器执行业务SQL。
     */
    public static StatementDeadline openStatementDeadline(Connection connection,
            java.util.function.LongSupplier remainingMillis,
            java.util.function.LongSupplier nanoClock) throws SQLException {
        java.util.Objects.requireNonNull(connection, "connection");
        java.util.Objects.requireNonNull(remainingMillis, "remainingMillis");
        java.util.Objects.requireNonNull(nanoClock, "nanoClock");
        var metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        String version = metadata.getDatabaseProductVersion();
        if (version == null || version.isBlank()) throw new SQLException("带时限玩家事务缺少数据库版本");
        boolean tidb = "TiDB".equalsIgnoreCase(product)
                || ("MySQL".equalsIgnoreCase(product) && version != null
                    && version.toLowerCase(Locale.ROOT).contains("tidb"));
        if (!tidb) {
            if (!"MySQL".equalsIgnoreCase(product) && !"H2".equalsIgnoreCase(product)) {
                throw new SQLException("带时限玩家事务不能使用未知数据库");
            }
            return new StatementDeadline();
        }
        if (!(connection instanceof com.alibaba.druid.pool.DruidPooledConnection pooled)
                || pooled.getOwnerThread() != Thread.currentThread()) {
            throw new SQLException("TiDB语句预算必须使用原线程的Druid借用连接");
        }
        var holder = pooled.getConnectionHolder();
        if (holder == null || !(holder.getDataSource() instanceof DruidDataSource pool)
                || !(holder.getConnection() instanceof com.alibaba.druid.proxy.jdbc.ConnectionProxy proxy)) {
            throw new SQLException("TiDB语句预算缺少原物理连接与池");
        }
        StatementBudgetFilter filter = null;
        for (var installed : pool.getProxyFilters()) {
            if (installed instanceof StatementBudgetFilter candidate) {
                if (filter != null) throw new SQLException("TiDB语句预算过滤器重复");
                filter = candidate;
            }
        }
        if (filter == null || filter.active.get() != null) {
            throw new SQLException("TiDB语句预算未在池初始化前安装或作用域嵌套");
        }
        var scope = new StatementDeadline(filter, pooled, holder, proxy.getRawObject(), remainingMillis, nanoClock);
        scope.samePhysical(proxy);
        filter.active.set(scope);
        return scope;
    }

    /** 一次原事务的资源作用域，不携带业务结果或重试规则。 */
    public static final class StatementDeadline implements AutoCloseable {
        private final StatementBudgetFilter filter;
        private final com.alibaba.druid.pool.DruidPooledConnection pooled;
        private final com.alibaba.druid.pool.DruidConnectionHolder holder;
        private final Connection raw;
        private static final long CANCELLATION_RESPONSE_MILLIS = 1000;
        private final java.util.function.LongSupplier remainingMillis;
        private final java.util.function.LongSupplier nanoClock;
        private final Thread thread = Thread.currentThread();
        private final java.util.Set<Throwable> timeouts =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private final java.util.Set<Throwable> delegateFailures =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private SQLException controlFailure;
        private boolean closed;

        private StatementDeadline() {
            filter = null; pooled = null; holder = null; raw = null; remainingMillis = null; nanoClock = null;
        }
        private StatementDeadline(StatementBudgetFilter filter,
                com.alibaba.druid.pool.DruidPooledConnection pooled,
                com.alibaba.druid.pool.DruidConnectionHolder holder, Connection raw,
                java.util.function.LongSupplier remainingMillis, java.util.function.LongSupplier nanoClock) {
            this.filter = filter; this.pooled = pooled; this.holder = holder;
            this.raw = raw; this.remainingMillis = remainingMillis; this.nanoClock = nanoClock;
        }
        public boolean tiDb() { return filter != null; }
        public boolean healthy() { return !closed && controlFailure == null; }

        /**
         * 只认本作用域业务delegate实际抛出的原异常对象，或本作用域自己的纯预算耗尽。
         * 包装可沿cause/nextException找到该对象；不按任意深层vendor码或suppressed猜超时。
         */
        public boolean businessTimedOut(Throwable failure) {
            return healthy() && origin(failure, timeouts) == 1;
        }

        /** 原纯工作段的Spring/路由预算错误可保留；不能将已记录的未知SQL错误重新包装成超时。 */
        public boolean pureWorkTimeoutAllowed(Throwable failure) {
            return healthy() && origin(failure, delegateFailures) == 0;
        }

        private static int origin(Throwable failure, java.util.Set<Throwable> originals) {
            if (failure == null) return -1;
            var pending = new java.util.ArrayDeque<Throwable>();
            var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            pending.add(failure);
            boolean found = false;
            while (!pending.isEmpty()) {
                Throwable current = pending.removeFirst();
                if (!visited.add(current)) continue;
                // 资源关闭等附加失败不能被原业务超时覆盖；不从suppressed寻找可映射的3024。
                if (current.getSuppressed().length != 0) return -1;
                if (originals.contains(current)) found = true;
                if (current.getCause() != null) pending.add(current.getCause());
                if (current instanceof SQLException sql && sql.getNextException() != null) pending.add(sql.getNextException());
            }
            return found ? 1 : 0;
        }

        private SQLException controlFailure(Throwable cause) {
            if (controlFailure == null) {
                controlFailure = new java.sql.SQLNonTransientConnectionException("TiDB语句预算控制失败，不能伪装业务超时", cause);
            }
            return controlFailure;
        }
        private long remaining() throws SQLException {
            if (!healthy() || Thread.currentThread() != thread) {
                throw controlFailure(new IllegalStateException("语句预算作用域失效或跨线程"));
            }
            final long value;
            try { value = remainingMillis.getAsLong(); }
            catch (RuntimeException failure) { throw controlFailure(failure); }
            if (value < 1) {
                var expired = new StatementBudgetExpired(this);
                timeouts.add(expired);
                throw expired;
            }
            return Math.min(value, Integer.MAX_VALUE);
        }
        private void samePhysical(com.alibaba.druid.proxy.jdbc.ConnectionProxy connection) throws SQLException {
            try {
                if (Thread.currentThread() != thread || pooled.getConnectionHolder() != holder
                        || holder.isDiscard() || pooled.isDisable()
                        || holder.getConnection() != pooled.getConnection()
                        || connection.getRawObject() != raw || raw.isClosed() || raw.getAutoCommit()) {
                    throw new SQLException("语句预算连接不是原Spring事务物理连接");
                }
            } catch (SQLException | RuntimeException failure) { throw controlFailure(failure); }
        }
        private void network(long millis, int originalLimit) throws SQLException {
            try {
                int requested = (int) Math.min(millis, Integer.MAX_VALUE);
                int target = originalLimit > 0 ? Math.min(originalLimit, requested) : requested;
                if (target < 1) throw new SQLException("语句网络上限不足");
                raw.setNetworkTimeout(Runnable::run, target);
                if (raw.getNetworkTimeout() != target) throw new SQLException("语句网络预算没有生效");
            } catch (SQLException | RuntimeException failure) { throw controlFailure(failure); }
        }

        private static long[] readCaps(java.sql.Statement control) throws SQLException {
            try (var rows = control.executeQuery(
                    "SELECT @@SESSION.max_execution_time,@@SESSION.tidb_dml_max_execution_time")) {
                if (!rows.next()) throw new SQLException("缺少TiDB服务器语句预算");
                long select = rows.getLong(1);
                if (rows.wasNull()) throw new SQLException("TiDB SELECT预算为空");
                long dml = rows.getLong(2);
                if (rows.wasNull() || select < 0 || dml < 0 || select > Integer.MAX_VALUE
                        || dml > Integer.MAX_VALUE || rows.next()) throw new SQLException("TiDB语句预算损坏");
                return new long[] {select, dml};
            }
        }
        private static void setCaps(java.sql.Statement control, long select, long dml) throws SQLException {
            control.execute("SET SESSION max_execution_time=" + select + ",tidb_dml_max_execution_time=" + dml);
        }
        private static void requireCaps(java.sql.Statement control, long select, long dml) throws SQLException {
            long[] actual = readCaps(control);
            if (actual[0] != select || actual[1] != dml) throw new SQLException("TiDB语句预算写入或恢复没有精确读回");
        }
        private static long capped(long original, long remaining) {
            return original > 0 ? Math.min(original, remaining) : remaining;
        }

        private SQLException quarantine(Throwable restoration, Throwable original) {
            var failure = new java.sql.SQLNonTransientConnectionException("TiDB取消响应或预算恢复未证明，连接禁止回池", restoration);
            if (original != null && original != restoration) failure.addSuppressed(original);
            controlFailure = failure; // 不可被调用者吞掉以后继续提交。
            try { raw.abort(Runnable::run); }
            catch (Throwable abort) { failure.addSuppressed(abort); }
            try {
                ((DruidDataSource) holder.getDataSource()).discardConnection(holder);
                if (!holder.isDiscard()) throw new SQLException("原holder没有被隔离");
            } catch (Throwable discard) { failure.addSuppressed(discard); }
            finally { pooled.disable(failure); }
            try {
                if (!pooled.isDisable() || pooled.getConnectionHolder() != null || !raw.isClosed()) {
                    throw new SQLException("不能证明原物理连接已经关闭并禁止回池");
                }
            } catch (SQLException | RuntimeException verification) { failure.addSuppressed(verification); }
            return failure;
        }

        private <T> T execute(com.alibaba.druid.proxy.jdbc.PreparedStatementProxy statement,
                SqlCall<T> delegate) throws SQLException {
            samePhysical(statement.getConnectionProxy());
            final int previousNetwork;
            final int previousQuerySeconds;
            try {
                if (statement.getFetchSize() != 0) throw new SQLException("带时限玩家事务不允许流式或游标结果");
                previousNetwork = raw.getNetworkTimeout();
                previousQuerySeconds = statement.getQueryTimeout();
                if (previousNetwork < 0 || previousQuerySeconds < 0) throw new SQLException("原语句或网络超时无效");
            } catch (SQLException | RuntimeException failure) { throw controlFailure(failure); }
            long[] previousCaps = null;
            boolean capsWriteAttempted = false;
            boolean queryWriteAttempted = false;
            long cancellationUntil = 0;
            boolean delegateStarted = false;
            SQLException unknownCancellation = null;
            Throwable original = null;
            try {
                network(remaining(), previousNetwork);
                try (var control = raw.createStatement()) {
                    control.setQueryTimeout(1);
                    previousCaps = readCaps(control);
                } catch (SQLException | RuntimeException failure) { throw controlFailure(failure); }

                long budget = remaining();
                if (previousQuerySeconds > 0) budget = Math.min(budget, previousQuerySeconds * 1000L);
                long select = capped(previousCaps[0], budget);
                long dml = capped(previousCaps[1], budget);
                long serverCap = Math.max(select, dml);
                long transportMillis = serverCap + CANCELLATION_RESPONSE_MILLIS;
                if (transportMillis > Integer.MAX_VALUE) throw controlFailure(new SQLException("取消响应上限超出JDBC表示范围"));
                network(remaining(), previousNetwork);
                capsWriteAttempted = true; // SET报错也可能部分生效，必须按原值恢复。
                try (var control = raw.createStatement()) {
                    control.setQueryTimeout(1);
                    setCaps(control, select, dml);
                } catch (SQLException | RuntimeException failure) { throw controlFailure(failure); }
                network(remaining(), previousNetwork);
                try (var control = raw.createStatement()) {
                    control.setQueryTimeout(1);
                    requireCaps(control, select, dml);
                } catch (SQLException | RuntimeException failure) { throw controlFailure(failure); }

                // SET/readback消耗原业务预算；到点不能再发业务。余量不另加给业务或COMMIT。
                remaining();
                int transportSeconds = (int) ((transportMillis + 999) / 1000);
                queryWriteAttempted = true;
                try {
                    statement.setQueryTimeout(transportSeconds);
                    if (statement.getQueryTimeout() != transportSeconds) throw new SQLException("取消响应queryTimeout没有读回");
                } catch (SQLException | RuntimeException failure) { throw controlFailure(failure); }
                network(transportMillis, previousNetwork); // 保原外部上限；不继承控制阶段临时缩短的值。
                remaining(); // 属性投影也不能让业务越过原截止。
                cancellationUntil = nanoClock.getAsLong()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(transportMillis);
                delegateStarted = true;
                try { return delegate.run(); }
                catch (SQLException failure) {
                    delegateFailures.add(failure);
                    if ((failure.getErrorCode() == 3024 && "HY000".equals(failure.getSQLState()))
                            || failure.getErrorCode() == 1205) {
                        timeouts.add(failure);
                    } else if (unknownCancellation(failure)) {
                        unknownCancellation = failure; // 客户端取消/断连不是已证实的服务端业务超时。
                    }
                    throw failure;
                } catch (RuntimeException | Error failure) {
                    delegateFailures.add(failure);
                    throw failure;
                }
            } catch (SQLException | RuntimeException | Error failure) {
                original = failure;
                throw failure;
            } finally {
                if (unknownCancellation != null) throw quarantine(unknownCancellation, original);
                // 没发业务时仅清理控制阶段；已发业务时复用原取消响应截止，绝不重新赠送1秒。
                long cleanupUntil = delegateStarted ? cancellationUntil
                        : nanoClock.getAsLong() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CANCELLATION_RESPONSE_MILLIS);
                try {
                    samePhysical(statement.getConnectionProxy());
                    if (capsWriteAttempted) {
                        cleanupNetwork(cleanupUntil, previousNetwork);
                        try (var control = raw.createStatement()) {
                            control.setQueryTimeout(1);
                            setCaps(control, previousCaps[0], previousCaps[1]);
                        }
                        cleanupNetwork(cleanupUntil, previousNetwork);
                        try (var control = raw.createStatement()) {
                            control.setQueryTimeout(1);
                            requireCaps(control, previousCaps[0], previousCaps[1]);
                        }
                    }
                    cleanupNetwork(cleanupUntil, previousNetwork);
                    if (queryWriteAttempted) {
                        statement.setQueryTimeout(previousQuerySeconds);
                        if (statement.getQueryTimeout() != previousQuerySeconds) throw new SQLException("原queryTimeout没有恢复");
                    }
                    cleanupNetwork(cleanupUntil, previousNetwork);
                    raw.setNetworkTimeout(Runnable::run, previousNetwork);
                    if (raw.getNetworkTimeout() != previousNetwork) throw new SQLException("原网络超时没有恢复");
                } catch (SQLException | RuntimeException | Error restoration) {
                    throw quarantine(restoration, original);
                }
            }
        }

        private void cleanupNetwork(long until, int previousNetwork) throws SQLException {
            long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(until - nanoClock.getAsLong());
            if (left < 1) throw new SQLTimeoutException("原取消响应/清理窗口已经用尽");
            network(Math.min(CANCELLATION_RESPONSE_MILLIS, left), previousNetwork);
        }
        private static boolean unknownCancellation(SQLException failure) {
            String state = failure.getSQLState();
            return failure instanceof SQLTimeoutException || failure.getErrorCode() == 1317
                    || (state != null && (state.startsWith("08") || state.equals("HYT00")
                        || state.equals("HYT01") || state.equals("S1T00") || state.equals("70100")));
        }

        @Override public void close() throws SQLException {
            if (closed) throw new SQLException("语句预算作用域重复关闭");
            if (filter == null) { closed = true; return; }
            if (Thread.currentThread() != thread || filter.active.get() != this) {
                throw controlFailure(new IllegalStateException("语句预算作用域所有权丢失"));
            }
            try {
                if (controlFailure == null) {
                    try { samePhysical((com.alibaba.druid.proxy.jdbc.ConnectionProxy) holder.getConnection()); }
                    catch (SQLException | RuntimeException finalIdentity) { quarantine(finalIdentity, null); }
                }
            } finally {
                filter.active.remove();
                closed = true;
            }
            if (controlFailure != null) {
                throw new java.sql.SQLNonTransientConnectionException("语句预算控制或恢复失败，拒绝提交", controlFailure);
            }
        }
    }

    private static final class StatementBudgetExpired extends SQLTimeoutException {
        private final StatementDeadline owner;
        private StatementBudgetExpired(StatementDeadline owner) {
            super("玩家原事务的语句剩余预算耗尽");
            this.owner = owner;
        }
    }

    @FunctionalInterface private interface SqlCall<T> { T run() throws SQLException; }

    private static final class StatementBudgetFilter extends com.alibaba.druid.filter.FilterAdapter {
        private final ThreadLocal<StatementDeadline> active = new ThreadLocal<>();
        private <T> T execute(com.alibaba.druid.proxy.jdbc.PreparedStatementProxy statement,
                SqlCall<T> delegate) throws SQLException {
            var scope = active.get();
            return scope == null ? delegate.run() : scope.execute(statement, delegate);
        }
        @Override public boolean preparedStatement_execute(com.alibaba.druid.filter.FilterChain chain,
                com.alibaba.druid.proxy.jdbc.PreparedStatementProxy statement) throws SQLException {
            return execute(statement, () -> chain.preparedStatement_execute(statement));
        }
        @Override public int preparedStatement_executeUpdate(com.alibaba.druid.filter.FilterChain chain,
                com.alibaba.druid.proxy.jdbc.PreparedStatementProxy statement) throws SQLException {
            return execute(statement, () -> chain.preparedStatement_executeUpdate(statement));
        }
        @Override public com.alibaba.druid.proxy.jdbc.ResultSetProxy preparedStatement_executeQuery(
                com.alibaba.druid.filter.FilterChain chain,
                com.alibaba.druid.proxy.jdbc.PreparedStatementProxy statement) throws SQLException {
            return execute(statement, () -> chain.preparedStatement_executeQuery(statement));
        }
        private void rejectUnsupported() throws SQLException {
            var scope = active.get();
            if (scope != null) throw scope.controlFailure(new SQLException("带时限玩家事务只允许同步PreparedStatement"));
        }
        @Override public void preparedStatement_addBatch(com.alibaba.druid.filter.FilterChain chain,
                com.alibaba.druid.proxy.jdbc.PreparedStatementProxy statement) throws SQLException {
            rejectUnsupported(); chain.preparedStatement_addBatch(statement);
        }
        @Override public int[] statement_executeBatch(com.alibaba.druid.filter.FilterChain chain,
                com.alibaba.druid.proxy.jdbc.StatementProxy statement) throws SQLException {
            rejectUnsupported(); return chain.statement_executeBatch(statement);
        }
        @Override public com.alibaba.druid.proxy.jdbc.StatementProxy connection_createStatement(
                com.alibaba.druid.filter.FilterChain chain,
                com.alibaba.druid.proxy.jdbc.ConnectionProxy connection) throws SQLException {
            rejectUnsupported(); return chain.connection_createStatement(connection);
        }
        @Override public com.alibaba.druid.proxy.jdbc.StatementProxy connection_createStatement(
                com.alibaba.druid.filter.FilterChain chain, com.alibaba.druid.proxy.jdbc.ConnectionProxy connection,
                int type, int concurrency) throws SQLException {
            rejectUnsupported(); return chain.connection_createStatement(connection, type, concurrency);
        }
        @Override public com.alibaba.druid.proxy.jdbc.StatementProxy connection_createStatement(
                com.alibaba.druid.filter.FilterChain chain, com.alibaba.druid.proxy.jdbc.ConnectionProxy connection,
                int type, int concurrency, int holdability) throws SQLException {
            rejectUnsupported(); return chain.connection_createStatement(connection, type, concurrency, holdability);
        }
        @Override public com.alibaba.druid.proxy.jdbc.CallableStatementProxy connection_prepareCall(
                com.alibaba.druid.filter.FilterChain chain, com.alibaba.druid.proxy.jdbc.ConnectionProxy connection,
                String sql) throws SQLException {
            rejectUnsupported(); return chain.connection_prepareCall(connection, sql);
        }
        @Override public com.alibaba.druid.proxy.jdbc.CallableStatementProxy connection_prepareCall(
                com.alibaba.druid.filter.FilterChain chain, com.alibaba.druid.proxy.jdbc.ConnectionProxy connection,
                String sql, int type, int concurrency) throws SQLException {
            rejectUnsupported(); return chain.connection_prepareCall(connection, sql, type, concurrency);
        }
        @Override public com.alibaba.druid.proxy.jdbc.CallableStatementProxy connection_prepareCall(
                com.alibaba.druid.filter.FilterChain chain, com.alibaba.druid.proxy.jdbc.ConnectionProxy connection,
                String sql, int type, int concurrency, int holdability) throws SQLException {
            rejectUnsupported(); return chain.connection_prepareCall(connection, sql, type, concurrency, holdability);
        }
    }

}
