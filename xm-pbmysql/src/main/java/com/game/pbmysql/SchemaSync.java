package com.game.pbmysql;

import static com.game.pbmysql.MysqlSyntax.escapeName;

import com.game.pbmysql.SchemaPlanner.ColumnMeta;
import com.game.pbmysql.SchemaPlanner.IndexColumn;
import com.game.pbmysql.SchemaPlanner.IndexMeta;
import com.game.pbmysql.SchemaPlanner.Plan;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只扩不缩的结构同步：读 information_schema → {@link SchemaPlanner} 规划 → 执行 ADD。
 *
 * <p>整轮同步在调用方给的<b>同一条连接</b>上完成，并持有 MySQL 咨询锁（{@code GET_LOCK}，会话级）：N 个副本同时冷启动时
 * 不会一个 ALTER 成功、其余撞 Error 1060；锁与释放必须在同一会话上，换连接等于没锁。锁名与 Go 版相同
 * （{@code proto2mysql:sync:<库名 FNV-1a>}），Go 与 Java 进程同步同一个库时也互斥。
 */
final class SchemaSync {

    private static final Logger log = LoggerFactory.getLogger(SchemaSync.class);

    static final String LOCK_NAME_PREFIX = "proto2mysql:sync";
    static final int LOCK_TIMEOUT_SECONDS = 30;
    private static final long SETTLE_TIMEOUT_MILLIS = 60_000;
    private static final long SETTLE_INTERVAL_MILLIS = 200;

    private SchemaSync() {
    }

    /** 同步一批表：先整批做内存预检（物理表名判重），再抢锁，按表名排序逐张同步。 */
    static void sync(Connection conn, List<TableSchema> tables, List<TableSchema> registry) throws SQLException {
        checkDuplicateMappings(registry);
        if (!conn.getAutoCommit()) {
            throw new IllegalStateException("结构同步不能在事务里执行：MySQL 的 DDL 会隐式提交，无法随业务事务回滚");
        }
        String database = currentDatabase(conn);
        List<TableSchema> sorted = new ArrayList<>(tables);
        sorted.sort(Comparator.comparing(TableSchema::tableName)
                .thenComparing(t -> t.descriptor().getFullName()));

        String lockName = lockName(database);
        boolean locked = acquireLock(conn, lockName);
        try {
            for (TableSchema table : sorted) {
                syncTable(conn, database, table);
            }
        } catch (SQLException | RuntimeException e) {
            if (locked) {
                try {
                    releaseLock(conn, lockName);
                } catch (RuntimeException releaseFailure) {
                    e.addSuppressed(releaseFailure);
                }
            }
            throw e;
        }
        if (locked) {
            releaseLock(conn, lockName);
        }
    }

