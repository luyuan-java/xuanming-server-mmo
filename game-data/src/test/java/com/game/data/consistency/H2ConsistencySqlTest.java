package com.game.data.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class H2ConsistencySqlTest {
    @Test void databaseClockKeepsTheSameInstantAcrossSessionTimeZonesAndPreservesParameters() throws Exception {
        try (var db = memory()) {
            for (String zone : List.of("UTC", "+08:00", "-05:00")) {
                try (var settings = db.createStatement()) { settings.execute("SET TIME ZONE '" + zone + "'"); }
                String sql = "SELECT CURRENT_TIMESTAMP(3) AS source_time," + ConsistencySqlPlans.NOW
                        + ",? AS marker WHERE ? = ?";
                try (var statement = db.prepareStatement(H2ConsistencySql.forDatabase(db, sql))) {
                    statement.setString(1, "unchanged");
                    statement.setInt(2, 7);
                    statement.setInt(3, 7);
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        long expected = rows.getObject("source_time", OffsetDateTime.class).toInstant().toEpochMilli();
                        assertThat(rows.getObject("db_now")).isInstanceOf(Long.class);
                        assertThat(rows.getLong("db_now")).as(zone).isEqualTo(expected);
                        assertThat(rows.getString("marker")).isEqualTo("unchanged");
                        assertThat(rows.next()).isFalse();
                    }
                }
            }
        }
    }

    @Test void explicitDatabaseInstantsKeepMillisecondsTimeZoneAndValuesBeyondIntegerSeconds() throws Exception {
        try (var db = memory()) {
            for (String timestamp : List.of("2026-11-06 07:52:04.123-05:00",
                    "2040-01-02 03:04:05.678+08:00", "1969-12-31 23:59:59.999+00:00")) {
                // 仅本回归把数据库的 now 输入换成确定的带时区 SQL 值；不以 JVM 当前时间代替数据库时钟。
                String sql = H2ConsistencySql.forDatabase(db, "SELECT " + ConsistencySqlPlans.NOW)
                        .replace("CURRENT_TIMESTAMP(3)", "TIMESTAMP WITH TIME ZONE '" + timestamp + "'");
                try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) {
                    assertThat(rows.next()).isTrue();
                    long expected = OffsetDateTime.parse(timestamp.replace(' ', 'T')).toInstant().toEpochMilli();
                    assertThat(rows.getBigDecimal("db_now").longValueExact()).as(timestamp).isEqualTo(expected);
                    assertThat(rows.getObject("db_now")).isInstanceOf(Long.class);
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    @Test void nonH2ProductMetadataKeepsTheOriginalSqlBytes() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        String query = ConsistencySqlPlans.expiredUpper().sql();
        for (String product : List.of("MySQL", "TiDB")) {
            when(metadata.getDatabaseProductName()).thenReturn(product);
            assertThat(H2ConsistencySql.forDatabase(connection, query)).isSameAs(query);
        }
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        String unrelated = "SELECT ? AS marker WHERE 1=0";
        assertThat(H2ConsistencySql.forDatabase(connection, unrelated)).isSameAs(unrelated);
    }

    private static Connection memory() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:consistency_clock_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
    }
}
