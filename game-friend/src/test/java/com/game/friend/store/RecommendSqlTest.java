package com.game.friend.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 离线守住参数含义、窗口边界与方言选择；执行计划另由真实数据库条件用例验证。 */
class RecommendSqlTest {
    @Test
    void 共同好友先排名截窗再排除_同一个随机键用于内外排序() {
        for (var dialect : RecommendSql.Dialect.values()) {
            var query = new RecommendSql(dialect).mutual(7, List.of(81L, 82L), 20);
            String sql = query.sql();
            assertThat(sql.indexOf("LIMIT ?) AS c")).isLessThan(sql.indexOf("NOT EXISTS"));
            assertThat(sql).contains("WHERE NOT EXISTS", "ORDER BY mutual DESC, shuffle",
                    "ORDER BY c.mutual DESC, c.shuffle", "c.candidate_id NOT IN (?,?)")
                    .doesNotContain("NOT IN ()", "ORDER BY c.mutual DESC, RAND()");
            assertThat(occurrences(sql, "RAND()")).isEqualTo(1);
            assertThat(query.arguments()).containsExactly(7L, 7L, 1024L, 7L, 7L, 7L, 7L, 7L, 81L, 82L, 20L);
            assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(query.arguments().size());
        }
    }

    @Test
    void 随机仍按锚点顺序_共同好友按候选主键前缀计数_高半区号不变负数() {
        long me = Long.MIN_VALUE + 7;
        long pivot = Long.MIN_VALUE + 100;
        var query = new RecommendSql(RecommendSql.Dialect.MYSQL).anchor(me, List.of(-1L), pivot, 3);
        assertThat(query.sql()).contains("m2.player_id = c.player_id AND m2.friend_player_id = m1.friend_player_id",
                "WHERE m1.player_id = ?", "WHERE c.player_id <> ? AND NOT EXISTS", "ORDER BY c.player_id\nLIMIT ?")
                .doesNotContain("ORDER BY mutual", "0 AS mutual");
        assertThat(query.arguments()).containsExactly(uid(me), uid(pivot), 1024L,
                uid(me), uid(me), uid(me), uid(me), uid(me), uid(me), uid(-1L), 3L);
        assertThat(query.sql().chars().filter(c -> c == '?').count()).isEqualTo(query.arguments().size());
    }

    @Test
    void 两种数据库共享排除语义_只替换可识别的优化器提示() {
        for (boolean mutual : List.of(true, false)) {
            var mysql = new RecommendSql(RecommendSql.Dialect.MYSQL);
            var tidb = new RecommendSql(RecommendSql.Dialect.TIDB);
            String a = (mutual ? mysql.mutual(7, List.of(), 10) : mysql.anchor(7, List.of(), 50, 10)).sql();
            String b = (mutual ? tidb.mutual(7, List.of(), 10) : tidb.anchor(7, List.of(), 50, 10)).sql();
            assertThat(a.replaceAll("/\\*\\+.*?\\*/ ", "")).isEqualTo(b.replaceAll("/\\*\\+.*?\\*/ ", ""));
            assertThat(occurrences(a, "SEMIJOIN(FIRSTMATCH)")).isEqualTo(5);
            assertThat(b).doesNotContain("SEMIJOIN");
            for (String alias : List.of("f", "b_out", "b_in", "r_out", "r_in")) {
                assertThat(b).contains(alias + "@qb_" + alias, "QB_NAME(qb_" + alias + ")");
            }
            if (mutual) assertThat(b).contains("INL_JOIN(@qb_fof f2)", "QB_NAME(qb_fof)");
            assertThat(a).contains("r_out.status = 1", "r_in.status = 1").doesNotContain("NOT IN ()");
        }
    }

    @Test
    void 语句预算合并到唯一外层提示_不覆盖TiDB定位提示() {
        for (var dialect : RecommendSql.Dialect.values()) {
            String sql = new RecommendSql(dialect).mutual(1, List.of(), 20).sql();
            String timed = RecommendSql.timed(sql, 347);
            assertThat(timed).startsWith("SELECT /*+ MAX_EXECUTION_TIME(347) ");
            assertThat(occurrences(timed, "MAX_EXECUTION_TIME")).isEqualTo(1);
            if (dialect == RecommendSql.Dialect.TIDB) assertThat(timed).contains("INL_JOIN(@qb_fof f2)");
        }
        assertThat(RecommendSql.timed(RecommendStore.ID_RANGE, 1000))
                .isEqualTo("SELECT /*+ MAX_EXECUTION_TIME(1000) */ MIN(player_id), MAX(player_id) FROM friend");
        assertThatThrownBy(() -> RecommendSql.timed("SELECT 1", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 启动探测明确数据库_未知数据库拒绝_探测也有超时() throws SQLException {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);
        when(statement.executeQuery("SELECT VERSION()")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("8.0.11-TiDB-v8.5.2", "8.4.1", "2.2.224");
        when(metadata.getDatabaseProductName()).thenReturn("MySQL", "H2");
        assertThat(RecommendSql.detect(connection, 2)).isEqualTo(RecommendSql.Dialect.TIDB);
        assertThat(RecommendSql.detect(connection, 2)).isEqualTo(RecommendSql.Dialect.MYSQL);
        assertThatThrownBy(() -> RecommendSql.detect(connection, 2)).isInstanceOf(SQLException.class);
        verify(statement, org.mockito.Mockito.times(3)).setQueryTimeout(2);
    }

    private static BigInteger uid(long id) { return new BigInteger(Long.toUnsignedString(id)); }
    private static int occurrences(String source, String token) {
        return (source.length() - source.replace(token, "").length()) / token.length();
    }
}
