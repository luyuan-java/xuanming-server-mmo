package com.game.pbmysql;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 代码级表选项，对应 Go 版的 {@code TableOption}（{@code WithTableName} / {@code WithPrimaryKey} / ...）。
 *
 * <p>注册时先应用 proto 描述符里声明的选项，再按传入顺序应用这些代码选项（后者覆盖前者），语义与 Go 版逐项相同：
 * <ul>
 *   <li>{@link #withPrimaryKey} / {@link #withIndexes} / {@link #withNullableFields} / {@link #withMaxLengths} 整体替换；</li>
 *   <li>{@link #withMaxLength} 按字段合并（只覆盖同名字段，proto 声明的其它字段保留）；</li>
 *   <li>字段名按原样保存，空分量、首尾空白、不存在的字段都留给注册时的校验 fail-closed，不在这里静默修正。</li>
 * </ul>
 */
public final class TableOption {

    private final Consumer<Spec> apply;

    private TableOption(Consumer<Spec> apply) {
        this.apply = apply;
    }

    void applyTo(Spec spec) {
        apply.accept(spec);
    }

    /** 自定义 SQL 表名（默认 = proto full name）。注册与查找仍按 proto full name，本选项只影响 SQL 里的表名。 */
    public static TableOption withTableName(String name) {
        return new TableOption(s -> s.tableName = name);
    }

    /** 设置主键（多个 = 联合主键）；不传任何字段 = 没有主键。 */
    public static TableOption withPrimaryKey(String... keys) {
        List<String> copy = List.copyOf(Arrays.asList(keys));
        return new TableOption(s -> s.primaryKey = copy);
    }

    /** 设置普通索引：每个参数一个索引，索引内逗号分隔 = 联合索引。 */
    public static TableOption withIndexes(String... indexes) {
        List<String> copy = List.copyOf(Arrays.asList(indexes));
        return new TableOption(s -> s.indexes = copy);
    }

    /** 设置唯一键（逗号分隔 = 联合唯一键；空串 = 没有唯一键）。 */
    public static TableOption withUniqueKey(String uniqueKey) {
        return new TableOption(s -> s.uniqueKey = uniqueKey);
    }

    /** 设置自增字段（空串 = 没有自增列，可用来清掉 proto 里的声明）。 */
    public static TableOption withAutoIncrementKey(String key) {
        return new TableOption(s -> s.autoIncrementKey = key);
    }

    /** 设置允许为 NULL 的字段（整体替换）。 */
    public static TableOption withNullableFields(String... fields) {
        List<String> copy = List.copyOf(Arrays.asList(fields));
        return new TableOption(s -> s.nullableFields = copy);
    }

    /** 同 {@link #withNullableFields(String...)}。 */
    public static TableOption withNullableFields(List<String> fields) {
        List<String> copy = List.copyOf(fields);
        return new TableOption(s -> s.nullableFields = copy);
    }

    /**
     * 主键 / 唯一键里 string / bytes 字段的列宽 n（string 按字符、bytes 按字节），按字段合并。
     * n 按 uint32 解释（负数视为大于 2^31 的无符号值，必然越界被拒）。
     */
    public static TableOption withMaxLength(String field, int n) {
        long unsigned = Integer.toUnsignedLong(n);
        return new TableOption(s -> {
            if (s.maxLengths == null) {
                s.maxLengths = new LinkedHashMap<>();
            }
            s.maxLengths.put(field, unsigned);
        });
    }

    /** 整体替换 max_length 集合，null 或空表 = 清空（proto 的声明落到键外字段上时只能用它清除）。 */
    public static TableOption withMaxLengths(Map<String, Integer> lengths) {
        if (lengths == null || lengths.isEmpty()) {
            return new TableOption(s -> s.maxLengths = null);
        }
        Map<String, Long> copy = new LinkedHashMap<>();
        lengths.forEach((k, v) -> copy.put(k, Integer.toUnsignedLong(v)));
        return new TableOption(s -> s.maxLengths = new LinkedHashMap<>(copy));
    }

    /** 主键追加 {@code /*T![clustered_index] NONCLUSTERED *}{@code /}（TiDB 方言，MySQL 忽略）。 */
    public static TableOption withTiDBNonclusteredPK() {
        return new TableOption(s -> s.tidbNonclusteredPk = true);
    }

    /** 追加 {@code SHARD_ROW_ID_BITS=bits}（TiDB 方言）；有主键却没声明 NONCLUSTERED 时被忽略并告警。按 uint32 解释。 */
    public static TableOption withTiDBShardRowIDBits(int bits) {
        long unsigned = Integer.toUnsignedLong(bits);
        return new TableOption(s -> s.tidbShardRowIdBits = unsigned);
    }

    /** 追加 {@code PRE_SPLIT_REGIONS=n}（TiDB 方言）；依赖 SHARD_ROW_ID_BITS，超过它时收敛到它。按 uint32 解释。 */
    public static TableOption withTiDBPreSplitRegions(int n) {
        long unsigned = Integer.toUnsignedLong(n);
        return new TableOption(s -> s.tidbPreSplitRegions = unsigned);
    }

    /** 表尾追加 {@code AUTO_ID_CACHE=1}（TiDB 方言，自增 ID 集中分配）。 */
    public static TableOption withTiDBAutoIDCacheOne() {
        return new TableOption(s -> s.tidbAutoIdCacheOne = true);
    }

    /** 选项应用的目标：注册过程中可变，校验后冻结成 {@link TableSchema}。 */
    static final class Spec {
        String tableName;
        List<String> primaryKey = List.of();
        List<String> indexes = List.of();
        String uniqueKey = "";
        String autoIncrementKey = "";
        List<String> nullableFields = List.of();
        /** 字段名 → uint32 列宽；null = 未声明。 */
        Map<String, Long> maxLengths;
        boolean tidbNonclusteredPk;
        long tidbShardRowIdBits;
        long tidbPreSplitRegions;
        boolean tidbAutoIdCacheOne;

        Spec(String defaultTableName) {
            this.tableName = defaultTableName;
        }
    }
}
