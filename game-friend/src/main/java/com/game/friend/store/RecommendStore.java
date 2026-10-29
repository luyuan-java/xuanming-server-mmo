package com.game.friend.store;

import com.game.common.deadline.Deadline;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongBinaryOperator;
import javax.sql.DataSource;

/**
 * 好友推荐的 JDBC 仓储（friend-spec.md §5.1）。查询结构与方言提示由 {@link RecommendSql} 定义：
 * <ul>
 *   <li>{@code STRAIGHT_JOIN} 钉住「先 f1（我的好友）后 f2（好友的好友）」——统计陈旧时优化器会翻成 f2 全索引扫描驱动；</li>
 *   <li>五条 NOT EXISTS 按方向拆开、每条都是完整主键等值；MySQL FIRSTMATCH / TiDB INL_JOIN 与主键提示，
 *       一起把每条排除钉成「每个候选一次主键单行点查」，扫描量与全服 pending 数、「拉黑我的人数」无关；</li>
 *   <li>共同好友排名先物化前 1024 人，随机兜底先物化锚点后 1024 个去重玩家；排除只在窗口内执行，可少于 want；</li>
 *   <li>随机兜底只对返回行数真实共同好友，按候选自己的好友主键前缀定位，不按共同数重排。</li>
 * </ul>
 * <b>纯读</b>：系统读取不进事务；客户端绑定视图在短事务首锁 P 验证原会话，关系查询仍为普通读、允许轻微陈旧。
 * 绝不能被复用成 AddFriend 的前置判定。候选池只有「至少有一条好友边的玩家」。
 * 这些写法 H2 不支持，测试连真 MySQL。线程安全；阻塞。
 */
public final class RecommendStore implements RecommendSource {

    /** 一条推荐候选；两种来源均报告真实共同好友数。 */
    public record Candidate(long playerId, int mutualFriends) {
    }

    /** 两种来源在关系排除前最多保留的候选数；排名窗口 / pivot 窗口的预算账见 spec §5.1。 */
    public static final int ANCHOR_WINDOW = 1024;

    static final String ID_RANGE = "SELECT MIN(player_id), MAX(player_id) FROM friend";

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;
    /** {@code (lo, span) -> [0, span)} 的随机偏移，调用方再加 lo；span 按无符号解释。 */
    private final LongBinaryOperator pivotPicker;
    private final FriendActor actor;
    private final RecommendSql queries;

    public RecommendStore(DataSource dataSource, int queryTimeoutSeconds, LongBinaryOperator pivotPicker) {
        this(dataSource, queryTimeoutSeconds, pivotPicker, new RecommendSql(RecommendSql.Dialect.MYSQL), null);
    }

    /** 生产装配在启动时确认实际方言；旧构造器仅供显式 MySQL/纯分发夹具。 */
    public static RecommendStore forDatabase(DataSource source, int timeout, LongBinaryOperator pivot) throws SQLException {
        try (Connection connection = source.getConnection()) {
            return new RecommendStore(source, timeout, pivot, new RecommendSql(RecommendSql.detect(connection, timeout)), null);
        }
    }

