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

/** 公会固定 OFF 物理初始化契约；不发送真实 SQL。 */
class AuthorityPhysicalFairReadbackTest {
    private static final String FAIR_READ = "SELECT @@SESSION.tidb_pessimistic_txn_fair_locking";
    private record Physical(Connection c, Statement statement, ResultSet strong, ResultSet fair) { }

    private static Physical physical(String product, String version, String fair, boolean empty, boolean duplicate) throws SQLException {
        Connection c=mock(Connection.class); DatabaseMetaData metadata=mock(DatabaseMetaData.class);
        Statement statement=mock(Statement.class); ResultSet vr=mock(ResultSet.class);
        ResultSet strong=mock(ResultSet.class), fr=mock(ResultSet.class);
        when(c.getAutoCommit()).thenReturn(true);when(c.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);when(c.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT VERSION()")).thenReturn(vr);
        when(vr.next()).thenReturn(true);when(vr.getString(1)).thenReturn(version);
        when(statement.executeQuery(startsWith("SELECT @@SESSION.tidb_snapshot"))).thenReturn(strong);
        when(strong.next()).thenReturn(true);when(strong.getString(1)).thenReturn("");
        when(strong.getLong(2)).thenReturn(0L);when(strong.getBoolean(3)).thenReturn(false);
        when(strong.getString(4)).thenReturn("strict");when(strong.getString(5)).thenReturn("leader");
        when(strong.getString(6)).thenReturn("pessimistic");when(strong.getString(7)).thenReturn("READ-COMMITTED");
        when(statement.executeQuery(FAIR_READ)).thenReturn(fr);
        if (empty) when(fr.next()).thenReturn(false); else when(fr.next()).thenReturn(true,duplicate);
        when(fr.getString(1)).thenReturn(fair);
        return new Physical(c,statement,strong,fr);
    }

    @Test void guild物理连接先完成原强读回再固定关闭并逐字段读回() throws Exception {
        for (String value : List.of("0","OFF","FALSE","off","false")) {
            Physical p=physical("MySQL","8.0.11-TiDB-v8.5.8",value,false,false);
            AuthorityDataSource.initialize(p.c(),true,List.of(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL));
            var order=inOrder(p.statement());
            order.verify(p.statement()).execute(AuthorityDataSource.STRONG_SESSION_SQL);
            order.verify(p.statement()).executeQuery(startsWith("SELECT @@SESSION.tidb_snapshot"));
            order.verify(p.statement()).execute(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL);
            order.verify(p.statement()).executeQuery(FAIR_READ);
            verify(p.fair()).getString(1);verify(p.fair(),times(2)).next();
        }
    }

    @Test void guild物理连接读回不符_空_null_多行均拒绝() throws Exception {
        for (String value : new String[] {"1","ON","TRUE","unknown",null}) {
            Physical p=physical("MySQL","8.0.11-TiDB-v8.5.8",value,false,false);
            assertThrows(SQLException.class,()->AuthorityDataSource.initialize(p.c(),true,List.of(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL)));
        }
        Physical missing=physical("MySQL","8.0.11-TiDB-v8.5.8","OFF",true,false);
        Physical duplicate=physical("MySQL","8.0.11-TiDB-v8.5.8","OFF",false,true);
        assertThrows(SQLException.class,()->AuthorityDataSource.initialize(missing.c(),true,List.of(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL)));
        assertThrows(SQLException.class,()->AuthorityDataSource.initialize(duplicate.c(),true,List.of(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL)));
    }

    @Test void 非guild和非tidb不发送公平锁变量_业务连接仍拒绝任意SET() throws Exception {
        Physical normal=physical("MySQL","8.0.11-TiDB-v8.5.8","ON",false,false);
        AuthorityDataSource.initialize(normal.c(),true,List.of());
        verify(normal.statement(),never()).execute(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL);
        verify(normal.statement(),never()).executeQuery(FAIR_READ);
        for (Physical other : List.of(physical("MySQL","8.0.39","ON",false,false),physical("H2","unused","ON",false,false))) {
            AuthorityDataSource.initialize(other.c(),false,List.of(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL));
            verify(other.statement(),never()).execute(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL);
            verify(other.statement(),never()).executeQuery(FAIR_READ);
        }
        assertThrows(SQLException.class,()->AuthorityDataSource.checkedInitialization(List.of("SET SESSION tidb_pessimistic_txn_fair_locking=ON")));
        Connection raw=mock(Connection.class);when(raw.getAutoCommit()).thenReturn(true);
        Connection managed=AuthorityConnection.wrap(raw,mock(WriterAuthority.class));
        for (String mode : List.of("ON","OFF"))
            assertThrows(SQLException.class,()->managed.prepareStatement("SET SESSION tidb_pessimistic_txn_fair_locking="+mode));
        verify(raw,never()).prepareStatement(anyString());
    }
}
