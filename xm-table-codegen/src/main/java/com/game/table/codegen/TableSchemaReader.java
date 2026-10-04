package com.game.table.codegen;

import com.game.table.codegen.TableSchema.Expression;
import com.game.table.codegen.TableSchema.Field;
import com.game.table.codegen.TableSchema.ForeignKey;
import com.game.table.codegen.TableSchema.Key;
import com.game.table.codegen.TableSchema.Kind;
import com.game.table.codegen.TableSchema.Table;
import com.game.table.codegen.TableSchema.TipRef;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从 protoc 描述符集读出 {@link TableSchema}。
 *
 * <p>{@code cfg_*} option 是 mmorpg {@code cfg_options.proto} 定义的扩展；这里不编译那份 proto，而是在描述符集里找到它，
 * 按扩展<b>名</b>查出扩展号，再从各 option 消息的未知字段里取值——扩展号以契约文件为唯一出处，不在代码里写死。
 *
 * <p>语义与 mmorpg 导表器（{@code tools/data_table_exporter/core/schema.py}）一致：
 * <ul>
 *   <li>主键 = {@code cfg_primary_key} 指定的列（缺省 {@code id}）；主键上标 {@code cfg_multi} = 主键可重复；</li>
 *   <li>{@code cfg_key} = 二级键，同时标 {@code cfg_multi} 为多值键；非主键标量列只标 {@code cfg_multi} 也按多值键处理（非标量忽略）；</li>
 *   <li>{@code cfg_index} = 索引（repeated 列按每个元素建）；</li>
 *   <li>{@code cfg_fk = "T"} / {@code "T.col"} 与 {@code cfg_gfk = "T"}（目标列恒为 id）= 外键；</li>
 *   <li>{@code cfg_owner} 不是 server / common 的列只给策划或客户端用，不进服务端产物，这里忽略。</li>
 * </ul>
 */
final class TableSchemaReader {

    private static final String MESSAGE_OPTIONS = ".google.protobuf.MessageOptions";
    private static final String FIELD_OPTIONS = ".google.protobuf.FieldOptions";
    private static final String DEFAULT_PRIMARY_KEY = "id";
    /** tip 码枚举所在的目录（同步来的 {@code xm-table/src/main/proto/tip/*.proto}；描述符集里的文件名相对 proto 源根）。 */
    static final String TIP_PROTO_DIR = "tip/";
    /** 导表器 validate_tip_references 认的整型（不含 fixed / 枚举 / bool）。 */
    private static final Set<FieldDescriptorProto.Type> TIP_INTEGER_TYPES = Set.of(
            FieldDescriptorProto.Type.TYPE_INT32, FieldDescriptorProto.Type.TYPE_UINT32, FieldDescriptorProto.Type.TYPE_INT64,
            FieldDescriptorProto.Type.TYPE_UINT64, FieldDescriptorProto.Type.TYPE_SINT32, FieldDescriptorProto.Type.TYPE_SINT64);
    /** 表达式里的白名单函数名（参数名不得与之相同）；与 xm-table 运行时 TableExpression 一致。 */
    static final Set<String> EXPRESSION_FUNCTIONS = Set.of("min", "max", "abs", "floor", "ceil", "random");

    private final Map<String, Integer> messageExt = new HashMap<>();
    private final Map<String, Integer> fieldExt = new HashMap<>();
    /** 全部顶层消息（全名 → 描述），结构体列据此找子列。 */
    private final Map<String, DescriptorProto> messages = new HashMap<>();

    private TableSchemaReader() {
    }

    /**
     * @throws SchemaException schema 不合法（键类型不支持、外键目标不存在、类型不一致……），消息可直接给人看
     */
    static TableSchema read(FileDescriptorSet set) {
        TableSchemaReader reader = new TableSchemaReader();
        reader.indexExtensions(set);
        return reader.readTables(set);
    }

    private void indexExtensions(FileDescriptorSet set) {
        for (FileDescriptorProto file : set.getFileList()) {
            for (FieldDescriptorProto ext : file.getExtensionList()) {
                if (!ext.getName().startsWith("cfg_")) {
                    continue;
                }
                Map<String, Integer> target = switch (ext.getExtendee()) {
                    case MESSAGE_OPTIONS -> messageExt;
                    case FIELD_OPTIONS -> fieldExt;
                    default -> null;
                };
                if (target != null) {
                    target.put(ext.getName(), ext.getNumber());
                }
            }
        }
    }

