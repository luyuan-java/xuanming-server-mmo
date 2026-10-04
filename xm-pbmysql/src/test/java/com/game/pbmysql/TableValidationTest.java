package com.game.pbmysql;

import static com.game.pbmysql.DynamicTables.field;
import static com.game.pbmysql.InvalidTableDefinitionException.Reason.INVALID_TABLE_OPTION;
import static com.game.pbmysql.InvalidTableDefinitionException.Reason.UNSUPPORTED_FIELD_KIND;
import static com.game.pbmysql.TableOption.withAutoIncrementKey;
import static com.game.pbmysql.TableOption.withIndexes;
import static com.game.pbmysql.TableOption.withMaxLength;
import static com.game.pbmysql.TableOption.withNullableFields;
import static com.game.pbmysql.TableOption.withPrimaryKey;
import static com.game.pbmysql.TableOption.withTableName;
import static com.game.pbmysql.TableOption.withUniqueKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.pbmysql.InvalidTableDefinitionException.Reason;
import com.game.pbmysql.option.Proto2MysqlOption;
import com.game.pbmysql.testpb.GolangTest;
import com.game.pbmysql.testpb.KitchenSink;
import com.game.pbmysql.testpb.OneofRow;
import com.game.pbmysql.testpb.RepeatedTimestampRow;
import com.game.pbmysql.testpb.ScalarRow;
import com.game.pbmysql.testpb.SintRow;
import com.game.pbmysql.testpb.StringKeyRow;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

/** 在产出任何 DDL 之前拒绝非法表定义（Go 版 validateFieldKinds / validateTableOptions / validateMaxLengths / validateIndexKeyBytes）。 */
class TableValidationTest {

    private static AbstractThrowableAssert<?, ? extends Throwable> rejects(Reason reason, Message m, TableOption... opts) {
        return assertThatThrownBy(() -> TableSchema.of(m, opts))
                .isInstanceOf(InvalidTableDefinitionException.class)
                .satisfies(e -> assertThat(((InvalidTableDefinitionException) e).reason()).isEqualTo(reason));
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> rejects(Reason reason, Descriptor d, TableOption... opts) {
        return assertThatThrownBy(() -> TableSchema.of(d, opts))
                .isInstanceOf(InvalidTableDefinitionException.class)
                .satisfies(e -> assertThat(((InvalidTableDefinitionException) e).reason()).isEqualTo(reason));
    }

    // ================================================================ 字段类型

    @Test
    void 真实oneof被拒绝_proto3_optional放行() {
        rejects(UNSUPPORTED_FIELD_KIND, OneofRow.getDefaultInstance())
                .hasMessageStartingWith("表 oneof_row 的字段 a 属于 oneof \"choice\"。");
        // KitchenSink 有 proto3 optional（synthetic oneof），能正常建表
        assertThat(TableSchema.of(KitchenSink.getDefaultInstance()).tableName()).isEqualTo("kitchen_sink");
    }

    @Test
    void sint32等没有列映射的类型被拒绝() {
        rejects(UNSUPPORTED_FIELD_KIND, SintRow.getDefaultInstance())
                .hasMessage("表 sint_row 的字段 z（sint32）。sint32/sint64/fixed32/fixed64/sfixed32/sfixed64 都不支持，"
                        + "改用 int32/int64/uint32/uint64 即可（取值范围一样，只是线上编码不同）");
        Descriptor fixed = DynamicTables.message("Fixed", field("id", 1, Type.TYPE_UINT64), field("f", 2, Type.TYPE_FIXED64));
        rejects(UNSUPPORTED_FIELD_KIND, fixed, withTableName("fixed")).hasMessageContaining("的字段 f（fixed64）");
    }

    @Test
    void repeated_Timestamp被拒绝_Java版独有() {
        rejects(UNSUPPORTED_FIELD_KIND, RepeatedTimestampRow.getDefaultInstance())
                .hasMessageContaining("字段 times 是 repeated google.protobuf.Timestamp");
    }

    @Test
    void 表名为空白_表名或字段名超过64字符_没有字段() {
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withTableName("   "))
                .hasMessage("table_name 不能为空或只含空白");
        rejects(UNSUPPORTED_FIELD_KIND, ScalarRow.getDefaultInstance(), withTableName("t".repeat(65)))
                .hasMessageStartingWith("表名 \"" + "t".repeat(65) + "\" 有 65 个字符，超过 MySQL 的 64 上限。");
        // 64 个字符刚好合法（按码点数，中文也是一个字符）
        assertThat(TableSchema.of(ScalarRow.getDefaultInstance(), withTableName("表".repeat(64))).tableName()).hasSize(64);
        Descriptor longField = DynamicTables.message("LongField", field("f".repeat(65), 1, Type.TYPE_UINT64));
        rejects(UNSUPPORTED_FIELD_KIND, longField, withTableName("long_field"))
                .hasMessage("表 long_field 的字段名 \"" + "f".repeat(65) + "\" 有 65 个字符，超过 MySQL 标识符的 64 上限");
        rejects(UNSUPPORTED_FIELD_KIND, DynamicTables.message("Empty"), withTableName("empty"))
                .hasMessage("表 empty 的 protobuf message 没有字段，无法生成合法的 MySQL 表");
    }

