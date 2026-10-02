package com.game.table.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.table.codegen.TableSchema.Kind;
import com.game.table.codegen.TableSchema.Table;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.UnknownFieldSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 手工构造描述符集（option 以未知字段形式存在，与 protoc 输出、不带扩展注册表解析时一致）验证 schema 读取。
 * 扩展号故意不用契约里的 600xx，证明读取方按扩展名查号、不写死。
 */
class TableSchemaReaderTest {

    private static final Map<String, Integer> MSG_EXT = Map.of("cfg_sheet", 1001, "cfg_source_file", 1002, "cfg_primary_key", 1003);
    private static final Map<String, Integer> FIELD_EXT = Map.of(
            "cfg_owner", 2002, "cfg_key", 2005, "cfg_multi", 2006, "cfg_index", 2007, "cfg_fk", 2010, "cfg_gfk", 2011);

    @Test
    void readsKeysIndexesAndForeignKeys() {
        FileDescriptorSet set = set(
                table("Reward", field("id", 1, Type.TYPE_UINT32, false)),
                table("Mission",
                        field("id", 1, Type.TYPE_UINT32, false),
                        withOpt(field("reward_id", 2, Type.TYPE_UINT32, false), "cfg_fk", "Reward"),
                        withOpt(field("condition_id", 3, Type.TYPE_UINT32, true), "cfg_gfk", "Reward"),
                        withBool(field("level", 4, Type.TYPE_UINT32, false), "cfg_index"),
                        withBool(field("name", 5, Type.TYPE_STRING, false), "cfg_key"),
                        withOpt(field("designer", 9001, Type.TYPE_INT32, false), "cfg_owner", "designer")));

        TableSchema schema = TableSchemaReader.read(set);

        assertThat(schema.javaPackage()).isEqualTo("com.game.table");
        assertThat(schema.tables()).extracting(Table::sheet).containsExactly("Mission", "Reward");
        Table mission = schema.table("Mission").orElseThrow();
        assertThat(mission.rowClass()).isEqualTo("MissionTable");
        assertThat(mission.primaryKey().name()).isEqualTo("id");
        assertThat(mission.multiPrimaryKey()).isFalse();
        assertThat(mission.keys()).singleElement().satisfies(k -> {
            assertThat(k.field().name()).isEqualTo("name");
            assertThat(k.field().kind()).isEqualTo(Kind.STRING);
            assertThat(k.multi()).isFalse();
        });
        assertThat(mission.indexes()).extracting(TableSchema.Field::name).containsExactly("level");
        assertThat(mission.foreignKeys()).extracting(fk -> fk.field().name() + "->" + fk.targetSheet() + "." + fk.targetColumn())
                .containsExactly("reward_id->Reward.id", "condition_id->Reward.id");
        assertThat(mission.fields()).extracting(TableSchema.Field::name).doesNotContain("designer");
        assertThat(mission.dataFile()).isEqualTo("mission.pb");
        assertThat(mission.field("condition_id").orElseThrow().getter()).isEqualTo("getConditionIdList()");
    }

    @Test
    void multiOnPrimaryKeyAndOnPlainColumn() {
        TableSchema schema = TableSchemaReader.read(set(table("Buff",
                withBool(field("id", 1, Type.TYPE_UINT32, false), "cfg_multi"),
                withBool(field("buff_type", 2, Type.TYPE_UINT32, false), "cfg_multi"))));
        Table buff = schema.tables().get(0);
        assertThat(buff.multiPrimaryKey()).isTrue();
        assertThat(buff.keys()).singleElement().satisfies(k -> assertThat(k.multi()).isTrue());
    }

    @Test
    void rejectsForeignKeyToMissingTable() {
        FileDescriptorSet set = set(table("Mission", field("id", 1, Type.TYPE_UINT32, false),
                withOpt(field("reward_id", 2, Type.TYPE_UINT32, false), "cfg_fk", "Reward")));
        assertThatThrownBy(() -> TableSchemaReader.read(set))
                .isInstanceOf(TableSchemaReader.SchemaException.class)
                .hasMessageContaining("目标表不存在");
    }

    @Test
    void rejectsForeignKeyTypeMismatch() {
        FileDescriptorSet set = set(
                table("Reward", field("id", 1, Type.TYPE_UINT32, false)),
                table("Mission", field("id", 1, Type.TYPE_UINT32, false),
                        withOpt(field("reward_id", 2, Type.TYPE_STRING, false), "cfg_fk", "Reward")));
        assertThatThrownBy(() -> TableSchemaReader.read(set)).hasMessageContaining("类型不一致");
    }

