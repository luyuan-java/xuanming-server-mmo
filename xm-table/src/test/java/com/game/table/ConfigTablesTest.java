package com.game.table;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.table.load.TableLoadException;
import com.game.table.load.TableSource;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 用仓库里同步来的真实表数据（config-data/tables）验证生成的 ConfigTables 与加载期校验。 */
class ConfigTablesTest {

    private static final Path TABLES = Path.of("../config-data/tables");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    void loadsAllTablesAndRowCountsMatchManifest() throws IOException {
        ConfigTables tables = ConfigTables.load(TABLES);

        ObjectNode manifest = (ObjectNode) JSON.readTree(TABLES.resolve(TableSource.MANIFEST).toFile());
        for (var entry : manifest.withArray("tables")) {
            String sheet = entry.path("name").asText();
            assertThat(tables.rowCounts()).as(sheet).containsEntry(sheet, entry.path("rows").asInt());
        }
        assertThat(tables.rowCounts().keySet()).containsExactlyElementsOf(ConfigTables.SHEETS.stream().sorted().toList());
    }

    @Test
    void sheetMissingFromManifestLoadsEmpty() throws IOException {
        // schema 里有、manifest 没登记（导表器尚未产出）：按空表处理并告警，不拒绝启动。
        Path dir = copyTables();
        editManifest(dir, tables -> removeSheet(tables, WorldRows.SHEET));
        ConfigTables tables = ConfigTables.load(dir);
        assertThat(tables.world().size()).isZero();
        assertThat(tables.world().find(1)).isEmpty();
    }

    @Test
    void primaryKeyLookups() {
        ConfigTables tables = ConfigTables.load(TABLES);
        WorldTable first = tables.world().all().get(0);
        assertThat(tables.world().get(first.getId())).isSameAs(first);
        assertThat(tables.world().contains(first.getId())).isTrue();
        assertThat(tables.world().find(Integer.MAX_VALUE)).isEmpty();
        assertThatThrownBy(() -> tables.world().get(Integer.MAX_VALUE))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("World");
        assertThat(tables.baseScene().contains(first.getSceneId())).isTrue();
    }

    @Test
    void secondaryKeysAndIndexes() {
        TestMultiKeyRows rows = ConfigTables.load(TABLES).testMultiKey();
        assertThat(rows.size()).isPositive();
        for (TestMultiKeyTable row : rows.all()) {
            assertThat(rows.findAll(row.getId())).contains(row);
            // 唯一键取值重复时（导表器不校验二级键；测试表里 string_key 就有重复）按表序取第一行。
            TestMultiKeyTable firstWithKey = rows.all().stream()
                    .filter(r -> r.getStringKey().equals(row.getStringKey())).findFirst().orElseThrow();
            assertThat(rows.findByStringKey(row.getStringKey())).containsSame(firstWithKey);
            assertThat(rows.findAllByMUint32Key(row.getMUint32Key())).contains(row);
            assertThat(rows.findAllByLevel(row.getLevel())).contains(row);
        }
        assertThat(rows.findAllByLevel(-12345)).isEmpty();
    }

