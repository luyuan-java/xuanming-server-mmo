package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.BattleResultTopics;
import com.game.match.rating.pb.MatchRatingAppliedRow;
import com.game.match.rating.pb.MatchRatingRow;
import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableSchema;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 评分两张表的登记（match-spec §5.2）：建表语句逐字钉住（任何改动都会让存量库走 pbmysql 的结构同步，必须是只扩不缩的追加）；
 * {@link RatingStore} 手写 SQL 里的表名、列名与表定义一致；H2 上用同一份 DDL 建得出来。
 */
class MatchRatingTablesTest {

    @Test
    void 登记了两张表_名字与原型一一对应() {
        PbMysql registry = MatchRatingTables.registry();

        assertThat(MatchRatingTables.NAMES).containsExactly("match_rating", "match_rating_applied");
        assertThat(MatchRatingTables.PROTOTYPES).hasSize(2);
        assertThat(registry.tables()).extracting(TableSchema::tableName).containsExactlyInAnyOrder("match_rating", "match_rating_applied");
        assertThat(registry.schema(MatchRatingRow.class).tableName()).isEqualTo(MatchRatingTables.RATING);
        assertThat(registry.schema(MatchRatingAppliedRow.class).tableName()).isEqualTo(MatchRatingTables.APPLIED);
        for (int i = 0; i < MatchRatingTables.NAMES.size(); i++) {
            assertThat(registry.schema(MatchRatingTables.PROTOTYPES.get(i)).tableName()).isEqualTo(MatchRatingTables.NAMES.get(i));
        }
    }

    @Test
    void 评分表的建表语句逐字钉住() {
        String ddl = MatchRatingTables.registry().createTableSql(MatchRatingRow.class);

        assertThat(ddl).isEqualTo("""
                CREATE TABLE IF NOT EXISTS `match_rating` (
                  `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
                  `rating_centi` bigint NOT NULL DEFAULT 0 COMMENT 'pb:2',
                  `games` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3',
                  `updated_at_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',
                  PRIMARY KEY (`player_id`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='match_rating';""");
    }

    @Test
    void 入账标记表的建表语句逐字钉住() {
        String ddl = MatchRatingTables.registry().createTableSql(MatchRatingAppliedRow.class);

        assertThat(ddl).isEqualTo("""
                CREATE TABLE IF NOT EXISTS `match_rating_applied` (
                  `battle_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:1',
                  `match_mode` int NOT NULL DEFAULT 0 COMMENT 'pb:2',
                  `delta_a_centi` bigint NOT NULL DEFAULT 0 COMMENT 'pb:3',
                  `applied_at_ms` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:4',
                  PRIMARY KEY (`battle_id`),
                  INDEX `idx_match_rating_applied_0` (`applied_at_ms`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='match_rating_applied';""");
    }

    @Test
    void 手写SQL里的表名与列名都在表定义里() {
        List<String> ratingColumns = columns(MatchRatingRow.getDescriptor());
        List<String> appliedColumns = columns(MatchRatingAppliedRow.getDescriptor());

        assertThat(ratingColumns).containsExactly("player_id", "rating_centi", "games", "updated_at_ms");
        assertThat(appliedColumns).containsExactly("battle_id", "match_mode", "delta_a_centi", "applied_at_ms");

        assertThat(RatingStore.SQL_INSERT_APPLIED).startsWith("INSERT INTO " + MatchRatingTables.APPLIED + " (" + String.join(", ", appliedColumns) + ")");
        assertThat(RatingStore.SQL_ENSURE_ROW).startsWith("INSERT INTO " + MatchRatingTables.RATING + " (" + String.join(", ", ratingColumns) + ")")
                .as("补行不用 INSERT IGNORE").doesNotContainIgnoringCase("IGNORE").endsWith("ON DUPLICATE KEY UPDATE player_id = player_id");
        assertThat(RatingStore.SQL_LOCK_ROW).isEqualTo("SELECT rating_centi FROM " + MatchRatingTables.RATING + " WHERE player_id = ? FOR UPDATE");
        assertThat(RatingStore.SQL_UPDATE_ROW)
                .isEqualTo("UPDATE " + MatchRatingTables.RATING + " SET rating_centi = ?, games = games + 1, updated_at_ms = ? WHERE player_id = ?");
        assertThat(RatingStore.SQL_SET_DELTA).isEqualTo("UPDATE " + MatchRatingTables.APPLIED + " SET delta_a_centi = ? WHERE battle_id = ?");
        assertThat(RatingStore.SQL_FIND_PREFIX).isEqualTo("SELECT player_id, rating_centi, games FROM " + MatchRatingTables.RATING + " WHERE player_id IN (");
        assertThat(RatingStore.SQL_DELETE_APPLIED).isEqualTo("DELETE FROM " + MatchRatingTables.APPLIED + " WHERE applied_at_ms < ? LIMIT ?");
    }

