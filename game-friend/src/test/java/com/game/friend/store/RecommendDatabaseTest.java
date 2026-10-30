package com.game.friend.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;
import com.game.friend.store.RecommendStore.Candidate;
import com.game.friend.store.pb.FriendBlockRow;
import com.game.friend.store.pb.FriendCapacityRow;
import com.game.friend.store.pb.FriendEdgeRow;
import com.game.friend.store.pb.FriendRequestRow;
import com.game.pbmysql.PbMysql;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 两种真实方言的条件验收：默认跳过，设置 game.it.mysql / game.it.tidb 后运行。
 * TiDB 严格验收另设 game.it.tidb.required=true，缺入口必须失败。每例使用有归属原件的随机独立 fixture。
 * seed / Schema / 统计维护走 fixture owner5；计划与会话读数走 owner 诊断，业务推荐走受保护的 normal 池。
 * 计划测试覆盖新鲜 / 空表陈旧统计；不把离线 SQL 文本断言或 MySQL 结果当作 TiDB 验收。
 */
class RecommendDatabaseTest {
    private static final List<String> EXCLUSIONS = List.of("f", "b_out", "b_in", "r_out", "r_in");
    private static final String TABLES = "friend,friend_block,friend_request";

    static Stream<Target> targets() {
        return Stream.of(new Target("mysql", RecommendSql.Dialect.MYSQL), new Target("tidb", RecommendSql.Dialect.TIDB));
    }

    @ParameterizedTest(name = "{0}：排名和随机都不能穿透被排除的 1024 人窗口")
    @MethodSource("targets")
    void 窗口截取在排除之前_随机兜底仍能选到窗口之后的真实共同好友(Target target) throws Exception {
        try (var db = target.open()) {
            db.graph(true);
            RecommendStore store = db.store();
            assertThat(store.mutual(1, List.of(), 20, deadline())).isEmpty();
            assertThat(store.anchor(1, List.of(), 10_000, 20, deadline())).isEmpty();
            assertThat(store.anchor(1, List.of(), 50_000, 3, deadline()))
                    .containsExactly(new Candidate(50_000, 1), new Candidate(50_001, 1), new Candidate(50_002, 1));
        }
    }

    @ParameterizedTest(name = "{0}：真实共同好友、五种关系排除和无符号 ID")
    @MethodSource("targets")
    void 随机共同数不改变ID顺序_五类排除_高半区ID按无符号绑定和读取(Target target) throws Exception {
        try (var db = target.open()) {
            long me = Long.MIN_VALUE + 1;
            long first = Long.MIN_VALUE + 100;
            db.edges(me, 10, me, 11, first, 10, first + 1, 10, first + 1, 11);
            for (int i = 2; i <= 8; i++) db.edges(first + i, 10);
            db.edges(me, first + 2);
            db.execute("INSERT INTO friend_block VALUES (?, ?, 1)", uid(me), uid(first + 3));
            db.execute("INSERT INTO friend_block VALUES (?, ?, 1)", uid(first + 4), uid(me));
            db.request(me, first + 5, 1);
            db.request(first + 6, me, 1);
            db.request(first + 7, me, 3); // 终态不是 pending，必须保留。
            RecommendStore store = db.store();
            assertThat(store.anchor(me, List.of(first + 8), first, 20, deadline()))
                    .containsExactly(new Candidate(first, 1), new Candidate(first + 1, 2), new Candidate(first + 7, 1));
            assertThat(store.random(me, List.of(first + 8), 20, deadline()))
                    .containsExactly(new Candidate(first, 1), new Candidate(first + 1, 2), new Candidate(first + 7, 1));
            assertThat(store.mutual(me, List.of(first + 8), 20, deadline())).containsExactlyInAnyOrder(
                    new Candidate(first, 1), new Candidate(first + 1, 2), new Candidate(first + 7, 1));
            assertThat(store.mutual(me, List.of(first + 8), 1, deadline())).containsExactly(new Candidate(first + 1, 2));
            assertThat(store.anchor(me, List.of(), -1L, 20, deadline())).isEmpty(); // 不回绕到低半区。
        }
    }

