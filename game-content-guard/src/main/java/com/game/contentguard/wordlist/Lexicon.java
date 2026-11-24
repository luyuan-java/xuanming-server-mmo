package com.game.contentguard.wordlist;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 词库文本格式（{@code #content_guard_lexicon 1}）与不可变数据结构（对齐 Go {@code wordlist.Lexicon}）。
 */
public record Lexicon(List<Entry> deny, List<String> allow) {

    public static final int MAX_WORD_CODE_POINTS = 64;
    public static final int MAX_ENTRIES = 1_000_000;
    public static final String FORMAT_HEADER = "#content_guard_lexicon 1";

    public record Entry(String word, WordCategory category, MatchMode mode) {
        public Entry {
            Objects.requireNonNull(word, "word");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(mode, "mode");
        }
    }

    public Lexicon {
        deny = List.copyOf(deny);
        allow = List.copyOf(allow);
    }

    public static Lexicon empty() {
        return new Lexicon(List.of(), List.of());
    }

    public static void validateWord(String word) {
        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("词条为空");
        }
        if (word.indexOf('\t') >= 0 || word.indexOf('\r') >= 0 || word.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("词条不能含制表符或换行");
        }
        int cpCount = word.codePointCount(0, word.length());
        if (cpCount > MAX_WORD_CODE_POINTS) {
            throw new IllegalArgumentException("词条超过字符上限: " + cpCount + " > " + MAX_WORD_CODE_POINTS);
        }
    }

    public static void validateEntry(Entry entry) {
        validateWord(entry.word());
        if (entry.category() == WordCategory.UNSPECIFIED) {
            throw new IllegalArgumentException("词条类别不能为空");
        }
    }

    /** 序列化为权威文本格式；任何一条不合法整体失败。 */
    public byte[] marshal() {
        if (deny.size() + allow.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("词库条数超限: " + (deny.size() + allow.size()));
        }
        StringBuilder sb = new StringBuilder(Math.max(64, (deny.size() + allow.size()) * 16));
        sb.append(FORMAT_HEADER).append('\n');
        for (Entry e : deny) {
            validateEntry(e);
            sb.append("D\t").append(e.category().label()).append('\t')
                    .append(e.mode().wireName()).append('\t').append(e.word()).append('\n');
        }
        for (String w : allow) {
            validateWord(w);
            sb.append("A\t").append(w).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 解析权威文本格式；任意行非法整体抛 {@link IllegalArgumentException}（fail-closed）。 */
    public static Lexicon parse(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("词库不是合法 UTF-8", e);
        }
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !stripTrailingCr(lines[0]).equals(FORMAT_HEADER)) {
            throw new IllegalArgumentException("词库首行必须为 " + FORMAT_HEADER);
        }
        List<Entry> deny = new ArrayList<>();
        List<String> allow = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = stripTrailingCr(lines[i]);
            if (line.isEmpty()) {
                continue;
            }
            int lineNo = i + 1;
            String[] fields = line.split("\t", -1);
            switch (fields[0]) {
                case "D" -> {
                    if (fields.length != 4) {
                        throw new IllegalArgumentException("第 " + lineNo + " 行 D 记录须有 4 列");
                    }
                    WordCategory category = WordCategory.parse(fields[1]);
                    if (category == null) {
                        throw new IllegalArgumentException("第 " + lineNo + " 行未知类别: " + fields[1]);
                    }
                    MatchMode mode = MatchMode.parse(fields[2]);
                    if (mode == null) {
                        throw new IllegalArgumentException("第 " + lineNo + " 行未知匹配模式: " + fields[2]);
                    }
                    Entry entry = new Entry(fields[3], category, mode);
                    validateEntry(entry);
                    deny.add(entry);
                }
                case "A" -> {
                    if (fields.length != 2) {
                        throw new IllegalArgumentException("第 " + lineNo + " 行 A 记录须有 2 列");
                    }
                    validateWord(fields[1]);
                    allow.add(fields[1]);
                }
                default -> throw new IllegalArgumentException("第 " + lineNo + " 行未知记录类型: " + fields[0]);
            }
            if (deny.size() + allow.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("词库条数超过上限 " + MAX_ENTRIES);
            }
        }
        return new Lexicon(deny, allow);
    }

    private static String stripTrailingCr(String s) {
        return !s.isEmpty() && s.charAt(s.length() - 1) == '\r' ? s.substring(0, s.length() - 1) : s;
    }
}
