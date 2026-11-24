package com.game.contentguard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.game.contentguard.engine.ContentGuardEngine;
import com.game.contentguard.engine.ContentGuardEngine.CheckResult;
import com.game.contentguard.engine.ContentGuardEngine.SceneClass;
import com.game.contentguard.engine.ContentGuardEngine.Verdict;
import com.game.contentguard.wordlist.Lexicon;
import com.game.contentguard.wordlist.WordCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ContactDetectorTest {

    @Test
    void detectsUrlsPhonesAndObfuscatedContactCues() {
        ContentGuardEngine engine = new ContentGuardEngine();
        engine.swap(1L, Lexicon.empty());

        for (String ad : List.of(
                "来看 https://example.com/abc",
                "加站 abc.cn 领福利",
                "联系我 138-1234-5678",
                "加v abc12345",
                "扣扣 一二三四五六七八",
                "加微 幺三八零零一三八零零零",
                "支付宝转账 1234567",
                "咸鱼搜 xy_998877")) {
            CheckResult res = engine.check(SceneClass.CHAT, ad);
            assertEquals(Verdict.REJECT, res.verdict(), "should reject ad: " + ad);
            assertEquals(WordCategory.AD, res.category(), "category should be AD: " + ad);
        }
    }

    @Test
    void skipsContactHeuristicsInPrivateLabelAndHonorsAllowSpans() {
        ContentGuardEngine engine = new ContentGuardEngine();
        engine.swap(1L, new Lexicon(List.of(), List.of("13812345678", "example.com")));

        assertEquals(Verdict.PASS, engine.check(SceneClass.PRIVATE_LABEL, "加v abc12345").verdict());
        assertEquals(Verdict.PASS, engine.check(SceneClass.CHAT, "客服号码 13812345678").verdict());
        assertEquals(Verdict.PASS, engine.check(SceneClass.CHAT, "官站 example.com").verdict());
        // 正常聊天与英文短句不误报
        assertEquals(Verdict.PASS, engine.check(SceneClass.CHAT, "今天晚上八点打团本，缺个奶妈").verdict());
        assertEquals(Verdict.PASS, engine.check(SceneClass.CHAT, "version 1.2 ready").verdict());
    }
}
