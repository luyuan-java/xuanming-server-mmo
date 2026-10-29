package com.game.inbox.store;

import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;

import static org.junit.jupiter.api.Assertions.*;

import com.game.common.deadline.Deadline;
import com.game.common.player.RouteAuthorityInitializer;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.notify.Notifier;
import com.game.inbox.rules.InboxShards;
import com.game.proto.player_inbox.PlayerInboxDomain;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import com.alibaba.druid.pool.DruidDataSource;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** 默认H2；本轮真库显式inbox.it.tidb=127.0.0.1:24000，require=1时缺配置直接失败。 */
final class InboxRouteFenceTest {
    private static final PlayerInboxDomain DOMAIN=PlayerInboxDomain.forNumber(1);
    static void requireEndpoint(String endpoint) {
        if(endpoint==null||!endpoint.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
                ||Integer.parseInt(endpoint.substring(endpoint.indexOf(':')+1))>65535)
            throw new IllegalArgumentException("收件箱夹具只允许root显式授权的数值loopback TiDB端口");
    }
    @Test void 真库端口只取显式合法loopback_不默认或改映射() {
        assertDoesNotThrow(()->requireEndpoint("127.0.0.1:24000"));
        assertDoesNotThrow(()->requireEndpoint("127.0.0.1:65535"));
        for(String bad:List.of("", "127.0.0.1", "127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1:024000",
                "localhost:24000", "192.168.1.2:24000", "127.0.0.1:24000/game", "127.0.0.1:24000?x=y"))
            assertThrows(IllegalArgumentException.class,()->requireEndpoint(bad));
    }
    /** 仅H2状态机回归替身外部writer资格；不发grant，真实SQL/事务与Fence包装仍由生产代码执行。 */
    private static final class H2Authority implements AutoCloseable {
        private final JdbcDataSource schema;
        private final DataSource normal;
        private final com.game.common.sql.WriterAuthority authority;
        private final java.util.concurrent.atomic.AtomicBoolean sealed = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

        H2Authority(JdbcDataSource schema) throws java.sql.SQLException {
            this.schema = schema;
            final String physicalUrl;
            try (Connection raw = schema.getConnection()) {
                requireRawH2(raw);
                physicalUrl = raw.getMetaData().getURL();
            }
            authority = org.mockito.Mockito.mock(com.game.common.sql.WriterAuthority.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("verifyPhysical") || method.equals("begin")) {
                    Connection raw = invocation.getArgument(0);
                    requireRawH2(raw);
                    if (sealed.get() || !raw.getAutoCommit() || !physicalUrl.equals(raw.getMetaData().getURL())) {
                        throw new java.sql.SQLException("H2外部writer接口替身拒绝已seal、旧事务或另一内存库连接");
                    }
                    // AuthorityConnection.finish仍对真实H2执行commit/rollback并恢复autoCommit。
                    if (method.equals("begin")) raw.setAutoCommit(false);
                    return null;
                }
                if (method.equals("seal")) { sealed.set(true); return null; }
                if (method.equals("isSealed")) return sealed.get();
                return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
            var credentials = new java.util.Properties();
            credentials.setProperty("user", "sa"); credentials.setProperty("password", "");
            normal = new com.game.common.sql.AuthorityDataSource(schema.getURL(), credentials, authority, false, java.util.List.of());
        }

        private static void requireRawH2(Connection raw) throws java.sql.SQLException {
            if (org.mockito.Mockito.mockingDetails(raw).isMock() || raw.isClosed()
                    || !"H2".equals(raw.getMetaData().getDatabaseProductName())
                    || !"SA".equalsIgnoreCase(raw.getMetaData().getUserName())) {
                throw new java.sql.SQLException("H2外部writer接口替身仅接受真实SA物理连接");
            }
        }

        DataSource normalSource() { return normal; }
        DataSource schemaSource() { if(closed.get())throw new IllegalStateException("H2测试fixture已关闭");return schema; }
        void seal() { authority.seal(); }
        @Override public synchronized void close() throws java.sql.SQLException {
            if(closed.getAndSet(true))return;
            seal();
            try(Connection raw=schema.getConnection();var statement=raw.createStatement()){statement.execute("SHUTDOWN");}
        }
    }

