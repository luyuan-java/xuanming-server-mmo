package com.game.contentguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.game.contentguard.engine.ContentGuardEngine;
import com.game.contentguard.platform.PlatformAdapter;
import com.game.contentguard.platform.PlatformReviewer;
import com.game.contentguard.service.ContentGuardRpcService;
import com.game.contentguard.wordlist.Lexicon;
import com.game.contentguard.wordlist.MatchMode;
import com.game.contentguard.wordlist.WordCategory;
import com.game.contract.generated.ContractMessageIds;
import com.game.contract.generated.service.ContentGuardContractDispatcher;
import com.game.proto.content_guard.CheckTextRequest;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

final class ContentGuardRpcServiceTest {

    @Test
    void everyContractTextSceneIsHandled() {
        ContentGuardEngine engine = new ContentGuardEngine();
        engine.swap(1L, Lexicon.empty());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ContentGuardRpcService svc = new ContentGuardRpcService(engine, null, true, 4096, meters);

        for (TextScene scene : TextScene.values()) {
            if (scene == TextScene.TEXT_SCENE_UNSPECIFIED || scene == TextScene.UNRECOGNIZED) {
                continue;
            }
            CheckTextResponse resp = svc.checkText(CheckTextRequest.newBuilder()
                    .setScene(scene)
                    .setText("正常内容")
                    .build());
            assertEquals(TextVerdict.TEXT_VERDICT_PASS, resp.getVerdict(), "unhandled scene: " + scene);
        }
    }

    @Test
    void enforcesRequireLexiconMaxBytesAndContractDispatcherAndLowCardinalityMetrics() throws Exception {
        ContentGuardEngine engine = new ContentGuardEngine();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ContentGuardRpcService svc = new ContentGuardRpcService(engine, null, true, 16, meters);

        // requireLexicon=true 且词库未加载（version=0）时 fail-closed + Health DOWN
        var indicator = new ContentGuardApplication().contentGuardHealthIndicator(svc, engine);
        assertFalse(svc.isReady());
        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertThrows(IllegalStateException.class, () -> svc.checkText(CheckTextRequest.newBuilder()
                .setScene(TextScene.TEXT_SCENE_CHAT_WORLD)
                .setText("你好")
                .build()));

        engine.swap(10L, new Lexicon(
                List.of(new Lexicon.Entry("笨蛋", WordCategory.ABUSE, MatchMode.SUB)),
                List.of()));
        assertTrue(svc.isReady());
        assertEquals(Status.UP, indicator.health().getStatus());

        // 超过 maxTextBytes 拒绝
        assertThrows(IllegalArgumentException.class, () -> svc.checkText(CheckTextRequest.newBuilder()
                .setScene(TextScene.TEXT_SCENE_CHAT_WORLD)
                .setText("一二三四五六") // 18 bytes > 16
                .build()));

        // 通过编译期生成的零反射分发器调用 CheckText
        String secretPlayerText = "你个笨蛋";
        var rawReq = CheckTextRequest.newBuilder()
                .setScene(TextScene.TEXT_SCENE_CHAT_WORLD)
                .setPlayerId(987654321L)
                .setText(secretPlayerText)
                .build()
                .toByteString();
        var dispatched = ContentGuardContractDispatcher.dispatch(
                ContractMessageIds.CONTENT_GUARD_CHECK_TEXT, null, rawReq, svc);
        assertNotNull(dispatched);
        CheckTextResponse parsed = (CheckTextResponse) dispatched;
        assertEquals(TextVerdict.TEXT_VERDICT_MASKED, parsed.getVerdict());
        assertEquals("你个**", parsed.getMaskedText());
        assertEquals(TextCategory.TEXT_CATEGORY_ABUSE, parsed.getCategory());
        assertEquals(10L, parsed.getLexiconVersion());

        // 指标 tag 绝不能包含被检文字或 player_id
        for (Meter m : meters.getMeters()) {
            m.getId().getTags().forEach(t -> {
                assertFalse(t.getKey().contains("player"), "high-cardinality tag forbidden: " + t);
                assertFalse(t.getValue().contains(secretPlayerText), "player text leaked to metric: " + t);
            });
        }
    }

    @Test
    void checkPlatformTextRunsFullSyncPlatformBudget() {
        ContentGuardEngine engine = new ContentGuardEngine();
        engine.swap(1L, Lexicon.empty());
        PlatformAdapter rejectAll = new PlatformAdapter() {
            @Override
            public String provider() {
                return PROVIDER_WECHAT;
            }

            @Override
            public ReviewResult check(TextScene scene, String openId, String text, Duration timeout) {
                return ReviewResult.reject(TextCategory.TEXT_CATEGORY_AD, PROVIDER_WECHAT);
            }
        };
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(rejectAll), null, Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), Clock.systemUTC());
        ContentGuardRpcService svc = new ContentGuardRpcService(engine, reviewer, true, 4096, new SimpleMeterRegistry());

        CheckTextResponse resp = svc.checkPlatformText(CheckTextRequest.newBuilder()
                .setScene(TextScene.TEXT_SCENE_CHAT_WORLD)
                .setAccount("wx_open_123")
                .setText("正常字面但平台拒")
                .build());
        assertEquals(TextVerdict.TEXT_VERDICT_REJECT, resp.getVerdict());
        assertEquals(TextCategory.TEXT_CATEGORY_AD, resp.getCategory());
    }
}
