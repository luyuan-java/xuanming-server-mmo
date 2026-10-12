package com.game.login.character;

import com.game.table.RoleNameRuleTable;
import java.util.Locale;

/**
 * 角色名规则（RoleNameRule 表 id=1 行）。构造即自检，拿到实例就一定能用：
 * <ul>
 *   <li>1 ≤ min ≤ max ≤ {@value #STRUCTURAL_MAX_CHARS}（结构上限：存储与客户端输入框的物理约束，不是玩法数值）；</li>
 *   <li>生成名前缀非空、每个字符都是允许字符、且不命中敏感词（否则服务端生成的名字过不了自己的校验）；</li>
 *   <li>1 ≤ 后缀位数 ≤ {@value #MAX_GENERATED_SUFFIX_LEN}，前缀字数 + 后缀位数落在 [min, max]；</li>
 *   <li>撞名最多尝试次数在 [{@value #MIN_GENERATE_ATTEMPTS}, {@value #MAX_GENERATE_ATTEMPTS}]。</li>
 * </ul>
 * 与 mmorpg {@code playernamereg.rulesFromRow} 同一套规则；任一项不满足即拒绝建角（2020），不带着坏规则放行。
 */
public record RoleNameRules(int minChars, int maxChars, String generatedPrefix, int generatedSuffixLen,
                            int maxGenerateAttempts) {

    public static final int ROW_ID = 1;
    public static final int STRUCTURAL_MAX_CHARS = 32;
    public static final int MAX_GENERATED_SUFFIX_LEN = 16;
    public static final int MIN_GENERATE_ATTEMPTS = 1;
    public static final int MAX_GENERATE_ATTEMPTS = 10;

    /** @throws IllegalArgumentException 规则不自洽 */
    public RoleNameRules {
        if (minChars < 1 || maxChars > STRUCTURAL_MAX_CHARS || minChars > maxChars) {
            throw new IllegalArgumentException("min_chars=" + minChars + " max_chars=" + maxChars
                    + " 必须满足 1 ≤ min ≤ max ≤ " + STRUCTURAL_MAX_CHARS);
        }
        if (generatedPrefix == null || generatedPrefix.isEmpty()) {
            throw new IllegalArgumentException("generated_prefix 为空");
        }
        if (!generatedPrefix.codePoints().allMatch(PlayerNames::isAllowedCodePoint)) {
            throw new IllegalArgumentException("generated_prefix 含不允许的字符: " + generatedPrefix);
        }
        if (PlayerNames.isSensitive(generatedPrefix.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("generated_prefix 命中敏感词: " + generatedPrefix);
        }
        if (generatedSuffixLen < 1 || generatedSuffixLen > MAX_GENERATED_SUFFIX_LEN) {
            throw new IllegalArgumentException("generated_suffix_len=" + generatedSuffixLen
                    + " 必须在 [1, " + MAX_GENERATED_SUFFIX_LEN + "]");
        }
        int total = generatedPrefix.codePointCount(0, generatedPrefix.length()) + generatedSuffixLen;
        if (total < minChars || total > maxChars) {
            throw new IllegalArgumentException("前缀字数 + 后缀位数 = " + total + "，不在 [" + minChars + ", " + maxChars + "]");
        }
        if (maxGenerateAttempts < MIN_GENERATE_ATTEMPTS || maxGenerateAttempts > MAX_GENERATE_ATTEMPTS) {
            throw new IllegalArgumentException("max_generate_attempts=" + maxGenerateAttempts
                    + " 必须在 [" + MIN_GENERATE_ATTEMPTS + ", " + MAX_GENERATE_ATTEMPTS + "]");
        }
    }

    /**
     * 从配表行构造。表里是 uint32，先按无符号数挡住越界值再转 int，避免超大值在 Java 里变成负数绕过上限检查。
     *
     * @throws IllegalArgumentException 行缺失或不合法
     */
    public static RoleNameRules fromRow(RoleNameRuleTable row) {
        if (row == null) {
            throw new IllegalArgumentException("RoleNameRule 表缺少 id=" + ROW_ID + " 的规则行");
        }
        return new RoleNameRules(
                boundedUnsigned(row.getMinChars(), "min_chars"),
                boundedUnsigned(row.getMaxChars(), "max_chars"),
                row.getGeneratedPrefix(),
                boundedUnsigned(row.getGeneratedSuffixLen(), "generated_suffix_len"),
                boundedUnsigned(row.getMaxGenerateAttempts(), "max_generate_attempts"));
    }

    private static int boundedUnsigned(int raw, String field) {
        long value = Integer.toUnsignedLong(raw);
        if (value > STRUCTURAL_MAX_CHARS) {
            throw new IllegalArgumentException("RoleNameRule." + field + "=" + value + " 超出上限");
        }
        return (int) value;
    }
}
