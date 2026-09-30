package com.game.login.character;

import com.game.login.support.GoSpaces;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

/**
 * 角色名的纯规则：归一化与判定、服务端生成默认名。与 mmorpg {@code go/shared/playername} 逐条同义
 * （客户端可见：返回的名字、拒绝时的错误码都取决于这里）。
 *
 * <p>不连库、不读配表、不打日志；玩法数值（长度、前缀、后缀位数）由调用方从 RoleNameRule 表读出后以
 * {@link RoleNameRules} 传入。
 */
public final class PlayerNames {

    /** 随机后缀字母表：36 个字符，全部是允许字符且全小写（展示名与唯一键在后缀部分一致）。 */
    static final String GENERATE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    /** 252 = 36 × 7：随机字节 ≥ 252 丢弃重取（拒绝采样），否则直接取模会让前 4 个字符概率偏高。 */
    static final int GENERATE_REJECT_FROM = 252;

    private static final int IDEOGRAPHIC_ZERO = 0x3007;
    private static final int CJK_EXT_A_FIRST = 0x3400;
    private static final int CJK_EXT_A_LAST = 0x4DBF;
    private static final int CJK_BASIC_FIRST = 0x4E00;
    private static final int CJK_BASIC_LAST = 0x9FFF;

    /** 中文敏感词按子串匹配（中文无词边界，冒名通常带前后缀）。占位词表，与 mmorpg 内置词表一致。 */
    private static final List<String> SENSITIVE_SUBSTRINGS = List.of("管理员", "客服", "官方", "系统", "运营");
    /** ASCII 敏感词只做前缀匹配（做子串会误伤 sigma、magma）。 */
    private static final List<String> SENSITIVE_PREFIXES = List.of("gm");

    private PlayerNames() {
    }

    public enum Verdict {
        /** 合规。 */
        OK,
        /** 去空白后为空：由服务端生成默认名。 */
        EMPTY,
        /** 长度 / 字符集 / 非法 UTF-16。 */
        INVALID,
        /** 命中敏感词。 */
        SENSITIVE
    }

    /**
     * 归一化结果。{@code display} 只在 {@link Verdict#OK} 与 {@link Verdict#SENSITIVE} 时非空
     * （敏感词也给出，便于日志），其余判定为空串——调用方拿不到「没过校验却看着能用」的名字。
     */
    public record Normalized(Verdict verdict, String display) {
    }

    /**
     * 按固定顺序归一化玩家输入的名字并判定（顺序是契约的一部分）：
     * <ol>
     *   <li>含未配对的代理项（Java 字符串里的「非法 UTF-8」）→ INVALID；</li>
     *   <li>NFKC（全角折半角、表意空格折成空格、兼容汉字折正字）；</li>
     *   <li>去首尾空白（Go {@code TrimSpace} 口径）；为空 → EMPTY；</li>
     *   <li>码点数不在 [min, max] → INVALID；</li>
     *   <li>逐码点过 {@link #isAllowedCodePoint} → INVALID；</li>
     *   <li>唯一键 = 展示名转小写；命中敏感词 → SENSITIVE。</li>
     * </ol>
     */
    public static Normalized normalize(String raw, RoleNameRules rules) {
        if (raw == null || hasUnpairedSurrogate(raw)) {
            return new Normalized(Verdict.INVALID, "");
        }
        String s = GoSpaces.trim(Normalizer.normalize(raw, Normalizer.Form.NFKC));
        if (s.isEmpty()) {
            return new Normalized(Verdict.EMPTY, "");
        }
        int chars = s.codePointCount(0, s.length());
        if (chars < rules.minChars() || chars > rules.maxChars()) {
            return new Normalized(Verdict.INVALID, "");
        }
        if (!s.codePoints().allMatch(PlayerNames::isAllowedCodePoint)) {
            return new Normalized(Verdict.INVALID, "");
        }
        if (isSensitive(s.toLowerCase(Locale.ROOT))) {
            return new Normalized(Verdict.SENSITIVE, s);
        }
        return new Normalized(Verdict.OK, s);
    }

    /**
     * 允许出现在（已归一化的）角色名里的码点：{@code 0-9 A-Z a-z}、U+3007 〇、CJK 扩展 A（U+3400–4DBF）、
     * CJK 基本区（U+4E00–9FFF）。写成显式区间而不是「所有汉字」，是为了挡住 NFKC 折叠不到正字的部首 / 杭州码等冒名字形。
     */
    public static boolean isAllowedCodePoint(int cp) {
        return (cp >= '0' && cp <= '9')
                || (cp >= 'A' && cp <= 'Z')
                || (cp >= 'a' && cp <= 'z')
                || cp == IDEOGRAPHIC_ZERO
                || (cp >= CJK_EXT_A_FIRST && cp <= CJK_EXT_A_LAST)
                || (cp >= CJK_BASIC_FIRST && cp <= CJK_BASIC_LAST);
    }

    /** @param normalizedLower 已归一化并转小写的名字 */
    public static boolean isSensitive(String normalizedLower) {
        for (String word : SENSITIVE_SUBSTRINGS) {
            if (normalizedLower.contains(word)) {
                return true;
            }
        }
        for (String prefix : SENSITIVE_PREFIXES) {
            if (normalizedLower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成「前缀 + N 位 [a-z0-9]」的默认名。每位取一个随机字节 b：b ≥ 252 丢弃重取，否则取字母表第 {@code b % 36} 个。
     *
     * @param randomByte 随机字节源，每次返回 [0, 255]（只取低 8 位）。生产必须是密码学安全随机数：
     *                   名字可预测就能被人提前抢注；测试传固定序列即可得到确定结果
     * @throws IllegalStateException 拒绝采样次数耗尽（正常随机源下概率约为 0，出现即随机源坏了）
     */
    public static String generate(RoleNameRules rules, IntSupplier randomByte) {
        int suffixLen = rules.generatedSuffixLen();
        StringBuilder name = new StringBuilder(rules.generatedPrefix().length() + suffixLen);
        name.append(rules.generatedPrefix());
        int maxAttempts = suffixLen * 64 + 64;
        int got = 0;
        int attempts = 0;
        while (got < suffixLen) {
            if (attempts >= maxAttempts) {
                throw new IllegalStateException("随机后缀取 " + suffixLen + " 位试了 " + attempts + " 次仍未取满，随机源异常");
            }
            attempts++;
            int b = randomByte.getAsInt() & 0xFF;
            if (b >= GENERATE_REJECT_FROM) {
                continue;
            }
            name.append(GENERATE_ALPHABET.charAt(b % GENERATE_ALPHABET.length()));
            got++;
        }
        return name.toString();
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }
}
