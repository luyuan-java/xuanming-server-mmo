package com.game.pbmysql;

import static com.game.pbmysql.InvalidTableDefinitionException.Reason.INVALID_TABLE_OPTION;
import static com.game.pbmysql.InvalidTableDefinitionException.Reason.UNSUPPORTED_FIELD_KIND;
import static com.game.pbmysql.MysqlSyntax.escapeName;
import static com.game.pbmysql.MysqlSyntax.goQuote;
import static com.game.pbmysql.MysqlSyntax.goTrimSpace;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个 protobuf message 与一张 MySQL 表的映射：表选项（proto 描述符里的声明 + 代码覆盖）解析、校验后冻结，
 * 并负责生成建表 DDL 与 CRUD 语句片段。不可变，线程安全。
 *
 * <p>DDL 与 Go 版 proto2mysql v0.2.0 的 {@code GetCreateTableSQL} 逐字节相同（类型映射、列注释 {@code pb:N}、
 * 索引名、TEXT/BLOB 191 前缀、标识符截断、TiDB 方言块、表尾）。所有校验在构造时完成，非法定义抛
 * {@link InvalidTableDefinitionException}，消息与 Go 版一致。
 *
 * <p>与 Go 版的一处有意差异：{@code repeated google.protobuf.Timestamp} 字段被拒绝。Go 版会把它建成 DATETIME(6)
 * 而写入的却是序列化字节（空列表写空串），这张表上的任何写入都会被 MySQL 拒绝。
 */
public final class TableSchema {

    private static final Logger log = LoggerFactory.getLogger(TableSchema.class);

    /** 在 TEXT/BLOB 列上建索引时用的前缀长度（utf8mb4 下的经典安全值 767 / 4）。 */
    public static final int TEXT_INDEX_PREFIX_LENGTH = 191;
    /** 未声明 max_length 时键列的 N（string 按字符、bytes 按字节）。 */
    public static final int DEFAULT_KEY_COLUMN_LENGTH = 191;
    /** string 键列 max_length 上限（字符）：3072 / 4。 */
    public static final int MAX_KEY_STRING_LENGTH = 768;
    /** bytes 键列 max_length 上限（字节）。 */
    public static final int MAX_KEY_BYTES_LENGTH = 3072;
    /** 单个索引所有列合计的字节上限（InnoDB 超出报 Error 1071）。 */
    public static final int MAX_INDEX_KEY_BYTES = 3072;
    /** string 键列的排序规则：按码点比较、区分大小写、NO PAD。 */
    public static final String KEY_STRING_COLLATION = "utf8mb4_0900_bin";

    static final String DEFAULT_TABLE_COLLATION = "utf8mb4_unicode_ci";
    static final String TIDB_NONCLUSTERED_PK_SQL = " /*T![clustered_index] NONCLUSTERED */";

    /** proto 类型 → MySQL 列类型（键列、Timestamp、容器另行处理），与 Go 版 MySQLFieldTypes 相同。 */
    private static final Map<FieldDescriptor.Type, String> FIELD_TYPES = new EnumMap<>(FieldDescriptor.Type.class);

    static {
        FIELD_TYPES.put(FieldDescriptor.Type.INT32, "int NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.UINT32, "int unsigned NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.FLOAT, "float NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.STRING, "MEDIUMTEXT");
        FIELD_TYPES.put(FieldDescriptor.Type.INT64, "bigint NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.UINT64, "bigint unsigned NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.DOUBLE, "double NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.BOOL, "tinyint(1) NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.ENUM, "int NOT NULL DEFAULT 0");
        FIELD_TYPES.put(FieldDescriptor.Type.BYTES, "MEDIUMBLOB");
        FIELD_TYPES.put(FieldDescriptor.Type.MESSAGE, "MEDIUMBLOB");
    }

    private final Message prototype;
    private final Descriptor descriptor;
    private final String tableName;
    private final List<String> primaryKey;
    private final List<String> indexes;
    private final String uniqueKey;
    private final String autoIncrementKey;
    private final List<String> nullableFields;
    private final Map<String, Long> maxLengths;
    private final boolean tidbNonclusteredPk;
    private final long tidbShardRowIdBits;
    private final long tidbPreSplitRegions;
    private final boolean tidbAutoIdCacheOne;

    private final List<FieldDescriptor> fields;
    private final Map<String, FieldDescriptor> fieldsByName;
    private final String columnListSql;
    private final String selectSql;
    private final String insertSql;

    private TableSchema(Message prototype, TableOption.Spec spec) {
        this.prototype = prototype;
        this.descriptor = prototype.getDescriptorForType();
        this.tableName = spec.tableName;
        this.primaryKey = List.copyOf(spec.primaryKey);
        this.indexes = List.copyOf(spec.indexes);
        this.uniqueKey = spec.uniqueKey == null ? "" : spec.uniqueKey;
        this.autoIncrementKey = spec.autoIncrementKey == null ? "" : spec.autoIncrementKey;
        this.nullableFields = List.copyOf(spec.nullableFields);
        this.maxLengths = spec.maxLengths == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(spec.maxLengths));
        this.tidbNonclusteredPk = spec.tidbNonclusteredPk;
        this.tidbShardRowIdBits = spec.tidbShardRowIdBits;
        this.tidbPreSplitRegions = spec.tidbPreSplitRegions;
        this.tidbAutoIdCacheOne = spec.tidbAutoIdCacheOne;

        this.fields = List.copyOf(descriptor.getFields());
        Map<String, FieldDescriptor> byName = new LinkedHashMap<>();
        List<String> escaped = new ArrayList<>(fields.size());
        for (FieldDescriptor fd : fields) {
            byName.put(fd.getName(), fd);
            escaped.add(escapeName(fd.getName()));
        }
        this.fieldsByName = Collections.unmodifiableMap(byName);
        this.columnListSql = String.join(", ", escaped);
        String table = escapeName(tableName == null ? "" : tableName);
        this.selectSql = "SELECT " + columnListSql + " FROM " + table;
        this.insertSql = "INSERT INTO " + table + " (" + columnListSql + ") VALUES ("
                + String.join(", ", Collections.nCopies(fields.size(), "?")) + ")";
    }

