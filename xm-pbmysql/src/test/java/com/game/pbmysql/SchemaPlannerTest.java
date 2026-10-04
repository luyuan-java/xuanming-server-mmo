package com.game.pbmysql;

import static com.game.pbmysql.TableOption.withPrimaryKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.pbmysql.SchemaPlanner.ColumnMeta;
import com.game.pbmysql.SchemaPlanner.IndexColumn;
import com.game.pbmysql.SchemaPlanner.IndexMeta;
import com.game.pbmysql.SchemaPlanner.Plan;
import com.game.pbmysql.testpb.Account;
import com.game.pbmysql.testpb.GolangTest;
import com.game.pbmysql.testpb.KitchenSink;
import com.game.pbmysql.testpb.SyncItemV2;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 只扩不缩的结构对齐规划（纯计算）。线上快照按 MySQL 8 information_schema 的回读形态构造；
 * 改名 / 回填注释 / 收窄等建议语句的文本与 Go 版 parity_emit_test.go 的 alter 语料一致（这里多了 ALTER TABLE 前缀）。
 */
class SchemaPlannerTest {

    private static final TableSchema GOLANG = TableSchema.of(GolangTest.getDefaultInstance(), withPrimaryKey("id"));

    /** information_schema 对一张由本库建出的表回读出的列快照。 */
    private static Map<String, ColumnMeta> aligned(TableSchema t) {
        Map<String, ColumnMeta> out = new LinkedHashMap<>();
        for (FieldDescriptor fd : t.fields()) {
            out.put(fd.getName(), meta(t, fd, fd.getNumber()));
        }
        return out;
    }

    private static ColumnMeta meta(TableSchema t, FieldDescriptor fd, int fieldNumber) {
        String target = t.columnType(fd);
        List<String> typeTokens = new ArrayList<>();
        for (String token : target.toLowerCase(Locale.ROOT).split(" ")) {
            if (List.of("not", "null", "default", "auto_increment", "character").contains(token)) {
                break;
            }
            typeTokens.add(token);
        }
        String collation = "";
        if (target.contains("COLLATE utf8mb4_0900_bin")) {
            collation = "utf8mb4_0900_bin";
        } else if (target.equals("MEDIUMTEXT")) {
            collation = "utf8mb4_unicode_ci";
        }
        return new ColumnMeta(String.join(" ", typeTokens), fieldNumber, !target.contains("NOT NULL"),
                SchemaPlanner.expectedColumnDefault(target), target.contains("AUTO_INCREMENT") ? "auto_increment" : "",
                collation);
    }

    private static Map<String, IndexMeta> declaredIndexes(TableSchema t) {
        Map<String, IndexMeta> out = new LinkedHashMap<>();
        for (int i = 0; i < t.indexes().size(); i++) {
            out.put(t.indexName(i), SchemaPlanner.expectedIndexMeta(t, t.indexes().get(i), false));
        }
        if (!t.uniqueKey().isEmpty()) {
            out.put(t.uniqueKeyName(), SchemaPlanner.expectedIndexMeta(t, t.uniqueKey(), true));
        }
        return out;
    }

    private static IndexMeta pk(TableSchema t) {
        return SchemaPlanner.expectedIndexMeta(t, String.join(",", t.primaryKey()), true);
    }

    private static Plan plan(TableSchema t, Map<String, ColumnMeta> cols) {
        return SchemaPlanner.plan(t, cols, declaredIndexes(t), pk(t));
    }

    @Test
    void 结构已对齐时什么都不做_重复同步幂等() {
        for (TableSchema t : List.of(GOLANG, TableSchema.of(KitchenSink.getDefaultInstance()),
                TableSchema.of(Account.getDefaultInstance()))) {
            assertThat(plan(t, aligned(t)).isEmpty()).as(t.tableName()).isTrue();
        }
    }

