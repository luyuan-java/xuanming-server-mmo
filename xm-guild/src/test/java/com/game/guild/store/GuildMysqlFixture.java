package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTableRules.ApplicationRules;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 真 MySQL 测试的公共夹具（缺省跳过：{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取环境变量 XM_MYSQL_PASSWORD）。
 *
 * <p>每个测试类建一个一次性库 {@code xm_guild_it_<随机>}（四张表经 {@link GuildTables#sync} 建，与生产同一条路径），结束时删掉；
 * 绝不碰 xm_java。连接串与生产同口径：会话级 READ COMMITTED、{@code innodb_lock_wait_timeout=1}、{@code useAffectedRows=true}
 * （不这样开池就等于在验一个与线上不同的数据库会话，基线 guild_repo_zone_test.go:42-46 同理）。每个用例前清空四张表并按启动顺序
 * 重建哨兵行（{@link GuildStartupChecks#ensureGlobalInsertGuard}）。
 *
 * <p>夹具行（seedXxx）直接用 SQL 造，刻意绕开被测方法：建帮 / 申请自带一整套前置（建状态行、清申请），用它们造夹具会让
 * 「被测的那一步失败」与「夹具没造出来」混在一起。时间一律用 {@link #NOW} 显式传入，过期与否由入参决定。
 */
final class GuildMysqlFixture implements AutoCloseable {

    static final String BASE_URL = System.getProperty("xm.it.mysql");
    static final String USER = System.getProperty("xm.it.mysql.user", "root");
    static final String PASSWORD = System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");
    static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useAffectedRows=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout=1";

    /** 所有用例的「当前时刻」（基线 testNowMs）。 */
    static final long NOW = 1_700_000_000_000L;
    /** GuildRule.application_expire_hours = 72。 */
    static final long TTL = 72L * 3_600_000L;
    /** MaxPerGuild 压到 2：队列上限的用例只需要 3 个申请人（基线 testRules）。 */
    static final ApplicationRules RULES = new ApplicationRules(TTL, 3, 2);

    /** 测试钩子用的闸门表：解散的「提前截止」位置点锁它的 1 号行，占锁事务持有它就能把解散停在那一步。 */
    static final String GATE_TABLE = "guild_it_gate";

    final String database;
    final DruidDataSource dataSource;

    private GuildMysqlFixture(String database, DruidDataSource dataSource) {
        this.database = database;
        this.dataSource = dataSource;
    }

    static GuildMysqlFixture create() throws SQLException {
        String database = "xm_guild_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(BASE_URL + "/" + PARAMS, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4");
        }
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(BASE_URL + "/" + database + PARAMS);
        ds.setUsername(USER);
        ds.setPassword(PASSWORD);
        ds.setMaxActive(96);
        ds.setMaxWait(10_000);
        ds.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        GuildMysqlFixture fixture = new GuildMysqlFixture(database, ds);
        GuildTables.sync(ds, java.time.Duration.ofMinutes(1));
        fixture.exec("CREATE TABLE `" + GATE_TABLE + "` (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
        fixture.exec("INSERT INTO `" + GATE_TABLE + "` (id) VALUES (1)");
        return fixture;
    }

    @Override
    public void close() throws SQLException {
        dataSource.close();
        try (Connection c = DriverManager.getConnection(BASE_URL + "/" + PARAMS, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    /** 清空四张表、按启动顺序重建哨兵行（updated_ms = NOW）。 */
    void reset() throws SQLException {
        for (String table : GuildTables.NAMES) {
            exec("TRUNCATE TABLE `" + table + "`");
        }
        startup(GuildTxListener.NONE).ensureGlobalInsertGuard(NOW, d());
    }

    GuildTx tx(GuildTxListener listener) {
        return new GuildTx(dataSource::getConnection, 10, listener);
    }

    JdbcGuildStore store(GuildTxListener listener, GuildTxHooks hooks) {
        return new JdbcGuildStore(tx(listener), hooks);
    }

    GuildStartupChecks startup(GuildTxListener listener) {
        return new GuildStartupChecks(tx(listener));
    }

    /** 每次调用一个宽裕的请求预算（关心的是 SQL 行为，不是超时；子预算照样是 1500 / 2500）。 */
    static Deadline d() {
        return Deadline.after(30_000);
    }

    // ================================================================ 夹具

    void exec(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        }
    }

    long count(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    Long queryU64(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? GuildJdbc.u64(rs, 1) : null;
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, GuildJdbc.bindValue(args[i]));
        }
    }

    /**
     * 直接造一个帮会：帮主 + {@code roles} 里的成员（基线 seedManagedGuild）。名字 manage-guild-&lt;id&gt; 全小写 ASCII，
     * name_norm 与原串相同（与生产写入的值一致）。
     */
    void seedGuild(long guildId, int zone, int level, int maxMembers, long leader, Map<Long, Integer> roles) throws SQLException {
        String name = "manage-guild-" + Long.toUnsignedString(guildId);
        exec("INSERT INTO guild (guild_id, name, name_norm, leader_id, level, announcement, create_time_ms, max_members,"
                + " zone_id, score, funds) VALUES (?, ?, ?, ?, ?, '', ?, ?, ?, 0, 0)",
                guildId, name, name, leader, level, NOW, maxMembers, zone);
        seedMember(guildId, leader, GuildRoles.LEADER);
        for (Map.Entry<Long, Integer> e : roles.entrySet()) {
            seedMember(guildId, e.getKey(), e.getValue());
        }
    }

    void seedGuild(long guildId, int zone, int maxMembers, long leader) throws SQLException {
        seedGuild(guildId, zone, 1, maxMembers, leader, Map.of());
    }

    /** 只插一行帮会（不带成员），名字须已是规范形。 */
    void seedNamedGuild(long guildId, long leader, String name, int zone) throws SQLException {
        assertThat(com.game.guild.rules.GuildNames.nameNorm(name)).as("夹具名字必须已是规范形").isEqualTo(name);
        exec("INSERT INTO guild (guild_id, name, name_norm, leader_id, level, announcement, create_time_ms, max_members,"
                + " zone_id, score, funds) VALUES (?, ?, ?, ?, 1, '', ?, 30, ?, 0, 0)", guildId, name, name, leader, NOW, zone);
    }

    void seedMember(long guildId, long playerId, int role) throws SQLException {
        exec("INSERT INTO guild_member (guild_id, player_id, role, join_time_ms, last_active_ms, contribution_total,"
                + " contribution_balance) VALUES (?, ?, ?, ?, ?, 0, 0)", guildId, playerId, role, NOW, NOW);
    }

    void seedApplication(long guildId, long playerId, long applyMs, long expireMs) throws SQLException {
        exec("INSERT INTO guild_application (guild_id, player_id, apply_ms, expire_ms) VALUES (?, ?, ?, ?)",
                guildId, playerId, applyMs, expireMs);
    }

    void seedState(long... playerIds) throws SQLException {
        for (long p : playerIds) {
            exec("INSERT INTO guild_player_state (player_id, updated_ms) VALUES (?, ?)", p, NOW);
        }
    }

    long playerApplications(long playerId) throws SQLException {
        return count("SELECT COUNT(*) FROM guild_application WHERE player_id = ?", playerId);
    }

    long application(long guildId, long playerId) throws SQLException {
        return count("SELECT COUNT(*) FROM guild_application WHERE guild_id = ? AND player_id = ?", guildId, playerId);
    }

    long liveApplications(long playerId, long now) throws SQLException {
        return count("SELECT COUNT(*) FROM guild_application WHERE player_id = ? AND expire_ms > ?", playerId, now);
    }

    Long expireOf(long guildId, long playerId) throws SQLException {
        return queryU64("SELECT expire_ms FROM guild_application WHERE guild_id = ? AND player_id = ?", guildId, playerId);
    }

    /** 成员行的权威 role；没有成员行返回 null。 */
    Integer roleOf(long guildId, long playerId) throws SQLException {
        Long role = queryU64("SELECT role FROM guild_member WHERE guild_id = ? AND player_id = ?", guildId, playerId);
        return role == null ? null : role.intValue();
    }

    long memberships(long playerId) throws SQLException {
        return count("SELECT COUNT(*) FROM guild_member WHERE player_id = ?", playerId);
    }

    long officers(long guildId) throws SQLException {
        return count("SELECT COUNT(*) FROM guild_member WHERE guild_id = ? AND role = ?", guildId, GuildRoles.OFFICER);
    }

    Long leaderOf(long guildId) throws SQLException {
        return queryU64("SELECT leader_id FROM guild WHERE guild_id = ?", guildId);
    }

    long guildRows(long guildId) throws SQLException {
        return count("SELECT COUNT(*) FROM guild WHERE guild_id = ?", guildId);
    }

    long stateRows(long playerId) throws SQLException {
        return count("SELECT COUNT(*) FROM guild_player_state WHERE player_id = ?", playerId);
    }

    /** 开一个 RC 占锁事务（调用方提交 / 回滚并关闭连接）。 */
    Connection begin() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(false);
        return c;
    }

    static void execOn(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) {
                    while (rs.next()) {
                        // 读完游标
                    }
                }
            }
        }
    }

    static void finish(Connection c, boolean commit) throws SQLException {
        try (c) {
            if (commit) {
                c.commit();
            } else {
                c.rollback();
            }
            c.setAutoCommit(true);
        }
    }

    /**
     * 等到本库上至少 n 个事务处于锁等待（INNODB_TRX，需 PROCESS 权限），最多等 limit 毫秒（基线 lockOrderAwaitLockWaits）。
     * 只用来把「先让一方卡住、再放另一方」的交错摆出来，不影响判据；查询报错就退化成固定等待。
     */
    void awaitLockWaits(int n, long limitMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limitMillis);
        String sql = "SELECT COUNT(*) FROM information_schema.INNODB_TRX t JOIN information_schema.PROCESSLIST p"
                + " ON p.ID = t.trx_mysql_thread_id WHERE t.trx_state = 'LOCK WAIT' AND p.DB = ?";
        while (System.nanoTime() < deadline) {
            try {
                if (count(sql, database) >= n) {
                    return;
                }
            } catch (SQLException e) {
                sleep(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                return;
            }
            sleep(5);
        }
    }

    static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    // ================================================================ 并发与结局

    /** 一路并发参与者的结局：拒绝原因（null = 成功）或异常。 */
    record Result(GuildReject reject, Throwable error) {
        boolean ok() {
            return reject == null && error == null;
        }

        @Override
        public String toString() {
            return error != null ? "error:" + error : reject == null ? "ok" : reject.name();
        }
    }

    static GuildReject rejectOf(TxOutcome<?> outcome) {
        return outcome.rejection();
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "guild-it");
        t.setDaemon(true);
        return t;
    });

    /** 后台跑一路（结果从返回的 future 取）。 */
    static CompletableFuture<Result> async(Callable<TxOutcome<?>> call) {
        return CompletableFuture.supplyAsync(() -> run(call), POOL);
    }

    /** 同时放行若干路（统一闸门，尽量落在同一时刻），返回与入参一一对应的结局。 */
    @SafeVarargs
    static List<Result> concurrently(Callable<TxOutcome<?>>... calls) {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Result>> futures = new ArrayList<>();
        for (Callable<TxOutcome<?>> call : calls) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return run(call);
            }, POOL));
        }
        start.countDown();
        List<Result> out = new ArrayList<>();
        for (CompletableFuture<Result> f : futures) {
            out.add(f.join());
        }
        return out;
    }

    private static Result run(Callable<TxOutcome<?>> call) {
        try {
            return new Result(call.call().rejection(), null);
        } catch (Throwable t) {
            return new Result(null, t);
        }
    }

    static long okCount(List<Result> results) {
        return results.stream().filter(Result::ok).count();
    }

    /** 失败的一路只能是给定的拒绝之一（异常一律不许）。 */
    static void assertRejectIn(Result result, GuildReject... allowed) {
        assertThat(result.error()).as("不许抛异常: %s", result).isNull();
        if (result.reject() != null) {
            assertThat(result.reject()).as("结局 %s", result).isIn((Object[]) allowed);
        }
    }

    /** 记录事务基座的三类指标：锁序回归断言「被重跑吸收掉的死锁」为零次。 */
    static final class Recorder implements GuildTxListener {
        final List<String> deadlocks = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> lockWaits = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> budgets = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void deadlockObserved(GuildTxOp op) {
            deadlocks.add(op.label());
        }

        @Override
        public void lockWaitTimeout(GuildTxOp op) {
            lockWaits.add(op.label());
        }

        @Override
        public void budgetExceeded(GuildTxOp op) {
            budgets.add(op.label());
        }

        void assertNoDeadlocks(String what) {
            assertThat(deadlocks).as("%s：出现了被重跑吸收掉的死锁 / 写冲突（返回值看不出来），必须为 0 次", what).isEmpty();
        }
    }

    /**
     * 测试钩子：{@link #blockOn} 设为某个帮会时，该帮的「提前截止」步骤（锁序位置 O）去点锁闸门表的 1 号行——占锁事务持有它，
     * 就能确定性地把解散 / 退帮 / 踢人停在「删成员与删申请之后、提交之前」。也可设 {@link #sleepMillis} 让它睡一会儿（耗尽子预算）。
     */
    static final class GateHooks implements GuildTxHooks {
        volatile long blockOn;
        volatile long sleepMillis;
        final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void accelerateDonationDeadlines(GuildJdbc tx, long guildId, List<Long> playerIds, long nowMs)
                throws SQLException {
            calls.add("accelerate:" + Long.toUnsignedString(guildId) + ":" + playerIds + ":" + nowMs);
            if (blockOn != 0 && blockOn == guildId) {
                tx.exists("SELECT id FROM " + GATE_TABLE + " WHERE id = ? FOR UPDATE", 1);
            }
            if (sleepMillis > 0) {
                sleep(sleepMillis);
            }
        }

        @Override
        public void deleteGuildActivityProgress(GuildJdbc tx, long guildId) {
            calls.add("activity:" + Long.toUnsignedString(guildId));
        }
    }
}
