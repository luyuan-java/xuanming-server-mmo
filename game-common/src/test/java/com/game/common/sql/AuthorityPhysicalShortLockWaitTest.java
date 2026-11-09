package com.game.common.sql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 固定好友/全球匹配初始化的物理接缝；这里不将 JDBC 替身当真实后端证据。 */
class AuthorityPhysicalShortLockWaitTest {
    private static final String SHORT = AuthorityDataSource.STRONG_SHORT_LOCK_WAIT_SQL;
    private static final String READ = "SELECT @@SESSION.innodb_lock_wait_timeout";
    private record Physical(Connection connection, Statement statement, ResultSet lockWait) { }

    private static Physical physical(String product, String version, long wait, boolean missing,
                                     boolean duplicate, boolean wasNull) throws SQLException {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet vr = mock(ResultSet.class), strong = mock(ResultSet.class), lock = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT VERSION()")).thenReturn(vr);
        when(vr.next()).thenReturn(true);
        when(vr.getString(1)).thenReturn(version);
        when(statement.executeQuery(startsWith("SELECT @@SESSION.tidb_snapshot"))).thenReturn(strong);
        when(strong.next()).thenReturn(true);
        when(strong.getString(1)).thenReturn("");
        when(strong.getLong(2)).thenReturn(0L);
        when(strong.getBoolean(3)).thenReturn(false);
        when(strong.getString(4)).thenReturn("strict");
        when(strong.getString(5)).thenReturn("leader");
        when(strong.getString(6)).thenReturn("pessimistic");
        when(strong.getString(7)).thenReturn("READ-COMMITTED");
        when(statement.executeQuery(READ)).thenReturn(lock);
        if (missing) when(lock.next()).thenReturn(false);
        else when(lock.next()).thenReturn(true, duplicate);
        when(lock.getLong(1)).thenReturn(wait);
        when(lock.wasNull()).thenReturn(wasNull);
        return new Physical(connection, statement, lock);
    }

    @Test void acceptsOnlyTheFixedReviewedStatementAndDeduplicatesIt() throws Exception {
        assertEquals(List.of(SHORT, AuthorityDataSource.STRONG_SESSION_SQL), AuthorityDataSource.checkedInitialization(
                List.of(SHORT, SHORT, AuthorityDataSource.STRONG_SESSION_SQL)));
        for (String sql : List.of(SHORT + ";SELECT 1", SHORT.replace("timeout=3", "timeout=30"),
                "SET SESSION innodb_lock_wait_timeout=3", SHORT.replace("'leader'", "'follower'")))
            assertThrows(SQLException.class, () -> AuthorityDataSource.checkedInitialization(List.of(sql)));
    }

    @Test void appliesShortWaitBeforeNormalStrongReadbackAndChecksItsActualValue() throws Exception {
        var p = physical("MySQL", "8.0.11-TiDB-v8.5.8", 3, false, false, false);
        AuthorityDataSource.initialize(p.connection(), true, List.of(SHORT, AuthorityDataSource.STRONG_SESSION_SQL));
        var ordered = inOrder(p.statement());
        ordered.verify(p.statement()).execute(SHORT);
        ordered.verify(p.statement()).execute(AuthorityDataSource.STRONG_SESSION_SQL);
        ordered.verify(p.statement()).executeQuery(startsWith("SELECT @@SESSION.tidb_snapshot"));
        ordered.verify(p.statement()).executeQuery(READ);
        verify(p.lockWait(), times(2)).next();
        verify(p.lockWait()).wasNull();
    }

    @Test void wrongMissingNullOrDuplicateReadbackRefusesThePhysicalConnection() throws Exception {
        for (var p : List.of(physical("MySQL", "TiDB", 5, false, false, false),
                physical("MySQL", "TiDB", 3, true, false, false),
                physical("MySQL", "TiDB", 3, false, true, false),
                physical("MySQL", "TiDB", 3, false, false, true)))
            assertThrows(SQLException.class, () -> AuthorityDataSource.initialize(p.connection(), true, List.of(SHORT)));
    }

    @Test void doesNotSendTiDbPolicyToOtherBackendsOrPermitBusinessSessionWrites() throws Exception {
        for (var p : List.of(physical("MySQL", "8.4.13", 3, false, false, false),
                physical("H2", "unused", 3, false, false, false))) {
            assertThrows(SQLException.class, () -> AuthorityDataSource.initialize(p.connection(), false, List.of(SHORT)));
            verify(p.statement(), never()).execute(anyString());
        }
        var normal = physical("MySQL", "TiDB", 50, false, false, false);
        AuthorityDataSource.initialize(normal.connection(), true, List.of());
        verify(normal.statement(), never()).executeQuery(READ);
        Connection raw = mock(Connection.class);
        when(raw.getAutoCommit()).thenReturn(true);
        Connection managed = AuthorityConnection.wrap(raw, mock(WriterAuthority.class));
        assertThrows(SQLException.class, () -> managed.prepareStatement(SHORT));
        verify(raw, never()).prepareStatement(anyString());
    }
}
