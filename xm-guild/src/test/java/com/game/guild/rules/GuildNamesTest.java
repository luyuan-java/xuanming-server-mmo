package com.game.guild.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * 帮名校验与判重键（基线 client_zone_test.go:122-151、guild_repo_test.go:171-192；guild-spec §2.4、§9.3、§11.1）。
 * 后半部分是 Java 增项：钉住 Go 语义与 JDK 缺省行为不同的边界字符（D14）。
 */
class GuildNamesTest {

    // ---- 基线用例 ----

    /** TestNormalizeGuildName（client_zone_test.go:144-151）。 */
    @Test
    void 展示名去首尾空白_恰好24个汉字合法() {
        assertThat(GuildNames.displayName("  青云门 ")).isEqualTo("青云门");
        assertThat(GuildNames.displayName("帮".repeat(GuildLimits.MAX_GUILD_NAME_RUNES))).isEqualTo("帮".repeat(24));
    }

    /** TestCreateGuild_RejectsInvalidNames（client_zone_test.go:122-128）。 */
    @Test
    void 空_全空白_超长_含控制字符一律非法() {
        assertThat(GuildNames.displayName("")).isNull();
        assertThat(GuildNames.displayName("   ")).isNull();
        assertThat(GuildNames.displayName("帮".repeat(GuildLimits.MAX_GUILD_NAME_RUNES + 1))).isNull();
        assertThat(GuildNames.displayName("青云\n门")).isNull();
        assertThat(GuildNames.displayName(null)).isNull();
        assertThat(GuildTip.INVALID_GUILD_NAME.code()).isEqualTo(GuildTips.NAME_INVALID);
    }

    /** TestGuildNameNorm（guild_repo_test.go:171-192）：NFKC → TrimSpace → 小写。 */
    @Test
    void 判重键_NFKC_去空白_小写() {
        assertThat(GuildNames.nameNorm("青云门")).isEqualTo("青云门");
        assertThat(GuildNames.nameNorm("  ABC ")).isEqualTo("abc");
        assertThat(GuildNames.nameNorm("ＡＢＣ")).isEqualTo("abc");
        assertThat(GuildNames.nameNorm("Ab　")).isEqualTo("ab");
        assertThat(GuildNames.nameNorm("")).isNull();
        assertThat(GuildNames.nameNorm("   ")).isNull();
        assertThat(GuildNames.nameNorm("帮".repeat(GuildLimits.MAX_GUILD_NAME_RUNES))).isEqualTo("帮".repeat(24));
        // ㍿ 经 NFKC 展开成 4 个字符（株式会社），13 个即 52 码点，超过 48
        assertThat(GuildNames.nameNorm("㍿".repeat(13))).isNull();
    }

    @Test
    void NFKC展开后超长的展示名也非法_恰好48码点合法() {
        assertThat(GuildNames.displayName("㍿".repeat(13))).as("展示名只有 13 个码点，但判重键 52 个").isNull();
        assertThat(GuildNames.displayName("㍿".repeat(12))).isEqualTo("㍿".repeat(12));
        assertThat(GuildNames.nameNorm("㍿".repeat(12))).isEqualTo("株式会社".repeat(12));
    }

    /** guild_repo_zone_test.go:110-151 的规范化撞名：这些写法得到同一个键（跨 zone 也算重名）。 */
    @Test
    void 全角_大小写_尾空格都撞同一个键() {
        String key = GuildNames.nameNorm("abc");
        assertThat(GuildNames.nameNorm("ABC")).isEqualTo(key);
        assertThat(GuildNames.nameNorm("ＡＢＣ")).isEqualTo(key);
        assertThat(GuildNames.nameNorm("abc ")).isEqualTo(key);
        assertThat(GuildNames.nameNorm("𝐀𝐁𝐂")).as("数学粗体 𝐀𝐁𝐂").isEqualTo(key);
    }

    @Test
    void 存库的展示名不做NFKC() {
        assertThat(GuildNames.displayName("ＡＢＣ")).isEqualTo("ＡＢＣ");
        assertThat(GuildNames.displayName(" 青 云 ")).as("中间的空白保留").isEqualTo("青 云");
        assertThat(GuildNames.nameNorm("青 云")).isEqualTo("青 云");
    }

