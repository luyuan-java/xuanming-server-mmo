package com.game.pbmysql;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从 proto 描述符读取建表选项（message option / field option / file option），对应 Go 版 options.go。
 *
 * <p><b>按扩展字段号读，不认扩展类型</b>：同一个号既可能是本模块 {@code proto2mysql/proto2mysql_option.proto} 生成的扩展，
 * 也可能是 mmorpg {@code proto/db/proto_option.proto} 里同号的 {@code OptionTableName} 等，还可能根本没有被链接进来。
 * 所以两条路都走：
 * <ul>
 *   <li>已知扩展：生成代码在构建描述符时把用到的扩展注册进去了，选项出现在 {@code getAllFields()} 里；</li>
 *   <li>unknown fields：选项定义没被链接（或生成代码旧于本库的选项号）时，选项留在 {@code getUnknownFields()} 里，
 *       按 wire 格式解出本库认识的号。已在已知扩展里出现过的号不再从 unknown fields 读。</li>
 * </ul>
 * 选项值类型与声明不符（例如别的库在同号上注册了非字符串扩展、wire 类型对不上）时跳过，不抛异常。
 */
public final class DescriptorOptions {

    /** file option：标记本 .proto 文件用于建表（纯用途声明）。 */
    public static final int FILE_DB = 500000;
    /** message option：表名。 */
    public static final int TABLE_NAME = 500001;
    /** message option：主键（逗号分隔 = 联合主键）。 */
    public static final int PRIMARY_KEY = 500002;
    /** message option：自增字段。 */
    public static final int AUTO_INCREMENT_KEY = 500006;
    /** message option：普通索引（分号分隔多个索引，索引内逗号分隔 = 联合索引）。 */
    public static final int INDEX = 500011;
    /** message option：唯一键（逗号分隔 = 联合唯一键）。 */
    public static final int UNIQUE_KEY = 500012;
    /** message option：TiDB 主键 NONCLUSTERED。 */
    public static final int TIDB_NONCLUSTERED_PK = 500021;
    /** message option：TiDB SHARD_ROW_ID_BITS。 */
    public static final int TIDB_SHARD_ROW_ID_BITS = 500022;
    /** message option：TiDB PRE_SPLIT_REGIONS。 */
    public static final int TIDB_PRE_SPLIT_REGIONS = 500023;
    /** message option：TiDB AUTO_ID_CACHE=1。 */
    public static final int TIDB_AUTO_ID_CACHE_ONE = 500024;
    /** field option：该列允许为 NULL。 */
    public static final int FIELD_NULLABLE = 600100;
    /** field option：主键 / 唯一键 string / bytes 列的最大长度。 */
    public static final int FIELD_MAX_LENGTH = 600101;

    private enum Kind { BOOL, STRING, UINT32 }

    /** 本库全部选项号 → 声明类型（与 Go 版 unknownOptionKinds 相同）。 */
    private static final Map<Integer, Kind> KINDS = Map.ofEntries(
            Map.entry(FILE_DB, Kind.BOOL),
            Map.entry(TABLE_NAME, Kind.STRING),
            Map.entry(PRIMARY_KEY, Kind.STRING),
            Map.entry(AUTO_INCREMENT_KEY, Kind.STRING),
            Map.entry(INDEX, Kind.STRING),
            Map.entry(UNIQUE_KEY, Kind.STRING),
            Map.entry(FIELD_NULLABLE, Kind.BOOL),
            Map.entry(FIELD_MAX_LENGTH, Kind.UINT32),
            Map.entry(TIDB_NONCLUSTERED_PK, Kind.BOOL),
            Map.entry(TIDB_SHARD_ROW_ID_BITS, Kind.UINT32),
            Map.entry(TIDB_PRE_SPLIT_REGIONS, Kind.UINT32),
            Map.entry(TIDB_AUTO_ID_CACHE_ONE, Kind.BOOL));

    private DescriptorOptions() {
    }

    /** 文件是否声明了 {@code option (proto2mysql.db) = true;}。 */
    public static boolean fileHasDbOption(FileDescriptor file) {
        boolean[] has = {false};
        forEachOption(file.getOptions(), (num, value) -> {
            if (num == FILE_DB && Boolean.TRUE.equals(value)) {
                has[0] = true;
            }
        });
        return has[0];
    }

    /** 消息声明的表名；没声明（或声明为空串）时为空。 */
    public static Optional<String> tableName(Descriptor message) {
        String[] name = {""};
        forEachOption(message.getOptions(), (num, value) -> {
            if (num == TABLE_NAME && value instanceof String s) {
                name[0] = s;
            }
        });
        return name[0].isEmpty() ? Optional.empty() : Optional.of(name[0]);
    }