    @ParameterizedTest(name = "{0}：优化后仍按原账号和修订取得事务身份")
    @MethodSource("targets")
    void 强读身份不因推荐优化而绕过_过户ABA冻结和高半区修订都拒绝(Target target) throws Exception {
        try (var db = target.open()) {
            db.execute("INSERT INTO player(player_id,account,owner_revision) VALUES (1,'original',7)");
            db.edges(1, 10, 10, 20);
            RecommendSource original = db.store().forActor(1, "original", 7);
            assertThat(original.mutual(1, List.of(), 10, deadline())).containsExactly(new Candidate(20, 1));
            for (String changed : List.of("account='next',owner_revision=8", "account='original',owner_revision=9",
                    "account='original',owner_revision=7,trade_listing_id=81",
                    "account='original',owner_revision=18446744073709551615,trade_listing_id=0")) {
                db.execute("UPDATE player SET " + changed + " WHERE player_id=1");
                assertThatThrownBy(() -> original.mutual(1, List.of(), 10, deadline())).isInstanceOf(SecurityException.class);
                assertThatThrownBy(() -> original.random(1, List.of(), 10, deadline())).isInstanceOf(SecurityException.class);
                assertThatThrownBy(() -> original.authorize(deadline())).isInstanceOf(SecurityException.class);
            }
        }
    }

    @ParameterizedTest(name = "{0}：新鲜和陈旧统计的主键点查计划与 MySQL 读数预算")
    @MethodSource("targets")
    void 两种统计状态都保持有界访问计划_真实查询结果一致(Target target) throws Exception {
        for (boolean stale : List.of(false, true)) {
            try (var db = target.open()) {
                if (stale) {
                    if (target.dialect == RecommendSql.Dialect.TIDB) db.execute("LOCK STATS " + TABLES);
                    else for (String table : TABLES.split(",")) db.execute("ALTER TABLE " + table + " STATS_AUTO_RECALC=0");
                }
                db.graph(false);
                db.noise();
                if (!stale) db.execute("ANALYZE TABLE " + TABLES);
                RecommendSql sql = new RecommendSql(target.dialect);
                var mutual = sql.mutual(1, List.of(10_000L), 20);
                var anchor = sql.anchor(1, List.of(10_000L), 10_000, 20);
                try (Connection c = db.diagnosticConnection()) {
                    assertPlan(c, target.dialect, mutual, "f2");
                    assertPlan(c, target.dialect, anchor, "m2");
                    if (target.dialect == RecommendSql.Dialect.MYSQL) {
                        // 只统计关系 SQL；P 锁围栏是独立的安全成本，不混入 Go 关系查询的 Handler 账本。
                        long r = scalar(c, "SELECT COUNT(*) FROM friend f1 JOIN friend f2 ON f2.player_id=f1.friend_player_id WHERE f1.player_id=1");
                        long before = handlerReads(c);
                        assertThat(query(c, sql.mutual(1, List.of(), RecommendStore.ANCHOR_WINDOW))).hasSize(1024);
                        long reads = handlerReads(c) - before;
                        assertThat(reads).as("%s 统计下关系查询读数", stale ? "陈旧" : "新鲜")
                                .isLessThanOrEqualTo(3 * r + 6L * RecommendStore.ANCHOR_WINDOW + 3);
                    }
                }
                assertThat(db.store().anchor(1, List.of(10_000L), 10_000, 2, deadline()))
                        .containsExactly(new Candidate(10_001, 2), new Candidate(10_002, 2));
            }
        }
    }

    private static void assertPlan(Connection c, RecommendSql.Dialect dialect, RecommendSql.Query query, String joinAlias) throws SQLException {
        String prefix = dialect == RecommendSql.Dialect.MYSQL ? "EXPLAIN FORMAT=TRADITIONAL " : "EXPLAIN ";
        if (dialect == RecommendSql.Dialect.TIDB) {
            try (var st = c.createStatement()) { st.execute("SET SESSION tidb_stats_load_sync_wait=10000"); }
        }
        var plan = query(c, new RecommendSql.Query(prefix + RecommendSql.timed(query.sql(), 30_000), query.arguments()));
        assertThat(plan).isNotEmpty();
        var located = new java.util.HashSet<String>();
        if (dialect == RecommendSql.Dialect.MYSQL) {
            for (var row : plan) {
                String table = row.get("table");
                assertThat(row.get("select_type")).isNotEqualTo("MATERIALIZED");
                assertThat(table).doesNotStartWith("<subquery");
                assertThat(row.get("extra")).doesNotContain("join buffer");
                if (EXCLUSIONS.contains(table)) {
                    assertThat(row).containsEntry("type", "eq_ref").containsEntry("key", "PRIMARY")
                            .containsEntry("key_len", "16").containsEntry("possible_keys", "PRIMARY");
                    located.add(table);
                }
                if (table.equals(joinAlias)) {
                    assertThat(row).containsEntry("key", "PRIMARY");
                    assertThat(row.get("type")).isIn("ref", "eq_ref");
                    located.add(table);
                }
            }
            assertThat(plan).anyMatch(row -> row.get("table").startsWith("<derived"));
        } else {
            for (var row : plan) {
                assertThat(row).containsKeys("id", "access object", "operator info");
                assertThat(row.get("id")).doesNotContain("FullScan");
                String access = row.get("access object");
                if (access.startsWith("table:") && access.contains("index:PRIMARY")
                        && row.get("operator info").contains("range: decided by")) {
                    located.add(access.substring(6).split(",", 2)[0].trim());
                }
            }
            assertThat(query(c, new RecommendSql.Query("SHOW WARNINGS", List.of())))
                    .as("TiDB 不得忽略未知 / 失效提示").isEmpty();
        }
        assertThat(located).containsAll(EXCLUSIONS).contains(joinAlias);
    }

