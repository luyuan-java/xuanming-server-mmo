package com.game.data.consistency;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 直接在进程内 H2 执行原生产 SELECT；不改写 SQL，不把本测试当作 TiDB 强读证明。 */
class TerminalOutboxBacklogSqlTest {
    private record Row(String kind, long pendingRows, BigDecimal oldestMs, long dbNow) { }

    @Test void emptyTablesReturnAllThreeKindsWithZeroCountsAndNullMinimum() throws Exception {
        try (var db = database()) {
            var rows = query(db);

            assertThat(rows).containsOnlyKeys("result", "first", "waiting");
            assertThat(rows.values()).allSatisfy(row -> {
                assertThat(row.pendingRows()).isZero();
                assertThat(row.oldestMs()).isNull();
                assertThat(row.dbNow()).isPositive();
            });
        }
    }

    @Test void resultCountsOnlyUnpublishedCompleteAndIncludesFutureRetry() throws Exception {
        try (var db = database()) {
            completion(db, 1, 1, 0, 2_000, Long.MAX_VALUE);
            completion(db, 2, 1, 0, 1_000, Long.MAX_VALUE);
            completion(db, 3, 2, 0, 100, 0); // ABORT。
            completion(db, 4, 0, 0, 50, 0); // 尚未归档终局。
            completion(db, 5, 1, 1, 10, 0); // 已确认发布。

            var rows = query(db);

            assertThat(rows.get("result").pendingRows()).isEqualTo(2);
            assertThat(rows.get("result").oldestMs()).isEqualByComparingTo("1000");
            assertThat(rows.get("first").pendingRows()).isZero();
            assertThat(rows.get("waiting").pendingRows()).isZero();
        }
    }

    @Test void personalKindsExcludeLegacyOwnerAndAppliedButKeepUnsignedHighInboxSequence() throws Exception {
        try (var db = database()) {
            personal(db, 1, 11, 0, "1", "0", 3_000, Long.MAX_VALUE);
            personal(db, 1, 12, 0, "1", "1", 2_000, Long.MAX_VALUE);
            personal(db, 1, 13, 0, "18446744073709551615", "18446744073709551615", 1_000, Long.MAX_VALUE);
            personal(db, 1, 14, 0, "0", "0", 10, 0); // 旧 Kafka 纯归档没有投递身份。
            personal(db, 1, 15, 0, "0", "9223372036854775808", 20, 0);
            personal(db, 1, 16, 1, "1", "0", 30, 0);
            personal(db, 1, 17, 1, "1", "1", 40, 0);

            var rows = query(db);

            assertThat(rows.get("first").pendingRows()).isEqualTo(1);
            assertThat(rows.get("first").oldestMs()).isEqualByComparingTo("3000");
            assertThat(rows.get("waiting").pendingRows()).isEqualTo(2);
            assertThat(rows.get("waiting").oldestMs()).isEqualByComparingTo("1000");
            assertThat(rows.get("result").pendingRows()).isZero();
        }
    }

    @Test void oldestUsesIssuedTimeRatherThanRetrySchedule() throws Exception {
        try (var db = database()) {
            completion(db, 1, 1, 0, 9_000, 1);
            completion(db, 2, 1, 0, 2_000, Long.MAX_VALUE);
            personal(db, 1, 11, 0, "1", "0", 8_000, 1);
            personal(db, 2, 12, 0, "1", "0", 3_000, Long.MAX_VALUE);
            personal(db, 1, 13, 0, "1", "1", 7_000, 1);
            personal(db, 2, 14, 0, "1", "2", 4_000, Long.MAX_VALUE);

            var rows = query(db);

            assertThat(rows.values()).allSatisfy(row -> assertThat(row.pendingRows()).isEqualTo(2));
            assertThat(rows.get("result").oldestMs()).isEqualByComparingTo("2000");
            assertThat(rows.get("first").oldestMs()).isEqualByComparingTo("3000");
            assertThat(rows.get("waiting").oldestMs()).isEqualByComparingTo("4000");
        }
    }

