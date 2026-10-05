package com.game.data.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.ops.OpsTables;
import com.game.data.ops.pb.OpsActiveRow;
import com.game.data.testing.DataSqlFixture;
import com.game.pbmysql.PbMysql;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 只在真 MySQL 上能验的（§12.3；{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取 XM_MYSQL_PASSWORD，缺省跳过）：
 * <ul>
 *   <li>迁移等价：M8 之前的建表脚本 → 执行 db-migrations.md 的 M8 → {@code SHOW CREATE TABLE} 与新建库逐字相同；</li>
 *   <li>EXPLAIN：全服物品 / 货币回收、uuid 追溯、玩家选源四条查询分别走 idx_txlog_item / idx_txlog_currency / idx_txlog_uuid / idx_snapshot_player；</li>
 *   <li>pbmysql：运维表 syncAll 幂等（第二次不做 DDL）；ops_active 重键 = 1062（7.2b 的单飞靠它）。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class XmDataMysqlIntegrationTest {

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
        String migrated = "xm_data_mig_old_" + suffix;
        String fresh = "xm_data_mig_new_" + suffix;
        try {
            DriverManagerDataSource old = database(migrated);
            new ResourceDatabasePopulator(new ClassPathResource("db/xm-data-schema-before-m8.sql")).execute(old);
            new ResourceDatabasePopulator(new ClassPathResource("db/xm-data-migration-m8.sql")).execute(old);
            DriverManagerDataSource now = database(fresh);
            new ResourceDatabasePopulator(new ClassPathResource("db/xm-data-schema.sql")).execute(now);

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

    private static String key(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject("EXPLAIN " + sql, (rs, n) -> rs.getString("key"));
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
}
