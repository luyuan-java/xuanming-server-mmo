package com.game.pbmysql;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 与 Go 版逐字节一致的字符串工具：标识符 / 注释转义、超长标识符截断、按 Go 语义的拆分与去空白、Go {@code %q}。
 *
 * <p>DDL 文本要与 Go v0.2.0 逐字节相同，所以这里刻意不用 Java 习惯的 {@code String.split} / {@code strip}：
 * 前者丢尾部空串，后者的空白集合与 Go {@code unicode.IsSpace} 不同。
 */
final class MysqlSyntax {

    /** MySQL 标识符（表名 / 列名 / 索引名）的硬上限，单位是字符；超了是 Error 1059。 */
    static final int MAX_IDENTIFIER_LENGTH = 64;

    private static final int FNV32_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV32_PRIME = 0x01000193;

    /** Go 版 {@code mysqlKeywordPattern}：只用于告警，不影响生成（列名一律加反引号）。 */
    private static final Pattern KEYWORD = Pattern.compile(
            "^(SELECT|INSERT|UPDATE|DELETE|FROM|WHERE|AND|OR|JOIN|ON|IN|NOT|NULL|PRIMARY|KEY|INDEX|UNIQUE|AUTO_INCREMENT"
                    + "|INT|VARCHAR|TEXT|BLOB|DATETIME|TIMESTAMP|FLOAT|DOUBLE|BOOL|TINYINT|BIGINT)$");

    private MysqlSyntax() {
    }

    /** 把标识符整体包进反引号，内部反引号双写（兼容含点号的 proto full name 表名）。 */
    static String escapeName(String name) {
        return "`" + name.replace("`", "``") + "`";
    }

    /**
     * 转义 {@code COMMENT '...'} 里的字符串字面量：先双写反斜杠（默认 SQL mode 下反斜杠参与转义），再把单引号倍写，
     * 换行 / 回车换成空格。一趟扫描完成，与 Go 的 {@code strings.NewReplacer} 同语义。
     */
    static String escapeComment(String comment) {
        StringBuilder b = new StringBuilder(comment.length() + 8);
        for (int i = 0; i < comment.length(); i++) {
            char c = comment.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\'' -> b.append("''");
                case '\n', '\r' -> b.append(' ');
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * 把超长标识符压回 64 字符：保留前缀 + {@code _%08x}（整个名字 UTF-8 字节的 FNV-1a 32 位指纹）。
     * 按码点而不是 UTF-16 单元切，与 Go 按 rune 切一致；同一输入永远得到同一输出，建表与补索引两条路径叫出同一个名字。
     */
    static String truncateIdentifier(String name) {
        int runes = name.codePointCount(0, name.length());
        if (runes <= MAX_IDENTIFIER_LENGTH) {
            return name;
        }
        String suffix = String.format("_%08x", fnv1a32(name.getBytes(StandardCharsets.UTF_8)));
        int keep = MAX_IDENTIFIER_LENGTH - suffix.length();
        int end = name.offsetByCodePoints(0, keep);
        return name.substring(0, end) + suffix;
    }

    /** FNV-1a 32 位（与 Go {@code hash/fnv.New32a} 相同），按无符号返回。 */
    static long fnv1a32(byte[] data) {
        int h = FNV32_OFFSET_BASIS;
        for (byte b : data) {
            h ^= (b & 0xff);
            h *= FNV32_PRIME;
        }
        return Integer.toUnsignedLong(h);
    }

    /** 同 Go {@code strings.Split}：保留所有空分量，空串得到一个空分量。 */
    static List<String> goSplit(String s, char sep) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** 同 Go 版 {@code splitTrimmed}：按逗号拆、逐项去首尾空白、丢掉空项。 */
    static List<String> splitTrimmed(String s) {
        List<String> out = new ArrayList<>();
        for (String part : goSplit(s, ',')) {
            String trimmed = goTrimSpace(part);
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /** 同 Go {@code strings.TrimSpace}：空白集合取 Go {@code unicode.IsSpace}。 */
    static String goTrimSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!isGoSpace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!isGoSpace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }

    /** Go {@code unicode.IsSpace}：Latin-1 里的 \t \n \v \f \r 空格 U+0085 U+00A0，其余取 Unicode White_Space 属性。 */
    static boolean isGoSpace(int cp) {
        if (cp <= 0xff) {
            return cp == '\t' || cp == '\n' || cp == 0x0b || cp == '\f' || cp == '\r' || cp == ' '
                    || cp == 0x85 || cp == 0xa0;
        }
        return cp == 0x1680 || (cp >= 0x2000 && cp <= 0x200a) || cp == 0x2028 || cp == 0x2029
                || cp == 0x202f || cp == 0x205f || cp == 0x3000;
    }

    /** 近似 Go {@code %q}（{@code strconv.Quote}）：双引号包裹，转义反斜杠、双引号与控制字符，可打印字符原样保留。 */
    static String goQuote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        b.append(String.format("\\x%02x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** 字段名是否撞 MySQL 关键字（Go 版只告警）。 */
    static boolean isKeyword(String name) {
        return KEYWORD.matcher(name.toUpperCase(java.util.Locale.ROOT)).matches();
    }

    /** 码点数（Go 的 {@code len([]rune(s))}）。 */
    static int runeCount(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Java 字符串能否无损编码成 UTF-8：不能有落单的代理项（驱动会把它编成 '?'，静默改值）。 */
    static boolean isWellFormedUtf16(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