    @Test void oneStatementReturnsTheSameDatabaseClockForAllKinds() throws Exception {
        try (var db = database()) {
            completion(db, 1, 1, 0, 1_000, 0);
            personal(db, 1, 11, 0, "1", "0", 1_000, 0);
            personal(db, 1, 12, 0, "1", "1", 1_000, 0);

            var rows = query(db);

            assertThat(rows.values().stream().map(Row::dbNow).distinct().toList()).hasSize(1);
            assertThat(rows.values()).allSatisfy(row -> assertThat(row.dbNow()).isGreaterThan(1_000));
        }
    }

    @Test void publishedAndAppliedProgressMovesKindsWithoutChangingArchiveTime() throws Exception {
        try (var db = database()) {
            completion(db, 1, 1, 0, 5_000, 0);
            personal(db, 1, 11, 0, "7", "0", 5_000, 0);
            var before = query(db);
            assertThat(before.get("result").pendingRows()).isEqualTo(1);
            assertThat(before.get("first").pendingRows()).isEqualTo(1);
            assertThat(before.get("waiting").pendingRows()).isZero();

            try (var statement = db.createStatement()) {
                statement.executeUpdate("UPDATE match_battle_completion SET result_published=1 WHERE battle_id=1");
                statement.executeUpdate("UPDATE match_battle_player_completion SET inbox_seq=9223372036854775808,retry_ms=9223372036854775807 WHERE battle_id=1 AND player_id=11");
            }
            var enqueued = query(db);
            assertThat(enqueued.get("result").pendingRows()).isZero();
            assertThat(enqueued.get("result").oldestMs()).isNull();
            assertThat(enqueued.get("first").pendingRows()).isZero();
            assertThat(enqueued.get("first").oldestMs()).isNull();
            assertThat(enqueued.get("waiting").pendingRows()).isEqualTo(1);
            assertThat(enqueued.get("waiting").oldestMs()).isEqualByComparingTo("5000");

            try (var statement = db.createStatement()) {
                statement.executeUpdate("UPDATE match_battle_player_completion SET applied=1 WHERE battle_id=1 AND player_id=11");
            }
            var applied = query(db);
            assertThat(applied.values()).allSatisfy(row -> {
                assertThat(row.pendingRows()).isZero();
                assertThat(row.oldestMs()).isNull();
            });
        }
    }

