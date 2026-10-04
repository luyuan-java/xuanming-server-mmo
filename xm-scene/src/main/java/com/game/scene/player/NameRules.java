package com.game.scene.player;

/**
 * 玩家自定义展示名的校验（属性方案名、宝宝名共用，同基线 player_attribute.cpp / player_pet.cpp 的同一口径）：
 * 非空、码点数不超上限、不含控制字符（U+0000–U+001F、U+007F）、至少一个可见码点
 * （全空格 / 零宽空格 / U+3000 / U+FEFF 之类不算，与客户端 IsNullOrWhiteSpace 对齐）。
 * 非法 UTF-8 在协议解析层就被拒（请求整条丢弃），到不了这里。
 */
public final class NameRules {

    private NameRules() {
    }

    /** @param maxCodePoints 码点数上限（uint32，long 承载） */
    public static boolean isValidDisplayName(String name, long maxCodePoints) {
        if (name.isEmpty()) {
            return false;
        }
        long codePoints = 0;
        boolean visible = false;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (cp < 0x20 || cp == 0x7F) {
                return false;
            }
            i += Character.charCount(cp);
            codePoints++;
            if (!isInvisible(cp)) {
                visible = true;
            }
        }
        return visible && codePoints <= maxCodePoints;
    }

    private static boolean isInvisible(int cp) {
        return cp == 0x20 || cp == 0xA0 || (cp >= 0x2000 && cp <= 0x200F) || cp == 0x2028 || cp == 0x2029
                || cp == 0x202F || cp == 0x205F || cp == 0x2060 || cp == 0x3000 || cp == 0xFEFF;
    }
}