    // ================================================================ 主键

    @Test
    void 浮点主键被拒绝() {
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withPrimaryKey("f32"))
                .hasMessage("表 scalar_row 的主键字段 \"f32\" 是 float；浮点值不能作为稳定身份，"
                        + "其十进制、二进制与数据库比较语义可能不一致；请改用整数或枚举主键");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withPrimaryKey("id", "f64"))
                .hasMessageContaining("主键字段 \"f64\" 是 double");
    }

    @Test
    void BLOB主键只能前缀索引被拒绝() {
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withPrimaryKey("tags"))
                .hasMessageStartingWith("表 kitchen_sink 的主键字段 \"tags\" 映射为 MEDIUMBLOB，只能建立前缀索引，");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withPrimaryKey("owner"))
                .hasMessageContaining("主键字段 \"owner\" 映射为 MEDIUMBLOB");
    }

    @Test
    void 可空主键与Timestamp主键被拒绝() {
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withNullableFields("id"))
                .hasMessage("表 scalar_row 的主键字段 \"id\" 目标类型 bigint unsigned DEFAULT 0 可为 NULL；"
                        + "MySQL 会静默强制成 NOT NULL 并造成永久 schema drift");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withPrimaryKey("created_at"))
                .hasMessageContaining("主键字段 \"created_at\" 目标类型 DATETIME(6) 可为 NULL");
    }

    @Test
    void 键列不能可空() {
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withNullableFields("token"))
                .hasMessageStartingWith("表 kitchen_sink 的 bytes 字段 \"token\" 在主键/唯一键里，不能声明 nullable：");
        rejects(INVALID_TABLE_OPTION, StringKeyRow.getDefaultInstance(), withNullableFields("sub"))
                .hasMessageStartingWith("表 string_key_row 的 string 字段 \"sub\" 在主键/唯一键里，不能声明 nullable：");
    }

    // ================================================================ 字段列表

    @Test
    void 空分量_代码选项与proto选项都fail_closed() {
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withPrimaryKey("id", ""))
                .hasMessage("表 scalar_row 的 primary_key 含空字段分量");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withIndexes("i32,,u32"))
                .hasMessage("表 scalar_row 的 index[0] 含空字段分量");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withUniqueKey("i32,"))
                .hasMessage("表 scalar_row 的 unique_key 含空字段分量");

        // proto 里写的 "id,,v" / "v;;i" 不能被静默压成合法声明
        Descriptor pk = DynamicTables.message("CsvPk", MessageOptions.newBuilder()
                        .setExtension(Proto2MysqlOption.tableName, "csv_pk")
                        .setExtension(Proto2MysqlOption.primaryKey, "id,,v").build(),
                field("id", 1, Type.TYPE_UINT64), field("v", 2, Type.TYPE_INT32), field("i", 3, Type.TYPE_INT32));
        rejects(INVALID_TABLE_OPTION, pk).hasMessage("表 csv_pk 的 primary_key 含空字段分量");
        Descriptor index = DynamicTables.message("CsvIndex", MessageOptions.newBuilder()
                        .setExtension(Proto2MysqlOption.tableName, "csv_index")
                        .setExtension(Proto2MysqlOption.index, "v;;i").build(),
                field("id", 1, Type.TYPE_UINT64), field("v", 2, Type.TYPE_INT32), field("i", 3, Type.TYPE_INT32));
        rejects(INVALID_TABLE_OPTION, index).hasMessage("表 csv_index 的 index[1] 含空字段分量");
        Descriptor emptyPk = DynamicTables.message("EmptyPk", MessageOptions.newBuilder()
                        .setExtension(Proto2MysqlOption.tableName, "empty_pk")
                        .setExtension(Proto2MysqlOption.primaryKey, "").build(),
                field("id", 1, Type.TYPE_UINT64));
        rejects(INVALID_TABLE_OPTION, emptyPk).hasMessage("表 empty_pk 的 primary_key 含空字段分量");
    }

    @Test
    void 不存在的字段_首尾空白_重复引用() {
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withPrimaryKey("nope"))
                .hasMessage("表 scalar_row 的 primary_key 引用了不存在的字段 \"nope\"");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withPrimaryKey(" id"))
                .hasMessage("表 scalar_row 的 primary_key 含空字段名或首尾空白 \" id\"");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withNullableFields("nope"))
                .hasMessage("表 scalar_row 的 nullable 引用了不存在的字段 \"nope\"");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withIndexes("i32,i32"))
                .hasMessage("表 scalar_row 的 index[0] 重复引用字段 \"i32\"");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withPrimaryKey("id", "id"))
                .hasMessage("表 scalar_row 的 primary_key 重复引用字段 \"id\"");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withAutoIncrementKey("nope"))
                .hasMessage("表 scalar_row 的 auto_increment_key 引用了不存在的字段 \"nope\"");
    }

    // ================================================================ max_length 与索引键长

    @Test
    void max_length只能写在键列上且须在区间内() {
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withMaxLength("text", 10))
                .hasMessageStartingWith("表 scalar_row 的字段 \"text\"（string，不在主键/唯一键里）声明了 max_length，"
                        + "但 max_length 目前仅用于主键/唯一键的 string/bytes 字段；");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withMaxLength("tags", 5))
                .hasMessageContaining("字段 \"tags\"（repeated uint32）声明了 max_length");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withMaxLength("counters", 5))
                .hasMessageContaining("字段 \"counters\"（map）声明了 max_length");
        rejects(INVALID_TABLE_OPTION, ScalarRow.getDefaultInstance(), withMaxLength("i32", 5))
                .hasMessageContaining("字段 \"i32\"（int32）声明了 max_length");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withMaxLength("token", 0))
                .hasMessage("表 kitchen_sink 的 bytes 键列 \"token\" 的 max_length=0 越界，取值须在 1..3072 个字节之间"
                        + "（单个索引键最多 3072 字节，VARCHAR 每字符按 4 字节、VARBINARY 每字节按 1 字节计）");
        rejects(INVALID_TABLE_OPTION, StringKeyRow.getDefaultInstance(), withMaxLength("sub", 769))
                .hasMessageContaining("string 键列 \"sub\" 的 max_length=769 越界，取值须在 1..768 个字符之间");
        // 负数按 uint32 解释，必然越界
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withMaxLength("token", -1))
                .hasMessageContaining("max_length=4294967295 越界");
        // 768 × 4 = 3072 刚好放得下（去掉含 sub 的联合索引，否则那条索引超长）
        assertThat(TableSchema.of(StringKeyRow.getDefaultInstance(), withMaxLength("sub", 768), withIndexes("seq"))
                .createTableSql()).contains("`sub` VARCHAR(768) ");
    }

    @Test
    void 单个索引超过3072字节被拒绝并逐列列出占用() {
        rejects(INVALID_TABLE_OPTION, StringKeyRow.getDefaultInstance(),
                withMaxLength("sub", 500), withMaxLength("provider", 500), withPrimaryKey("sub", "provider"))
                .hasMessage("表 string_key_row 的 primary_key 键长 4000 字节，超过单个索引 3072 字节的上限（建表会报 Error 1071）："
                        + "sub VARCHAR(500)×4=2000，provider VARCHAR(500)×4=2000；请调小 string/bytes 键列的 max_length 或减少索引列");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withMaxLength("token", 3072),
                withUniqueKey("token,owner_id"))
                .hasMessageContaining("unique_key 键长 3080 字节").hasMessageContaining("token VARBINARY(3072)=3072，owner_id uint64=8");
        rejects(INVALID_TABLE_OPTION, StringKeyRow.getDefaultInstance(), withMaxLength("sub", 700),
                withIndexes("sub,bio"))
                .hasMessageContaining("index[0] 键长 3564 字节")
                .hasMessageContaining("sub VARCHAR(700)×4=2800，bio MEDIUMTEXT 前缀(191)×4=764");
    }

    // ================================================================ 自增

    @Test
    void 自增列必须是整数且是某个键的第一列且不可空() {
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withAutoIncrementKey("slot"))
                .hasMessage("表 kitchen_sink 的 auto_increment_key \"slot\" 必须是某个主键/普通索引/唯一键的第一列");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withAutoIncrementKey("note"))
                .hasMessage("表 kitchen_sink 的 auto_increment_key \"note\" 必须是 int32/int64/uint32/uint64 标量字段，实际为 string");
        rejects(INVALID_TABLE_OPTION, KitchenSink.getDefaultInstance(), withAutoIncrementKey("tags"))
                .hasMessageContaining("实际为 uint32");
        rejects(INVALID_TABLE_OPTION, GolangTest.getDefaultInstance(), withNullableFields("port"),
                withAutoIncrementKey("port"), withIndexes("port"))
                .hasMessage("表 golang_test 的 auto_increment_key \"port\" 不能同时声明 nullable");
        // 普通索引 / 唯一键的第一列都算
        assertThat(TableSchema.of(KitchenSink.getDefaultInstance(), withAutoIncrementKey("slot"), withIndexes("slot,owner_id"))
                .createTableSql()).contains("`slot` int unsigned NOT NULL AUTO_INCREMENT COMMENT 'pb:2'");
    }
}
