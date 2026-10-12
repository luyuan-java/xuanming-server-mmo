package com.game.battle.engine;

import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.CooldownTable;
import com.game.table.DungeonTable;
import com.game.table.ItemTable;
import com.game.table.MonsterTable;
import com.game.table.SkillPermissionTable;
import com.game.table.SkillTable;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 战斗配表指纹（基线 {@code battle_table_fingerprint.{h,cpp}}，规格 §9.5–§9.6）：七张战斗表内容的短摘要，随快照携带，
 * 让出快照的 scene、凑局的 match 与开局的 battle 节点确认读的是同一份战斗配表（确定性的前提）。
 *
 * <p>算法（逐字节同基线 {@code fp.cpp:34-86}）：按固定表序 skill、buff、cooldown、skillpermission、dungeon、monster、item，
 * 每张表做确定性序列化（map 按键排序），拼成 {@code 表名 + '\0' + 8 字节大端段长 + 字节}，整体 sha256 后取小写 hex 前 32 位。
 * 表序是契约，新表只能往尾部追加。
 *
 * <p>Java 没有外层 {@code <Sheet>TableData} 消息类：逐行写字段 1，与序列化外层消息的字节相同（数据文件本身就是这个格式，
 * 见 {@code TableSource}）。基线在进程内缓存指纹、靠 {@code Refresh} 跟随表重载；Java 按快照计算（{@link TableBattleData}
 * 构造时算好），热更换快照时自然重算（规格 D3）。
 *
 * <p>跨语言边角（规格 §9.8，现有数据都不涉及）：Java 确定性序列化对整数键 map 按有符号排序、字符串键按 UTF-16 排序，
 * C++ 分别按无符号、UTF-8 字节排序；七张战斗表只有 {@code map<string, bool>} 且键全是 ASCII。
 */
public final class BattleTableFingerprint {

    /** 指纹长度：sha256 hex 的前 32 位（16 字节）。 */
    public static final int HEX_LENGTH = 32;

    private BattleTableFingerprint() {
    }

    /** 对一份配置表快照计算指纹。 */
    public static String compute(ConfigTables tables) {
        return computeFrom(tables.skill().all(), tables.buff().all(), tables.cooldown().all(), tables.skillPermission().all(),
                tables.dungeon().all(), tables.monster().all(), tables.item().all());
    }

    /** 对七张表的行（各自按表序）计算指纹；纯函数，单测直接构造行（对应基线 {@code ComputeFrom}）。 */
    static String computeFrom(List<SkillTable> skill, List<BuffTable> buff, List<CooldownTable> cooldown,
                              List<SkillPermissionTable> skillPermission, List<DungeonTable> dungeon,
                              List<MonsterTable> monster, List<ItemTable> item) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        // 表序固定：改顺序 = 改指纹契约；新表只能追加在末尾
        appendSection(buffer, "skill", skill);
        appendSection(buffer, "buff", buff);
        appendSection(buffer, "cooldown", cooldown);
        appendSection(buffer, "skillpermission", skillPermission);
        appendSection(buffer, "dungeon", dungeon);
        appendSection(buffer, "monster", monster);
        appendSection(buffer, "item", item);
        return HexFormat.of().formatHex(sha256(buffer.toByteArray())).substring(0, HEX_LENGTH);
    }

    /** 追加一段：表名 + NUL + 8 字节大端段长 + 确定性序列化字节（段边界固定，相邻两表不会「借位」碰撞）。 */
    private static void appendSection(ByteArrayOutputStream out, String tableName, List<? extends Message> rows) {
        byte[] bytes = serializeDeterministic(rows);
        out.writeBytes(tableName.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        long size = bytes.length;
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (size >>> shift) & 0xFF);
        }
        out.writeBytes(bytes);
    }

    /** 等价于确定性序列化 {@code <Sheet>TableData { repeated <Sheet>Table data = 1; }}。 */
    private static byte[] serializeDeterministic(List<? extends Message> rows) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        CodedOutputStream coded = CodedOutputStream.newInstance(body);
        // 关键：map 字段按键排序输出，否则同一份表两次加载的 map 迭代序可能不同
        coded.useDeterministicSerialization();
        try {
            for (Message row : rows) {
                coded.writeMessage(1, row);
            }
            coded.flush();
        } catch (IOException e) {
            // 写内存流不会失败；万一失败也不能静默产出半截指纹
            throw new UncheckedIOException(e);
        }
        return body.toByteArray();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }
}
