package com.game.login.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.login.character.PlayerNames.Normalized;
import com.game.login.character.PlayerNames.Verdict;
import java.util.Random;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class PlayerNamesTest {

    /** 与 RoleNameRule 表当前数据一致。 */
    static final RoleNameRules RULES = new RoleNameRules(2, 12, "道友", 6, 5);

    private static Normalized normalize(String raw) {
        return PlayerNames.normalize(raw, RULES);
    }

    @Test
    void 合规名字原样通过() {
        assertThat(normalize("张三")).isEqualTo(new Normalized(Verdict.OK, "张三"));
        assertThat(normalize("Abc12")).isEqualTo(new Normalized(Verdict.OK, "Abc12"));
        assertThat(normalize("〇〇")).isEqualTo(new Normalized(Verdict.OK, "〇〇"));
        // CJK 扩展 A。
        assertThat(normalize("㐀㐁")).isEqualTo(new Normalized(Verdict.OK, "㐀㐁"));
    }

    @Test
    void NFKC后再trim_展示名可能与输入不同() {
        assertThat(normalize("ＡＢ１２")).isEqualTo(new Normalized(Verdict.OK, "AB12"));
        assertThat(normalize("  张三 ")).isEqualTo(new Normalized(Verdict.OK, "张三"));
        // 表意空格 U+3000 经 NFKC 变成空格后被 trim。
        assertThat(normalize("　张三　")).isEqualTo(new Normalized(Verdict.OK, "张三"));
    }

    @Test
    void 空白名由服务端生成() {
        assertThat(normalize("").verdict()).isEqualTo(Verdict.EMPTY);
        assertThat(normalize("   ").verdict()).isEqualTo(Verdict.EMPTY);
        assertThat(normalize("　").verdict()).isEqualTo(Verdict.EMPTY);
        assertThat(normalize("\u0085").verdict()).isEqualTo(Verdict.EMPTY);
        assertThat(normalize("").display()).isEmpty();
    }

    @Test
    void 长度按码点计_不在区间即不合法() {
        assertThat(normalize("a").verdict()).isEqualTo(Verdict.INVALID);
        assertThat(normalize("张").verdict()).isEqualTo(Verdict.INVALID);
        assertThat(normalize("a".repeat(12)).verdict()).isEqualTo(Verdict.OK);
        assertThat(normalize("a".repeat(13)).verdict()).isEqualTo(Verdict.INVALID);
        assertThat(normalize("张".repeat(12)).verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    void 字符集之外不合法() {
        assertThat(normalize("ab cd").verdict()).isEqualTo(Verdict.INVALID);
        assertThat(normalize("a-b").verdict()).isEqualTo(Verdict.INVALID);
        assertThat(normalize("😀😀").verdict()).isEqualTo(Verdict.INVALID);
        // 扩展 B（U+20000）不开放。
        assertThat(normalize("𠀀𠀁").verdict()).isEqualTo(Verdict.INVALID);
        // 未配对的代理项 = 非法 UTF-8。
        assertThat(normalize("\uD800ab").verdict()).isEqualTo(Verdict.INVALID);
        assertThat(normalize("ab\uDC00").display()).isEmpty();
    }

    @Test
    void 敏感词() {
        assertThat(normalize("官方客服")).isEqualTo(new Normalized(Verdict.SENSITIVE, "官方客服"));
        assertThat(normalize("我是管理员").verdict()).isEqualTo(Verdict.SENSITIVE);
        assertThat(normalize("系统公告").verdict()).isEqualTo(Verdict.SENSITIVE);
        assertThat(normalize("运营小号").verdict()).isEqualTo(Verdict.SENSITIVE);
        // gm 前缀按小写唯一键判定；全角经 NFKC 折叠后同样命中。
        assertThat(normalize("GM001").verdict()).isEqualTo(Verdict.SENSITIVE);
        assertThat(normalize("ＧＭ12").verdict()).isEqualTo(Verdict.SENSITIVE);
        // gm 只做前缀：中间出现不误伤。
        assertThat(normalize("sigma").verdict()).isEqualTo(Verdict.OK);
        assertThat(normalize("magma").verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    void 生成名是前缀加6位小写字母数字() {
        assertThat(PlayerNames.generate(RULES, sequence(0, 1, 2, 3, 4, 5))).isEqualTo("道友abcdef");
        // 35 → '9'；36 % 36 → 'a'；251 % 36 = 35 → '9'。
        assertThat(PlayerNames.generate(RULES, sequence(35, 36, 251, 25, 26, 0))).isEqualTo("道友9a9z0a");
    }

    @Test
    void 大于等于252的字节丢弃重取() {
        assertThat(PlayerNames.generate(RULES, sequence(252, 255, 0, 253, 1, 2, 3, 4, 5)))
                .isEqualTo("道友abcdef");
    }

    @Test
    void 随机源坏了不死循环() {
        assertThatThrownBy(() -> PlayerNames.generate(RULES, () -> 255))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 生成名一定能通过自己的校验() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String name = PlayerNames.generate(RULES, () -> random.nextInt(256));
            assertThat(name).hasSize(8).startsWith("道友").matches("道友[a-z0-9]{6}");
            assertThat(normalize(name)).isEqualTo(new Normalized(Verdict.OK, name));
        }
    }

    private static IntSupplier sequence(int... bytes) {
        int[] next = {0};
        return () -> bytes[next[0]++];
    }
}
