package com.game.pbmysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.game.pbmysql.testpb.Account;
import java.sql.*;
import org.junit.jupiter.api.Test;

/** normal核验缺结构必须拒启，不能执行CREATE/ALTER或GET_LOCK。 */
class SchemaVerifyTest {
    @Test void missingTableFailsBeforeAnyDdlOrAdvisoryLock()throws Exception{
        Connection connection=mock(Connection.class);Statement statement=mock(Statement.class);ResultSet database=mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery("SELECT DATABASE()")).thenReturn(database);
        when(database.next()).thenReturn(true,false);when(database.getString(1)).thenReturn("fixture");
        PreparedStatement table=mock(PreparedStatement.class);ResultSet missing=mock(ResultSet.class);when(connection.prepareStatement(anyString())).thenReturn(table);when(table.executeQuery()).thenReturn(missing);when(missing.next()).thenReturn(true);when(missing.getInt(1)).thenReturn(0);
        PbMysql db=new PbMysql();db.register(Account.getDefaultInstance());
        assertThrows(SQLException.class,()->db.syncAll(connection));verify(statement,never()).execute(anyString());verify(table,never()).executeUpdate();
        verify(connection,never()).prepareStatement(contains("GET_LOCK"));
    }
    @Test void normalVerificationDoesNotChangeTransactionOwnership()throws Exception{
        Connection connection=mock(Connection.class);PbMysql empty=new PbMysql();Statement statement=mock(Statement.class);ResultSet database=mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);when(statement.executeQuery("SELECT DATABASE()")).thenReturn(database);when(database.next()).thenReturn(true,false);when(database.getString(1)).thenReturn("fixture");
        empty.syncAll(connection);verify(connection,never()).setAutoCommit(anyBoolean());verify(connection,never()).commit();verify(connection,never()).rollback();
    }
}
