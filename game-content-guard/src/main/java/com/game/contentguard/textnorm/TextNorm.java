package com.game.contentguard.textnorm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 文本归一化折叠（对齐 Go {@code content_guard/internal/textnorm}）：
 * <ol>
 *   <li>NFKD（全角→半角、带圈数字/上下标→数字、兼容字形→正字、音标字母拆为基字+组合记号）；</li>
 *   <li>转小写；</li>
 *   <li>繁体→简体（内嵌 {@code content-guard/t2s.txt} 2,481 字对）；</li>
 *   <li>标注可跳过字符（空白、标点、符号、控制/格式符、组合记号、私用区）。</li>
 * </ol>
 *
 * <p>严格按 Unicode 码点（{@code int}）下标追踪原文，避免 UTF-16 代理对（Emoji / 增补汉字）在打码时偏移。
 */
public final class TextNorm {

    private static final String T2S_RESOURCE = "content-guard/t2s.txt";
    private static final Map<Integer, Integer> T2S = loadT2S();

    private TextNorm() {
    }

    /** 折叠结果：原始码点数组 + 折叠后的并行原始数组（零装箱，GC 友好）。 */
    public static final class FoldedText {
        private final int[] codePoints;
        private final int[] origIndex;
        private final boolean[] skip;
        private final int length;
        private final int[] origCodePoints;

        FoldedText(int[] codePoints, int[] origIndex, boolean[] skip, int length, int[] origCodePoints) {
            this.codePoints = codePoints;
            this.origIndex = origIndex;
            this.skip = skip;
            this.length = length;
            this.origCodePoints = origCodePoints;
        }

        public int length() { return length; }
        public int codePointAt(int i) { return codePoints[i]; }
        public int origAt(int i) { return origIndex[i]; }
        public boolean skipAt(int i) { return skip[i]; }
        public int origLength() { return origCodePoints.length; }
        public int origCodePointAt(int i) { return origCodePoints[i]; }
        public int[] copyOrigCodePoints() { return Arrays.copyOf(origCodePoints, origCodePoints.length); }
    }

    public static FoldedText fold(String text) {
        if (text == null || text.isEmpty()) {
            int[] empty = new int[0];
            return new FoldedText(empty, empty, new boolean[0], 0, empty);
        }
        int[] orig = text.codePoints().toArray();
        int cap = Math.max(orig.length, 16);
        int[] cps = new int[cap];
        int[] origIdx = new int[cap];
        boolean[] skips = new boolean[cap];
        int len = 0;

        for (int i = 0; i < orig.length; i++) {
            int cp = orig[i];
            if (cp < 0x80) {
                int lower = (cp >= 'A' && cp <= 'Z') ? (cp + ('a' - 'A')) : cp;
                if (len == cps.length) {
                    int nextCap = cps.length * 2;
                    cps = Arrays.copyOf(cps, nextCap);
                    origIdx = Arrays.copyOf(origIdx, nextCap);
                    skips = Arrays.copyOf(skips, nextCap);
                }
                cps[len] = lower;
                origIdx[len] = i;
                skips[len] = isSkippable(lower);
                len++;
                continue;
            }
            String decomposed = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKD);
            for (int off = 0; off < decomposed.length(); ) {
                int folded = decomposed.codePointAt(off);
                off += Character.charCount(folded);
                folded = Character.toLowerCase(folded);
                Integer simplified = T2S.get(folded);
                if (simplified != null) {
                    folded = simplified;
                }
                if (len == cps.length) {
                    int nextCap = cps.length * 2;
                    cps = Arrays.copyOf(cps, nextCap);
                    origIdx = Arrays.copyOf(origIdx, nextCap);
                    skips = Arrays.copyOf(skips, nextCap);
                }
                cps[len] = folded;
                origIdx[len] = i;
                skips[len] = isSkippable(folded);
                len++;
            }
        }
        return new FoldedText(cps, origIdx, skips, len, orig);
    }

    /** 折叠词库词条并剔除可跳过字符；全符号词返回空数组。 */
    public static int[] foldWord(String word) {
        FoldedText folded = fold(word);
        int[] buf = new int[folded.length()];
        int count = 0;
        for (int i = 0; i < folded.length(); i++) {
            if (!folded.skipAt(i)) {
                buf[count++] = folded.codePointAt(i);
            }
        }
        return Arrays.copyOf(buf, count);
    }

    public static boolean isSkippable(int cp) {
        if (cp < 0x80) {
            return !isAsciiAlnum(cp);
        }
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85 || cp == 0xA0) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION,
                    Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION,
                    Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION,
                    Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION,
                    Character.MATH_SYMBOL,
                    Character.CURRENCY_SYMBOL,
                    Character.MODIFIER_SYMBOL,
                    Character.OTHER_SYMBOL,
                    Character.CONTROL,
                    Character.FORMAT,
                    Character.NON_SPACING_MARK,
                    Character.ENCLOSING_MARK,
                    Character.PRIVATE_USE -> true;
            default -> false;
        };
    }

    public static boolean isAsciiAlnum(int cp) {
        return (cp >= '0' && cp <= '9') || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z');
    }

    /** 组合记号（Unicode Mn / Me）：打码时随紧邻前字符一并替换，不留在 * 上。 */
    public static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }

    private static Map<Integer, Integer> loadT2S() {
        InputStream in = TextNorm.class.getClassLoader().getResourceAsStream(T2S_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("缺少内嵌繁简字表资源: " + T2S_RESOURCE);
        }
        Map<Integer, Integer> table = new HashMap<>(4096);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int first = line.codePointAt(0);
                int firstLen = Character.charCount(first);
                if (firstLen >= line.length()) {
                    throw new IllegalStateException("t2s.txt 格式错误: " + line);
                }
                int second = line.codePointAt(firstLen);
                int secondLen = Character.charCount(second);
                if (firstLen + secondLen != line.length() || first == second) {
                    throw new IllegalStateException("t2s.txt 格式错误: " + line);
                }
                table.put(first, second);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 t2s.txt 失败", e);
        }
        return Map.copyOf(table);
    }
}
