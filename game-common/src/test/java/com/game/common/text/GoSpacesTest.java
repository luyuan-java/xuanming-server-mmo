package com.game.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoSpacesTest {

    @Test
    void 与Go的TrimSpace同口径() {
        assertThat(GoSpaces.trim("  abc \t\n")).isEqualTo("abc");
        // U+0085（NEL）与 U+00A0（不换行空格）在 Go 里是空白，Java 的 isWhitespace 不认。
        assertThat(GoSpaces.trim("\u0085abc ")).isEqualTo("abc");
        // 表意空格 U+3000、U+2028 行分隔符是 Unicode White_Space。
        assertThat(GoSpaces.trim("　abc ")).isEqualTo("abc");
        // U+001C 在 Java 的 isWhitespace 里算空白，Go 不算。
        assertThat(GoSpaces.trim("\u001Cabc")).isEqualTo("\u001Cabc");
        assertThat(GoSpaces.trim(" 　 ")).isEmpty();
        assertThat(GoSpaces.trim("")).isEmpty();
    }

    @Test
    void 中间的空白不动() {
        assertThat(GoSpaces.trim(" a b ")).isEqualTo("a b");
    }

    @Test
    void 不拆代理对() {
        String emoji = "😀";
        assertThat(GoSpaces.trim(" " + emoji + " ")).isEqualTo(emoji);
    }
}