    private TableSchema readTables(FileDescriptorSet set) {
        if (!messageExt.containsKey("cfg_sheet")) {
            return new TableSchema("", List.of(), List.of());
        }
        List<Table> tables = new ArrayList<>();
        Set<String> packages = new HashSet<>();
        Set<String> sheets = new HashSet<>();
        for (FileDescriptorProto file : set.getFileList()) {
            for (DescriptorProto message : file.getMessageTypeList()) {
                messages.put((file.getPackage().isEmpty() ? "." : "." + file.getPackage() + ".") + message.getName(), message);
            }
        }
        for (FileDescriptorProto file : set.getFileList()) {
            for (DescriptorProto message : file.getMessageTypeList()) {
                Optional<String> sheet = stringOption(message.getOptions().getUnknownFields(), messageExt, "cfg_sheet");
                if (sheet.isEmpty()) {
                    continue;
                }
                if (!file.getOptions().getJavaMultipleFiles()) {
                    throw new SchemaException(file.getName() + ": 配置表 proto 必须 java_multiple_files = true（由 ContractSync 注入）");
                }
                if (!sheets.add(sheet.get())) {
                    throw new SchemaException("表名重复: " + sheet.get());
                }
                packages.add(file.getOptions().getJavaPackage());
                tables.add(readTable(sheet.get(), message));
            }
        }
        if (packages.size() > 1) {
            throw new SchemaException("配置表 proto 的 java_package 不一致: " + packages);
        }
        tables.sort(Comparator.comparing(Table::sheet));
        TableSchema schema = new TableSchema(packages.isEmpty() ? "" : packages.iterator().next(), List.copyOf(tables),
                tipCodes(set));
        validateForeignKeys(schema);
        validateTipRefs(schema);
        return schema;
    }

    private Table readTable(String sheet, DescriptorProto message) {
        UnknownFieldSet msgOpts = message.getOptions().getUnknownFields();
        String sourceFile = stringOption(msgOpts, messageExt, "cfg_source_file").orElse(sheet + ".xlsx");
        String pkName = stringOption(msgOpts, messageExt, "cfg_primary_key").orElse(DEFAULT_PRIMARY_KEY);

        Set<String> mapEntries = new HashSet<>();
        for (DescriptorProto nested : message.getNestedTypeList()) {
            if (nested.getOptions().getMapEntry()) {
                mapEntries.add(nested.getName());
            }
        }

        List<Field> fields = new ArrayList<>();
        Field primaryKey = null;
        boolean multiPrimaryKey = false;
        List<Key> keys = new ArrayList<>();
        List<Field> indexes = new ArrayList<>();
        List<ForeignKey> foreignKeys = new ArrayList<>();
        List<TipRef> tipRefs = new ArrayList<>();
        List<Expression> expressions = new ArrayList<>();
        for (FieldDescriptorProto fd : message.getFieldList()) {
            UnknownFieldSet opts = fd.getOptions().getUnknownFields();
            if (!isServerOwned(opts)) {
                continue;
            }
            Field field = toField(fd, message.getName(), mapEntries);
            fields.add(field);
            String where = sheet + "." + field.name();
            // tip 引用：同导表器 validate_tip_references——整型列标了 tip_ref，或列名含 tip（大小写不敏感）都纳入
            boolean explicitTip = boolOption(opts, "cfg_tip_ref");
            if (TIP_INTEGER_TYPES.contains(fd.getType()) && (explicitTip || field.name().toLowerCase(Locale.ROOT).contains("tip"))) {
                tipRefs.add(new TipRef(field, null, null));
            } else if (explicitTip) {
                throw new SchemaException(where + ": cfg_tip_ref 只能标在整型列上");
            }
            structTipRefs(where, fd, field, mapEntries, tipRefs);
            Optional<String> exprType = stringOption(opts, fieldExt, "cfg_expr_type");
            List<String> exprParams = stringOptions(opts, fieldExt, "cfg_expr_param");
            if (exprType.isPresent()) {
                expressions.add(expression(where, field, exprType.get().trim(), exprParams));
            } else if (!exprParams.isEmpty()) {
                throw new SchemaException(where + ": 有 cfg_expr_param 却没有 cfg_expr_type");
            }
            boolean multi = boolOption(opts, "cfg_multi");
            if (field.name().equals(pkName)) {
                requireScalarKey(where, field);
                primaryKey = field;
                multiPrimaryKey = multi;
            } else if (boolOption(opts, "cfg_key")) {
                requireScalarKey(where, field);
                keys.add(new Key(field, multi));
            } else if (multi && !field.repeated() && field.kind().keyable()) {
                // 非主键列只标 cfg_multi：mmorpg 不生成查找（只认 cfg_key 列），Java 给一个多值查找；
                // 非标量列上这样标 mmorpg 同样忽略，这里也忽略，不让导表器接受的 schema 卡住 Java 构建。
                keys.add(new Key(field, true));
            } else if (boolOption(opts, "cfg_index")) {
                if (!field.kind().keyable()) {
                    throw new SchemaException(where + ": cfg_index 不支持该字段类型");
                }
                indexes.add(field);
            }
            Optional<String> fk = stringOption(opts, fieldExt, "cfg_fk");
            Optional<String> gfk = stringOption(opts, fieldExt, "cfg_gfk");
            if (fk.isPresent() && gfk.isPresent()) {
                throw new SchemaException(where + ": cfg_fk 与 cfg_gfk 不能同时出现");
            }
            if (fk.isPresent()) {
                String ref = fk.get().trim();
                int dot = ref.indexOf('.');
                String targetSheet = dot < 0 ? ref : ref.substring(0, dot).trim();
                String targetColumn = dot < 0 ? DEFAULT_PRIMARY_KEY : ref.substring(dot + 1).trim();
                foreignKeys.add(new ForeignKey(field, targetSheet, targetColumn, "cfg_fk"));
            } else if (gfk.isPresent()) {
                foreignKeys.add(new ForeignKey(field, gfk.get().trim(), DEFAULT_PRIMARY_KEY, "cfg_gfk"));
            }
        }
        if (primaryKey == null) {
            throw new SchemaException(sheet + ": 找不到主键列 " + pkName);
        }
        return new Table(sheet, sourceFile, message.getName(), primaryKey, multiPrimaryKey,
                List.copyOf(keys), List.copyOf(indexes), List.copyOf(foreignKeys), List.copyOf(fields),
                List.copyOf(tipRefs), List.copyOf(expressions));
    }