    /** 两个不同 message 映射到同一张物理表（MySQL 表名按大小写不敏感比较）时拒绝：两套定义会把同一张表来回改。 */
    static void checkDuplicateMappings(List<TableSchema> registry) {
        List<TableSchema> sorted = new ArrayList<>(registry);
        sorted.sort(Comparator.comparing(TableSchema::tableName)
                .thenComparing(t -> t.descriptor().getFullName()));
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                TableSchema a = sorted.get(i);
                TableSchema b = sorted.get(j);
                if (!a.tableName().equalsIgnoreCase(b.tableName())) {
                    continue;
                }
                throw new InvalidTableDefinitionException(InvalidTableDefinitionException.Reason.DUPLICATE_TABLE_MAPPING,
                        String.format("multiple protobuf messages map to the same physical table: table %s (%s) conflicts with %s (%s)"
                                        + " under case-insensitive comparison",
                                MysqlSyntax.goQuote(a.tableName()), a.descriptor().getFullName(),
                                MysqlSyntax.goQuote(b.tableName()), b.descriptor().getFullName()));
            }
        }
    }

    static String lockName(String database) {
        return String.format("%s:%08x", LOCK_NAME_PREFIX, MysqlSyntax.fnv1a32(database.getBytes(StandardCharsets.UTF_8)));
    }

    private static String currentDatabase(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            String db = rs.next() ? rs.getString(1) : null;
            if (db == null || db.isEmpty()) {
                throw new PbMysqlException("连接没有选中库：请把库名写进 JDBC URL（jdbc:mysql://host:3306/<库名>）");
            }
            return db;
        }
    }

    // ================================================================ 单表

    private static void syncTable(Connection conn, String database, TableSchema table) throws SQLException {
        String name = table.tableName();
        if (!tableExists(conn, database, name)) {
            String create = table.buildCreateTableSql();
            try {
                exec(conn, create);
            } catch (SQLException e) {
                throw wrap("创建表 " + name + " 失败", create, e);
            }
            log.info("pbmysql 建表 {}", name);
        }
        // 建完不 return：CREATE TABLE IF NOT EXISTS 在并发冷启动下可能整条是 no-op（另一个版本的进程先建了），
        // 落到对齐路径上按真实结构补齐本进程独有的列与索引
        Map<String, ColumnMeta> columns = readColumns(conn, database, name);
        Map<String, IndexMeta> indexes = readIndexes(conn, database, name, false);
        IndexMeta primaryKey = table.primaryKey().isEmpty() ? null : primaryKeyOf(readIndexes(conn, database, name, true));
        Plan plan = SchemaPlanner.plan(table, columns, indexes, primaryKey);
        if (plan.isEmpty()) {
            return;
        }
        if (!plan.columnClauses().isEmpty()) {
            String alter = "ALTER TABLE " + escapeName(name) + " " + String.join(", ", plan.columnClauses());
            try {
                exec(conn, alter);
            } catch (SQLException e) {
                throw wrap("更新表 " + name + " 结构失败", alter, e);
            }
            log.info("pbmysql 对齐表 {}: {}", name, alter);
            awaitColumnsVisible(conn, database, name, plan.columnClauses());
        }
        if (!plan.primaryKeyClauses().isEmpty()) {
            String alter = "ALTER TABLE " + escapeName(name) + " " + String.join(", ", plan.primaryKeyClauses());
            try {
                exec(conn, alter);
            } catch (SQLException e) {
                throw wrap("表 " + name + " 的列已对齐，但补齐主键失败（线上已有重复行时须先人工去重；"
                        + "TiDB 不支持给已存在的列加 AUTO_INCREMENT，只能重建表）", alter, e);
            }
            log.info("pbmysql 补主键 {}: {}", name, alter);
            awaitColumnsVisible(conn, database, name, plan.primaryKeyClauses());
        }
    }

    private static SQLException wrap(String what, String sql, SQLException e) {
        return new SQLException(what + ": " + e.getMessage() + ", SQL: " + sql, e.getSQLState(), e.getErrorCode(), e);
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static boolean tableExists(Connection conn, String database, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, database);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        }
    }

    static Map<String, ColumnMeta> readColumns(Connection conn, String database, String table) throws SQLException {
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLLATION_NAME"
                        + " FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, database);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String collation = rs.getString(7);
                    String extra = rs.getString(6);
                    out.put(rs.getString(1), new ColumnMeta(
                            rs.getString(2),
                            SchemaPlanner.fieldNumberFromComment(rs.getString(3)),
                            "YES".equalsIgnoreCase(rs.getString(4)),
                            rs.getString(5),
                            extra == null ? "" : extra.toLowerCase(Locale.ROOT),
                            collation == null ? "" : collation));
                }
            }
        }
        return out;
    }

    /** primary = false 读二级索引（不含 PRIMARY），true 只读 PRIMARY。 */
    static Map<String, IndexMeta> readIndexes(Connection conn, String database, String table, boolean primary)
            throws SQLException {
        String sql = "SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, SUB_PART"
                + " FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME "
                + (primary ? "= 'PRIMARY'" : "<> 'PRIMARY'") + " ORDER BY INDEX_NAME, SEQ_IN_INDEX";
        Map<String, Boolean> unique = new LinkedHashMap<>();
        Map<String, List<IndexColumn>> columns = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, database);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (name == null || name.isEmpty()) {
                        continue;
                    }
                    boolean isUnique = rs.getLong(2) == 0;
                    Boolean prev = unique.putIfAbsent(name, isUnique);
                    if (prev != null && prev != isUnique) {
                        throw new PbMysqlException("index metadata for table " + table + " index " + name
                                + " has inconsistent NON_UNIQUE values");
                    }
                    long subPart = rs.getLong(5);
                    Long sub = rs.wasNull() ? null : subPart;
                    String column = rs.getString(4);
                    columns.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(new IndexColumn(column == null ? "" : column, rs.getInt(3), sub));
                }
            }
        }
        Map<String, IndexMeta> out = new LinkedHashMap<>();
        unique.forEach((name, u) -> out.put(name, new IndexMeta(u, List.copyOf(columns.get(name)))));
        return out;
    }

    private static IndexMeta primaryKeyOf(Map<String, IndexMeta> primary) {
        return primary.isEmpty() ? null : primary.values().iterator().next();
    }

    /**
     * 等 ADD COLUMN 的结果真的能从 information_schema 看见再放行后续 SQL。MySQL 单机上立即通过；TiDB 的 DDL 异步生效，
     * 各节点按 schema lease 分批加载，不等的话首批请求会撞 Unknown column。超时只告警不报错（慢不应变成起不来）。
     */
    private static void awaitColumnsVisible(Connection conn, String database, String table, List<String> clauses)
            throws SQLException {
        Set<String> want = addedColumnNames(clauses);
        if (want.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS;
        while (true) {
            Map<String, ColumnMeta> live;
            try {
                live = readColumns(conn, database, table);
            } catch (SQLException e) {
                // 与 Go 版一致：回读失败只告警、跳过就绪探测（ALTER 已经生效，不能让一次回读抖动变成起不来）
                log.warn("回读表 {} 结构失败，跳过就绪探测: {}", table, e.getMessage());
                return;
            }
            if (live.isEmpty()) {
                return; // 一列都读不到 = 根本看不见这张表（权限 / 库名不对），继续等只会白等到超时
            }
            Set<String> missing = new TreeSet<>(want);
            missing.removeAll(live.keySet());
            if (missing.isEmpty()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                log.warn("表 {} 的结构变更在 {} ms 内没有全部可见（仍缺 {}）。TiDB 的 DDL 是异步的，可能仍在后台排队；"
                        + "若后续 SQL 报 Unknown column，等一个 schema lease 再重试", table, SETTLE_TIMEOUT_MILLIS, missing);
                return;
            }
            try {
                Thread.sleep(SETTLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 从 ALTER 子句里挑出本次新增的列名（只等这些列）。 */
    static Set<String> addedColumnNames(List<String> clauses) {
        Set<String> names = new TreeSet<>();
        String prefix = "ADD COLUMN `";
        for (String clause : clauses) {
            if (!clause.startsWith(prefix)) {
                continue;
            }
            String rest = clause.substring(prefix.length());
            int i = 0;
            StringBuilder name = new StringBuilder();
            while (i < rest.length()) {
                char c = rest.charAt(i);
                if (c == '`') {
                    if (i + 1 < rest.length() && rest.charAt(i + 1) == '`') {
                        name.append('`');
                        i += 2;
                        continue;
                    }
                    break;
                }
                name.append(c);
                i++;
            }
            if (name.length() > 0) {
                names.add(name.toString());
            }
        }
        return names;
    }

    // ================================================================ 咨询锁

    /**
     * 抢 DDL 咨询锁。只有后端明确表示「不支持 GET_LOCK」时允许无锁降级（返回 false）；锁竞争超时、NULL、
     * 连接或查询错误都中止同步，不能把锁保护悄悄降成 best-effort。
     *
     * <p>锁状态未知（GET_LOCK 出错 / NULL / 意外值）时先丢弃这条连接的物理会话再抛（Go 版 discardSQLConn 同义）：
     * 连接池里的会话永不结束，泄漏的锁会让所有副本的结构同步等满 30 秒后起不来。
     */
    static boolean acquireLock(Connection conn, String lockName) throws SQLException {
        // GET_LOCK 在同一会话上可重入：本会话已持有（上一轮泄漏）时再抢会成功并把泄漏掩盖掉，先查出来丢弃
        if (heldBySession(conn, lockName)) {
            discardSession(conn);
            throw new SchemaLockException("DDL 咨询锁 " + lockName + " 已被本会话持有（此前泄漏），已丢弃这条连接的会话");
        }
        Object got;
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, lockName);
            ps.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                got = rs.next() ? rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            if (isAdvisoryLockUnsupported(e)) {
                log.warn("后端明确不支持 GET_LOCK（{}），本次结构同步有边界地无锁执行：{}", lockName, e.getMessage());
                return false;
            }
            discardSession(conn);
            throw new SQLException("取得 DDL 咨询锁 " + lockName + " 失败（已丢弃会话）: " + e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), e);
        }
        if (got == null) {
            discardSession(conn);
            throw new SchemaLockException("DDL 咨询锁 " + lockName + " 返回 NULL，锁状态未知，已丢弃会话并中止结构同步");
        }
        long value = ((Number) got).longValue();
        if (value == 0) {
            throw new SchemaLockException("DDL 咨询锁 " + lockName + " 在 " + LOCK_TIMEOUT_SECONDS + " 秒内未取得，另一个结构同步仍可能持锁");
        }
        if (value != 1) {
            discardSession(conn);
            throw new SchemaLockException("DDL 咨询锁 " + lockName + " 返回意外值 " + value + "，锁状态未知，已丢弃会话");
        }
        return true;
    }

    /** 本会话是否已持有该锁；后端不支持 IS_USED_LOCK（如 TiDB）或查询出错时按「未持有」继续（只是少一道纵深检查）。 */
    private static boolean heldBySession(Connection conn, String lockName) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) == 1;
            }
        } catch (SQLException e) {
            log.debug("IS_USED_LOCK 不可用，跳过重入检查: {}", e.getMessage());
            return false;
        }
    }

    /** 尽力结束物理会话（会话结束时 MySQL 释放它持有的全部咨询锁）；连接池随后会把这条已关闭的连接剔除。 */
    private static void discardSession(Connection conn) {
        try {
            conn.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            log.warn("丢弃连接会话失败: {}", e.toString());
        }
    }

    static boolean isAdvisoryLockUnsupported(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (!message.contains("get_lock")) {
            return false;
        }
        boolean unsupported = message.contains("does not exist") || message.contains("doesn't exist")
                || message.contains("not support") || message.contains("unsupported");
        if (!unsupported) {
            return false;
        }
        int code = e.getErrorCode();
        return code == 1105 || code == 1235 || code == 1305;
    }

    /**
     * 在持锁的同一条连接上释放。释放出错或返回值不是 1 时锁状态未知：丢弃物理会话（连带释放锁）并抛出——
     * 同步本身失败时由调用方挂成 suppressed，不覆盖原错误。
     */
    private static void releaseLock(Connection conn, String lockName) {
        String problem;
        try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, lockName);
            try (ResultSet rs = ps.executeQuery()) {
                Object released = rs.next() ? rs.getObject(1) : null;
                if (released instanceof Number n && n.longValue() == 1) {
                    return;
                }
                problem = "释放返回 " + released + "（预期 1）";
            }
        } catch (SQLException e) {
            problem = "释放失败: " + e.getMessage();
        }
        discardSession(conn);
        throw new SchemaLockException("DDL 咨询锁 " + lockName + " " + problem + "，锁状态未知，已丢弃这条连接的会话");
    }
}
