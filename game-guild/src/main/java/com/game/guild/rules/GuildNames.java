package com.game.guild.rules;

import com.game.common.text.GoSpaces;
import java.text.Normalizer;

/**
 * 帮名校验与判重键（基线 guild_logic.go:175-191 normalizeGuildName、guild_repo.go:519-534 GuildNameNorm；guild-spec §2.4、D14）。
 *
 * <p>两版共享同一个 {@code uk_guild(name_norm)} 判重语义，所以必须<b>按 Go 语义</b>实现，每一步都有坑：
 * <ul>
 *   <li>去空白用 {@link GoSpaces#trim}（Go {@code unicode.IsSpace}）：{@link String#strip()} 不认 U+0085，却认 U+001C–U+001F；</li>
 *   <li>控制字符用 {@link Character#isISOControl(int)}：集合恰为 U+0000–U+001F、U+007F–U+009F，与 Go {@code unicode.IsControl} 相同；</li>
 *   <li>长度按码点数（Go rune），不按 UTF-16 char 数——代理对字符算 1 个；</li>
 *   <li>小写<b>逐码点</b> {@link Character#toLowerCase(int)}（简单映射，同 Go {@code unicode.ToLower}）；不能用
 *       {@code String.toLowerCase(Locale.ROOT)}：它把 {@code İ}(U+0130) 变成两个码点、把词尾 {@code Σ} 变成 {@code ς}，与 Go 不同；</li>
 *   <li>不能复用 {@code PlayerStore.nameKey}（NFKC → strip → toLowerCase(Locale.ROOT)），空白集合与大小写规则都不同。</li>
 * </ul>
 * NFKC 用 JDK {@link Normalizer}；Go 用 {@code golang.org/x/text/unicode/norm}。两边的 Unicode 版本若不同，极少数字符的 NFKC 结果可能不同
 * （§9.2 第 7 条），上线前用基线样例加随机样例对拍。全是纯函数，可在任意线程调用。
 */
public final class GuildNames {

    private GuildNames() {
    }

    /**
     * 校验客户端给的帮名，返回要存库与展示的名字；非法（→ 14009 {@link GuildTip#INVALID_GUILD_NAME}）返回 null。
     *
     * <p>步骤（guild_logic.go:175-191）：Go 口径 trim → 为空或码点数 &gt; 24 非法 → 任一码点是控制字符非法 → 判重键必须可生成
     * （{@link #nameNorm}）。返回值是 trim 之后的原文，<b>不做 NFKC</b>（:190）。
     *
     * <p>Java 增项：未配对的代理（不是合法 Unicode 标量值）也判非法。proto3 解析已保证 string 是合法 UTF-8，线上走不到这一支；
     * 留着它是因为这种字符写进 utf8mb4 会被替换成 {@code ?}，宁可拒绝（对应基线注释「不是合法 UTF-8」那一条，constants.go:74）。
     */
    public static String displayName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = GoSpaces.trim(raw);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > GuildLimits.MAX_GUILD_NAME_RUNES) {
            return null;
        }
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isISOControl(cp) || isLoneSurrogate(cp)) {
                return null;
            }
            i += Character.charCount(cp);
        }
        return nameNorm(name) == null ? null : name;
    }

    /**
     * 帮名判重键 {@code name_norm} = {@code ToLower(TrimSpace(NFKC(display)))}（guild_repo.go:529）；为空或码点数 &gt; 48 返回 null，
     * 调用方按帮名非法处理。建帮的仓储会对展示名再算一次（防御性重算，guild_repo.go:337-341）。
     *
     * <p>「青云门」与「青云门 」、「ABC」与「abc」、「ＡＢＣ」与「abc」都得到同一个键（跨 zone 也算重名）。
     */
    public static String nameNorm(String display) {
        if (display == null) {
            return null;
        }
        String trimmed = GoSpaces.trim(Normalizer.normalize(display, Normalizer.Form.NFKC));
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder lower = new StringBuilder(trimmed.length());
        int runes = 0;
        for (int i = 0; i < trimmed.length(); ) {
            int cp = trimmed.codePointAt(i);
            lower.appendCodePoint(Character.toLowerCase(cp));
            runes++;
            i += Character.charCount(cp);
        }
        return runes > GuildLimits.MAX_GUILD_NAME_NORM_RUNES ? null : lower.toString();
    }

    /** {@link String#codePointAt} 遇到未配对的代理时原样返回那个 char 值。 */
    private static boolean isLoneSurrogate(int cp) {
        return cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE;
    }
}