    /**
     * 结构体列（非 map 的消息字段）展开后的 tip 子列：导表器按 Excel 列判定，结构体的子列在 Excel 里就是普通列（如 {@code state_tip}），
     * 同样按「整型 + 标了 tip_ref 或名字含 tip」纳入。
     */
    private void structTipRefs(String where, FieldDescriptorProto fd, Field field, Set<String> mapEntries, List<TipRef> out) {
        if (fd.getType() != FieldDescriptorProto.Type.TYPE_MESSAGE) {
            return;
        }
        String typeName = fd.getTypeName();
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        if (mapEntries.contains(simple)) {
            return;
        }
        DescriptorProto struct = messages.get(typeName);
        if (struct == null) {
            throw new SchemaException(where + ": 找不到结构体类型 " + typeName + "（只支持同一描述符集里的顶层消息）");
        }
        for (FieldDescriptorProto sub : struct.getFieldList()) {
            boolean explicit = boolOption(sub.getOptions().getUnknownFields(), "cfg_tip_ref");
            if (TIP_INTEGER_TYPES.contains(sub.getType()) && (explicit || sub.getName().toLowerCase(Locale.ROOT).contains("tip"))) {
                out.add(new TipRef(field, simple, toField(sub, struct.getName(), Set.of())));
            } else if (explicit) {
                throw new SchemaException(where + "." + sub.getName() + ": cfg_tip_ref 只能标在整型列上");
            }
        }
    }

