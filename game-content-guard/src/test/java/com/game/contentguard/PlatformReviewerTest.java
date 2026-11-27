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
    private volatile boolean wxRiskyErrCode;
    private volatile boolean wxInactiveErrCode;
    private volatile boolean wxSlow;
    private volatile boolean dyExpireFirstCheck;
    private volatile boolean dyHit;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/stable_token", ex -> {
            assertEquals("POST", ex.getRequestMethod());
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
            if (wxRiskyErrCode) {
                respondJson(ex, 200, "{\"errcode\":87014,\"errmsg\":\"risky content\"}");
                return;
            }
            if (wxInactiveErrCode) {
                respondJson(ex, 200, "{\"errcode\":61010,\"errmsg\":\"user not recently active\"}");
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
        PlatformReviewer.IdentityLookup lookup = pid -> new PlatformReviewer.Identity("wechat_minigame", "wx_openid1", "wx_union1");
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(wx), lookup, Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofSeconds(2), Clock.systemUTC());

        wxExpireFirstCheck = true;
        ReviewResult pass = reviewer.review(TextScene.TEXT_SCENE_ROLE_NAME, 1L, "", "逍遥子", false);
        assertEquals(Outcome.PASS, pass.outcome());
        assertEquals(2, wxTokenCalls.get(), "should refresh token once after 40001");
        assertEquals(2, wxCheckCalls.get());

        wxExpireFirstCheck = false;
        wxReject = true;
        ReviewResult rej = reviewer.review(TextScene.TEXT_SCENE_ROLE_NAME, 1L, "", "违规名", false);
        assertEquals(Outcome.REJECT, rej.outcome());
        assertEquals(TextCategory.TEXT_CATEGORY_POLITICS, rej.category());
        assertEquals(2, wxTokenCalls.get(), "cached token should be reused");
    }

    @Test
    void wechatErrCode87014RejectsWorldChatAnd61010SkipsWithoutBlocking() {
        WeChatAdapter wx = new WeChatAdapter("wxApp", "wxSec", baseUrl, http, json, Clock.systemUTC());
        PlatformReviewer.IdentityLookup lookup = pid -> new PlatformReviewer.Identity("wechat_minigame", "wx_openid_w", "wx_u");
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(wx), lookup, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(2), Clock.systemUTC());

        wxRiskyErrCode = true;
        ReviewResult risky = reviewer.review(TextScene.TEXT_SCENE_CHAT_WORLD, 11L, "", "违规世界发言", true);
        assertEquals(Outcome.REJECT, risky.outcome(), "87014 必须明确拒收，绝不可降级为 PENDING/SKIP 放行");

        wxRiskyErrCode = false;
        wxInactiveErrCode = true;
        ReviewResult inactive = reviewer.review(TextScene.TEXT_SCENE_ROLE_NAME, 11L, "", "在线三小时改名", false);
        assertEquals(Outcome.SKIP, inactive.outcome(), "61010 必须 SKIP 交由本地词库兜底，不得永久阻断");
    }

    @Test
    void wechatWorldChatTimesOutToPendingWhenAsyncEnabled() {
        WeChatAdapter wx = new WeChatAdapter("wxApp", "wxSec", baseUrl, http, json, Clock.systemUTC());
        PlatformReviewer.IdentityLookup lookup = pid -> new PlatformReviewer.Identity("wechat_minigame", "wx_openid2", "wx_u2");
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(wx), lookup, Duration.ofSeconds(2), Duration.ofMillis(80), Duration.ofSeconds(2), Clock.systemUTC());

        wxSlow = true;
        ReviewResult pending = reviewer.review(TextScene.TEXT_SCENE_CHAT_WORLD, 2L, "", "你好", true);
        assertEquals(Outcome.PENDING, pending.outcome());

        // 私聊、队聊、私有标签跳过外部审核
        ReviewResult skipped = reviewer.review(TextScene.TEXT_SCENE_CHAT_PRIVATE, 2L, "", "你好", false);
        assertEquals(Outcome.SKIP, skipped.outcome());
    }

    @Test
    void douyinRetriesOnHttp401AndDetectsHit() {
        DouyinAdapter dy = new DouyinAdapter("dyApp", "dySec", baseUrl, http, json, Clock.systemUTC());
        PlatformReviewer.IdentityLookup lookup = pid -> new PlatformReviewer.Identity("douyin_minigame", "dy_openid3", "dy_u3");
        PlatformReviewer reviewer = new PlatformReviewer(
                List.of(dy), lookup, Duration.ofSeconds(2), Duration.ofMillis(250), Duration.ofSeconds(2), Clock.systemUTC());

        dyExpireFirstCheck = true;
        dyHit = true;
        ReviewResult rej = reviewer.review(TextScene.TEXT_SCENE_GUILD_NAME, 3L, "", "帮会名", false);
        assertEquals(Outcome.REJECT, rej.outcome());
        assertEquals(TextCategory.TEXT_CATEGORY_OTHER, rej.category());
        assertEquals(2, dyTokenCalls.get());
        assertEquals(2, dyCheckCalls.get());
    }

    @Test
    void 半配平台凭据或缺渠道回查依赖必须启动期拒绝_仅认小游戏渠道且建角按账号回查() {
        var halfWx = new ContentGuardProperties(false, 4096, "",
                new ContentGuardProperties.PlatformProperties(1500, 250, 2500, 150, "wx_app", "", baseUrl, "", "", ""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ContentGuardApplication.createPlatformReviewer(halfWx, json, http, pid -> PlatformReviewer.Identity.EMPTY));

        var halfDy = new ContentGuardProperties(false, 4096, "",
                new ContentGuardProperties.PlatformProperties(1500, 250, 2500, 150, "", "", "", "", "dy_sec", baseUrl));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ContentGuardApplication.createPlatformReviewer(halfDy, json, http, pid -> PlatformReviewer.Identity.EMPTY));

        var fullWx = new ContentGuardProperties(false, 4096, "",
                new ContentGuardProperties.PlatformProperties(1500, 250, 2500, 150, "wx_app", "wx_sec", baseUrl, "", "", ""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ContentGuardApplication.createPlatformReviewer(fullWx, json, http, null));

        wxReject = true;
        PlatformReviewer.IdentityLookup lookup = new PlatformReviewer.IdentityLookup() {
            @Override
            public PlatformReviewer.Identity lookup(long playerId) {
                return lookup(playerId, "");
            }

            @Override
            public PlatformReviewer.Identity lookup(long playerId, String account) {
                if (playerId == 9001L) {
                    return new PlatformReviewer.Identity("wechat_minigame", "openid_after_login", "wx_union_1");
                }
                if (playerId == 9002L) {
                    // App 端微信渠道不得路由到小游戏 msgSecCheck
                    return new PlatformReviewer.Identity("wechat", "app_openid", "wx_union_2");
                }
                if (playerId == 0L && "wx_union_create".equals(account)) {
                    return new PlatformReviewer.Identity("wechat_minigame", "minigame_openid_create", account);
                }
                return PlatformReviewer.Identity.EMPTY;
            }
        };
        PlatformReviewer wired = ContentGuardApplication.createPlatformReviewer(fullWx, json, http, lookup);

        // 小游戏渠道建角前（playerId=0, account 非空）按账号查出小游戏 openid 并送检
        ReviewResult preCreate = wired.review(TextScene.TEXT_SCENE_ROLE_NAME, 0L, "wx_union_create", "含违规词内容", false);
        assertEquals(Outcome.REJECT, preCreate.outcome());
        assertEquals(1, wxCheckCalls.get());

        // 小游戏渠道已登录角色走小游戏审核
        ReviewResult postLogin = wired.review(TextScene.TEXT_SCENE_ROLE_NAME, 9001L, "", "含违规词内容", false);
        assertEquals(Outcome.REJECT, postLogin.outcome());
        assertEquals(2, wxCheckCalls.get());

        // App 渠道 wechat 与未知 wx_ 前缀账号不得把 unionid/App openid 错传小游戏接口
        ReviewResult appSkipped = wired.review(TextScene.TEXT_SCENE_ROLE_NAME, 9002L, "wx_union_2", "正常名", false);
        assertEquals(Outcome.SKIP, appSkipped.outcome());
        ReviewResult unknownPrefix = wired.review(TextScene.TEXT_SCENE_ROLE_NAME, 0L, "wx_unknown_union", "正常名", false);
        assertEquals(Outcome.SKIP, unknownPrefix.outcome());
        assertEquals(2, wxCheckCalls.get(), "App 渠道与未知 wx_ 前缀不得调用小游戏接口");
    }

    private static void respondJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
