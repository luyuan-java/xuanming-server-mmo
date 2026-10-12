package com.game.friend.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 清理的 SQL 侧（mmorpg go/friend internal/data/sweep_repo.go，friend-spec.md §6）：终态好友申请与零好友容量行。SQL 逐字照搬。
 *
 * <p>纪律：
 * <ul>
 *   <li>候选普通读、读完先关结果集，再<b>逐行、自动提交、按完整主键</b>删，WHERE 里重复条件作提交点复核——不许改成批量
 *       {@code DELETE ... LIMIT}：批量删按二级索引序锁多行，与写路径的「先主键」反序会成 1213；容量行回收任一时刻至多持一把守卫锁；</li>
 *   <li>终态必须写 {@code status IN (2,3)} 而不是 {@code <> 1}：这样 {@code updated_ms} 才是 {@code (status, updated_ms)} 索引的区间上界；
 *       status = 0 的行永不清；pending 永不清；</li>
 *   <li>截止点非正（进程时钟不对）绝不传给 SQL：负值与无符号列比较会匹配所有行——delete 模式下等于清空整表；</li>
 *   <li>delete 模式的保险：存在 updated_ms = 0 的终态行（某条写路径漏写 updated_ms）时只数不删；</li>
 *   <li>出错时带着<b>已删行数</b>返回（前面各行已各自提交）。</li>
 * </ul>
 * 连接池须是会话级 READ COMMITTED。阻塞；只在清理线程上调用。
 */
public final class SweepStore {

    private static final Logger log = LoggerFactory.getLogger(SweepStore.class);

    public static final String MODE_REPORT_ONLY = "report_only";
    public static final String MODE_DELETE = "delete";
    public static final int MAX_RETENTION_DAYS = 36500;
    static final long MILLIS_PER_DAY = 86_400_000L;

    static final String COUNT_TERMINAL = "SELECT COUNT(*) FROM (SELECT 1 FROM friend_request WHERE status IN (?, ?)"
            + " AND updated_ms < ? LIMIT ?) AS bounded";
    static final String COUNT_TERMINAL_MISSING_UPDATED = "SELECT COUNT(*) FROM (SELECT 1 FROM friend_request"
            + " WHERE status IN (?, ?) AND updated_ms = 0 LIMIT ?) AS bounded";
    static final String LIST_TERMINAL = "SELECT from_player_id, to_player_id FROM friend_request WHERE status IN (?, ?)"
            + " AND updated_ms < ? LIMIT ?";
    static final String DELETE_TERMINAL = "DELETE FROM friend_request WHERE from_player_id = ? AND to_player_id = ?"
            + " AND status IN (?, ?) AND updated_ms < ?";
    static final String LIST_IDLE_CAPACITY = "SELECT player_id FROM friend_capacity WHERE friend_count = 0 AND created_ms < ?"
            + " LIMIT ?";
    static final String DELETE_IDLE_CAPACITY = "DELETE FROM friend_capacity WHERE player_id = ? AND friend_count = 0"
            + " AND created_ms < ?";

    /** 一段清理的结果：{@code seen} 受 batchLimit 封顶（等于它只说明积压 ≥ 一批）；{@code error} 非空时 deleted 是中止前已删的行数。 */
    public record Result(long seen, long deleted, Exception error) {
        static Result ok(long seen, long deleted) {
            return new Result(seen, deleted, null);
        }
    }

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;

    public SweepStore(DataSource dataSource, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * 截止点：{@code batchLimit ≤ 0} 或保留期越界 → 抛（配置校验已拒，这里 fail-fast，不当默认值）；截止点非正 → -1（打 ERROR，本轮不做事）。
     */
    static long cutoffMs(int retentionDays, int batchLimit, long nowMs) {
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("sweep batchLimit 必须为正数: " + batchLimit);
        }
        if (retentionDays <= 0 || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("sweep retentionDays 必须在 [1, " + MAX_RETENTION_DAYS + "] 内: " + retentionDays);
        }
        long cutoff = nowMs - retentionDays * MILLIS_PER_DAY;
        if (cutoff <= 0) {
            log.error("[friend] sweep 截止点非正（nowMs={} retention_days={}），本轮不做任何事：检查进程时钟", nowMs, retentionDays);
            return -1;
        }
        return cutoff;
    }

