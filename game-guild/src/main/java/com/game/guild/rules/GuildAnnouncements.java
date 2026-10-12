package com.game.guild.rules;

/**
 * 帮会公告的服务端校验（基线 guild_logic.go:524-526、constants.go:200-203；guild-spec §2.5）。
 *
 * <p>只有一条：<b>未 trim 的原始 UTF-8 字节数</b> &gt; 600 → 14011 {@link GuildTip#ANNOUNCEMENT_TOO_LONG}。不 trim、不检查字符集与控制字符；
 * 空串合法（等于清空公告）。这一步排在身份与归属区之前（client_zone_test.go:153-161）。客户端本地更严（trim 后 ≤ 200 字且 ≤ 600 字节，
 * GuildClient.cs:59-62），服务端按原始字节判。纯函数，可在任意线程调用。
 */
public final class GuildAnnouncements {

    private GuildAnnouncements() {
    }

    /** 公告是否超长（基线 {@code len(announcement) > MaxAnnouncementBytes}）。null 按空串处理（proto string 不会是 null）。 */
    public static boolean tooLong(String announcement) {
        return utf8Length(announcement) > GuildLimits.MAX_ANNOUNCEMENT_BYTES;
    }

    /**
     * 字符串编码成 UTF-8 的字节数，不分配数组（等于 {@code s.getBytes(UTF_8).length}）。
     * 未配对的代理按 JDK 编码器的替换字符 {@code ?} 计 1 字节；proto3 解析出的 string 不会含它。
     */
    public static int utf8Length(String s) {
        if (s == null) {
            return 0;
        }
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp < 0x80) {
                bytes += 1;
            } else if (cp < 0x800) {
                bytes += 2;
            } else if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                bytes += 1;
            } else if (cp < 0x10000) {
                bytes += 3;
            } else {
                bytes += 4;
            }
            i += Character.charCount(cp);
        }
        return bytes;
    }
}
