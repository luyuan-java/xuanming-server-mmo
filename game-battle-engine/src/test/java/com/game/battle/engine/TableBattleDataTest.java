package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.table.BuffRows;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.MonsterTable;
import com.game.table.SkillRows;
import com.game.table.SkillTable;
import com.game.table.load.TableExpression;
import com.game.table.load.TableLoadException;
import com.game.table.load.TableSource;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TableBattleData} 对仓库里真实表数据（{@code config-data/tables}，与 mmorpg {@code generated/tables} 字节相同）的契约测试。
 * 前两个用例移植基线 {@code table_battle_data_provider_test.cpp:94-129}（【C++ 断言】）；其余是 Java 追加：表达式求值、
 * {@code random()} 闸（有意差异 D4）与快照绑定。基线同文件 :132-159 的两个 Class 表用例属于 2.x，不在本批。
 */
class TableBattleDataTest {

    private static final Path TABLES = Path.of("../config-data/tables");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ConfigTables tables;
    private static TableBattleData data;

    @TempDir
    Path tmp;

    @BeforeAll
    static void loadRealTables() {
        tables = ConfigTables.load(TABLES);
        data = new TableBattleData(tables);
    }

    // ---- 移植 tdp_test.cpp ----

    @Test
    void 副本怪物组与导出表一致_补位的0不进组() {
        // 【C++ 断言】tdp_test.cpp:94-100
        assertThat(data.dungeonMonsterIds(1)).containsExactly(1, 2);
        assertThat(data.dungeonMonsterIds(2)).containsExactly(6, 7);
        assertThat(data.dungeonMonsterIds(3)).containsExactly(11, 12, 16);
        assertThat(data.dungeonMonsterIds(999)).isEmpty();
    }

    @Test
    void 每只配置的怪都有战斗属性与奖励_副本引用的怪都存在() {
        // 【C++ 断言】tdp_test.cpp:103-129（uint64 的 > 0 即非 0）
        assertThat(tables.monster().all()).as("Monster 表为空 = PVE 全靠引擎回退常量").isNotEmpty();
        for (MonsterTable row : tables.monster().all()) {
            MonsterTable found = data.monster(row.getId()).orElseThrow();
            assertThat(found.getHealth()).as("0 血怪物开局即死 id=%d", row.getId()).isNotZero();
            assertThat(found.getStrength()).as("0 力量怪物打不出伤害 id=%d", row.getId()).isNotZero();
            assertThat(found.getSpeed()).as("0 速度怪物破坏出手序 id=%d", row.getId()).isNotZero();
            assertThat(found.getExpReward()).as("无经验奖励 id=%d", row.getId()).isNotZero();
            assertThat(found.getGoldReward()).as("无金币奖励 id=%d", row.getId()).isNotZero();
        }
        assertThat(tables.dungeon().all()).as("Dungeon 表为空 = 没有可打的副本").isNotEmpty();
        for (DungeonTable dungeon : tables.dungeon().all()) {
            List<Integer> group = data.dungeonMonsterIds(dungeon.getId());
            assertThat(group).as("dungeon %d 没配怪物组", dungeon.getId()).isNotEmpty();
            for (int monsterId : group) {
                assertThat(data.monster(monsterId)).as("dungeon %d 引用了不存在的怪物 %d", dungeon.getId(), monsterId).isPresent();
            }
        }
        assertThat(data.monster(0)).isEmpty();
        assertThat(data.monster(999999)).isEmpty();
    }

    // ---- Java 追加：表达式求值与其余查询 ----

    @Test
    void 技能伤害公式按施法者等级求值() {
        // Skill 1 = 100*level，Skill 13 = 10000*level（规格 §9.3）
        assertThat(data.skillDamage(1, 10)).isEqualTo(1000.0);
        assertThat(data.skillDamage(13, 10)).isEqualTo(100000.0);
        assertThat(data.skillDamage(999999, 10)).as("缺行返回 0（同基线生成代码）").isZero();
    }

