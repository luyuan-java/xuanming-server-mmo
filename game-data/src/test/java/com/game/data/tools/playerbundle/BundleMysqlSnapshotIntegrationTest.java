package com.game.data.tools.playerbundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 只连显式指定的真实 MySQL / TiDB；不创建业务表，不用 H2 或连接替身代替服务器事务行为。 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class BundleMysqlSnapshotIntegrationTest {
    @Test
    @Timeout(20)
    void 实际版本开启快照后可以执行查询并回滚() throws Exception {
        String url = System.getProperty("game.it.mysql").replaceAll("/$", "")
                + "/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=10000";
        String user = System.getProperty("game.it.mysql.user", "root");
        String password = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            String version;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery("SELECT VERSION()")) {
                    assertThat(rows.next()).isTrue();
                    version = rows.getString(1);
                }
            }
            assertThat(version).isNotBlank();
            try {
                BundleMysqlReader.beginSnapshot(connection, version);
                assertThat(connection.getAutoCommit()).isFalse();
                assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                if (!version.toLowerCase(Locale.ROOT).contains("tidb")) {
                    assertThat(connection.isReadOnly()).as("MySQL 仍保留 JDBC 只读保护").isTrue();
                }
                // TiDB 在首条语句进入事务时才可能报 1235，必须真实执行，不能只检查 JDBC 标记。
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(5);
                    try (var rows = statement.executeQuery("SELECT 1")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getInt(1)).isEqualTo(1);
                        assertThat(rows.next()).isFalse();
                    }
                }
            } finally {
                BundleMysqlReader.endSnapshot(connection);
            }
        }
    }
}
