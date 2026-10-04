package com.game.guild.store;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.PlayerProfiles.ConnectionSource;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildReject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会写事务的<b>唯一</b>基座（基线 inTx = retryOnDeadlock(runTxOnce)，guild_manage_repo.go:133-307；guild-spec §1.6、§7.6）。
 * 4.5 / 4.6 的仓储复用它，不各写一份重试助手。
 *
 * <p><b>骨架</b>：原始 JDBC 连接事务（{@code setAutoCommit(false)} / {@code commit} / {@code rollback}，同 JdbcFriendStore），
 * 每次尝试显式 READ COMMITTED。体返回 {@link TxOutcome}：{@link TxOutcome.Reject} 回滚、{@link TxOutcome.Ok} 与
 * {@link TxOutcome.CommitThenReject} 提交。体内累积的结果只能放在体的局部变量里、经返回值交出——每次重跑从零开始
 * （§1.6「结果变量约定」，基线单测 guild_manage_repo_test.go:433）。体内不许有非数据库副作用（推送、缓存失效都在提交之后）。
 *
 * <p><b>预算</b>：每次尝试各给一份完整子预算 {@code min(请求剩余, op.budgetMillis())}（1500 ms，解散 2500 ms）；子预算靠三样合起来实现：
 * 每条语句（含 COMMIT）之前查子预算（{@link GuildJdbc}）、语句查询超时 {@code ceil(剩余秒)}、连接级 {@code innodb_lock_wait_timeout=1}。
 * 取连接最多等子预算剩余。
 *
 * <p><b>分类</b>（错误号沿 cause 链取，{@link GuildSqlErrors}）：
 * <ol>
 *   <li>子预算已到期（语句前检查命中、查询超时，或出错时子预算已过）：请求还活着 → 计 {@code budgetExceeded}、记 ERROR、
 *       回 {@link GuildReject#WRITE_CONFLICT}，<b>不内部重试</b>；请求预算也用完了 → {@link DependencyException}（基线 runTxOnce
 *       的「txCtx 到期且父 ctx 未到期」判据，guild_manage_repo.go:153-181）；</li>
 *   <li>1213 / 9007 → 计 {@code deadlockObserved}，整体重跑，至多 {@value GuildLimits#MAX_TX_ATTEMPTS} 次，每次之间随机 10–50 ms；
 *       用尽 → 记 ERROR、WRITE_CONFLICT；退避期间请求预算用完 → {@link DependencyException}；</li>
 *   <li>1205 → 计 {@code lockWaitTimeout}、WRITE_CONFLICT，不重试；</li>
 *   <li>COMMIT 抛错且不是 1213 / 9007 / 1205（典型 CommunicationsException）→ <b>结果不明</b>，记 ERROR、WRITE_CONFLICT
 *       （classifyCommitErr，:203-234：客户端重试时先读当前状态，幂等分支兜住已生效的那一半）；</li>
 *   <li>其余 SQL 错误 → {@link DependencyException}；体抛的 {@link RuntimeException}（{@link GuildStoreException} 等）回滚后原样抛出。</li>
 * </ol>
 *
 * <p>线程安全；全部方法阻塞（JDBC），只在 guild-worker 线程上调用。
 */
public final class GuildTx {

    private static final Logger log = LoggerFactory.getLogger(GuildTx.class);

    /** 事务体：在一条连接、一份子预算上跑完一次尝试。 */
    @FunctionalInterface
    public interface Body<T> {
        TxOutcome<T> run(GuildJdbc tx) throws SQLException;
    }

    /** 事务外（自动提交）的读：语句挂在请求预算上。 */
    @FunctionalInterface
    public interface Read<T> {
        T run(GuildJdbc db) throws SQLException;
    }

    /** 测试缝：提交之前调用（模拟提交时连接被掐断 → 结果不明）。 */
    @FunctionalInterface
    interface CommitHook {
        void beforeCommit(Connection c) throws SQLException;
    }

    /** 测试缝：退避等待。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ConnectionSource connections;
    private final int queryTimeoutCapSeconds;
    private final GuildTxListener listener;
    private final ToLongFunction<GuildTxOp> budgets;
    private final LongSupplier backoffMillis;
    private final Sleeper sleeper;
    private volatile CommitHook commitHook = c -> { };

    /**
     * @param connections            取连接（按次限等，Druid {@code getConnection(long)}；见 PlayerProfiles.ConnectionSource）
     * @param queryTimeoutCapSeconds 语句查询超时的上限（秒，{@code xm.guild.query-timeout}）；实际取它与剩余预算（向上取整）的较小者
     * @param listener               指标钩子
     */
    public GuildTx(ConnectionSource connections, int queryTimeoutCapSeconds, GuildTxListener listener) {
        this(connections, queryTimeoutCapSeconds, listener, GuildTxOp::budgetMillis, GuildTx::randomBackoffMillis,
                Thread::sleep);
    }

    /** 测试用：可替换子预算、退避长度与等待方式（生产的子预算是代码常量，不开放配置）。 */
    GuildTx(ConnectionSource connections, int queryTimeoutCapSeconds, GuildTxListener listener,
            ToLongFunction<GuildTxOp> budgets, LongSupplier backoffMillis, Sleeper sleeper) {
        this.connections = connections;
        this.queryTimeoutCapSeconds = queryTimeoutCapSeconds;
        this.listener = listener;
        this.budgets = budgets;
        this.backoffMillis = backoffMillis;
        this.sleeper = sleeper;
    }

    void commitHookForTest(CommitHook hook) {
        this.commitHook = hook;
    }

    GuildTxListener listener() {
        return listener;
    }

    /**
     * 跑一个写事务（含死锁重跑）。业务拒绝与写冲突类都以 {@link TxOutcome} 返回；故障抛异常（见类注释）。
     *
     * @param op      指标标签，也决定子预算
     * @param request 整请求预算
     */
    public <T> TxOutcome<T> run(GuildTxOp op, Deadline request, Body<T> body) {
        for (int attempt = 1; ; attempt++) {
            if (request.expired()) {
                throw new DependencyException("帮会写事务 " + op.label() + " 开始前请求预算已用完");
            }
            Deadline sub = Deadline.after(Math.min(request.remainingMillis(), budgets.applyAsLong(op)));
            Attempt<T> result = attemptOnce(op, request, sub, body);
            if (!result.retry()) {
                return result.outcome();
            }
            if (attempt >= GuildLimits.MAX_TX_ATTEMPTS) {
                log.error("帮会写事务 {} 重跑 {} 次仍死锁，回 WRITE_CONFLICT: {}", op.label(), attempt, result.cause().toString());
                return TxOutcome.reject(GuildReject.WRITE_CONFLICT);
            }
            backoff(op, request);
        }
    }

    /**
     * 事务外的自动提交读（语句挂在请求预算上）。SQL 错误与预算用完都是依赖故障，抛 {@link DependencyException}；
     * 体抛的 {@link RuntimeException} 原样抛出。
     */
    public <T> T read(Deadline request, Read<T> body) {
        if (request.expired()) {
            throw new DependencyException("帮会读：请求预算已用完");
        }
        try (Connection c = connections.get(Math.max(1, request.remainingMillis()))) {
            return body.run(new GuildJdbc(c, request, queryTimeoutCapSeconds));
        } catch (SQLException e) {
            throw new DependencyException("帮会读失败", e);
        }
    }

    // ================================================================ 一次尝试

    /** 一次尝试的结局：要么给出最终结果，要么要求整体重跑（cause 是那次 1213 / 9007）。 */
    private record Attempt<T>(TxOutcome<T> outcome, boolean retry, SQLException cause) {
        static <T> Attempt<T> done(TxOutcome<T> outcome) {
            return new Attempt<>(outcome, false, null);
        }

        static <T> Attempt<T> again(SQLException cause) {
            return new Attempt<>(null, true, cause);
        }
    }

    private <T> Attempt<T> attemptOnce(GuildTxOp op, Deadline request, Deadline sub, Body<T> body) {
        Connection c;
        try {
            c = connections.get(Math.max(1, sub.remainingMillis()));
        } catch (SQLException e) {
            return classifyStatementError(op, request, sub, e);
        }
        try {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            boolean finished = false;
            try {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                TxOutcome<T> outcome;
                try {
                    outcome = body.run(new GuildJdbc(c, sub, queryTimeoutCapSeconds));
                } catch (SQLException e) {
                    rollbackQuietly(c);
                    finished = true;
                    return classifyStatementError(op, request, sub, e);
                }
                if (outcome instanceof TxOutcome.Reject<T>) {
                    rollbackQuietly(c);
                    finished = true;
                    return Attempt.done(outcome);
                }
                if (sub.expired()) {
                    // COMMIT 也是一条语句：子预算过了就不提交（gate 那边可能已经判超时）
                    rollbackQuietly(c);
                    finished = true;
                    return classifyStatementError(op, request, sub, new GuildJdbc.BudgetExpired("COMMIT"));
                }
                commitHook.beforeCommit(c);
                try {
                    c.commit();
                } catch (SQLException e) {
                    finished = true;
                    return classifyCommitError(op, e);
                }
                finished = true;
                return Attempt.done(outcome);
            } finally {
                // 没走到提交 / 回滚（体抛 RuntimeException / Error）：先回滚再还原自动提交——切回 true 会隐式提交半截的事务
                if (!finished) {
                    rollbackQuietly(c);
                }
                restore(c, autoCommit, isolation);
            }
        } catch (SQLException e) {
            // getAutoCommit / setAutoCommit / 隔离级别出错：还没有任何写提交
            return classifyStatementError(op, request, sub, e);
        } finally {
            // 归还连接的失败不能把一次已提交的写报成失败
            closeQuietly(c);
        }
    }

    /** 体内语句（或取连接、开事务）出错的分类；见类注释第 1–3、5 条。 */
    private <T> Attempt<T> classifyStatementError(GuildTxOp op, Deadline request, Deadline sub, SQLException e) {
        if (e instanceof GuildJdbc.BudgetExpired || sub.expired() || GuildSqlErrors.isQueryTimeout(e)) {
            if (request.expired()) {
                throw new DependencyException("帮会写事务 " + op.label() + " 跑满请求预算", e);
            }
            listener.budgetExceeded(op);
            log.error("帮会写事务 {} 跑满子预算 {} ms，回 WRITE_CONFLICT: {}", op.label(), budgets.applyAsLong(op), e.toString());
            return Attempt.done(TxOutcome.reject(GuildReject.WRITE_CONFLICT));
        }
        if (GuildSqlErrors.isRetryable(e)) {
            listener.deadlockObserved(op);
            return Attempt.again(e);
        }
        if (GuildSqlErrors.isLockWaitTimeout(e)) {
            listener.lockWaitTimeout(op);
            return Attempt.done(TxOutcome.reject(GuildReject.WRITE_CONFLICT));
        }
        throw new DependencyException("帮会写事务 " + op.label() + " 失败", e);
    }

    /** COMMIT 出错的分类（classifyCommitErr）：可重试与 1205 照常分类，其余一律「结果不明」。 */
    private <T> Attempt<T> classifyCommitError(GuildTxOp op, SQLException e) {
        if (GuildSqlErrors.isRetryable(e)) {
            listener.deadlockObserved(op);
            return Attempt.again(e);
        }
        if (GuildSqlErrors.isLockWaitTimeout(e)) {
            listener.lockWaitTimeout(op);
            return Attempt.done(TxOutcome.reject(GuildReject.WRITE_CONFLICT));
        }
        log.error("帮会写事务 {} 提交结果不明（可能已生效），回 WRITE_CONFLICT: {}", op.label(), e.toString());
        return Attempt.done(TxOutcome.reject(GuildReject.WRITE_CONFLICT));
    }

    /**
     * 10–50 ms 随机退避：两个互相回滚的事务若同时重来，大概率再撞一次，抖动把它们错开。退避期间请求预算会用完 →
     * {@link DependencyException}（基线 select ctx.Done，guild_manage_repo.go:255-264）。
     */
    private void backoff(GuildTxOp op, Deadline request) {
        long wait = backoffMillis.getAsLong();
        if (request.remainingMillis() <= wait) {
            throw new DependencyException("帮会写事务 " + op.label() + " 在死锁退避期间请求预算用完");
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyException("帮会写事务 " + op.label() + " 退避被中断", e);
        }
    }

    static long randomBackoffMillis() {
        return ThreadLocalRandom.current().nextLong(GuildLimits.TX_RETRY_BACKOFF_MIN_MS, GuildLimits.TX_RETRY_BACKOFF_MAX_MS);
    }

    long nextBackoffMillis() {
        return backoffMillis.getAsLong();
    }

    void sleepBackoff(long millis) throws InterruptedException {
        sleeper.sleep(millis);
    }

    ConnectionSource connections() {
        return connections;
    }

    private static void restore(Connection c, boolean autoCommit, int isolation) {
        try {
            if (c.getAutoCommit() != autoCommit) {
                c.setAutoCommit(autoCommit);
            }
            if (c.getTransactionIsolation() != isolation) {
                c.setTransactionIsolation(isolation);
            }
        } catch (SQLException e) {
            log.warn("还原连接状态失败: {}", e.toString());
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            log.warn("归还连接失败: {}", e.toString());
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warn("回滚失败（连接断开时服务端会自动回滚）: {}", e.toString());
        }
    }
}
