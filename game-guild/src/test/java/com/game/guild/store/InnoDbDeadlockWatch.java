package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * InnoDB 自己记下的「最近一次死锁」（SHOW ENGINE INNODB STATUS 的 LATEST DETECTED DEADLOCK 一段；照 mmorpg economy_repo_test.go:559-638
 * econDeadlockWatch）：它不经过任何一层重试，是唯一看得见被吸收掉的 1213（含夹具里裸 JDBC 占锁事务那一侧）的地方。
 *
 * <p>与基线的差异：本机 MySQL 实例上还有别的模块的测试在并发跑，「最近一次死锁」可能被别库覆盖——基线在这种情况下判红（宁红勿绿），这里只在
 * 新现场<b>出现本测试库名</b>时判红，否则只记一行日志；判据的主干仍是事务基座的重跑记录器（GuildTx / BackgroundTx 的 Listener）。
 * 读不到（缺 PROCESS 权限）时直接红，不许退化成跳过。
 */
public final class InnoDbDeadlockWatch {

    private static final String HEADER = "LATEST DETECTED DEADLOCK";

    private final GuildMysqlFixture db;
    private final DataSource diagnostics;
    private final String before;
    private final TiDbSnapshot beforeTiDb;

    private InnoDbDeadlockWatch(GuildMysqlFixture db, DataSource diagnostics, String before) {
        this.db = db;
        this.diagnostics = diagnostics;
        this.before = before;
        this.beforeTiDb = null;
    }

    /** 诊断源由夹具显式注入，版本识别和业务事务仍使用普通连接。 */
    public static InnoDbDeadlockWatch start(GuildMysqlFixture db, DataSource diagnostics) throws SQLException {
        try (Connection c = db.dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT VERSION()")) {
            assertThat(rs.next()).isTrue();
            String version = rs.getString(1);
            assertThat(ServerVersion.rejection(version)).isNull();
            if (version.toLowerCase(java.util.Locale.ROOT).contains("tidb")) {
                return new InnoDbDeadlockWatch(db, diagnostics, latestTiDb(diagnostics));
            }
        }
        return new InnoDbDeadlockWatch(db, diagnostics, latest(diagnostics));
    }

    /** 全部并发结束之后在主线程调用。 */
    public void assertNone(String what) throws SQLException {
        if (beforeTiDb != null) {
            assertNoTiDbDeadlock(what);
            return;
        }
        String after = latest(diagnostics);
        if (after.equals(before)) {
            return;
        }
        assertThat(after).as("%s：本测试库 %s 出现了新的 InnoDB 死锁（1213，已被重试吸收，返回值看不出来）。现场：%n%s", what,
                db.database, after).doesNotContain("`" + db.database + "`");
        System.out.println("[InnoDbDeadlockWatch] " + what + "：期间同一实例记录了别库的死锁（不判红）");
    }

    private record TiDbDeadlock(String event, String occurred, String waiter, String holder, int retryable,
                               String database, String table) {
    }

    private record TiDbSnapshot(String instance, int capacity, java.util.Set<TiDbDeadlock> rows) {
    }

    private InnoDbDeadlockWatch(GuildMysqlFixture db, DataSource diagnostics, TiDbSnapshot beforeTiDb) {
        this.db = db;
        this.diagnostics = diagnostics;
        this.before = "";
        this.beforeTiDb = beforeTiDb;
    }

