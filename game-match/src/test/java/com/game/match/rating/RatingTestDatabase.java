package com.game.match.rating;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.sql.testing.AuthoritySqlFixture;
import com.game.discovery.sql.AuthorityDataSourcePolicy;
import com.game.pbmysql.PbMysql;
import com.game.pbmysql.TableSchema;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * 评分两张表的测试库（各包的测试都可以用；不依赖 Spring）。两种后端：
 * <ul>
 *   <li>{@link #h2()}：H2 内存库的 MySQL 兼容模式。H2 不认 {@code GET_LOCK} 与 MySQL 的 information_schema，所以不走 pbmysql 的同步，
 *       改用 pbmysql 生成的<b>同一份 DDL</b>（去掉 H2 不认的表尾选项、无符号列换成放得下的类型）直接建，列名、主键、索引与生产一致。</li>
 *   <li>{@link #mysql()}：真 MySQL（{@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取环境变量 GAME_MYSQL_PASSWORD）：建一个一次性库
 *       {@code game_match_it_<随机>}，两张表走生产路径 {@link MatchRatingTables#sync}，{@link #close()} 时删库；绝不碰 {@code game}。
 *       连接串与生产同口径（READ COMMITTED、STRICT_TRANS_TABLES、{@code useAffectedRows=true}）。</li>
 * </ul>
 * 起整个 Spring 上下文的测试（没有 MySQL）：提供一个 H2 的 {@code DataSource} bean，再把 {@link #H2_SCHEMA} 声明成
 * {@link MatchRatingTables.SchemaSync} bean，装配就会用它建表而不是走 pbmysql：
 *
 * <pre>
 * &#64;Bean DataSource dataSource() { return RatingTestDatabase.h2DataSource("my-test"); }
 * &#64;Bean MatchRatingTables.SchemaSync ratingSchema() { return RatingTestDatabase.H2_SCHEMA; }
 * </pre>
 */
public final class RatingTestDatabase implements AutoCloseable {

    public static final String MYSQL_URL = System.getProperty("game.it.mysql");
    public static final String MYSQL_USER = System.getProperty("game.it.mysql.user", "root");
    public static final String MYSQL_PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");

    /** 在 H2 上建两张评分表（幂等：DDL 是 {@code CREATE TABLE IF NOT EXISTS}）。 */
    public static final MatchRatingTables.SchemaSync H2_SCHEMA = RatingTestDatabase::createH2Tables;

    public final DataSource dataSource;
    private final String mysqlDatabase;
    private final AuthoritySqlFixture authority;

    private RatingTestDatabase(DataSource dataSource,String mysqlDatabase){this(dataSource,mysqlDatabase,null);}
    private RatingTestDatabase(DataSource dataSource, String mysqlDatabase,AuthoritySqlFixture authority) {
        this.authority=authority;
        this.dataSource = dataSource;
        this.mysqlDatabase = mysqlDatabase;
    }

    /** 是否给了真 MySQL 的地址。 */
    public static boolean mysqlAvailable() {
        return MYSQL_URL != null && !MYSQL_URL.isBlank();
    }

    /** 只接root显式传入的数值loopback端口；不猜4000、不接受URL参数或远端。 */
    public static String tidbRoot(String endpoint) {
        if (endpoint == null || !endpoint.matches("127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
                || Integer.parseInt(endpoint.substring(endpoint.indexOf(':') + 1)) > 65535)
            throw new IllegalArgumentException("MATCH_IT_TIDB必须为已授权127.0.0.1数值端口");
        return "jdbc:mysql://" + endpoint + "/";
    }

    /** 创建随机库前核真实TiDB，避免在误指MySQL或其它端口上写入。 */
    public static void requireTiDb(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (var rows = statement.executeQuery("SELECT TIDB_VERSION()")) {
                if (!rows.next() || rows.getString(1) == null || rows.getString(1).isBlank())
                    throw new SQLException("匹配集成夹具需要真实TiDB");
            }
        }
    }

    /** 一个全新的 H2 内存库，两张表已建好。 */
    public static RatingTestDatabase h2() {
        DataSource ds = h2DataSource("game-match-rating-" + UUID.randomUUID());
        try {
            createH2Tables(ds);
        } catch (SQLException e) {
            try { ((H2Source) ds).close(); } catch (SQLException cleanup) { e.addSuppressed(cleanup); }
            throw new IllegalStateException("H2 建评分表失败", e);
        } catch (RuntimeException | Error failure) {
            try { ((H2Source) ds).close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return new RatingTestDatabase(ds, null);
    }

    /** 组件装配共用正规评分/路由夹具，并显式登记SQL公开角色名；账号不充当展示名。 */
    public static DataSource h2WithProfiles(Map<Long, String> names) {
        var db = h2();
        names.forEach((player, name) -> db.update("INSERT INTO player (player_id, name) VALUES (?, ?)",
                PbMysql.uint64(player), name));
        return db.dataSource;
    }

    /** H2 normal数据源；表由H2_SCHEMA经显式raw schema连接准备，不能在normal执行DDL。 */
    public static DataSource h2DataSource(String name) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setUser("sa"); h2.setPassword("");
        h2.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        try { return new H2Source(h2); }
        catch (SQLException failure) {
            try { shutdownH2(h2); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("H2 normal接口夹具创建失败", failure);
        } catch (RuntimeException | Error failure) {
            try { shutdownH2(h2); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * 真 MySQL 上的一次性库，两张表经生产的同步路径建好。
     *
     * @param lockWaitTimeoutSeconds 会话的 {@code innodb_lock_wait_timeout}（生产是 1；要观察死锁而不是锁等待超时的用例给大一点）
     */
    public static RatingTestDatabase mysql(int lockWaitTimeoutSeconds) {
        if(!mysqlAvailable())throw new IllegalStateException("没有给-Dgame.it.mysql");
        String parameters="?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=3000&socketTimeout=20000&useAffectedRows=true&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout="+lockWaitTimeoutSeconds;
        try{
            var options=AuthoritySqlFixture.Options.fromEnvironment(MYSQL_URL,MYSQL_USER,MYSQL_PASSWORD,"game_match",!Boolean.getBoolean("game.it.require-tidb"),java.util.List.of(1));
            var authority=AuthoritySqlFixture.prepare(options,(connection,principal)->{MatchRatingTables.registry().initializeFresh(connection,principal);createPlayerTable(connection);});
            DruidDataSource ds=new DruidDataSource();
            try{ds.setUrl(authority.jdbcUrl(parameters));ds.setUsername(authority.normalUser());ds.setPassword(authority.normalPassword());ds.setMaxActive(16);ds.setMaxWait(10_000);ds.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                AuthorityDataSourcePolicy.protectDruid(ds,authority.writerBoot(),!options.allowMySql());MatchRatingTables.sync(ds,Duration.ofMinutes(1));return new RatingTestDatabase(ds,authority.database(),authority);
            }catch(Exception failure){ds.close();try{authority.close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }catch(Exception failure){throw new IllegalStateException("评分隔离SQL夹具初始化失败",failure);}
    }

    public boolean isMysql() {
        return mysqlDatabase != null;
    }

    /** 仅供测试准备/损坏注入/清理；应用normal连接始终从原DataSource获取。 */
    public static DataSource h2SchemaDataSource(DataSource source) throws SQLException {
        H2Source owner = h2Owner(source); owner.requireOpen(); return owner.schema;
    }

    /** 外部writer资格接口替身；不代表grant、PD/I3或真实NORMAL授权。 */
    public static com.game.common.sql.WriterAuthority h2WriterAuthority(DataSource source) throws SQLException {
        H2Source owner = h2Owner(source); owner.requireOpen(); return owner.boot;
    }

    private static H2Source h2Owner(DataSource source) throws SQLException {
        if (!(source instanceof H2Source owner)) throw new SQLException("H2 schema入口必须属于本测试fixture");
        return owner;
    }

    private static void shutdownH2(DataSource schema) throws SQLException {
        try (Connection connection = schema.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    /** 状态只属于这一fixture，不按URL/表存在推断所有权，不跨测试缓存。 */
    private static final class H2Source extends org.springframework.jdbc.datasource.AbstractDataSource implements AutoCloseable {
        final JdbcDataSource schema;
        final DataSource normal;
        final com.game.common.sql.WriterAuthority boot;
        private final java.util.concurrent.atomic.AtomicBoolean sealed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean closed;
        boolean routeAttempted, routeReady;

        H2Source(JdbcDataSource schema) throws SQLException {
            this.schema = schema;
            final String physicalUrl;
            try (Connection raw = schema.getConnection()) { requireRawH2(raw); physicalUrl = raw.getMetaData().getURL(); }
            UUID identity = UUID.randomUUID();
            byte[] instance = java.nio.ByteBuffer.allocate(16).putLong(identity.getMostSignificantBits()).putLong(identity.getLeastSignificantBits()).array();
            boot = org.mockito.Mockito.mock(com.game.common.sql.WriterAuthority.class, invocation -> {
                String method = invocation.getMethod().getName();
                if (method.equals("verifyPhysical") || method.equals("begin")) {
                    Connection raw = invocation.getArgument(0); requireRawH2(raw);
                    if (sealed.get() || !raw.getAutoCommit() || !physicalUrl.equals(raw.getMetaData().getURL()))
                        throw new SQLException("H2 writer接口替身拒绝已seal、旧事务或另一内存库连接");
                    // 事务结局仍由真实AuthorityConnection在真实H2执行并恢复autoCommit。
                    if (method.equals("begin")) raw.setAutoCommit(false);
                    return null;
                }
                if (method.equals("seal")) { sealed.set(true); return null; }
                if (method.equals("isSealed")) return sealed.get();
                if (method.equals("instanceId")) return instance.clone();
                return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
            var credentials = new java.util.Properties(); credentials.setProperty("user", "sa"); credentials.setProperty("password", "");
            normal = new com.game.common.sql.AuthorityDataSource(schema.getURL(), credentials, boot, false, java.util.List.of());
        }

        private static void requireRawH2(Connection raw) throws SQLException {
            if (org.mockito.Mockito.mockingDetails(raw).isMock() || raw.isClosed()
                    || !"H2".equals(raw.getMetaData().getDatabaseProductName())
                    || !"SA".equalsIgnoreCase(raw.getMetaData().getUserName()))
                throw new SQLException("H2 writer接口替身必须收到真实SA物理连接");
        }

        void requireOpen() throws SQLException { if (closed) throw new SQLException("H2 fixture已关闭", "08003"); }
        @Override public Connection getConnection() throws SQLException { requireOpen(); return normal.getConnection(); }
        @Override public Connection getConnection(String user, String password) throws SQLException { requireOpen(); return normal.getConnection(user, password); }
        @Override public synchronized void close() throws SQLException {
            if (closed) return;
            closed = true; boot.seal(); shutdownH2(schema);
        }
    }

    private static void createH2Tables(DataSource ds) throws SQLException {
        PbMysql registry = MatchRatingTables.registry();
        try (Connection c = h2SchemaDataSource(ds).getConnection(); Statement st = c.createStatement()) {
            for (TableSchema table : registry.tables()) {
                st.execute(h2Ddl(table.createTableSql()));
            }
        }
        initializeRouteAuthority(ds);
    }

    private static void createPlayerTable(Connection connection)throws SQLException{
        try(var statement=connection.createStatement()){
            statement.execute("CREATE TABLE IF NOT EXISTS player(player_id BIGINT PRIMARY KEY,zone_id INT NOT NULL DEFAULT 1,"
                    + "name VARCHAR(128) NOT NULL DEFAULT '',level INT NOT NULL DEFAULT 1,class_id INT NOT NULL DEFAULT 0,"
                    + "gender INT NOT NULL DEFAULT 0,appearance_id VARCHAR(128) NOT NULL DEFAULT '')");
        }
    }

    static void initializeRouteAuthority(DataSource source)throws SQLException {
        H2Source owner = h2Owner(source);
        synchronized (owner) {
            owner.requireOpen();
            if (owner.routeReady) return;
            if (owner.routeAttempted) throw new SQLException("本H2 fixture的fresh控制DDL曾失败，拒绝复用部分schema");
            owner.routeAttempted = true;
        try(var c=owner.schema.getConnection();var statement=c.createStatement()){
            createPlayerTable(c);
            var resource=new org.springframework.core.io.ClassPathResource("db/game-route-authority-schema.sql");
            String sql;try(var input=resource.getInputStream()){sql=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
            if("H2".equals(c.getMetaData().getDatabaseProductName()))sql=sql.replaceAll("(?i)ENGINE\\s*=\\s*InnoDB[^;]*","");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.support.EncodedResource(new org.springframework.core.io.ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            try(var check=c.createStatement();var rows=check.executeQuery("SELECT COUNT(*) FROM route_meta")){rows.next();if(rows.getInt(1)!=0)throw new SQLException("fresh H2 route_meta必须为空");}
            String principal;try(var identity=c.createStatement();var rows=identity.executeQuery("SELECT CURRENT_USER()")){if(!rows.next())throw new SQLException("缺H2 schema主体");principal=rows.getString(1);}
            com.game.common.player.RouteAuthorityInitializer.initialize(c,UUID.randomUUID().toString(),java.util.List.of(1),principal);
        }catch(java.io.IOException failure){throw new SQLException("正式路由测试DDL不可读",failure);}
            owner.routeReady = true;
        }
    }

    /**
     * pbmysql 的 MySQL DDL → H2 MySQL 模式能执行的形式：去掉表尾选项；无符号整数列换成放得下它的类型——H2 把 {@code bigint unsigned} 当成有符号的
     * BIGINT，≥ 2^63 的玩家号 / 战斗号存不进去，所以换成 NUMERIC(20)；{@code int unsigned} 换成 BIGINT。列名、主键、索引不变。
     */
    static String h2Ddl(String ddl) {
        return ddl.replace(" bigint unsigned ", " NUMERIC(20) ").replace(" int unsigned ", " BIGINT ")
                .replaceAll("\\) ENGINE=InnoDB[^\\n]*;\\s*$", ");");
    }

    // ================================================================ 摆状态 / 看状态

    /** 直接写一行评分（覆盖已有的）。 */
    public void putRating(long playerId, long ratingCenti, long games) {
        update("DELETE FROM match_rating WHERE player_id = ?", PbMysql.uint64(playerId));
        update("INSERT INTO match_rating (player_id, rating_centi, games, updated_at_ms) VALUES (?, ?, ?, 0)", PbMysql.uint64(playerId),
                ratingCenti, games);
    }

    /** 直接写一行入账标记（给清理用例摆旧数据）。 */
    public void putApplied(long battleId, long appliedAtMs) {
        update("INSERT INTO match_rating_applied (battle_id, match_mode, delta_a_centi, applied_at_ms) VALUES (?, 3, 0, ?)",
                PbMysql.uint64(battleId), appliedAtMs);
    }

    /** 评分行：{rating_centi, games, updated_at_ms}；没有行为空。 */
    public Optional<long[]> ratingRow(long playerId) {
        return query("SELECT rating_centi, games, updated_at_ms FROM match_rating WHERE player_id = ?",
                rs -> rs.next() ? Optional.of(new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}) : Optional.empty(),
                PbMysql.uint64(playerId));
    }

    public OptionalLong ratingCenti(long playerId) {
        return ratingRow(playerId).map(row -> OptionalLong.of(row[0])).orElse(OptionalLong.empty());
    }

    public long games(long playerId) {
        return ratingRow(playerId).map(row -> row[1]).orElse(-1L);
    }

    /** 入账标记行：{match_mode, delta_a_centi, applied_at_ms}；没有行为空。 */
    public Optional<long[]> appliedRow(long battleId) {
        return query("SELECT match_mode, delta_a_centi, applied_at_ms FROM match_rating_applied WHERE battle_id = ?",
                rs -> rs.next() ? Optional.of(new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}) : Optional.empty(),
                PbMysql.uint64(battleId));
    }

    public long count(String table) {
        return query("SELECT COUNT(*) FROM " + table, rs -> {
            rs.next();
            return rs.getLong(1);
        });
    }

    /** 全部评分之和（centi）：零和性质的断言用。 */
    public long sumRatingCenti() {
        return query("SELECT COALESCE(SUM(rating_centi), 0) FROM match_rating", rs -> {
            rs.next();
            BigDecimal sum = rs.getBigDecimal(1);
            return sum == null ? 0L : sum.longValueExact();
        });
    }

    public void update(String sql, Object... args) {
        try (Connection c = authority==null?h2SchemaDataSource(dataSource).getConnection():authority.schemaConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    @FunctionalInterface
    public interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    public <T> T query(String sql, RowReader<T> reader, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return reader.read(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** 真 MySQL：关连接池并删掉一次性库。H2：把内存库清掉。 */
    @Override public void close(){
        if(authority!=null){((DruidDataSource)dataSource).close();try{authority.close();}catch(SQLException failure){throw new IllegalStateException("评分fixture归属清理未确认",failure);}return;}
        try { h2Owner(dataSource).close(); } catch(SQLException failure){throw new IllegalStateException("H2评分fixture关闭未确认",failure);}
    }
}
