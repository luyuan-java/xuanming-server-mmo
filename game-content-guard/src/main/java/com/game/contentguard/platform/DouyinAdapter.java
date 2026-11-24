package com.game.contentguard.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.content_guard.TextCategory;
import com.game.proto.content_guard.TextScene;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抖音小游戏内容安全适配器（antidirt，对齐 Go {@code platform/douyin.go}）。
 * 响应体限 64 KiB；HTTP 401 或业务码 401/40002/40020 时清 token 缓存并重试一次。
 */
public final class DouyinAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(DouyinAdapter.class);
    private static final String DEFAULT_BASE_URL = "https://developer.toutiao.com";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

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

    public DouyinAdapter(String appId, String appSecret, String baseUrl, HttpClient httpClient, ObjectMapper json, Clock clock) {
        this.appId = Objects.requireNonNull(appId, "appId");
        this.appSecret = Objects.requireNonNull(appSecret, "appSecret");
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String provider() {
        return PROVIDER_DOUYIN;
    }

    @Override
    public ReviewResult check(TextScene scene, String openId, String text, Duration timeout) {
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(Map.of("tasks", List.of(Map.of("content", text))));
        } catch (IOException e) {
            return ReviewResult.unavailable(PROVIDER_DOUYIN);
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            String token;
            try {
                token = accessToken(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[content_guard/douyin] token fetch interrupted");
                return ReviewResult.unavailable(PROVIDER_DOUYIN);
            } catch (Exception e) {
                log.error("[content_guard/douyin] token fetch failed: {}", e.getMessage());
                return ReviewResult.unavailable(PROVIDER_DOUYIN);
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v2/tags/text/antidirt"))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("X-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                        .build();
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
                byte[] body = WeChatAdapter.readBounded(resp.body());
                if (resp.statusCode() == 401) {
                    invalidateToken(token);
                    continue;
                }
                if (resp.statusCode() != 200) {
                    log.error("[content_guard/douyin] antidirt http status={}", resp.statusCode());
                    return ReviewResult.unavailable(PROVIDER_DOUYIN);
                }
                JsonNode root = json.readTree(body);
                int code = root.path("code").asInt(0);
                if (code == 401 || code == 40002 || code == 40020) {
                    invalidateToken(token);
                    continue;
                }
                JsonNode data = root.path("data");
                if (code != 0 || !data.isArray() || data.isEmpty()) {
                    log.error("[content_guard/douyin] antidirt code={} message={}", code, root.path("message").asText(""));
                    return ReviewResult.unavailable(PROVIDER_DOUYIN);
                }
                for (JsonNode item : data) {
                    if (item.path("code").asInt(0) != 0) {
                        return ReviewResult.unavailable(PROVIDER_DOUYIN);
                    }
                    for (JsonNode predict : item.path("predicts")) {
                        if (predict.path("hit").asBoolean(false) || predict.path("prob").asDouble(0.0) >= 1.0) {
                            return ReviewResult.reject(TextCategory.TEXT_CATEGORY_OTHER, PROVIDER_DOUYIN);
                        }
                    }
                }
                return ReviewResult.pass(PROVIDER_DOUYIN);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ReviewResult.unavailable(PROVIDER_DOUYIN);
            } catch (Exception e) {
                log.error("[content_guard/douyin] antidirt request failed: {}", e.getMessage());
                return ReviewResult.unavailable(PROVIDER_DOUYIN);
            }
        }
        return ReviewResult.unavailable(PROVIDER_DOUYIN);
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
                throw new IOException("douyin token refresh wait timed out", e);
            }
        }
        try {
            byte[] payload = json.writeValueAsBytes(Map.of(
                    "appid", appId,
                    "secret", appSecret,
                    "grant_type", "client_credential"));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/apps/v2/token"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body = WeChatAdapter.readBounded(resp.body());
            if (resp.statusCode() != 200) {
                throw new IOException("douyin token http status " + resp.statusCode());
            }
            JsonNode root = json.readTree(body);
            int errNo = root.path("err_no").asInt(0);
            String token = root.path("data").path("access_token").asText("");
            if (errNo != 0 || token.isEmpty()) {
                throw new IOException("douyin token err_no=" + errNo);
            }
            Duration ttl = Duration.ofSeconds(root.path("data").path("expires_in").asLong(0));
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

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
