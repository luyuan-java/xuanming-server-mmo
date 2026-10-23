package com.game.data.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.OpsTables;
import com.game.data.ops.fence.AdminOwnership;
import com.game.data.ops.fence.AdminOwnership.Claim;
import com.game.data.ops.pb.OpsActiveRow;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.snapshot.PlayerSnapshotRow;
import com.game.data.testing.DataSqlFixture;
import com.game.pbmysql.PbMysql;
import com.game.player.store.OwnerState;
import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.ClaimResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 只在真 MySQL 上能验的（§12.3；{@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取 GAME_MYSQL_PASSWORD，缺省跳过）：
 * <ul>
 *   <li>迁移等价：M8 之前的建表脚本 → 执行 db-migrations.md 的 M8 → {@code SHOW CREATE TABLE} 与新建库逐字相同；</li>
 *   <li>EXPLAIN：全服物品 / 货币回收、uuid 追溯、玩家选源四条查询分别走 idx_txlog_item / idx_txlog_currency / idx_txlog_uuid / idx_snapshot_player；</li>
 *   <li>pbmysql：运维表 syncAll 幂等（第二次不做 DDL）；ops_active 重键 = 1062（7.2b 的单飞靠它）。</li>
 *   <li>7.2b：一个事务里 MyBatis 与 pbmysql 的写记在同一条连接的同一个 InnoDB 事务上；login 与运维两线程抢同一名玩家恰好一方夺到
 *       （行锁的真实语义，H2 管不到）。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class DataMysqlIntegrationTest {

    private static DriverManagerDataSource database(String name) {
        return new DriverManagerDataSource(DataSqlFixture.MYSQL_URL + "/" + name
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&useAffectedRows=true",
                DataSqlFixture.MYSQL_USER, DataSqlFixture.MYSQL_PASSWORD);
    }

    private static void drop(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(DataSqlFixture.MYSQL_URL + "/?useSSL=false&allowPublicKeyRetrieval=true",
                DataSqlFixture.MYSQL_USER, DataSqlFixture.MYSQL_PASSWORD); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }

    @Test
    void 迁移M8之后与新建库逐字相同() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String migrated = "game_data_mig_old_" + suffix;
        String fresh = "game_data_mig_new_" + suffix;
        try {
            DriverManagerDataSource old = database(migrated);
            new ResourceDatabasePopulator(new ClassPathResource("db/game-data-schema-before-m8.sql")).execute(old);
            new ResourceDatabasePopulator(new ClassPathResource("db/game-data-migration-m8.sql")).execute(old);
            DriverManagerDataSource now = database(fresh);
            new ResourceDatabasePopulator(new ClassPathResource("db/game-data-schema.sql")).execute(now);

            for (String table : List.of("transaction_log", "player_snapshot")) {
                assertThat(showCreate(old, table)).as(table).isEqualTo(showCreate(now, table));
            }
            assertThat(showCreate(now, "transaction_log")).contains("KEY `idx_txlog_item` (`kind`,`item_config_id`,`time_ms`)",
                    "KEY `idx_txlog_currency` (`kind`,`currency_type`,`time_ms`)", "KEY `idx_txlog_uuid` (`item_uuid`,`time_ms`)");
            assertThat(showCreate(now, "player_snapshot")).contains("`operator` varchar(64)", "`note` varchar(256)");
        } finally {
            drop(migrated);
            drop(fresh);
        }
    }

    @Test
    void 迁移M9之后player表与新建库的M9形状逐字相同_按区列玩家走idx_player_zone() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String migrated = "game_player_mig_old_" + suffix;
        String fresh = "game_player_mig_new_" + suffix;
        try {
            DriverManagerDataSource old = database(migrated);
            new ResourceDatabasePopulator(new ClassPathResource("db/game-player-schema-before-m9.sql")).execute(old);
            new ResourceDatabasePopulator(new ClassPathResource("db/game-player-migration-m9.sql")).execute(old);
            DriverManagerDataSource now = database(fresh);
            new ResourceDatabasePopulator(new ClassPathResource("db/game-player-schema.sql")).execute(now);
            // 本例只验M9；M15交易列由玩家权威测试独立验收，不把旧M9迁移冒称最新迁移。
            new JdbcTemplate(now).execute("ALTER TABLE player DROP COLUMN owner_revision, DROP COLUMN trade_listing_id");
            for (String table : List.of("account", "player", "player_state")) {
                assertThat(showCreate(old, table)).as(table).isEqualTo(showCreate(now, table));
            }
            assertThat(showCreate(now, "player")).contains("KEY `idx_player_zone` (`zone_id`)");

            JdbcTemplate jdbc = new JdbcTemplate(now);
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 3000; i++) {
                rows.add(new Object[] {i, "acc" + i, 1 + i % 20, "n" + i, "n" + i});
            }
            jdbc.batchUpdate("INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, created_at, "
                    + "updated_at) VALUES (?, ?, ?, ?, ?, 1, 1, 1, 1)", rows);
            jdbc.execute("ANALYZE TABLE player");
            String sql = "SELECT player_id, zone_id, created_at FROM player WHERE zone_id = 3 AND player_id > 0 "
                    + "ORDER BY player_id LIMIT 500";
            List<List<Long>> originalExpected = new ArrayList<>();
            for (int i = 1; i <= 3000; i++) {
                if (1 + i % 20 == 3) originalExpected.add(List.of((long) i, 3L, 1L));
            }
            assertThat(jdbc.query(sql, (row, ignored) -> List.of(row.getLong("player_id"), row.getLong("zone_id"),
                    row.getLong("created_at")))).hasSize(150).containsExactlyElementsOf(originalExpected);

            // 原20区分布已观察到合法全表扫描；精确索引选择另用明确高选择性，不推断容量或吞吐。
            jdbc.update("UPDATE player SET zone_id = 1 + MOD(player_id, 200)");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM player", Long.class)).isEqualTo(3000);
            jdbc.execute("ANALYZE TABLE player");
            List<List<Long>> selectiveExpected = new ArrayList<>();
            for (int i = 1; i <= 3000; i++) {
                if (1 + i % 200 == 3) selectiveExpected.add(List.of((long) i, 3L, 1L));
            }
            assertThat(selectiveExpected).isNotEmpty().hasSize(15);
            assertThat(key(jdbc, sql)).isEqualTo("idx_player_zone");
            assertThat(jdbc.query(sql, (row, ignored) -> List.of(row.getLong("player_id"), row.getLong("zone_id"),
                    row.getLong("created_at")))).hasSize(15).containsExactlyElementsOf(selectiveExpected);
        } finally {
            drop(migrated);
            drop(fresh);
        }
    }

    private static String showCreate(DriverManagerDataSource ds, String table) {
        Map<String, Object> row = new JdbcTemplate(ds).queryForMap("SHOW CREATE TABLE " + table);
        return (String) row.get("Create Table");
    }

    @Test
    void 四条查询走预期的索引() throws Exception {
        try (DataSqlFixture db = DataSqlFixture.create()) {
            JdbcTemplate jdbc = db.jdbc();
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 3000; i++) {
                int kind = i % 2 == 0 ? 1 : 2;
                rows.add(new Object[] {i, 1_000_000L + i, 9, kind, 0, 100 + i % 300, kind == 1 ? i % 50 : 0,
                        kind == 1 ? 5 : 0, kind == 2 ? 50_000L + i : 0, kind == 2 ? 500 + i % 200 : 0, kind == 2 ? 1 : 0});
            }
            jdbc.batchUpdate("INSERT INTO transaction_log (tx_id, time_ms, reason, kind, from_player, to_player, "
                    + "currency_type, currency_delta, item_uuid, item_config_id, item_quantity, zone_id, ingested_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)", rows);
            List<Object[]> snaps = new ArrayList<>();
            for (int i = 1; i <= 2000; i++) {
                snaps.add(new Object[] {i, 100 + i % 400, 1_000L + i, 1 + i % 2});
            }
            jdbc.batchUpdate("INSERT INTO player_snapshot (snapshot_id, player_id, time_ms, cause, zone_id, owner_epoch, "
                    + "level, scene_config_id, pos_x, pos_y, pos_z, player_state, ingested_at) "
                    + "VALUES (?, ?, ?, ?, 1, 1, 1, 1, 0, 0, 0, '', 1)", snaps);
            jdbc.execute("ANALYZE TABLE transaction_log, player_snapshot");

            assertThat(key(jdbc, "SELECT * FROM transaction_log WHERE kind = 2 AND item_config_id = 511 AND to_player <> 0 "
                    + "AND (kind <> 1 OR currency_delta > 0) AND time_ms >= 1000000 AND time_ms < 1003000 "
                    + "ORDER BY time_ms, tx_id LIMIT 1001")).isEqualTo("idx_txlog_item");
            assertThat(key(jdbc, "SELECT * FROM transaction_log WHERE kind = 1 AND currency_type = 0 AND to_player <> 0 "
                    + "AND (kind <> 1 OR currency_delta > 0) AND time_ms >= 1000000 AND time_ms < 1003000 "
                    + "ORDER BY time_ms, tx_id LIMIT 1001")).isEqualTo("idx_txlog_currency");
            assertThat(key(jdbc, "SELECT * FROM transaction_log WHERE item_uuid = 50001 AND time_ms >= 0 "
                    + "AND time_ms < 9223372036854775807 ORDER BY time_ms, tx_id LIMIT 1001")).isEqualTo("idx_txlog_uuid");
            assertThat(key(jdbc, "SELECT snapshot_id FROM player_snapshot WHERE player_id = 150 AND time_ms <= 2500 "
                    + "AND cause IN (1, 2, 3, 5, 6) ORDER BY time_ms DESC, snapshot_id DESC LIMIT 1"))
                    .isEqualTo("idx_snapshot_player");
        }
    }

    private static boolean isTiDb(Connection connection) throws SQLException {
        var metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        String version = metadata.getDatabaseProductVersion();
        if (version == null || version.isBlank()) throw new SQLException("无法识别真实数据库版本");
        if ("TiDB".equalsIgnoreCase(product)) return true;
        if (!"MySQL".equalsIgnoreCase(product)) throw new SQLException("只接受已识别的 MySQL 或 TiDB");
        return version.toLowerCase(Locale.ROOT).contains("tidb");
    }

    private static String key(JdbcTemplate jdbc, String sql) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            boolean tidb = isTiDb(connection);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("EXPLAIN " + sql)) {
                if (!tidb) {
                    if (!rows.next()) throw new SQLException("MySQL EXPLAIN 缺少结果");
                    String key = rows.getString("key");
                    if (rows.next()) throw new SQLException("MySQL EXPLAIN 并非唯一查询计划行");
                    return key;
                }
                // TiDB 的计划是多行；保留真实优化器选择，不用 FORCE INDEX 伪造预期。
                var indexes = new LinkedHashSet<String>();
                var pattern = java.util.regex.Pattern.compile("index:([A-Za-z0-9_]+)\\(");
                while (rows.next()) {
                    String access = rows.getString("access object");
                    if (access == null) continue;
                    var matches = pattern.matcher(access);
                    while (matches.find()) indexes.add(matches.group(1));
                }
                if (indexes.size() != 1) throw new SQLException("TiDB EXPLAIN 必须恰好选择一个具名索引");
                return indexes.iterator().next();
            }
        });
    }

    @Test
    void 运维表syncAll幂等_单飞槽重键是1062() throws Exception {
        try (DataSqlFixture db = DataSqlFixture.create(); Connection c = db.dataSource.getConnection()) {
            PbMysql again = OpsTables.sync(db.dataSource, Duration.ofMinutes(1));
            assertThat(again.tables()).hasSize(OpsTables.NAMES.size());
            for (String table : OpsTables.NAMES) {
                assertThat(db.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? "
                        + "AND TABLE_NAME = ?", Integer.class, db.database, table)).as(table).isEqualTo(1);
            }
            db.ops.insert(c, OpsActiveRow.newBuilder().setSlot(1).setJobId(7).setRunner("a").build());
            assertThatThrownBy(() -> db.ops.insert(c, OpsActiveRow.newBuilder().setSlot(1).setJobId(8).setRunner("b").build()))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(1062));
        }
    }

    // ------------------------------------------------------------------ 批次 7.2b（§12.3；审计 OPS-12 第 4 条）

    /**
     * 精确观察当前会话事务的写入量：MySQL 是修改行数，TiDB 是内存写入 key 数，二者不混称。
     * 只比较首次正数及第二次严格增加；最终仍须共同回滚。MySQL 原缓存等待保留。
     */
    private static Long transactionWriteUnitsOn(JdbcTemplate jdbc, long connectionId) {
        return jdbc.execute((ConnectionCallback<Long>) connection -> {
            boolean tidb = isTiDb(connection);
            if (!tidb) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            String sql = tidb
                    ? "SELECT MEM_BUFFER_KEYS FROM information_schema.TIDB_TRX WHERE SESSION_ID = ?"
                    : "SELECT trx_rows_modified FROM information_schema.INNODB_TRX WHERE trx_mysql_thread_id = ?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, connectionId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        if (tidb) throw new SQLException("TiDB 缺少指定会话的真实事务");
                        return null;
                    }
                    long units = rows.getLong(1);
                    boolean missing = rows.wasNull();
                    if (tidb && (missing || rows.next())) throw new SQLException("TiDB 指定会话事务计数必须唯一且非空");
                    return missing ? null : units;
                }
            }
        });
    }

    /**
     * 「MyBatis 与 pbmysql 同连接」：一个 Spring 事务里两边各写一行，都记在事务绑定的那条连接（{@code CONNECTION_ID()}）的同一个 InnoDB 事务上
     * （MySQL 的 {@code INNODB_TRX.trx_rows_modified} 或 TiDB 的 {@code TIDB_TRX.MEM_BUFFER_KEYS} 两次都涨）；pbmysql 要是自取连接，它那一行不会算进这个事务。回滚后两行都不在。
     */
    @Test
    void 一个事务里MyBatis与pbmysql的写记在同一条MySQL连接的同一个事务上_一起回滚() throws Exception {
        try (DataSqlFixture db = DataSqlFixture.create()) {
            OpsJobStore jobs = db.jobs();
            PlayerSnapshotRow snapshot = new PlayerSnapshotRow(7777, 1001, 1, 6, 1, 3, 5, 2002, 0, 0, 0, new byte[] {1});
            OpsJobPlayerRow detail = OpsJobPlayerRow.newBuilder().setJobId(9001).setPlayerId(1001)
                    .setOutcome(OpsJobStore.PLANNED).build();
            long[] seen = new long[3];
            db.tx().executeWithoutResult(status -> {
                JdbcTemplate jdbc = db.jdbc(); // JdbcTemplate 经 DataSourceUtils 取到的就是事务绑定的连接
                long connection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                seen[0] = connection;
                assertThat(db.snapshots.insertDirect(snapshot, 2, "ops", "同连接")).isEqualTo(1); // MyBatis
                Long afterMyBatis = transactionWriteUnitsOn(jdbc, connection);
                assertThat(afterMyBatis).as("MyBatis 的写在这条连接的事务上").isNotNull().isPositive();
                jobs.insertPlayer(detail);                                                         // pbmysql
                Long afterPbmysql = transactionWriteUnitsOn(jdbc, connection);
                assertThat(afterPbmysql).as("pbmysql 的写也记在同一个事务上").isGreaterThan(afterMyBatis);
                seen[1] = afterMyBatis;
                seen[2] = afterPbmysql;
                status.setRollbackOnly();
            });
            assertThat(seen[0]).isPositive();
            assertThat(seen[2]).isGreaterThan(seen[1]);
            assertThat(db.snapshots.findById(7777)).isNull();
            assertThat(jobs.players(9001, 0, 10)).isEmpty();
        }
    }

    /**
     * 并发：login（进游戏夺权）与运维（{@link AdminOwnership}）各一个线程同时抢同一名已释放的玩家。每一轮恰好一方夺到、epoch 恰好加一，
     * 另一方等行锁放开后看到的就是对方的 epoch（Held / Online）——夺权是一条带条件的 UPDATE，没有「先查再改」的窗口。
     */
    @Test
    void login与运维同时抢同一名玩家_每轮恰好一方夺到_epoch恰好加一_另一方看到对方的epoch() throws Exception {
        try (DataSqlFixture db = DataSqlFixture.create()) {
            long player = 1001;
            long created = System.currentTimeMillis();
            db.insertPlayer(player, 1, 9, 1001, 0, true, 0, created, created);
            PlayerStore login = db.playerStore(System::currentTimeMillis);
            AdminOwnership ops = new AdminOwnership(db.playerStore(System::currentTimeMillis), db.tx(), (p, e) -> { },
                    (p, e) -> CompletableFuture.completedFuture(true), new DataMetrics(new SimpleMeterRegistry()));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int loginWins = 0;
            int opsWins = 0;
            try {
                for (long epoch = 1; epoch <= 40; epoch++) {
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<ClaimResult> byLogin = pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return db.tx().execute(s -> login.claimOwnership(player));
                    });
                    Future<Claim> byOps = pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return ops.claim(player, false, Duration.ofSeconds(1), d -> { });
                    });
                    ClaimResult l = byLogin.get(30, TimeUnit.SECONDS);
                    Claim o = byOps.get(30, TimeUnit.SECONDS);
                    if (l instanceof ClaimResult.Claimed) {
                        loginWins++;
                        assertThat(l).as("第 %d 轮 login 夺到", epoch).isEqualTo(new ClaimResult.Claimed(epoch));
                        assertThat(o).as("第 %d 轮运维看到在线", epoch).isEqualTo(new Claim.Online(epoch));
                        assertThat(ops.heldCount()).isZero();
                        // login 一方的 scene 离场：带围栏释放
                        assertThat(db.playerMapper.releaseOwner(player, epoch, System.currentTimeMillis())).isEqualTo(1);
                    } else {
                        opsWins++;
                        assertThat(o).as("第 %d 轮运维夺到", epoch).isEqualTo(new Claim.Claimed(epoch, false));
                        assertThat(l).as("第 %d 轮 login 看到 Held", epoch).isEqualTo(new ClaimResult.Held(epoch));
                        ops.release(player);
                    }
                    OwnerState owner = db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(player));
                    assertThat(owner.ownerEpoch()).as("第 %d 轮之后 epoch 恰好加一", epoch).isEqualTo(epoch);
                    assertThat(owner.released()).isTrue();
                }
            } finally {
                pool.shutdownNow();
            }
            assertThat(loginWins + opsWins).isEqualTo(40);
        }
    }

    /**
     * 区号是 uint32：≥ 2^31 的区号在 Java 里是负的 int，直接绑定会被当成负数、一行也查不到，读回 INT UNSIGNED 也会越界。
     * H2 的 MySQL 模式没有无符号列，所以只在真 MySQL 上验。
     */
    @Test
    void 区号不小于2的31次方时按区列玩家与数人都查得到_读回按位还原() throws Exception {
        try (DataSqlFixture h = DataSqlFixture.create()) {
            long bigZone = 4_000_000_000L;
            int zoneBits = (int) bigZone;
            assertThat(zoneBits).isNegative();
            String insert = "INSERT INTO player (player_id, account, zone_id, name, name_key, class_id, gender, level, "
                    + "scene_config_id, pos_x, pos_y, pos_z, owner_epoch, owner_released, owner_lease_until, created_at, "
                    + "updated_at) VALUES (?, ?, ?, ?, ?, 1, 1, 1, 1, 0, 0, 0, 1, 1, 0, 5, 5)";
            h.jdbc().update(insert, 11L, "accbig11", bigZone, "big11", "big11");
            h.jdbc().update(insert, 12L, "accbig12", bigZone, "big12", "big12");
            h.jdbc().update(insert, 13L, "accbig13", 7L, "big13", "big13");

            assertThat(h.players.countInZones(List.of(zoneBits))).isEqualTo(2);
            assertThat(h.players.countInZones(List.of(zoneBits, 7))).isEqualTo(3);
            List<PlayerBrief> page = h.players.listInZone(zoneBits, 0L, 500);
            assertThat(page).extracting(PlayerBrief::getPlayerId).containsExactly(11L, 12L);
            assertThat(page).allSatisfy(b -> assertThat(Integer.toUnsignedLong(b.getZoneId())).isEqualTo(bigZone));
            assertThat(h.players.listInZone(zoneBits, 11L, 500)).extracting(PlayerBrief::getPlayerId).containsExactly(12L);
            assertThat(Integer.toUnsignedLong(h.players.findBrief(11L).getZoneId())).isEqualTo(bigZone);
            assertThat(Integer.toUnsignedLong(h.players.find(11L).getZoneId())).isEqualTo(bigZone);
            assertThat(h.players.find(13L).getZoneId()).isEqualTo(7);
        }
    }
}