    // ---- Java 增项（D14） ----

    /** Go unicode.IsControl 只认 U+0000–U+001F、U+007F–U+009F；U+00A0 不是控制字符。 */
    @Test
    void 控制字符边界() {
        assertThat(GuildNames.displayName("青\u0000云")).isNull();
        assertThat(GuildNames.displayName("青\u001F云")).isNull();
        assertThat(GuildNames.displayName("青\u007F云")).isNull();
        assertThat(GuildNames.displayName("青\u0080云")).isNull();
        assertThat(GuildNames.displayName("青\u009F云")).isNull();
        assertThat(GuildNames.displayName("青 云")).isEqualTo("青 云");
        assertThat(GuildNames.displayName("青~云")).isEqualTo("青~云");
        // 中间的 U+0085 是 C1 控制字符（虽然 Go 也把它算空白，但只有首尾会被 trim 掉）
        assertThat(GuildNames.displayName("青\u0085云")).isNull();
    }

    /** 首尾空白按 Go unicode.IsSpace：U+0085、U+00A0、U+3000 会被去掉；U+001C–U+001F 不是空白而是控制字符。 */
    @Test
    void 首尾空白按Go口径() {
        assertThat(GuildNames.displayName("\u0085青云门\u0085")).isEqualTo("青云门");
        assertThat(GuildNames.displayName(" 青云门 ")).isEqualTo("青云门");
        assertThat(GuildNames.displayName("　青云门　")).isEqualTo("青云门");
        assertThat(GuildNames.displayName("\t\n\u000B\f\r 青云门       ")).isEqualTo("青云门");
        // String.strip() 会把 U+001C 当空白去掉；Go 不会，于是它作为控制字符让帮名非法
        assertThat("\u001C青云门".strip()).isEqualTo("青云门");
        assertThat(GuildNames.displayName("\u001C青云门")).isNull();
        // 零宽空格 / BOM 不是空白也不是控制字符：照收（与 Go 相同）
        assertThat(GuildNames.displayName("﻿青云门")).isEqualTo("﻿青云门");
        // 24 个汉字加首尾空白仍合法（长度按 trim 之后数）
        assertThat(GuildNames.displayName("　" + "帮".repeat(24) + " ")).isEqualTo("帮".repeat(24));
    }

    /** 小写逐码点（Go unicode.ToLower 的简单映射），不能用 String.toLowerCase(Locale.ROOT)。 */
    @Test
    void 小写逐码点_İ与词尾Σ() {
        assertThat(GuildNames.nameNorm("İ")).isEqualTo("i");
        assertThat("İ".toLowerCase(Locale.ROOT)).as("JDK 的整串小写产出两个码点").isEqualTo("i̇");

        assertThat(GuildNames.nameNorm("ΑΣ")).isEqualTo("ασ");
        assertThat("ΑΣ".toLowerCase(Locale.ROOT)).as("JDK 的整串小写对词尾 Σ 用 ς").isEqualTo("ας");

        assertThat(GuildNames.nameNorm("ẞ")).as("大写 ẞ 没有兼容分解，逐码点小写成 ß").isEqualTo("ß");
        // 有兼容分解的字符先经 NFKC 展开再小写：U+01C5 ǅ → "Dž" → "dž"
        assertThat(GuildNames.nameNorm("ǅ")).isEqualTo("dž");
    }

    /** 长度按码点（Go rune）数，代理对字符算 1 个。 */
    @Test
    void 代理对字符按码点计数() {
        String emoji = "😀";
        assertThat(GuildNames.displayName(emoji.repeat(24))).isEqualTo(emoji.repeat(24));
        assertThat(GuildNames.displayName(emoji.repeat(25))).isNull();
        assertThat(GuildNames.nameNorm(emoji.repeat(24))).isEqualTo(emoji.repeat(24));
        // 判重键恰好 48 个码点合法、49 个非法
        assertThat(GuildNames.nameNorm("ab".repeat(24))).hasSize(48);
        assertThat(GuildNames.nameNorm("ab".repeat(24) + "c")).isNull();
    }

    @Test
    void 未配对的代理非法() {
        assertThat(GuildNames.displayName("\uD800青云")).isNull();
        assertThat(GuildNames.displayName("青云\uDC00")).isNull();
    }
}
