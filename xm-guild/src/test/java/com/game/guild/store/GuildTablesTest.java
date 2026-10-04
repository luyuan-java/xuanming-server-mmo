package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.guild.store.pb.GuildApplicationRow;
import com.game.guild.store.pb.GuildMemberRow;
import com.game.guild.store.pb.GuildPlayerStateRow;
import com.game.guild.store.pb.GuildRow;
import com.game.pbmysql.PbMysql;
import com.game.proto.guild.GuildApplicationRecord;
import com.game.proto.guild.GuildMemberRecord;
import com.game.proto.guild.GuildPlayerStateRecord;
import com.game.proto.guild.GuildRecord;
import com.google.protobuf.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 帮会四张表的 DDL 与 Go 版逐字节相同（guild-spec §1.2、§7.5、§11.3「建表」）：拿 Java 自有的 guild_tables.proto 生成的建表语句，
 * 对拍同步来的 mmorpg guild_db.proto（{@code com.game.proto.guild.*Record}，即 Go proto2mysql 的输入）生成的；guild 与 guild_member
 * 两张再与 spec §1.2（= xm-pbmysql CreateTableSqlTest:310-341）的原文逐字比对。不连库。
 */
class GuildTablesTest {

    private static final String TAIL = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
            + " /*T! SHARD_ROW_ID_BITS=4 PRE_SPLIT_REGIONS=4 */ COMMENT=";

    private static String ddl(PbMysql db, Class<? extends Message> type) {
        return db.createTableSql(type);
    }

    @Test
    void 四张表与同步来的guild_db逐字节相同() {
        PbMysql java = GuildTables.registry();
        PbMysql go = new PbMysql();
        go.register(GuildRecord.getDefaultInstance());
        go.register(GuildPlayerStateRecord.getDefaultInstance());
        go.register(GuildMemberRecord.getDefaultInstance());
        go.register(GuildApplicationRecord.getDefaultInstance());

        assertThat(ddl(java, GuildRow.class)).isEqualTo(ddl(go, GuildRecord.class));
        assertThat(ddl(java, GuildPlayerStateRow.class)).isEqualTo(ddl(go, GuildPlayerStateRecord.class));
        assertThat(ddl(java, GuildMemberRow.class)).isEqualTo(ddl(go, GuildMemberRecord.class));
        assertThat(ddl(java, GuildApplicationRow.class)).isEqualTo(ddl(go, GuildApplicationRecord.class));
    }

    @Test
    void guild与guild_member与规格原文逐字相同() {
        PbMysql java = GuildTables.registry();
        assertThat(ddl(java, GuildRow.class)).isEqualTo(String.join("\n",
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
                TAIL + "'guild';"));
        assertThat(ddl(java, GuildMemberRow.class)).isEqualTo(String.join("\n",
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
                TAIL + "'guild_member';"));
    }

    @Test
    void 状态行与申请表的索引() {
        PbMysql java = GuildTables.registry();
        assertThat(ddl(java, GuildPlayerStateRow.class))
                .contains("PRIMARY KEY (`player_id`) /*T![clustered_index] NONCLUSTERED */")
                .doesNotContain("INDEX").doesNotContain("UNIQUE");
        assertThat(ddl(java, GuildApplicationRow.class))
                .contains("PRIMARY KEY (`guild_id`,`player_id`) /*T![clustered_index] NONCLUSTERED */")
                .contains("INDEX `idx_guild_application_0` (`player_id`)")
                .contains("INDEX `idx_guild_application_1` (`expire_ms`)")
                .doesNotContain("UNIQUE");
    }

    @Test
    void 登记顺序就是表间锁序() {
        assertThat(GuildTables.NAMES).containsExactly("guild", "guild_player_state", "guild_member", "guild_application");
        PbMysql db = GuildTables.registry();
        List<String> registered = GuildTables.PROTOTYPES.stream().map(m -> db.schema(m).tableName()).toList();
        assertThat(registered).isEqualTo(GuildTables.NAMES);
    }
}