    /**
     * 解析并校验一张表：先应用 proto 描述符里声明的表选项，再按顺序应用 {@code overrides}（覆盖前者）。
     *
     * @param prototype 该消息类型的任意实例（通常是 {@code Xxx.getDefaultInstance()}），用于构造读出的消息
     * @throws InvalidTableDefinitionException 字段类型 / 表名 / 表选项不能映射成一张合法的表
     */
    public static TableSchema of(Message prototype, TableOption... overrides) {
        Message defaultInstance = prototype.getDefaultInstanceForType();
        Descriptor descriptor = defaultInstance.getDescriptorForType();
        TableOption.Spec spec = new TableOption.Spec(descriptor.getFullName());
        for (TableOption option : DescriptorOptions.tableOptions(descriptor)) {
            option.applyTo(spec);
        }
        for (TableOption option : overrides) {
            option.applyTo(spec);
        }
        TableSchema schema = new TableSchema(defaultInstance, spec);
        schema.validateSchemaDefinition();
        if (schema.tableName.equals(descriptor.getFullName())) {
            log.warn("表 {} 没有声明 table_name 选项，表名退化为 proto full name。proto 的 package 一改表名就跟着变，"
                    + "会建出一张空表而旧数据留在旧表里，且两边都不报错。建议在 .proto 里显式写 option (proto2mysql.table_name)",
                    descriptor.getFullName());
        }
        return schema;
    }

    /** 只有描述符时（动态消息）：读出的消息是 {@link DynamicMessage}。 */
    public static TableSchema of(Descriptor descriptor, TableOption... overrides) {
        return of(DynamicMessage.getDefaultInstance(descriptor), overrides);
    }

    // ================================================================ 访问器

    /** SQL 里的表名。 */
    public String tableName() {
        return tableName;
    }

    /** 消息描述符。 */
    public Descriptor descriptor() {
        return descriptor;
    }

    /** 该消息类型的默认实例。 */
    public Message prototype() {
        return prototype;
    }

    /** 主键字段（声明顺序）；空表示没有主键。 */
    public List<String> primaryKey() {
        return primaryKey;
    }

    /** 普通索引（每项一个索引，项内逗号分隔 = 联合索引）。 */
    public List<String> indexes() {
        return indexes;
    }

    /** 唯一键（逗号分隔）；空串表示没有。 */
    public String uniqueKey() {
        return uniqueKey;
    }

    /** 自增字段名；空串表示没有。 */
    public String autoIncrementKey() {
        return autoIncrementKey;
    }

    boolean tidbNonclusteredPk() {
        return tidbNonclusteredPk;
    }

    /** 全部字段（声明顺序 = 列顺序）。 */
    List<FieldDescriptor> fields() {
        return fields;
    }

    FieldDescriptor field(String name) {
        return fieldsByName.get(name);
    }

    boolean isNullableField(String name) {
        return nullableFields.contains(name);
    }

    boolean isAutoIncrementField(String name) {
        return autoIncrementKey.equals(name);
    }

    // ================================================================ 列类型

    /** 字段对应的 MySQL 列类型（不含列注释），与 Go 版 getMySQLFieldType 相同。 */
    public String columnType(FieldDescriptor fd) {
        // Timestamp：DATETIME(6)（不带小数秒精度会把毫秒静默截断到整秒），恒定可空（未设置写 NULL），不受 nullable 影响
        if (RowCodec.isTimestampMessage(fd)) {
            return "DATETIME(6)";
        }
        if (fd.isRepeated()) {
            return "MEDIUMBLOB";
        }
        // 主键 / 唯一键里的 string / bytes 建整列索引：区分大小写、NO PAD，固定 NOT NULL DEFAULT ''（写入从不写 NULL）
        FieldDescriptor.Type keyKind = keyColumnKind(fd);
        if (keyKind != null) {
            long length = keyColumnLength(fd.getName());
            if (keyKind == FieldDescriptor.Type.STRING) {
                return "VARCHAR(" + length + ") CHARACTER SET utf8mb4 COLLATE " + KEY_STRING_COLLATION + " NOT NULL DEFAULT ''";
            }
            return "VARBINARY(" + length + ") NOT NULL DEFAULT ''";
        }
        String baseType = FIELD_TYPES.getOrDefault(fd.getType(), "TEXT");
        if (isNullableField(fd.getName())) {
            baseType = baseType.replace(" NOT NULL", "");
        }
        if (isAutoIncrementField(fd.getName())) {
            // 自增列去掉 DEFAULT 0（否则 Error 1067）
            baseType = baseType.replace(" DEFAULT 0", "") + " AUTO_INCREMENT";
        }
        return baseType;
    }

