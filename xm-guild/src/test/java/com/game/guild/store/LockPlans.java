package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 锁定语句执行计划回归的公共助手（照 mmorpg economy_lock_plan_mysql_test.go:230-319 的 lockPlanExplain / lockPlanInline /
 * lockPlanPrimaryKeyLen）：对<b>生产代码里的 SQL 常量本身</b>跑 EXPLAIN，断言 {@code key = PRIMARY}、{@code key_len} = 主键全部列的字节和、
 * access type 在允许集合内。锁集由执行计划决定，不由 SQL 文本决定。
 */
public final class LockPlans {

    private static final Pattern SAFE_STRING = Pattern.compile("^[A-Za-z0-9_-]*$");
    private static final Map<String, Integer> INTEGER_WIDTH = Map.of("tinyint", 1, "smallint", 2, "mediumint", 3, "int", 4,
            "bigint", 8);

    /** SELECT … FOR UPDATE 的 access type：const。 */
    public static final List<String> LOCK_READ = List.of("const");
    /** 按主键的 UPDATE / DELETE：MySQL 单表写走范围优化器显示 range（rows = 1），改走 join 优化器则显示 const。 */
    public static final List<String> POINT_WRITE = List.of("range", "const");

    /**
     * 一条被核对的生产 SQL。
     *
     * @param types null = 不断言 access type（非锁定读）
     */
    public record PlanCase(String name, String sql, List<Object> args, String table, List<String> types) {
    }

    private LockPlans() {
    }

    /** 逐条 EXPLAIN 并断言（夹具行必须存在且满足语句里的其余条件，否则 table / key 都是 NULL，用例因夹具而红）。 */
    public static void assertPrimaryKeyPointOperations(GuildMysqlFixture db, List<PlanCase> cases) throws SQLException {
        Map<String, String> keyLen = new HashMap<>();
        for (PlanCase c : cases) {
            if (!keyLen.containsKey(c.table())) {
                keyLen.put(c.table(), primaryKeyLen(db, c.table()));
            }
        }
        for (PlanCase c : cases) {
            String stmt = inline(c.sql(), c.args());
            Map<String, String> plan = explain(db, stmt);
            assertThat(plan.get("table")).as("%s 的计划行不是 %s（Extra=%s）：夹具行缺失或参数与夹具对不上。SQL: %s",
                    c.name(), c.table(), plan.get("Extra"), stmt).isEqualTo(c.table());
            assertThat(plan.get("key")).as("%s 没有走主键（possible_keys=%s type=%s）：走二级索引就是「先二级后主键」，与按主键删行的事务"
                    + "反序成环。SQL: %s", c.name(), plan.get("possible_keys"), plan.get("type"), stmt).isEqualTo("PRIMARY");
            assertThat(plan.get("key_len")).as("%s 没有用满主键全部列。SQL: %s", c.name(), stmt).isEqualTo(keyLen.get(c.table()));
            if (c.types() != null) {
                assertThat(plan.get("type")).as("%s 的 access type。SQL: %s", c.name(), stmt).isIn(c.types().toArray());
            }
        }
    }

    /** 把 ? 依次替换成字面量（只接受整数与字母数字短串；Long 按无符号印）。 */
    public static String inline(String sql, List<Object> args) {
        assertThat(sql.chars().filter(ch -> ch == '?').count()).as("占位符与参数个数: %s", sql).isEqualTo(args.size());
        StringBuilder out = new StringBuilder();
        int argIndex = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch != '?') {
                out.append(ch);
                continue;
            }
            Object arg = args.get(argIndex++);
            if (arg instanceof Long l) {
                out.append(Long.toUnsignedString(l));
            } else if (arg instanceof Integer n) {
                out.append(Integer.toUnsignedString(n));
            } else if (arg instanceof String s && SAFE_STRING.matcher(s).matches()) {
                out.append('\'').append(s).append('\'');
            } else {
                throw new AssertionError("inline 不接受 " + arg);
            }
        }
        return out.toString();
    }

    /** EXPLAIN FORMAT=TRADITIONAL 的第一行（被测语句都是单表）。 */
    public static Map<String, String> explain(GuildMysqlFixture db, String stmt) throws SQLException {
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

    /** 「用满主键全部列」时的 key_len：逐列按整数类型取定长，可空列再加 1 字节。 */
    public static String primaryKeyLen(GuildMysqlFixture db, String table) throws SQLException {
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