    @Test void countsHaveNoDeliveryPageLimitAndRepeatedObservationsDoNotWriteRows() throws Exception {
        try (var db = database()) {
            for (int id = 1; id <= 40; id++) {
                completion(db, id, 1, 0, id * 1_000L, Long.MAX_VALUE);
                personal(db, id, id, 0, "1", id % 2 == 0 ? "0" : "1", id * 1_000L, Long.MAX_VALUE);
            }
            var first = query(db);
            var second = query(db);

            assertThat(first.get("result").pendingRows()).isEqualTo(40);
            assertThat(first.get("first").pendingRows()).isEqualTo(20);
            assertThat(first.get("waiting").pendingRows()).isEqualTo(20);
            for (String kind : first.keySet()) {
                assertThat(second.get(kind).pendingRows()).isEqualTo(first.get(kind).pendingRows());
                assertThat(second.get(kind).oldestMs()).isEqualByComparingTo(first.get(kind).oldestMs());
            }
            try (var statement = db.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM match_battle_completion WHERE result_published=0 AND retry_ms=9223372036854775807")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(40);
                assertThat(rows.next()).isFalse();
            }
            try (var statement = db.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM match_battle_player_completion WHERE applied=0 AND owner_revision=1 AND retry_ms=9223372036854775807")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(40);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test void zeroAndNullMinimumEvidenceIsNotSilentlyReplacedBySql() throws Exception {
        try (var db = database()) {
            completion(db, 1, 1, 0, 0, 0);
            personal(db, 1, 11, 0, "1", "0", 1_000, 0);
            try (var statement = db.createStatement()) {
                statement.executeUpdate("UPDATE match_battle_player_completion SET issued_at_ms=NULL WHERE battle_id=1 AND player_id=11");
            }

            var rows = query(db);

            assertThat(rows.get("result").pendingRows()).isEqualTo(1);
            assertThat(rows.get("result").oldestMs()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rows.get("first").pendingRows()).isEqualTo(1);
            assertThat(rows.get("first").oldestMs()).isNull();
            // 非空但缺失/零时间由 Audit 解码判 UNKNOWN，本层不伪造健康年龄。
            assertThat(rows.get("waiting").pendingRows()).isZero();
            assertThat(rows.get("waiting").oldestMs()).isNull();
        }
    }

    @Test void mixedNullAndValidArchiveTimesRemainUnknownForEveryKind() throws Exception {
        try (var db = database()) {
            completion(db, 1, 1, 0, 1_000, 0);
            completion(db, 2, 1, 0, 2_000, 0);
            personal(db, 1, 11, 0, "1", "0", 1_000, 0);
            personal(db, 2, 12, 0, "1", "0", 2_000, 0);
            personal(db, 1, 13, 0, "1", "1", 1_000, 0);
            personal(db, 2, 14, 0, "1", "2", 2_000, 0);
            try (var statement = db.createStatement()) {
                statement.executeUpdate("UPDATE match_battle_completion SET issued_at_ms=NULL WHERE battle_id=1");
                statement.executeUpdate("UPDATE match_battle_player_completion SET issued_at_ms=NULL WHERE battle_id=1");
            }

            var rows = query(db);

            // MIN 本身会忽略 NULL，必须额外证明每条待投原件都有归档时间。
            assertThat(rows.values()).allSatisfy(row -> {
                assertThat(row.pendingRows()).isEqualTo(2);
                assertThat(row.oldestMs()).isNull();
            });
        }
    }

    private static Connection database() throws Exception {
        Connection db = DriverManager.getConnection("jdbc:h2:mem:terminal_backlog_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (var statement = db.createStatement()) {
            statement.execute("CREATE TABLE match_battle_completion (battle_id DECIMAL(20,0) PRIMARY KEY,terminal_decision INT,result_published INT,issued_at_ms DECIMAL(20,0),retry_ms BIGINT)");
            statement.execute("CREATE TABLE match_battle_player_completion (battle_id DECIMAL(20,0),player_id DECIMAL(20,0),applied INT,owner_revision DECIMAL(20,0),inbox_seq DECIMAL(20,0),issued_at_ms DECIMAL(20,0),retry_ms BIGINT,PRIMARY KEY(battle_id,player_id))");
        } catch (Exception failure) {
            db.close();
            throw failure;
        }
        return db;
    }

    private static void completion(Connection db, long battle, int decision, int published, long issued, long retry) throws Exception {
        try (var statement = db.prepareStatement("INSERT INTO match_battle_completion VALUES(?,?,?,?,?)")) {
            statement.setLong(1, battle); statement.setInt(2, decision); statement.setInt(3, published);
            statement.setLong(4, issued); statement.setLong(5, retry); statement.executeUpdate();
        }
    }

    private static void personal(Connection db, long battle, long player, int applied, String revision, String seq,
                                 long issued, long retry) throws Exception {
        try (var statement = db.prepareStatement("INSERT INTO match_battle_player_completion VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, battle); statement.setLong(2, player); statement.setInt(3, applied);
            statement.setBigDecimal(4, new BigDecimal(revision)); statement.setBigDecimal(5, new BigDecimal(seq));
            statement.setLong(6, issued); statement.setLong(7, retry); statement.executeUpdate();
        }
    }

    private static Map<String, Row> query(Connection db) throws Exception {
        List<Row> result = new ArrayList<>();
        try (var statement = db.prepareStatement(H2ConsistencySql.forDatabase(db, TerminalOutboxBacklogQueries.snapshot())); var rows = statement.executeQuery()) {
            while (rows.next()) result.add(new Row(rows.getString("kind"), rows.getBigDecimal("pending_rows").longValueExact(),
                    rows.getBigDecimal("oldest_ms"), rows.getBigDecimal("db_now").longValueExact()));
        }
        assertThat(result).hasSize(3);
        assertThat(result).extracting(Row::kind).containsExactlyInAnyOrder("result", "first", "waiting");
        return result.stream().collect(Collectors.toMap(Row::kind, Function.identity()));
    }
}
