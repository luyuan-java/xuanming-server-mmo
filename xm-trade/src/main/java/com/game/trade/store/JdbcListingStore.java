package com.game.trade.store;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.PlayerProfiles.ConnectionSource;
import com.game.trade.rules.TradeLimits;
import com.game.trade.store.ListingSql.Stmt;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ListingStore} 的 MySQL 实现（基线 data.ListingRepo，listing_repo.go:80-349；trade-spec §1.6、§1.9、§5.4、§5.5）：手写参数化 SQL
 * （{@link ListingSql}，逐字照搬基线），不拼任何用户输入。
 *
 * <p><b>每次调用的上限</b>（listing_repo.go:93-95 bounded）：先看请求预算，用完了就不取连接、不发语句（抛 {@link DependencyException}）；
 * 否则本次调用的截止时刻 = {@code min(STORE_OP_TIMEOUT_MS = 2000 ms, 请求剩余)}，三处都受它约束：
 * <ol>
 *   <li>取连接最多等剩余时长（Druid {@code getConnection(long)}，见 {@link ConnectionSource}；连接池的固定 max-wait 不认预算）；</li>
 *   <li>SELECT 带 {@code MAX_EXECUTION_TIME(剩余毫秒)} 提示（服务端按毫秒中止只读查询；{@link ListingSql#withExecutionTimeHint}）；</li>
 *   <li>JDBC 查询超时 {@code min(上限, 剩余向上取整到秒)} 兜底网络停顿；写语句另有连接级 {@code innodb_lock_wait_timeout=2}（Q7）。</li>
 * </ol>
 * 收藏写入整个带重试的调用一共只有这一份上限（listing_repo.go:302-305 的 bounded 包住整个 WithTxRetry）。
 *
 * <p><b>收藏写入的有界重试</b>（listing_repo.go:288-312）：F4 用自动提交语句（会话级 RC 下与基线单语句的 RC 事务语义相同，trade-spec §5.5）；
 * 撞 1213 / 1205 / 9007（错误号沿 cause 链按 {@link SQLException#getErrorCode()} 取，不做文本匹配；asset_op_repo.go:876-895）整条重跑，
 * 共 {@value TradeLimits#FAVORITE_WRITE_ATTEMPTS} 次，两次之间退避 8–12 ms（10 ms ±20%，decide.go:144-155）；剩余预算不够退避就不再重跑。
 * 语句幂等，重跑安全；每次重跑计一次 {@link Events#favoriteRetry}。其余写入（InsertListing、DeleteFavorite）与读都不重试。
 *
 * <p><b>无符号</b>：绑定见 {@link ListingSql} 的约定；读 {@code bigint unsigned} 不能直接 {@code rs.getLong}（≥ 2^63 时 Connector/J 报越界），
 * 按 {@code getObject} 取 {@link BigInteger} 再转位模式（{@link #readU64}）；MEDIUMTEXT 读回 NULL 统一换成 {@code ""}（§1.5、§5.9 第 8 条）。
 *
 * <p>连接池须是会话级 READ COMMITTED + {@code STRICT_TRANS_TABLES}（对应基线 DSN，servicecontext.go:225-243）。线程安全；全部方法阻塞，
 * 只在 trade-worker 线程（或播种接口的 Tomcat 线程）上调用。
 */
public final class JdbcListingStore implements ListingStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcListingStore.class);

    /** InnoDB 死锁：语句（自动提交时即整个事务）已回滚，可重跑。 */
    static final int ER_LOCK_DEADLOCK = 1213;
    /** 锁等待超时（innodb_lock_wait_timeout）：基线 IsRetryableTxError 也认它（asset_op_repo.go:888）。 */
    static final int ER_LOCK_WAIT_TIMEOUT = 1205;
    /** TiDB 写冲突：Java 首批不上 TiDB，照基线一并认。 */
    static final int TIDB_WRITE_CONFLICT = 9007;

    /** 事件回调（指标 {@code xm.trade.favorite.retries}）。 */
    public interface Events {
        /** 收藏写入撞了可重试错误、整条重跑一次。 */
        void favoriteRetry();

        Events NONE = () -> { };
    }

    /** 测试缝：退避等待。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** 截止时刻已过，语句没有发出（不是可重试错误）。 */
    private static final class BudgetExpired extends SQLException {
        BudgetExpired(String op) {
            super("超过预算，不再执行 " + op);
        }
    }

    private final ConnectionSource connections;
    private final int queryTimeoutCapSeconds;
    private final Events events;
    private final DoubleSupplier jitter;
    private final Sleeper sleeper;

    /**
     * @param connections            取连接（按次限等；Druid 用 {@code druid::getConnection}）
     * @param queryTimeoutCapSeconds 每条语句查询超时的上限（秒，{@code xm.trade.query-timeout}）；实际取它与剩余预算（向上取整）的较小者；≤ 0 不设上限
     * @param events                 指标回调
     */
    public JdbcListingStore(ConnectionSource connections, int queryTimeoutCapSeconds, Events events) {
        this(connections, queryTimeoutCapSeconds, events, () -> ThreadLocalRandom.current().nextDouble(), Thread::sleep);
    }

    JdbcListingStore(ConnectionSource connections, int queryTimeoutCapSeconds, Events events, DoubleSupplier jitter,
                     Sleeper sleeper) {
        this.connections = connections;
        this.queryTimeoutCapSeconds = queryTimeoutCapSeconds;
        this.events = events;
        this.jitter = jitter;
        this.sleeper = sleeper;
    }

    // ================================================================ 浏览 / 货架 / 详情

    @Override
    public long countListings(ListingQuery query, Deadline deadline) {
        return select("CountListings", deadline, ListingSql.countListings(query), JdbcListingStore::readCount);
    }

    @Override
    public List<Listing> queryListings(ListingQuery query, long offset, int limit, Deadline deadline) {
        return select("QueryListings", deadline, ListingSql.queryListings(query, offset, limit), rs -> readListings(rs, false));
    }

    @Override
    public long countSellerListings(long sellerPlayerId, Deadline deadline) {
        return select("CountSellerListings", deadline, ListingSql.countSellerListings(sellerPlayerId), JdbcListingStore::readCount);
    }

    @Override
    public List<Listing> querySellerListings(long sellerPlayerId, long offset, int limit, Deadline deadline) {
        return select("QuerySellerListings", deadline, ListingSql.querySellerListings(sellerPlayerId, offset, limit),
                rs -> readListings(rs, false));
    }

    @Override
    public Optional<Listing> getListing(long listingId, Deadline deadline) {
        return select("GetListing", deadline, ListingSql.getListing(listingId),
                rs -> rs.next() ? Optional.of(readListing(rs, true)) : Optional.empty());
    }

    @Override
    public void insertListing(Listing listing, Deadline deadline) {
        String op = "InsertListing";
        Deadline bound = bound(op, deadline);
        try {
            update(op, bound, ListingSql.insertListing(listing));
        } catch (SQLException e) {
            throw new DependencyException("trade " + op + " 失败 listing_id=" + Long.toUnsignedString(listing.listingId()), e);
        }
    }

    // ================================================================ 收藏

    @Override
    public Set<Long> favoriteIds(long playerId, List<Long> listingIds, Deadline deadline) {
        if (listingIds.isEmpty()) {
            return Set.of();
        }
        return select("FavoriteIDs", deadline, ListingSql.favoriteIds(playerId, listingIds), rs -> {
            Set<Long> out = new HashSet<>();
            while (rs.next()) {
                out.add(readU64(rs, 1));
            }
            return Collections.unmodifiableSet(out);
        });
    }

    @Override
    public boolean favoriteExists(long playerId, long listingId, Deadline deadline) {
        return select("FavoriteExists", deadline, ListingSql.favoriteExists(playerId, listingId), ResultSet::next);
    }

    @Override
    public long countFavorites(long playerId, Deadline deadline) {
        return select("CountFavorites", deadline, ListingSql.countFavorites(playerId), JdbcListingStore::readCount);
    }

    @Override
    public void insertFavorite(long playerId, long listingId, long createdMs, Deadline deadline) {
        String op = "InsertFavorite";
        Deadline bound = bound(op, deadline);
        Stmt stmt = ListingSql.insertFavorite(playerId, listingId, createdMs);
        for (int attempt = 1; ; attempt++) {
            try {
                update(op, bound, stmt);
                return;
            } catch (SQLException e) {
                int code = errorCode(e);
                if (!isRetryable(e) || attempt >= TradeLimits.FAVORITE_WRITE_ATTEMPTS) {
                    throw new DependencyException("trade " + op + " 失败（第 " + attempt + " 次尝试，错误号 " + code + "）", e);
                }
                long backoff = backoffMillis();
                if (bound.remainingMillis() <= backoff) {
                    throw new DependencyException("trade " + op + " 撞 " + code + "，剩余预算不够退避重跑", e);
                }
                events.favoriteRetry();
                log.warn("收藏写入撞 {}，{} ms 后整条重跑 player={} listing={}", code, backoff,
                        Long.toUnsignedString(playerId), Long.toUnsignedString(listingId));
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new DependencyException("trade " + op + " 退避被中断", e);
                }
            }
        }
    }

    @Override
    public void deleteFavorite(long playerId, long listingId, Deadline deadline) {
        String op = "DeleteFavorite";
        Deadline bound = bound(op, deadline);
        try {
            update(op, bound, ListingSql.deleteFavorite(playerId, listingId));
        } catch (SQLException e) {
            throw new DependencyException("trade " + op + " 失败", e);
        }
    }

    // ================================================================ 执行

    /** 单次调用的截止时刻 {@code min(2000 ms, 请求剩余)}；请求预算已经用完就不再开始（listing_repo.go:93-95）。 */
    private static Deadline bound(String op, Deadline deadline) {
        if (deadline.expired()) {
            throw new DependencyException("超过请求预算，不再执行 trade " + op);
        }
        return Deadline.after(Math.min(TradeLimits.STORE_OP_TIMEOUT_MS, deadline.remainingMillis()));
    }

    private <T> T select(String op, Deadline deadline, Stmt stmt, Reader<T> reader) {
        Deadline bound = bound(op, deadline);
        try (Connection c = acquire(op, bound);
             PreparedStatement ps = prepare(c, op, stmt, bound, true);
             ResultSet rs = ps.executeQuery()) {
            return reader.read(rs);
        } catch (SQLException e) {
            throw new DependencyException("trade " + op + " 失败", e);
        }
    }

    private int update(String op, Deadline bound, Stmt stmt) throws SQLException {
        try (Connection c = acquire(op, bound); PreparedStatement ps = prepare(c, op, stmt, bound, false)) {
            return ps.executeUpdate();
        }
    }

    /** 取连接最多等本次调用的剩余时长；等完已过截止时刻就还回去、不发语句。 */
    private Connection acquire(String op, Deadline bound) throws SQLException {
        long remaining = bound.remainingMillis();
        if (remaining <= 0 || bound.expired()) {
            throw new BudgetExpired(op);
        }
        Connection c = connections.get(remaining);
        if (bound.expired()) {
            c.close();
            throw new BudgetExpired(op + "（等连接用完了预算）");
        }
        return c;
    }

    /** 每条语句：预算用完就不发；SELECT 加执行时限提示；查询超时 = min(上限, 剩余预算向上取整到秒)。 */
    private PreparedStatement prepare(Connection c, String op, Stmt stmt, Deadline bound, boolean select) throws SQLException {
        long remaining = bound.remainingMillis();
        if (remaining <= 0 || bound.expired()) {
            throw new BudgetExpired(op);
        }
        String sql = select ? ListingSql.withExecutionTimeHint(stmt.sql(), remaining) : stmt.sql();
        int timeout = (int) Math.max(1, (remaining + 999) / 1000);
        if (queryTimeoutCapSeconds > 0) {
            timeout = Math.min(timeout, queryTimeoutCapSeconds);
        }
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(timeout);
            List<Object> args = stmt.args();
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    /**
     * 两次尝试之间的退避：{@code 10 ms × (0.8 + 0.4 × jitter)} 按纳秒算、再向下取整到毫秒 → 8–12 ms（±20%；NextAttemptMs，decide.go:144-155；
     * jitter 越界夹回 [0,1)）。算式与基线逐项相同（含先乘纳秒再除），免得浮点舍入在边界上与 Go 差 1 ms。
     */
    long backoffMillis() {
        double j = jitter.getAsDouble();
        if (Double.isNaN(j) || j < 0) {
            j = 0;
        } else if (j >= 1) {
            j = Math.nextDown(1.0);
        }
        double scaled = (double) (TradeLimits.FAVORITE_RETRY_BACKOFF_MS * 1_000_000L) * (0.8 + 0.4 * j);
        return (long) (scaled / 1_000_000.0);
    }

    // ================================================================ 错误分类

    /** 沿 cause 链找到的第一个非 0 MySQL 错误号；没有时返回 0（同 GuildSqlErrors.errorCode）。 */
    static int errorCode(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() != 0) {
                return sql.getErrorCode();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return 0;
    }

    /** 1213 / 1205 / 9007（基线 IsRetryableTxError，asset_op_repo.go:876-895）。 */
    static boolean isRetryable(Throwable error) {
        int code = errorCode(error);
        return code == ER_LOCK_DEADLOCK || code == ER_LOCK_WAIT_TIMEOUT || code == TIDB_WRITE_CONFLICT;
    }

    // ================================================================ 读行

    private static long readCount(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            throw new SQLException("计数查询没有返回行");
        }
        return rs.getLong(1);
    }

    private static List<Listing> readListings(ResultSet rs, boolean withDescription) throws SQLException {
        List<Listing> out = new ArrayList<>();
        while (rs.next()) {
            out.add(readListing(rs, withDescription));
        }
        return out;
    }

    /** 按 {@link ListingSql#SUMMARY_COLUMNS}（+ description）的列序读一行（scanListing，listing_repo.go:330-349）。 */
    static Listing readListing(ResultSet rs, boolean withDescription) throws SQLException {
        return new Listing(
                readU64(rs, 1),                                     // listing_id
                readU64(rs, 2),                                     // seller_player_id
                readText(rs, 3),                                    // seller_account
                readU32(rs, 4),                                     // market_zone
                readU32(rs, 5),                                     // seller_zone_at_listing
                rs.getInt(6),                                       // category
                readU32(rs, 7),                                     // subcategory
                readText(rs, 8),                                    // title
                readU32(rs, 9),                                     // level
                readU64(rs, 10),                                    // price_fen
                rs.getInt(11),                                      // status
                readText(rs, 12),                                   // summary
                withDescription ? readText(rs, ListingSql.DETAIL_COLUMN_COUNT) : "", // description
                readText(rs, 13),                                   // icon_key
                readU64(rs, 14),                                    // notice_end_ms
                readU64(rs, 15),                                    // sale_end_ms
                readU64(rs, 16),                                    // created_ms
                readU64(rs, 17),                                    // updated_ms
                readU64(rs, 18));                                   // version
    }

    /** 读 {@code bigint unsigned} 列为 uint64 位模式（驱动对它给 {@link BigInteger}）。 */
    static long readU64(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        if (value instanceof BigInteger big) {
            return big.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new SQLException("列 " + column + " 不是数字: " + value);
    }

    /** 读 {@code int unsigned} 列为 uint32 位模式。 */
    static int readU32(ResultSet rs, int column) throws SQLException {
        return (int) rs.getLong(column);
    }

    /** 读 MEDIUMTEXT：NULL → {@code ""}（手工插入的 NULL 在 Go 里会扫描失败成 1003；Java 不依赖异常路径，§1.5）。 */
    static String readText(ResultSet rs, int column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }
}
