package com.game.friend.store;

import com.game.common.deadline.Deadline;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongBinaryOperator;
import javax.sql.DataSource;

/**
 * 好友推荐的 SQL 侧（mmorpg go/friend internal/data/recommend_repo.go，friend-spec.md §5.1）。两条 SQL <b>逐字照搬</b>，
 * 包括优化器提示与字面量 {@code status = 1}，每一处写法都是承重的：
 * <ul>
 *   <li>{@code STRAIGHT_JOIN} 钉住「先 f1（我的好友）后 f2（好友的好友）」——统计陈旧时优化器会翻成 f2 全索引扫描驱动；</li>
 *   <li>五条 NOT EXISTS 按方向拆开、每条都是完整主键等值；{@code SEMIJOIN(FIRSTMATCH)} 承重、{@code FORCE INDEX (PRIMARY)} 是防线，
 *       一起把每条排除钉成「每个候选一次主键单行点查」，扫描量与全服 pending 数、「拉黑我的人数」无关；</li>
 *   <li>外层列一律写别名（{@code f2.friend_player_id} / {@code c.player_id}）：裸列名会先解析到子查询自己的表上，排除静默失效；</li>
 *   <li>随机兜底的派生表 LIMIT（窗口 1024 个去重玩家）就是扫描上界本身，不回绕、不越窗：结果可能少于 want（可能为空）。</li>
 * </ul>
 * <b>纯读</b>：不进事务、不加锁，结果允许轻微陈旧；绝不能被复用成 AddFriend 的前置判定。候选池只有「至少有一条好友边的玩家」。
 * 这些写法 H2 不支持，测试连真 MySQL。线程安全；阻塞。
 */
public final class RecommendStore implements RecommendSource {

    /** 一条推荐候选；随机兜底的 {@code mutualFriends} 恒为 0。 */
    public record Candidate(long playerId, int mutualFriends) {
    }

    /** 随机兜底一次最多检视的候选池玩家数（pivot 起按 player_id 升序的前 W 个去重 id；预算账见 spec §5.1）。 */
    public static final int ANCHOR_WINDOW = 1024;

    static final String MUTUAL_BASE = """
            SELECT f2.friend_player_id, COUNT(*) AS mutual
            FROM friend f1 FORCE INDEX (PRIMARY)
            STRAIGHT_JOIN friend f2 FORCE INDEX (PRIMARY) ON f2.player_id = f1.friend_player_id
            WHERE f1.player_id = ?
              AND f2.friend_player_id <> ?
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend f FORCE INDEX (PRIMARY)
                    WHERE f.player_id = ? AND f.friend_player_id = f2.friend_player_id)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_block b_out FORCE INDEX (PRIMARY)
                    WHERE b_out.player_id = ? AND b_out.blocked_player_id = f2.friend_player_id)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_block b_in FORCE INDEX (PRIMARY)
                    WHERE b_in.player_id = f2.friend_player_id AND b_in.blocked_player_id = ?)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_request r_out FORCE INDEX (PRIMARY)
                    WHERE r_out.from_player_id = ? AND r_out.to_player_id = f2.friend_player_id AND r_out.status = 1)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_request r_in FORCE INDEX (PRIMARY)
                    WHERE r_in.from_player_id = f2.friend_player_id AND r_in.to_player_id = ? AND r_in.status = 1)""";

    static final String ANCHOR_BASE = """
            SELECT c.player_id, 0 AS mutual
            FROM (SELECT DISTINCT player_id FROM friend FORCE INDEX (PRIMARY)
                  WHERE player_id >= ?
                  ORDER BY player_id
                  LIMIT ?) AS c
            WHERE c.player_id <> ?
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend f FORCE INDEX (PRIMARY)
                    WHERE f.player_id = ? AND f.friend_player_id = c.player_id)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_block b_out FORCE INDEX (PRIMARY)
                    WHERE b_out.player_id = ? AND b_out.blocked_player_id = c.player_id)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_block b_in FORCE INDEX (PRIMARY)
                    WHERE b_in.player_id = c.player_id AND b_in.blocked_player_id = ?)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_request r_out FORCE INDEX (PRIMARY)
                    WHERE r_out.from_player_id = ? AND r_out.to_player_id = c.player_id AND r_out.status = 1)
              AND NOT EXISTS (SELECT /*+ SEMIJOIN(FIRSTMATCH) */ 1 FROM friend_request r_in FORCE INDEX (PRIMARY)
                    WHERE r_in.from_player_id = c.player_id AND r_in.to_player_id = ? AND r_in.status = 1)""";