    /** 沿真实后端读取原生历史；配置缺失、采集关闭或历史窗口已满都不能冒充「没有死锁」。 */
    private static TiDbSnapshot latestTiDb(DataSource diagnostics) throws SQLException {
        java.util.Map<String, String> config = new java.util.HashMap<>();
        String instance = null;
        try (Connection c = diagnostics.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT INSTANCE, `KEY`, VALUE FROM INFORMATION_SCHEMA.CLUSTER_CONFIG"
                     + " WHERE TYPE='tidb' AND `KEY` IN ('pessimistic-txn.deadlock-history-capacity',"
                     + "'pessimistic-txn.deadlock-history-collect-retryable')")) {
            while (rs.next()) {
                if (instance == null) instance = rs.getString(1);
                assertThat(rs.getString(1)).as("本夹具只支持已核的单 TiDB 实例").isEqualTo(instance);
                assertThat(config.put(rs.getString(2), rs.getString(3))).as("配置项不能重复").isNull();
            }
        }
        assertThat(instance).isNotBlank();
        assertThat(config).hasSize(2);
        assertThat(config.get("pessimistic-txn.deadlock-history-collect-retryable"))
                .as("要覆盖被 TiDB 内部吸收的死锁，须先单独批准并实际启用本验收实例的采集；测试不会修改配置")
                .isEqualToIgnoringCase("true");
        int capacity = Integer.parseInt(config.get("pessimistic-txn.deadlock-history-capacity"));
        assertThat(capacity).as("受控历史容量，关闭或未知配置不能返回空成功").isBetween(1, 128);
        java.util.Set<TiDbDeadlock> rows = new java.util.HashSet<>();
        java.util.Set<String> events = new java.util.HashSet<>();
        // KEY_INFO 仅在 SQL 内取结构字段，不读出原始业务键、SQL 文本或参数。
        try (Connection c = diagnostics.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DEADLOCK_ID, OCCUR_TIME, TRY_LOCK_TRX_ID, TRX_HOLDING_LOCK, RETRYABLE,"
                     + " JSON_UNQUOTE(JSON_EXTRACT(KEY_INFO,'$.db_name')) AS db_name,"
                     + " JSON_UNQUOTE(JSON_EXTRACT(KEY_INFO,'$.table_name')) AS table_name"
                     + " FROM INFORMATION_SCHEMA.DEADLOCKS LIMIT 4097")) {
            int rowsSeen = 0;
            while (rs.next()) {
                assertThat(++rowsSeen).as("死锁历史超过有界观察容量，不能证明无丢失").isLessThanOrEqualTo(4096);
                String event = rs.getString(1);
                String occurred = rs.getString(2);
                String database = rs.getString(6);
                String table = rs.getString(7);
                assertThat(event).isNotBlank();
                assertThat(occurred).isNotBlank();
                // 旧随机库已删除时允许基线缺元数据；新增事件必须在下方明确归属，不能按别库放行。
                assertThat(rs.getString(3)).isNotBlank();
                assertThat(rs.getString(4)).isNotBlank();
                int retryable = rs.getInt(5);
                assertThat(retryable).isIn(0, 1);
                rows.add(new TiDbDeadlock(event, occurred, rs.getString(3), rs.getString(4), retryable, database, table));
                events.add(event);
            }
        }
        assertThat(events.size()).as("历史窗口已满，不能排除本测试死锁被逐出；需要独立验收窗口").isLessThan(capacity);
        return new TiDbSnapshot(instance, capacity, java.util.Set.copyOf(rows));
    }

    /** 元数据删除不能改变旧事件身份；不使用非持久的 DEADLOCK_ID 单字段作水位。 */
    private static java.util.List<String> eventIdentity(TiDbDeadlock row) {
        return java.util.List.of(row.event(), row.occurred(), row.waiter(), row.holder(), Integer.toString(row.retryable()));
    }

    private void assertNoTiDbDeadlock(String what) throws SQLException {
        TiDbSnapshot after = latestTiDb(diagnostics);
        assertThat(after.instance()).isEqualTo(beforeTiDb.instance());
        assertThat(after.capacity()).isEqualTo(beforeTiDb.capacity());
        java.util.Set<java.util.List<String>> beforeEvents = beforeTiDb.rows().stream()
                .map(InnoDbDeadlockWatch::eventIdentity).collect(java.util.stream.Collectors.toSet());
        java.util.Set<java.util.List<String>> afterEvents = after.rows().stream()
                .map(InnoDbDeadlockWatch::eventIdentity).collect(java.util.stream.Collectors.toSet());
        assertThat(afterEvents).as("历史事件清空或逐出，不能证明没有死锁").containsAll(beforeEvents);
        var added = after.rows().stream().filter(row -> !beforeEvents.contains(eventIdentity(row))).toList();
        for (TiDbDeadlock row : added) {
            assertThat(row.database()).as("新增死锁缺schema，不能按别库放行").isNotBlank().isNotEqualTo("null");
            assertThat(row.table()).isNotBlank().isNotEqualTo("null");
        }
        assertThat(added.stream().filter(row -> row.database().equals(db.database)).toList())
                .as("%s：本随机测试库出现新的真实 TiDB 死锁（含被重试吸收的结局）", what).isEmpty();
        if (!added.isEmpty()) System.out.println("[InnoDbDeadlockWatch] " + what + "：期间记录了别库死锁，原本库断言保留");
    }

    private static String latest(DataSource diagnostics) throws SQLException {
        try (Connection c = diagnostics.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW ENGINE INNODB STATUS")) {
            assertThat(rs.next()).as("SHOW ENGINE INNODB STATUS 没有返回行：测试账号需要 PROCESS 权限").isTrue();
            String status = rs.getString(3);
            int start = status == null ? -1 : status.indexOf(HEADER);
            if (start < 0) {
                return "";
            }
            String section = status.substring(start);
            int end = section.indexOf("\nTRANSACTIONS\n");
            return end >= 0 ? section.substring(0, end) : section;
        }
    }
}
