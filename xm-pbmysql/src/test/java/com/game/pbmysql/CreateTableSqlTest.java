package com.game.pbmysql;

import static com.game.pbmysql.DynamicTables.field;
import static com.game.pbmysql.DynamicTables.strings;
import static com.game.pbmysql.DynamicTables.unknown;
import static com.game.pbmysql.DynamicTables.varints;
import static com.game.pbmysql.TableOption.withAutoIncrementKey;
import static com.game.pbmysql.TableOption.withIndexes;
import static com.game.pbmysql.TableOption.withMaxLength;
import static com.game.pbmysql.TableOption.withMaxLengths;
import static com.game.pbmysql.TableOption.withNullableFields;
import static com.game.pbmysql.TableOption.withPrimaryKey;
import static com.game.pbmysql.TableOption.withTableName;
import static com.game.pbmysql.TableOption.withTiDBAutoIDCacheOne;
import static com.game.pbmysql.TableOption.withTiDBNonclusteredPK;
import static com.game.pbmysql.TableOption.withTiDBPreSplitRegions;
import static com.game.pbmysql.TableOption.withTiDBShardRowIDBits;
import static com.game.pbmysql.TableOption.withUniqueKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.pbmysql.option.Proto2MysqlOption;
import com.game.pbmysql.testpb.Account;
import com.game.pbmysql.testpb.GolangTest;
import com.game.pbmysql.testpb.KitchenSink;
import com.game.pbmysql.testpb.LongNameRow;
import com.game.pbmysql.testpb.ScalarRow;
import com.game.pbmysql.testpb.StringKeyRow;
import com.game.pbmysql.testpb.ThirdPartyAccount;
import com.game.pbmysql.testpb.TidbHotspot;
import com.game.proto.friend.FriendEdgeRecord;
import com.game.proto.guild.GuildMemberRecord;
import com.game.proto.guild.GuildRecord;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 建表语句的逐字节 golden。期望文本按 Go 版 proto2mysql v0.2.0 的 buildCreateTableSQL / getMySQLFieldType /
 * tidbTableOptionsSQL / truncateIdentifier 逐行推导（golang_test / account / third_party_account / tidb_hotspot
 * 与 Go 版测试、README、parity_emit_test.go 里的同名样本相同）。
 */
class CreateTableSqlTest {

    private static final String TAIL = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

    private static String ddl(String... lines) {
        return String.join("\n", lines);
    }

    private static String sql(com.google.protobuf.Message m, TableOption... opts) {
        return TableSchema.of(m, opts).createTableSql();
    }

    // ================================================================ Go 版对拍样本（parity_emit_test.go）

    @Test
    void golang_test_主键自增_来自proto选项() {
        assertThat(sql(GolangTest.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `golang_test` (",
                "  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:1',",
                "  `ip` MEDIUMTEXT COMMENT 'pb:2',",
                "  `port` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  `group_id` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',",
                "  `player` MEDIUMBLOB COMMENT 'pb:5',",
                "  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6',",
                "  PRIMARY KEY (`id`)",
                TAIL + " COMMENT='golang_test';"));
    }

    @Test
    void golang_test_联合索引与唯一键_唯一键里的string变成VARCHAR键列() {
        assertThat(sql(GolangTest.getDefaultInstance(), withPrimaryKey("id"),
                withIndexes("player_id,group_id"), withUniqueKey("ip"))).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `golang_test` (",
                "  `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:1',",
                "  `ip` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',",
                "  `port` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  `group_id` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',",
                "  `player` MEDIUMBLOB COMMENT 'pb:5',",
                "  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6',",
                "  PRIMARY KEY (`id`),",
                "  INDEX `idx_golang_test_0` (`player_id`,`group_id`),",
                "  UNIQUE KEY `uk_golang_test` (`ip`)",
                TAIL + " COMMENT='golang_test';"));
    }

    @Test
    void golang_test_可空列去掉NOT_NULL保留DEFAULT() {
        assertThat(sql(GolangTest.getDefaultInstance(), withPrimaryKey("id"), withNullableFields("port")))
                .contains("  `port` int unsigned DEFAULT 0 COMMENT 'pb:3',\n");
    }

