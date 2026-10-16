package com.game.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SensitiveWordFilterTest {

    @Test
    void 按原源字面匹配_大小写敏感_中文与补充平面字符各变一星() {
        SensitiveWordFilter filter = new SensitiveWordFilter(List.of("广告", "Spam", "😀", "a.b", ""));
        String original = "这是广告 Spam spam SPAM 😀 a.b axb";
        String masked = filter.mask(original);
        assertThat(masked).isEqualTo("这是** **** spam SPAM * *** axb");
        assertThat(masked.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(original.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void 保留配置顺序与空表语义_构造后不跟随外部列表修改() {
        assertThat(new SensitiveWordFilter(List.of("ab", "abc")).mask("abc")).isEqualTo("**c");
        assertThat(new SensitiveWordFilter(List.of("abc", "ab")).mask("abc")).isEqualTo("***");
        assertThat(new SensitiveWordFilter(null).mask("广告")).isEqualTo("广告");
        List<String> words = new ArrayList<>(List.of("广告"));
        SensitiveWordFilter filter = new SensitiveWordFilter(words);
        words.set(0, "正常");
        assertThat(filter.mask("正常广告")).isEqualTo("正常**");
    }

    @Test
    void 配置边界按UTF8字节_超限与非法Unicode启动时拒绝() {
        new SensitiveWordFilter(Collections.nCopies(SensitiveWordFilter.MAX_WORDS, ""));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SensitiveWordFilter(Collections.nCopies(SensitiveWordFilter.MAX_WORDS + 1, "")));
        new SensitiveWordFilter(List.of("x".repeat(SensitiveWordFilter.MAX_WORD_BYTES)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new SensitiveWordFilter(List.of("x".repeat(SensitiveWordFilter.MAX_WORD_BYTES + 1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new SensitiveWordFilter(List.of("广".repeat(86))));
        List<String> words = new ArrayList<>(Collections.nCopies(
                SensitiveWordFilter.MAX_TOTAL_BYTES / SensitiveWordFilter.MAX_WORD_BYTES,
                "x".repeat(SensitiveWordFilter.MAX_WORD_BYTES)));
        new SensitiveWordFilter(words);
        words.add("x");
        assertThatIllegalArgumentException().isThrownBy(() -> new SensitiveWordFilter(words));
        assertThatIllegalArgumentException().isThrownBy(() -> new SensitiveWordFilter(List.of("\uD800")));
    }
}