    static final String ID_RANGE = "SELECT MIN(player_id), MAX(player_id) FROM friend";

    private final DataSource dataSource;
    private final int queryTimeoutSeconds;
    /** {@code (lo, span) -> lo + [0, span)} 的随机 pivot（测试注入固定值）；span 按无符号解释。 */
    private final LongBinaryOperator pivotPicker;

    public RecommendStore(DataSource dataSource, int queryTimeoutSeconds, LongBinaryOperator pivotPicker) {
        this.dataSource = dataSource;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.pivotPicker = pivotPicker;
    }

    /** 好友的好友：按共同好友数降序、同数随机，至多 {@code limit} 个。参数：me × 7 → exclude… → limit。 */
    @Override
    public List<Candidate> mutual(long me, List<Long> exclude, int limit, Deadline deadline) throws SQLException {
        String sql = MUTUAL_BASE + excludeClause("f2.friend_player_id", exclude.size())
                + "\nGROUP BY f2.friend_player_id\nORDER BY mutual DESC, RAND()\nLIMIT ?";
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            args.add(JdbcFriendStore.uid(me));
        }
        exclude.forEach(id -> args.add(JdbcFriendStore.uid(id)));
        args.add((long) limit);
        return query(sql, args, deadline);
    }

    /**
     * 随机兜底：先取 friend 表 player_id 的真实 MIN / MAX（雪花号集中在高位，直接随机一个 uint64 几乎必然落在最大号之后），
     * 在 [min, max] 里随机一个 pivot，再在 pivot 起的窗口里按升序取前 {@code limit} 个合格者。空表（NULL）或 max == 0 → 空，不是错误。
     */
    @Override
    public List<Candidate> random(long me, List<Long> exclude, int limit, Deadline deadline) throws SQLException {
        long lo;
        long hi;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = prepare(c, ID_RANGE, List.of(), deadline);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return List.of();
            }
            Object min = rs.getObject(1);
            Object max = rs.getObject(2);
            if (min == null || max == null) {
                return List.of();
            }
            lo = toLong(min);
            hi = toLong(max);
        }
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

    /** 参数：pivot → 窗口 W → me × 6 → exclude… → limit。 */
    List<Candidate> anchor(long me, List<Long> exclude, long pivot, int limit, Deadline deadline) throws SQLException {
        String sql = ANCHOR_BASE + excludeClause("c.player_id", exclude.size()) + "\nORDER BY c.player_id\nLIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(JdbcFriendStore.uid(pivot));
        args.add((long) ANCHOR_WINDOW);
        for (int i = 0; i < 6; i++) {
            args.add(JdbcFriendStore.uid(me));
        }
        exclude.forEach(id -> args.add(JdbcFriendStore.uid(id)));
        args.add((long) limit);
        return query(sql, args, deadline);
    }

    /** exclude 为空时不拼（空的 {@code NOT IN ()} 是语法错误）；条数已由上层按 RecommendMaxExclude 封顶。 */
    static String excludeClause(String column, int count) {
        if (count == 0) {
            return "";
        }
        return "\n  AND " + column + " NOT IN (" + String.join(",", Collections.nCopies(count, "?")) + ")";
    }

    private List<Candidate> query(String sql, List<Object> args, Deadline deadline) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = prepare(c, sql, args, deadline);
             ResultSet rs = ps.executeQuery()) {
            List<Candidate> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Candidate(JdbcFriendStore.readUid(rs, 1), (int) rs.getLong(2)));
            }
            return out;
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
        PreparedStatement ps = c.prepareStatement(sql);
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
