package com.game.table.codegen;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * 从权威 schema（mmorpg {@code data/schema/*_table.proto} 上的 {@code cfg_*} option）读出的配置表模型。
 * 只描述生成代码要用的部分：主键、二级键、索引、外键。
 *
 * @param javaPackage 生成代码与行消息类所在的 Java 包（全部表必须同包）
 * @param tables      按表名排序
 */
record TableSchema(String javaPackage, List<Table> tables) {

    Optional<Table> table(String sheet) {
        return tables.stream().filter(t -> t.sheet().equals(sheet)).findFirst();
    }

    /** 对每张表的每个字段做同一个变换（用于把访问器名换成 protoc 实际生成的名字）。 */
    TableSchema mapFields(java.util.function.BiFunction<Table, Field, Field> fn) {
        return new TableSchema(javaPackage, tables.stream().map(t -> t.mapFields(f -> fn.apply(t, f))).toList());
    }

    /** 字段值在 Java 里的类型。枚举字段一律按数值（{@code getXxxValue()}）处理。 */
    enum Kind {
        INT("int", "Integer"),
        LONG("long", "Long"),
        STRING("String", "String"),
        BOOL("boolean", "Boolean"),
        /** 浮点 / bytes / 子消息 / map：不能当键、索引或外键。 */
        OTHER("", "");

        final String primitive;
        final String boxed;

        Kind(String primitive, String boxed) {
            this.primitive = primitive;
            this.boxed = boxed;
        }

        boolean keyable() {
            return this != OTHER;
        }
    }

    /**
     * @param name         proto 字段名（snake_case，与 Excel 列名一致）
     * @param repeated     是否 repeated（map 字段的 kind 为 OTHER）
     * @param isEnum       枚举字段：取值用 {@code getXxxValue()} / {@code getXxxValueList()}
     * @param accessorBase protoc 访问器里 {@code get} 之后、{@code Value} / {@code List} 之前的部分。缺省按 protoc 命名规则算
     *                     （{@code skill_type} → {@code SkillType}）；注解处理器再按 protoc 实际生成的方法校正
     *                     （同消息里 {@code x} 与 {@code x_count} 冲突时 protoc 会追加字段号：{@code Monster5}）
     */
    record Field(String name, int number, boolean repeated, Kind kind, boolean isEnum, String accessorBase) {

        Field(String name, int number, boolean repeated, Kind kind, boolean isEnum) {
            this(name, number, repeated, kind, isEnum, JavaNames.capitalizedFieldName(name));
        }

        /** 访问器名的后缀：枚举加 {@code Value}，repeated 加 {@code List}。 */
        String accessorSuffix() {
            return (isEnum ? "Value" : "") + (repeated ? "List" : "");
        }

        /** 取值表达式（不含接收者）：标量 {@code getSkillType()}，repeated {@code getSkillTypeList()}。 */
        String getter() {
            return "get" + accessorBase + accessorSuffix() + "()";
        }

        Field withAccessorBase(String base) {
            return new Field(name, number, repeated, kind, isEnum, base);
        }

        /** 生成方法名里的字段部分：{@code m_uint32_key} → {@code MUint32Key}。 */
        String pascal() {
            return JavaNames.underscoresToCamelCase(name, true);
        }
    }

    /** 唯一键（{@code multi=false}）或多值键（{@code multi=true}）；只允许标量字段。 */
    record Key(Field field, boolean multi) {
    }

    /** 外键：{@code field} 的每个非空取值都必须能在 {@code targetSheet.targetColumn} 里找到。 */
    record ForeignKey(Field field, String targetSheet, String targetColumn, String option) {
    }

    /**
     * @param sheet           表名（cfg_sheet）
     * @param sourceFile      源 Excel 文件名（cfg_source_file），只用于注释
     * @param rowClass        行消息的 Java 简单类名（protoc 生成）
     * @param primaryKey      主键字段
     * @param multiPrimaryKey 主键可重复（主键上标了 cfg_multi）：不生成按 id 取单行的方法
     * @param keys            除主键以外的键
     * @param indexes         索引（标量或 repeated 标量；repeated 时按每个元素建索引）
     * @param foreignKeys     外键
     * @param fields          全部服务端字段
     */
    record Table(String sheet, String sourceFile, String rowClass, Field primaryKey, boolean multiPrimaryKey,
                 List<Key> keys, List<Field> indexes, List<ForeignKey> foreignKeys, List<Field> fields) {

        String rowsClass() {
            return sheet + "Rows";
        }

        String accessor() {
            return JavaNames.accessorName(sheet);
        }

        /** 导表器产物文件名：表名小写 + {@code .pb}（mmorpg {@code generated/tables/<sheet 小写>.pb}）。 */
        String dataFile() {
            return sheet.toLowerCase(Locale.ROOT) + ".pb";
        }

        Optional<Field> field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).findFirst();
        }

        Optional<Key> uniqueKey(String column) {
            return keys.stream().filter(k -> !k.multi() && k.field().name().equals(column)).findFirst();
        }

        /** 每个字段（含键、索引、外键里引用的同一字段）都换成 {@code fn} 的结果，保持彼此一致。 */
        Table mapFields(UnaryOperator<Field> fn) {
            List<Field> mapped = fields.stream().map(fn).toList();
            UnaryOperator<Field> same = f -> mapped.stream().filter(m -> m.name().equals(f.name())).findFirst().orElseThrow();
            return new Table(sheet, sourceFile, rowClass, same.apply(primaryKey), multiPrimaryKey,
                    keys.stream().map(k -> new Key(same.apply(k.field()), k.multi())).toList(),
                    indexes.stream().map(same).toList(),
                    foreignKeys.stream().map(fk -> new ForeignKey(same.apply(fk.field()), fk.targetSheet(), fk.targetColumn(),
                            fk.option())).toList(),
                    mapped);
        }
    }
}