    /** 表达式列：字符串标量；结果类型只支持 double（基线 ExcelExpression 的唯一用法）；参数名是标识符、不重复、不与函数名撞。 */
    private static Expression expression(String where, Field field, String resultType, List<String> params) {
        if (field.kind() != Kind.STRING || field.repeated()) {
            throw new SchemaException(where + ": cfg_expr_type 只能标在字符串标量列上");
        }
        if (!resultType.equals("double")) {
            throw new SchemaException(where + ": cfg_expr_type 只支持 double，实际 " + resultType);
        }
        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (String raw : params) {
            String name = raw.trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || EXPRESSION_FUNCTIONS.contains(name)) {
                throw new SchemaException(where + ": cfg_expr_param 不是合法的参数名: " + raw);
            }
            if (!seen.add(name)) {
                throw new SchemaException(where + ": cfg_expr_param 重复: " + name);
            }
            names.add(name);
        }
        return new Expression(field, resultType, List.copyOf(names));
    }

    /** 合法 tip 码：{@code tip/} 目录下各 proto 的顶层枚举的全部取值（导表器 enum_gen 产出的就是当前活动的码），外加 0。 */
    private static List<Long> tipCodes(FileDescriptorSet set) {
        java.util.TreeSet<Long> codes = new java.util.TreeSet<>();
        for (FileDescriptorProto file : set.getFileList()) {
            if (!file.getName().startsWith(TIP_PROTO_DIR)) {
                continue;
            }
            for (var e : file.getEnumTypeList()) {
                for (var v : e.getValueList()) {
                    codes.add((long) v.getNumber());
                }
            }
        }
        if (!codes.isEmpty()) {
            codes.add(0L);
        }
        return List.copyOf(codes);
    }

    private static void validateTipRefs(TableSchema schema) {
        if (!schema.tipCodes().isEmpty()) {
            return;
        }
        for (Table table : schema.tables()) {
            if (!table.tipRefs().isEmpty()) {
                throw new SchemaException(table.tipRefs().get(0).label(table.sheet())
                        + ": 有 tip 引用列，但描述符集里没有 " + TIP_PROTO_DIR + "*.proto 的 tip 枚举，无法校验");
            }
        }
    }

    private void validateForeignKeys(TableSchema schema) {
        for (Table table : schema.tables()) {
            for (ForeignKey fk : table.foreignKeys()) {
                String label = "[" + table.sheet() + "." + fk.field().name() + "] " + fk.option() + " -> "
                        + fk.targetSheet() + "." + fk.targetColumn();
                Table target = schema.table(fk.targetSheet())
                        .orElseThrow(() -> new SchemaException(label + ": 目标表不存在"));
                Field targetField = target.field(fk.targetColumn())
                        .orElseThrow(() -> new SchemaException(label + ": 目标列不存在（或不是服务端列）"));
                if (!fk.field().kind().keyable() || fk.field().kind() == Kind.BOOL) {
                    throw new SchemaException(label + ": 外键列类型不支持");
                }
                if (targetField.repeated()) {
                    throw new SchemaException(label + ": 外键不能指向 repeated 列");
                }
                if (fk.field().kind() != targetField.kind()) {
                    throw new SchemaException(label + ": 两端类型不一致");
                }
                if (targetField.equals(target.primaryKey()) && target.multiPrimaryKey()) {
                    throw new SchemaException(label + ": 目标表主键可重复，外键无法唯一解析");
                }
            }
        }
    }

    private boolean isServerOwned(UnknownFieldSet opts) {
        // 缺省 common；导表器先 lower() 再比（schema_proto.py），这里同口径。
        String owner = stringOption(opts, fieldExt, "cfg_owner").map(o -> o.toLowerCase(Locale.ROOT)).orElse("common");
        return owner.equals("server") || owner.equals("common");
    }

    private static void requireScalarKey(String where, Field field) {
        if (field.repeated() || !field.kind().keyable()) {
            throw new SchemaException(where + ": 键列必须是整型 / 字符串 / 布尔标量");
        }
    }

    private static Field toField(FieldDescriptorProto fd, String messageName, Set<String> mapEntries) {
        boolean repeated = fd.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED;
        boolean isEnum = fd.getType() == FieldDescriptorProto.Type.TYPE_ENUM;
        Kind kind = switch (fd.getType()) {
            case TYPE_INT32, TYPE_UINT32, TYPE_SINT32, TYPE_FIXED32, TYPE_SFIXED32, TYPE_ENUM -> Kind.INT;
            case TYPE_INT64, TYPE_UINT64, TYPE_SINT64, TYPE_FIXED64, TYPE_SFIXED64 -> Kind.LONG;
            case TYPE_STRING -> Kind.STRING;
            case TYPE_BOOL -> Kind.BOOL;
            default -> Kind.OTHER;
        };
        if (fd.getType() == FieldDescriptorProto.Type.TYPE_MESSAGE) {
            String typeName = fd.getTypeName();
            String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
            boolean isMap = repeated && mapEntries.contains(simple) && typeName.endsWith(messageName + "." + simple);
            return new Field(fd.getName(), fd.getNumber(), repeated && !isMap, Kind.OTHER, false);
        }
        return new Field(fd.getName(), fd.getNumber(), repeated, kind, isEnum);
    }

    private boolean boolOption(UnknownFieldSet opts, String name) {
        Integer number = fieldExt.get(name);
        if (number == null || !opts.hasField(number)) {
            return false;
        }
        List<Long> varints = opts.getField(number).getVarintList();
        return !varints.isEmpty() && varints.get(varints.size() - 1) != 0;
    }

    /** repeated string option 的全部取值（按出现顺序）。 */
    private static List<String> stringOptions(UnknownFieldSet opts, Map<String, Integer> ext, String name) {
        Integer number = ext.get(name);
        if (number == null || !opts.hasField(number)) {
            return List.of();
        }
        return opts.getField(number).getLengthDelimitedList().stream().map(b -> b.toStringUtf8()).toList();
    }

    private static Optional<String> stringOption(UnknownFieldSet opts, Map<String, Integer> ext, String name) {
        Integer number = ext.get(name);
        if (number == null || !opts.hasField(number)) {
            return Optional.empty();
        }
        var values = opts.getField(number).getLengthDelimitedList();
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(values.size() - 1).toStringUtf8());
    }

    /** schema 不合法。 */
    static final class SchemaException extends RuntimeException {
        SchemaException(String message) {
            super(message);
        }
    }
}
