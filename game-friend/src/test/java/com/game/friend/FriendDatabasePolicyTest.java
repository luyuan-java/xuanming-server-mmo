package com.game.friend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class FriendDatabasePolicyTest {
    private Connection database(String version, String txnMode) throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet versionRows = mock(ResultSet.class);
        ResultSet modeRows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT VERSION()")).thenReturn(versionRows);
        when(versionRows.next()).thenReturn(true);
        when(versionRows.getString(1)).thenReturn(version);
        when(statement.executeQuery("SELECT @@tidb_txn_mode")).thenReturn(modeRows);
        when(modeRows.next()).thenReturn(true);
        when(modeRows.getString(1)).thenReturn(txnMode);
        ResultSet readRows = mock(ResultSet.class);
        when(statement.executeQuery("SELECT @@tidb_read_consistency, @@tidb_snapshot, @@tidb_read_staleness, @@tidb_low_resolution_tso, @@tidb_replica_read"))
                .thenReturn(readRows);
        when(readRows.next()).thenReturn(true);
        when(readRows.getString(1)).thenReturn("strict");
        when(readRows.getString(2)).thenReturn("");
        when(readRows.getString(3)).thenReturn("0");
        when(readRows.getString(4)).thenReturn("0");
        when(readRows.getString(5)).thenReturn("leader");
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }

    @Test
    void 悲观事务不能放过陈旧或弱读会话() throws SQLException {
        Connection connection = database("8.0.11-TiDB-v8.5.2", "pessimistic");
        Statement statement = connection.createStatement();
        ResultSet weak = mock(ResultSet.class);
        when(weak.next()).thenReturn(true);
        when(weak.getString(1)).thenReturn("weak");
        when(weak.getString(2)).thenReturn("");
        when(weak.getString(3)).thenReturn("0");
        when(weak.getString(4)).thenReturn("0");
        when(weak.getString(5)).thenReturn("leader");
        when(statement.executeQuery("SELECT @@tidb_read_consistency, @@tidb_snapshot, @@tidb_read_staleness, @@tidb_low_resolution_tso, @@tidb_replica_read"))
                .thenReturn(weak);
        assertThatThrownBy(() -> FriendConfiguration.verifyFriendDatabase(connection, true))
                .isInstanceOf(SQLException.class).hasMessageContaining("强读");
    }

    @Test
    void 开发允许MySQL_生产要求TiDB() throws SQLException {
        FriendConfiguration.verifyFriendDatabase(database("8.4.0", ""), false);
        assertThatThrownBy(() -> FriendConfiguration.verifyFriendDatabase(database("8.4.0", ""), true))
                .isInstanceOf(SQLException.class).hasMessageContaining("不是 TiDB");
    }

    @Test
    void TiDB必须启用悲观事务() throws SQLException {
        FriendConfiguration.verifyFriendDatabase(database("8.0.11-TiDB-v8.5.2", "pessimistic"), true);
        assertThatThrownBy(() -> FriendConfiguration.verifyFriendDatabase(database("8.0.11-TiDB-v8.5.2", "optimistic"), true))
                .isInstanceOf(SQLException.class).hasMessageContaining("pessimistic");
    }

    @Test
    @EnabledIfSystemProperty(named = "game.it.tidb", matches = ".+")
    void 真实TiDB通过生产门禁_改为乐观事务即拒绝() throws SQLException {
        try (Connection connection = DriverManager.getConnection(System.getProperty("game.it.tidb"),
                System.getProperty("game.it.mysql.user", "root"), System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(FriendConnectionPolicy.SESSION_SQL);
            }
            FriendConfiguration.verifyFriendDatabase(connection, true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION tidb_txn_mode='optimistic'");
            }
            assertThatThrownBy(() -> FriendConfiguration.verifyFriendDatabase(connection, true))
                    .isInstanceOf(SQLException.class).hasMessageContaining("pessimistic");
        }
    }
}
