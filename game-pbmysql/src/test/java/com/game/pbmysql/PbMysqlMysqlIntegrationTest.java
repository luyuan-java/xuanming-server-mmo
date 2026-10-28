package com.game.pbmysql;

import static com.game.pbmysql.TableOption.withAutoIncrementKey;
import static com.game.pbmysql.TableOption.withIndexes;
import static com.game.pbmysql.TableOption.withPrimaryKey;
import static com.game.pbmysql.TableOption.withTableName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.pbmysql.testpb.Account;
import com.game.pbmysql.testpb.DriftRow;
import com.game.pbmysql.testpb.GolangTest;
import com.game.pbmysql.testpb.KitchenSink;
import com.game.pbmysql.testpb.LongNameRow;
import com.game.pbmysql.testpb.Player;
import com.game.pbmysql.testpb.ScalarRow;
import com.game.pbmysql.testpb.StringKeyRow;
import com.game.pbmysql.testpb.SyncItemV1;
import com.game.pbmysql.testpb.SyncItemV2;
import com.game.pbmysql.testpb.ThirdPartyAccount;
import com.game.pbmysql.testpb.TidbHotspot;
import com.game.pbmysql.testpb.Tier;
import com.game.proto.friend.FriendEdgeRecord;
import com.game.proto.guild.GuildRecord;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 连真 MySQL 的集成测试（缺省跳过）：{@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306}，用户 {@code game.it.mysql.user}
 * （缺省 root），口令取环境变量 GAME_MYSQL_PASSWORD。建一个一次性库 {@code game_pbmysql_it_<随机>}，结束时删掉。
 *
 * <p>覆盖：建表语句真的能在 MySQL 上执行、重复同步幂等、加字段 / 补索引、漂移被拒绝且不执行 DDL、
 * 全套 CRUD（uint64 最大值、bytes 键、Timestamp、upsert、insertIgnore、save、IN 查询、事务内 FOR UPDATE 行锁）。
 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class PbMysqlMysqlIntegrationTest {

    private static final String BASE_URL = System.getProperty("game.it.mysql");
    private static final String USER = System.getProperty("game.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true";
    private static AuthoritySqlFixture fixture;
    private static AuthoritySqlFixture.UnpublishedSchemaLease publishedLease;

    @BeforeAll
    static void createDatabase() throws Exception {
        // 样本与漂移夹具只在未发布Schema阶段准备，发布后业务仅使用NORMAL。
        try (var lease = beginUnpublished()) {
            PbMysql samples = new PbMysql();
            samples.register(GolangTest.getDefaultInstance());
            samples.register(Account.getDefaultInstance());
            samples.register(ThirdPartyAccount.getDefaultInstance());
            samples.register(TidbHotspot.getDefaultInstance());
            samples.register(StringKeyRow.getDefaultInstance());
            samples.register(LongNameRow.getDefaultInstance());
            samples.register(FriendEdgeRecord.getDefaultInstance());
            samples.register(GuildRecord.getDefaultInstance());
            samples.register(KitchenSink.getDefaultInstance());
            samples.register(ScalarRow.getDefaultInstance());
            samples.initializeFresh(lease);
            // 同一proto full name重注册会覆盖映射，因此两个别名使用独立注册器。
            PbMysql generatedIds = new PbMysql();
            generatedIds.register(Account.getDefaultInstance(), withTableName("pbm_account"));
            generatedIds.initializeFresh(lease);
            PbMysql rowLocks = new PbMysql();
            rowLocks.register(GolangTest.getDefaultInstance(), withTableName("pbm_lock"));
            rowLocks.initializeFresh(lease);
            exec(lease, "CREATE TABLE `pbm_drift` (`id` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1', `name` MEDIUMTEXT,"
                    + " `v` bigint NULL DEFAULT 0 COMMENT 'pb:3', PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            exec(lease, "CREATE TABLE `pbm_rename` (`id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',"
                    + " `old_name` MEDIUMTEXT COMMENT 'pb:2', `v` bigint NOT NULL DEFAULT 0 COMMENT 'pb:3', PRIMARY KEY (`id`))"
                    + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            fixture = lease.publish();
            publishedLease = lease;
        }
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (fixture != null) fixture.close();
    }

    private static AuthoritySqlFixture.UnpublishedSchemaLease beginUnpublished() throws Exception {
        return AuthoritySqlFixture.beginUnpublishedWithPlayerBaseSchema(AuthoritySqlFixture.Options.fromEnvironment(
                BASE_URL, USER, PASSWORD, "game_pbmysql", true, List.of(1)));
    }

    private static Connection connect() throws SQLException {
        return fixture.normalDataSource(PARAMS).getConnection();
    }

    private static void assertLeaseRevoked(AuthoritySqlFixture.UnpublishedSchemaLease lease) {
        assertThatThrownBy(() -> lease.withSchema((c, configuredPrincipal, database) -> null))
                .isInstanceOf(SQLException.class);
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = connect(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static Map<String, String> columnTypes(String table) throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COLUMN_NAME, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                     + " WHERE TABLE_SCHEMA = '" + fixture.database() + "' AND TABLE_NAME = '" + table + "'")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    private static List<String> indexNames(String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS"
                     + " WHERE TABLE_SCHEMA = '" + fixture.database() + "' AND TABLE_NAME = '" + table + "' ORDER BY INDEX_NAME")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static void exec(AuthoritySqlFixture.UnpublishedSchemaLease lease, String sql) throws Exception {
        lease.withSchema((c, configuredPrincipal, database) -> {
            try (Statement st = c.createStatement()) { st.execute(sql); }
            return null;
        });
    }

    private static Map<String, String> columnTypes(AuthoritySqlFixture.UnpublishedSchemaLease lease, String table)
            throws Exception {
        return lease.withSchema((c, configuredPrincipal, database) -> {
            Map<String, String> out = new HashMap<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COLUMN_NAME, COLUMN_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                         + " WHERE TABLE_SCHEMA = '" + lease.database() + "' AND TABLE_NAME = '" + table + "'")) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
            return out;
        });
    }

    private static List<String> indexNames(AuthoritySqlFixture.UnpublishedSchemaLease lease, String table)
            throws Exception {
        return lease.withSchema((c, configuredPrincipal, database) -> {
            List<String> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS"
                         + " WHERE TABLE_SCHEMA = '" + lease.database() + "' AND TABLE_NAME = '" + table + "' ORDER BY INDEX_NAME")) {
                while (rs.next()) out.add(rs.getString(1));
            }
            return out;
        });
    }

    // ================================================================ 建表与同步

    @Test
    void 全部样本表的建表语句能在MySQL上执行_重复同步是空操作() throws SQLException {
        PbMysql db = new PbMysql();
        db.register(GolangTest.getDefaultInstance());
        db.register(Account.getDefaultInstance());
        db.register(ThirdPartyAccount.getDefaultInstance());
        db.register(TidbHotspot.getDefaultInstance());
        db.register(StringKeyRow.getDefaultInstance());
        db.register(LongNameRow.getDefaultInstance());
        db.register(FriendEdgeRecord.getDefaultInstance());
        db.register(GuildRecord.getDefaultInstance());
        // 与 CRUD 用例共用的表：VARBINARY 键列、DATETIME(6)、FLOAT / DOUBLE、可空 int、BLOB 前缀索引的回读形态都要判成「已对齐」
        db.register(KitchenSink.getDefaultInstance());
        db.register(ScalarRow.getDefaultInstance());
        try (Connection c = connect()) {
            db.syncAll(c);
            db.syncAll(c); // 线上结构与 proto 完全一致：什么都不做、也不报漂移
        }
        assertLeaseRevoked(publishedLease);
        assertThat(columnTypes("kitchen_sink")).containsEntry("token", "varbinary(64)")
                .containsEntry("created_at", "datetime(6)").containsEntry("score", "int");
        Map<String, String> account = columnTypes("account");
        assertThat(account).containsEntry("id", "bigint unsigned").containsEntry("email", "varchar(191)")
                .containsEntry("name", "mediumtext").containsEntry("vip", "tinyint(1)");
        assertThat(indexNames("account")).containsExactlyInAnyOrder("PRIMARY", "idx_account_0", "uk_account");
        assertThat(indexNames("a_very_long_table_name_for_identifier_truncation_check_0123456")).containsExactlyInAnyOrder(
                "idx_a_very_long_table_name_for_identifier_truncation_ch_f043655f", "PRIMARY",
                "uk_a_very_long_table_name_for_identifier_truncation_che_10512a9b");
        assertThat(columnTypes("friend")).containsEntry("friend_player_id", "bigint unsigned");
    }

    @Test
    void v1到v2只加列与索引_旧行按默认值读出_v1再同步不回退() throws Exception {
        PbMysql v1 = new PbMysql();
        v1.register(SyncItemV1.getDefaultInstance());
        PbMysql v2 = new PbMysql();
        v2.register(SyncItemV2.getDefaultInstance());
        try (var lease = beginUnpublished()) {
            v1.initializeFresh(lease);
            lease.withSchema((c, configuredPrincipal, database) -> {
                v1.insert(c, SyncItemV1.newBuilder().setId(1).setOwnerId(10).setName("old").build());
                return null;
            });

            v2.upgradeUnpublishedSchema(lease);
            assertThat(columnTypes(lease, "pbm_sync_item")).containsEntry("level", "int").containsEntry("code", "varchar(32)");
            assertThat(indexNames(lease, "pbm_sync_item"))
                    .containsExactlyInAnyOrder("PRIMARY", "idx_pbm_sync_item_0", "idx_pbm_sync_item_1", "uk_pbm_sync_item");
            lease.withSchema((c, configuredPrincipal, database) -> {
                assertThat(v2.findOneByPk(c, SyncItemV2.newBuilder().setId(1).build())).contains(
                        SyncItemV2.newBuilder().setId(1).setOwnerId(10).setName("old").build());
                return null;
            });
            v2.upgradeUnpublishedSchema(lease);

            // 未发布阶段再用v1声明核对，额外列与索引必须保留，不执行回退。
            v1.upgradeUnpublishedSchema(lease);
            assertThat(columnTypes(lease, "pbm_sync_item")).containsKeys("level", "code");

            // 被人手工删掉的声明索引会按原名补回来
            exec(lease, "ALTER TABLE `pbm_sync_item` DROP INDEX `idx_pbm_sync_item_0`");
            v2.upgradeUnpublishedSchema(lease);
            assertThat(indexNames(lease, "pbm_sync_item")).contains("idx_pbm_sync_item_0");
            try (var published = lease.publish();
                 Connection c = published.normalDataSource(PARAMS).getConnection()) {
                v2.syncAll(c);
                assertThat(v2.findOneByPk(c, SyncItemV2.newBuilder().setId(1).build())).contains(
                        SyncItemV2.newBuilder().setId(1).setOwnerId(10).setName("old").build());
                assertLeaseRevoked(lease);
            }
        }
    }

    @Test
    void 索引阶段失败保留已补列_修正冲突后重复同步可继续且旧行不丢() throws Exception {
        String table = "pbm_sync_retry";
        PbMysql v1 = new PbMysql();
        v1.register(SyncItemV1.getDefaultInstance(), withTableName(table));
        PbMysql v2 = new PbMysql();
        v2.register(SyncItemV2.getDefaultInstance(), withTableName(table));
        try (var lease = beginUnpublished()) {
            v1.initializeFresh(lease);
            lease.withSchema((c, configuredPrincipal, database) -> {
                v1.insert(c, SyncItemV1.newBuilder().setId(1).setOwnerId(10).setName("first").build());
                v1.insert(c, SyncItemV1.newBuilder().setId(2).setOwnerId(20).setName("second").build());
                return null;
            });
            // 新 code 列的默认值都是空串：唯一索引必须明确失败，不能把 DDL 的部分成功伪装成整轮成功。
            assertThatThrownBy(() -> v2.upgradeUnpublishedSchema(lease)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("补齐索引失败");
            assertThatThrownBy(() -> {
                try (var unexpected = lease.publish()) {
                    throw new AssertionError("索引升级未完成时不能发布");
                }
            }).isInstanceOfAny(SQLException.class, SchemaDriftException.class);
            assertThat(columnTypes(lease, table)).containsKeys("level", "code");
            lease.withSchema((c, configuredPrincipal, database) -> {
                assertThat(v1.count(c, SyncItemV1.class, null)).isEqualTo(2);
                return null;
            });
            exec(lease, "UPDATE `" + table + "` SET `code` = CONCAT('old-', `id`)");
            assertThatThrownBy(() -> {
                try (var unexpected = lease.publish()) {
                    throw new AssertionError("修复DML不能代替缺失索引的真实全量校验");
                }
            }).isInstanceOfAny(SQLException.class, SchemaDriftException.class);
            v2.upgradeUnpublishedSchema(lease);
            v2.upgradeUnpublishedSchema(lease);
            assertThat(indexNames(lease, table)).containsExactlyInAnyOrder(
                    "PRIMARY", "idx_pbm_sync_retry_0", "idx_pbm_sync_retry_1", "uk_pbm_sync_retry");
            lease.withSchema((c, configuredPrincipal, database) -> {
                assertThat(v2.findOneByPk(c, SyncItemV2.newBuilder().setId(2).build()).orElseThrow())
                        .isEqualTo(SyncItemV2.newBuilder().setId(2).setOwnerId(20).setName("second").setCode("old-2").build());
                return null;
            });
            try (var published = lease.publish();
                 Connection c = published.normalDataSource(PARAMS).getConnection()) {
                v2.syncAll(c);
                assertThat(v2.findOneByPk(c, SyncItemV2.newBuilder().setId(2).build()).orElseThrow())
                        .isEqualTo(SyncItemV2.newBuilder().setId(2).setOwnerId(20).setName("second").setCode("old-2").build());
                assertLeaseRevoked(lease);
            }
        }
    }

    @Test
    void 新增非主键自增列与首列索引同句_保留旧行并可重复同步() throws Exception {
        String table = "pbm_auto_secondary_migration";
        PbMysql db = new PbMysql();
        db.register(KitchenSink.getDefaultInstance(), withTableName(table), withPrimaryKey("owner_id"),
                withAutoIncrementKey("slot"), withIndexes("slot,owner_id"));
        try (var lease = beginUnpublished()) {
            if (lease.withSchema((c, configuredPrincipal, database) -> SchemaSync.isTiDb(c))) {
                // TiDB只验证明确拒绝和原件不变，不能把fresh CREATE冒成已有表ALTER支持。
                assertTiDbAutoIncrementAlterRejected(lease, db, table);
                return;
            }
            db.initializeFresh(lease);
            exec(lease, "ALTER TABLE `" + table + "` DROP INDEX `idx_" + table + "_0`, DROP COLUMN `slot`");
            exec(lease, "INSERT INTO `" + table + "` (`owner_id`, `token`) VALUES (31, X'31')");
            db.upgradeUnpublishedSchema(lease);
            db.upgradeUnpublishedSchema(lease);
            lease.withSchema((c, configuredPrincipal, database) -> {
                KitchenSink restored = db.findOneByPk(c, KitchenSink.newBuilder().setOwnerId(31).build()).orElseThrow();
                assertThat(restored.getOwnerId()).isEqualTo(31);
                assertThat(restored.getSlot()).isPositive();
                assertThat(restored.getToken()).isEqualTo(ByteString.copyFromUtf8("1"));
                return null;
            });
            assertThat(indexNames(lease, table)).contains("idx_" + table + "_0");
            try (var published = lease.publish();
                 Connection c = published.normalDataSource(PARAMS).getConnection()) {
                KitchenSink restored = db.findOneByPk(c, KitchenSink.newBuilder().setOwnerId(31).build()).orElseThrow();
                assertThat(restored.getOwnerId()).isEqualTo(31);
                assertThat(restored.getSlot()).isPositive();
                assertThat(restored.getToken()).isEqualTo(ByteString.copyFromUtf8("1"));
                assertLeaseRevoked(lease);
            }
        }
    }

    private static void assertTiDbAutoIncrementAlterRejected(AuthoritySqlFixture.UnpublishedSchemaLease lease, PbMysql db, String table)
            throws Exception {
        String freshTable = table + "_fresh";
        PbMysql fresh = new PbMysql();
        fresh.register(KitchenSink.getDefaultInstance(), withTableName(freshTable), withPrimaryKey("owner_id"),
                withAutoIncrementKey("slot"), withIndexes("slot,owner_id"));
        fresh.initializeFresh(lease);
        fresh.upgradeUnpublishedSchema(lease);
        lease.withSchema((c, configuredPrincipal, database) -> {
            assertThat(SchemaSync.readColumns(c, c.getCatalog(), freshTable).get("slot").extra())
                    .contains("auto_increment");
            return null;
        });

        // 用同一目标定义建立合法旧schema；缺普通列note使测试能抓住先ALTER普通列的半迁移。
        TableSchema target = db.schema(KitchenSink.class);
        String create = target.buildCreateTableSql();
        String slot = "  `slot` " + target.columnType(KitchenSink.getDescriptor().findFieldByName("slot"))
                + TableSchema.columnComment(2) + ",\n";
        String note = "  `note` " + target.columnType(KitchenSink.getDescriptor().findFieldByName("note"))
                + TableSchema.columnComment(4) + ",\n";
        String slotIndex = "  INDEX `" + target.indexName(0) + "` ("
                + target.indexColumnsSql("slot,owner_id") + "),\n";
        assertThat(create).containsOnlyOnce(slot).containsOnlyOnce(note).containsOnlyOnce(slotIndex);
        exec(lease, create.replace(slot, "").replace(note, "").replace(slotIndex, ""));
        exec(lease, "INSERT INTO `" + table + "` (`owner_id`, `token`) VALUES (31, X'31')");

        // 若预检只做到单表中途，这个排序更早的新表会先CREATE；必须保持不存在。
        String earlier = "aaa_pbm_auto_preflight_probe";
        db.register(SyncItemV1.getDefaultInstance(), withTableName(earlier));
        byte[][] before = lease.withSchema((c, configuredPrincipal, database) ->
                new byte[][] {createTableBytes(c, table), allRowBytes(c, table)});
        byte[] schemaBefore = before[0];
        byte[] rowsBefore = before[1];
        lease.withSchema((c, configuredPrincipal, database) -> {
            assertThat(tablePresent(c, earlier)).isFalse();
            return null;
        });
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> db.upgradeUnpublishedSchema(lease))
                    .isInstanceOf(java.sql.SQLFeatureNotSupportedException.class)
                    .hasMessageContaining("TiDB").hasMessageContaining("AUTO_INCREMENT")
                    .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("0A000"));
            lease.withSchema((c, configuredPrincipal, database) -> {
                assertThat(createTableBytes(c, table)).isEqualTo(schemaBefore);
                assertThat(allRowBytes(c, table)).isEqualTo(rowsBefore);
                assertThat(SchemaSync.readColumns(c, c.getCatalog(), table)).doesNotContainKeys("slot", "note");
                assertThat(tablePresent(c, earlier)).isFalse();
                return null;
            });
        }
        assertThatThrownBy(() -> {
            try (var unexpected = lease.publish()) {
                throw new AssertionError("未完成TiDB目标结构时不能发布");
            }
        }).isInstanceOfAny(SQLException.class, SchemaDriftException.class);
    }

    private static boolean tablePresent(Connection c, String table) throws SQLException {
        try (var statement = c.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setString(1, c.getCatalog());
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) != 0;
            }
        }
    }

    private static byte[] createTableBytes(Connection c, String table) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet rows = statement.executeQuery(
                "SHOW CREATE TABLE `" + table + "`")) {
            if (!rows.next()) throw new SQLException("测试schema原件缺失");
            return rows.getBytes(2);
        }
    }

    /** 固定PK顺序、列元数据和逐列NULL/长度/原字节；不把COUNT一致当作旧行不变。 */
    private static byte[] allRowBytes(Connection c, String table) throws SQLException {
        try (Statement statement = c.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT * FROM `" + table + "` ORDER BY `owner_id`")) {
            var output = new java.io.ByteArrayOutputStream();
            try (var data = new java.io.DataOutputStream(output)) {
                var metadata = rows.getMetaData();
                int count = metadata.getColumnCount();
                data.writeInt(count);
                for (int column = 1; column <= count; column++) {
                    data.writeUTF(metadata.getColumnName(column));
                    data.writeInt(metadata.getColumnType(column));
                }
                while (rows.next()) {
                    data.writeBoolean(true);
                    for (int column = 1; column <= count; column++) {
                        byte[] value = rows.getBytes(column);
                        data.writeInt(value == null ? -1 : value.length);
                        if (value != null) data.write(value);
                    }
                }
                data.writeBoolean(false);
            }
            return output.toByteArray();
        } catch (java.io.IOException memoryOnly) {
            throw new AssertionError("内存原件序列化失败", memoryOnly);
        }
    }
    @Test
    void 线上列更窄_可空_缺注释时拒绝且不执行任何DDL() throws SQLException {
        PbMysql db = new PbMysql();
        db.register(DriftRow.getDefaultInstance());
        try (Connection c = connect()) {
            assertThatThrownBy(() -> db.syncAll(c))
                    .isInstanceOfSatisfying(SchemaDriftException.class, e -> {
                        assertThat(e.differences()).containsExactly(
                                "列 id 线上是 int unsigned，比 proto 要求的 bigint unsigned NOT NULL DEFAULT 0 窄，需要拓宽",
                                "列 name 缺少字段号注释 COMMENT 'pb:2'",
                                "column v nullable mismatch (online=true, proto=false)");
                        assertThat(e.suggestedStatements()).containsExactly(
                                "ALTER TABLE `pbm_drift` MODIFY COLUMN `id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1'",
                                "ALTER TABLE `pbm_drift` MODIFY COLUMN `name` MEDIUMTEXT COMMENT 'pb:2'",
                                "ALTER TABLE `pbm_drift` MODIFY COLUMN `v` bigint NOT NULL DEFAULT 0 COMMENT 'pb:3'");
                    });
        }
        assertThat(columnTypes("pbm_drift")).containsEntry("id", "int unsigned");

        // 按 pb:N 认出的改名同样拒绝
        PbMysql renamed = new PbMysql();
        renamed.register(DriftRow.getDefaultInstance(), withTableName("pbm_rename"));
        try (Connection c = connect()) {
            assertThatThrownBy(() -> renamed.syncAll(c))
                    .isInstanceOfSatisfying(SchemaDriftException.class, e -> assertThat(e.suggestedStatements())
                            .containsExactly("ALTER TABLE `pbm_rename` CHANGE COLUMN `old_name` `name` MEDIUMTEXT COMMENT 'pb:2'"));
        }
        assertThat(columnTypes("pbm_rename")).containsKey("old_name").doesNotContainKey("name");
    }

    @Test
    void 同步不能在事务里执行_两个message映射同一张表时拒绝() throws SQLException {
        // 第二个映射到同一张物理表的消息在 register 时就被拒，且不登记（不调 syncAll 的进程也拦得住）
        PbMysql db = new PbMysql();
        db.register(SyncItemV1.getDefaultInstance());
        assertThatThrownBy(() -> db.register(SyncItemV2.getDefaultInstance()))
                .isInstanceOfSatisfying(InvalidTableDefinitionException.class,
                        e -> assertThat(e.reason()).isEqualTo(InvalidTableDefinitionException.Reason.DUPLICATE_TABLE_MAPPING));
        assertThat(db.tables()).hasSize(1);
        assertThatThrownBy(() -> db.schema(SyncItemV2.class)).isInstanceOf(PbMysqlException.class);
        try (Connection c = connect()) {
            PbMysql single = new PbMysql();
            single.register(Account.getDefaultInstance(), withTableName("pbm_tx_guard"));
            String principal = publishedLease.schemaPrincipal();
            c.setAutoCommit(false);
            assertThatThrownBy(() -> single.initializeFresh(c, principal)).isInstanceOf(IllegalStateException.class);
            c.rollback();
        }
    }

    // ================================================================ CRUD

    private static ScalarRow scalar(long id) {
        return ScalarRow.newBuilder()
                .setId(id).setI32(Integer.MIN_VALUE).setU32(-1).setI64(Long.MIN_VALUE).setU64(-1L)
                .setF32(0.1f).setF64(Math.PI).setFlag(true).setText("中文 😀 'q' \\ end")
                .setBlob(ByteString.copyFrom(new byte[] {0, 1, (byte) 0xff, ' '})).setTier(Tier.TIER_GOLD)
                .build();
    }

    @Test
    void 标量往返_uint64最大值与相邻值按主键精确命中() throws SQLException {
        PbMysql db = new PbMysql();
        db.register(ScalarRow.getDefaultInstance());
        try (Connection c = connect()) {
            db.syncAll(c);
            ScalarRow max = scalar(-1L);       // 2^64 - 1
            ScalarRow next = scalar(-2L).toBuilder().setText("neighbour").build();
            db.insert(c, max);
            db.insert(c, next);
            db.insert(c, ScalarRow.newBuilder().setId(Long.MIN_VALUE).build()); // 2^63
            assertThat(db.findOneByPk(c, ScalarRow.newBuilder().setId(-1L).build())).contains(max);
            assertThat(db.findOneByPk(c, ScalarRow.newBuilder().setId(-2L).build())).contains(next);
            assertThat(db.findOneByPk(c, ScalarRow.newBuilder().setId(Long.MIN_VALUE).build()))
                    .contains(ScalarRow.newBuilder().setId(Long.MIN_VALUE).build());
            assertThat(db.findAllByKvIn(c, ScalarRow.class, "id", List.of("18446744073709551615", -2L)))
                    .containsExactlyInAnyOrder(max, next);
            assertThat(db.count(c, ScalarRow.class, "`u64` = ?", new java.math.BigInteger("18446744073709551615")))
                    .isEqualTo(2);
            assertThat(db.count(c, ScalarRow.class, "`f32` = ?", 0.1f)).isEqualTo(2);
            assertThat(db.findOneByPk(c, ScalarRow.newBuilder().setId(12345).build())).isEmpty();
        }
    }

    private static KitchenSink sink(long owner, int slot, byte[] token) {
        return KitchenSink.newBuilder()
                .setOwnerId(owner).setSlot(slot).setToken(ByteString.copyFrom(token))
                .setNote("note " + slot).setScore(slot)
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_456_789))
                .addTags(slot).addTags(300).putCounters("gold", -5)
                .setOwner(Player.newBuilder().setPlayerId(owner).setName("alice"))
                .setOptValue(0).setTier(Tier.TIER_SILVER)
                .build();
    }

    private static KitchenSink stored(KitchenSink m) {
        if (!m.hasCreatedAt()) {
            return m;
        }
        Timestamp ts = m.getCreatedAt();
        return m.toBuilder().setCreatedAt(ts.toBuilder().setNanos(ts.getNanos() / 1000 * 1000)).build();
    }

    @Test
    void 联合主键与bytes唯一键的全套写读() throws SQLException {
        PbMysql db = new PbMysql();
        db.register(KitchenSink.getDefaultInstance());
        try (Connection c = connect()) {
            db.syncAll(c);
            KitchenSink a = sink(-1L, 1, new byte[] {'a', 0, ' '});
            KitchenSink b = sink(-1L, 2, new byte[] {'a', 0}); // 与 a 只差一个尾部空格：VARBINARY 逐字节比较，是两个键
            db.insert(c, a);
            db.insert(c, b);
            KitchenSink keyA = KitchenSink.newBuilder().setOwnerId(-1L).setSlot(1).build();
            assertThat(db.findOneByPk(c, keyA)).contains(stored(a));

            // 未设置的 Timestamp 落 SQL NULL，读回仍是未设置；未设置的子消息 / 空容器读回为空。
            // proto3 optional 的 presence 不落库（列是 NOT NULL DEFAULT 0，与 Go 版相同）：读回的 0 带 presence
            KitchenSink bare = KitchenSink.newBuilder().setOwnerId(7).setSlot(1).setToken(ByteString.copyFromUtf8("bare")).build();
            db.insert(c, bare);
            assertThat(db.count(c, KitchenSink.class, "`created_at` IS NULL AND `owner_id` = ?", 7L)).isEqualTo(1);
            assertThat(db.findOneByPk(c, bare)).contains(bare.toBuilder().setOptValue(0).build());

            // 键列超长：发 SQL 之前就拒绝
            assertThatThrownBy(() -> db.insert(c, sink(8, 1, new byte[65]))).isInstanceOf(InvalidKeyValueException.class);

            // insertIgnore：主键冲突 / 唯一键冲突都是 false，新行 true
            assertThat(db.insertIgnore(c, a)).isFalse();
            assertThat(db.insertIgnore(c, sink(9, 9, new byte[] {'a', 0, ' '}))).isFalse();
            assertThat(db.insertIgnore(c, sink(9, 9, new byte[] {'n', 'e', 'w'}))).isTrue();

            // upsert：同主键覆盖；新主键插入；撞上另一行的唯一键时那一行保持原样
            db.upsert(c, a.toBuilder().setNote("upserted").setScore(0).build());
            assertThat(db.findOneByPk(c, keyA).orElseThrow().getNote()).isEqualTo("upserted");
            assertThat(db.findOneByPk(c, keyA).orElseThrow().getScore()).isZero();
            db.upsert(c, sink(10, 1, new byte[] {'u'}));
            assertThat(db.findOneByPk(c, KitchenSink.newBuilder().setOwnerId(10).setSlot(1).build())).isPresent();
            db.upsert(c, sink(11, 1, new byte[] {'u'}).toBuilder().setNote("hijack").build());
            assertThat(db.findOneByPk(c, KitchenSink.newBuilder().setOwnerId(10).setSlot(1).build()).orElseThrow().getNote())
                    .isEqualTo("note 1");
            assertThat(db.findOneByPk(c, KitchenSink.newBuilder().setOwnerId(11).setSlot(1).build())).isEmpty();

            // save：存在则整行更新，不存在则插入，值完全相同也算成功，撞备用唯一键抛 DuplicateKeyException 且不改那一行
            db.save(c, b.toBuilder().setNote("saved").build());
            assertThat(db.findOneByPk(c, b).orElseThrow().getNote()).isEqualTo("saved");
            db.save(c, b.toBuilder().setNote("saved").build());
            db.save(c, sink(12, 1, new byte[] {'s'}));
            assertThat(db.findOneByPk(c, KitchenSink.newBuilder().setOwnerId(12).setSlot(1).build())).isPresent();
            assertThatThrownBy(() -> db.save(c, sink(13, 1, new byte[] {'s'})))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("conflicts with a different unique row");
            assertThat(db.findOneByPk(c, KitchenSink.newBuilder().setOwnerId(12).setSlot(1).build()).orElseThrow().getOwner()
                    .getPlayerId()).isEqualTo(12);
            // UPDATE 阶段（主键已存在）撞上备用唯一键：同样是 DuplicateKeyException，不是裸 SQLException
            assertThatThrownBy(() -> db.save(c, b.toBuilder().setToken(ByteString.copyFrom(new byte[] {'s'})).build()))
                    .isInstanceOf(DuplicateKeyException.class);

            // updateByPk 整行：把字段改回零值也会写进去；updateFieldsByPk 只动指定列
            assertThat(db.updateByPk(c, a.toBuilder().setNote("").setScore(0).clearOwner().clearTags().build())).isTrue();
            KitchenSink zeroed = db.findOneByPk(c, keyA).orElseThrow();
            assertThat(zeroed.getNote()).isEmpty();
            assertThat(zeroed.hasOwner()).isFalse();
            assertThat(zeroed.getTagsList()).isEmpty();
            assertThat(db.updateFieldsByPk(c, a.toBuilder().setNote("only note").setScore(99).build(), "note")).isTrue();
            assertThat(db.findOneByPk(c, keyA).orElseThrow()).satisfies(r -> {
                assertThat(r.getNote()).isEqualTo("only note");
                assertThat(r.getScore()).isZero();
            });
            assertThat(db.updateByPk(c, KitchenSink.newBuilder().setOwnerId(404).setSlot(4).build())).isFalse();

            // 查询：where 原样拼接（可带 ORDER BY），IN 列表（uint64 列的 long 位模式自动换成无符号值），count，
            // findOne 命中多行报错。where 的参数不知道对着哪一列，uint64 的大值要用 PbMysql.uint64 换
            assertThat(db.findAll(c, KitchenSink.class, "`owner_id` = ? ORDER BY `slot` DESC", PbMysql.uint64(-1L)))
                    .extracting(KitchenSink::getSlot).containsExactly(2, 1);
            assertThat(db.findAll(c, KitchenSink.class, "`owner_id` = ?", -1L)).isEmpty();
            assertThat(db.findAllByKvIn(c, KitchenSink.class, "owner_id", List.of(-1L, 9L)))
                    .extracting(KitchenSink::getOwnerId).containsExactlyInAnyOrder(-1L, -1L, 9L);
            assertThat(db.findAllByKvIn(c, KitchenSink.class, "owner_id", List.of())).isEmpty();
            assertThat(db.findOne(c, KitchenSink.class, "`token` = ?", new byte[] {'a', 0})).contains(
                    db.findOneByPk(c, b).orElseThrow());
            assertThatThrownBy(() -> db.findOne(c, KitchenSink.class, "`owner_id` = ?", PbMysql.uint64(-1L)))
                    .isInstanceOf(PbMysqlException.class).hasMessageContaining("multiple rows found");
            // a、b、bare、(9,9)、(10,1)、(12,1)
            assertThat(db.count(c, KitchenSink.class, null)).isEqualTo(6);

            assertThat(db.deleteByPk(c, keyA)).isTrue();
            assertThat(db.deleteByPk(c, keyA)).isFalse();
        }
    }

    @Test
    void 自增主键返回生成的id() throws SQLException {
        PbMysql db = new PbMysql();
        db.register(Account.getDefaultInstance(), withTableName("pbm_account"));
        try (Connection c = connect()) {
            db.syncAll(c);
            long first = db.insertReturningId(c, Account.newBuilder().setName("a").setEmail("a@x").build());
            long second = db.insertReturningId(c, Account.newBuilder().setName("b").setEmail("b@x").build());
            assertThat(second).isEqualTo(first + 1);
            assertThat(db.findOneByPk(c, Account.newBuilder().setId(second).build()).orElseThrow().getEmail()).isEqualTo("b@x");
            // utf8mb4_0900_bin：区分大小写，"A@X" 与 "a@x" 是两个键
            assertThat(db.insertIgnore(c, Account.newBuilder().setName("c").setEmail("A@X").build())).isTrue();
            assertThat(db.insertIgnore(c, Account.newBuilder().setName("d").setEmail("a@x").build())).isFalse();
        }
    }

    @Test
    void 事务内FOR_UPDATE锁住行直到提交_自动提交模式下直接拒绝() throws SQLException {
        PbMysql db = new PbMysql();
        db.register(GolangTest.getDefaultInstance(), withTableName("pbm_lock"));
        try (Connection setup = connect()) {
            db.syncAll(setup);
            db.insert(setup, GolangTest.newBuilder().setId(1).setIp("10.0.0.1").setPort(1).build());
            GolangTest key = GolangTest.newBuilder().setId(1).build();
            assertThatThrownBy(() -> db.findOneByPkForUpdate(setup, key)).isInstanceOf(IllegalStateException.class);

            try (Connection holder = connect(); Connection other = connect()) {
                holder.setAutoCommit(false);
                assertThat(db.findOneByPkForUpdate(holder, key).orElseThrow().getIp()).isEqualTo("10.0.0.1");
                try (Statement st = other.createStatement()) {
                    st.execute("SET SESSION innodb_lock_wait_timeout = 1");
                }
                assertThatThrownBy(() -> db.updateFieldsByPk(other, key.toBuilder().setPort(2).build(), "port"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(1205));
                holder.commit();
                assertThat(db.updateFieldsByPk(other, key.toBuilder().setPort(2).build(), "port")).isTrue();
            }
            assertThat(db.findOneByPk(setup, key).orElseThrow().getPort()).isEqualTo(2);
        }
    }
}
