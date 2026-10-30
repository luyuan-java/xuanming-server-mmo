package com.game.guild.store;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.guild.rules.GuildLimits;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
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
 * 预算照基线：版本检查 5 s、建哨兵行 10 s（guild.go:124-142）。
 * 建哨兵行使用普通受管连接上的单行原子 upsert；不使用会话命名锁。阻塞 JDBC；只在启动线程上调用。
 */
public final class GuildStartupChecks {

    private static final Logger log = LoggerFactory.getLogger(GuildStartupChecks.class);

    /** 保留原剩余启动预算之外的网络超时余量，不扩大整步预算。 */
    static final int NETWORK_TIMEOUT_MARGIN_MS = 1_000;

    static final String SELECT_VERSION = "SELECT VERSION()";
    // 唯一主键原子仲裁首建；重复键走排他更新路径，保留首建的 updated_ms。
    static final String ENSURE_GLOBAL_INSERT_GUARD = "INSERT INTO guild_player_state (player_id, updated_ms) VALUES (?, ?)"
            + " ON DUPLICATE KEY UPDATE player_id = player_id";

    private final GuildTx tx;

    /** @param namespace 保留装配调用面的非空部署标识；数据隔离由连接绑定的 schema 保证。 */
    public GuildStartupChecks(GuildTx tx, GameNamespace namespace) {
        this.tx = tx;
        Objects.requireNonNull(namespace, "namespace");
    }

    /**
     * 保留原命名锁名的纯函数兼容接口与 golden 向量；当前初始化不获取会话命名锁。
     * 多部署数据按各自 schema 中的哨兵主键隔离。
     */
    public static String insertGuardInitLockName(GameNamespace namespace) {
        return "game_guild_insert_guard_init:" + Objects.requireNonNull(namespace, "namespace").value();
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
     * 保证全局插入守卫哨兵行存在；幂等，多实例同时首建由同一主键上的原子 upsert 仲裁。
     *
     * <ol>
     *   <li>普通读：行已在就返回，不取数据锁，运行实例持有哨兵行时也不排队；</li>
     *   <li>缺行：普通受管连接执行单行 {@code INSERT ... ON DUPLICATE KEY UPDATE player_id = player_id}；
     *       不使用被 normal SQL 守卫禁止的 GET_LOCK / RELEASE_LOCK，也不使用 INSERT IGNORE 的重复键共享锁路径；</li>
     *   <li>1213 / 9007 仍在原预算内有界重跑（op = insert_guard），1205 拒启；</li>
     *   <li>原子语句自动提交后复读确认，重复首建不改写最早已提交的 updated_ms。</li>
     * </ol>
     *
     * @param nowMs 只写进首次建行的 updated_ms（诊断用）
     * @throws GuildStoreException 行仍缺（{@link GuildStoreException.Kind#GLOBAL_INSERT_GUARD_MISSING}）
     * @throws IllegalStateException 建行遇 1205 或死锁重跑用尽
     * @throws DependencyException SQL 故障
     */
    public void ensureGlobalInsertGuard(long nowMs, Deadline deadline) {
        List<Long> guard = List.of(JdbcGuildStore.GLOBAL_INSERT_GUARD_PLAYER_ID);
        if (tx.read(deadline, db -> JdbcGuildStore.missingPlayerStateRows(db, guard)).isEmpty()) {
            return;
        }
        try (Connection c = tx.connections().get(Math.max(1, deadline.remainingMillis()))) {
            // 普通行锁等待仍受原启动预算约束；暂时放宽请求连接的 socketTimeout，归还前恢复。
            int socketTimeout = c.getNetworkTimeout();
            c.setNetworkTimeout(Runnable::run, (int) Math.max(1, deadline.remainingMillis()) + NETWORK_TIMEOUT_MARGIN_MS);
            try {
                GuildJdbc db = new GuildJdbc(c, deadline, 0);
                insertGuardAtomically(db, guard, nowMs, deadline);
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

    private void insertGuardAtomically(GuildJdbc db, List<Long> guard, long nowMs, Deadline deadline) throws SQLException {
        if (JdbcGuildStore.missingPlayerStateRows(db, guard).isEmpty()) {
            return; // 另一个首建者已经提交，避免再排重复键更新锁
        }
        GuildTxListener listener = tx.listener();
        for (int attempt = 1; ; attempt++) {
            try {
                db.update(ENSURE_GLOBAL_INSERT_GUARD, JdbcGuildStore.GLOBAL_INSERT_GUARD_PLAYER_ID, nowMs);
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
                    "原子 upsert 之后哨兵行仍缺失");
        }
        log.info("已建全局插入守卫哨兵行 guild_player_state(0)");
    }

}
