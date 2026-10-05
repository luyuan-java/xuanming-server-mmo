package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.game.table.BuffRows;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.CooldownRows;
import com.game.table.CooldownTable;
import com.game.table.DungeonRows;
import com.game.table.DungeonTable;
import com.game.table.ItemRows;
import com.game.table.ItemTable;
import com.game.table.MonsterRows;
import com.game.table.MonsterTable;
import com.game.table.Monsterdrop;
import com.game.table.SkillPermissionRows;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillRows;
import com.game.table.SkillTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 战斗配表指纹（规格 §9.5、§13.2）。前四个用例逐条移植基线 {@code battle_table_fingerprint_test.cpp:87-171}，
 * 其后是 Java 追加的向量与真表核对。
 */
class BattleTableFingerprintTest {

    private static final Path TABLES = Path.of("../config-data/tables");
    private static final Path CONTRACT_SOURCE = Path.of("../contract/SOURCE.properties");
    /** 本稿基线：mmorpg 26ceb70ca（{@code contract/SOURCE.properties} 的 mmorpg.commit）。 */
    private static final String BASELINE_MMORPG_COMMIT = "26ceb70ca5771c5ab055c2ec7144c1e55f32c528";

    /** 七张表的行（对应基线夹具的 {@code TableSet}）；可变，用例就地改一列再算。 */
    private static final class TableSet {
        final List<SkillTable.Builder> skill = new ArrayList<>();
        final List<BuffTable.Builder> buff = new ArrayList<>();
        final List<CooldownTable.Builder> cooldown = new ArrayList<>();
        final List<SkillPermissionTable.Builder> permission = new ArrayList<>();
        final List<DungeonTable.Builder> dungeon = new ArrayList<>();
        final List<MonsterTable.Builder> monster = new ArrayList<>();
        final List<ItemTable.Builder> item = new ArrayList<>();

        String fingerprint() {
            return BattleTableFingerprint.computeFrom(
                    skill.stream().map(SkillTable.Builder::build).toList(),
                    buff.stream().map(BuffTable.Builder::build).toList(),
                    cooldown.stream().map(CooldownTable.Builder::build).toList(),
                    permission.stream().map(SkillPermissionTable.Builder::build).toList(),
                    dungeon.stream().map(DungeonTable.Builder::build).toList(),
                    monster.stream().map(MonsterTable.Builder::build).toList(),
                    item.stream().map(ItemTable.Builder::build).toList());
        }
    }

    /** 基线 {@code MakeTables()}（{@code fp_test.cpp:39-85}）：含 repeated、map 与子消息，覆盖确定性序列化的关键路径。 */
    private static TableSet makeTables() {
        TableSet tables = new TableSet();
        tables.skill.add(SkillTable.newBuilder().setId(101).addTargetingMode(1).addSkillType(1).setCooldownId(9).addEffect(201));
        tables.buff.add(BuffTable.newBuilder().setId(201).setDuration(12.0).setInterval(6.0).addIntervalEffect(10.0).setMaxLayer(3)
                // map 字段：确定性序列化按 key 排序，插入序不影响指纹
                .putTag("poison_tag", true).putTag("alpha_tag", true));
        tables.cooldown.add(CooldownTable.newBuilder().setId(9).setDuration(12000));
        tables.permission.add(SkillPermissionTable.newBuilder().setId(1));
        tables.dungeon.add(DungeonTable.newBuilder().setId(7).addMonster(1001));
        tables.monster.add(MonsterTable.newBuilder().setId(1001)
                .addDrop(Monsterdrop.newBuilder().setDropItem(10).setDropCount(1).setDropRate(10000)));
        tables.item.add(ItemTable.newBuilder().setId(10).setBattleUsable(1).setBattleHealHp(300));
        return tables;
    }

    private static void assertChanges(String base, String what, Consumer<TableSet> edit) {
        TableSet tables = makeTables();
        edit.accept(tables);
        assertThat(tables.fingerprint()).as(what + " 变化未反映到指纹").isNotEqualTo(base);
    }

    // ---- 逐条移植 fp_test.cpp ----