    @Test
    void rowListsAndIndexesAreImmutable() {
        ConfigTables tables = ConfigTables.load(TABLES);
        assertThatThrownBy(() -> tables.skill().all().clear()).isInstanceOf(UnsupportedOperationException.class);
        TestMultiKeyTable row = tables.testMultiKey().all().get(0);
        assertThatThrownBy(() -> tables.testMultiKey().findAllByLevel(row.getLevel()).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void namedRowIdsExist() {
        ConfigTables tables = ConfigTables.load(TABLES);
        assertThat(tables.globalVariable().contains(TableConstants.GlobalVariable.ABNORMAL_LOGOUT)).isTrue();
    }

    @Test
    void rejectsTamperedDataFile() throws IOException {
        Path dir = copyTables();
        Path skill = dir.resolve(SkillRows.DATA_FILE);
        byte[] bytes = Files.readAllBytes(skill);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(skill, bytes);
        assertThatThrownBy(() -> ConfigTables.load(dir))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("sha256");
    }

    @Test
    void rejectsMissingDataFileListedInManifest() throws IOException {
        Path dir = copyTables();
        Files.delete(dir.resolve(WorldRows.DATA_FILE));
        assertThatThrownBy(() -> ConfigTables.load(dir))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining(WorldRows.DATA_FILE);
    }

    @Test
    void rejectsRowCountMismatch() throws IOException {
        Path dir = copyTables();
        editManifest(dir, tables -> tables.forEach(t -> {
            if (t.path("name").asText().equals(SkillRows.SHEET)) {
                ((ObjectNode) t).put("rows", t.path("rows").asInt() + 1);
            }
        }));
        assertThatThrownBy(() -> ConfigTables.load(dir))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("行数");
    }

    @Test
    void rejectsSheetUnknownToSchema() throws IOException {
        Path dir = copyTables();
        editManifest(dir, tables -> {
            ObjectNode copy = tables.get(0).deepCopy();
            copy.put("name", "NotInSchema");
            tables.add(copy);
        });
        assertThatThrownBy(() -> ConfigTables.load(dir))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("NotInSchema");
    }

    @Test
    void danglingForeignKeyIsRejected() throws IOException {
        // World 第一行的 scene_id 改成 BaseScene 里没有的 id（重新编码 .pb 并更新 manifest 的 sha256）。
        Path dir = copyTables();
        rewriteRows(dir, WorldRows.SHEET, WorldRows.DATA_FILE, WorldTable.parser(),
                rows -> rows.set(0, ((WorldTable) rows.get(0)).toBuilder().setSceneId(987654).build()));
        assertThatThrownBy(() -> ConfigTables.load(dir))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("World.scene_id -> BaseScene.id")
                .hasMessageContaining("987654");
    }

    @Test
    void foreignKeyIntoEmptyTargetIsSkipped() throws IOException {
        // 目标表为空（BaseScene 不在 manifest 里）：与导表器一致，跳过并告警，不拒绝启动。
        Path dir = copyTables();
        editManifest(dir, tables -> removeSheet(tables, BaseSceneRows.SHEET));
        ConfigTables tables = ConfigTables.load(dir);
        assertThat(tables.baseScene().size()).isZero();
        assertThat(tables.world().size()).isPositive();
    }

    @Test
    void rejectsUnknownFieldInsideStructColumn() throws IOException {
        Path dir = copyTables();
        UnknownFieldSet extra = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build();
        rewriteRows(dir, TestMultiKeyRows.SHEET, TestMultiKeyRows.DATA_FILE, TestMultiKeyTable.parser(), rows -> {
            TestMultiKeyTable row = (TestMultiKeyTable) rows.get(0);
            TestMultiKeytestobj1 element = TestMultiKeytestobj1.newBuilder().setUnknownFields(extra).build();
            rows.set(0, row.toBuilder().addTestobj1(element).build());
        });
        assertThatThrownBy(() -> ConfigTables.load(dir))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("testobj1[99]");
    }

    @Test
    void rejectsMissingDirectory() {
        assertThatThrownBy(() -> ConfigTables.load(tmp.resolve("nope")))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("配置表目录不存在");
    }

    private Path copyTables() throws IOException {
        Path dir = tmp.resolve("tables");
        Files.createDirectories(dir);
        try (Stream<Path> files = Files.list(TABLES)) {
            for (Path f : files.toList()) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        return dir;
    }

    private static void removeSheet(ArrayNode tables, String sheet) {
        for (int i = tables.size() - 1; i >= 0; i--) {
            if (tables.get(i).path("name").asText().equals(sheet)) {
                tables.remove(i);
            }
        }
    }

    /** 解码一张表的 .pb，改行后按导表器格式（字段 1 逐条）重新编码，并更新 manifest 里的 sha256 与行数。 */
    private static void rewriteRows(Path dir, String sheet, String dataFile, Parser<? extends Message> parser,
                                    Consumer<List<Message>> edit) throws IOException {
        Path file = dir.resolve(dataFile);
        List<Message> rows = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(Files.readAllBytes(file));
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            rows.add(in.readMessage(parser, ExtensionRegistryLite.getEmptyRegistry()));
        }
        edit.accept(rows);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(bytes);
        for (Message row : rows) {
            out.writeMessage(1, row);
        }
        out.flush();
        Files.write(file, bytes.toByteArray());
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        editManifest(dir, tables -> tables.forEach(t -> {
            if (t.path("name").asText().equals(sheet)) {
                ((ObjectNode) t).put("rows", rows.size());
                t.withArray("artifacts").forEach(a -> {
                    if (a.path("kind").asText().equals("binary")) {
                        ((ObjectNode) a).put("sha256", sha);
                    }
                });
            }
        }));
    }

    private static void editManifest(Path dir, Consumer<ArrayNode> edit) throws IOException {
        Path file = dir.resolve(TableSource.MANIFEST);
        ObjectNode root = (ObjectNode) JSON.readTree(file.toFile());
        edit.accept(root.withArray("tables"));
        JSON.writeValue(file.toFile(), root);
    }
}
