package com.game.common.sql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigInteger;
import java.sql.*;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/** 面向JDBC接口验证；测试不改变真实大updateCount，也不把keys行数当受影响行数。 */
class AuthorityConnectionTest {
    private record Fixture(Connection raw,Connection managed,WriterAuthority authority){}
    private Fixture fixture()throws Exception{
        Connection raw=mock(Connection.class);when(raw.getAutoCommit()).thenReturn(true);
        WriterAuthority authority=mock(WriterAuthority.class);
        return new Fixture(raw,AuthorityConnection.wrap(raw,authority),authority);
    }
    private ResultSet keys(BigInteger... values)throws Exception{
        ResultSet keys=mock(ResultSet.class);ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(keys.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);when(metadata.getColumnTypeName(1)).thenReturn("BIGINT UNSIGNED");
        when(metadata.getColumnName(1)).thenReturn("GENERATED_KEY");when(metadata.getColumnLabel(1)).thenReturn("GENERATED_KEY");
        when(metadata.getColumnClassName(1)).thenReturn(BigInteger.class.getName());when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
        when(metadata.getSchemaName(1)).thenReturn("");when(metadata.getTableName(1)).thenReturn("");when(metadata.getCatalogName(1)).thenReturn("");
        when(keys.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);when(keys.getHoldability()).thenReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        int[] at={-1};when(keys.next()).thenAnswer(call->++at[0]<values.length);
        when(keys.getObject(1)).thenAnswer(call->values[at[0]]);
        when(keys.getString(1)).thenAnswer(call->values[at[0]].toString());
        when(keys.getLong(1)).thenThrow(new SQLDataException("原driver高半区溢出","22003",1264));
        return keys;
    }
    @Test void updateCountAndUnsignedKeysReturnOnlyAfterCommit()throws Exception{
        var f=fixture();PreparedStatement statement=mock(PreparedStatement.class);ResultSet original=keys(new BigInteger("18446744073709551615"));
        when(f.raw.prepareStatement("INSERT INTO business(v) VALUES (?)",Statement.RETURN_GENERATED_KEYS)).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(Integer.MAX_VALUE);when(statement.getGeneratedKeys()).thenReturn(original);
        var managed=f.managed.prepareStatement("INSERT INTO business(v) VALUES (?)",Statement.RETURN_GENERATED_KEYS);
        assertEquals(Integer.MAX_VALUE,managed.executeUpdate());
        var order=inOrder(statement,original,f.raw);order.verify(statement).executeUpdate();order.verify(statement).getGeneratedKeys();order.verify(original).close();order.verify(f.raw).commit();
        var cached=managed.getGeneratedKeys();assertTrue(cached.next());assertInstanceOf(BigInteger.class,cached.getObject(1));
        assertEquals(new BigInteger("18446744073709551615"),cached.getObject("GENERATED_KEY"));
        assertEquals("22003",assertThrows(SQLException.class,()->cached.getLong(1)).getSQLState());
        assertEquals(BigInteger.class.getName(),cached.getMetaData().getColumnClassName(1));assertSame(managed,cached.getStatement());
        managed.close();assertTrue(cached.isClosed());verify(statement,times(1)).executeUpdate();
    }
    @Test void commitReplyLossIsOpaqueUnknownAndDiscardsPhysical()throws Exception{
        var f=fixture();PreparedStatement statement=mock(PreparedStatement.class);when(f.raw.prepareStatement("UPDATE business SET v=1")).thenReturn(statement);
        ResultSet generated=keys(BigInteger.ONE);
        when(statement.executeUpdate()).thenReturn(7);when(statement.getGeneratedKeys()).thenReturn(generated);
        doThrow(new SQLRecoverableException("应答丢失","08S01",3024)).when(f.raw).commit();
        var managed=f.managed.prepareStatement("UPDATE business SET v=1");
        SQLException unknown=assertThrows(SQLException.class,managed::executeUpdate);assertEquals("08007",unknown.getSQLState());assertNull(unknown.getCause());
        assertEquals("08007",assertThrows(SQLException.class,managed::executeUpdate).getSQLState());verify(statement,times(1)).executeUpdate();verify(f.raw,atLeastOnce()).abort(any());verify(f.raw,never()).rollback();
    }
    @Test void secondAutoStatementCannotRunBeforeFirstEof()throws Exception{
        var f=fixture();Statement first=mock(Statement.class),second=mock(Statement.class);when(f.raw.createStatement()).thenReturn(first,second);
        ResultSet rows=mock(ResultSet.class);when(first.executeQuery("SELECT v FROM business")).thenReturn(rows);
        Statement a=f.managed.createStatement(),b=f.managed.createStatement();ResultSet result=a.executeQuery("SELECT v FROM business");
        SQLException blocked=assertThrows(SQLException.class,()->b.executeUpdate("UPDATE business SET v=2"));assertEquals("HY010",blocked.getSQLState());verify(second,never()).executeUpdate(anyString());
        assertSame(a,result.getStatement());assertSame(f.managed,a.getConnection());assertThrows(SQLException.class,()->a.unwrap(first.getClass()));
        result.close();verify(f.raw).commit();
    }
    @Test void explicitBatchPreservesDefinite1062PrefixAndCallerDecision()throws Exception{
        var f=fixture();PreparedStatement statement=mock(PreparedStatement.class);when(f.raw.prepareStatement("INSERT INTO business(v) VALUES (?)")).thenReturn(statement);
        BatchUpdateException duplicate=new BatchUpdateException("重复键","23000",1062,new int[]{1,1});when(statement.executeBatch()).thenThrow(duplicate);
        f.managed.setAutoCommit(false);var managed=f.managed.prepareStatement("INSERT INTO business(v) VALUES (?)");managed.addBatch();
        assertSame(duplicate,assertThrows(BatchUpdateException.class,managed::executeBatch));verify(f.raw,never()).commit();verify(f.raw,never()).rollback();
        f.managed.commit();verify(f.raw).commit();verify(f.authority,times(1)).begin(f.raw);
    }
    @Test void autoNativeBatchRejectsBeforeDelegate()throws Exception{
        var f=fixture();PreparedStatement statement=mock(PreparedStatement.class);when(f.raw.prepareStatement("INSERT INTO business(v) VALUES (?)")).thenReturn(statement);
        var managed=f.managed.prepareStatement("INSERT INTO business(v) VALUES (?)");managed.addBatch();assertThrows(SQLFeatureNotSupportedException.class,managed::executeBatch);verify(statement,never()).executeBatch();
    }
    @Test void sqlControlAndUnwrapCannotEscape()throws Exception{
        var f=fixture();assertThrows(SQLException.class,()->f.managed.prepareStatement("UPDATE writer_registry SET state=1"));
        assertThrows(SQLException.class,()->f.managed.prepareStatement("SELECT 1; DELETE FROM business"));
        assertThrows(SQLException.class,()->f.managed.prepareStatement("SET SESSION tidb_snapshot='old'"));
        assertThrows(SQLException.class,()->f.managed.unwrap(f.raw.getClass()));verify(f.raw,never()).prepareStatement(anyString());
    }