    /** 字段是否出现在主键或唯一键里（唯一键按建表处同样拆分并去空白）。 */
    boolean isKeyColumnName(String name) {
        if (primaryKey.contains(name)) {
            return true;
        }
        if (uniqueKey.isEmpty()) {
            return false;
        }
        for (String col : MysqlSyntax.goSplit(uniqueKey, ',')) {
            if (goTrimSpace(col).equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 主键 / 唯一键里的非容器 string / bytes 字段返回其类型，否则 null。 */
    FieldDescriptor.Type keyColumnKind(FieldDescriptor fd) {
        if (fd == null || fd.isRepeated()) {
            return null;
        }
        FieldDescriptor.Type type = fd.getType();
        if (type != FieldDescriptor.Type.STRING && type != FieldDescriptor.Type.BYTES) {
            return null;
        }
        return isKeyColumnName(fd.getName()) ? type : null;
    }

    long keyColumnLength(String fieldName) {
        Long n = maxLengths.get(fieldName);
        return n != null ? n : DEFAULT_KEY_COLUMN_LENGTH;
    }

    /** 该列建索引时是否必须带前缀长度（TEXT / BLOB 系列）。 */
    boolean needsIndexPrefix(String column) {
        FieldDescriptor fd = fieldsByName.get(column);
        if (fd == null) {
            return false;
        }
        String type = columnType(fd).toUpperCase(Locale.ROOT);
        return type.contains("TEXT") || type.contains("BLOB");
    }

    /** 索引里的一列，必要时补前缀长度。 */
    String indexColumn(String column) {
        String escaped = escapeName(column);
        return needsIndexPrefix(column) ? escaped + "(" + TEXT_INDEX_PREFIX_LENGTH + ")" : escaped;
    }

    /** 与建表、补索引同一种写法拼索引列（逗号拆分、逐项去空白、必要时带前缀）。 */
    String indexColumnsSql(String spec) {
        List<String> quoted = new ArrayList<>();
        for (String col : MysqlSyntax.goSplit(spec, ',')) {
            quoted.add(indexColumn(goTrimSpace(col)));
        }
        return String.join(",", quoted);
    }

    /** 第 idx 个普通索引的名字（建表与补索引的唯一取名处）。 */
    String indexName(int idx) {
        return MysqlSyntax.truncateIdentifier("idx_" + tableName + "_" + idx);
    }

    /** 唯一键的名字。 */
    String uniqueKeyName() {
        return MysqlSyntax.truncateIdentifier("uk_" + tableName);
    }

    static String columnComment(int fieldNumber) {
        return " COMMENT 'pb:" + fieldNumber + "'";
    }

    // ================================================================ DDL

    /** 建表语句（{@code CREATE TABLE IF NOT EXISTS}，多行、两空格缩进、带结尾分号），与 Go 版 GetCreateTableSQL 逐字节相同。 */
    public String createTableSql() {
        return buildCreateTableSql() + ";";
    }

    /** 同 {@link #createTableSql()}，但不带结尾分号（交给 JDBC 执行）。 */
    String buildCreateTableSql() {
        List<String> entries = new ArrayList<>();
        for (FieldDescriptor fd : fields) {
            entries.add(escapeName(fd.getName()) + " " + columnType(fd) + columnComment(fd.getNumber()));
        }
        if (!primaryKey.isEmpty()) {
            List<String> cols = new ArrayList<>();
            for (String pk : primaryKey) {
                cols.add(indexColumn(pk));
            }
            String clause = "PRIMARY KEY (" + String.join(",", cols) + ")";
            if (tidbNonclusteredPk) {
                clause += TIDB_NONCLUSTERED_PK_SQL;
            }
            entries.add(clause);
        }
        for (int i = 0; i < indexes.size(); i++) {
            entries.add("INDEX " + escapeName(indexName(i)) + " (" + indexColumnsSql(indexes.get(i)) + ")");
        }
        if (!uniqueKey.isEmpty()) {
            for (String col : MysqlSyntax.splitTrimmed(uniqueKey)) {
                if (needsIndexPrefix(col)) {
                    log.warn("unique key on TEXT/BLOB column {} in table {} only enforces uniqueness over the first {} characters",
                            col, tableName, TEXT_INDEX_PREFIX_LENGTH);
                }
            }
            entries.add("UNIQUE KEY " + escapeName(uniqueKeyName()) + " (" + indexColumnsSql(uniqueKey) + ")");
        }
        return "CREATE TABLE IF NOT EXISTS " + escapeName(tableName) + " (\n  " + String.join(",\n  ", entries) + "\n)"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=" + DEFAULT_TABLE_COLLATION
                + tidbTableOptionsSql() + " COMMENT='" + MysqlSyntax.escapeComment(tableName) + "'";
    }

    /**
     * 表级 TiDB 方言片段（含前导空格），顺序同 TiDB SHOW CREATE TABLE：AUTO_ID_CACHE 块在前、SHARD 块在后。
     * 两条 fail-safe：有主键却没声明 NONCLUSTERED 时忽略 SHARD_ROW_ID_BITS（连带 PRE_SPLIT_REGIONS）；
     * PRE_SPLIT_REGIONS 超过 SHARD_ROW_ID_BITS 时收敛到后者。
     */
    String tidbTableOptionsSql() {
        StringBuilder b = new StringBuilder();
        if (tidbAutoIdCacheOne) {
            b.append(" /*T![auto_id_cache] AUTO_ID_CACHE=1 */");
        }
        long shardBits = tidbShardRowIdBits;
        if (shardBits > 0 && !primaryKey.isEmpty() && !tidbNonclusteredPk) {
            log.warn("proto2mysql: 表 {} 声明了 SHARD_ROW_ID_BITS 但主键未声明 NONCLUSTERED，TiDB 聚簇表不支持该选项，已忽略"
                    + "（请补 tidb_nonclustered_pk）", tableName);
            shardBits = 0;
        }
        if (shardBits > 0) {
            b.append(" /*T! SHARD_ROW_ID_BITS=").append(shardBits);
            long pre = tidbPreSplitRegions;
            if (pre > 0) {
                if (pre > shardBits) {
                    log.warn("proto2mysql: 表 {} 的 PRE_SPLIT_REGIONS={} 超过 SHARD_ROW_ID_BITS={}，已收敛到 {}（TiDB 要求前者 ≤ 后者）",
                            tableName, pre, shardBits, shardBits);
                    pre = shardBits;
                }
                b.append(" PRE_SPLIT_REGIONS=").append(pre);
            }
            b.append(" */");
        }
        return b.toString();
    }

    // ================================================================ 校验

    private void validateSchemaDefinition() {
        validateFieldKinds();
        validateTableOptions();
    }

    private static InvalidTableDefinitionException unsupported(String message) {
        return new InvalidTableDefinitionException(UNSUPPORTED_FIELD_KIND, message);
    }

    private static InvalidTableDefinitionException invalidOption(String message) {
        return new InvalidTableDefinitionException(INVALID_TABLE_OPTION, message);
    }

    private void validateFieldKinds() {
        if (tableName == null || goTrimSpace(tableName).isEmpty()) {
            throw invalidOption("table_name 不能为空或只含空白");
        }
        int nameLength = MysqlSyntax.runeCount(tableName);
        if (nameLength > MysqlSyntax.MAX_IDENTIFIER_LENGTH) {
            throw unsupported(String.format("表名 %s 有 %d 个字符，超过 MySQL 的 %d 上限。"
                            + "没声明 table_name 时表名会退化成 proto full name（含 package），"
                            + "请在 .proto 里显式写 option (proto2mysql.table_name)",
                    goQuote(tableName), nameLength, MysqlSyntax.MAX_IDENTIFIER_LENGTH));
        }
        if (fields.isEmpty()) {
            throw unsupported(String.format("表 %s 的 protobuf message 没有字段，无法生成合法的 MySQL 表", tableName));
        }
        for (FieldDescriptor fd : fields) {
            int n = MysqlSyntax.runeCount(fd.getName());
            if (n > MysqlSyntax.MAX_IDENTIFIER_LENGTH) {
                throw unsupported(String.format("表 %s 的字段名 %s 有 %d 个字符，超过 MySQL 标识符的 %d 上限",
                        tableName, goQuote(fd.getName()), n, MysqlSyntax.MAX_IDENTIFIER_LENGTH));
            }
            OneofDescriptor oneof = fd.getRealContainingOneof();
            if (oneof != null) {
                throw unsupported(String.format("表 %s 的字段 %s 属于 oneof %s。"
                                + "本库把每个字段各映射成一列，没有\"未选中\"的表示：写入时未选中的成员按零值落列，"
                                + "读取时按声明顺序逐个 Set 又会让**最后声明的成员恒胜**，oneof 的真实选择被静默抹掉。"
                                + "请把 oneof 拆成普通字段（另加一个 enum 字段标记当前用哪个），"
                                + "或把整个 oneof 包进一个子 message 字段（子 message 整体序列化进一列，往返正常）",
                        tableName, fd.getName(), goQuote(oneof.getName())));
            }
            if (fd.isRepeated() && !fd.isMapField() && RowCodec.isTimestampMessage(fd)) {
                // Java 版独有：Go 版把它建成 DATETIME(6)，写入的却是序列化字节，任何写入都会被 MySQL 拒绝
                throw unsupported(String.format("表 %s 的字段 %s 是 repeated google.protobuf.Timestamp："
                                + "列类型会是 DATETIME(6)，而写入的是序列化字节（空列表是空串），这张表上的任何写入都会被 MySQL 拒绝。"
                                + "请把它包进一个子 message 字段（整体序列化进 MEDIUMBLOB）",
                        tableName, fd.getName()));
            }
            if (fd.isRepeated() || fd.getType() == FieldDescriptor.Type.MESSAGE) {
                continue;
            }
            if (!FIELD_TYPES.containsKey(fd.getType())) {
                throw unsupported(String.format("表 %s 的字段 %s（%s）。"
                                + "sint32/sint64/fixed32/fixed64/sfixed32/sfixed64 都不支持，"
                                + "改用 int32/int64/uint32/uint64 即可（取值范围一样，只是线上编码不同）",
                        tableName, fd.getName(), RowCodec.typeName(fd)));
            }
        }
    }

    private FieldDescriptor lookup(String option, String name) {
        if (name.isEmpty() || !goTrimSpace(name).equals(name)) {
            throw invalidOption(String.format("表 %s 的 %s 含空字段名或首尾空白 %s", tableName, option, goQuote(name)));
        }
        FieldDescriptor fd = fieldsByName.get(name);
        if (fd == null) {
            throw invalidOption(String.format("表 %s 的 %s 引用了不存在的字段 %s", tableName, option, goQuote(name)));
        }
        return fd;
    }

    private void validateKeyColumns(String option, List<String> rawColumns, boolean trim) {
        if (rawColumns.isEmpty()) {
            throw invalidOption(String.format("表 %s 的 %s 没有字段", tableName, option));
        }
        Set<String> seen = new HashSet<>();
        for (String raw : rawColumns) {
            String name = trim ? goTrimSpace(raw) : raw;
            if (name.isEmpty()) {
                throw invalidOption(String.format("表 %s 的 %s 含空字段分量", tableName, option));
            }
            if (!seen.add(name)) {
                throw invalidOption(String.format("表 %s 的 %s 重复引用字段 %s", tableName, option, goQuote(name)));
            }
            lookup(option, name);
        }
    }

    private void validateTableOptions() {
        if (!primaryKey.isEmpty()) {
            validateKeyColumns("primary_key", primaryKey, false);
            for (String name : primaryKey) {
                FieldDescriptor fd = fieldsByName.get(name);
                String targetType = columnType(fd);
                if (fd.getType() == FieldDescriptor.Type.FLOAT || fd.getType() == FieldDescriptor.Type.DOUBLE) {
                    throw invalidOption(String.format("表 %s 的主键字段 %s 是 %s；浮点值不能作为稳定身份，"
                                    + "其十进制、二进制与数据库比较语义可能不一致；请改用整数或枚举主键",
                            tableName, goQuote(name), RowCodec.typeName(fd)));
                }
                if (needsIndexPrefix(name)) {
                    throw invalidOption(String.format("表 %s 的主键字段 %s 映射为 %s，只能建立前缀索引，"
                                    + "不能保证完整主键唯一性。标量 string/bytes 主键会映射为 VARCHAR/VARBINARY 整列索引，"
                                    + "但 repeated/map/message 这类整体序列化进 BLOB 的字段不行；请改用整数/枚举或标量 string/bytes 字段",
                            tableName, goQuote(name), targetType));
                }
                if (!targetType.toUpperCase(Locale.ROOT).contains("NOT NULL")) {
                    throw invalidOption(String.format("表 %s 的主键字段 %s 目标类型 %s 可为 NULL；"
                                    + "MySQL 会静默强制成 NOT NULL 并造成永久 schema drift",
                            tableName, goQuote(name), targetType));
                }
            }
        }
        for (int i = 0; i < indexes.size(); i++) {
            // 保留 split 产生的空分量：生成路径同样逐分量输出，"id,,port" 必须在这里被拒
            validateKeyColumns("index[" + i + "]", MysqlSyntax.goSplit(indexes.get(i), ','), true);
        }
        if (!uniqueKey.isEmpty()) {
            validateKeyColumns("unique_key", MysqlSyntax.goSplit(uniqueKey, ','), true);
        }
        for (String name : nullableFields) {
            FieldDescriptor fd = lookup("nullable", name);
            FieldDescriptor.Type kind = keyColumnKind(fd);
            if (kind != null) {
                throw invalidOption(String.format("表 %s 的 %s 字段 %s 在主键/唯一键里，不能声明 nullable："
                                + "写入路径从不为 string/bytes 写 NULL（未赋值一律写 ''），nullable 无法让未赋值的行不参与唯一性，"
                                + "只会让列定义与写入语义不一致",
                        tableName, kind.name().toLowerCase(Locale.ROOT), goQuote(name)));
            }
            if (primaryKey.contains(name)) {
                throw invalidOption(String.format("表 %s 的主键字段 %s 不能同时声明 nullable", tableName, goQuote(name)));
            }
        }
        validateMaxLengths();
        validateIndexKeyBytes();

        if (autoIncrementKey.isEmpty()) {
            return;
        }
        FieldDescriptor autoField = lookup("auto_increment_key", autoIncrementKey);
        if (autoField.isRepeated() || !isAutoIncrementType(autoField.getType())) {
            throw invalidOption(String.format("表 %s 的 auto_increment_key %s 必须是 int32/int64/uint32/uint64 标量字段，实际为 %s",
                    tableName, goQuote(autoIncrementKey), RowCodec.typeName(autoField)));
        }
        if (isNullableField(autoIncrementKey)) {
            throw invalidOption(String.format("表 %s 的 auto_increment_key %s 不能同时声明 nullable",
                    tableName, goQuote(autoIncrementKey)));
        }
        if (!autoIncrementIsFirstKeyColumn()) {
            throw invalidOption(String.format("表 %s 的 auto_increment_key %s 必须是某个主键/普通索引/唯一键的第一列",
                    tableName, goQuote(autoIncrementKey)));
        }
    }

    private static boolean isAutoIncrementType(FieldDescriptor.Type type) {
        return type == FieldDescriptor.Type.INT32 || type == FieldDescriptor.Type.INT64
                || type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.UINT64;
    }

    private boolean autoIncrementIsFirstKeyColumn() {
        if (!primaryKey.isEmpty() && primaryKey.get(0).equals(autoIncrementKey)) {
            return true;
        }
        for (String spec : indexes) {
            List<String> cols = MysqlSyntax.splitTrimmed(spec);
            if (!cols.isEmpty() && cols.get(0).equals(autoIncrementKey)) {
                return true;
            }
        }
        List<String> cols = MysqlSyntax.splitTrimmed(uniqueKey);
        return !cols.isEmpty() && cols.get(0).equals(autoIncrementKey);
    }

    /** max_length 只作用于主键 / 唯一键里的 string / bytes 字段，取值须能建整列索引；按字段名排序逐个检查。 */
    private void validateMaxLengths() {
        for (Map.Entry<String, Long> entry : new TreeMap<>(maxLengths).entrySet()) {
            String name = entry.getKey();
            FieldDescriptor fd = lookup("max_length", name);
            FieldDescriptor.Type kind = keyColumnKind(fd);
            if (kind == null) {
                throw invalidOption(String.format("表 %s 的字段 %s（%s）声明了 max_length，但 max_length 目前仅用于主键/唯一键的 string/bytes 字段；"
                                + "若这条声明来自 proto 而代码改掉了键，用 withMaxLengths 整体替换（传 null 即清空）",
                        tableName, goQuote(name), describeKeyCandidate(fd)));
            }
            long n = entry.getValue();
            long limit = kind == FieldDescriptor.Type.BYTES ? MAX_KEY_BYTES_LENGTH : MAX_KEY_STRING_LENGTH;
            String unit = kind == FieldDescriptor.Type.BYTES ? "个字节" : "个字符";
            if (n == 0 || n > limit) {
                throw invalidOption(String.format("表 %s 的 %s 键列 %s 的 max_length=%d 越界，取值须在 1..%d %s之间"
                                + "（单个索引键最多 %d 字节，VARCHAR 每字符按 4 字节、VARBINARY 每字节按 1 字节计）",
                        tableName, kind.name().toLowerCase(Locale.ROOT), goQuote(name), n, limit, unit, MAX_INDEX_KEY_BYTES));
            }
        }
    }

    private static String describeKeyCandidate(FieldDescriptor fd) {
        if (fd.isMapField()) {
            return "map";
        }
        if (fd.isRepeated()) {
            return "repeated " + RowCodec.typeName(fd);
        }
        if (fd.getType() == FieldDescriptor.Type.STRING || fd.getType() == FieldDescriptor.Type.BYTES) {
            return RowCodec.typeName(fd) + "，不在主键/唯一键里";
        }
        return RowCodec.typeName(fd);
    }

    /** 主键、唯一键、每个普通索引各自的键长不得超过 3072 字节；超出时逐列列出占用。 */
    private void validateIndexKeyBytes() {
        if (!primaryKey.isEmpty()) {
            checkIndexKeyBytes("primary_key", primaryKey);
        }
        if (!uniqueKey.isEmpty()) {
            checkIndexKeyBytes("unique_key", MysqlSyntax.splitTrimmed(uniqueKey));
        }
        for (int i = 0; i < indexes.size(); i++) {
            checkIndexKeyBytes("index[" + i + "]", MysqlSyntax.splitTrimmed(indexes.get(i)));
        }
    }

    private void checkIndexKeyBytes(String option, List<String> columns) {
        long total = 0;
        List<String> parts = new ArrayList<>();
        for (String name : columns) {
            KeyPart part = indexKeyPartBytes(fieldsByName.get(name));
            total += part.bytes();
            parts.add(name + " " + part.how() + "=" + part.bytes());
        }
        if (total <= MAX_INDEX_KEY_BYTES) {
            return;
        }
        throw invalidOption(String.format("表 %s 的 %s 键长 %d 字节，超过单个索引 %d 字节的上限（建表会报 Error 1071）：%s；"
                        + "请调小 string/bytes 键列的 max_length 或减少索引列",
                tableName, option, total, MAX_INDEX_KEY_BYTES, String.join("，", parts)));
    }

    private record KeyPart(long bytes, String how) {
    }

    /** 一列在索引键里占的字节数及算法说明，判定顺序与 {@link #columnType} 一致。 */
    private KeyPart indexKeyPartBytes(FieldDescriptor fd) {
        if (RowCodec.isTimestampMessage(fd)) {
            return new KeyPart(8, "DATETIME(6)");
        }
        if (fd.isRepeated()) {
            return new KeyPart(TEXT_INDEX_PREFIX_LENGTH, "MEDIUMBLOB 前缀(" + TEXT_INDEX_PREFIX_LENGTH + ")");
        }
        FieldDescriptor.Type keyKind = keyColumnKind(fd);
        if (keyKind != null) {
            long n = keyColumnLength(fd.getName());
            if (keyKind == FieldDescriptor.Type.STRING) {
                return new KeyPart(4 * n, "VARCHAR(" + n + ")×4");
            }
            return new KeyPart(n, "VARBINARY(" + n + ")");
        }
        return switch (fd.getType()) {
            case STRING -> new KeyPart(4L * TEXT_INDEX_PREFIX_LENGTH, "MEDIUMTEXT 前缀(" + TEXT_INDEX_PREFIX_LENGTH + ")×4");
            case BYTES, MESSAGE -> new KeyPart(TEXT_INDEX_PREFIX_LENGTH, "MEDIUMBLOB 前缀(" + TEXT_INDEX_PREFIX_LENGTH + ")");
            case INT32, UINT32, ENUM, FLOAT -> new KeyPart(4, RowCodec.typeName(fd));
            case INT64, UINT64, DOUBLE -> new KeyPart(8, RowCodec.typeName(fd));
            case BOOL -> new KeyPart(1, RowCodec.typeName(fd));
            default -> new KeyPart(0, RowCodec.typeName(fd));
        };
    }

    // ================================================================ 键值校验与参数

    /**
     * 整行的列值（声明顺序），键列的长度与编码在这里校验，一定发生在任何 SQL 发出之前。
     *
     * @throws InvalidKeyValueException 键列值超长或不是合法 UTF-8
     * @throws RowConversionException   浮点 NaN / Inf 等无法落库的值
     */
    List<Object> columnValues(MessageOrBuilder message) {
        requireSameType(message);
        List<Object> values = new ArrayList<>(fields.size());
        for (FieldDescriptor fd : fields) {
            values.add(columnValue(message, fd));
        }
        return values;
    }

    /** 单列的值（键列带长度 / 编码校验；非键 string 列只校验编码）。 */
    Object columnValue(MessageOrBuilder message, FieldDescriptor fd) {
        Object value = RowCodec.toColumnValue(message, fd);
        if (keyColumnKind(fd) != null) {
            checkKeyColumnValue(fd, value);
        } else if (value instanceof String s && !MysqlSyntax.isWellFormedUtf16(s)) {
            // Go 版靠 MySQL 1366 fail-closed；Connector/J 会把落单代理项静默编成 '?'，这里先拒
            throw new RowConversionException("string field " + fd.getName()
                    + " is not valid UTF-16 (unpaired surrogate); utf8mb4 cannot store it losslessly");
        }
        return value;
    }

    /** 主键列的值（主键声明顺序）。 */
    List<Object> primaryKeyValues(MessageOrBuilder message) {
        requirePrimaryKey();
        requireSameType(message);
        List<Object> values = new ArrayList<>(primaryKey.size());
        for (String name : primaryKey) {
            values.add(columnValue(message, fieldsByName.get(name)));
        }
        return values;
    }

    /**
     * string 键列按字符数对照 VARCHAR(N) 并要求合法 UTF-8，bytes 键列按字节数对照 VARBINARY(N)；空串合法。
     *
     * <p>与 Go 版的差异：这里拿到的已经是 Java {@code String}，只能检出落单代理项。proto2 / 关闭 utf8_validation 的
     * string 字段在 protobuf-java 解码时已把非法 UTF-8 字节换成 U+FFFD（Go 保留原字节并拒绝），两个只差非法字节的 ID
     * 会落成同一个键——这种 ID 应当声明成 bytes 键列。
     */
    void checkKeyColumnValue(FieldDescriptor fd, Object value) {
        FieldDescriptor.Type kind = keyColumnKind(fd);
        if (kind == null) {
            return;
        }
        String name = fd.getName();
        long limit = keyColumnLength(name);
        if (kind == FieldDescriptor.Type.BYTES) {
            int n = ((byte[]) value).length;
            if (n > limit) {
                throw new InvalidKeyValueException(String.format("表 %s 的键列 %s 长 %d 字节，超过 VARBINARY(%d) 的上限",
                        tableName, name, n, limit));
            }
            return;
        }
        String text = (String) value;
        if (!MysqlSyntax.isWellFormedUtf16(text)) {
            throw new InvalidKeyValueException(String.format("表 %s 的键列 %s 不是合法 UTF-8（%d 字节），utf8mb4 列无法原样存储",
                    tableName, name, text.getBytes(StandardCharsets.UTF_8).length));
        }
        int n = MysqlSyntax.runeCount(text);
        if (n > limit) {
            throw new InvalidKeyValueException(String.format("表 %s 的键列 %s 长 %d 个字符，超过 VARCHAR(%d) 的上限",
                    tableName, name, n, limit));
        }
    }

    void requireSameType(MessageOrBuilder message) {
        if (message == null) {
            throw new PbMysqlException("message cannot be null");
        }
        if (message.getDescriptorForType() != descriptor) {
            throw new PbMysqlException(String.format("message descriptor %s does not match table %s",
                    message.getDescriptorForType().getFullName(), tableName));
        }
    }

    void requirePrimaryKey() {
        if (primaryKey.isEmpty()) {
            throw new PbMysqlException("primary key not found: 表 " + tableName + " 没有声明主键");
        }
    }

    // ================================================================ DML 片段

    /** {@code `a`, `b`, ...}（声明顺序）。 */
    String columnListSql() {
        return columnListSql;
    }

    /** {@code SELECT `a`, `b` FROM `t`}。 */
    String selectSql() {
        return selectSql;
    }

    /** {@code INSERT INTO `t` (`a`, `b`) VALUES (?, ?)}。 */
    String insertSql() {
        return insertSql;
    }

    /** {@code `pk1` = ? AND `pk2` = ?}。 */
    String primaryKeyWhereSql() {
        requirePrimaryKey();
        List<String> parts = new ArrayList<>();
        for (String pk : primaryKey) {
            parts.add(escapeName(pk) + " = ?");
        }
        return String.join(" AND ", parts);
    }

    /** 非主键字段（声明顺序）。 */
    List<FieldDescriptor> nonPrimaryKeyFields() {
        List<FieldDescriptor> out = new ArrayList<>();
        for (FieldDescriptor fd : fields) {
            if (!primaryKey.contains(fd.getName())) {
                out.add(fd);
            }
        }
        return out;
    }

    /**
     * 单语句 upsert：INSERT ... ON DUPLICATE KEY UPDATE，每个非主键列都受完整主键守卫
     * （{@code c = IF(pk <=> VALUES(pk), VALUES(c), c)}），撞上备用唯一键的另一行时所有列保持原值。
     * 与 Go 版 GetSaveSQLWithArgs 相同；参数就是 {@link #insertSql()} 的参数。
     */
    String upsertSql() {
        requirePrimaryKey();
        List<String> identity = new ArrayList<>();
        for (String pk : primaryKey) {
            String name = escapeName(pk);
            identity.add(name + " <=> VALUES(" + name + ")");
        }
        String guard = String.join(" AND ", identity);
        List<String> parts = new ArrayList<>();
        for (FieldDescriptor fd : nonPrimaryKeyFields()) {
            String name = escapeName(fd.getName());
            parts.add(name + " = IF(" + guard + ", VALUES(" + name + "), " + name + ")");
        }
        if (parts.isEmpty()) {
            // 整张表只有主键列：ODKU 子句不能为空，用合法 no-op 占位
            String pk = escapeName(primaryKey.get(0));
            parts.add(pk + " = " + pk);
        }
        return insertSql + " ON DUPLICATE KEY UPDATE " + String.join(", ", parts);
    }

    /**
     * 按完整主键覆盖全部非主键列（零值 / 未设置字段照写）：{@code UPDATE `t` SET `c` = ?, ... WHERE pk}。
     * 参数顺序：全部非主键值，再全部主键值。整张表只有主键列时 SET 子句用 {@code pk = pk} 占位。
     */
    String fullRowUpdateSql() {
        requirePrimaryKey();
        List<String> sets = new ArrayList<>();
        for (FieldDescriptor fd : nonPrimaryKeyFields()) {
            sets.add(escapeName(fd.getName()) + " = ?");
        }
        if (sets.isEmpty()) {
            String pk = escapeName(primaryKey.get(0));
            sets.add(pk + " = " + pk);
        }
        return "UPDATE " + escapeName(tableName) + " SET " + String.join(", ", sets) + " WHERE " + primaryKeyWhereSql();
    }

    /**
     * Save 在 INSERT 撞 1062、重试 UPDATE 仍为 0 时的最终分类查询：按主键定位并逐列比较（字符串 / 二进制按字节比较），
     * FOR UPDATE 强制当前读。参数顺序：全部主键值，再全部非主键值。
     */
    String saveMatchSql() {
        requirePrimaryKey();
        List<String> parts = new ArrayList<>();
        for (String pk : primaryKey) {
            parts.add(escapeName(pk) + " = ?");
        }
        for (FieldDescriptor fd : nonPrimaryKeyFields()) {
            String name = escapeName(fd.getName());
            if (needsBinaryComparison(fd)) {
                parts.add("CAST(" + name + " AS BINARY) <=> CAST(? AS BINARY)");
            } else {
                parts.add(name + " <=> ?");
            }
        }
        return "SELECT 1 FROM " + escapeName(tableName) + " WHERE " + String.join(" AND ", parts) + " FOR UPDATE";
    }

    private static boolean needsBinaryComparison(FieldDescriptor fd) {
        if (fd.isRepeated()) {
            return true;
        }
        return switch (fd.getType()) {
            case STRING, BYTES -> true;
            case MESSAGE, GROUP -> !RowCodec.isTimestampMessage(fd);
            default -> false;
        };
    }

    @Override
    public String toString() {
        return "TableSchema[" + tableName + " ← " + descriptor.getFullName() + "]";
    }
}