    @Test
    void rejectsForeignKeyIntoMultiPrimaryKeyTable() {
        FileDescriptorSet set = set(
                table("Reward", withBool(field("id", 1, Type.TYPE_UINT32, false), "cfg_multi")),
                table("Mission", field("id", 1, Type.TYPE_UINT32, false),
                        withOpt(field("reward_id", 2, Type.TYPE_UINT32, false), "cfg_fk", "Reward")));
        assertThatThrownBy(() -> TableSchemaReader.read(set)).hasMessageContaining("主键可重复");
    }

    @Test
    void rejectsRepeatedKey() {
        FileDescriptorSet set = set(table("Mission", field("id", 1, Type.TYPE_UINT32, false),
                withBool(field("tags", 2, Type.TYPE_UINT32, true), "cfg_key")));
        assertThatThrownBy(() -> TableSchemaReader.read(set)).hasMessageContaining("键列必须是");
    }

    @Test
    void rejectsMissingPrimaryKey() {
        FileDescriptorSet set = set(table("Mission", field("level", 1, Type.TYPE_UINT32, false)));
        assertThatThrownBy(() -> TableSchemaReader.read(set)).hasMessageContaining("找不到主键列 id");
    }

    @Test
    void noOptionsFileMeansNoTables() {
        FileDescriptorSet set = FileDescriptorSet.newBuilder()
                .addFile(FileDescriptorProto.newBuilder().setName("x.proto")
                        .addMessageType(DescriptorProto.newBuilder().setName("X")))
                .build();
        assertThat(TableSchemaReader.read(set).tables()).isEmpty();
    }

    // ------------------------------------------------------------------ 构造工具

    private static FileDescriptorSet set(FileDescriptorProto... tables) {
        FileDescriptorProto.Builder options = FileDescriptorProto.newBuilder().setName("cfg_options.proto");
        MSG_EXT.forEach((name, num) -> options.addExtension(ext(name, num, ".google.protobuf.MessageOptions")));
        FIELD_EXT.forEach((name, num) -> options.addExtension(ext(name, num, ".google.protobuf.FieldOptions")));
        FileDescriptorSet.Builder set = FileDescriptorSet.newBuilder().addFile(options);
        for (FileDescriptorProto t : tables) {
            set.addFile(t);
        }
        return set.build();
    }

    private static FieldDescriptorProto ext(String name, int number, String extendee) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setExtendee(extendee).build();
    }

    private static FileDescriptorProto table(String sheet, FieldDescriptorProto... fields) {
        UnknownFieldSet opts = UnknownFieldSet.newBuilder()
                .addField(MSG_EXT.get("cfg_sheet"), lengthDelimited(sheet))
                .build();
        DescriptorProto.Builder msg = DescriptorProto.newBuilder().setName(sheet + "Table")
                .setOptions(MessageOptions.newBuilder().setUnknownFields(opts));
        for (FieldDescriptorProto f : fields) {
            msg.addField(f);
        }
        return FileDescriptorProto.newBuilder().setName(sheet.toLowerCase() + "_table.proto")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.game.table").setJavaMultipleFiles(true))
                .addMessageType(msg)
                .build();
    }

    private static FieldDescriptorProto field(String name, int number, Type type, boolean repeated) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type)
                .setLabel(repeated ? Label.LABEL_REPEATED : Label.LABEL_OPTIONAL).build();
    }

    private static FieldDescriptorProto withOpt(FieldDescriptorProto f, String option, String value) {
        UnknownFieldSet merged = UnknownFieldSet.newBuilder(f.getOptions().getUnknownFields())
                .addField(FIELD_EXT.get(option), lengthDelimited(value)).build();
        return f.toBuilder().setOptions(FieldOptions.newBuilder().setUnknownFields(merged)).build();
    }

    private static FieldDescriptorProto withBool(FieldDescriptorProto f, String option) {
        UnknownFieldSet merged = UnknownFieldSet.newBuilder(f.getOptions().getUnknownFields())
                .addField(FIELD_EXT.get(option), UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        return f.toBuilder().setOptions(FieldOptions.newBuilder().setUnknownFields(merged)).build();
    }

    private static UnknownFieldSet.Field lengthDelimited(String value) {
        return UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8(value)).build();
    }
}
