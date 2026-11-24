package com.game.contentguard.wordlist;

import com.game.proto.content_guard.TextCategory;

/** 词条类别，编码与契约 {@link TextCategory} 一一对应。 */
public enum WordCategory {
    UNSPECIFIED(0, "none", TextCategory.TEXT_CATEGORY_UNSPECIFIED),
    POLITICS(1, "politics", TextCategory.TEXT_CATEGORY_POLITICS),
    PORN(2, "porn", TextCategory.TEXT_CATEGORY_PORN),
    ABUSE(3, "abuse", TextCategory.TEXT_CATEGORY_ABUSE),
    AD(4, "ad", TextCategory.TEXT_CATEGORY_AD),
    IMPERSONATION(5, "impersonation", TextCategory.TEXT_CATEGORY_IMPERSONATION),
    OTHER(6, "other", TextCategory.TEXT_CATEGORY_OTHER);

    private static final WordCategory[] BY_CODE = values();

    private final int code;
    private final String label;
    private final TextCategory proto;

    WordCategory(int code, String label, TextCategory proto) {
        this.code = code;
        this.label = label;
        this.proto = proto;
    }

    public int code() { return code; }
    public String label() { return label; }
    public TextCategory proto() { return proto; }

    public static WordCategory fromCode(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : UNSPECIFIED;
    }

    public static WordCategory fromProto(TextCategory proto) {
        if (proto == null) {
            return UNSPECIFIED;
        }
        return fromCode(proto.getNumber());
    }

    /** 按词库文本名解析；未知或 {@code none} 返回 null。 */
    public static WordCategory parse(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (WordCategory c : BY_CODE) {
            if (c != UNSPECIFIED && c.label.equals(name)) {
                return c;
            }
        }
        return null;
    }
}
