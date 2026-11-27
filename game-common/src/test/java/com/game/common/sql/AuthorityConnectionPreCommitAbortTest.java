package com.game.common.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import org.junit.jupiter.api.Test;

/** 覆盖 J2：显式事务 COMMIT 之前连接错误返回确定已中止（40001），COMMIT 阶段连接丢失保持结局未知（08007）。 */
class AuthorityConnectionPreCommitAbortTest {

    @Test
    void preCommitConnectionDropReturnsDefiniteAbortedWhileCommitDropRemainsUnknown() throws Exception {
        Connection raw1 = mock(Connection.class);
        when(raw1.getAutoCommit()).thenReturn(true);
        WriterAuthority auth = mock(WriterAuthority.class);
        PreparedStatement ps1 = mock(PreparedStatement.class);
        when(raw1.prepareStatement("UPDATE t SET v=1")).thenReturn(ps1);
        when(ps1.executeUpdate()).thenThrow(new SQLRecoverableException("TiDB 重启前连接断开", "08S01", 2013));

        Connection managed1 = AuthorityConnection.wrap(raw1, auth);
        managed1.setAutoCommit(false);
        PreparedStatement mps1 = managed1.prepareStatement("UPDATE t SET v=1");
        SQLException preCommit = assertThrows(SQLException.class, mps1::executeUpdate);
        assertEquals(AuthorityConnection.PRE_COMMIT_ABORTED_SQL_STATE, preCommit.getSQLState());
        verify(raw1, atLeastOnce()).abort(any());

        // COMMIT 阶段连接丢失仍为 08007（结局未知）
        Connection raw2 = mock(Connection.class);
        when(raw2.getAutoCommit()).thenReturn(true);
        PreparedStatement ps2 = mock(PreparedStatement.class);
        when(raw2.prepareStatement("UPDATE t SET v=1")).thenReturn(ps2);
        when(ps2.executeUpdate()).thenReturn(1);
        doThrow(new SQLRecoverableException("COMMIT 应答丢失", "08S01", 3024)).when(raw2).commit();

        Connection managed2 = AuthorityConnection.wrap(raw2, auth);
        managed2.setAutoCommit(false);
        managed2.prepareStatement("UPDATE t SET v=1").executeUpdate();
        SQLException commitUnknown = assertThrows(SQLException.class, managed2::commit);
        assertEquals("08007", commitUnknown.getSQLState());

        // COMMIT 阶段服务端明确返回 1213/40001（已回滚）时返回确定中止 40001
        Connection raw3 = mock(Connection.class);
        when(raw3.getAutoCommit()).thenReturn(true);
        PreparedStatement ps3 = mock(PreparedStatement.class);
        when(raw3.prepareStatement("UPDATE t SET v=1")).thenReturn(ps3);
        when(ps3.executeUpdate()).thenReturn(1);
        doThrow(new SQLException("Deadlock rolled back", "40001", 1213)).when(raw3).commit();

        Connection managed3 = AuthorityConnection.wrap(raw3, auth);
        managed3.setAutoCommit(false);
        managed3.prepareStatement("UPDATE t SET v=1").executeUpdate();
        SQLException commitRolledBack = assertThrows(SQLException.class, managed3::commit);
        assertEquals(AuthorityConnection.PRE_COMMIT_ABORTED_SQL_STATE, commitRolledBack.getSQLState());
    }
}
