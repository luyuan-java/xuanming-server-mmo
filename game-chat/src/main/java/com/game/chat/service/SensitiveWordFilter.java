package com.game.chat.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 聊天启动时的不可变词表快照。沿用原 Go 聊天业务的字面子串、大小写敏感和配置顺序；
 * 一个 Unicode 码点替换为一个 *，过滤后的 UTF-8 字节数不会扩大。
 */
public final class SensitiveWordFilter {

    public static final int MAX_WORDS = 1024;
    public static final int MAX_WORD_BYTES = 256;
    public static final int MAX_TOTAL_BYTES = 64 * 1024;

    private record Replacement(String word, String mask) { }

    private final List<Replacement> replacements;

    /** 词表的条数、单词字节数与总字节数有界；空词跳过，空表保持原文。 */
    public SensitiveWordFilter(List<String> words) {
        List<String> configured = words == null ? List.of() : words;
        if (configured.size() > MAX_WORDS) {
            throw new IllegalArgumentException("game.chat.sensitive-words 最多 " + MAX_WORDS + " 条");
        }
        List<Replacement> compiled = new ArrayList<>(configured.size());
        int totalBytes = 0;
        for (int i = 0; i < configured.size(); i++) {
            String word = configured.get(i);
            if (word == null || !StandardCharsets.UTF_8.newEncoder().canEncode(word)) {
                throw new IllegalArgumentException("game.chat.sensitive-words[" + i + "] 必须是有效 Unicode 字符串");
            }
            int bytes = word.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_WORD_BYTES) {
                throw new IllegalArgumentException("game.chat.sensitive-words[" + i + "] 最多 " + MAX_WORD_BYTES + " UTF-8 字节");
            }
            totalBytes += bytes;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("game.chat.sensitive-words 总计最多 " + MAX_TOTAL_BYTES + " UTF-8 字节");
            }
            if (!word.isEmpty()) {
                compiled.add(new Replacement(word, "*".repeat(word.codePointCount(0, word.length()))));
            }
        }
        replacements = List.copyOf(compiled);
    }

    public String mask(String content) {
        for (Replacement replacement : replacements) {
            content = content.replace(replacement.word(), replacement.mask());
        }
        return content;
    }
}
