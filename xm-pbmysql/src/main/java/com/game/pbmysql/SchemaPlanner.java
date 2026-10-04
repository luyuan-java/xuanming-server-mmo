package com.game.pbmysql;

import static com.game.pbmysql.MysqlSyntax.escapeName;
import static com.game.pbmysql.MysqlSyntax.goQuote;

import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只扩不缩（expand-only）的结构对齐规划：给定线上结构（information_schema 读回的列、二级索引、主键），算出要执行的
 * ADD 语句，或者抛出 {@link SchemaDriftException} 列出差异与对齐所需的语句。纯计算，不碰数据库。
 *
 * <p>对应 Go 版 buildColumnClauses + validateSchemaDrift + planSchemaAlignment 在 {@code ExpandOnly = true} 下的行为：
 * <ul>
 *   <li>proto 有、线上没有的列 → {@code ADD COLUMN}（按列名匹配，MySQL 列名大小写不敏感）；</li>
 *   <li>线上列名不同但注释 {@code pb:N} 与某个 proto 字段号相同 → 视为改名，拒绝（CHANGE COLUMN 只作为建议给出）；</li>
 *   <li>同名列类型比 proto 窄 / 缺 {@code pb:N} 注释 / 跨族 / 有无符号不同 → 拒绝（MODIFY 只作为建议给出）；
 *       线上更宽 → 视为兼容，不收窄；</li>
 *   <li>NULL / DEFAULT / AUTO_INCREMENT 属性、同名索引的定义、主键定义与 proto 不一致 → 拒绝；</li>
 *   <li>proto 声明、线上缺的索引 / 唯一键（按名字）→ {@code ADD INDEX} / {@code ADD UNIQUE KEY}；
 *       线上没有主键 → {@code ADD PRIMARY KEY}（第二条 ALTER，带 AUTO_INCREMENT 的主键列新增一并挪过去）；</li>
 *   <li>线上多出来的列 / 索引一律不动，从不 DROP。</li>
 * </ul>
 */
final class SchemaPlanner {

    private static final Logger log = LoggerFactory.getLogger(SchemaPlanner.class);

    /** 线上单列：COLUMN_TYPE、注释里的字段号（0 = 没有）、IS_NULLABLE、COLUMN_DEFAULT（null = SQL NULL）、EXTRA（小写）、COLLATION_NAME。 */
    record ColumnMeta(String columnType, int fieldNumber, boolean nullable, String defaultValue, String extra,
                      String collation) {
        boolean isAutoIncrement() {
            return extra.contains("auto_increment");
        }
    }

    /** 索引里的一列：列名、SEQ_IN_INDEX、SUB_PART（null = 整列）。 */
    record IndexColumn(String name, int sequence, Long subPart) {
    }

    /** 一条索引（二级索引或主键）。 */
    record IndexMeta(boolean unique, List<IndexColumn> columns) {
    }

    /** 规划结果：第一条 ALTER（列 + 索引）、第二条 ALTER（补主键，及必须跟它同句的自增主键列）。都为空 = 结构已对齐。 */
    record Plan(List<String> columnClauses, List<String> primaryKeyClauses) {
        boolean isEmpty() {
            return columnClauses.isEmpty() && primaryKeyClauses.isEmpty();
        }
    }

    private record ColumnClause(String sql, String column, boolean autoIncrement) {
    }

    private record Found(String name, ColumnMeta meta) {
    }

    private SchemaPlanner() {
    }

