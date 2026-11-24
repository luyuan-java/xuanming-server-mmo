package com.game.contentguard.contact;

import com.game.contentguard.textnorm.TextNorm;
import com.game.contentguard.textnorm.TextNorm.FoldedText;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变体联系方式与外部引流广告检测器（对齐 Go {@code content_guard/internal/contact}）。
 * 覆盖：URL 协议/域名、裸 11 位大陆手机号、24 个微信/QQ/交易线索词 + 汉字数字/谐音折算 + 白名单区间豁免。
 */
public final class ContactDetector {

    @FunctionalInterface
    public interface AllowCheck {
        boolean isAllowed(int startOrig, int endOrig);
    }

    private static final int LOOKAHEAD = 28;

    private static final Pattern URL_SCHEME = Pattern.compile(
            "(?i)(?:https?://|www\\.)[a-z0-9.-]+");
    private static final Pattern DOMAIN_SUFFIX = Pattern.compile(
            "(?i)(?<![a-z0-9])[a-z0-9-]{2,}\\.(?:com|cn|net|org|xyz|top|vip|cc|tv|me|gg|io|club|shop|work|site)(?![a-z0-9])");

    private static final List<int[]> CHANNEL_CUES = List.of(
            "weixin", "weix", "微x", "wei信", "微信", "威信", "徽信", "v信",
            "加微", "加wx", "加vx", "加v", "wx", "vx",
            "qq群", "q群", "扣扣", "企鹅", "加q", "qq",
            "支付宝", "zfb", "闲鱼", "咸鱼")
            .stream().map(s -> s.codePoints().toArray()).toList();

    private static final Map<Integer, Character> CN_DIGIT_MAP = Map.ofEntries(
            Map.entry((int) '〇', '0'), Map.entry((int) '零', '0'), Map.entry((int) '洞', '0'),
            Map.entry((int) '一', '1'), Map.entry((int) '壹', '1'), Map.entry((int) '幺', '1'), Map.entry((int) '妖', '1'),
            Map.entry((int) '二', '2'), Map.entry((int) '贰', '2'), Map.entry((int) '两', '2'),
            Map.entry((int) '三', '3'), Map.entry((int) '叁', '3'),
            Map.entry((int) '四', '4'), Map.entry((int) '肆', '4'),
            Map.entry((int) '五', '5'), Map.entry((int) '伍', '5'),
            Map.entry((int) '六', '6'), Map.entry((int) '陆', '6'),
            Map.entry((int) '七', '7'), Map.entry((int) '柒', '7'), Map.entry((int) '拐', '7'),
            Map.entry((int) '八', '8'), Map.entry((int) '捌', '8'),
            Map.entry((int) '九', '9'), Map.entry((int) '玖', '9'));

    private ContactDetector() {
    }

    public static boolean containsAd(String text, FoldedText elems, AllowCheck allowCheck) {
        if (text == null || text.isEmpty() || elems.length() == 0) {
            return false;
        }
        if (hasUnallowedRegexMatch(URL_SCHEME, text, allowCheck)) {
            return true;
        }
        if (hasUnallowedRegexMatch(DOMAIN_SUFFIX, text, allowCheck)) {
            return true;
        }

        int denseCount = 0;
        for (int i = 0; i < elems.length(); i++) {
            if (!elems.skipAt(i)) {
                denseCount++;
            }
        }
        if (denseCount == 0) {
            return false;
        }
        int[] denseCp = new int[denseCount];
        int[] denseOrig = new int[denseCount];
        int k = 0;
        for (int i = 0; i < elems.length(); i++) {
            if (!elems.skipAt(i)) {
                denseCp[k] = elems.codePointAt(i);
                denseOrig[k] = elems.origAt(i);
                k++;
            }
        }
        return hasBarePhoneNumber(denseCp, denseOrig, allowCheck)
                || hasCueWithContact(denseCp, denseOrig, allowCheck);
    }

    private static boolean hasUnallowedRegexMatch(Pattern pattern, String text, AllowCheck allowCheck) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (allowCheck == null) {
                return true;
            }
            int startOrig = text.codePointCount(0, m.start());
            int endOrig = text.codePointCount(0, m.end()) - 1;
            if (!allowCheck.isAllowed(startOrig, endOrig)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBarePhoneNumber(int[] denseCp, int[] denseOrig, AllowCheck allowCheck) {
        int run = 0;
        for (int i = 0; i < denseCp.length; i++) {
            int cp = denseCp[i];
            if (cp >= '0' && cp <= '9') {
                run++;
                if (run == 11) {
                    int start = i - 10;
                    if (denseCp[start] == '1' && denseCp[start + 1] >= '3' && denseCp[start + 1] <= '9') {
                        boolean nextIsDigit = i + 1 < denseCp.length && denseCp[i + 1] >= '0' && denseCp[i + 1] <= '9';
                        if (!nextIsDigit && (allowCheck == null || !allowCheck.isAllowed(denseOrig[start], denseOrig[i]))) {
                            return true;
                        }
                    }
                }
            } else {
                run = 0;
            }
        }
        return false;
    }

    private static boolean hasCueWithContact(int[] denseCp, int[] denseOrig, AllowCheck allowCheck) {
        for (int i = 0; i < denseCp.length; i++) {
            int cueLen = 0;
            for (int[] cue : CHANNEL_CUES) {
                if (matchDenseCue(denseCp, i, cue)) {
                    cueLen = cue.length;
                    break;
                }
            }
            if (cueLen == 0) {
                continue;
            }
            if (i > 0 && denseCp[i] < 128 && Character.isLetter(denseCp[i]) && TextNorm.isAsciiAlnum(denseCp[i - 1])) {
                continue;
            }
            int digits = 0;
            int handleChars = 0;
            int end = Math.min(denseCp.length, i + cueLen + LOOKAHEAD);
            for (int j = i + cueLen; j < end; j++) {
                int cp = denseCp[j];
                if (cp >= '0' && cp <= '9') {
                    digits++;
                    handleChars++;
                } else if (CN_DIGIT_MAP.containsKey(cp)) {
                    digits++;
                } else if ((cp >= 'a' && cp <= 'z') || cp == '_' || cp == '-') {
                    handleChars++;
                    digits = 0;
                } else {
                    if (digits >= 5) {
                        break;
                    }
                    digits = 0;
                    handleChars = 0;
                }
                if (digits >= 5 || (handleChars >= 6 && hasDigitInWindow(denseCp, i + cueLen, j))) {
                    if (allowCheck == null || !allowCheck.isAllowed(denseOrig[i], denseOrig[j])) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    private static boolean hasDigitInWindow(int[] denseCp, int from, int to) {
        for (int k = from; k <= to && k < denseCp.length; k++) {
            if (denseCp[k] >= '0' && denseCp[k] <= '9') {
                return true;
            }
        }
        return false;
    }

    private static boolean matchDenseCue(int[] denseCp, int start, int[] cue) {
        if (start + cue.length > denseCp.length) {
            return false;
        }
        for (int k = 0; k < cue.length; k++) {
            if (denseCp[start + k] != cue[k]) {
                return false;
            }
        }
        return true;
    }
}
