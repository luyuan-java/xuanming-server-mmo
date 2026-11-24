package com.game.contentguard.wordlist;

/** 词条匹配模式（对齐 Go {@code wordlist.Mode}）。 */
public enum MatchMode {
    /** 子串：任意位置命中，字间允许夹杂可跳过字符。 */
    SUB(0, "sub"),
    /** 整词：必须连续出现，前后不能紧挨 ASCII 字母或数字。 */
    TOKEN(1, "token"),
    /** 开头：只在整段首个非跳过字符起命中，中间允许夹杂可跳过字符。 */
    PREFIX(2, "prefix");

    public static final int COUNT = 3;

    private final int ordinalIndex;
    private final String wireName;

    MatchMode(int ordinalIndex, String wireName) {
        this.ordinalIndex = ordinalIndex;
        this.wireName = wireName;
    }

    public int index() { return ordinalIndex; }
    public String wireName() { return wireName; }

    public static MatchMode parse(String name) {
        if (name == null) {
            return null;
        }
        for (MatchMode m : values()) {
            if (m.wireName.equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** 缺省模式：≤3 字符纯 ASCII 走整词，其余走子串。 */
    public static MatchMode defaultFor(String word) {
        int count = 0;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            if (cp >= 0x80) {
                return SUB;
            }
            count++;
            i += Character.charCount(cp);
        }
        return count <= 3 ? TOKEN : SUB;
    }
}