    /**
     * 标记是恰好一次的唯一依据：它必须比结果消息活得久，否则「标记已删、消息还在」的那段时间里一次从最早位点的重放就是重复入账。
     * 消息的最长寿命 = topic 的保留期 + 滚段周期（Kafka 按段删除，段里最新一条过了保留期整段才删）。topic 的规格在 xm-audit，
     * 那边改保留期或声明 segment.ms 时这条用例会红，提醒把 {@link RatingCleanup#RETENTION} 一起重算。
     */
    @Test
    void 入账标记的保留期长于结果消息在topic里的最长寿命_保留期加滚段周期再加一周余量() {
        Map<String, String> topic = BattleResultTopics.spec(1).configs();
        long retentionMs = Long.parseLong(topic.get("retention.ms"));
        // 没有声明 segment.ms 时取 broker 的缺省滚段周期（log.roll.hours = 168）
        long segmentRollMs = topic.containsKey("segment.ms") ? Long.parseLong(topic.get("segment.ms")) : Duration.ofDays(7).toMillis();
        long weekMs = Duration.ofDays(7).toMillis();

        assertThat(retentionMs).as("topic 保留 7 天").isEqualTo(604_800_000L);
        assertThat(RatingCleanup.TOPIC_RETENTION.toMillis()).as("RatingCleanup 里抄的那份与 topic 规格一致").isEqualTo(retentionMs);
        assertThat(RatingCleanup.TOPIC_SEGMENT_ROLL.toMillis()).isEqualTo(segmentRollMs);
        assertThat(RatingCleanup.RETENTION_MARGIN.toMillis()).isGreaterThanOrEqualTo(weekMs);
        assertThat(RatingCleanup.RETENTION.toMillis()).as("标记保留期 ≥ 消息最长寿命 + 余量").isGreaterThanOrEqualTo(retentionMs + segmentRollMs + weekMs);
        assertThat(RatingCleanup.RETENTION.toDays()).isEqualTo(30);
    }

    @Test
    void H2上用同一份DDL建得出两张表_列与清理索引都在() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2(); Connection c = db.dataSource.getConnection(); Statement st = c.createStatement()) {
            List<String> ratingColumns = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_name = 'match_rating' ORDER BY ordinal_position")) {
                while (rs.next()) {
                    ratingColumns.add(rs.getString(1));
                }
            }
            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT index_name FROM information_schema.indexes WHERE table_name = 'match_rating_applied'")) {
                while (rs.next()) {
                    indexes.add(rs.getString(1).toLowerCase());
                }
            }

            assertThat(ratingColumns).containsExactly("player_id", "rating_centi", "games", "updated_at_ms");
            assertThat(indexes).contains("idx_match_rating_applied_0");
            // 建表是幂等的：再建一次不报错、不清数据
            db.putRating(1, 150_000, 0);
            RatingTestDatabase.H2_SCHEMA.sync(db.dataSource);
            assertThat(db.count("match_rating")).isEqualTo(1);
        }
    }

    /** 列名 = proto 字段名（pbmysql 的映射规则），按字段声明顺序。 */
    private static List<String> columns(Descriptor message) {
        return message.getFields().stream().map(FieldDescriptor::getName).toList();
    }
}
