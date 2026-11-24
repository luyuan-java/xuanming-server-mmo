package com.game.contentguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.game.contentguard.engine.ContentGuardEngine;
import com.game.contentguard.engine.ContentGuardEngine.CheckResult;
import com.game.contentguard.engine.ContentGuardEngine.SceneClass;
import com.game.contentguard.engine.ContentGuardEngine.Verdict;
import com.game.contentguard.textnorm.TextNorm;
import com.game.contentguard.wordlist.Lexicon;
import com.game.contentguard.wordlist.MatchMode;
import com.game.contentguard.wordlist.WordCategory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TextNormAndLexiconTest {

    @Test
    void nfkdTraditionalAndSurrogatePairsFoldAndMaskCleanly() {
        assertEquals("发财", fromCodePoints(TextNorm.foldWord("發財")));
        assertEquals("av", fromCodePoints(TextNorm.foldWord("ＡＶ")));
        assertEquals("hello", fromCodePoints(TextNorm.foldWord("H.e-l_l\u200Bo")));

        ContentGuardEngine engine = new ContentGuardEngine();
        engine.swap(2L, new Lexicon(
                List.of(new Lexicon.Entry("笨蛋", WordCategory.ABUSE, MatchMode.SUB)),
                List.of()));
        // 含非 BMP emoji 与组合音标：原串索引按 Unicode 码点对齐，不能把代理对截成乱码
        String input = "😀笨\u0301蛋\u0301好";
        CheckResult res = engine.check(SceneClass.CHAT, input);
        assertEquals(Verdict.MASKED, res.verdict());
        assertEquals("😀****好", res.maskedText());
    }

    @Test
    void lexiconCodecRoundTripAndFailClosedValidation() {
        Lexicon src = new Lexicon(
                List.of(
                        new Lexicon.Entry("加微信", WordCategory.AD, MatchMode.SUB),
                        new Lexicon.Entry("av", WordCategory.PORN, MatchMode.TOKEN),
                        new Lexicon.Entry("gm", WordCategory.IMPERSONATION, MatchMode.PREFIX)),
                List.of("臭豆腐", "测试白名单"));
        byte[] wire = src.marshal();
        Lexicon parsed = Lexicon.parse(wire);
        assertEquals(src, parsed);

        assertThrows(IllegalArgumentException.class,
                () -> Lexicon.parse("wrong header\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> Lexicon.parse((Lexicon.FORMAT_HEADER + "\nD\tunknown\tsub\tfoo\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> Lexicon.parse((Lexicon.FORMAT_HEADER + "\nD\tad\tbadmode\tfoo\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> Lexicon.parse((Lexicon.FORMAT_HEADER + "\nX\tfoo\n").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> Lexicon.parse(new byte[] {(byte) 0xFF, (byte) 0xFE}));
    }

    @Test
    void swapRejectsVersionZeroAndReplacesAtomically() {
        ContentGuardEngine engine = new ContentGuardEngine();
        assertEquals(0L, engine.current().version());
        assertThrows(IllegalArgumentException.class, () -> engine.swap(0L, Lexicon.empty()));

        var report = engine.swap(42L, new Lexicon(
                List.of(new Lexicon.Entry("测试词", WordCategory.OTHER, MatchMode.SUB)),
                List.of("允许词")));
        assertEquals(42L, engine.current().version());
        assertTrue(report.denyWords() >= 7); // 1 条自定义 + 6 条内置防冒充词
        assertEquals(1, report.allowWords());
    }

    private static String fromCodePoints(int[] cps) {
        return new String(cps, 0, cps.length);
    }
}
