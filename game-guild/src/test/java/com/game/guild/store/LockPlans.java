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

    /** 只读服务端实际版本；不按 URL、环境变量或 MySQL 兼容前缀猜后端。 */
    public static boolean isTiDb(GuildMysqlFixture db) throws SQLException {
        try (Connection c = db.dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT VERSION()")) {
            assertThat(rs.next()).isTrue();
            String version = rs.getString(1);
            assertThat(ServerVersion.rejection(version)).isNull();
            return version.toLowerCase(Locale.ROOT).contains("tidb");
        }
    }

    /** TiDB 直接读取原生产 SQL 的全部计划节点，不伪造 MySQL 的 key/type/key_len 列。 */
    private static void assertTiDbPointOperation(GuildMysqlFixture db, PlanCase planCase) throws SQLException {
        List<String> primary = new ArrayList<>();
        String pkType;
        try (Connection c = db.dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS"
                     + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX")) {
            ps.setString(1, planCase.table());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) primary.add(rs.getString(1)); }
            assertThat(primary).isNotEmpty();
            try (PreparedStatement table = c.prepareStatement("SELECT TIDB_PK_TYPE FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                table.setString(1, planCase.table());
                try (ResultSet rs = table.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    pkType = rs.getString(1);
                    assertThat(rs.next()).isFalse();
                }
            }
        }
        String statement = inline(planCase.sql(), planCase.args());
        List<Map<String, String>> nodes = new ArrayList<>();
        try (Connection c = db.dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN FORMAT=TRADITIONAL " + statement)) {
            while (rs.next()) {
                Map<String, String> row = new HashMap<>();
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getString(i));
                }
                nodes.add(row);
            }
        }
        assertThat(nodes).as("%s 的 TiDB 真实计划", planCase.name()).isNotEmpty();
        String primaryIndex = "index:PRIMARY(" + String.join(",", primary) + ")";
        String clusteredIndex = "clustered " + primaryIndex;
        String tableOnly = "table:" + planCase.table();
        int keyAccesses = 0;
        int rowIdLookups = 0;
        boolean primaryIndexRange = false;
        boolean indexLookup = false;
        for (Map<String, String> node : nodes) {
            String id = node.get("id");
            assertThat(id).isNotNull();
            // 树形缩进只用于显示；算子名称严格从 TiDB 的 id 后缀前读取。
            String operator = id.replaceFirst("^[^A-Za-z]*", "").replaceFirst("_[0-9]+(?:\\(Build\\)|\\(Probe\\))?$", "");
            String access = node.get("access object");
            String info = node.get("operator info");
            assertThat(access).as("%s 缺 access object", planCase.name()).isNotNull();
            assertThat(info).as("%s 缺 operator info", planCase.name()).isNotNull();
            access = access.replace(", ", ",");
            switch (operator) {
                case "Point_Get" -> {
                    keyAccesses++;
                    if (access.equals(tableOnly)) {
                        assertThat(pkType).isEqualTo("CLUSTERED");
                        assertThat(primary).hasSize(1);
                        assertThat(info).contains("handle:");
                    } else {
                        assertThat(access).isIn(tableOnly + "," + primaryIndex, tableOnly + "," + clusteredIndex);
                    }
                }
                case "TableRangeScan" -> {
                    keyAccesses++;
                    assertThat(access).isEqualTo(tableOnly);
                    assertThat(pkType).isEqualTo("CLUSTERED");
                    assertCompletePointRange(info, primary.size(), planCase.name());
                }
                case "IndexRangeScan" -> {
                    keyAccesses++;
                    primaryIndexRange = true;
                    assertThat(access).isIn(tableOnly + "," + primaryIndex, tableOnly + "," + clusteredIndex);
                    assertCompletePointRange(info, primary.size(), planCase.name());
                }
                case "TableRowIDScan" -> {
                    rowIdLookups++;
                    assertThat(access).isEqualTo(tableOnly);
                }
                case "IndexLookUp" -> {
                    indexLookup = true;
                    assertThat(access).isEmpty();
                }
                case "Projection", "Selection", "Update", "Delete", "TableReader", "SelectLock" ->
                    assertThat(access).isEmpty();
                default -> throw new AssertionError(planCase.name() + " 出现未证明为完整主键点操作的 TiDB 算子：" + operator);
            }
        }
        assertThat(keyAccesses).as("%s 必须恰好一个完整主键点入口", planCase.name()).isEqualTo(1);
        if (primaryIndexRange) {
            assertThat(indexLookup).isTrue();
            assertThat(rowIdLookups).isEqualTo(1);
        } else {
            assertThat(indexLookup).isFalse();
            assertThat(rowIdLookups).isZero();
        }
    }

    /** TiDB Range.String 的闭区间：上下界全列逐值相等；未知/无界/前缀/多区间一律红。 */
    private static void assertCompletePointRange(String info, int keyColumns, String name) {
        var matcher = Pattern.compile("^range:\\[(-?\\d+(?: -?\\d+)*),(-?\\d+(?: -?\\d+)*)], (.+)$").matcher(info);
        assertThat(matcher.matches()).as("%s 不是单个整数闭区间：%s", name, info).isTrue();
        assertThat(matcher.group(1)).as("%s 不是等值点区间", name).isEqualTo(matcher.group(2));
        assertThat(matcher.group(1).split(" ")).as("%s 区间没有用满主键列", name).hasSize(keyColumns);
        assertThat(matcher.group(3)).doesNotContain("range:");
    }

    /** 逐条 EXPLAIN 并断言（夹具行必须存在且满足语句里的其余条件，否则 table / key 都是 NULL，用例因夹具而红）。 */
    public static void assertPrimaryKeyPointOperations(GuildMysqlFixture db, List<PlanCase> cases) throws SQLException {
        Map<String, String> keyLen = new HashMap<>();
        for (PlanCase c : cases) {
            if (!keyLen.containsKey(c.table())) {
                keyLen.put(c.table(), primaryKeyLen(db, c.table()));
            }
        }
        if (isTiDb(db)) {
            for (PlanCase c : cases) assertTiDbPointOperation(db, c);
            return;
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