    @Test void heldCursorReentersFenceAfterCommitAndRollback()throws Exception{
        for(boolean commit:new boolean[]{true,false}){
            var f=fixture();Statement raw=mock(Statement.class);ResultSet cursor=mock(ResultSet.class);
            when(f.raw.createStatement()).thenReturn(raw);when(raw.executeQuery("SELECT v FROM business")).thenReturn(cursor);
            f.managed.setAutoCommit(false);ResultSet rows=f.managed.createStatement().executeQuery("SELECT v FROM business");
            if(commit)f.managed.commit();else f.managed.rollback();
            rows.updateRow();rows.insertRow();rows.deleteRow();
            var order=inOrder(f.authority,f.raw,cursor);
            order.verify(f.authority).begin(f.raw);
            if(commit)order.verify(f.raw).commit();else order.verify(f.raw).rollback();
            order.verify(f.authority).begin(f.raw);order.verify(cursor).updateRow();order.verify(cursor).insertRow();order.verify(cursor).deleteRow();
            verify(f.authority,times(2)).begin(f.raw);
        }
    }
    @Test void retiredFenceBlocksHeldCursorBeforeAnyMutation()throws Exception{
        var f=fixture();Statement raw=mock(Statement.class);ResultSet cursor=mock(ResultSet.class);
        when(f.raw.createStatement()).thenReturn(raw);when(raw.executeQuery("SELECT v FROM business")).thenReturn(cursor);
        f.managed.setAutoCommit(false);ResultSet rows=f.managed.createStatement().executeQuery("SELECT v FROM business");f.managed.commit();
        doThrow(new SQLException("本boot已退役","25000")).when(f.authority).begin(f.raw);
        assertThrows(SQLException.class,rows::updateRow);assertThrows(SQLException.class,rows::insertRow);assertThrows(SQLException.class,rows::deleteRow);
        assertThrows(SQLException.class,()->rows.updateString(1,"blocked"));
        verify(cursor,never()).updateRow();verify(cursor,never()).insertRow();verify(cursor,never()).deleteRow();verify(cursor,never()).updateString(anyInt(),anyString());
    }
    @Test void lobLocatorCannotWriteAfterFenceWasRetired()throws Exception{
        var f=fixture();Statement raw=mock(Statement.class);ResultSet cursor=mock(ResultSet.class);Blob blob=mock(Blob.class);
        when(f.raw.createStatement()).thenReturn(raw);when(raw.executeQuery("SELECT v FROM business")).thenReturn(cursor);when(cursor.getBlob(1)).thenReturn(blob);
        f.managed.setAutoCommit(false);Blob managed=f.managed.createStatement().executeQuery("SELECT v FROM business").getBlob(1);f.managed.rollback();
        doThrow(new SQLException("本boot已退役","25000")).when(f.authority).begin(f.raw);
        assertThrows(SQLException.class,()->managed.setBytes(1,new byte[]{1}));assertThrows(SQLException.class,()->managed.truncate(0));
        verify(blob,never()).setBytes(anyLong(),any(byte[].class));verify(blob,never()).truncate(anyLong());
    }
    @Test void writableLobStreamKeepsFenceUntilCloseAndRollbackInvalidatesIt()throws Exception{
        var f=fixture();Blob blob=mock(Blob.class);java.io.OutputStream stream=mock(java.io.OutputStream.class);when(f.raw.createBlob()).thenReturn(blob);when(blob.setBinaryStream(1)).thenReturn(stream);
        Blob managed=f.managed.createBlob();f.managed.setAutoCommit(false);java.io.OutputStream output=managed.setBinaryStream(1);output.write(7);
        assertEquals("HY010",assertThrows(SQLException.class,f.managed::commit).getSQLState());verify(f.raw,never()).commit();
        f.managed.rollback();var order=inOrder(f.authority,stream,f.raw);order.verify(f.authority).begin(f.raw);order.verify(stream).write(7);order.verify(stream).close();order.verify(f.raw).rollback();
        assertThrows(java.io.IOException.class,()->output.write(9));verify(stream,never()).write(9);
    }
    @Test void writableLobStreamFailurePoisonsPhysicalWithoutReplay()throws Exception{
        var f=fixture();Blob blob=mock(Blob.class);java.io.OutputStream stream=mock(java.io.OutputStream.class);when(f.raw.createBlob()).thenReturn(blob);when(blob.setBinaryStream(1)).thenReturn(stream);
        doThrow(new java.io.IOException("应答未知")).when(stream).write(7);
        java.io.OutputStream output=f.managed.createBlob().setBinaryStream(1);
        assertThrows(java.io.IOException.class,()->output.write(7));assertEquals("08007",assertThrows(SQLException.class,f.managed::createStatement).getSQLState());verify(stream,times(1)).write(7);verify(f.raw,atLeastOnce()).abort(any());
    }
    @Test void replayPropertiesAcceptOnlyExplicitFalse()throws Exception{
        for(String key:java.util.List.of("allowMultiQueries","autoReconnect","autoReconnectForPools","reconnectAtTxEnd","createDatabaseIfNotExist")){
            for(String value:java.util.List.of("true","YES","yes","1","unknown","","false ")){
                java.util.Properties properties=new java.util.Properties();properties.setProperty(key,value);
                assertThrows(SQLException.class,()->AuthorityDataSource.validateProperties(properties));
                assertThrows(SQLException.class,()->AuthorityDataSource.validateUrl("jdbc:mysql://127.0.0.1/test?"+key+"="+value));
            }
            java.util.Properties properties=new java.util.Properties();properties.setProperty(key,"FALSE");AuthorityDataSource.validateProperties(properties);AuthorityDataSource.validateUrl("jdbc:mysql://127.0.0.1/test?"+key+"=false");
        }
        assertThrows(SQLException.class,()->AuthorityDataSource.validateUrl("jdbc:mysql://127.0.0.1/test?auto%52econnect=%59ES"));
        assertThrows(SQLException.class,()->AuthorityDataSource.validateUrl("jdbc:mysql://127.0.0.1/test?autoReconnect=false&autoReconnect=YES"));
        java.util.Properties defaults=new java.util.Properties();defaults.setProperty("autoReconnect","YES");
        assertThrows(SQLException.class,()->AuthorityDataSource.validateProperties(new java.util.Properties(defaults)));
    }

    @Test void nestedArrayReferenceStillRequiresCurrentFence()throws Exception{
        var f=fixture();java.sql.Array array=mock(java.sql.Array.class);Ref ref=mock(Ref.class);
        when(f.raw.createArrayOf(eq("business"),any(Object[].class))).thenReturn(array);when(array.getArray()).thenReturn(new Object[]{ref});
        f.managed.setAutoCommit(false);Ref managed=(Ref)((Object[])f.managed.createArrayOf("business",new Object[0]).getArray())[0];
        managed.setObject("first");f.managed.commit();doThrow(new SQLException("已退役","25000")).when(f.authority).begin(f.raw);
        assertThrows(SQLException.class,()->managed.setObject("blocked"));verify(ref).setObject("first");verify(ref,never()).setObject("blocked");
    }
}