    @Test
    void 缺列只ADD_COLUMN_线上多出来的列不动() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.remove("player_id");
        cols.put("legacy_extra", new ColumnMeta("int", 99, false, "0", "", ""));
        Plan p = plan(GOLANG, cols);
        assertThat(p.columnClauses()).containsExactly("ADD COLUMN `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6'");
        assertThat(p.primaryKeyClauses()).isEmpty();
    }

    @Test
    void 线上列比proto宽_保持不动不收窄() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.put("ip", new ColumnMeta("longtext", 2, true, null, "", "utf8mb4_unicode_ci"));
        cols.put("port", new ColumnMeta("bigint unsigned", 3, false, "0", "", ""));
        cols.put("player", new ColumnMeta("longblob", 5, true, null, "", ""));
        assertThat(plan(GOLANG, cols).isEmpty()).isTrue();
    }

    @Test
    void 按字段号识别出的改名_拒绝并给出CHANGE_COLUMN建议() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.put("old_ip", cols.remove("ip"));
        assertThatThrownBy(() -> plan(GOLANG, cols))
                .isInstanceOfSatisfying(SchemaDriftException.class, e -> {
                    assertThat(e.tableName()).isEqualTo("golang_test");
                    assertThat(e.differences()).anySatisfy(d -> assertThat(d).startsWith("线上列 old_ip 按字段号 pb:2 对应 proto 字段 ip（字段改名）"));
                    assertThat(e.suggestedStatements())
                            .containsExactly("ALTER TABLE `golang_test` CHANGE COLUMN `old_ip` `ip` MEDIUMTEXT COMMENT 'pb:2'");
                });
    }

    @Test
    void 缺字段号注释的老表_拒绝并给出回填注释的MODIFY建议_且不收窄() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.replaceAll((name, m) -> new ColumnMeta(m.columnType(), 0, m.nullable(), m.defaultValue(), m.extra(), m.collation()));
        cols.put("port", new ColumnMeta("bigint unsigned", 0, false, "0", "", ""));
        SchemaDriftException e = catchDrift(() -> plan(GOLANG, cols));
        assertThat(e.differences()).contains("列 ip 缺少字段号注释 COMMENT 'pb:2'");
        assertThat(e.suggestedStatements()).contains(
                "ALTER TABLE `golang_test` MODIFY COLUMN `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:1'",
                "ALTER TABLE `golang_test` MODIFY COLUMN `ip` MEDIUMTEXT COMMENT 'pb:2'",
                // 回填注释不能顺带把线上的 bigint 收窄成 int
                "ALTER TABLE `golang_test` MODIFY COLUMN `port` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3'");
    }

    @Test
    void 线上列比proto窄_拒绝并给出拓宽建议() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.put("player_id", new ColumnMeta("int unsigned", 6, false, "0", "", ""));
        SchemaDriftException e = catchDrift(() -> plan(GOLANG, cols));
        assertThat(e.differences()).containsExactly(
                "列 player_id 线上是 int unsigned，比 proto 要求的 bigint unsigned NOT NULL DEFAULT 0 窄，需要拓宽");
        assertThat(e.suggestedStatements()).containsExactly(
                "ALTER TABLE `golang_test` MODIFY COLUMN `player_id` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'pb:6'");
        assertThat(e.getMessage()).contains("本表未执行任何 DDL");
    }

    @Test
    void 跨族与有无符号不同_拒绝且不给自动语句() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.put("port", new ColumnMeta("mediumtext", 3, true, null, "", "utf8mb4_unicode_ci"));
        cols.put("group_id", new ColumnMeta("int", 4, false, "0", "", ""));
        SchemaDriftException e = catchDrift(() -> plan(GOLANG, cols));
        assertThat(e.differences()).anySatisfy(d -> assertThat(d).startsWith("列 port 线上是 mediumtext，proto 要 int unsigned NOT NULL DEFAULT 0。跨类型族"));
        assertThat(e.differences()).anySatisfy(d -> assertThat(d).startsWith("列 group_id 线上是 int，proto 要 int unsigned NOT NULL DEFAULT 0。signed 与 unsigned"));
        assertThat(e.suggestedStatements()).noneMatch(s -> s.contains("`port`") || s.contains("`group_id`"));
    }

    @Test
    void 字段号被复用到跨族的新字段_拒绝() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.remove("player_id");
        cols.put("old_blob", new ColumnMeta("mediumblob", 6, true, null, "", ""));
        SchemaDriftException e = catchDrift(() -> plan(GOLANG, cols));
        assertThat(e.differences()).anySatisfy(d -> assertThat(d).contains("类型跨族，无法当作改名处理。字段号是 protobuf 的身份，永不复用"));
    }

    @Test
    void 可空_默认值_自增属性漂移被拒绝() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.put("port", new ColumnMeta("int unsigned", 3, true, null, "", ""));
        cols.put("id", new ColumnMeta("int unsigned", 1, false, "0", "", ""));
        SchemaDriftException e = catchDrift(() -> plan(GOLANG, cols));
        assertThat(e.differences()).containsExactly(
                "column id default mismatch (online=\"0\", proto=NULL)",
                "column id auto_increment mismatch (online=false, proto=true)",
                "column port nullable mismatch (online=true, proto=false)",
                "column port default mismatch (online=NULL, proto=\"0\")");
        assertThat(e.suggestedStatements()).containsExactly(
                "ALTER TABLE `golang_test` MODIFY COLUMN `id` int unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:1'",
                "ALTER TABLE `golang_test` MODIFY COLUMN `port` int unsigned NOT NULL DEFAULT 0 COMMENT 'pb:3'");
    }

    @Test
    void 缺的索引与唯一键按名字补_同名索引定义不同则拒绝() {
        TableSchema v2 = TableSchema.of(SyncItemV2.getDefaultInstance());
        Map<String, IndexMeta> online = new LinkedHashMap<>();
        online.put("IDX_PBM_SYNC_ITEM_0", SchemaPlanner.expectedIndexMeta(v2, "owner_id", false)); // 大小写不同也算同名
        Plan p = SchemaPlanner.plan(v2, aligned(v2), online, pk(v2));
        assertThat(p.columnClauses()).containsExactly(
                "ADD INDEX `idx_pbm_sync_item_1` (`level`)",
                "ADD UNIQUE KEY `uk_pbm_sync_item` (`code`)");

        online.put("idx_pbm_sync_item_1", SchemaPlanner.expectedIndexMeta(v2, "name", false));
        SchemaDriftException e = catchDrift(() -> SchemaPlanner.plan(v2, aligned(v2), online, pk(v2)));
        assertThat(e.differences()).containsExactly(
                "index idx_pbm_sync_item_1 definition mismatch (online=INDEX(1:name(191)), proto=INDEX(1:level))");
        assertThat(e.suggestedStatements()).containsExactly(
                "ALTER TABLE `pbm_sync_item` DROP INDEX `idx_pbm_sync_item_1`, ADD INDEX `idx_pbm_sync_item_1` (`level`)");
    }

    @Test
    void 主键定义不同被拒绝_线上没有主键时补主键且新增的自增主键列挪进第二条ALTER() {
        IndexMeta wrongPk = new IndexMeta(true, List.of(new IndexColumn("player_id", 1, null)));
        SchemaDriftException e = catchDrift(() -> SchemaPlanner.plan(GOLANG, aligned(GOLANG), Map.of(), wrongPk));
        assertThat(e.differences()).containsExactly(
                "primary key definition mismatch (online=UNIQUE(1:player_id), proto=UNIQUE(1:id))");
        assertThat(e.suggestedStatements()).containsExactly(
                "ALTER TABLE `golang_test` DROP PRIMARY KEY, ADD PRIMARY KEY (`id`)");

        TableSchema account = TableSchema.of(Account.getDefaultInstance());
        Map<String, ColumnMeta> cols = aligned(account);
        cols.remove("id");
        Plan p = SchemaPlanner.plan(account, cols, Map.of(), null);
        assertThat(p.columnClauses()).containsExactly("ADD INDEX `idx_account_0` (`name`(191))", "ADD UNIQUE KEY `uk_account` (`email`)");
        assertThat(p.primaryKeyClauses()).containsExactly(
                "ADD COLUMN `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:1'",
                "ADD PRIMARY KEY (`id`)");
    }

    @Test
    void 旧形态的键列_TEXT前缀或ci排序规则_拒绝() {
        TableSchema account = TableSchema.of(Account.getDefaultInstance());
        Map<String, ColumnMeta> cols = aligned(account);
        cols.put("email", new ColumnMeta("mediumtext", 3, true, null, "", "utf8mb4_unicode_ci"));
        SchemaDriftException e = catchDrift(() -> SchemaPlanner.plan(account, cols, declaredIndexes(account), pk(account)));
        assertThat(e.differences()).anySatisfy(d -> assertThat(d)
                .startsWith("键列 email（唯一键）仍是旧形态：线上 mediumtext COLLATE utf8mb4_unicode_ci NULL，期望 VARCHAR(191)")
                .contains("原因：TEXT 列只能建前缀索引，唯一性只覆盖前 191 个字符"));

        cols.put("email", new ColumnMeta("varchar(191)", 3, false, "", "", "utf8mb4_unicode_ci"));
        assertThat(catchDrift(() -> SchemaPlanner.plan(account, cols, declaredIndexes(account), pk(account))).differences())
                .anySatisfy(d -> assertThat(d).contains("排序规则 utf8mb4_unicode_ci 不区分大小写"));
    }

    @Test
    void 同一个pb号出现在两列上_身份歧义直接拒绝() {
        Map<String, ColumnMeta> cols = aligned(GOLANG);
        cols.put("ip_backup", new ColumnMeta("mediumtext", 2, true, null, "", "utf8mb4_unicode_ci"));
        SchemaDriftException e = catchDrift(() -> plan(GOLANG, cols));
        assertThat(e.differences()).containsExactly(
                "线上列 \"ip\" 与 \"ip_backup\" 都声明 COMMENT 'pb:2'；字段号身份已经歧义，无法安全判断哪列是真实数据。请先人工修正重复注释再同步");
    }

    @Test
    void 列注释里的字段号解析() {
        assertThat(SchemaPlanner.fieldNumberFromComment("pb:3")).isEqualTo(3);
        assertThat(SchemaPlanner.fieldNumberFromComment("pb:536870911")).isEqualTo(536_870_911);
        assertThat(SchemaPlanner.fieldNumberFromComment("pb:0")).isZero();
        assertThat(SchemaPlanner.fieldNumberFromComment("pb:x")).isZero();
        assertThat(SchemaPlanner.fieldNumberFromComment("名字")).isZero();
        assertThat(SchemaPlanner.fieldNumberFromComment(null)).isZero();
        // 只认 ASCII 数字（全角数字在 Go 的 strconv.Atoi 下不是数字）
        assertThat(SchemaPlanner.fieldNumberFromComment("pb:３")).isZero();
        assertThat(SchemaPlanner.fieldNumberFromComment("pb:+3")).isEqualTo(3);
    }

    private static SchemaDriftException catchDrift(Runnable r) {
        try {
            r.run();
        } catch (SchemaDriftException e) {
            return e;
        }
        throw new AssertionError("expected SchemaDriftException");
    }
}
