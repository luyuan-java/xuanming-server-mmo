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
        String root=System.getProperty("game.it.authority.jdbc",System.getProperty("game.it.mysql"));if(root==null||root.isBlank())throw new IllegalStateException("缺少真实fixture配置：game.it.authority.jdbc（兼容game.it.mysql）");
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
    @Test void 显式H2开发分支不发MySQL版本查询且生产仍拒绝()throws Exception{
        var connection=org.mockito.Mockito.mock(Connection.class);
        var metadata=org.mockito.Mockito.mock(DatabaseMetaData.class);
        org.mockito.Mockito.when(connection.getMetaData()).thenReturn(metadata);
        org.mockito.Mockito.when(metadata.getDatabaseProductName()).thenReturn("H2");
        org.mockito.Mockito.doThrow(new SQLException("H2不支持MySQL版本探测","90022",90022)).when(connection).createStatement();
        StrongTiDbConnectionPolicy.verify(connection,false);
        org.mockito.Mockito.verify(connection,org.mockito.Mockito.never()).createStatement();
        var failure=assertThrows(SQLException.class,()->StrongTiDbConnectionPolicy.verify(connection,true));
        assertEquals("权威库要求TiDB",failure.getMessage());
        org.mockito.Mockito.verify(connection,org.mockito.Mockito.never()).createStatement();
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
    @Test void 维护标记NORMAL可持行锁但数据库拒绝全部写与DDL()throws Exception{
        String root=System.getProperty("game.it.authority.jdbc",System.getProperty("game.it.mysql"));if(root==null||root.isBlank())throw new IllegalStateException("缺少真实fixture配置：game.it.authority.jdbc（兼容game.it.mysql）");
        var options=AuthoritySqlFixture.Options.fromEnvironment(root,System.getProperty("game.it.mysql.user","root"),System.getenv().getOrDefault("GAME_MYSQL_PASSWORD",""),"jmarkeracl",false,java.util.List.of(1));
        try(var fixture=AuthoritySqlFixture.prepareWithPlayerSchema(options,(schema,principal)->{
            try(var statement=schema.createStatement()){
                statement.setQueryTimeout(5);
                statement.execute("CREATE TABLE maintenance_recovery_target(purpose VARBINARY(16) NOT NULL,zone_id INT UNSIGNED NOT NULL,schema_name VARBINARY(64) NOT NULL,target_nonce VARBINARY(32) NOT NULL,physical_addr VARBINARY(255) NOT NULL,PRIMARY KEY(purpose,zone_id,schema_name))");
            }
            try(var insert=schema.prepareStatement("INSERT INTO maintenance_recovery_target VALUES(?,?,?,?,?)")){
                insert.setQueryTimeout(5);insert.setBytes(1,ascii("admission"));insert.setInt(2,1);insert.setBytes(3,ascii(schema.getCatalog()));
                insert.setBytes(4,ascii("a".repeat(32)));insert.setBytes(5,ascii("maintenance-db.example:3306"));assertEquals(1,insert.executeUpdate());
            }
        });var pool=new DruidDataSource()){
            pool.setUrl(fixture.jdbcUrl("?connectTimeout=3000&socketTimeout=3000"));pool.setUsername(fixture.normalUser());pool.setPassword(fixture.normalPassword());pool.setMaxActive(2);pool.setMaxWait(3000);
            AuthorityDataSourcePolicy.protectDruid(pool,fixture.writerBoot(),true);assertMarkerOwnerState(fixture);
            // NORMAL持锁；只有这个新建的独立owner争锁会话把服务器锁等待收窄为1秒。
            try(var normal=pool.getConnection();var contender=fixture.schemaConnection()){
                try(var control=contender.createStatement()){
                    control.setQueryTimeout(5);control.execute("SET SESSION innodb_lock_wait_timeout=1");
                    try(var actual=control.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")){assertTrue(actual.next());assertEquals(1,actual.getInt(1));assertFalse(actual.next());}
                }
                long normalUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                normal.setAutoCommit(false);
                try{
                    try(var deadline=StrongTiDbConnectionPolicy.openStatementDeadline(normal,()->markerRemainingMillis(normalUntil),System::nanoTime);var query=normal.prepareStatement(markerLockSql())){
                        markerApplyBounds(normal,query,normalUntil);bindMarker(query,fixture.database());try(var rows=query.executeQuery()){assertLockedMarker(rows);}markerRemainingMillis(normalUntil);
                    }
                    contender.setAutoCommit(false);
                    try(var query=contender.prepareStatement(markerLockSql())){
                        markerApplyBounds(contender,query,normalUntil);bindMarker(query,fixture.database());
                        SQLException blocked=assertThrows(SQLException.class,()->{try(var rows=query.executeQuery()){assertLockedMarker(rows);}});
                        String blockedState=blocked.getSQLState();int blockedVendor=blocked.getErrorCode();
                        String blockedDiagnostic="服务器争锁只认1205/40001，实际SQLState="+blockedState+" vendor="+blockedVendor;
                        assertEquals(1205,blockedVendor,blockedDiagnostic);assertEquals("40001",blockedState,blockedDiagnostic);
                    }finally{contender.setNetworkTimeout(Runnable::run,(int)markerRemainingMillis(normalUntil));contender.rollback();}
                    markerRemainingMillis(normalUntil);normal.setNetworkTimeout(Runnable::run,(int)markerRemainingMillis(normalUntil));normal.rollback();markerRemainingMillis(normalUntil);
                    contender.setNetworkTimeout(Runnable::run,5000);
                    try(var query=contender.prepareStatement(markerLockSql())){
                        query.setQueryTimeout(5);bindMarker(query,fixture.database());try(var rows=query.executeQuery()){assertLockedMarker(rows);}
                    }
                }finally{try{normal.rollback();}finally{contender.rollback();}}
            }
            assertMarkerOwnerState(fixture);
            String key="purpose='admission' AND zone_id=1 AND schema_name='"+fixture.database()+"'";
            // 原行只有zone=1；zone=2是合法且不冲突的新主键，UPDATE也确实命中原行并改为不同值。
            assertMarkerDenied(fixture,"INSERT","INSERT INTO maintenance_recovery_target VALUES('admission',2,'"+fixture.database()+"','"+"b".repeat(32)+"','another-db.example:3306')",1142);
            assertMarkerDenied(fixture,"UPDATE","UPDATE maintenance_recovery_target SET physical_addr='another-db.example:3306' WHERE "+key,1142,1143);
            assertMarkerDenied(fixture,"DELETE","DELETE FROM maintenance_recovery_target WHERE "+key,1142);
            assertMarkerDenied(fixture,"CREATE","CREATE TABLE normal_acl_create_probe(id INT PRIMARY KEY)",1142,1044);
            assertMarkerDenied(fixture,"ALTER","ALTER TABLE maintenance_recovery_target ADD COLUMN normal_acl_alter_probe INT NULL",1142);
        }
    }

    private static byte[] ascii(String value){return value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);}
    private static String markerLockSql(){return "SELECT target_nonce,physical_addr FROM maintenance_recovery_target WHERE purpose=? AND zone_id=? AND schema_name=? FOR UPDATE";}
    private static void bindMarker(PreparedStatement query,String database)throws SQLException{query.setBytes(1,ascii("admission"));query.setInt(2,1);query.setBytes(3,ascii(database));}
    private static void assertLockedMarker(ResultSet rows)throws SQLException{
        assertTrue(rows.next());assertArrayEquals(ascii("a".repeat(32)),rows.getBytes(1));assertArrayEquals(ascii("maintenance-db.example:3306"),rows.getBytes(2));assertFalse(rows.next());
    }
    private static Connection markerNormalRaw(AuthoritySqlFixture fixture)throws Exception{
        Connection raw=null;
        try{
            // 直接用同一个实际NORMAL账号验证数据库ACL；不unwrap，也不让本地DDL解析器代替服务器拒权。
            raw=DriverManager.getConnection(fixture.jdbcUrl("?connectTimeout=3000&socketTimeout=3000"),fixture.normalUser(),fixture.normalPassword());
            raw.setNetworkTimeout(Runnable::run,3000);com.game.common.sql.AuthorityDataSource.initialize(raw,true,java.util.List.of());assertFalse(raw.isReadOnly());
            try(var query=raw.createStatement()){
                query.setQueryTimeout(3);try(var rows=query.executeQuery("SELECT CURRENT_USER(),DATABASE(),VERSION()")){
                    assertTrue(rows.next());assertTrue((fixture.normalUser()+"@%").equals(rows.getString(1)),"ACL探针必须使用已登记NORMAL主体");
                    assertTrue(fixture.database().equals(rows.getString(2)),"ACL探针必须仍在同一owner库");assertTrue(rows.getString(3).toLowerCase(java.util.Locale.ROOT).contains("tidb"));assertFalse(rows.next());
                }
            }
            return raw;
        }catch(SQLException failure){
            SQLException safe=new SQLException("NORMAL ACL探针连接或会话核验失败",failure.getSQLState(),failure.getErrorCode());
            if(raw!=null)try{raw.close();}catch(SQLException close){safe.addSuppressed(new SQLException("NORMAL ACL连接关闭失败",close.getSQLState(),close.getErrorCode()));}throw safe;
        }catch(RuntimeException|Error failure){if(raw!=null)try{raw.close();}catch(SQLException close){failure.addSuppressed(new SQLException("NORMAL ACL连接关闭失败",close.getSQLState(),close.getErrorCode()));}throw failure;}
    }
    private static void assertMarkerDenied(AuthoritySqlFixture fixture,String operation,String sql,int... codes)throws Exception{
        assertMarkerOwnerState(fixture);
        try(var raw=markerNormalRaw(fixture)){
            long rawUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            raw.setAutoCommit(false);
            try(var statement=raw.createStatement()){
                markerApplyBounds(raw,statement,rawUntil);
                try{statement.executeUpdate(sql);fail(operation+"不应被NORMAL执行成功");}
                catch(SQLException denied){
                    assertTrue(denied instanceof SQLSyntaxErrorException,operation+"必须是服务器授权错误类型");assertEquals("42000",denied.getSQLState(),operation);
                    int code=denied.getErrorCode();assertTrue(java.util.Arrays.stream(codes).anyMatch(expected->expected==code),operation+"必须是明确权限拒绝，实际code="+code);
                }
                markerRemainingMillis(rawUntil);
            }finally{raw.setNetworkTimeout(Runnable::run,(int)markerRemainingMillis(rawUntil));raw.rollback();}
            markerRemainingMillis(rawUntil);
        }
        assertMarkerOwnerState(fixture);
    }
    private static long markerRemainingMillis(long until){
        long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime());
        if(remaining<=0)throw new IllegalStateException("标记ACL探针原3秒事务预算已耗尽");return remaining;
    }
    private static void markerApplyBounds(Connection connection,Statement statement,long until)throws SQLException{
        long remaining=Math.min(3000L,markerRemainingMillis(until));
        // JDBC秒向上取整不扩网络预算；毫秒network始终收紧到同一个绝对截止的剩余量。
        connection.setNetworkTimeout(Runnable::run,(int)remaining);statement.setQueryTimeout((int)Math.max(1L,(remaining+999L)/1000L));
    }
    private static void assertMarkerOwnerState(AuthoritySqlFixture fixture)throws Exception{
        try(var schema=fixture.schemaConnection()){
            try(var query=schema.prepareStatement("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=? AND table_name='maintenance_recovery_target' ORDER BY ORDINAL_POSITION")){
                query.setQueryTimeout(5);query.setString(1,fixture.database());var columns=new java.util.ArrayList<String>();try(var rows=query.executeQuery()){while(rows.next())columns.add(rows.getString(1));}
                assertEquals(java.util.List.of("purpose","zone_id","schema_name","target_nonce","physical_addr"),columns);
            }
            try(var query=schema.createStatement()){
                query.setQueryTimeout(5);try(var rows=query.executeQuery("SELECT purpose,zone_id,schema_name,target_nonce,physical_addr FROM maintenance_recovery_target")){
                    assertTrue(rows.next());assertArrayEquals(ascii("admission"),rows.getBytes(1));assertEquals(1,rows.getInt(2));assertArrayEquals(ascii(fixture.database()),rows.getBytes(3));
                    assertArrayEquals(ascii("a".repeat(32)),rows.getBytes(4));assertArrayEquals(ascii("maintenance-db.example:3306"),rows.getBytes(5));assertFalse(rows.next());
                }
            }
            try(var query=schema.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='normal_acl_create_probe'")){
                query.setQueryTimeout(5);query.setString(1,fixture.database());try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));assertFalse(rows.next());}
            }
        }
    }

}
