package com.game.contentguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.contentguard.engine.ContentGuardEngine;
import com.game.contentguard.engine.ContentGuardEngine.CheckResult;
import com.game.contentguard.engine.ContentGuardEngine.SceneClass;
import com.game.contentguard.engine.ContentGuardEngine.Verdict;
import com.game.contentguard.wordlist.Lexicon;
import com.game.contentguard.wordlist.MatchMode;
import com.game.contentguard.wordlist.WordCategory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 双端共享行为测试向量（对应 Go {@code go/content_guard/testdata/vectors.json}）：20 条用例必须 100% 一致。
 */
final class ContentGuardVectorsTest {

    @Test
    void allSharedVectorsMatchGoImplementation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("content-guard/vectors.json")) {
            assertNotNull(in, "vectors.json missing on test classpath");
            root = mapper.readTree(in);
        }

        List<Lexicon.Entry> deny = new ArrayList<>();
        for (JsonNode d : root.path("deny")) {
            WordCategory cat = WordCategory.parse(d.path("category").asText());
            MatchMode mode = MatchMode.parse(d.path("mode").asText());
            assertNotNull(cat, "unknown category in vector: " + d);
            assertNotNull(mode, "unknown mode in vector: " + d);
            deny.add(new Lexicon.Entry(d.path("word").asText(), cat, mode));
        }
        List<String> allow = new ArrayList<>();
        for (JsonNode a : root.path("allow")) {
            allow.add(a.asText());
        }

        ContentGuardEngine engine = new ContentGuardEngine();
        engine.swap(1L, new Lexicon(deny, allow));

        JsonNode cases = root.path("cases");
        assertEquals(20, cases.size(), "expected all 20 shared vectors");

        for (JsonNode tc : cases) {
            String name = tc.path("name").asText();
            SceneClass scene = switch (tc.path("scene").asText()) {
                case "name" -> SceneClass.NAME;
                case "notice" -> SceneClass.NOTICE;
                case "chat" -> SceneClass.CHAT;
                default -> throw new IllegalArgumentException("unknown scene: " + tc.path("scene").asText());
            };
            String text = tc.path("text").asText();
            Verdict expectedVerdict = switch (tc.path("verdict").asText()) {
                case "pass" -> Verdict.PASS;
                case "masked" -> Verdict.MASKED;
                case "reject" -> Verdict.REJECT;
                default -> throw new IllegalArgumentException("unknown verdict: " + tc.path("verdict").asText());
            };
            String expectedMasked = tc.path("masked").asText("");
            String expectedCategory = tc.path("category").asText();

            CheckResult got = engine.check(scene, text);
            assertEquals(expectedVerdict, got.verdict(), () -> "verdict mismatch on vector: " + name);
            assertEquals(expectedMasked, got.maskedText(), () -> "masked mismatch on vector: " + name);
            assertEquals(expectedCategory, got.category().label(), () -> "category mismatch on vector: " + name);
            assertEquals(1L, got.lexiconVersion(), () -> "lexicon version mismatch on vector: " + name);
        }
    }
}
