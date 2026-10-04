package com.game.guild.store;

import static com.game.guild.store.GuildMysqlFixture.NOW;
import static com.game.guild.store.GuildMysqlFixture.TTL;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.guild.rules.GuildRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 锁定语句的执行计划回归（真库，缺省跳过；移植 mmorpg guild_lock_plan_mysql_test.go:55-135，guild-spec §1.9「执行计划回归」）。
 *
 * <p>锁集由执行计划决定，不由 SQL 文本决定。这里对<b>生产代码里的 SQL 常量本身</b>跑 EXPLAIN，钉住计划确实是主键：
 * {@code key = PRIMARY}、{@code key_len} 等于主键全部列的字节和（guild 8、guild_player_state 8、guild_member 16、guild_application 16，
 * 从 INFORMATION_SCHEMA 现算）；SELECT … FOR UPDATE 的 type 为 const，按主键的 UPDATE / DELETE 为 range 或 const。提示子句被误删、
 * 某个版本不认它、或单表 DELETE（不收索引提示）被规划到二级索引上，这里都会红。夹具只有被查的那几行（小表是最坏情况），
 * 每条语句的参数与夹具行逐列对得上（const 判定在优化期就去读这一行，读不到时 table / key 都是 NULL）。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class GuildLockPlanMysqlTest {

    private static final long GUILD = 7901;
    private static final long LEADER = 8901;
    private static final long MEMBER = 8902;
    private static final long APPLICANT = 8903;
    private static final long EXPIRE = NOW + TTL;
    private static final Pattern SAFE_STRING = Pattern.compile("^[A-Za-z0-9_-]*$");
    private static final Map<String, Integer> INTEGER_WIDTH = Map.of("tinyint", 1, "smallint", 2, "mediumint", 3,
            "int", 4, "bigint", 8);

    private static GuildMysqlFixture db;

    @BeforeAll
    static void seed() throws SQLException {
        db = GuildMysqlFixture.create();
        db.reset();
        db.seedGuild(GUILD, 2, 1, 50, LEADER, Map.of(MEMBER, GuildRoles.MEMBER));
        db.seedState(MEMBER);
        db.seedApplication(GUILD, APPLICANT, NOW, EXPIRE);
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    private record PlanCase(String name, String sql, List<Object> args, String table, List<String> types) {
    }

    @Test
    void 锁定语句都是完整主键的点操作() throws SQLException {
        List<String> lockRead = List.of("const");
        List<String> pointWrite = List.of("range", "const");
        List<PlanCase> cases = List.of(
                // guild（锁序位置 1）
                new PlanCase("G1 LOCK_GUILD", JdbcGuildStore.LOCK_GUILD, List.of(GUILD), "guild", lockRead),
                new PlanCase("G5 UPDATE_ANNOUNCEMENT", JdbcGuildStore.UPDATE_ANNOUNCEMENT, List.of("lock-plan", GUILD), "guild",
                        pointWrite),
                new PlanCase("G2 DELETE_GUILD", JdbcGuildStore.DELETE_GUILD, List.of(GUILD), "guild", pointWrite),
                // guild_player_state（位置 2）：玩家守卫与全局插入守卫（player_id = 0，哨兵行由 reset 按启动顺序建好）
                new PlanCase("S2 LOCK_PLAYER_STATE", JdbcGuildStore.LOCK_PLAYER_STATE, List.of(MEMBER), "guild_player_state",
                        lockRead),
                new PlanCase("S2 LOCK_PLAYER_STATE(全局插入守卫)", JdbcGuildStore.LOCK_PLAYER_STATE,
                        List.of(JdbcGuildStore.GLOBAL_INSERT_GUARD_PLAYER_ID), "guild_player_state", lockRead),
                // guild_member（位置 3）：主键与 uk_guild_member 的列同时被钉死，必须选主键
                new PlanCase("M1 LOCK_MEMBER_ROLE", JdbcGuildStore.LOCK_MEMBER_ROLE, List.of(GUILD, MEMBER), "guild_member",
                        lockRead),
                new PlanCase("M8 UPDATE_MEMBER_ROLE", JdbcGuildStore.UPDATE_MEMBER_ROLE,
                        List.of(GuildRoles.OFFICER, GUILD, MEMBER), "guild_member", pointWrite),
                // 单表 DELETE 不收索引提示，只能靠这条断言钉住它走 PRIMARY
                new PlanCase("M9 DELETE_MEMBER", JdbcGuildStore.DELETE_MEMBER, List.of(GUILD, MEMBER), "guild_member",
                        pointWrite),
                // guild_application（位置 4）：锁定读 / 刷新 / 三种点删
                new PlanCase("A1 LOCK_APPLICATION", JdbcGuildStore.LOCK_APPLICATION, List.of(GUILD, APPLICANT),
                        "guild_application", lockRead),
                new PlanCase("A3 REFRESH_APPLICATION", JdbcGuildStore.REFRESH_APPLICATION,
                        List.of(NOW, EXPIRE, GUILD, APPLICANT), "guild_application", pointWrite),
                new PlanCase("A4 DELETE_APPLICATION", JdbcGuildStore.DELETE_APPLICATION, List.of(GUILD, APPLICANT),
                        "guild_application", pointWrite),
                // expire_ms <= ? 对夹具行成立；最该防的是被规划到 idx_guild_application_1 的 expire_ms 范围上
                new PlanCase("A5 DELETE_EXPIRED_APPLICATION", JdbcGuildStore.DELETE_EXPIRED_APPLICATION,
                        List.of(GUILD, APPLICANT, EXPIRE), "guild_application", pointWrite),
                new PlanCase("A6 CANCEL_APPLICATION", JdbcGuildStore.CANCEL_APPLICATION, List.of(GUILD, APPLICANT, NOW),
                        "guild_application", pointWrite));

        Map<String, String> keyLen = new HashMap<>();
        for (String table : GuildTables.NAMES) {
            keyLen.put(table, primaryKeyLen(table));
        }
        assertThat(keyLen).containsEntry("guild", "8").containsEntry("guild_player_state", "8")
                .containsEntry("guild_member", "16").containsEntry("guild_application", "16");

        for (PlanCase c : cases) {
            String stmt = inline(c.sql(), c.args());
            Map<String, String> plan = explain(stmt);
            assertThat(plan.get("table")).as("%s 的计划行不是 %s（Extra=%s）：夹具行缺失或参数与夹具对不上。SQL: %s",
                    c.name(), c.table(), plan.get("Extra"), stmt).isEqualTo(c.table());
            assertThat(plan.get("key")).as("%s 没有走主键（possible_keys=%s type=%s）。SQL: %s", c.name(),
                    plan.get("possible_keys"), plan.get("type"), stmt).isEqualTo("PRIMARY");
            assertThat(plan.get("key_len")).as("%s 没有用满主键全部列。SQL: %s", c.name(), stmt).isEqualTo(keyLen.get(c.table()));
            assertThat(plan.get("type")).as("%s 的 access type。SQL: %s", c.name(), stmt).isIn(c.types().toArray());
        }
    }

    /** 把 ? 依次替换成字面量（只接受整数与字母数字短串，基线 lockPlanInline）。 */
    private static String inline(String sql, List<Object> args) {
        assertThat(sql.chars().filter(ch -> ch == '?').count()).as("占位符与参数个数: %s", sql).isEqualTo(args.size());
        String out = sql;
        for (Object arg : args) {
            String literal;
            if (arg instanceof Long l) {
                literal = Long.toUnsignedString(l);
            } else if (arg instanceof Integer i) {
                literal = Integer.toUnsignedString(i);
            } else if (arg instanceof String s && SAFE_STRING.matcher(s).matches()) {
                literal = "'" + s + "'";
            } else {
                throw new AssertionError("inline 不接受 " + arg);
            }
            out = out.replaceFirst("\\?", literal);
        }
        return out;
    }

    private static Map<String, String> explain(String stmt) throws SQLException {
        try (Connection c = db.dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN FORMAT=TRADITIONAL " + stmt)) {
            assertThat(rs.next()).as("EXPLAIN 没有返回任何行: %s", stmt).isTrue();
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, String> plan = new HashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                plan.put(meta.getColumnLabel(i), rs.getString(i));
            }
            return plan;
        }
    }

    /** 「用满主键全部列」时的 key_len：逐列按整数类型取定长，可空列再加 1 字节（基线 lockPlanPrimaryKeyLen）。 */
    private static String primaryKeyLen(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection c = db.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS"
                     + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = 'PRIMARY' ORDER BY SEQ_IN_INDEX")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            assertThat(columns).as("%s 没有主键", table).isNotEmpty();
            int total = 0;
            for (String column : columns) {
                try (PreparedStatement col = c.prepareStatement("SELECT DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
                    col.setString(1, table);
                    col.setString(2, column);
                    try (ResultSet rs = col.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        Integer width = INTEGER_WIDTH.get(rs.getString(1).toLowerCase(Locale.ROOT));
                        assertThat(width).as("%s 的主键列 %s 不是整数", table, column).isNotNull();
                        total += width + ("YES".equalsIgnoreCase(rs.getString(2)) ? 1 : 0);
                    }
                }
            }
            return Integer.toString(total);
        }
    }
}