    static final class Fixture implements AutoCloseable {
        final DataSource source;
        DruidDataSource nativePool;
        AuthoritySqlFixture fixture;
        private H2Authority h2Authority;
        final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
        final AuthorityJdbc jdbc;
        Fixture() throws Exception {
            String endpoint=System.getProperty("inbox.it.tidb", "");
            if ("1".equals(System.getProperty("inbox.it.require")) && endpoint.isEmpty())
                throw new IllegalStateException("真库门禁缺少inbox.it.tidb，不得降级成H2");
            if(endpoint.isEmpty()) {
                var memory=new JdbcDataSource();
                memory.setUser("sa");memory.setPassword("");
                memory.setURL("jdbc:h2:mem:inbox_route_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
                try { h2Authority=new H2Authority(memory);source=h2Authority.normalSource(); }
                catch(Exception|Error failure){try{if(h2Authority!=null)h2Authority.seal();try(var cleanupConnection=memory.getConnection();var cleanupStatement=cleanupConnection.createStatement()){cleanupStatement.execute("SHUTDOWN");}}catch(Exception|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
            } else {
                requireEndpoint(endpoint);
                String password=System.getenv().getOrDefault("GAME_TEST_MYSQL_PASSWORD", "");
                fixture=AuthoritySqlFixture.prepare(AuthoritySqlFixture.Options.fromEnvironment("jdbc:mysql://"+endpoint+"/",
                        System.getProperty("game.it.mysql.user", "root"),password,"inbox_route",false,List.of(7)),(connection,principal)->{
                    try(var statement=connection.createStatement()) { createDomain(connection,statement); }
                });
                nativePool=new DruidDataSource();
                try {
                    nativePool.setUrl(fixture.jdbcUrl("?connectTimeout=3000&socketTimeout=4000"));
                    nativePool.setUsername(fixture.normalUser());nativePool.setPassword(fixture.normalPassword());nativePool.setMaxActive(2);nativePool.setMaxWait(3000);
                    AuthorityDataSourcePolicy.protectDruid(nativePool,fixture.writerBoot(),true);
                    source=nativePool;
                } catch(Exception|Error failure) {
                    nativePool.close();try{fixture.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;
                }
            }
            try {
            if(nativePool==null) try(var connection=h2Authority.schemaSource().getConnection();var statement=connection.createStatement()) {
                try(var input=RouteAuthorityInitializer.class.getResourceAsStream("/db/game-route-authority-schema.sql")) {
                    assertNotNull(input);
                    String ddl=new String(input.readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?i)ENGINE\\s*=\\s*InnoDB[^;]*","");
                    for(String sql:ddl.split(";")) if(!sql.isBlank()) statement.execute(sql);
                }
                createDomain(connection,statement);
                String principal;try(var identity=statement.executeQuery("SELECT CURRENT_USER()")){assertTrue(identity.next());principal=identity.getString(1);}
                RouteAuthorityInitializer.initialize(connection,UUID.randomUUID().toString(),List.of(7),principal);
            }
            jdbc=new AuthorityJdbc(source);
            } catch(Exception | AssertionError failure) {
                try { close(); } catch(Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        Connection schemaConnection() throws SQLException {return fixture==null?h2Authority.schemaSource().getConnection():fixture.schemaConnection();}
        private static void createDomain(Connection connection,java.sql.Statement statement)throws SQLException {
                statement.execute("CREATE TABLE player(player_id BIGINT PRIMARY KEY,zone_id INT NOT NULL,trade_listing_id BIGINT NOT NULL,account VARCHAR(64) NOT NULL,owner_revision BIGINT NOT NULL,owner_epoch BIGINT NOT NULL,owner_released INT NOT NULL)");
                statement.execute("INSERT INTO player VALUES(1,7,0,'fixture-owner',1,1,0)");
                statement.execute("CREATE TABLE player_inbox_stream(shard INT,player_id BIGINT,domain INT,next_seq BIGINT,notify_due_ms BIGINT,PRIMARY KEY(shard,player_id,domain))");
                try(var insert=connection.prepareStatement("INSERT INTO player_inbox_stream VALUES(?,1,?,2,100)")) {
                    insert.setInt(1,InboxShards.rowShard(1));insert.setInt(2,DOMAIN.getNumber());insert.executeUpdate();
                }
        }
        void sql(String sql) throws SQLException {try(var connection=schemaConnection();var statement=connection.createStatement()){statement.execute(sql);}}
        long due() throws SQLException {try(var connection=source.getConnection();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT notify_due_ms FROM player_inbox_stream")){assertTrue(rows.next());return rows.getLong(1);}}
        boolean clear() throws SQLException {return InboxStore.clearNotifyDue(Deadline.after(3000),jdbc,1,DOMAIN,2);}
        @Override public void close() throws SQLException {
            metrics.close();
            if(nativePool==null) { h2Authority.close();return; }
            nativePool.close();
            if(fixture!=null)fixture.close();
        }
    }
    @Test void 开放原件允许CAS清提醒_过期CAS不能覆盖后续提醒() throws Exception {
        try(var f=new Fixture()) {
            assertTrue(f.clear());assertEquals(0,f.due());
            f.sql("UPDATE player_inbox_stream SET next_seq=3,notify_due_ms=200");
            assertFalse(f.clear());assertEquals(200,f.due());
        }
    }
    @Test void 全局冻结_任意围栏片_缺片_角色交易封存均拒绝写且保留原提醒() throws Exception {
        for(String change:List.of("UPDATE route_meta SET writable=0 WHERE id=1",
                "UPDATE zone_merge_fence SET run_id='00000000-0000-0000-0000-000000000001' WHERE zone_id=7 AND shard=63",
                "DELETE FROM zone_merge_fence WHERE zone_id=7 AND shard=63",
                "UPDATE player SET trade_listing_id=91 WHERE player_id=1")) {
            try(var f=new Fixture()) {
                f.sql(change);assertThrows(SQLException.class,f::clear);assertEquals(100,f.due());
                var maintenance=new InboxMaintenanceStore(f.jdbc,Notifier.NONE,new InboxMetrics(f.metrics),()->1000);
                assertThrows(SQLException.class,()->maintenance.deferNotifyDue(new InboxMaintenanceStore.NotifyDue(1,DOMAIN,2,100),200));
                assertEquals(100,f.due());
            }
        }
    }
    @Test void 玩家被删除后维护入口不能继续写孤儿流() throws Exception {
        try(var f=new Fixture()) {
            f.sql("DELETE FROM player WHERE player_id=1");
            assertThrows(SQLException.class,f::clear);assertEquals(100,f.due());
        }
    }
}
