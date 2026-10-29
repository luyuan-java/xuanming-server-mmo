package com.game.friend.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 推荐查询的不可变 SQL 规格；两种数据库只改变优化器提示，参数始终由 JDBC 绑定。 */
final class RecommendSql {
    enum Dialect { MYSQL, TIDB }
    record Query(String sql, List<Object> arguments) {
        Query { arguments = List.copyOf(arguments); }
    }

    private final Dialect dialect;
    RecommendSql(Dialect dialect) { this.dialect = java.util.Objects.requireNonNull(dialect); }

    /** 装配时探测一次；请求路径不重复访问 VERSION，也不把未知数据库当作已验证方言。 */
    static Dialect detect(Connection connection, int timeoutSeconds) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.max(1, timeoutSeconds));
            try (var rows = statement.executeQuery("SELECT VERSION()")) {
                if (!rows.next()) throw new SQLException("无法确认推荐查询数据库方言");
                String version = rows.getString(1);
                if (version != null && version.toLowerCase(Locale.ROOT).contains("tidb")) return Dialect.TIDB;
                if (version != null && version.matches("[0-9]+\\.[0-9]+.*")
                        && "MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return Dialect.MYSQL;
                throw new SQLException("推荐查询只支持已确认的 MySQL/TiDB 方言");
            }
        }
    }

    Query mutual(long me, List<Long> exclude, int limit) {
        String sql = "SELECT " + outer(true) + "c.candidate_id, c.mutual\n"
                + "FROM (SELECT " + (dialect == Dialect.TIDB ? "/*+ QB_NAME(qb_fof) */ " : "") + """
                f2.friend_player_id AS candidate_id, COUNT(*) AS mutual, RAND() AS shuffle
                      FROM friend f1 FORCE INDEX (PRIMARY)
                      STRAIGHT_JOIN friend f2 FORCE INDEX (PRIMARY) ON f2.player_id = f1.friend_player_id
                      WHERE f1.player_id = ? AND f2.friend_player_id <> ?
                      GROUP BY f2.friend_player_id
                      ORDER BY mutual DESC, shuffle
                      LIMIT ?) AS c
                WHERE """ + " " + exclusions("c.candidate_id") + exclude("c.candidate_id", exclude.size())
                + "\nORDER BY c.mutual DESC, c.shuffle\nLIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(JdbcFriendStore.uid(me)); args.add(JdbcFriendStore.uid(me));
        args.add((long) RecommendStore.ANCHOR_WINDOW);
        addActor(args, me, 5); addExcluded(args, exclude); args.add((long) limit);
        return new Query(sql, args);
    }

    Query anchor(long me, List<Long> exclude, long pivot, int limit) {
        String sql = "SELECT " + outer(false) + """
                c.player_id,
                       (SELECT COUNT(*) FROM friend m1 FORCE INDEX (PRIMARY)
                        STRAIGHT_JOIN friend m2 FORCE INDEX (PRIMARY)
                          ON m2.player_id = c.player_id AND m2.friend_player_id = m1.friend_player_id
                        WHERE m1.player_id = ?) AS mutual
                FROM (SELECT DISTINCT player_id FROM friend FORCE INDEX (PRIMARY)
                      WHERE player_id >= ? ORDER BY player_id LIMIT ?) AS c
                WHERE c.player_id <> ? AND """ + " " + exclusions("c.player_id") + exclude("c.player_id", exclude.size())
                + "\nORDER BY c.player_id\nLIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(JdbcFriendStore.uid(me)); args.add(JdbcFriendStore.uid(pivot));
        args.add((long) RecommendStore.ANCHOR_WINDOW);
        addActor(args, me, 6); addExcluded(args, exclude); args.add((long) limit);
        return new Query(sql, args);
    }

    private String outer(boolean mutual) {
        if (dialect == Dialect.MYSQL) return "";
        return "/*+ INL_JOIN(f@qb_f, b_out@qb_b_out, b_in@qb_b_in, r_out@qb_r_out, r_in@qb_r_in)"
                + (mutual ? " INL_JOIN(@qb_fof f2)" : "") + " */ ";
    }

    private String subquery(String alias) {
        return dialect == Dialect.MYSQL ? "/*+ SEMIJOIN(FIRSTMATCH) */ " : "/*+ QB_NAME(qb_" + alias + ") */ ";
    }

    private String exclusions(String candidate) {
        return "NOT EXISTS (SELECT " + subquery("f") + "1 FROM friend f FORCE INDEX (PRIMARY)"
                + " WHERE f.player_id = ? AND f.friend_player_id = " + candidate + ")\n"
                + "  AND NOT EXISTS (SELECT " + subquery("b_out") + "1 FROM friend_block b_out FORCE INDEX (PRIMARY)"
                + " WHERE b_out.player_id = ? AND b_out.blocked_player_id = " + candidate + ")\n"
                + "  AND NOT EXISTS (SELECT " + subquery("b_in") + "1 FROM friend_block b_in FORCE INDEX (PRIMARY)"
                + " WHERE b_in.player_id = " + candidate + " AND b_in.blocked_player_id = ?)\n"
                + "  AND NOT EXISTS (SELECT " + subquery("r_out") + "1 FROM friend_request r_out FORCE INDEX (PRIMARY)"
                + " WHERE r_out.from_player_id = ? AND r_out.to_player_id = " + candidate + " AND r_out.status = 1)\n"
                + "  AND NOT EXISTS (SELECT " + subquery("r_in") + "1 FROM friend_request r_in FORCE INDEX (PRIMARY)"
                + " WHERE r_in.from_player_id = " + candidate + " AND r_in.to_player_id = ? AND r_in.status = 1)";
    }

    private static String exclude(String column, int count) {
        return count == 0 ? "" : "\n  AND " + column + " NOT IN (" + String.join(",", Collections.nCopies(count, "?")) + ")";
    }
    private static void addActor(List<Object> args, long me, int count) {
        for (int i = 0; i < count; i++) args.add(JdbcFriendStore.uid(me));
    }
    private static void addExcluded(List<Object> args, List<Long> excluded) {
        excluded.forEach(id -> args.add(JdbcFriendStore.uid(id)));
    }

    /** 服务器也受毫秒预算限制，避免 JDBC 超时后服务器仍执行退化查询；不会生成第二个互相覆盖的外层提示。 */
    static String timed(String sql, long millis) {
        if (millis <= 0) throw new IllegalArgumentException("推荐语句预算必须为正");
        String hint = "MAX_EXECUTION_TIME(" + millis + ") ";
        return sql.startsWith("SELECT /*+ ") ? "SELECT /*+ " + hint + sql.substring(11)
                : "SELECT /*+ " + hint + "*/ " + sql.substring(7);
    }
}