    /**
     * 把描述符里的表选项转成 {@link TableOption} 列表（与 Go 版 TableOptionsFromDescriptor 逐项同语义）。
     * primary_key / index 保留空分量，交给校验 fail-closed（"id,,port" 不能被静默改写成两列）。
     */
    static List<TableOption> tableOptions(Descriptor message) {
        List<TableOption> opts = new ArrayList<>();
        forEachOption(message.getOptions(), (num, value) -> {
            switch (num) {
                case TABLE_NAME -> {
                    if (value instanceof String s && !s.isEmpty()) {
                        opts.add(TableOption.withTableName(s));
                    }
                }
                case PRIMARY_KEY -> {
                    if (value instanceof String s) {
                        opts.add(TableOption.withPrimaryKey(splitOptionList(s, ',').toArray(String[]::new)));
                    }
                }
                case AUTO_INCREMENT_KEY -> {
                    if (value instanceof String s && !MysqlSyntax.goTrimSpace(s).isEmpty()) {
                        opts.add(TableOption.withAutoIncrementKey(MysqlSyntax.goTrimSpace(s)));
                    }
                }
                case INDEX -> {
                    if (value instanceof String s) {
                        opts.add(TableOption.withIndexes(splitOptionList(s, ';').toArray(String[]::new)));
                    }
                }
                case UNIQUE_KEY -> {
                    if (value instanceof String s && !MysqlSyntax.goTrimSpace(s).isEmpty()) {
                        opts.add(TableOption.withUniqueKey(MysqlSyntax.goTrimSpace(s)));
                    }
                }
                case TIDB_NONCLUSTERED_PK -> {
                    if (Boolean.TRUE.equals(value)) {
                        opts.add(TableOption.withTiDBNonclusteredPK());
                    }
                }
                case TIDB_SHARD_ROW_ID_BITS -> {
                    if (value instanceof Long n && n > 0) {
                        opts.add(TableOption.withTiDBShardRowIDBits((int) (long) n));
                    }
                }
                case TIDB_PRE_SPLIT_REGIONS -> {
                    if (value instanceof Long n && n > 0) {
                        opts.add(TableOption.withTiDBPreSplitRegions((int) (long) n));
                    }
                }
                case TIDB_AUTO_ID_CACHE_ONE -> {
                    if (Boolean.TRUE.equals(value)) {
                        opts.add(TableOption.withTiDBAutoIDCacheOne());
                    }
                }
                default -> {
                }
            }
        });

        List<String> nullable = new ArrayList<>();
        for (FieldDescriptor field : message.getFields()) {
            forEachOption(field.getOptions(), (num, value) -> {
                if (num == FIELD_NULLABLE && Boolean.TRUE.equals(value)) {
                    nullable.add(field.getName());
                } else if (num == FIELD_MAX_LENGTH && value instanceof Long n) {
                    // 显式写 0 也要传下去交给校验拒绝，不能当成"没声明"而退回默认长度
                    opts.add(TableOption.withMaxLength(field.getName(), (int) (long) n));
                }
            });
        }
        if (!nullable.isEmpty()) {
            opts.add(TableOption.withNullableFields(nullable));
        }
        return opts;
    }

    /** 拆分逗号 / 分号分隔的选项值并去掉每项首尾空白，但故意保留空项（Go 版 splitOptionCSV / splitOptionIndexes）。 */
    private static List<String> splitOptionList(String value, char sep) {
        List<String> parts = MysqlSyntax.goSplit(value, sep);
        parts.replaceAll(MysqlSyntax::goTrimSpace);
        return parts;
    }

    @FunctionalInterface
    private interface OptionVisitor {
        void visit(int number, Object value);
    }

    /**
     * 遍历 options 消息上本库认识的选项：值统一成 Boolean / String / Long（uint32 的无符号值）。
     * 先遍历已知扩展，再补扫 unknown fields（同一个号出现在已知扩展里就不再读 unknown）。
     * unknown fields 里同一个号出现多次时按出现顺序逐个回调，与 Go 版逐个 wire 记录回调一致。
     */
    private static void forEachOption(Message options, OptionVisitor visitor) {
        if (options == null) {
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (Map.Entry<FieldDescriptor, Object> entry : options.getAllFields().entrySet()) {
            FieldDescriptor fd = entry.getKey();
            if (!fd.isExtension()) {
                continue;
            }
            seen.add(fd.getNumber());
            Kind kind = KINDS.get(fd.getNumber());
            if (kind == null || fd.isRepeated()) {
                continue;
            }
            Object value = entry.getValue();
            switch (kind) {
                case BOOL -> {
                    if (fd.getType() == FieldDescriptor.Type.BOOL && value instanceof Boolean b) {
                        visitor.visit(fd.getNumber(), b);
                    }
                }
                case STRING -> {
                    if (fd.getType() == FieldDescriptor.Type.STRING && value instanceof String s) {
                        visitor.visit(fd.getNumber(), s);
                    }
                }
                case UINT32 -> {
                    // 与 Go 版同口径：TiDB 两项用 Value.Uint()（uint32 / fixed32 / uint64 / fixed64 都收，截成 uint32），
                    // max_length 用 .(uint32) 断言（只收 uint32 / fixed32）
                    FieldDescriptor.Type type = fd.getType();
                    if ((type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32)
                            && value instanceof Integer i) {
                        visitor.visit(fd.getNumber(), Integer.toUnsignedLong(i));
                    } else if ((type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64)
                            && value instanceof Long l && fd.getNumber() != FIELD_MAX_LENGTH) {
                        visitor.visit(fd.getNumber(), l & 0xffffffffL);
                    }
                }
            }
        }

        UnknownFieldSet unknown = options.getUnknownFields();
        for (Map.Entry<Integer, UnknownFieldSet.Field> entry : unknown.asMap().entrySet()) {
            int number = entry.getKey();
            Kind kind = KINDS.get(number);
            if (kind == null || seen.contains(number)) {
                continue;
            }
            UnknownFieldSet.Field field = entry.getValue();
            switch (kind) {
                case BOOL -> {
                    for (Long v : field.getVarintList()) {
                        visitor.visit(number, v != 0);
                    }
                }
                case UINT32 -> {
                    for (Long v : field.getVarintList()) {
                        visitor.visit(number, v & 0xffffffffL);
                    }
                }
                case STRING -> {
                    for (ByteString v : field.getLengthDelimitedList()) {
                        visitor.visit(number, v.toStringUtf8());
                    }
                }
            }
        }
    }
}
