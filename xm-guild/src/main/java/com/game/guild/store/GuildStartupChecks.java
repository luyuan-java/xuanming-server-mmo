package com.game.guild.store;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.guild.rules.GuildLimits;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动期的两道数据库检查（guild-spec §7.11 第 3–4 步；基线 guild.go:124-142）：建表之后、暴露 Dubbo 之前调用，任一失败即拒启。
 *
 * <ol>
 *   <li>{@link #checkServerVersion}：{@code SELECT VERSION()} 按 {@link ServerVersion} 判定（MySQL ≥ 8.0.29 或 TiDB）；</li>
 *   <li>{@link #ensureGlobalInsertGuard}：确保全局插入守卫哨兵行 guild_player_state(0) 存在；</li>
 *   <li>{@link #checkAffectedRowsSemantics}（4.5）：连接池必须是「实际改动行数」语义（useAffectedRows=true）。</li>
 * </ol>
 * 预算照基线：版本检查 5 s、建哨兵行 10 s（guild.go:124-142；后者必须明显长于 GET_LOCK 的 5 s 等待）。
 * 建哨兵行那条专用连接上不设语句超时上限（GET_LOCK 要等满 5 s，超过 {@code xm.guild.query-timeout}）。阻塞 JDBC；只在启动线程上调用。
 */
public final class GuildStartupChecks {

    private static final Logger log = LoggerFactory.getLogger(GuildStartupChecks.class);

    /**
     * 建哨兵行的会话级命名锁（死锁复核 C1，guild_manage_repo.go:841-845）。GET_LOCK 的名字在整个 MySQL 实例内共用、不分库，所以带上库名
     * （Java 的库是 xm_java）；与 pbmysql 的结构同步咨询锁不同名，两者在启动期先后获取、从不嵌套。
     */
    public static final String INSERT_GUARD_INIT_LOCK_NAME = "xm_guild_insert_guard_init:xm_java";

    /** GET_LOCK 的等待上限（秒），必须明显短于调用方预算（基线给整步 10 s）。 */
    static final int INSERT_GUARD_INIT_LOCK_WAIT_SECONDS = 5;

    /** RELEASE_LOCK 的独立超时：调用方预算可能已到期，锁仍然要放。 */
    static final long INSERT_GUARD_INIT_RELEASE_TIMEOUT_MS = 2_000L;
    /** 放宽网络超时时在剩余启动预算之外再留的余量（让 GET_LOCK 的 0 结果先于驱动断连回来）。 */
    static final int NETWORK_TIMEOUT_MARGIN_MS = 1_000;

    static final String SELECT_VERSION = "SELECT VERSION()";
    static final String GET_LOCK = "SELECT GET_LOCK(?, ?)";
    static final String RELEASE_LOCK = "SELECT RELEASE_LOCK(?)";

    private final GuildTx tx;

    public GuildStartupChecks(GuildTx tx) {
        this.tx = tx;
    }

    /**
     * 读 VERSION() 并判定（CheckServerVersion，server_version.go:30-42）。
     *
     * @return 版本串（供启动日志）
     * @throws IllegalStateException 版本不满足下限（拒启）
     * @throws DependencyException   读不出来
     */
    public String checkServerVersion(Deadline deadline) {
        String version = tx.read(deadline, db -> db.one(SELECT_VERSION, rs -> rs.getString(1)));
        String rejection = ServerVersion.rejection(version);
        if (rejection != null) {
            throw new IllegalStateException(rejection);
        }
        return version;
    }

    /**
     * 保证全局插入守卫哨兵行存在（EnsureGlobalInsertGuard，guild_manage_repo.go:865-949）；幂等，多实例同时首建也不会死锁。
     *
     * <ol>
     *   <li>普通读：行已在（常态）就返回，不取任何锁——运行中的实例可能正持着它，新实例启动不该排在后面；</li>
     *   <li>缺行：在一条专用连接上 {@code GET_LOCK(name, 5)}；结果不是 1 → 复读一次，行已在算成功，否则拒启（绝不无锁插入）；</li>
     *   <li>同一连接上复读，仍缺就自动提交 {@code INSERT IGNORE (0, now)}（1213 / 9007 有界重跑，op = insert_guard；1205 拒启），
     *       插完再读一次确认（INSERT IGNORE 会把非重复类异常降级成告警，不能只看返回值）；</li>
     *   <li>最后 {@code RELEASE_LOCK}（独立 2 s 超时，失败只记日志：连接断开时服务端也会释放）。</li>
     * </ol>
     * 复读、INSERT、确认都走同一条连接：命名锁是会话级的，语句落到池里别的连接上就不在锁下。
     *
     * @param nowMs 只写进 updated_ms（诊断用）
     * @throws GuildStoreException 行仍缺（{@link GuildStoreException.Kind#GLOBAL_INSERT_GUARD_MISSING}）
     * @throws IllegalStateException 建行遇 1205 或死锁重跑用尽
     * @throws DependencyException   SQL 故障
     */
    public void ensureGlobalInsertGuard(long nowMs, Deadline deadline) {
        List<Long> guard = List.of(JdbcGuildStore.GLOBAL_INSERT_GUARD_PLAYER_ID);
        if (tx.read(deadline, db -> JdbcGuildStore.missingPlayerStateRows(db, guard)).isEmpty()) {
            return;
        }
        try (Connection c = tx.connections().get(Math.max(1, deadline.remainingMillis()))) {
            // GET_LOCK 要能等满 5 s 拿到 0 再复读（基线 insertGuardInitLockWaitSeconds 必须明显短于调用方 ctx）：
            // 连接池按 URL 的 socketTimeout（4 s）给每条物理连接设了网络超时，等锁期间服务端不回任何字节，
            // 不放宽的话驱动 4 s 就断连、复读分支永远走不到。放宽到剩余启动预算，还回池前恢复（同 GuildTables.sync）。
            int socketTimeout = c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run, (int) Math.max(1, deadline.remainingMillis()) + NETWORK_TIMEOUT_MARGIN_MS);
            try {
                // 不设查询超时上限：GET_LOCK 要等到 5 s，超过 xm.guild.query-timeout
                GuildJdbc db = new GuildJdbc(c, deadline, 0);
                Long got = db.one(GET_LOCK, rs -> {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }, INSERT_GUARD_INIT_LOCK_NAME, INSERT_GUARD_INIT_LOCK_WAIT_SECONDS);
                if (got == null || got != 1) {
                    if (JdbcGuildStore.missingPlayerStateRows(db, guard).isEmpty()) {
                        return; // 持锁者已建好、只是还没放锁
                    }
                    throw new GuildStoreException(GuildStoreException.Kind.GLOBAL_INSERT_GUARD_MISSING,
                            "命名锁 " + INSERT_GUARD_INIT_LOCK_NAME + " 在 " + INSERT_GUARD_INIT_LOCK_WAIT_SECONDS
                                    + " s 内没拿到（结果 " + got + "），哨兵行仍缺失");
                }
                try {
                    insertGuardUnderLock(db, guard, nowMs, deadline);
                } finally {
                    releaseInitLock(c);
                }
            } finally {
                if (!c.isClosed()) {
                    c.setNetworkTimeout(Runnable::run, socketTimeout);
                }
            }
        } catch (SQLException e) {
            throw new DependencyException("建全局插入守卫哨兵行失败", e);
        }
    }

    /**
     * 4.5：探测连接池是不是「实际改动行数」语义（{@code useAffectedRows=true}；guild-economy-spec §7.3、§9.2 第 6 条、§11.3 Java 增项）。
     * 计数行带上限的 upsert（C1）靠「0 行 = 达上限」判定，写入自检与「加 0 跳过」也都依赖它；漏配时 C1 达上限会回 1（found rows）被当成
     * 新插入，捐献次数与限购被突破、帮贡多发——语句本身看不出来，只能在启动期探测、拒启。
     *
     * <p>做法：一个 RC 事务里对一个永远不属于任何玩家的计数键（player_id = 0、counter_kind = UNSPECIFIED）连做两次
     * 「改成原值」的 upsert，第二次在实际改动行数语义下必须是 0（found rows 语义下是 1），然后<b>回滚</b>（不留任何行）。
     * 多实例同时启动只会在这个键上短暂排队。
     *
     * @throws IllegalStateException 语义不对（拒启）
     * @throws DependencyException   SQL 故障
     */
    public void checkAffectedRowsSemantics(Deadline deadline) {
        try (Connection c = tx.connections().get(Math.max(1, deadline.remainingMillis()))) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                GuildJdbc db = new GuildJdbc(c, deadline, 0);
                db.update(AFFECTED_ROWS_PROBE, GLOBAL_PROBE_PLAYER_ID, UNSPECIFIED_COUNTER_KIND);
                int second = db.update(AFFECTED_ROWS_PROBE, GLOBAL_PROBE_PLAYER_ID, UNSPECIFIED_COUNTER_KIND);
                if (second != 0) {
                    throw new IllegalStateException("帮会连接池不是「实际改动行数」语义（改成原值的 upsert 回了 " + second
                            + " 行）：连接串必须带 useAffectedRows=true，否则计数行的上限判定失效");
                }
            } finally {
                c.rollback();
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new DependencyException("探测连接池影响行数语义失败", e);
        }
    }

    /** 探测用的计数键：player_id = 0 永远不是真实玩家（同全局插入守卫的约定）。 */
    private static final long GLOBAL_PROBE_PLAYER_ID = 0L;
    private static final int UNSPECIFIED_COUNTER_KIND =
            com.game.guild.store.pb.GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_UNSPECIFIED_VALUE;
    static final String AFFECTED_ROWS_PROBE = "INSERT INTO guild_daily_counter"
            + " (player_id, counter_kind, ref_id, period_key, used_count, updated_ms) VALUES (?, ?, 0, 0, 0, 0)"
            + " ON DUPLICATE KEY UPDATE used_count = used_count";

    private void insertGuardUnderLock(GuildJdbc db, List<Long> guard, long nowMs, Deadline deadline) throws SQLException {
        if (JdbcGuildStore.missingPlayerStateRows(db, guard).isEmpty()) {
            return; // 前一个持锁者已经建好并提交
        }
        GuildTxListener listener = tx.listener();
        for (int attempt = 1; ; attempt++) {
            try {
                db.update(JdbcGuildStore.ENSURE_PLAYER_STATE, JdbcGuildStore.GLOBAL_INSERT_GUARD_PLAYER_ID, nowMs);
                break;
            } catch (SQLException e) {
                if (GuildSqlErrors.isLockWaitTimeout(e)) {
                    listener.lockWaitTimeout(GuildTxOp.INSERT_GUARD);
                    throw new IllegalStateException("建全局插入守卫哨兵行遇锁等待超时（1205）", e);
                }
                if (!GuildSqlErrors.isRetryable(e)) {
                    throw e;
                }
                listener.deadlockObserved(GuildTxOp.INSERT_GUARD);
                if (attempt >= GuildLimits.MAX_TX_ATTEMPTS) {
                    throw new IllegalStateException("建全局插入守卫哨兵行重跑 " + attempt + " 次仍死锁", e);
                }
                long wait = tx.nextBackoffMillis();
                if (deadline.remainingMillis() <= wait) {
                    throw new IllegalStateException("建全局插入守卫哨兵行在死锁退避期间用完预算", e);
                }
                try {
                    tx.sleepBackoff(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("建全局插入守卫哨兵行被中断", ie);
                }
            }
        }
        if (!JdbcGuildStore.missingPlayerStateRows(db, guard).isEmpty()) {
            throw new GuildStoreException(GuildStoreException.Kind.GLOBAL_INSERT_GUARD_MISSING,
                    "INSERT IGNORE 之后哨兵行仍缺失");
        }
        log.info("已建全局插入守卫哨兵行 guild_player_state(0)");
    }

    private static void releaseInitLock(Connection c) {
        try {
            new GuildJdbc(c, Deadline.after(INSERT_GUARD_INIT_RELEASE_TIMEOUT_MS), 0)
                    .one(RELEASE_LOCK, rs -> rs.getObject(1) == null ? 0L : rs.getLong(1), INSERT_GUARD_INIT_LOCK_NAME);
        } catch (SQLException | RuntimeException e) {
            log.error("释放命名锁 {} 失败（连接关闭时服务端会释放）: {}", INSERT_GUARD_INIT_LOCK_NAME, e.toString());
        }
    }
}
