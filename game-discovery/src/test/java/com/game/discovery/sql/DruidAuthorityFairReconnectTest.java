package com.game.discovery.sql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.WriterAuthority;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** 仅接口替身：新 physical 必须各自执行并读回，不能因旧连接成功放行重建连接。 */
class DruidAuthorityFairReconnectTest {
    private static final String FAIR_READ="SELECT @@SESSION.tidb_pessimistic_txn_fair_locking";
    private record Raw(Connection c, Statement statement, ResultSet fair) { }
    private static Raw raw(String mode) throws SQLException {
        Connection c=mock(Connection.class);DatabaseMetaData md=mock(DatabaseMetaData.class);Statement s=mock(Statement.class);
        ResultSet v=mock(ResultSet.class),strong=mock(ResultSet.class),fair=mock(ResultSet.class);
        when(c.getAutoCommit()).thenReturn(true);when(c.getMetaData()).thenReturn(md);when(md.getDatabaseProductName()).thenReturn("MySQL");
        when(c.createStatement()).thenReturn(s);when(s.executeQuery("SELECT VERSION()")).thenReturn(v);
        when(v.next()).thenReturn(true);when(v.getString(1)).thenReturn("8.0.11-TiDB-v8.5.8");
        when(s.executeQuery(startsWith("SELECT @@SESSION.tidb_snapshot"))).thenReturn(strong);
        when(strong.next()).thenReturn(true);when(strong.getString(1)).thenReturn("");when(strong.getLong(2)).thenReturn(0L);
        when(strong.getBoolean(3)).thenReturn(false);when(strong.getString(4)).thenReturn("strict");when(strong.getString(5)).thenReturn("leader");
        when(strong.getString(6)).thenReturn("pessimistic");when(strong.getString(7)).thenReturn("READ-COMMITTED");
        when(s.executeQuery(FAIR_READ)).thenReturn(fair);when(fair.next()).thenReturn(true,false);when(fair.getString(1)).thenReturn(mode);
        return new Raw(c,s,fair);
    }

    @Test void 新physical各自读回_重建连接为ON时关闭且不交付() throws Exception {
        Driver delegate=mock(Driver.class);WriterAuthority authority=mock(WriterAuthority.class);
        Raw first=raw("OFF"), second=raw("ON");String url="jdbc:mysql://127.0.0.1/test";
        when(delegate.acceptsURL(url)).thenReturn(true);when(delegate.connect(eq(url),any(Properties.class))).thenReturn(first.c(),second.c());
        var driver=new DruidAuthorityDriver(delegate,authority,true,List.of(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL));
        try(Connection managed=driver.connect(url,new Properties())) { assertNotNull(managed); }
        assertThrows(SQLException.class,()->driver.connect(url,new Properties()));
        verify(first.c()).close();verify(second.c()).close();
        for(Raw item:List.of(first,second)) {
            verify(item.statement()).execute(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL);
            verify(item.statement()).executeQuery(FAIR_READ);verify(item.fair()).getString(1);
        }
        verify(authority).verifyPhysical(first.c());verify(authority,never()).verifyPhysical(second.c());
    }

    @Test void factory仅给guild安装固定参数_非guild不继承() {
        var guild=new DruidDataSource();var other=new DruidDataSource();var dev=new DruidDataSource();
        try {
            StrongTiDbConnectionPolicy.forGuild("guild",true).postProcessBeforeInitialization(guild,"guild");
            new StrongTiDbConnectionPolicy("other",true).postProcessBeforeInitialization(other,"other");
            StrongTiDbConnectionPolicy.forGuild("guild-dev",false).postProcessBeforeInitialization(dev,"dev");
            assertTrue(guild.getConnectionInitSqls().contains(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL));
            assertFalse(other.getConnectionInitSqls().contains(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL));
            assertTrue(dev.getConnectionInitSqls().contains(AuthorityDataSource.GUILD_FAIR_LOCKING_OFF_SQL));
        } finally { guild.close();other.close();dev.close(); }
    }
}