    @Test
    void buff回血公式第二个参数按位置传损血() {
        // Buff 17 = 0.013*level*health，按公式原文左结合：(0.013*level)*health（规格 §9.3、§12.3 Q7）
        assertThat(data.buffHealthRegeneration(17, 10, 100)).isEqualTo(13.0);
        assertThat(data.buffHealthRegeneration(17, 85, 12130)).isEqualTo(13403.65);
        assertThat(data.buffHealthRegeneration(17, 85, 12130)).isEqualTo((0.013 * 85) * 12130);
        assertThat(data.buffHealthRegeneration(999999, 10, 100)).isZero();
    }

    @Test
    void 冷却时长按无符号毫秒_缺行为0() {
        // 规格 §9.9：cd 1 = 500 ms，cd 5 = 2000 ms
        assertThat(data.cooldownDurationMs(1)).isEqualTo(500);
        assertThat(data.cooldownDurationMs(5)).isEqualTo(2000);
        assertThat(data.cooldownDurationMs(999999)).isZero();
    }

    @Test
    void 行查询来自绑定的同一份快照() {
        SkillTable skill = data.skill(1).orElseThrow();
        assertThat(skill).isSameAs(tables.skill().get(1));
        assertThat(data.buff(17)).containsSame(tables.buff().get(17));
        assertThat(data.skillPermission(BattleConstants.COMBAT_STATE_SILENCE)).isPresent();
        assertThat(data.dungeon(1)).containsSame(tables.dungeon().get(1));
        assertThat(data.item(10)).containsSame(tables.item().get(10));
        assertThat(data.skill(999999)).isEmpty();
        assertThat(data.tables()).isSameAs(tables);
    }

    @Test
    void 构造时算好本快照的指纹() {
        assertThat(data.fingerprint()).hasSize(BattleTableFingerprint.HEX_LENGTH)
                .isEqualTo(BattleTableFingerprint.compute(tables));
    }

    // ---- Java 追加：random() 闸（有意差异 D4） ----

    @Test
    void 正式表的战斗公式都不含random() {
        for (SkillTable row : tables.skill().all()) {
            assertThat(TableBattleData.callsRandom(row.getDamage())).as("Skill %d", row.getId()).isFalse();
        }
        for (BuffTable row : tables.buff().all()) {
            assertThat(TableBattleData.callsRandom(row.getHealthRegeneration())).as("Buff %d", row.getId()).isFalse();
        }
    }

    @Test
    void random检测与表达式词法同口径() {
        assertThat(TableBattleData.callsRandom("random()*level")).isTrue();
        assertThat(TableBattleData.callsRandom("100*level+random()")).isTrue();
        assertThat(TableBattleData.callsRandom("RANDOM ()")).as("函数名大小写不敏感，名字与括号间可有空白").isTrue();
        assertThat(TableBattleData.callsRandom("Random\t(\n)")).isTrue();
        assertThat(TableBattleData.callsRandom("min(random(), 1)")).isTrue();
        assertThat(TableBattleData.callsRandom("(random())")).isTrue();
        assertThat(TableBattleData.callsRandom("100*level")).isFalse();
        assertThat(TableBattleData.callsRandom("0.013*level*health")).isFalse();
        assertThat(TableBattleData.callsRandom("")).isFalse();
        assertThat(TableBattleData.callsRandom(null)).isFalse();
        assertThat(TableBattleData.callsRandom("my_random()")).as("名字的一部分不算").isFalse();
        assertThat(TableBattleData.callsRandom("x1random()")).isFalse();
        assertThat(TableBattleData.callsRandom("随机random()")).as("前面是字母（含中文）也不算").isFalse();
        assertThat(TableBattleData.callsRandom("random　()")).as("全角空格也是空白（Character.isWhitespace）").isTrue();
    }

    @Test
    void random检测与表达式实际是否调用随机源一致() {
        // 对能编译通过的公式：检测命中 ⇔ 求值时真的调用了随机源
        List<String> formulas = List.of("random()", "RANDOM ()", "rAnDoM( )*level", "random　()", "random ()",
                "min(random(), level)", "100*level", "level+1", "max(1, level)", "-(level)^2");
        for (String formula : formulas) {
            TableExpression expression = TableExpression.compile("Skill", "damage", 1, formula, List.of("level"));
            int[] calls = {0};
            RandomGenerator counting = () -> {
                calls[0]++;
                return 0L;
            };
            expression.evaluate(new double[] {3}, counting);
            assertThat(TableBattleData.callsRandom(formula)).as("\"%s\"", formula).isEqualTo(calls[0] > 0);
        }
    }