    private RecommendStore(DataSource dataSource, int queryTimeoutSeconds, LongBinaryOperator pivotPicker,
                           RecommendSql queries, FriendActor actor) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.pivotPicker = pivotPicker;
        this.actor = actor;
        this.queries = queries;
    }

    @Override
    public RecommendSource forActor(long playerId, String originalAccount, long originalRevision) {
        return new RecommendStore(dataSource, queryTimeoutSeconds, pivotPicker, queries,
                new FriendActor(playerId, originalAccount, originalRevision));
    }

    @Override
    public void authorize(Deadline deadline) throws SQLException {
        if (actor == null) throw new SecurityException("外部好友推荐必须绑定原会话");
        read(deadline, c -> null);
    }

    /** 好友的好友：排名窗口内排除，按共同好友数降序、同数使用同一个物化随机键。 */
    @Override
    public List<Candidate> mutual(long me, List<Long> exclude, int limit, Deadline deadline) throws SQLException {
        if (actor != null) actor.requirePlayer(me);
        return query(queries.mutual(me, exclude, limit), deadline);
    }

    /**
     * 随机兜底：先取 friend 表 player_id 的真实 MIN / MAX（雪花号集中在高位，直接随机一个 uint64 几乎必然落在最大号之后），
     * 在 [min, max] 里随机一个 pivot，再在 pivot 起的窗口里按升序取前 {@code limit} 个合格者。空表（NULL）或 max == 0 → 空，不是错误。
     */
    @Override
    public List<Candidate> random(long me, List<Long> exclude, int limit, Deadline deadline) throws SQLException {
        if (actor != null) actor.requirePlayer(me);
        long[] range = read(deadline, c -> {
            try (PreparedStatement ps = prepare(c, ID_RANGE, List.of(), deadline); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Object min = rs.getObject(1);
                Object max = rs.getObject(2);
                return min == null || max == null ? null : new long[] {toLong(min), toLong(max)};
            }
        });
        if (range == null) return List.of();
        long lo = range[0];
        long hi = range[1];
        if (hi == 0) {
            return List.of();
        }
        long pivot = lo;
        if (Long.compareUnsigned(hi, lo) > 0) {
            long span = hi - lo + 1; // 无符号宽度；溢出为 0 时退回 lo（雪花号差值远小于 2^63，现实中到不了）
            if (span != 0) {
                pivot = lo + pivotPicker.applyAsLong(lo, span);
            }
        }
        return anchor(me, exclude, pivot, limit, deadline);
    }

    /** 参数：me（返回行共同数）→ pivot → 窗口 W → me × 6 → exclude… → limit。 */
    List<Candidate> anchor(long me, List<Long> exclude, long pivot, int limit, Deadline deadline) throws SQLException {
        if (actor != null) actor.requirePlayer(me);
        return query(queries.anchor(me, exclude, pivot, limit), deadline);
    }

    private List<Candidate> query(RecommendSql.Query query, Deadline deadline) throws SQLException {
        return read(deadline, c -> {
            try (PreparedStatement ps = prepare(c, query.sql(), query.arguments(), deadline); ResultSet rs = ps.executeQuery()) {
                List<Candidate> out = new ArrayList<>();
                while (rs.next()) {
                    FriendActor.timeout(deadline, queryTimeoutSeconds);
                    out.add(new Candidate(JdbcFriendStore.readUid(rs, 1), (int) rs.getLong(2)));
                }
                return out;
            }
        });
    }

    @FunctionalInterface
    private interface ReadBody<T> {
        T run(Connection connection) throws SQLException;
    }

    /** 推荐可以陈旧，原会话权限不能陈旧：读取关系的整个语句都在 P 锁内完成。 */
    private <T> T read(Deadline deadline, ReadBody<T> body) throws SQLException {
        FriendActor.timeout(deadline, queryTimeoutSeconds);
        try (Connection c = dataSource.getConnection()) {
            if (actor == null) {
                T result = body.run(c);
                FriendActor.timeout(deadline, queryTimeoutSeconds);
                return result;
            }
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                actor.lock(c, deadline, queryTimeoutSeconds);
                T result = body.run(c);
                FriendActor.timeout(deadline, queryTimeoutSeconds);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                // 失败路径已回滚，才可还原自动提交。
                if (c.getAutoCommit() != autoCommit) c.setAutoCommit(autoCommit);
                if (c.getTransactionIsolation() != isolation) c.setTransactionIsolation(isolation);
            }
        }
    }

    private PreparedStatement prepare(Connection c, String sql, List<Object> args, Deadline deadline) throws SQLException {
        long remaining = deadline.remainingMillis();
        if (remaining <= 0) {
            throw new SQLException("超过请求预算，不再执行推荐查询");
        }
        int timeout = (int) Math.max(1, (remaining + 999) / 1000);
        if (queryTimeoutSeconds > 0) {
            timeout = Math.min(timeout, queryTimeoutSeconds);
        }
        long serverBudget = queryTimeoutSeconds > 0 ? Math.min(remaining, queryTimeoutSeconds * 1000L) : remaining;
        PreparedStatement ps = c.prepareStatement(RecommendSql.timed(sql, serverBudget));
        try {
            ps.setQueryTimeout(timeout);
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    private static long toLong(Object value) throws SQLException {
        if (value instanceof BigInteger big) {
            return big.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new SQLException("player_id 不是数字: " + value);
    }
}
