package com.game.discovery.sql;

import static org.junit.jupiter.api.Assertions.*;
import com.alibaba.druid.pool.*;
import com.alibaba.druid.proxy.jdbc.ConnectionProxy;
import com.game.common.sql.WriterAuthority;
import com.game.common.sql.testing.AuthoritySqlFixture;
import java.sql.*;
import org.junit.jupiter.api.Test;

class AuthorityPoolTiDbTest {
    @Test void druidHolderAndDeadlineFilterKeepTheirRealTypes()throws Exception{
        String root=System.getProperty("game.it.mysql");if(root==null||root.isBlank())throw new IllegalStateException("缺少真实fixture配置：game.it.mysql");
        var options=AuthoritySqlFixture.Options.fromEnvironment(root,System.getProperty("game.it.mysql.user","root"),System.getenv().getOrDefault("GAME_MYSQL_PASSWORD",""),"jpooldrain",false,java.util.List.of(1));
        // 本例只需SELECT 1与slot保护；正式控制/player DDL和注册由fixture创建。
        try(var fixture=AuthoritySqlFixture.prepareWithPlayerSchema(options,(schema,actualSchemaPrincipal)->{});var pool=new DruidDataSource()){
            var boot=fixture.writerBoot();
            pool.setUrl(fixture.jdbcUrl(""));pool.setUsername(fixture.normalUser());pool.setPassword(fixture.normalPassword());pool.setMaxActive(2);pool.setMaxWait(3000);
            AuthorityDataSourcePolicy.protectDruid(pool,boot,true);
            assertTrue(pool.getConnectionInitSqls().isEmpty());
            try(var connection=pool.getConnection()){
                assertInstanceOf(DruidPooledConnection.class,connection);assertInstanceOf(ConnectionProxy.class,connection.getConnectionHolder().getConnection());
                connection.setAutoCommit(false);
                try(var budget=StrongTiDbConnectionPolicy.openStatementDeadline(connection,()->2000L,System::nanoTime);
                    var statement=connection.prepareStatement("SELECT 1");var rows=statement.executeQuery()){
                    assertTrue(rows.next());assertEquals(1,rows.getInt(1));assertTrue(budget.tiDb());
                }
                connection.commit();assertThrows(SQLException.class,()->connection.prepareStatement("DELETE FROM writer_slot"));
            }
            boot.seal();assertTrue(boot.isSealed());
            assertThrows(SQLException.class,()->{try(var connection=pool.getConnection();var statement=connection.createStatement()){statement.executeQuery("SELECT 1");}});
        }
    }
    @Test void productionPhysicalPolicyRejectsMissingGrantBeforePoolInitialization()throws Exception{
        var definitions=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var definition=new org.springframework.beans.factory.support.RootBeanDefinition(WriterAuthority.class);
        definition.setInstanceSupplier(()->{try{return WriterAuthority.consume(java.util.Map.of());}catch(java.io.IOException missing){throw new IllegalStateException("缺少启动原件",missing);}});definitions.registerBeanDefinition("normalWriterBoot",definition);
        var policy=new AuthorityDataSourcePolicy.Installer(new org.springframework.mock.env.MockEnvironment(),definitions.getBeanProvider(WriterAuthority.class));
        try(var pool=new DruidDataSource()){
            pool.setUrl("jdbc:h2:mem:missing_boot_must_not_connect");var failure=assertThrows(org.springframework.beans.BeansException.class,()->policy.postProcessBeforeInitialization(pool,"normalPool"));Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();assertInstanceOf(java.io.IOException.class,cause);assertFalse(pool.isInited());assertNull(pool.getDriver());
        }finally{definitions.destroySingletons();}
    }
}
