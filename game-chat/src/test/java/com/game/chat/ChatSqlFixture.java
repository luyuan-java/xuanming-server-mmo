package com.game.chat;

import static org.mockito.Mockito.*;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;
import com.game.chat.store.ChatRoleAuthority;
import com.game.chat.store.JdbcChatStore;
import com.game.common.player.RouteAuthorityInitializer;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** H2仅验证接口/事务；原生专项必须显式owned数值loopback、真实TiDB强会话与随机本夹具库。 */
final class ChatSqlFixture implements AutoCloseable {
    final DataSource source;
    final JdbcTemplate sql;
    final ChatRoleAuthority authority;
    final JdbcChatStore store;
    final AtomicLong clock = new AtomicLong(1_000_000);
    private final DataSource physical;
    private final AuthoritySqlFixture sqlFixture;
    private final String database;
    private final String rootUrl;
    private final String user;
    private final String password;

    ChatSqlFixture(ChatProperties rules) throws Exception { this(rules, null); }

    ChatSqlFixture(ChatProperties rules, String endpoint) throws Exception {
        if (endpoint == null) {
            sqlFixture=null;
            var h2 = new JdbcDataSource();
            h2.setURL("jdbc:h2:mem:chat_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            physical = h2;
            database = rootUrl = user = password = null;
            source = new DelegatingDataSource(h2) {
                @Override public Connection getConnection() throws java.sql.SQLException {
                    Connection connection = super.getConnection();
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && args != null && args.length > 0
                                    && "SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000.000".equals(args[0])) {
                                // 固定数据库时钟标量接缝；其它SQL/锁/回滚均由真实H2执行，测试不取墙钟也不sleep跨秒。
                                PreparedStatement statement = mock(PreparedStatement.class);
                                when(statement.executeQuery()).thenAnswer(call -> {
                                    ResultSet rows = mock(ResultSet.class);
                                    when(rows.next()).thenReturn(true, false);
                                    when(rows.getBigDecimal(1)).thenReturn(BigDecimal.valueOf(clock.get()));
                                    return rows;
                                });
                                return statement;
                            }
                            try { return method.invoke(connection, args); }
                            catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                        });
                }
            };
        } else {
            if (!endpoint.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
                    || Integer.parseInt(endpoint.substring(endpoint.indexOf(':') + 1)) > 65535) {
                throw new IllegalArgumentException("聊天原生专项只接显式owned数值loopback非零端口");
            }
            rootUrl = "jdbc:mysql://" + endpoint + "/";
            user = System.getProperty("game.it.mysql.user", "root");
            password = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
            var options=AuthoritySqlFixture.Options.fromEnvironment(rootUrl,user,password,"game_chat",false,List.of(1));
            sqlFixture=AuthoritySqlFixture.prepare(options,(connection,principal)->{
                try(var statement=connection.createStatement()){statement.execute("CREATE TABLE player(player_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,account VARCHAR(191) NOT NULL,owner_revision BIGINT NOT NULL,trade_listing_id BIGINT NOT NULL,zone_id INT NOT NULL)");}
                new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/chat-authority.sql")).populate(connection);
            });
            database=sqlFixture.database();var pool=new DruidDataSource();pool.setUrl(sqlFixture.jdbcUrl(parameters()));pool.setUsername(sqlFixture.normalUser());pool.setPassword(sqlFixture.normalPassword());pool.setMaxActive(8);pool.setMaxWait(1000);
            try { AuthorityDataSourcePolicy.protectDruid(pool,sqlFixture.writerBoot(),true); }
            catch (Exception failure) {
                pool.close();
                try { sqlFixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            physical = source = pool;
        }
        sql = new JdbcTemplate(sqlFixture==null?source:sqlFixture.schemaDataSource());
        ChatRoleAuthority created = null;
        try {
        if(endpoint==null){
        sql.execute("CREATE TABLE player(player_id " + (endpoint == null ? "DECIMAL(20,0)" : "BIGINT UNSIGNED")
                + " NOT NULL PRIMARY KEY,account VARCHAR(191) NOT NULL,owner_revision BIGINT NOT NULL,"
                + "trade_listing_id BIGINT NOT NULL,zone_id INT NOT NULL)");
        install("db/game-route-authority-schema.sql", false);
        install("db/chat-authority.sql", endpoint == null);
        }
        try (var connection = source.getConnection()) {
            if(endpoint==null){String principal;try(var identity=connection.createStatement();var rows=identity.executeQuery("SELECT CURRENT_USER()")){if(!rows.next())throw new java.sql.SQLException("缺H2 schema主体");principal=rows.getString(1);}
                RouteAuthorityInitializer.initialize(connection,UUID.randomUUID().toString(),List.of(1),principal);
            }
            ChatSqlConfiguration.verifySchema(connection);
            if (endpoint != null) StrongTiDbConnectionPolicy.verify(connection, true);
        }
        created = new ChatRoleAuthority(source);
        authority = created;
        store = new JdbcChatStore(source, authority, rules);
        store.initializePolicy();
        } catch (Exception failure) {
            if (created != null) created.close();
            if (physical instanceof DruidDataSource pool) pool.close();
            try { cleanupDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void player(long id, String account, long revision) {
        sql.update("INSERT INTO player VALUES(?,?,?,0,1)", com.game.discovery.sql.AuthorityJdbc.u64(id), account, revision);
    }

    private void install(String resource, boolean h2Unsigned) throws Exception {
        String text;
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("缺正式DDL资源：" + resource);
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (h2Unsigned) text = text.replace("BIGINT UNSIGNED", "DECIMAL(20,0)");
        for (String statement : text.split(";")) if (!statement.isBlank()) sql.execute(statement);
    }

    private static String parameters() {
        return "?useSSL=false&characterEncoding=utf8&connectTimeout=3000&socketTimeout=5000";
    }

    @Override public void close() throws Exception {
        authority.close();
        if (physical instanceof DruidDataSource pool) pool.close();
        cleanupDatabase();
    }

    private void cleanupDatabase() throws Exception {
        if (database == null) { sql.execute("DROP ALL OBJECTS"); return; }
        sqlFixture.close();
    }
}
