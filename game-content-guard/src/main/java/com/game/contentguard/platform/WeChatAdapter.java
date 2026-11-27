package com.game.contentguard.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信小游戏内容安全适配器（msgSecCheck v2，对齐 Go {@code platform/wechat.go}）。
 * 凭据仅存于内存，不写日志；响应体读取限制在 64 KiB 以内；access_token 过期码（40001/40014/42001）自动刷新并重试一次。
 */
public final class WeChatAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(WeChatAdapter.class);
    private static final String DEFAULT_BASE_URL = "https://api.weixin.qq.com";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final int MAX_RESP_BYTES = 64 * 1024;
    /** 微信 msgSecCheck 内容含违规信息错误码（对齐 Go wechat.go: wechatErrRiskyContent）。 */
    static final int ERR_RISKY_CONTENT = 87014;
    /** 微信 msgSecCheck 用户近 2 小时未在小程序活跃错误码（非故障，仅走本地词库兜底）。 */
    static final int ERR_USER_NOT_RECENTLY_ACTIVE = 61010;

    private final String appId;
    private final String appSecret;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final Clock clock;

    private final Object tokenLock = new Object();
    private String cachedToken = "";
    private Instant tokenExpiresAt = Instant.EPOCH;
    private CompletableFuture<String> inFlightRefresh;

    public WeChatAdapter(String appId, String appSecret, String baseUrl, HttpClient httpClient, ObjectMapper json, Clock clock) {
        this.appId = Objects.requireNonNull(appId, "appId");
        this.appSecret = Objects.requireNonNull(appSecret, "appSecret");
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String provider() {
        return PROVIDER_WECHAT;
    }

    @Override
    public ReviewResult check(TextScene scene, String openId, String text, Duration timeout) {
        if (openId == null || openId.isEmpty()) {
            return ReviewResult.skip(PROVIDER_WECHAT);
        }
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(Map.of(
                    "content", text,
                    "version", 2,
                    "scene", wechatSceneCode(scene),
                    "openid", openId));
        } catch (IOException e) {
            return ReviewResult.unavailable(PROVIDER_WECHAT);
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            String token;
            try {
                token = accessToken(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[content_guard/wechat] token fetch interrupted");
                return ReviewResult.unavailable(PROVIDER_WECHAT);
            } catch (Exception e) {
                log.error("[content_guard/wechat] token fetch failed: {}", sanitizeError(e));
                return ReviewResult.unavailable(PROVIDER_WECHAT);
            }
            try {
                URI uri = URI.create(baseUrl + "/wxa/msg_sec_check?access_token=" + urlEncode(token));
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                byte[] body = readBounded(resp.body());
                if (resp.statusCode() != 200) {
                    log.error("[content_guard/wechat] msg_sec_check http status={}", resp.statusCode());
                    return ReviewResult.unavailable(PROVIDER_WECHAT);
                }
                JsonNode root = json.readTree(body);
                int errCode = root.path("errcode").asInt(0);
                if (errCode != 0) {
                    if (errCode == 40001 || errCode == 40014 || errCode == 42001) {
                        invalidateToken(token);
                        continue;
                    }
                    if (errCode == ERR_RISKY_CONTENT) {
                        // 平台明确判违规不得降级为 UNAVAILABLE（否则世界频道会按超时先放行导致违规内容漏过）。
                        return ReviewResult.reject(TextCategory.TEXT_CATEGORY_OTHER, PROVIDER_WECHAT);
                    }
                    if (errCode == ERR_USER_NOT_RECENTLY_ACTIVE) {
                        // 连续在线超 2 小时非平台故障，跳过外部层由本地词库兜底，避免长期阻塞改名/建帮。
                        return ReviewResult.skip(PROVIDER_WECHAT);
                    }
                    log.error("[content_guard/wechat] msg_sec_check errcode={} errmsg={}", errCode, root.path("errmsg").asText(""));
                    return ReviewResult.unavailable(PROVIDER_WECHAT);
                }
                JsonNode result = root.path("result");
                if ("pass".equals(result.path("suggest").asText(""))) {
                    return ReviewResult.pass(PROVIDER_WECHAT);
                }
                return ReviewResult.reject(labelCategory(result.path("label").asInt(0)), PROVIDER_WECHAT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ReviewResult.unavailable(PROVIDER_WECHAT);
            } catch (Exception e) {
                log.error("[content_guard/wechat] msg_sec_check request failed: {}", sanitizeError(e));
                return ReviewResult.unavailable(PROVIDER_WECHAT);
            }
        }
        return ReviewResult.unavailable(PROVIDER_WECHAT);
    }

    private String accessToken(Duration timeout) throws IOException, InterruptedException {
        Instant now = clock.instant();
        CompletableFuture<String> mine = null;
        CompletableFuture<String> existing;
        synchronized (tokenLock) {
            if (!cachedToken.isEmpty() && now.plus(REFRESH_MARGIN).isBefore(tokenExpiresAt)) {
                return cachedToken;
            }
            existing = inFlightRefresh;
            if (existing == null) {
                mine = new CompletableFuture<>();
                inFlightRefresh = mine;
            }
        }
        if (mine == null) {
            try {
                return existing.get(Math.max(100L, timeout.toMillis()), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (TimeoutException e) {
                throw new IOException("wechat token refresh wait timed out", e);
            }
        }
        try {
            // 对齐 Go wechat.go: 使用 POST /cgi-bin/stable_token 避免多副本刷新互踢且不把 appSecret 暴露在 URL 查询串。
            byte[] tokenReq = json.writeValueAsBytes(Map.of(
                    "grant_type", "client_credential",
                    "appid", appId,
                    "secret", appSecret));
            URI uri = URI.create(baseUrl + "/cgi-bin/stable_token");
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(tokenReq))
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = readBounded(resp.body());
            if (resp.statusCode() != 200) {
                throw new IOException("wechat token http status " + resp.statusCode());
            }
            JsonNode root = json.readTree(body);
            int errCode = root.path("errcode").asInt(0);
            String token = root.path("access_token").asText("");
            if (errCode != 0 || token.isEmpty()) {
                throw new IOException("wechat token errcode=" + errCode);
            }
            Duration ttl = Duration.ofSeconds(root.path("expires_in").asLong(0));
            if (ttl.compareTo(REFRESH_MARGIN) <= 0) {
                ttl = REFRESH_MARGIN.plusMinutes(1);
            }
            synchronized (tokenLock) {
                cachedToken = token;
                tokenExpiresAt = clock.instant().plus(ttl);
                if (inFlightRefresh == mine) {
                    inFlightRefresh = null;
                }
            }
            mine.complete(token);
            return token;
        } catch (Throwable t) {
            synchronized (tokenLock) {
                if (inFlightRefresh == mine) {
                    inFlightRefresh = null;
                }
            }
            mine.completeExceptionally(t);
            if (t instanceof IOException io) {
                throw io;
            }
            if (t instanceof InterruptedException ie) {
                throw ie;
            }
            throw new IOException(t);
        }
    }

    private void invalidateToken(String stale) {
        synchronized (tokenLock) {
            if (cachedToken.equals(stale)) {
                cachedToken = "";
                tokenExpiresAt = Instant.EPOCH;
            }
        }
    }

    static int wechatSceneCode(TextScene scene) {
        return switch (scene) {
            case TEXT_SCENE_ROLE_NAME, TEXT_SCENE_GUILD_NAME, TEXT_SCENE_PET_NAME, TEXT_SCENE_TRADE_TITLE -> 1;
            case TEXT_SCENE_GUILD_ANNOUNCEMENT -> 2;
            case TEXT_SCENE_CHAT_WORLD -> 3;
            default -> 4;
        };
    }

    static TextCategory labelCategory(int label) {
        return switch (label) {
            case 20001 -> TextCategory.TEXT_CATEGORY_POLITICS;
            case 20002, 20003 -> TextCategory.TEXT_CATEGORY_PORN;
            case 20012, 20013 -> TextCategory.TEXT_CATEGORY_ABUSE;
            case 10001 -> TextCategory.TEXT_CATEGORY_AD;
            default -> TextCategory.TEXT_CATEGORY_OTHER;
        };
    }

    static byte[] readBounded(InputStream in) throws IOException {
        try (in) {
            return in.readNBytes(MAX_RESP_BYTES);
        }
    }

    private static String sanitizeError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String cleaned = msg.replaceAll("https?://\\S+", "<redacted-url>")
                .replaceAll("(access_token|secret)=[^&\\s\"']+", "$1=<redacted>");
        return e.getClass().getSimpleName() + ": " + cleaned;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
