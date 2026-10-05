package com.game.guild.store;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.PlayerProfiles.ConnectionSource;
import com.game.guild.rules.GuildLimits;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产指令后台写的事务基座（基线 assetop.WithTxRetry + isRetryableBackground，seq.go:387-425、asset_store.go:324-329；
 * guild-economy-spec §1.5「后台写」、§7.3）。重排、毒行推迟、终结、人工终结、清理都走它；请求路径的预留 / 升级照旧走 {@link GuildTx}。
 *
 * <p>与 {@link GuildTx} 的区别（基线 asset_store.go:16-19：这里全是后台写，没有玩家在等「稍后重试」）：
 * <ul>
 *   <li><b>1205 也重跑</b>：1213 / 9007 / 1205 都表示事务已整体回滚、重跑安全；锁等待超时之后再排一次队，比把一个已定的终局留在内存里、
 *       等下一轮再投一次 scene 划算得多；</li>
 *   <li>尝试次数由调用方给（{@value GuildLimits#BACKGROUND_TX_ATTEMPTS}，清理每行 1 次）；两次尝试之间指数退避
 *       {@code min(10 ms << i, 200 ms) × (0.8 + 0.4·rnd)}（抖动用 {@link ThreadLocalRandom}：被猜到不产生任何后果，seq.go:178-180）；</li>
 *   <li>截止时刻由调用方给（Store 已取 {@code min(自身子预算, 调用方的 settle 截止)}）：每条语句前查它、语句查询超时取剩余秒数、
 *       退避等待前看剩余够不够——用尽即 {@link DependencyException}，不在一个已经没有预算的截止上睡满；</li>
 *   <li>业务拒绝不经这里：后台写没有「拒绝」，体直接返回结果；结果只在成功返回前交给调用方，每次重跑从零开始（asset_store.go:678-679）。</li>
 * </ul>
 * 隔离级每次尝试显式 READ COMMITTED（与预留事务同一前提：AllocateSeq 的未决行普通读只在 RC 下看得全，asset_store.go:29-33）。
 * COMMIT 抛的非可重试错误按依赖故障上抛（结果不明：终结的 CAS 若已生效，下一轮 CAS 落空，不会做第二遍对侧账）。
 *
 * <p>线程安全；全部方法阻塞（JDBC），只在后台 worker / settle 执行器 / 清理线程上调用，绝不在 Netty I/O 或请求的 guild-worker 上等退避。
 */
public final class BackgroundTx {

    private static final Logger log = LoggerFactory.getLogger(BackgroundTx.class);

    /** 后台写的种类（指标 / 日志标签，固定集合；不放任何 id）。 */
    public enum Op {
        RESCHEDULE("reschedule"),
        POISON("poison"),
        FINALIZE("finalize"),
        MANUAL_RESOLVE("manual_resolve"),
        CLEANUP("cleanup");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 重跑事件的指标钩子（装配时可绑到 Micrometer；锁序并发回归换成记录器，断言「被重跑吸收掉的死锁」为零）。实现必须便宜、不抛异常。
     */
    public interface Listener {
        /** 一次尝试因 1213 / 9007 被整体回滚（含用尽重试的最后一次）。 */
        void deadlockObserved(Op op);

        /** 一次尝试因 1205 被回滚（后台写会重跑它）。 */
        void lockWaitTimeout(Op op);

        Listener NONE = new Listener() {
            @Override
            public void deadlockObserved(Op op) {
            }

            @Override
            public void lockWaitTimeout(Op op) {
            }
        };
    }

    /** 事务体：在一条连接上跑完一次尝试，返回结果（可以是 null）。 */
    @FunctionalInterface
    public interface Body<T> {
        T run(GuildJdbc tx) throws SQLException;
    }

    /** 测试缝：退避等待。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final ConnectionSource connections;
    private final int queryTimeoutCapSeconds;
    private final Listener listener;
    private final DoubleSupplier jitter;
    private final Sleeper sleeper;

    /**
     * @param connections            取连接（按次限等）
     * @param queryTimeoutCapSeconds 语句查询超时上限（秒，与 {@link GuildTx} 同一配置项）
     * @param listener               重跑事件钩子
     */
    public BackgroundTx(ConnectionSource connections, int queryTimeoutCapSeconds, Listener listener) {
        this(connections, queryTimeoutCapSeconds, listener, () -> ThreadLocalRandom.current().nextDouble(), Thread::sleep);
    }

    /** 测试用：可替换抖动源与等待方式。 */
    BackgroundTx(ConnectionSource connections, int queryTimeoutCapSeconds, Listener listener, DoubleSupplier jitter,
                 Sleeper sleeper) {
        this.connections = connections;
        this.queryTimeoutCapSeconds = queryTimeoutCapSeconds;
        this.listener = listener;
        this.jitter = jitter;
        this.sleeper = sleeper;
    }

    /**
     * 跑一个后台写事务，1213 / 9007 / 1205 整体重跑至多 {@code attempts} 次。
     *
     * @param deadline 本次写的截止时刻（调用方已取 min(子预算, settle)）；每次尝试共用它，不各给一份
     * @throws DependencyException 非可重试的 SQL 错误、重试用尽、截止时刻用尽、COMMIT 结果不明（cause 是最后一次 SQL 错误）
     * @throws RuntimeException    体抛的运行期异常（回滚后原样抛出，不重试）
     */
    public <T> T run(Op op, Deadline deadline, int attempts, Body<T> body) {
        if (attempts < 1) {
            throw new IllegalArgumentException("后台写 " + op.label() + ": attempts 必须 >= 1，实际 " + attempts);
        }
        SQLException last = null;
        for (int i = 0; i < attempts; i++) {
            if (deadline.expired()) {
                throw new DependencyException("后台写 " + op.label() + " 截止时刻已过（第 " + (i + 1) + " 次尝试之前）", last);
            }
            try {
                return attemptOnce(deadline, body);
            } catch (SQLException e) {
                last = e;
                if (GuildSqlErrors.isRetryable(e)) {
                    listener.deadlockObserved(op);
                } else if (GuildSqlErrors.isLockWaitTimeout(e)) {
                    listener.lockWaitTimeout(op);
                } else {
                    throw new DependencyException("后台写 " + op.label() + " 失败", e);
                }
            }
            if (i == attempts - 1) {
                break; // 最后一次失败后不必再睡：那只会把同一个结论晚几十毫秒告诉调用方
            }
            backoff(op, deadline, i, last);
        }
        log.warn("后台写 {} 重跑 {} 次仍被回滚: {}", op.label(), attempts, last.toString());
        throw new DependencyException("后台写 " + op.label() + " 重跑 " + attempts + " 次仍失败", last);
    }

    /**
     * 事务外的自动提交语句（ListDue、Claim 的 CAS、各种读；Claim 刻意不进事务，asset_store.go:54-59）。语句挂在给定截止时刻上；
     * SQL 错误与截止用尽都是依赖故障。
     */
    public <T> T autocommit(Deadline deadline, Body<T> body) {
        if (deadline.expired()) {
            throw new DependencyException("后台自动提交语句：截止时刻已过");
        }
        try (Connection c = connections.get(Math.max(1, deadline.remainingMillis()))) {
            if (!c.getAutoCommit()) {
                c.setAutoCommit(true);
            }
            return body.run(new GuildJdbc(c, deadline, queryTimeoutCapSeconds));
        } catch (SQLException e) {
            throw new DependencyException("后台自动提交语句失败", e);
        }
    }

    /** 一次尝试；SQL 错误原样抛出由 {@link #run} 分类。体抛的运行期异常回滚后原样抛出。 */
    private <T> T attemptOnce(Deadline deadline, Body<T> body) throws SQLException {
        Connection c = connections.get(Math.max(1, deadline.remainingMillis()));
        try {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            boolean finished = false;
            try {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                c.setAutoCommit(false);
                T result;
                try {
                    result = body.run(new GuildJdbc(c, deadline, queryTimeoutCapSeconds));
                } catch (SQLException e) {
                    rollbackQuietly(c);
                    finished = true;
                    throw e;
                }
                if (deadline.expired()) {
                    rollbackQuietly(c);
                    finished = true;
                    throw new GuildJdbc.BudgetExpired("COMMIT");
                }
                c.commit();
                finished = true;
                return result;
            } finally {
                if (!finished) {
                    rollbackQuietly(c);
                }
                restore(c, autoCommit, isolation);
            }
        } finally {
            closeQuietly(c);
        }
    }

    private void backoff(Op op, Deadline deadline, int attemptIndex, SQLException last) {
        long wait = backoffMillis(attemptIndex, jitter);
        if (deadline.remainingMillis() <= wait) {
            throw new DependencyException("后台写 " + op.label() + " 在重跑退避期间截止时刻用尽", last);
        }
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            DependencyException failure = new DependencyException("后台写 " + op.label() + " 退避被中断", e);
            failure.addSuppressed(last);
            throw failure;
        }
    }

    /**
     * 第 {@code attemptIndex} 次失败之后的退避（txBackoff = NextAttemptMs(0, i, 10 ms, 200 ms, rnd)，seq.go:420-425）：
     * {@code min(10 << i, 200) × (0.8 + 0.4·rnd)}，rnd 越界夹回 [0, 1)。与重投循环的退避是同一个公式（{@code AssetOpDecisions.nextAttemptMs}）；
     * 这里内联一份是为了不让 store 包反向依赖 asset 包，两者逐值相同由 BackgroundTxTest 钉住。
     */
    static long backoffMillis(int attemptIndex, DoubleSupplier rnd) {
        int shift = Math.min(Math.max(attemptIndex, 0), 16);
        long delay = Math.min(GuildLimits.BACKGROUND_TX_BASE_BACKOFF_MS << shift, GuildLimits.BACKGROUND_TX_MAX_BACKOFF_MS);
        double j = rnd == null ? 0.5 : rnd.getAsDouble();
        if (Double.isNaN(j) || j < 0) {
            j = 0;
        } else if (j >= 1) {
            j = Math.nextDown(1.0);
        }
        return (long) (delay * (0.8 + 0.4 * j));
    }

    /** 后台写的可重试分类（isRetryableBackground，asset_store.go:324-329）：1213 / 9007 / 1205，沿 cause 链取错误号。 */
    public static boolean isRetryable(Throwable error) {
        return GuildSqlErrors.isRetryable(error) || GuildSqlErrors.isLockWaitTimeout(error);
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