    private static List<Map<String, String>> query(Connection c, RecommendSql.Query query) throws SQLException {
        try (var ps = c.prepareStatement(query.sql())) {
            ps.setQueryTimeout(30);
            for (int i = 0; i < query.arguments().size(); i++) ps.setObject(i + 1, query.arguments().get(i));
            try (ResultSet rows = ps.executeQuery()) {
                var out = new ArrayList<Map<String, String>>();
                while (rows.next()) {
                    var row = new HashMap<String, String>();
                    for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                        row.put(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), java.util.Objects.toString(rows.getString(i), ""));
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }
    private static long scalar(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement(); var rows = st.executeQuery(sql)) { rows.next(); return rows.getLong(1); }
    }
    private static long handlerReads(Connection c) throws SQLException {
        return query(c, new RecommendSql.Query("SHOW SESSION STATUS LIKE 'Handler_read_%'", List.of())).stream()
                .mapToLong(row -> Long.parseLong(row.get("value"))).sum();
    }
    private static Object uid(long id) { return JdbcFriendStore.uid(id); }
    private static Deadline deadline() { return Deadline.after(30_000); }

    record Target(String property, RecommendSql.Dialect dialect) {
        @Override public String toString() { return dialect.name(); }
        Database open() throws Exception {
            String configured = System.getProperty("game.it." + property, "").trim();
            if (property.equals("tidb") && Boolean.getBoolean("game.it.tidb.required")) {
                assertThat(configured).as("严格 TiDB 推荐验收要求 game.it.tidb").isNotEmpty();
            }
            assumeTrue(!configured.isEmpty(), "未设置 game.it." + property + "，真实推荐验收未运行");
            return new Database(this, configured);
        }
    }

    private static final class Database implements AutoCloseable {
        private final String password = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
        private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=35000";
        private final DruidDataSource normalPool = new DruidDataSource();
        private final Target target;
        private AuthoritySqlFixture fixture;
        Database(Target target, String configured) throws Exception {
            this.target = target;
            if (!configured.startsWith("jdbc:mysql://")) throw new IllegalArgumentException("只接受 MySQL JDBC 入口");
            URI address = URI.create(configured.substring(5));
            if (address.getRawUserInfo() != null || address.getRawQuery() != null || address.getHost() == null)
                throw new IllegalArgumentException("测试入口仅提供服务器地址，凭据从环境注入");
            String server = "jdbc:mysql://" + address.getHost() + (address.getPort() > 0 ? ":" + address.getPort() : "");
            String user = System.getProperty("game.it." + target.property + ".user", System.getProperty("game.it.mysql.user", "root"));
            // 保留原只读方言断言；这里不执行库 / 表 DDL。
            try (var c = DriverManager.getConnection(server + "/" + PARAMS, user, password)) {
                assertThat(RecommendSql.detect(c, 3)).as("入口必须确实连接声明的方言").isEqualTo(target.dialect);
            }
            try {
                PbMysql schema = new PbMysql();
                schema.register(FriendEdgeRow.getDefaultInstance()); schema.register(FriendRequestRow.getDefaultInstance());
                schema.register(FriendCapacityRow.getDefaultInstance()); schema.register(FriendBlockRow.getDefaultInstance());
                fixture = AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment(
                        server + "/", user, password, "game_friend_rec",
                        target.dialect == RecommendSql.Dialect.MYSQL, List.of(1)), (c, principal) -> {
                    schema.initializeFresh(c, principal);
                    try (var st = c.createStatement()) {
                        st.setQueryTimeout(5);
                        st.execute("CREATE TABLE player (player_id BIGINT UNSIGNED PRIMARY KEY, account VARBINARY(191) NOT NULL, owner_revision BIGINT UNSIGNED NOT NULL, trade_listing_id BIGINT UNSIGNED NOT NULL DEFAULT 0, zone_id INT UNSIGNED NOT NULL DEFAULT 1)");
                    }
                });
                normalPool.setUrl(fixture.jdbcUrl(PARAMS)); normalPool.setUsername(fixture.normalUser()); normalPool.setPassword(fixture.normalPassword()); normalPool.setMaxActive(4);
                normalPool.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                AuthorityDataSourcePolicy.protectDruid(normalPool, fixture.writerBoot(), target.dialect == RecommendSql.Dialect.TIDB);
                try (var c = diagnosticConnection()) { schema.syncAll(c); }
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        RecommendStore store() throws SQLException { return RecommendStore.forDatabase(normalPool, 10, (lo, span) -> 0); }
        // 仅供计划 / SHOW / Handler_read诊断；既有schemaDataSource保owner归属门禁及network5，不借normal或unwrap。
        Connection diagnosticConnection() throws SQLException { return fixture.schemaDataSource().getConnection(); }
        private void ownerWork(AuthoritySqlFixture.OwnerSchemaWork<Void> work) throws SQLException {
            try {
                fixture.withOwnerSchema(work);
            } catch (SQLException | RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SQLException("推荐夹具 owner 工作未确认", failure);
            }
        }
        void execute(String sql, Object... args) throws SQLException {
            ownerWork(c -> {
                try (var ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout(5);
                    for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                    ps.execute();
                }
                return null;
            });
        }
        void edges(long... pairs) throws SQLException {
            ownerWork(c -> {
                try (var ps = c.prepareStatement("INSERT INTO friend (player_id,friend_player_id,since_ms) VALUES (?,?,1)")) {
                    ps.setQueryTimeout(5);
                    for (int i = 0; i < pairs.length; i += 2) {
                        ps.setObject(1, uid(pairs[i])); ps.setObject(2, uid(pairs[i + 1])); ps.addBatch();
                        ps.setObject(1, uid(pairs[i + 1])); ps.setObject(2, uid(pairs[i])); ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
        }
        void request(long from, long to, int status) throws SQLException {
            execute("INSERT INTO friend_request(from_player_id,to_player_id,request_time_ms,status,updated_ms) VALUES (?,?,1,?,1)", uid(from), uid(to), status);
        }
        void graph(boolean blockTop) throws SQLException {
            var pairs = new ArrayList<Long>();
            for (int i = 0; i < 16; i++) { pairs.add(1L); pairs.add(10L + i); }
            for (int i = 0; i < 1024; i++) {
                pairs.add(10_000L + i); pairs.add(10L + i % 16);
                pairs.add(10_000L + i); pairs.add(10L + (i + 1) % 16);
            }
            for (int i = 0; i < 32; i++) { pairs.add(50_000L + i); pairs.add(10L + i % 16); }
            edges(pairs.stream().mapToLong(Long::longValue).toArray());
            if (blockTop) ownerWork(c -> {
                try (var ps = c.prepareStatement("INSERT INTO friend_block VALUES (?,1,1)")) {
                    ps.setQueryTimeout(5);
                    for (long id = 10_000; id < 11_024; id++) { ps.setLong(1, id); ps.addBatch(); }
                    ps.executeBatch();
                }
                return null;
            });
        }
        void noise() throws SQLException {
            ownerWork(c -> {
                try (var block = c.prepareStatement("INSERT INTO friend_block VALUES (?,1,1)");
                     var pending = c.prepareStatement("INSERT INTO friend_request(from_player_id,to_player_id,request_time_ms,status,updated_ms) VALUES (?,2,1,1,1),(?,1,1,3,1)")) {
                    block.setQueryTimeout(5); pending.setQueryTimeout(5);
                    for (int i = 0; i < 4000; i++) {
                        block.setLong(1, 1_000_000L + i); block.addBatch();
                        pending.setLong(1, 2_000_000L + i); pending.setLong(2, 3_000_000L + i); pending.addBatch();
                    }
                    block.executeBatch(); pending.executeBatch();
                }
                return null;
            });
        }
        @Override public void close() throws SQLException {
            Throwable failure = null;
            try {
                if (fixture != null && target.dialect == RecommendSql.Dialect.TIDB) execute("UNLOCK STATS " + TABLES);
            } catch (Exception | Error cleanup) {
                failure = cleanup;
            }
            try {
                normalPool.close();
            } catch (Exception | Error cleanup) {
                if (failure == null) failure = cleanup; else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            try {
                if (fixture != null) fixture.close();
            } catch (Exception | Error cleanup) {
                if (failure == null) failure = cleanup; else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
            if (failure instanceof SQLException sql) throw sql;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new SQLException("推荐夹具关闭未确认", failure);
        }
    }
}