    /** 终态好友申请（mode 不是 delete 时只数不删）。{@code keepGoing} 在逐行删之间检查（单轮预算）。 */
    public Result sweepTerminalRequests(String mode, int retentionDays, int batchLimit, long nowMs, BooleanSupplier keepGoing) {
        long cutoff = cutoffMs(retentionDays, batchLimit, nowMs);
        if (cutoff < 0) {
            return Result.ok(0, 0);
        }
        long pending;
        try {
            pending = count(COUNT_TERMINAL, JdbcFriendStore.STATUS_ACCEPTED, JdbcFriendStore.STATUS_REJECTED, cutoff, batchLimit);
        } catch (SQLException e) {
            return new Result(0, 0, e);
        }
        if (pending == 0 || !MODE_DELETE.equals(mode)) {
            return Result.ok(pending, 0);
        }
        try {
            long missing = count(COUNT_TERMINAL_MISSING_UPDATED, JdbcFriendStore.STATUS_ACCEPTED,
                    JdbcFriendStore.STATUS_REJECTED, batchLimit);
            if (missing > 0) {
                log.error("[friend] sweep(delete) 拒绝删除：发现 {} 行终态好友申请的 updated_ms=0，说明有写路径没写 updated_ms"
                        + " 或旧数据搬迁时漏了这一列。修好写入方之前继续按观察模式运行；存量修法：UPDATE friend_request"
                        + " SET updated_ms=request_time_ms WHERE status IN (2, 3) AND updated_ms=0", missing);
                return Result.ok(pending, 0);
            }
        } catch (SQLException e) {
            return new Result(pending, 0, e);
        }
        List<long[]> candidates = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = prepare(c, LIST_TERMINAL, JdbcFriendStore.STATUS_ACCEPTED,
                     JdbcFriendStore.STATUS_REJECTED, cutoff, batchLimit);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                candidates.add(new long[] {JdbcFriendStore.readUid(rs, 1), JdbcFriendStore.readUid(rs, 2)});
            }
        } catch (SQLException e) {
            return new Result(pending, 0, e);
        }
        long deleted = 0;
        for (long[] key : candidates) {
            if (!keepGoing.getAsBoolean()) {
                return new Result(pending, deleted, new IllegalStateException("单轮预算用完，已删 " + deleted + "/" + candidates.size()));
            }
            try {
                deleted += update(DELETE_TERMINAL, JdbcFriendStore.uid(key[0]), JdbcFriendStore.uid(key[1]),
                        JdbcFriendStore.STATUS_ACCEPTED, JdbcFriendStore.STATUS_REJECTED, cutoff);
            } catch (SQLException e) {
                return new Result(pending, deleted, e);
            }
        }
        return Result.ok(pending, deleted);
    }

    /** 零好友容量行（没有 updated_ms 那道保险：created_ms = 0 的零好友行被回收无害，补行时按权威边数重算）。 */
    public Result sweepIdleCapacityRows(String mode, int retentionDays, int batchLimit, long nowMs, BooleanSupplier keepGoing) {
        long cutoff = cutoffMs(retentionDays, batchLimit, nowMs);
        if (cutoff < 0) {
            return Result.ok(0, 0);
        }
        List<Long> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = prepare(c, LIST_IDLE_CAPACITY, cutoff, batchLimit);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(JdbcFriendStore.readUid(rs, 1));
            }
        } catch (SQLException e) {
            return new Result(0, 0, e);
        }
        long idle = ids.size();
        if (idle == 0 || !MODE_DELETE.equals(mode)) {
            return Result.ok(idle, 0);
        }
        long deleted = 0;
        for (long id : ids) {
            if (!keepGoing.getAsBoolean()) {
                return new Result(idle, deleted, new IllegalStateException("单轮预算用完，已删 " + deleted + "/" + idle));
            }
            try {
                deleted += update(DELETE_IDLE_CAPACITY, JdbcFriendStore.uid(id), cutoff);
            } catch (SQLException e) {
                return new Result(idle, deleted, e);
            }
        }
        return Result.ok(idle, deleted);
    }

    /** 直调用：按主键删一行容量行（提交点复核由 WHERE 保证；测试钉住）。 */
    long deleteIdleCapacityRow(long playerId, long cutoff) throws SQLException {
        return update(DELETE_IDLE_CAPACITY, JdbcFriendStore.uid(playerId), cutoff);
    }

    private long count(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = prepare(c, sql, args);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private int update(String sql, Object... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = prepare(c, sql, args)) {
            return ps.executeUpdate();
        }
    }

    private PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            if (queryTimeoutSeconds > 0) {
                ps.setQueryTimeout(queryTimeoutSeconds);
            }
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }
}