    @Test
    void 技能伤害公式调用random时构造即拒绝() throws IOException {
        Path dir = copyTables();
        rewriteFirstRow(dir, SkillRows.SHEET, SkillRows.DATA_FILE, SkillTable.parser(),
                row -> ((SkillTable) row).toBuilder().setDamage("random()*level").build());
        ConfigTables withRandom = ConfigTables.load(dir); // 表达式本身合法，加载通过
        assertThatThrownBy(() -> new TableBattleData(withRandom))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("Skill")
                .hasMessageContaining("damage")
                .hasMessageContaining("random()");
    }

    @Test
    void buff回血公式调用random时构造即拒绝() throws IOException {
        Path dir = copyTables();
        rewriteFirstRow(dir, BuffRows.SHEET, BuffRows.DATA_FILE, BuffTable.parser(),
                row -> ((BuffTable) row).toBuilder().setHealthRegeneration("RANDOM ( ) * level").build());
        ConfigTables withRandom = ConfigTables.load(dir);
        assertThatThrownBy(() -> new TableBattleData(withRandom))
                .isInstanceOf(TableLoadException.class)
                .hasMessageContaining("health_regeneration");
    }

    @Test
    void 引擎不读的bonus_damage列不检查random() throws IOException {
        Path dir = copyTables();
        rewriteFirstRow(dir, BuffRows.SHEET, BuffRows.DATA_FILE, BuffTable.parser(),
                row -> ((BuffTable) row).toBuilder().setBonusDamage("random()").build());
        new TableBattleData(ConfigTables.load(dir));
    }

    @Test
    void 求值时的随机源一被调用就抛异常() throws IOException {
        // 第二道保险：即使绕过构造期的闸，含 random() 的公式在引擎求值时也会失败，而不是悄悄用上全局随机数
        assertThatThrownBy(() -> TableBattleData.FORBID_RANDOM.nextDouble()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TableBattleData.FORBID_RANDOM.nextLong()).isInstanceOf(IllegalStateException.class);
        Path dir = copyTables();
        rewriteFirstRow(dir, SkillRows.SHEET, SkillRows.DATA_FILE, SkillTable.parser(),
                row -> ((SkillTable) row).toBuilder().setDamage("random()*level").build());
        ConfigTables withRandom = ConfigTables.load(dir);
        SkillTable row = withRandom.skill().all().get(0);
        assertThatThrownBy(() -> withRandom.skill().evalDamage(row, 10, TableBattleData.FORBID_RANDOM))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("random()");
    }

    // ---- 工具：复制真表并改一行（同 ConfigTablesTest 的做法：重新编码 .pb 并更新 manifest 的 sha256） ----

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

    private static void rewriteFirstRow(Path dir, String sheet, String dataFile, Parser<? extends Message> parser,
                                        UnaryOperator<Message> edit) throws IOException {
        Path file = dir.resolve(dataFile);
        List<Message> rows = new ArrayList<>();
        CodedInputStream in = CodedInputStream.newInstance(Files.readAllBytes(file));
        for (int tag = in.readTag(); tag != 0; tag = in.readTag()) {
            rows.add(in.readMessage(parser, ExtensionRegistryLite.getEmptyRegistry()));
        }
        rows.set(0, edit.apply(rows.get(0)));
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
        Path manifest = dir.resolve(TableSource.MANIFEST);
        ObjectNode root = (ObjectNode) JSON.readTree(manifest.toFile());
        root.withArray("tables").forEach(t -> {
            if (t.path("name").asText().equals(sheet)) {
                t.withArray("artifacts").forEach(a -> {
                    if (a.path("kind").asText().equals("binary")) {
                        ((ObjectNode) a).put("sha256", sha);
                    }
                });
            }
        });
        JSON.writeValue(manifest.toFile(), root);
    }
}
