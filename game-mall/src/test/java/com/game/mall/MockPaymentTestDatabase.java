package com.game.mall;

import com.game.common.GameNamespace;
import com.game.common.RunMode;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.testing.AuthoritySqlFixture;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** 每例独占owner；显式game.it.mysql才进入真库，默认H2的本地测试边界不变。 */
final class MockPaymentTestDatabase implements AutoCloseable {
    final DataSource source;
    final JdbcTemplate jdbc;
    final OwnerSchemaOperations schemaJdbc;
    final DataSourceTransactionManager manager;
    final MallProperties props;
    final MallOrderStore store;
    private final AuthoritySqlFixture owned;
    private boolean closed;
    private Throwable actorUnknown;
    private final Set<java.util.concurrent.ExecutorService> actors = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    synchronized void trackActors(java.util.concurrent.ExecutorService executor) throws SQLException {
        if (closed || actorUnknown != null) throw new SQLException("商城actor夹具已关闭或结局未知");
        actors.add(executor);
        if (owned != null) owned.trackNormalActors(executor);
    }
    synchronized void retainUnknownActors(Throwable failure) {
        if (actorUnknown == null) actorUnknown = failure;
        if (owned != null) owned.retainUnknownActorOutcome(failure);
    }
    static boolean unknownActorFailure(Throwable failure) {
        return AuthoritySqlFixture.isUnknownFixtureOutcome(failure);
    }
    static Throwable combineFailure(Throwable primary, Throwable cleanup) {
        if (primary == null) return cleanup;
        if (!containsFailure(primary, cleanup) && !containsFailure(cleanup, primary)) primary.addSuppressed(cleanup);
        return primary;
    }
    /** 与fixture既有首因合并同规则：独立错误才挂suppressed，拒绝重复与因果环。 */
    private static boolean containsFailure(Throwable root, Throwable wanted) {
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        var pending = new java.util.ArrayDeque<Throwable>(); pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (current == wanted) return true;
            if (!seen.add(current)) continue;
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) pending.addLast(suppressed);
            if (current instanceof SQLException sql && sql.getNextException() != null) pending.addLast(sql.getNextException());
        }
        return false;
    }
    static void raiseFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
    }

    MockPaymentTestDatabase() { this("mallmock", null); }

    MockPaymentTestDatabase(String prefix, MallProperties configuredProperties) {
        boolean real = System.getProperty("game.it.mysql") != null;
        props = configuredProperties == null
                ? new MallProperties(real, MallTestDatabase.GATEWAY_KEY, MallTestDatabase.PAYMENT_KEY,
                    null, null, null, null, null, true, Set.of("test"))
                : configuredProperties;
        AuthoritySqlFixture created = null;
        DataSource ready = null;
        try {
            if (!real) {
                String url = "jdbc:h2:mem:mallmock_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=4000";
                var h2 = new JdbcDataSource(); h2.setURL(url); h2.setUser("sa"); ready = h2;
                install(ready, "db/migration/V1__official_sku.sql");
            } else {
                created = createOwned(ownedOptions(prefix), props, null);
                ready = normalPool(created, props, "test");
            }
            owned = created;
            source = ready;
            jdbc = new JdbcTemplate(source);
            // 测试seed/结构损坏/显式迁移只走owner；业务Store和事务管理器始终使用同一个NORMAL池。
            schemaJdbc = new OwnerSchemaOperations();
            manager = new DataSourceTransactionManager(source);
            new MallSchemaVerifier(source, props).verify();
            store = new MallOrderStore(jdbc, manager,
                    Clock.fixed(Instant.ofEpochMilli(1_760_000_000_000L), ZoneOffset.UTC), props, RunMode.TEST);
        } catch (Exception | Error failure) {
            if (ready instanceof HikariDataSource pool) suppressPoolClose(failure, pool, created);
            else if (ready != null) {
                try { new JdbcTemplate(ready).execute("SHUTDOWN"); }
                catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            if (created != null) suppressClose(failure, created);
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("mock隔离测试库初始化失败", failure);
        }
    }

    static AuthoritySqlFixture.Options ownedOptions(String prefix) throws SQLException {
        String root = System.getProperty("game.it.authority.jdbc", System.getProperty("game.it.mysql"));
        if (root == null || !root.matches("jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/?"))
            throw new SQLException("商城owned测试要求批准的数值loopback无库名入口：game.it.authority.jdbc");
        String selected = System.getProperty("game.it.mysql");
        if (selected != null && !selected.replaceAll("/$", "").equals(root.replaceAll("/$", "")))
            throw new SQLException("商城真库选择入口必须与已批准的authority物理入口相同");
        String port = System.getProperty("game.it.tidb.port");
        if (port != null && !root.replaceAll("/$", "").endsWith(":" + Integer.parseInt(port)))
            throw new SQLException("game.it.tidb.port与已批准的authority入口不一致");
        return AuthoritySqlFixture.Options.fromEnvironment(root,
                System.getProperty("game.it.mysql.user", System.getenv().getOrDefault("GAME_MYSQL_USER", "root")),
                System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""), prefix, false, List.of(1));
    }

    static AuthoritySqlFixture createOwned(AuthoritySqlFixture.Options options, MallProperties properties,
                                          GameNamespace namespace) throws Exception {
        var lease = namespace == null ? AuthoritySqlFixture.beginUnpublishedWithPlayerBaseSchema(options)
                : AuthoritySqlFixture.beginUnpublishedWithPlayerBaseSchema(options, namespace);
        try {
            lease.withSchemaChange((schema, principal, database) -> {
                installSchema(schema, "db/migration/V1__official_sku.sql");
                return null;
            }, (schema, principal, database) -> {
                verifyOwnedSchema(schema, properties);
                return null;
            });
            return lease.publish();
        } catch (Exception | Error failure) {
            suppressClose(failure, lease);
            throw failure;
        }
    }

    static HikariDataSource normalPool(AuthoritySqlFixture fixture, MallProperties properties, String runMode)
            throws SQLException {
        var values = new DataSourceProperties(); values.setUrl(fixture.jdbcUrl(""));
        values.setUsername(fixture.normalUser()); values.setPassword(fixture.normalPassword());
        var pool = (HikariDataSource) new MallDatabaseConfiguration().dataSource(values, properties, runMode);
        try { protectPool(pool, fixture); return pool; }
        catch (SQLException | RuntimeException | Error failure) { suppressPoolClose(failure, pool, fixture); throw failure; }
    }

    /** 非Spring测试在首次借连接前装同一正式physical adapter；不绕过或替代生产NORMAL包装。 */
    static void protectPool(HikariDataSource pool, AuthoritySqlFixture fixture) throws SQLException {
        if (pool.getHikariPoolMXBean() != null || pool.getDataSource() != null)
            throw new SQLException("商城测试池必须在初始化前安装同owner physical adapter");
        var credentials = new Properties(); credentials.putAll(pool.getDataSourceProperties());
        credentials.setProperty("user", pool.getUsername()); credentials.setProperty("password", pool.getPassword());
        String initialization = pool.getConnectionInitSql();
        pool.setDataSource(new AuthorityDataSource(pool.getJdbcUrl(), credentials, fixture.writerBoot(), true,
                initialization == null ? List.of() : List.of(initialization)));
        pool.setConnectionInitSql(null);
    }

    private static void installSchema(Connection schema, String resource) throws SQLException {
        try { install(new SingleConnectionDataSource(schema, true), resource); }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql)
                    throw new SQLException("正式Mall DDL执行失败", sql.getSQLState(), sql.getErrorCode(), failure);
            }
            throw failure;
        }
    }

    private static void verifyOwnedSchema(Connection schema, MallProperties properties) throws SQLException {
        try { new MallSchemaVerifier(new SingleConnectionDataSource(schema, true), properties).verify(); }
        catch (IllegalStateException incompatible) {
            // 已知完整契约不匹配保留原cause和未完成check；不把普通seed成功当作完成。
            throw new SQLException("正式Mall schema完整验证未通过", "22000", incompatible);
        }
    }
    private static void install(DataSource installer, String resource) {
        new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(installer);
    }

    /** 显式owner操作，不是NORMAL JdbcTemplate，也不按SQL内容暗切DataSource。 */
    final class OwnerSchemaOperations {
        void execute(String sql) {
            ownerSchemaWork(connection -> { ownerJdbc(connection).execute(sql); return null; });
        }
        int update(String sql, Object... parameters) {
            return ownerSchemaWork(connection -> ownerJdbc(connection).update(sql, parameters));
        }
    }
    private JdbcTemplate ownerJdbc(Connection connection) {
        var template = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        if (owned != null) template.setQueryTimeout(5);
        return template;
    }
    private <T>T ownerSchemaWork(AuthoritySqlFixture.OwnerSchemaWork<T> work) {
        try {
            if (owned != null) return owned.withOwnerSchema(work);
            try (Connection connection = source.getConnection()) { return work.run(connection); }
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("商城owner工作失败，保留原错误链", failure); }
    }
    void migrate(String resource) {
        // 同一owner monitor覆盖安装和Connection.close；UNKNOWN在精确DROP之前写回fixture。
        ownerSchemaWork(connection -> {
            install(new SingleConnectionDataSource(connection, true), resource);
            return null;
        });
    }
    void sku(long id, int stock, long price) {
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES(?,?,?,?,'CNY',?,0,1)", id, id, "mock-sku-" + id, price, stock);
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(?,10,1,'玄铁')", id);
    }
    long value(String table, String column) { return jdbc.queryForObject("SELECT COALESCE(SUM(" + column + "),0) FROM " + table, Long.class); }
    int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    /** 池关闭未确认先单调撤销owner；后续owned.close只能保留原件，不能继续DROP。 */
    private static void suppressPoolClose(Throwable failure, HikariDataSource pool, AuthoritySqlFixture fixture) {
        try { pool.close(); }
        catch (RuntimeException | Error cleanup) {
            if (fixture != null) fixture.retainUnknownActorOutcome(cleanup);
            combineFailure(failure, cleanup);
        }
    }
    static void suppressClose(Throwable failure, AutoCloseable resource) {
        try { resource.close(); }
        catch (Exception | Error cleanup) { combineFailure(failure, cleanup); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        boolean actorsEnded = true;
        for (var executor : actors) if (!executor.isTerminated()) {
            actorsEnded = false; retainUnknownActors(new IllegalStateException("商城actor未确认终结"));
        }
        if (!actorsEnded) throw new IllegalStateException("商城actor未终结，保留连接池与owned原件", actorUnknown);
        if (owned != null) {
            try { owned.requireNormalActorsTerminated(); }
            catch (SQLException failure) { retainUnknownActors(failure); throw new IllegalStateException("商城actor关闭前未终结", failure); }
        }
        closed = true;
        if (owned == null) {
            if (actorUnknown != null) throw new IllegalStateException("商城H2 actor结局未知，拒绝SHUTDOWN", actorUnknown);
            jdbc.execute("SHUTDOWN"); return;
        }
        // 已确认actor结束才关本地池；UNKNOWN仍由fixture单调保留，绝不据此执行DROP。
        Throwable failure = actorUnknown == null ? null : new IllegalStateException("商城actor结局未知，保留owned原件", actorUnknown);
        try { ((HikariDataSource) source).close(); } catch (RuntimeException | Error cleanup) { retainUnknownActors(cleanup); failure = combineFailure(failure, cleanup); }
        try { owned.close(); } catch (Exception | Error cleanup) { failure = combineFailure(failure, cleanup); }
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("商城owned夹具关闭未确认", failure);
    }
}