    @Test
    void golang_test_string主键_默认191与max_length() {
        String defaultLength = sql(GolangTest.getDefaultInstance(), withPrimaryKey("ip"), withAutoIncrementKey(""));
        assertThat(defaultLength).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `golang_test` (",
                "  `id` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `ip` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',",
                "  `port` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  `group_id` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',",
                "  `player` MEDIUMBLOB COMMENT 'pb:5',",
                "  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6',",
                "  PRIMARY KEY (`ip`)",
                TAIL + " COMMENT='golang_test';"));
        assertThat(sql(GolangTest.getDefaultInstance(), withPrimaryKey("ip"), withAutoIncrementKey(""), withMaxLength("ip", 255)))
                .isEqualTo(defaultLength.replace("VARCHAR(191)", "VARCHAR(255)"));
    }

    @Test
    void bytes唯一键_动态描述符_VARBINARY整列() {
        Descriptor probe = DynamicTables.message("parity_key_probe",
                field("id", 1, Type.TYPE_UINT64), field("provider", 2, Type.TYPE_STRING), field("token", 3, Type.TYPE_BYTES));
        assertThat(TableSchema.of(probe, withTableName("bytes_key_probe"), withPrimaryKey("id"),
                withUniqueKey("provider,token")).createTableSql()).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `bytes_key_probe` (",
                "  `id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `provider` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',",
                "  `token` VARBINARY(191) NOT NULL DEFAULT '' COMMENT 'pb:3',",
                "  PRIMARY KEY (`id`),",
                "  UNIQUE KEY `uk_bytes_key_probe` (`provider`,`token`)",
                TAIL + " COMMENT='bytes_key_probe';"));
    }

    // ================================================================ proto2sql testdata

    @Test
    void account_普通索引里的MEDIUMTEXT带191前缀_唯一键整列() {
        assertThat(sql(Account.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `account` (",
                "  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:1',",
                "  `name` MEDIUMTEXT COMMENT 'pb:2',",
                "  `email` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:3',",
                "  `level` int NOT NULL DEFAULT 0 COMMENT 'pb:4',",
                "  `vip` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'pb:5',",
                "  PRIMARY KEY (`id`),",
                "  INDEX `idx_account_0` (`name`(191)),",
                "  UNIQUE KEY `uk_account` (`email`)",
                TAIL + " COMMENT='account';"));
    }

    @Test
    void third_party_account_联合唯一键按max_length() {
        assertThat(sql(ThirdPartyAccount.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `third_party_account` (",
                "  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:1',",
                "  `provider` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',",
                "  `provider_id` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:3',",
                "  `token_hash` MEDIUMBLOB COMMENT 'pb:4',",
                "  PRIMARY KEY (`id`),",
                "  UNIQUE KEY `uk_third_party_account` (`provider`,`provider_id`)",
                TAIL + " COMMENT='third_party_account';"));
    }

    @Test
    void 代码改掉唯一键后_proto的max_length落到键外字段_只能用withMaxLengths整体替换() {
        // provider_id 不再在键里，proto 上的 max_length=255 落空，按字段合并的 withMaxLength 清不掉它
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TableSchema.of(ThirdPartyAccount.getDefaultInstance(),
                        withUniqueKey("provider"), withMaxLength("provider", 16)))
                .isInstanceOf(InvalidTableDefinitionException.class)
                .hasMessageContaining("\"provider_id\"（string，不在主键/唯一键里）声明了 max_length");
        String ddl = sql(ThirdPartyAccount.getDefaultInstance(), withUniqueKey("provider"),
                withMaxLengths(Map.of("provider", 16)));
        assertThat(ddl)
                .contains("  `provider` VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',\n")
                .contains("  `provider_id` MEDIUMTEXT COMMENT 'pb:3',\n")
                .contains("  UNIQUE KEY `uk_third_party_account` (`provider`)\n");
        // withMaxLength 按字段合并：后应用的覆盖先应用的，没提到的字段保留 proto 声明
        assertThat(sql(ThirdPartyAccount.getDefaultInstance(), withMaxLength("provider_id", 200), withMaxLength("provider_id", 100)))
                .contains("`provider` VARCHAR(32) ").contains("`provider_id` VARCHAR(100) ");
    }

    @Test
    void tidb_hotspot_非聚簇主键与打散预切_注释块在COMMENT之前() {
        // 与 Go 版 README 的 player_data 示例同形
        assertThat(sql(TidbHotspot.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `tidb_hotspot` (",
                "  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `data` MEDIUMBLOB COMMENT 'pb:2',",
                "  PRIMARY KEY (`player_id`) /*T![clustered_index] NONCLUSTERED */",
                TAIL + " /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='tidb_hotspot';"));
    }

    @Test
    void tidb_各条忽略与收敛规则() {
        // PRE_SPLIT_REGIONS 超过 SHARD_ROW_ID_BITS：收敛到 shard 值
        assertThat(sql(TidbHotspot.getDefaultInstance(), withTiDBPreSplitRegions(8)))
                .endsWith(TAIL + " /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='tidb_hotspot';");
        // AUTO_ID_CACHE 块在 SHARD 块之前
        assertThat(sql(TidbHotspot.getDefaultInstance(), withTiDBAutoIDCacheOne()))
                .endsWith(TAIL + " /*T![auto_id_cache] AUTO_ID_CACHE=1 */ /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */"
                        + " COMMENT='tidb_hotspot';");
        // 有主键却没声明 NONCLUSTERED：SHARD（连带 PRE_SPLIT）整块忽略
        String clustered = sql(ScalarRow.getDefaultInstance(), withTiDBShardRowIDBits(4), withTiDBPreSplitRegions(2));
        assertThat(clustered).endsWith(TAIL + " COMMENT='scalar_row';").doesNotContain("SHARD_ROW_ID_BITS");
        // 没有 SHARD 时 PRE_SPLIT 单独设置被忽略
        String noShard = sql(ScalarRow.getDefaultInstance(), withTiDBNonclusteredPK(), withTiDBPreSplitRegions(4));
        assertThat(noShard).contains("  PRIMARY KEY (`id`) /*T![clustered_index] NONCLUSTERED */\n")
                .endsWith(TAIL + " COMMENT='scalar_row';");
        // 无主键表可以直接打散
        assertThat(sql(GolangTest.getDefaultInstance(), withPrimaryKey(), withAutoIncrementKey(""), withTiDBShardRowIDBits(2)))
                .doesNotContain("PRIMARY KEY")
                .contains("  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6'\n)")
                .endsWith(TAIL + " /*T! SHARD_ROW_ID_BITS=2 */ COMMENT='golang_test';");
    }

    // ================================================================ 本模块的覆盖样本

    @Test
    void scalar_row_每种标量的列类型_uint64主键() {
        assertThat(sql(ScalarRow.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `scalar_row` (",
                "  `id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `i32` int NOT NULL DEFAULT 0 COMMENT 'pb:2',",
                "  `u32` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  `i64` bigint NOT NULL DEFAULT 0 COMMENT 'pb:4',",
                "  `u64` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:5',",
                "  `f32` float NOT NULL DEFAULT 0 COMMENT 'pb:6',",
                "  `f64` double NOT NULL DEFAULT 0 COMMENT 'pb:7',",
                "  `flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'pb:8',",
                "  `text` MEDIUMTEXT COMMENT 'pb:9',",
                "  `blob` MEDIUMBLOB COMMENT 'pb:10',",
                "  `tier` int NOT NULL DEFAULT 0 COMMENT 'pb:11',",
                "  PRIMARY KEY (`id`)",
                TAIL + " COMMENT='scalar_row';"));
    }

    @Test
    void kitchen_sink_联合主键_bytes唯一键_可空_Timestamp_容器_前缀索引() {
        assertThat(sql(KitchenSink.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `kitchen_sink` (",
                "  `owner_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `slot` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',",
                "  `token` VARBINARY(64) NOT NULL DEFAULT '' COMMENT 'pb:3',",
                "  `note` MEDIUMTEXT COMMENT 'pb:4',",
                "  `score` int DEFAULT 0 COMMENT 'pb:5',",
                "  `created_at` DATETIME(6) COMMENT 'pb:6',",
                "  `tags` MEDIUMBLOB COMMENT 'pb:7',",
                "  `counters` MEDIUMBLOB COMMENT 'pb:8',",
                "  `owner` MEDIUMBLOB COMMENT 'pb:9',",
                "  `opt_value` bigint NOT NULL DEFAULT 0 COMMENT 'pb:10',",
                "  `tier` int NOT NULL DEFAULT 0 COMMENT 'pb:11',",
                "  PRIMARY KEY (`owner_id`,`slot`),",
                "  INDEX `idx_kitchen_sink_0` (`created_at`),",
                "  INDEX `idx_kitchen_sink_1` (`note`(191)),",
                "  INDEX `idx_kitchen_sink_2` (`tags`(191)),",
                "  UNIQUE KEY `uk_kitchen_sink` (`token`)",
                TAIL + " COMMENT='kitchen_sink';"));
    }

    @Test
    void string_key_row_带空白的选项值按建表处同样拆分_自增列是普通索引第一列_AUTO_ID_CACHE() {
        assertThat(sql(StringKeyRow.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `string_key_row` (",
                "  `sub` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:1',",
                "  `provider` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',",
                "  `email` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:3',",
                "  `seq` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:4',",
                "  `bio` MEDIUMTEXT COMMENT 'pb:5',",
                "  PRIMARY KEY (`sub`),",
                "  INDEX `idx_string_key_row_0` (`seq`),",
                "  INDEX `idx_string_key_row_1` (`provider`,`sub`),",
                "  UNIQUE KEY `uk_string_key_row` (`provider`,`email`)",
                TAIL + " /*T![auto_id_cache] AUTO_ID_CACHE=1 */ COMMENT='string_key_row';"));
    }

    @Test
    void 超长索引名按FNV1a截断到64字符_表名本身不截() {
        // 指纹另用 PowerShell 独立实现的 FNV-1a 算出（标准向量 ""/"a"/"foobar" 已对上）
        assertThat(sql(LongNameRow.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `a_very_long_table_name_for_identifier_truncation_check_0123456` (",
                "  `id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `k` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',",
                "  `v` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  PRIMARY KEY (`id`),",
                "  INDEX `idx_a_very_long_table_name_for_identifier_truncation_ch_f043655f` (`v`),",
                "  UNIQUE KEY `uk_a_very_long_table_name_for_identifier_truncation_che_10512a9b` (`k`)",
                TAIL + " COMMENT='a_very_long_table_name_for_identifier_truncation_check_0123456';"));
    }

    @Test
    void 表名里的引号反斜杠反引号_标识符与注释各自转义() {
        assertThat(sql(GolangTest.getDefaultInstance(), withTableName("a'b\\c`d")))
                .startsWith("CREATE TABLE IF NOT EXISTS `a'b\\c``d` (\n")
                .endsWith(TAIL + " COMMENT='a''b\\\\c`d';");
    }

    // ================================================================ mmorpg 的表消息（另一套同号扩展）

    @Test
    void mmorpg_friend表_OptionTableName等同号选项照样生效() {
        assertThat(sql(FriendEdgeRecord.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `friend` (",
                "  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `friend_player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',",
                "  `since_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  PRIMARY KEY (`player_id`,`friend_player_id`) /*T![clustered_index] NONCLUSTERED */,",
                "  INDEX `idx_friend_0` (`friend_player_id`)",
                TAIL + " /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='friend';"));
    }

    @Test
    void mmorpg_guild表_string唯一键是VARCHAR键列_两个普通索引() {
        assertThat(sql(GuildRecord.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `guild` (",
                "  `guild_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `name` MEDIUMTEXT COMMENT 'pb:2',",
                "  `leader_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  `level` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',",
                "  `announcement` MEDIUMTEXT COMMENT 'pb:5',",
                "  `create_time_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6',",
                "  `max_members` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:7',",
                "  `zone_id` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:8',",
                "  `score` bigint NOT NULL DEFAULT 0 COMMENT 'pb:9',",
                "  `funds` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:10',",
                "  `name_norm` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:11',",
                "  PRIMARY KEY (`guild_id`) /*T![clustered_index] NONCLUSTERED */,",
                "  INDEX `idx_guild_0` (`zone_id`),",
                "  INDEX `idx_guild_1` (`leader_id`),",
                "  UNIQUE KEY `uk_guild` (`name_norm`)",
                TAIL + " /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='guild';"));
        assertThat(sql(GuildMemberRecord.getDefaultInstance())).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `guild_member` (",
                "  `guild_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:2',",
                "  `role` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',",
                "  `join_time_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',",
                "  `last_active_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:5',",
                "  `contribution_total` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6',",
                "  `contribution_balance` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:7',",
                "  PRIMARY KEY (`guild_id`,`player_id`) /*T![clustered_index] NONCLUSTERED */,",
                "  UNIQUE KEY `uk_guild_member` (`player_id`)",
                TAIL + " /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT='guild_member';"));
    }

    // ================================================================ 选项只以 unknown fields 存在

    @Test
    void 选项定义没被链接时从unknown_fields读出_同号多次出现后者覆盖前者() {
        MessageOptions options = MessageOptions.newBuilder().setUnknownFields(unknown()
                .addField(DescriptorOptions.TABLE_NAME, strings("first", "u_probe"))
                .addField(DescriptorOptions.PRIMARY_KEY, strings("id"))
                .addField(DescriptorOptions.INDEX, strings("v"))
                .addField(DescriptorOptions.UNIQUE_KEY, strings("code"))
                .addField(DescriptorOptions.TIDB_NONCLUSTERED_PK, varints(1))
                .addField(DescriptorOptions.TIDB_SHARD_ROW_ID_BITS, varints(3))
                .build()).build();
        FieldOptions maxLength = FieldOptions.newBuilder().setUnknownFields(unknown()
                .addField(DescriptorOptions.FIELD_MAX_LENGTH, varints(40)).build()).build();
        FieldOptions nullable = FieldOptions.newBuilder().setUnknownFields(unknown()
                .addField(DescriptorOptions.FIELD_NULLABLE, varints(1)).build()).build();
        Descriptor probe = DynamicTables.message("UnknownProbe", options,
                field("id", 1, Type.TYPE_UINT64),
                field("code", 2, Type.TYPE_STRING, maxLength),
                field("v", 3, Type.TYPE_INT32, nullable));

        assertThat(TableSchema.of(probe).createTableSql()).isEqualTo(ddl(
                "CREATE TABLE IF NOT EXISTS `u_probe` (",
                "  `id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',",
                "  `code` VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT '' COMMENT 'pb:2',",
                "  `v` int DEFAULT 0 COMMENT 'pb:3',",
                "  PRIMARY KEY (`id`) /*T![clustered_index] NONCLUSTERED */,",
                "  INDEX `idx_u_probe_0` (`v`),",
                "  UNIQUE KEY `uk_u_probe` (`code`)",
                TAIL + " /*T! SHARD_ROW_ID_BITS=3 */ COMMENT='u_probe';"));
    }

    @Test
    void 已知扩展声明成其他整数类型时与Go同口径读取() throws Exception {
        // 别处的选项定义把 TiDB 两项声明成 uint64 / fixed64、max_length 声明成 fixed32：Go 版 Value.Uint() / .(uint32) 都能读
        FileDescriptorProto extFile = FileDescriptorProto.newBuilder()
                .setName("dyn_int_ext.proto").setPackage("dynext").setSyntax("proto2")
                .addDependency("google/protobuf/descriptor.proto")
                .addExtension(extension("shard_u64", DescriptorOptions.TIDB_SHARD_ROW_ID_BITS, Type.TYPE_UINT64, "MessageOptions"))
                .addExtension(extension("split_f64", DescriptorOptions.TIDB_PRE_SPLIT_REGIONS, Type.TYPE_FIXED64, "MessageOptions"))
                .addExtension(extension("max_len_f32", DescriptorOptions.FIELD_MAX_LENGTH, Type.TYPE_FIXED32, "FieldOptions"))
                .build();
        FileDescriptor ext = FileDescriptor.buildFrom(extFile, new FileDescriptor[] {DescriptorProtos.getDescriptor()});
        MessageOptions options = MessageOptions.newBuilder()
                .setExtension(Proto2MysqlOption.primaryKey, "code")
                .setExtension(Proto2MysqlOption.tidbNonclusteredPk, true)
                .setField(ext.findExtensionByName("shard_u64"), 4L)
                .setField(ext.findExtensionByName("split_f64"), 2L)
                .build();
        FieldOptions maxLength = FieldOptions.newBuilder().setField(ext.findExtensionByName("max_len_f32"), 255).build();
        Descriptor probe = DynamicTables.message("IntKinds", options, field("code", 1, Type.TYPE_STRING, maxLength));

        assertThat(TableSchema.of(probe).createTableSql())
                .contains("`code` VARCHAR(255) ")
                .contains(" /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=2 */");
    }

    private static FieldDescriptorProto extension(String name, int number, Type type, String extendee) {
        return field(name, number, type).toBuilder().setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setExtendee(".google.protobuf." + extendee).build();
    }

    @Test
    void 同一个号既是已知扩展又在unknown_fields里_以已知扩展为准() {
        MessageOptions options = MessageOptions.newBuilder()
                .setExtension(Proto2MysqlOption.tableName, "known_wins")
                .setUnknownFields(unknown().addField(DescriptorOptions.TABLE_NAME, strings("unknown_loses")).build())
                .build();
        Descriptor probe = DynamicTables.message("Mixed", options, field("id", 1, Type.TYPE_UINT64));
        assertThat(TableSchema.of(probe).tableName()).isEqualTo("known_wins");
        assertThat(DescriptorOptions.tableName(probe)).contains("known_wins");
    }

    @Test
    void 代码选项覆盖proto选项_没声明table_name时退化为full_name() {
        assertThat(TableSchema.of(Account.getDefaultInstance(), withTableName("account_v2")).createTableSql())
                .startsWith("CREATE TABLE IF NOT EXISTS `account_v2` (")
                .contains("INDEX `idx_account_v2_0` (`name`(191))")
                .endsWith(" COMMENT='account_v2';");
        Descriptor plain = DynamicTables.message("Plain", field("x", 1, Type.TYPE_UINT32));
        assertThat(TableSchema.of(plain).tableName()).isEqualTo("dyn.Plain");
    }
}
