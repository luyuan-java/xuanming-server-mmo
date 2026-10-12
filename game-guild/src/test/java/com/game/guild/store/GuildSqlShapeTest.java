package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.guild.rules.GuildLimits;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SQL 常量的静态形状（不连库，基线 guild_lock_plan_mysql_test.go:137-190 TestGuildLockingStatementShapes）：EXPLAIN 回归只能在有库时跑，
 * 这一层保证「改 SQL 时手滑」在任何环境都红——guild_member 的锁定 SELECT / UPDATE 带 FORCE INDEX (PRIMARY)；锁定读与点写 / 点删按完整
 * 主键定位；候选读与计数读不带任何锁定子句。
 */
class GuildSqlShapeTest {

    @Test
    void 成员行的锁定读与改role强制走主键() {
        for (String sql : new String[] {JdbcGuildStore.LOCK_MEMBER_ROLE, JdbcGuildStore.UPDATE_MEMBER_ROLE}) {
            assertThat(sql).contains("guild_member FORCE INDEX (PRIMARY)");
        }
    }

    @Test
    void 锁定读与点写按完整主键定位() {
        Map<String, String> fullPrimaryKey = Map.of(
                "LOCK_MEMBER_ROLE", JdbcGuildStore.LOCK_MEMBER_ROLE,
                "UPDATE_MEMBER_ROLE", JdbcGuildStore.UPDATE_MEMBER_ROLE,
                "DELETE_MEMBER", JdbcGuildStore.DELETE_MEMBER,
                "LOCK_APPLICATION", JdbcGuildStore.LOCK_APPLICATION,
                "REFRESH_APPLICATION", JdbcGuildStore.REFRESH_APPLICATION,
                "DELETE_APPLICATION", JdbcGuildStore.DELETE_APPLICATION,
                "DELETE_EXPIRED_APPLICATION", JdbcGuildStore.DELETE_EXPIRED_APPLICATION,
                "CANCEL_APPLICATION", JdbcGuildStore.CANCEL_APPLICATION);
        fullPrimaryKey.forEach((name, sql) -> assertThat(sql).as(name).contains("WHERE guild_id = ? AND player_id = ?"));
        assertThat(JdbcGuildStore.LOCK_GUILD).contains("WHERE guild_id = ? FOR UPDATE");
        assertThat(JdbcGuildStore.DELETE_GUILD).endsWith("WHERE guild_id = ?");
        assertThat(JdbcGuildStore.UPDATE_LEADER).endsWith("WHERE guild_id = ?");
        assertThat(JdbcGuildStore.UPDATE_ANNOUNCEMENT).endsWith("WHERE guild_id = ?");
        assertThat(JdbcGuildStore.LOCK_PLAYER_STATE).contains("WHERE player_id = ? FOR UPDATE");
        // A1 是带复核点删之前的点锁：只许完整主键等值 + FOR UPDATE，不带复核条件（TiDB 才走 Point_Get 快路径）
        assertThat(JdbcGuildStore.LOCK_APPLICATION).endsWith("WHERE guild_id = ? AND player_id = ? FOR UPDATE");
    }

    @Test
    void 申请插入直接取X_清理候选带LIMIT() {
        // 申请插入查重必须直接取 X（IODKU），不许退回普通 INSERT 的「先 S 后 X」升级
        assertThat(JdbcGuildStore.INSERT_APPLICATION).endsWith("ON DUPLICATE KEY UPDATE apply_ms = apply_ms");
        // 申请后顺带清理的候选读由调用方传上限，不许写死一个大数拖长回包
        assertThat(JdbcGuildStore.SELECT_EXPIRED_APPLICANTS_OF_GUILD).endsWith("ORDER BY player_id LIMIT ?");
        assertThat(GuildLimits.PURGE_EXPIRED_APPLICATIONS_PER_APPLY).isEqualTo(10);
        // 建帮的 guild 行与成员行写 0 帮贡 / 0 分 / 0 资金
        assertThat(JdbcGuildStore.INSERT_GUILD).endsWith("VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)");
        assertThat(JdbcGuildStore.INSERT_MEMBER).endsWith("VALUES (?, ?, ?, ?, ?, 0, 0)");
    }

    @Test
    void 候选读与计数读不加锁() {
        Map<String, String> plainReads = Map.ofEntries(
                Map.entry("S3", JdbcGuildStore.SELECT_EXISTING_PLAYER_STATES_HEAD + JdbcGuildStore.placeholders(2) + ")"),
                Map.entry("A8", JdbcGuildStore.SELECT_APPLICATION_KEYS_OF_PLAYERS_HEAD + JdbcGuildStore.placeholders(2) + ")"),
                Map.entry("A9", JdbcGuildStore.SELECT_APPLICANTS_OF_GUILD),
                Map.entry("A10", JdbcGuildStore.SELECT_EXPIRED_APPLICATIONS_OF_PLAYER),
                Map.entry("A11", JdbcGuildStore.SELECT_EXPIRED_APPLICANTS_OF_GUILD),
                Map.entry("A12", JdbcGuildStore.COUNT_PENDING_APPLICATIONS_OF_PLAYER),
                Map.entry("A13", JdbcGuildStore.COUNT_LIVE_APPLICATIONS_OF_GUILD),
                Map.entry("A7", JdbcGuildStore.SELECT_APPLICATION_EXISTS),
                Map.entry("M10", JdbcGuildStore.SELECT_MEMBER_IDS),
                Map.entry("M5", JdbcGuildStore.SELECT_MEMBER_GUILD),
                Map.entry("M2", JdbcGuildStore.COUNT_MEMBERS),
                Map.entry("M3", JdbcGuildStore.COUNT_MEMBERS_BY_ROLE),
                Map.entry("M4", JdbcGuildStore.SELECT_MEMBER_ROLE),
                Map.entry("M6", JdbcGuildStore.SELECT_REVIEWERS),
                Map.entry("G8", JdbcGuildStore.SELECT_GUILD_ZONE),
                Map.entry("G9", JdbcGuildStore.SELECT_GUILD),
                Map.entry("G10", JdbcGuildStore.SELECT_ALL_SCORES),
                Map.entry("M11", JdbcGuildStore.SELECT_MEMBERS),
                Map.entry("A14", JdbcGuildStore.LIST_MY_APPLICATIONS),
                Map.entry("A15", JdbcGuildStore.LIST_APPLICANTS));
        plainReads.forEach((name, sql) -> {
            String upper = sql.toUpperCase(Locale.ROOT);
            for (String clause : new String[] {"FOR UPDATE", "FOR SHARE", "LOCK IN SHARE MODE"}) {
                assertThat(upper).as("%s 是候选 / 计数普通读，不许加锁: %s", name, sql).doesNotContain(clause);
            }
        });
    }

    @Test
    void 有效申请的判据I1在计数与列表里同口径() {
        String filter = "LEFT JOIN guild_member m ON m.player_id = a.player_id"
                + " WHERE a.guild_id = ? AND a.expire_ms > ? AND m.player_id IS NULL";
        assertThat(JdbcGuildStore.COUNT_LIVE_APPLICATIONS_OF_GUILD).contains(filter);
        assertThat(JdbcGuildStore.LIST_APPLICANTS).contains(filter).endsWith("ORDER BY a.apply_ms ASC, a.player_id ASC LIMIT ?");
        assertThat(JdbcGuildStore.LIST_MY_APPLICATIONS).endsWith("ORDER BY apply_ms DESC, guild_id ASC LIMIT ?");
    }
}
