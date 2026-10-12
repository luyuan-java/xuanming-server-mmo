package com.game.discovery.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.battle.BattleRedis.EnterRead;
import com.game.discovery.battle.BattleRedis.SettlementField;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link BattleRedis} 的回复解析与编码纯函数（不连 Redis；做法同 {@code TeamMembershipReaderTest}）：{@code ENTER_READ} 的扁平回复 → {@link EnterRead}、
 * 待结算字段名的原始字节与规范判定（审计 RDS-11）、无符号十进制的解析与编码。
 */
class BattleRedisReplyTest {

    private static final long BATTLE = Long.MIN_VALUE + 77;   // ≥ 2^63：必须按无符号
    private static final String BATTLE_TEXT = "9223372036854775885";

    private static byte[] s(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static Map<String, String> lock(String state) {
        Map<String, String> lock = new LinkedHashMap<>();
        lock.put("b", BATTLE_TEXT);
        lock.put("n", "3");
        lock.put("s", state);
        lock.put("d", "1760000096000");
        lock.put("p", "1760000030000");
        return lock;
    }

    // ------------------------------------------------------------------ ENTER_READ

    @Test
    void 无锁无记录_TTL为负2_两个段都空() {
        EnterRead read = BattleRedis.toEnterRead(BattleRedis.flatEnterRead(-2, Map.of(), List.of()));

        assertThat(read.lockTtlSec()).isEqualTo(-2);
        assertThat(read.lock()).isEmpty();
        assertThat(read.settlements()).isEmpty();
        assertThat(read.lockBattleId()).isZero();
    }

    @Test
    void 有锁无记录_字段齐全_b按无符号解析() {
        EnterRead read = BattleRedis.toEnterRead(BattleRedis.flatEnterRead(155, lock("P"), List.of()));

        assertThat(read.lockTtlSec()).isEqualTo(155);
        assertThat(read.lock()).isEqualTo(lock("P"));
        assertThat(read.settlements()).isEmpty();
        assertThat(read.lockBattleId()).isEqualTo(BATTLE).isNegative();
    }

    @Test
    void 锁与记录都有_记录保持读出顺序_值原样() {
        byte[] first = {0x00, (byte) 0xFF, 0x0A, 0x00};
        byte[] second = {};
        EnterRead read = BattleRedis.toEnterRead(BattleRedis.flatEnterRead(200, lock("F"),
                List.of(SettlementField.of(BATTLE, first), SettlementField.of(5, second))));

        assertThat(read.lock().get(BattleRedis.FIELD_STATE)).isEqualTo("F");
        assertThat(read.settlements()).hasSize(2);
        assertThat(read.settlements().get(0).battleId()).isEqualTo(BATTLE);
        assertThat(read.settlements().get(0).value()).isEqualTo(first);
        assertThat(read.settlements().get(1).battleId()).isEqualTo(5);
        assertThat(read.settlements().get(1).value()).isEmpty();
    }

    @Test
    void 无锁有记录_锁段长度0_记录紧跟其后() {
        EnterRead read = BattleRedis.toEnterRead(BattleRedis.flatEnterRead(-2, Map.of(), List.of(SettlementField.of(9, s("x")))));

        assertThat(read.lock()).isEmpty();
        assertThat(read.settlements()).containsExactly(SettlementField.of(9, s("x")));
    }

    @Test
    void 整数项也可以是十进制字节串() {
        // Redisson 对多值回复里的整数给 Long；这里钉住解析对「整数以 bulk string 形式出现」同样成立，不依赖客户端版本
        List<Object> flat = new ArrayList<>(BattleRedis.flatEnterRead(0, lock("P"), List.of()));
        flat.set(0, s("-1"));
        flat.set(1, s("10"));

        EnterRead read = BattleRedis.toEnterRead(flat);

        assertThat(read.lockTtlSec()).isEqualTo(-1);
        assertThat(read.lock()).hasSize(5);
    }

    @Test
    void 回复形状不对_一律抛IllegalStateException() {
        byte[] k = s("5");
        byte[] v = s("v");
        List<List<Object>> bad = List.of(
                List.of(),                                             // 空回复
                List.of(-2L),                                          // 少于 2 项
                Arrays.asList(60L, 4L, s("b"), s("1")),                // 锁段声称 4 项，实际只有 2 项（越界）
                Arrays.asList(60L, -2L),                               // 锁段长度为负
                Arrays.asList(60L, 1L, s("b")),                        // 锁段长度为奇数
                Arrays.asList(-2L, 0L, k),                             // 记录段不成对（少一个值）
                Arrays.asList(-2L, 0L, k, v, k),
                Arrays.asList(s("abc"), 0L),                           // TTL 不是整数
                Arrays.asList(-2L, new Object()));                     // 锁段长度既不是数也不是字节串
        for (List<Object> flat : bad) {
            assertThatThrownBy(() -> BattleRedis.toEnterRead(flat)).as("回复 %s", flat).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> BattleRedis.toEnterRead(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 锁的b不是无符号十进制_lockBattleId为0() {
        for (String b : new String[] {"", "abc", "-5", "18446744073709551616", "1.5", " 7"}) {
            Map<String, String> lock = lock("P");
            lock.put("b", b);
            assertThat(BattleRedis.toEnterRead(BattleRedis.flatEnterRead(60, lock, List.of())).lockBattleId()).as("b=%s", b).isZero();
        }
        Map<String, String> max = lock("P");
        max.put("b", "18446744073709551615");
        assertThat(BattleRedis.toEnterRead(BattleRedis.flatEnterRead(60, max, List.of())).lockBattleId()).isEqualTo(-1L);
    }

    @Test
    void 快照不可变_改入参不影响已建好的快照() {
        Map<String, String> lock = lock("P");
        List<SettlementField> fields = new ArrayList<>(List.of(SettlementField.of(1, s("a"))));
        EnterRead read = new EnterRead(lock, 60, fields);
        lock.put("s", "F");
        fields.clear();

        assertThat(read.lock().get("s")).isEqualTo("P");
        assertThat(read.settlements()).hasSize(1);
        assertThatThrownBy(() -> read.settlements().add(SettlementField.of(2, s("b")))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> read.lock().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ 字段名：原始字节（RDS-11）

    @Test
    void 坏字段名保留原始字节_非法UTF8不被替换() {
        byte[] invalidUtf8 = {(byte) 0xFF, (byte) 0xFE, '1'};
        byte[] otherInvalid = {(byte) 0xC0, (byte) 0xFE, '1'};   // 按 UTF-8 解码会和上一个变成同一个串（都是两个 U+FFFD + "1"）
        assertThat(new String(invalidUtf8, StandardCharsets.UTF_8)).isEqualTo(new String(otherInvalid, StandardCharsets.UTF_8));

        EnterRead read = BattleRedis.toEnterRead(BattleRedis.flatEnterRead(-2, Map.of(), List.of(
                new SettlementField(invalidUtf8, s("a")), new SettlementField(otherInvalid, s("b")))));

        // 两个字段都在（没有因为解码成同一个 String 而互相覆盖），原始字节一字不差——拿它去 HDEL 才删得到
        assertThat(read.settlements()).hasSize(2);
        assertThat(read.settlements().get(0).rawName()).isEqualTo(invalidUtf8);
        assertThat(read.settlements().get(1).rawName()).isEqualTo(otherInvalid);
        assertThat(read.settlements().get(0).battleId()).isZero();
        assertThat(read.settlements().get(0).name()).isEqualTo("hex:fffe31");
        assertThat(read.settlements().get(1).name()).isEqualTo("hex:c0fe31");
    }

    @Test
    void 字段名只认规范的无符号十进制_其余按坏字段() {
        assertThat(new SettlementField(s("1"), s("")).battleId()).isEqualTo(1);
        assertThat(new SettlementField(s(BATTLE_TEXT), s("")).battleId()).isEqualTo(BATTLE);
        assertThat(new SettlementField(s("18446744073709551615"), s("")).battleId()).isEqualTo(-1L);
        // 宽松解析能读成数字、但销账按规范串 HDEL 删不到的写法：一律 0（坏字段），否则每次进场都会再应用一次
        for (String name : new String[] {"", "0", "05", "+5", "-5", " 5", "5 ", "5\n", "0x5", "5.0", "１２", "abc",
                "18446744073709551616", "184467440737095516150", "000000000000000000001"}) {
            assertThat(new SettlementField(name.getBytes(StandardCharsets.UTF_8), s("")).battleId()).as("字段名 [%s]", name).isZero();
        }
        // 宽松解析确实会放过前两种——这正是不能用它判字段名的原因
        assertThat(BattleRedis.parseUnsigned("05")).isEqualTo(5);
        assertThat(BattleRedis.parseUnsigned("+5")).isEqualTo(5);
    }

    @Test
    void 规范字段的名字就是无符号十进制_可读形式原样() {
        SettlementField field = SettlementField.of(BATTLE, s("payload"));

        assertThat(field.rawName()).isEqualTo(s(BATTLE_TEXT));
        assertThat(field.name()).isEqualTo(BATTLE_TEXT);
        assertThat(field.battleId()).isEqualTo(BATTLE);
        assertThat(new SettlementField(new byte[] {0x00}, s("")).name()).isEqualTo("hex:00");
        assertThat(new SettlementField(new byte[0], s("")).name()).isEmpty();
    }

    @Test
    void 字段按内容比较_不按数组引用() {
        SettlementField a = new SettlementField(new byte[] {1, 2}, new byte[] {3});
        SettlementField b = new SettlementField(new byte[] {1, 2}, new byte[] {3});

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new SettlementField(new byte[] {1, 2}, new byte[] {4}));
        assertThat(a).isNotEqualTo(new SettlementField(new byte[] {1}, new byte[] {3}));
        assertThat(a.toString()).contains("hex:0102").contains("1 字节");
        assertThatThrownBy(() -> new SettlementField(null, new byte[0])).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SettlementField(new byte[0], null)).isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------------------------------ 无符号十进制

    @Test
    void parseUnsigned_null空非法为0_大号按无符号() {
        assertThat(BattleRedis.parseUnsigned(null)).isZero();
        assertThat(BattleRedis.parseUnsigned("")).isZero();
        assertThat(BattleRedis.parseUnsigned("abc")).isZero();
        assertThat(BattleRedis.parseUnsigned("-1")).isZero();
        assertThat(BattleRedis.parseUnsigned("18446744073709551616")).isZero();   // 溢出 uint64
        assertThat(BattleRedis.parseUnsigned("0")).isZero();
        assertThat(BattleRedis.parseUnsigned("42")).isEqualTo(42);
        assertThat(BattleRedis.parseUnsigned("9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(BattleRedis.parseUnsigned("18446744073709551615")).isEqualTo(-1L);
    }

    @Test
    void 脚本参数的编码_battle_id无符号_TTL有符号() {
        assertThat(BattleRedis.unsigned(Long.MIN_VALUE)).isEqualTo(s("9223372036854775808"));
        assertThat(BattleRedis.unsigned(-1L)).isEqualTo(s("18446744073709551615"));
        assertThat(BattleRedis.unsigned(0)).isEqualTo(s("0"));
        assertThat(BattleRedis.number(180)).isEqualTo(s("180"));
        assertThat(BattleRedis.number(0)).isEqualTo(s("0"));
        assertThat(BattleRedis.text("P")).isEqualTo(new byte[] {'P'});
    }
}
