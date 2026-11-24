package com.game.contentguard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.contentguard.platform.DouyinAdapter;
import com.game.contentguard.platform.PlatformAdapter.Outcome;
import com.game.contentguard.platform.PlatformAdapter.ReviewResult;
import com.game.contentguard.platform.PlatformReviewer;
import com.game.contentguard.platform.WeChatAdapter;
import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PlatformReviewerTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final AtomicInteger wxTokenCalls = new AtomicInteger();
    private final AtomicInteger wxCheckCalls = new AtomicInteger();
    private final AtomicInteger dyTokenCalls = new AtomicInteger();
    private final AtomicInteger dyCheckCalls = new AtomicInteger();
    private volatile boolean wxExpireFirstCheck;
    private volatile boolean wxReject;
    private volatile boolean wxSlow;
    private volatile boolean dyExpireFirstCheck;
    private volatile boolean dyHit;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/token", ex -> {
            int n = wxTokenCalls.incrementAndGet();
            respondJson(ex, 200, "{\"access_token\":\"wx_tok_" + n + "\",\"expires_in\":7200}");
        });
        server.createContext("/wxa/msg_sec_check", ex -> {
            int n = wxCheckCalls.incrementAndGet();
            if (wxSlow) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (wxExpireFirstCheck && n == 1) {
                respondJson(ex, 200, "{\"errcode\":40001,\"errmsg\":\"invalid credential\"}");
                return;
            }
            if (wxReject) {
                respondJson(ex, 200, "{\"errcode\":0,\"result\":{\"suggest\":\"risky\",\"label\":20001}}");
                return;
            }
            respondJson(ex, 200, "{\"errcode\":0,\"result\":{\"suggest\":\"pass\",\"label\":100}}");
        });
        server.createContext("/api/apps/v2/token", ex -> {
            int n = dyTokenCalls.incrementAndGet();
            respondJson(ex, 200, "{\"err_no\":0,\"data\":{\"access_token\":\"dy_tok_" + n + "\",\"expires_in\":7200}}");
        });
        server.createContext("/api/v2/tags/text/antidirt", ex -> {
            int n = dyCheckCalls.incrementAndGet();
            if (dyExpireFirstCheck && n == 1) {
                respondJson(ex, 401, "{\"code\":401}");
                return;
            }
            if (dyHit) {
                respondJson(ex, 200, "{\"code\":0,\"data\":[{\"code\":0,\"predicts\":[{\"hit\":true,\"prob\":1.0}]}]}");
                return;
            }
            respondJson(ex, 200, "{\"code\":0,\"data\":[{\"code\":0,\"predicts\":[{\"hit\":false,\"prob\":0.01}]}]}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void wechatCachesTokenRetriesOn40001AndMapsRejectLabel() {
        WeChatAdapter wx = new WeChatAdapter("wxApp", "wxSec", baseUrl, http, json, Clock.systemUTC());
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(wx), null, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofSeconds(2), Clock.systemUTC());

        wxExpireFirstCheck = true;
        ReviewResult pass = reviewer.review(TextScene.TEXT_SCENE_ROLE_NAME, 1L, "wx_openid1", "逍遥子", false);
        assertEquals(Outcome.PASS, pass.outcome());
        assertEquals(2, wxTokenCalls.get(), "should refresh token once after 40001");
        assertEquals(2, wxCheckCalls.get());

        wxExpireFirstCheck = false;
        wxReject = true;
        ReviewResult rej = reviewer.review(TextScene.TEXT_SCENE_ROLE_NAME, 1L, "wx_openid1", "违规名", false);
        assertEquals(Outcome.REJECT, rej.outcome());
        assertEquals(TextCategory.TEXT_CATEGORY_POLITICS, rej.category());
        assertEquals(2, wxTokenCalls.get(), "cached token should be reused");
    }

    @Test
    void wechatWorldChatTimesOutToPendingWhenAsyncEnabled() {
        WeChatAdapter wx = new WeChatAdapter("wxApp", "wxSec", baseUrl, http, json, Clock.systemUTC());
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(wx), null, Duration.ofSeconds(2), Duration.ofMillis(80), Duration.ofSeconds(2), Clock.systemUTC());

        wxSlow = true;
        ReviewResult pending = reviewer.review(TextScene.TEXT_SCENE_CHAT_WORLD, 2L, "wx_openid2", "你好", true);
        assertEquals(Outcome.PENDING, pending.outcome());

        // 私聊、队聊、私有标签跳过外部审核
        ReviewResult skipped = reviewer.review(TextScene.TEXT_SCENE_CHAT_PRIVATE, 2L, "wx_openid2", "你好", false);
        assertEquals(Outcome.SKIP, skipped.outcome());
    }

    @Test
    void douyinRetriesOnHttp401AndDetectsHit() {
        DouyinAdapter dy = new DouyinAdapter("dyApp", "dySec", baseUrl, http, json, Clock.systemUTC());
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(dy), null, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(2), Clock.systemUTC());

        dyExpireFirstCheck = true;
        dyHit = true;
        ReviewResult rej = reviewer.review(TextScene.TEXT_SCENE_GUILD_NAME, 3L, "dy_openid3", "帮会名", false);
        assertEquals(Outcome.REJECT, rej.outcome());
        assertEquals(TextCategory.TEXT_CATEGORY_OTHER, rej.category());
        assertEquals(2, dyTokenCalls.get());
        assertEquals(2, dyCheckCalls.get());
    }

    private static void respondJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
