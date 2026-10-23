package com.game.mall;

import com.game.common.RunMode;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 每例自有随机库；显式game.it.mysql只接受本机无库名TiDB端点（默认4000），绝不借用已有共享表。 */
final class MockPaymentTestDatabase implements AutoCloseable {
    final DataSource source;
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager manager;
    final MallProperties props;
    final MallOrderStore store;
    private final String endpoint;
    private final String database;
    private final String jdbcUrl;

    MockPaymentTestDatabase() {
        endpoint = System.getProperty("game.it.mysql");
        props = new MallProperties(endpoint != null, MallTestDatabase.GATEWAY_KEY, MallTestDatabase.PAYMENT_KEY,
                null, null, null, null, null, true, Set.of("test"));
        if (endpoint == null) {
            database = null;
            jdbcUrl = "jdbc:h2:mem:mallmock_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000";
            var h2 = new JdbcDataSource(); h2.setURL(jdbcUrl); h2.setUser("sa"); source = h2;
        } else {
            int tidbPort = Integer.parseInt(System.getProperty("game.it.tidb.port", "4000"));
            if (tidbPort < 1 || tidbPort > 65535) {
                throw new IllegalArgumentException("game.it.tidb.port必须在1..65535");
            }
            if (!endpoint.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):" + tidbPort + "/?")) {
                throw new IllegalArgumentException("mock真TiDB测试仅接受指定loopback端口且不得指定已有数据库");
            }
            database = "mallmock" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            jdbcUrl = endpoint.replaceAll("/$", "") + "/" + database;
            ownedDDL("CREATE DATABASE");
            var values = new DataSourceProperties(); values.setUrl(jdbcUrl);
            values.setUsername(credentials().getProperty("user")); values.setPassword(credentials().getProperty("password"));
            try { source = new MallDatabaseConfiguration().dataSource(values, props, "test"); }
            catch (RuntimeException failure) {
                try { ownedDDL("DROP DATABASE"); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        try {
            migrate("db/migration/V1__official_sku.sql");
            new MallSchemaVerifier(source, props).verify();
            store = new MallOrderStore(jdbc, manager, Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneOffset.UTC), props, RunMode.TEST);
        } catch (Exception failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("mock随机测试库初始化失败", failure);
        }
    }

    void migrate(String resource) {
        DataSource installer = source;
        if (database != null) {
            var ddl = new DriverManagerDataSource(jdbcUrl); ddl.setConnectionProperties(credentials()); installer = ddl;
        }
        // 与生产6秒请求池分开，DDL使用独立30秒安装连接。
        new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(installer);
    }
    void sku(long id, int stock, long price) {
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES(?,?,?,?,'CNY',?,0,1)", id, id, "mock-sku-" + id, price, stock);
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(?,10,1,'玄铁')", id);
    }
    long value(String table, String column) { return jdbc.queryForObject("SELECT COALESCE(SUM(" + column + "),0) FROM " + table, Long.class); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    @Override public void close() {
        if (database == null) { jdbc.execute("SHUTDOWN"); return; }
        try { ((HikariDataSource) source).close(); } finally { ownedDDL("DROP DATABASE"); }
    }
    private static Properties credentials() {
        var values = new Properties();
        values.setProperty("user", System.getenv().getOrDefault("GAME_MYSQL_USER", "root"));
        values.setProperty("password", System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""));
        values.setProperty("connectTimeout", "3000"); values.setProperty("socketTimeout", "30000"); values.setProperty("useSSL", "false");
        return values;
    }
    private void ownedDDL(String command) {
        if (database == null || !database.matches("mallmock[0-9a-f]{16}")) { throw new IllegalStateException("拒绝操作非本例随机库"); }
        try (Connection connection = DriverManager.getConnection(endpoint.replaceAll("/$", "") + "/", credentials()); var statement = connection.createStatement()) {
            statement.setQueryTimeout(30); statement.execute(command + " `" + database + "`");
        } catch (SQLException failure) { throw new IllegalStateException("mock专属随机库DDL失败: " + database, failure); }
    }
}
