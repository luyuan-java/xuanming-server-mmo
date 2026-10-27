package com.game.trade.store;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;
import com.game.common.deadline.Deadline;
import com.game.trade.rules.TradeLimits;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 真 MySQL 测试的公共夹具（缺省跳过：{@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306}，账号取 {@code -Dgame.it.mysql.user}（缺省 root），
 * 口令取环境变量 GAME_MYSQL_PASSWORD；先例 game-guild GuildMysqlFixture、game-friend JdbcFriendStoreMysqlTest）。
 *
 * <p>每个测试类建一个一次性库 {@code game_trade_it_<随机>}（两张表经 {@link TradeTables#sync} 建，与生产同一条路径），结束时删掉；绝不碰 game。
 * 连接串与生产同口径：会话级 READ COMMITTED、{@code STRICT_TRANS_TABLES}、{@code innodb_lock_wait_timeout=2}（Q7）、{@code useAffectedRows=true}
 * （不这样开池就等于在验一个与线上不同的数据库会话）。夹具行直接用 SQL 造或走 {@link JdbcListingStore#insertListing}（基线集成测试同样直接调
 * InsertListing，listing_repo_integration_test.go:136-139）。
 */
final class TradeMysqlFixture implements AutoCloseable {

    static final String BASE_URL = System.getProperty("game.it.mysql");
    static final String USER = System.getProperty("game.it.mysql.user", "root");
    static final String PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
    static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useAffectedRows=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout="
            + TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS;

    final String database;
    final DruidDataSource dataSource;
    private final AuthoritySqlFixture authority;
    final AtomicInteger favoriteRetries = new AtomicInteger();

    private TradeMysqlFixture(String database, DruidDataSource dataSource,AuthoritySqlFixture authority) {
        this.authority=authority;
        this.database = database;
        this.dataSource = dataSource;
    }

    static TradeMysqlFixture create() throws SQLException {
        try{
            var options=AuthoritySqlFixture.Options.fromEnvironment(BASE_URL,USER,PASSWORD,"game_trade",!Boolean.getBoolean("game.it.require-tidb"),List.of(1));
            var authority=AuthoritySqlFixture.prepare(options,(connection,principal)->{
                TradeTables.registry().initializeFresh(connection,principal);
                try(var sql=connection.createStatement()){sql.execute("CREATE TABLE player(player_id BIGINT UNSIGNED PRIMARY KEY,zone_id INT UNSIGNED NOT NULL DEFAULT 1)");}
            });
            DruidDataSource ds=new DruidDataSource();
            try{ds.setUrl(authority.jdbcUrl(PARAMS));ds.setUsername(authority.normalUser());ds.setPassword(authority.normalPassword());ds.setMaxActive(64);ds.setMaxWait(10_000);ds.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                AuthorityDataSourcePolicy.protectDruid(ds,authority.writerBoot(),!options.allowMySql());
                TradeTables.sync(ds,java.time.Duration.ofMinutes(1));return new TradeMysqlFixture(authority.database(),ds,authority);
            }catch(Exception failure){ds.close();try{authority.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }catch(SQLException failure){throw failure;}catch(Exception failure){throw new SQLException("隔离交易SQL夹具初始化失败",failure);}
    }

    @Override
    public void close() throws SQLException {
        dataSource.close();authority.close();
    }

    /** 被测存储：取连接按次限等（Druid {@code getConnection(long)}，同生产装配），查询超时上限 3 s（同 friend 的 query-timeout）。 */
    JdbcListingStore store() {
        return new JdbcListingStore(dataSource::getConnection, 3, favoriteRetries::incrementAndGet);
    }

    /** 每次调用一个宽裕的请求预算（关心的是 SQL 行为，不是超时；单次上限照样是 2000 ms）。 */
    static Deadline d() {
        return Deadline.after(30_000);
    }

    void truncate() throws SQLException {
        for (String table : TradeTables.NAMES) {
            try(var connection=authority.schemaConnection();var sql=connection.createStatement()){sql.execute("TRUNCATE TABLE `"+table+"`");}
        }
    }

    /** 新开一条独立连接（锁模式编排用：每个参与者一条、自己管事务），会话参数同连接池，但锁等待放宽到 {@code lockWaitSeconds}。 */
    Connection connect(int lockWaitSeconds) throws SQLException {
        String params = PARAMS.replace("innodb_lock_wait_timeout=" + TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS,
                "innodb_lock_wait_timeout=" + lockWaitSeconds);
        return authority.normalDataSource(params).getConnection();
    }

    int exec(String sql, Object... args) throws SQLException {
        try (Connection c = authority.schemaConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    long count(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 读一列 bigint unsigned（无行返回 null）。 */
    Long queryU64(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? JdbcListingStore.readU64(rs, 1) : null;
            }
        }
    }

    /** 读一列文本。 */
    List<String> queryStrings(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new java.util.ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        }
    }

    /** Long 按 uint64 绑定（同 ListingSql.u64），其余原样。 */
    static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            ps.setObject(i + 1, arg instanceof Long l ? ListingSql.u64(l) : arg);
        }
    }
}
