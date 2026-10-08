package com.game.match.rating;

import com.alibaba.druid.pool.DruidDataSource;
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
 *   <li>{@link #mysql()}：真 MySQL（{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}，口令取环境变量 XM_MYSQL_PASSWORD）：建一个一次性库
 *       {@code xm_match_it_<随机>}，两张表走生产路径 {@link MatchRatingTables#sync}，{@link #close()} 时删库；绝不碰 {@code xm_java}。
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

    public static final String MYSQL_URL = System.getProperty("xm.it.mysql");
    public static final String MYSQL_USER = System.getProperty("xm.it.mysql.user", "root");
    public static final String MYSQL_PASSWORD = System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");

    /** 在 H2 上建两张评分表（幂等：DDL 是 {@code CREATE TABLE IF NOT EXISTS}）。 */
    public static final MatchRatingTables.SchemaSync H2_SCHEMA = RatingTestDatabase::createH2Tables;

    public final DataSource dataSource;
    private final String mysqlDatabase;

    private RatingTestDatabase(DataSource dataSource, String mysqlDatabase) {
        this.dataSource = dataSource;
        this.mysqlDatabase = mysqlDatabase;
    }

    /** 是否给了真 MySQL 的地址。 */
    public static boolean mysqlAvailable() {
        return MYSQL_URL != null && !MYSQL_URL.isBlank();
    }

    /** 一个全新的 H2 内存库，两张表已建好。 */
    public static RatingTestDatabase h2() {
        DataSource ds = h2DataSource("xm-match-rating-" + UUID.randomUUID());
        try {
            createH2Tables(ds);
        } catch (SQLException e) {
            throw new IllegalStateException("H2 建评分表失败", e);
        }
        return new RatingTestDatabase(ds, null);
    }

    /** H2 内存库的数据源（MySQL 兼容模式；库随 JVM 存活；表要另外建，见 {@link #H2_SCHEMA}）。 */
    public static DataSource h2DataSource(String name) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return h2;
    }

    /**
     * 真 MySQL 上的一次性库，两张表经生产的同步路径建好。
     *
     * @param lockWaitTimeoutSeconds 会话的 {@code innodb_lock_wait_timeout}（生产是 1；要观察死锁而不是锁等待超时的用例给大一点）
     */
    public static RatingTestDatabase mysql(int lockWaitTimeoutSeconds) {
        if (!mysqlAvailable()) {
            throw new IllegalStateException("没有给 -Dxm.it.mysql");
        }
        String database = "xm_match_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String params = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=3000&socketTimeout=20000"
                + "&useAffectedRows=true&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',"
                + "innodb_lock_wait_timeout=" + lockWaitTimeoutSeconds;
        try (Connection c = DriverManager.getConnection(MYSQL_URL + "/" + params, MYSQL_USER, MYSQL_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4");
        } catch (SQLException e) {
            throw new IllegalStateException("建一次性库失败", e);
        }
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(MYSQL_URL + "/" + database + params);
        ds.setUsername(MYSQL_USER);
        ds.setPassword(MYSQL_PASSWORD);
        ds.setMaxActive(16);
        ds.setMaxWait(10_000);
        ds.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        RatingTestDatabase db = new RatingTestDatabase(ds, database);
        try {
            MatchRatingTables.sync(ds, Duration.ofMinutes(1));
        } catch (SQLException | RuntimeException e) {
            db.close();
            throw new IllegalStateException("真 MySQL 上同步评分表失败", e);
        }
        return db;
    }

    public boolean isMysql() {
        return mysqlDatabase != null;
    }

    private static void createH2Tables(DataSource ds) throws SQLException {
        PbMysql registry = MatchRatingTables.registry();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (TableSchema table : registry.tables()) {
                st.execute(h2Ddl(table.createTableSql()));
            }
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
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
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
    @Override
    public void close() {
        if (isMysql()) {
            ((DruidDataSource) dataSource).close();
            try (Connection c = DriverManager.getConnection(MYSQL_URL + "/?useSSL=false&allowPublicKeyRetrieval=true", MYSQL_USER, MYSQL_PASSWORD);
                 Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + mysqlDatabase + "`");
            } catch (SQLException e) {
                throw new IllegalStateException("删一次性库 " + mysqlDatabase + " 失败", e);
            }
        } else {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("SHUTDOWN");
            } catch (SQLException e) {
                // 内存库已经不在了：无事可做
            }
        }
    }
}