    /** 从列注释解析 proto 字段号；不是 {@code pb:N} 或越界时返回 0。 */
    static int fieldNumberFromComment(String comment) {
        if (comment == null || !comment.startsWith("pb:")) {
            return 0;
        }
        String digits = comment.substring(3);
        // 只认 ASCII 数字（Go 的 strconv.Atoi 口径）：Integer.parseInt 还收全角等 Unicode 数字，会把漂移误当成已标注
        if (!digits.matches("[+-]?[0-9]+")) {
            return 0;
        }
        try {
            int n = Integer.parseInt(digits);
            return n >= 1 && n <= 536_870_911 ? n : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Plan plan(TableSchema t, Map<String, ColumnMeta> currentCols, Map<String, IndexMeta> existingIndexes,
                     IndexMeta currentPk) {
        String table = t.tableName();
        String alter = "ALTER TABLE " + escapeName(table) + " ";
        List<String> diffs = new ArrayList<>();
        List<String> fixes = new ArrayList<>();
        // 已有结论的列：给过 MODIFY / CHANGE 建议，或只能人工迁移；属性漂移不再重复给 MODIFY
        Set<String> fixedColumns = new HashSet<>();

        // 线上列按 pb:N 建身份表。按列名排序只为让错误文本稳定；同一个 pb:N 出现在两列上时身份歧义，直接拒绝
        Map<String, ColumnMeta> remaining = new LinkedHashMap<>();
        Map<Integer, String> byFieldNum = new HashMap<>();
        for (String name : new TreeSet<>(currentCols.keySet())) {
            ColumnMeta meta = currentCols.get(name);
            remaining.put(name, meta);
            if (meta.fieldNumber() == 0) {
                continue;
            }
            String prev = byFieldNum.putIfAbsent(meta.fieldNumber(), name);
            if (prev != null) {
                throw new SchemaDriftException(table, List.of(String.format(
                        "线上列 %s 与 %s 都声明 COMMENT 'pb:%d'；字段号身份已经歧义，无法安全判断哪列是真实数据。请先人工修正重复注释再同步",
                        goQuote(prev), goQuote(name), meta.fieldNumber())), List.of());
            }
        }

        List<ColumnClause> adds = new ArrayList<>();
        for (FieldDescriptor fd : t.fields()) {
            String name = fd.getName();
            int num = fd.getNumber();
            if (MysqlSyntax.isKeyword(name)) {
                log.warn("field {} in table {} conflicts with MySQL keyword", name, table);
            }
            String target = t.columnType(fd);
            String comment = TableSchema.columnComment(num);

            // 1) 同名列（MySQL 列名大小写不敏感）
            Found found = lookupColumn(table, remaining, name);
            if (found != null) {
                ColumnMeta meta = found.meta();
                boolean typeOk = MysqlTypes.isTypeMatch(meta.columnType(), target);
                if (!typeOk || meta.fieldNumber() != num) {
                    if (!MysqlTypes.isRenameConvertible(meta.columnType(), target)) {
                        diffs.add(String.format("列 %s 线上是 %s，proto 要 %s。跨类型族自动 MODIFY 会依赖 MySQL 隐式转换"
                                        + "并可能把整列数据改成 0/空值；请人工迁移数据后再调整 proto",
                                name, meta.columnType(), target));
                        fixedColumns.add(name); // 只能人工迁移：属性漂移也不再给 MODIFY 建议
                    } else if (MysqlTypes.unsafeIntegerSignednessChange(meta.columnType(), target)) {
                        diffs.add(String.format("列 %s 线上是 %s，proto 要 %s。signed 与 unsigned 的值域互不包含，"
                                        + "自动转换可能截断负数或大正数；请先核对存量范围并人工执行 ALTER",
                                name, meta.columnType(), target));
                        fixedColumns.add(name);
                    } else {
                        if (!typeOk) {
                            diffs.add(String.format("列 %s 线上是 %s，比 proto 要求的 %s 窄，需要拓宽", name, meta.columnType(), target));
                        }
                        if (meta.fieldNumber() != num) {
                            diffs.add(meta.fieldNumber() == 0
                                    ? String.format("列 %s 缺少字段号注释 COMMENT 'pb:%d'", name, num)
                                    : String.format("列 %s 的字段号注释是 pb:%d，proto 字段号是 %d", name, meta.fieldNumber(), num));
                        }
                        // 只补注释也不能收窄：保留线上更宽的类型本体，只带上目标属性
                        fixes.add(alter + "MODIFY COLUMN " + escapeName(name) + " "
                                + MysqlTypes.alignedColumnType(meta.columnType(), target) + comment);
                        fixedColumns.add(name);
                    }
                } else if (MysqlTypes.narrowingSuppressed(meta.columnType(), target)) {
                    log.info("table {}: 列 {} 线上是 {}、proto 要 {}——保持线上的不动。收窄会丢数据，且在滚动发布期间会被新旧副本来回改。"
                            + "确实要收窄请人工写 ALTER", table, name, meta.columnType(), target);
                }
                remaining.remove(found.name());
                continue;
            }

            // 2) 字段号匹配、列名不同：改名
            String oldName = byFieldNum.get(num);
            if (oldName != null && remaining.containsKey(oldName)) {
                ColumnMeta old = remaining.get(oldName);
                if (MysqlTypes.unsafeIntegerSignednessChange(old.columnType(), target)) {
                    diffs.add(String.format("列 %s→%s 改名同时要求从 %s 变成 %s。signed 与 unsigned 的值域互不包含，"
                            + "请把改名与人工数据迁移分开执行", oldName, name, old.columnType(), target));
                } else if (!MysqlTypes.isRenameConvertible(old.columnType(), target)) {
                    diffs.add(String.format("列 %s（%s，pb:%d）与新字段 %s（%s，pb:%d）类型跨族，无法当作改名处理。"
                                    + "字段号是 protobuf 的身份，永不复用：删字段请用 reserved，新字段另取一个没用过的编号。"
                                    + "若确实要把这一列的数据转成新类型，请人工写 ALTER 并自行确认转换语义",
                            oldName, old.columnType(), num, name, target, num));
                } else {
                    diffs.add(String.format("线上列 %s 按字段号 pb:%d 对应 proto 字段 %s（字段改名）。expand-only 不自动改名："
                                    + "滚动发布期间新旧副本会把这一列来回改名；正确做法是 expand→migrate→contract"
                                    + "（先加新字段、双写回填、下个版本再删旧字段）",
                            oldName, num, name));
                    fixes.add(alter + "CHANGE COLUMN " + escapeName(oldName) + " " + escapeName(name) + " "
                            + MysqlTypes.alignedColumnType(old.columnType(), target) + comment);
                    fixedColumns.add(name);
                }
                remaining.remove(oldName);
                continue;
            }

            // 3) 全新字段
            adds.add(new ColumnClause("ADD COLUMN " + escapeName(name) + " " + target + comment, name,
                    target.toUpperCase(Locale.ROOT).contains("AUTO_INCREMENT")));
        }

        checkAttributeDrift(t, currentCols, existingIndexes, currentPk, alter, diffs, fixes, fixedColumns);

        if (!diffs.isEmpty()) {
            throw new SchemaDriftException(table, diffs, fixes);
        }

        List<String> columnClauses = new ArrayList<>();
        List<String> pkClauses = new ArrayList<>();
        Set<String> movedAutoIncrement = new HashSet<>();
        if (!t.primaryKey().isEmpty() && currentPk == null) {
            // 补主键：只补「从无到有」。MySQL 要求自增列必须是键，所以新增的自增主键列要与 ADD PRIMARY KEY 同句
            for (ColumnClause c : adds) {
                if (c.autoIncrement() && t.primaryKey().contains(c.column())) {
                    pkClauses.add(c.sql());
                    movedAutoIncrement.add(c.column());
                } else {
                    columnClauses.add(c.sql());
                }
            }
            List<String> pkCols = new ArrayList<>();
            for (String pk : t.primaryKey()) {
                pkCols.add(t.indexColumn(pk));
            }
            String pkClause = "ADD PRIMARY KEY (" + String.join(",", pkCols) + ")";
            if (t.tidbNonclusteredPk()) {
                pkClause += TableSchema.TIDB_NONCLUSTERED_PK_SQL;
            }
            pkClauses.add(pkClause);
            log.info("table {} is missing its primary key; adding {}", table, String.join(",", pkCols));
        } else {
            for (ColumnClause c : adds) {
                columnClauses.add(c.sql());
            }
        }

        for (String clause : missingIndexClauses(t, existingIndexes)) {
            boolean dependsOnMoved = false;
            for (String column : movedAutoIncrement) {
                if (clause.contains(escapeName(column))) {
                    dependsOnMoved = true;
                    break;
                }
            }
            // 依赖尚未建出的自增主键列的索引只能放进第二条 ALTER，否则第一条先报 Error 1072
            (dependsOnMoved ? pkClauses : columnClauses).add(clause);
        }
        return new Plan(List.copyOf(columnClauses), List.copyOf(pkClauses));
    }

    /** NULL / DEFAULT / AUTO_INCREMENT、键列形态、同名索引定义、主键定义的漂移（Go 版 validateSchemaDrift）。 */
    private static void checkAttributeDrift(TableSchema t, Map<String, ColumnMeta> currentCols,
                                            Map<String, IndexMeta> existingIndexes, IndexMeta currentPk,
                                            String alter, List<String> diffs, List<String> fixes,
                                            Set<String> fixedColumns) {
        String table = t.tableName();
        Set<String> legacy = new HashSet<>();
        for (FieldDescriptor fd : t.fields()) {
            String name = fd.getName();
            Found found = lookupColumn(table, currentCols, name);
            if (found == null) {
                // 改名路径：属性漂移要看按 pb:N 认出的旧列
                for (Map.Entry<String, ColumnMeta> e : currentCols.entrySet()) {
                    if (e.getValue().fieldNumber() != fd.getNumber()) {
                        continue;
                    }
                    if (found == null || e.getKey().compareTo(found.name()) < 0) {
                        found = new Found(e.getKey(), e.getValue());
                    }
                }
            }
            if (found == null) {
                continue; // 这一列会被 ADD COLUMN 建出来，属性自然是对的
            }
            ColumnMeta meta = found.meta();
            String display = found.name().equals(name) ? name : found.name() + "->" + name;
            String target = t.columnType(fd);

            // 键列先认形态：旧形态（TEXT/BLOB 前缀索引、*_ci、PAD SPACE）的唯一性语义本身就不对，不再做通用比较
            FieldDescriptor.Type keyKind = t.keyColumnKind(fd);
            if (keyKind != null) {
                String reason = legacyKeyColumnReason(keyKind, meta);
                if (!reason.isEmpty()) {
                    String online = meta.columnType()
                            + (meta.collation().isEmpty() ? "" : " COLLATE " + meta.collation())
                            + (meta.nullable() ? " NULL" : "");
                    diffs.add(String.format("键列 %s（%s）仍是旧形态：线上 %s，期望 %s；原因：%s。"
                                    + "需要先核对超长值、NULL 与重复值，再人工迁移列定义并重建索引（本库不自动迁移）",
                            display, keyRoles(t, name), online, target, reason));
                    legacy.add(name);
                    continue;
                }
            }

            boolean wantNullable = !target.toUpperCase(Locale.ROOT).contains("NOT NULL");
            String wantDefault = expectedColumnDefault(target);
            boolean wantAutoInc = t.isAutoIncrementField(name);
            boolean drift = false;
            if (meta.nullable() != wantNullable) {
                diffs.add(String.format("column %s nullable mismatch (online=%b, proto=%b)", display, meta.nullable(), wantNullable));
                drift = true;
            }
            if (!columnDefaultsEqual(meta.defaultValue(), wantDefault)) {
                diffs.add(String.format("column %s default mismatch (online=%s, proto=%s)",
                        display, formatDefault(meta.defaultValue()), formatDefault(wantDefault)));
                drift = true;
            }
            if (meta.isAutoIncrement() != wantAutoInc) {
                diffs.add(String.format("column %s auto_increment mismatch (online=%b, proto=%b)",
                        display, meta.isAutoIncrement(), wantAutoInc));
                drift = true;
            }
            if (drift && found.name().equals(name) && fixedColumns.add(name)) {
                fixes.add(alter + "MODIFY COLUMN " + escapeName(name) + " "
                        + MysqlTypes.alignedColumnType(meta.columnType(), target) + TableSchema.columnComment(fd.getNumber()));
            }
        }

        // 旧形态键列所在的索引要随迁移一起重建，不再单独报索引漂移
        List<String> indexes = t.indexes();
        for (int i = 0; i < indexes.size(); i++) {
            if (referencesAny(MysqlSyntax.splitTrimmed(indexes.get(i)), legacy)) {
                continue;
            }
            String indexName = t.indexName(i);
            IndexMeta online = lookupIndex(existingIndexes, indexName);
            if (online == null) {
                continue; // 缺的由 missingIndexClauses 补
            }
            IndexMeta want = expectedIndexMeta(t, indexes.get(i), false);
            if (!indexMetaEqual(online, want)) {
                diffs.add(String.format("index %s definition mismatch (online=%s, proto=%s)",
                        indexName, formatIndexMeta(online), formatIndexMeta(want)));
                fixes.add(alter + "DROP INDEX " + escapeName(indexName) + ", ADD INDEX " + escapeName(indexName)
                        + " (" + t.indexColumnsSql(indexes.get(i)) + ")");
            }
        }
        if (!t.uniqueKey().isEmpty() && !referencesAny(MysqlSyntax.splitTrimmed(t.uniqueKey()), legacy)) {
            String ukName = t.uniqueKeyName();
            IndexMeta online = lookupIndex(existingIndexes, ukName);
            if (online != null) {
                IndexMeta want = expectedIndexMeta(t, t.uniqueKey(), true);
                if (!indexMetaEqual(online, want)) {
                    diffs.add(String.format("unique index %s definition mismatch (online=%s, proto=%s)",
                            ukName, formatIndexMeta(online), formatIndexMeta(want)));
                    fixes.add(alter + "DROP INDEX " + escapeName(ukName) + ", ADD UNIQUE KEY " + escapeName(ukName)
                            + " (" + t.indexColumnsSql(t.uniqueKey()) + ")");
                }
            }
        }
        if (!t.primaryKey().isEmpty() && currentPk != null && !referencesAny(t.primaryKey(), legacy)) {
            IndexMeta want = expectedIndexMeta(t, String.join(",", t.primaryKey()), true);
            if (!indexMetaEqual(currentPk, want)) {
                diffs.add(String.format("primary key definition mismatch (online=%s, proto=%s)",
                        formatIndexMeta(currentPk), formatIndexMeta(want)));
                List<String> cols = new ArrayList<>();
                for (String pk : t.primaryKey()) {
                    cols.add(t.indexColumn(pk));
                }
                fixes.add(alter + "DROP PRIMARY KEY, ADD PRIMARY KEY (" + String.join(",", cols) + ")");
            }
        }
    }

    /** proto 声明了、线上却没有的索引（按名字，大小写不敏感）：只加不删，名字与建表分支一致。 */
    static List<String> missingIndexClauses(TableSchema t, Map<String, IndexMeta> existing) {
        List<String> clauses = new ArrayList<>();
        List<String> indexes = t.indexes();
        for (int i = 0; i < indexes.size(); i++) {
            String name = t.indexName(i);
            if (lookupIndex(existing, name) != null) {
                continue;
            }
            String cols = t.indexColumnsSql(indexes.get(i));
            clauses.add("ADD INDEX " + escapeName(name) + " (" + cols + ")");
            log.info("table {} 补索引 {} ({})", t.tableName(), name, cols);
        }
        if (!t.uniqueKey().isEmpty()) {
            String name = t.uniqueKeyName();
            if (lookupIndex(existing, name) == null) {
                String cols = t.indexColumnsSql(t.uniqueKey());
                clauses.add("ADD UNIQUE KEY " + escapeName(name) + " (" + cols + ")");
                log.warn("table {} 补唯一键 {} ({})：线上若已有重复行，这条 ALTER 会失败，需先人工去重再重试（fail-closed，不会静默跳过）",
                        t.tableName(), name, cols);
            }
        }
        return clauses;
    }

    /** 按 MySQL 列名语义（大小写不敏感）查线上列；多个候选时身份歧义，拒绝。 */
    private static Found lookupColumn(String table, Map<String, ColumnMeta> columns, String name) {
        List<String> candidates = new ArrayList<>();
        for (String actual : columns.keySet()) {
            if (actual.equalsIgnoreCase(name)) {
                candidates.add(actual);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            candidates.sort(null);
            throw new SchemaDriftException(table, List.of(String.format(
                    "线上列 %s 按 MySQL 大小写不敏感语义都匹配 proto 字段 %s；列身份歧义，拒绝自动同步",
                    candidates, goQuote(name))), List.of());
        }
        return new Found(candidates.get(0), columns.get(candidates.get(0)));
    }

    private static IndexMeta lookupIndex(Map<String, IndexMeta> indexes, String name) {
        IndexMeta exact = indexes.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, IndexMeta> e : indexes.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean referencesAny(List<String> columns, Set<String> names) {
        for (String c : columns) {
            if (names.contains(c)) {
                return true;
            }
        }
        return false;
    }

    private static String keyRoles(TableSchema t, String name) {
        boolean inPk = t.primaryKey().contains(name);
        boolean inUk = MysqlSyntax.splitTrimmed(t.uniqueKey()).contains(name);
        if (inPk && inUk) {
            return "主键、唯一键";
        }
        return inPk ? "主键" : "唯一键";
    }

    /** 线上列不是键列应有的形态时返回原因（只认 string→varchar + utf8mb4_0900_bin、bytes→varbinary），形态正确返回空串。 */
    static String legacyKeyColumnReason(FieldDescriptor.Type kind, ColumnMeta meta) {
        String base = MysqlTypes.normalizeBaseType(MysqlTypes.parse(meta.columnType()).baseType());
        if (kind == FieldDescriptor.Type.BYTES) {
            return switch (base) {
                case "varbinary" -> "";
                case "tinyblob", "blob", "mediumblob", "longblob" ->
                        "BLOB 列只能建前缀索引，唯一性只覆盖前 " + TableSchema.TEXT_INDEX_PREFIX_LENGTH + " 个字节";
                case "binary" -> "BINARY 定长、写入时右补 0x00，'a' 与 'a\\0' 会存成同一个值";
                default -> "线上类型不是 bytes 键列要求的 VARBINARY，无法确认按字节唯一";
            };
        }
        return switch (base) {
            case "varchar" -> keyCollationReason(meta.collation());
            case "tinytext", "text", "mediumtext", "longtext" ->
                    "TEXT 列只能建前缀索引，唯一性只覆盖前 " + TableSchema.TEXT_INDEX_PREFIX_LENGTH + " 个字符";
            case "char" -> "CHAR 比较时忽略尾部空格，'abc' 与 'abc ' 会被判为同一个键";
            default -> "线上类型不是 string 键列要求的 VARCHAR，无法确认按字符唯一";
        };
    }

    private static String keyCollationReason(String collation) {
        String lower = collation.toLowerCase(Locale.ROOT);
        if (lower.equals(TableSchema.KEY_STRING_COLLATION)) {
            return "";
        }
        if (lower.isEmpty()) {
            return "读不到排序规则，无法确认是否区分大小写、是否比较尾部空格";
        }
        if (lower.endsWith("_ci")) {
            return "排序规则 " + collation + " 不区分大小写，'AbC' 与 'abc' 会被判为同一个键";
        }
        if (!lower.contains("_0900_")) {
            return "排序规则 " + collation + " 是 PAD SPACE，'abc' 与 'abc ' 会被判为同一个键";
        }
        return "排序规则 " + collation + " 不是按码点比较，不同的字符串可能被判为同一个键";
    }

    /** proto 目标类型对应的 COLUMN_DEFAULT：数值列 "0"，键列 ""（空串而非 NULL），其余 NULL。 */
    static String expectedColumnDefault(String targetType) {
        String upper = targetType.toUpperCase(Locale.ROOT);
        if (upper.contains(" DEFAULT 0")) {
            return "0";
        }
        if (upper.contains(" DEFAULT ''")) {
            return "";
        }
        return null;
    }

    /** 比较前去掉首尾空白与一对首尾单引号（有的兼容实现把空串默认值回读成 {@code ''}），不区分大小写。 */
    static boolean columnDefaultsEqual(String online, String want) {
        if ((online == null) != (want == null)) {
            return false;
        }
        if (online == null) {
            return true;
        }
        return unquoteDefault(online).equalsIgnoreCase(unquoteDefault(want));
    }

    private static String unquoteDefault(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.charAt(0) == '\'' && v.charAt(v.length() - 1) == '\'') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String formatDefault(String value) {
        return value == null ? "NULL" : goQuote(value);
    }

    static IndexMeta expectedIndexMeta(TableSchema t, String columns, boolean unique) {
        List<String> names = MysqlSyntax.splitTrimmed(columns);
        List<IndexColumn> cols = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Long subPart = t.needsIndexPrefix(name) ? (long) TableSchema.TEXT_INDEX_PREFIX_LENGTH : null;
            cols.add(new IndexColumn(name, i + 1, subPart));
        }
        return new IndexMeta(unique, cols);
    }

    static boolean indexMetaEqual(IndexMeta online, IndexMeta want) {
        if (online.unique() != want.unique() || online.columns().size() != want.columns().size()) {
            return false;
        }
        for (int i = 0; i < online.columns().size(); i++) {
            IndexColumn a = online.columns().get(i);
            IndexColumn b = want.columns().get(i);
            if (a.sequence() != b.sequence() || !a.name().equalsIgnoreCase(b.name())
                    || !Objects.equals(a.subPart(), b.subPart())) {
                return false;
            }
        }
        return true;
    }

    static String formatIndexMeta(IndexMeta meta) {
        List<String> cols = new ArrayList<>();
        for (IndexColumn c : meta.columns()) {
            String name = c.subPart() != null ? c.name() + "(" + c.subPart() + ")" : c.name();
            cols.add(c.sequence() + ":" + name);
        }
        return (meta.unique() ? "UNIQUE" : "INDEX") + "(" + String.join(",", cols) + ")";
    }
}
