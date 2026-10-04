package com.game.guild.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 公告长度按未 trim 的 UTF-8 字节数（基线 guild_logic.go:524-526、client_zone_test.go:153-161；guild-spec §2.5、§9.3）。 */
class GuildAnnouncementsTest {

    @Test
    void 边界_600字节合法_601字节超长_空串合法() {
        assertThat(GuildAnnouncements.tooLong("a".repeat(600))).isFalse();
        assertThat(GuildAnnouncements.tooLong("a".repeat(601))).isTrue();
        assertThat(GuildAnnouncements.tooLong("")).as("空串 = 清空公告").isFalse();
        assertThat(GuildAnnouncements.tooLong(null)).isFalse();
        // 每个汉字 3 字节：200 个恰好 600，201 个超长（基线用例 MaxAnnouncementBytes/3+1）
        assertThat(GuildAnnouncements.tooLong("汉".repeat(200))).isFalse();
        assertThat(GuildAnnouncements.tooLong("汉".repeat(GuildLimits.MAX_ANNOUNCEMENT_BYTES / 3 + 1))).isTrue();
        assertThat(GuildTip.ANNOUNCEMENT_TOO_LONG.code()).isEqualTo(GuildTips.ANNOUNCEMENT_TOO_LONG);
    }

    @Test
    void 不trim_首尾空白也算字节() {
        assertThat(GuildAnnouncements.tooLong(" ".repeat(601))).isTrue();
        assertThat(GuildAnnouncements.tooLong("a".repeat(599) + "　")).as("U+3000 占 3 字节").isTrue();
    }

    @Test
    void 字节数与JDK编码器一致() {
        String[] samples = {"", "abc", "é", "汉字", "😀", "a\u0000b", "混合 mixed ✓ 𝐀", "\u0085 　"};
        for (String s : samples) {
            assertThat(GuildAnnouncements.utf8Length(s)).as(s).isEqualTo(s.getBytes(StandardCharsets.UTF_8).length);
        }
        // 未配对的代理：JDK 编码成 '?'（1 字节）
        assertThat(GuildAnnouncements.utf8Length("\uD800")).isEqualTo("\uD800".getBytes(StandardCharsets.UTF_8).length);
        assertThat(GuildAnnouncements.utf8Length("😀".repeat(150))).isEqualTo(600);
        assertThat(GuildAnnouncements.tooLong("😀".repeat(150) + "a")).isTrue();
    }
}