    @Test
    void 同一份表指纹相同且重复计算稳定() {
        // 【C++ 断言】fp_test.cpp:87-97
        TableSet first = makeTables();
        TableSet second = makeTables();
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.fingerprint()).isEqualTo(first.fingerprint());
    }

    @Test
    void 指纹是定长小写十六进制_空表也有确定值且与非空不同() {
        // 【C++ 断言】fp_test.cpp:99-110
        String fingerprint = makeTables().fingerprint();
        assertThat(fingerprint).hasSize(BattleTableFingerprint.HEX_LENGTH).matches("[0-9a-f]{32}");
        String empty = new TableSet().fingerprint();
        assertThat(empty).hasSize(BattleTableFingerprint.HEX_LENGTH).isNotEqualTo(fingerprint);
    }

    @Test
    void 七张表任一字段变化都改变指纹() {
        // 【C++ 断言】fp_test.cpp:112-160，九种改法
        String base = makeTables().fingerprint();
        assertChanges(base, "skill 字段", t -> t.skill.get(0).setCooldownId(10));
        assertChanges(base, "item 字段", t -> t.item.get(0).setBattleHealHp(301));
        assertChanges(base, "monster 掉落槽", t -> t.monster.get(0).getDropBuilder(0).setDropRate(5000));
        assertChanges(base, "buff 字段", t -> t.buff.get(0).setMaxLayer(4));
        assertChanges(base, "buff map 字段", t -> t.buff.get(0).putTag("extra_tag", true));
        assertChanges(base, "cooldown 字段", t -> t.cooldown.get(0).setDuration(12001));
        assertChanges(base, "skillpermission 字段", t -> t.permission.get(0).setId(2));
        assertChanges(base, "dungeon 字段", t -> t.dungeon.get(0).addMonster(1002));
        assertChanges(base, "monster 新增行", t -> t.monster.add(MonsterTable.newBuilder().setId(1002)));
    }

    @Test
    void 表序与段边界是契约() {
        // 【C++ 断言】fp_test.cpp:162-171：skill 有行 + buff 空 与 skill 空 + buff 有行 必须不同
        TableSet onlySkill = new TableSet();
        onlySkill.skill.add(SkillTable.newBuilder().setId(1));
        TableSet onlyBuff = new TableSet();
        onlyBuff.buff.add(BuffTable.newBuilder().setId(1));
        assertThat(onlySkill.fingerprint()).isNotEqualTo(onlyBuff.fingerprint());
    }

    // ---- Java 追加 ----

    @Test
    void map插入顺序不影响指纹() {
        TableSet sorted = makeTables();
        sorted.buff.set(0, sorted.buff.get(0).clearTag().putTag("alpha_tag", true).putTag("poison_tag", true));
        TableSet reversed = makeTables();
        reversed.buff.set(0, reversed.buff.get(0).clearTag().putTag("poison_tag", true).putTag("alpha_tag", true));
        // 非确定性序列化按插入序输出，字节不同；指纹走确定性序列化，相同
        assertThat(sorted.buff.get(0).build().toByteArray()).isNotEqualTo(reversed.buff.get(0).build().toByteArray());
        assertThat(sorted.fingerprint()).isEqualTo(reversed.fingerprint());
    }

    @Test
    void 空表指纹与语言无关() {
        // 【复核】每段是「表名 + NUL + 8 个零字节」；规格用 printf + sha256sum 独立算出，C++ 必然同值
        assertThat(new TableSet().fingerprint()).isEqualTo("fad2935e5c749630621c18c616048e32");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (String name : List.of("skill", "buff", "cooldown", "skillpermission", "dungeon", "monster", "item")) {
            buffer.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
            buffer.write(0);
            buffer.writeBytes(new byte[8]);
        }
        assertThat(sha256Hex(buffer.toByteArray()).substring(0, 32)).isEqualTo("fad2935e5c749630621c18c616048e32");
    }

    @Test
    void 基线夹具表的指纹向量() {
        // 【派生】只由 Java 算出；需要在 mmorpg 的 fp_test.cpp 加同值断言确认（规格 §13.9）
        assertThat(makeTables().fingerprint()).isEqualTo("11ca1a2d9b625cceb3ce199148d03133");
        TableSet onlySkill = new TableSet();
        onlySkill.skill.add(SkillTable.newBuilder().setId(1));
        assertThat(onlySkill.fingerprint()).isEqualTo("e1c97a64990a49b717cf32748fe0e84f");
        TableSet onlyBuff = new TableSet();
        onlyBuff.buff.add(BuffTable.newBuilder().setId(1));
        assertThat(onlyBuff.fingerprint()).isEqualTo("4635b07869db163d304ed141dba96c19");
    }

    @Test
    void 正式表_重序列化与原始字节求得的指纹相同() throws IOException {
        // 数据文件就是 <Sheet>TableData 的序列化字节：对它直接按同一格式求哈希，应与「加载后确定性重序列化」相同。
        // 依赖导表器输出是规范编码；失败时先查导表器（规格 §13.2）
        ConfigTables tables = ConfigTables.load(TABLES);
        assertThat(BattleTableFingerprint.compute(tables)).isEqualTo(rawFingerprint());
    }

    @Test
    void 正式表的指纹值() throws IOException {
        // 【复核】规格 §13.2：Java 重序列化 = 对七个 .pb 原始字节求哈希。只对本稿基线的表数据成立：
        // 同步表之后这里会跳过，确认上一个用例仍绿后把新值抄过来（可与 mmorpg scene 启动日志里的指纹对照）
        assumeTrue(BASELINE_MMORPG_COMMIT.equals(contractSource().getProperty("mmorpg.commit")),
                "config-data 已不是本稿基线 mmorpg 26ceb70ca 的数据，跳过固定指纹值");
        assertThat(new TableBattleData(ConfigTables.load(TABLES)).fingerprint()).isEqualTo("9382fd045e8ceacd121d06e514bf3b13");
    }

    // ---- 工具 ----

    /** 直接对七个 .pb 原始字节按 fp.cpp 的拼接格式求指纹（不经过解析与重序列化）。 */
    private static String rawFingerprint() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String[][] sections = {
                {"skill", SkillRows.DATA_FILE},
                {"buff", BuffRows.DATA_FILE},
                {"cooldown", CooldownRows.DATA_FILE},
                {"skillpermission", SkillPermissionRows.DATA_FILE},
                {"dungeon", DungeonRows.DATA_FILE},
                {"monster", MonsterRows.DATA_FILE},
                {"item", ItemRows.DATA_FILE},
        };
        for (String[] section : sections) {
            byte[] bytes = Files.readAllBytes(TABLES.resolve(section[1]));
            buffer.writeBytes(section[0].getBytes(StandardCharsets.US_ASCII));
            buffer.write(0);
            long size = bytes.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                buffer.write((int) (size >>> shift) & 0xFF);
            }
            buffer.writeBytes(bytes);
        }
        return sha256Hex(buffer.toByteArray()).substring(0, 32);
    }

    private static Properties contractSource() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(CONTRACT_SOURCE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
