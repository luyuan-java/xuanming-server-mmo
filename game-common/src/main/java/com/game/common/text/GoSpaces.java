package com.game.common.text;

/**
 * 与 Go {@code unicode.IsSpace} / {@code strings.TrimSpace} 同义的空白判定。
 *
 * <p>为什么不用 {@link String#strip()}：Java 的 {@link Character#isWhitespace} 不认 U+0085（NEL）与不换行空格，
 * 却把 U+001C–U+001F 算作空白，和 mmorpg 的判定不一致。账号「首尾不能有空白」与角色名「trim 后为空则由服务端生成」
 * 都是两版共享的客户端可见行为，边界字符上必须与 Go 一致。
 *
 * <p>Go 的定义：Latin-1 区为 {@code \t \n \v \f \r 空格 U+0085 U+00A0}；其余按 Unicode White_Space，
 * 即 Zs / Zl / Zp 三类（{@link Character#isSpaceChar} 恰好覆盖，含 U+00A0）。
 */
public final class GoSpaces {

    private GoSpaces() {
    }

    public static boolean isSpace(int codePoint) {
        return switch (codePoint) {
            case '\t', '\n', 0x0B, '\f', '\r', 0x85 -> true;
            default -> Character.isSpaceChar(codePoint);
        };
    }

    /** 去掉首尾空白（按码点，不拆代理对）。 */
    public static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!isSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }
}
