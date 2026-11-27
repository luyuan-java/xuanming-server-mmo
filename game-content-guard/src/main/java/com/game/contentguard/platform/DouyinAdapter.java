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
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抖音小游戏内容安全适配器（antidirt，对齐 Go {@code platform/douyin.go} 与 {@code token_store.go}）。
 * 响应体限 64 KiB；支持多副本经 {@link TokenStore} CAS 共享 access_token 防止互相顶掉；
 * HTTP 401 或业务码 401/40002/40020 时标记旧凭据为 stale 并刷新重试一次。
 */
public final class DouyinAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(DouyinAdapter.class);
    private static final String DEFAULT_BASE_URL = "https://developer.toutiao.com";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final String REDIS_CAS_LUA =
            "local cur = redis.call('GET', KEYS[1]) "
                    + "if (not cur and ARGV[1] == '') or cur == ARGV[1] then "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', tonumber(ARGV[3])) "
                    + "return 1 end return 0";

    /** 多副本共享平台调用凭据存储（对齐 Go {@code platform.TokenStore}；读写异常不阻断审核，降级为本副本直取）。 */
    public interface TokenStore {
        /** @param version 用于 CAS 写回比对的当前版本串（键不存在时为空串）。 */
        record Snapshot(String token, Instant expiresAt, String version) {
            public static final Snapshot EMPTY = new Snapshot("", Instant.EPOCH, "");
        }

        Snapshot load() throws Exception;

        boolean compareAndStore(String expectedVersion, String token, Instant expiresAt) throws Exception;
    }

    /** 基于 Redis 单键 Lua CAS 的多副本共享凭据存储（只存 token 与过期时间戳，AppSecret 不进 Redis）。 */
    public static TokenStore redisTokenStore(RedissonClient redis, String key, Clock clock) {
        Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(clock, "clock");
        return new TokenStore() {
            @Override
            public Snapshot load() {
                String raw = redis.<String>getBucket(key, StringCodec.INSTANCE).get();
                if (raw == null || raw.isEmpty()) {
                    return Snapshot.EMPTY;
                }
                int sep = raw.indexOf('\n');
                if (sep <= 0 || sep >= raw.length() - 1) {
                    // 损坏值带原 raw 作版本号，允许下一次刷新原子覆盖。
                    return new Snapshot("", Instant.EPOCH, raw);
                }
                try {
                    long expMs = Long.parseLong(raw.substring(0, sep));
                    String tok = raw.substring(sep + 1);
                    return new Snapshot(tok, Instant.ofEpochMilli(expMs), raw);
                } catch (NumberFormatException bad) {
                    return new Snapshot("", Instant.EPOCH, raw);
                }
            }

            @Override
            public boolean compareAndStore(String expectedVersion, String token, Instant expiresAt) {
                long ttlMs = Math.max(60_000L, expiresAt.toEpochMilli() - clock.millis());
                String nextRaw = expiresAt.toEpochMilli() + "\n" + token;
                Long ok = redis.getScript(StringCodec.INSTANCE).eval(
                        RScript.Mode.READ_WRITE,
                        REDIS_CAS_LUA,
                        RScript.ReturnType.INTEGER,
                        List.of(key),
                        expectedVersion == null ? "" : expectedVersion,
                        nextRaw,
                        Long.toString(ttlMs));
                return ok != null && ok == 1L;
            }
        };
    }

    private final String appId;
    private final String appSecret;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final Clock clock;
    private volatile TokenStore sharedStore;

    private final Object tokenLock = new Object();
    private String cachedToken = "";
    private Instant tokenExpiresAt = Instant.EPOCH;
    private String staleToken = "";
    private CompletableFuture<String> inFlightRefresh;

    public DouyinAdapter(String appId, String appSecret, String baseUrl, HttpClient httpClient, ObjectMapper json, Clock clock) {
        this.appId = Objects.requireNonNull(appId, "appId");
        this.appSecret = Objects.requireNonNull(appSecret, "appSecret");
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 装配多副本共享凭据存储（null 表示退回单副本本地缓存）。 */
    public DouyinAdapter withSharedToken(TokenStore shared) {
        this.sharedStore = shared;
        return this;
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
                log.error("[content_guard/douyin] token fetch failed: {}", WeChatAdapter.sanitizeError(e));
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
                log.error("[content_guard/douyin] antidirt request failed: {}", WeChatAdapter.sanitizeError(e));
                return ReviewResult.unavailable(PROVIDER_DOUYIN);
            }
        }
        return ReviewResult.unavailable(PROVIDER_DOUYIN);
    }

    private String accessToken(Duration timeout) throws IOException, InterruptedException {
        Instant now = clock.instant();
        CompletableFuture<String> mine = null;
        CompletableFuture<String> existing;
        String stale;
        synchronized (tokenLock) {
            if (!cachedToken.isEmpty() && now.plus(REFRESH_MARGIN).isBefore(tokenExpiresAt)) {
                return cachedToken;
            }
            stale = staleToken;
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
            TokenStore shared = this.sharedStore;
            String expectedVersion = "";
            boolean sharedActive = shared != null;
            if (sharedActive) {
                try {
                    TokenStore.Snapshot snap = shared.load();
                    if (isUsable(snap, stale)) {
                        remember(snap.token(), snap.expiresAt(), mine);
                        mine.complete(snap.token());
                        return snap.token();
                    }
                    expectedVersion = snap == null ? "" : snap.version();
                } catch (Exception e) {
                    log.warn("[content_guard/douyin] shared token read failed, falling back to local fetch: {}",
                            WeChatAdapter.sanitizeError(e));
                    sharedActive = false;
                }
            }

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
            Instant expiresAt = clock.instant().plus(ttl);
            if (sharedActive) {
                try {
                    boolean stored = shared.compareAndStore(expectedVersion, token, expiresAt);
                    if (!stored) {
                        // 别的副本抢先写回：尽量改用对方写入的新凭据，全集群收敛到同一份。
                        TokenStore.Snapshot winner = shared.load();
                        if (isUsable(winner, stale)) {
                            token = winner.token();
                            expiresAt = winner.expiresAt();
                        }
                    }
                } catch (Exception e) {
                    log.warn("[content_guard/douyin] shared token write failed, using local token: {}",
                            WeChatAdapter.sanitizeError(e));
                }
            }
            remember(token, expiresAt, mine);
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

    private boolean isUsable(TokenStore.Snapshot snap, String stale) {
        return snap != null
                && snap.token() != null
                && !snap.token().isEmpty()
                && !snap.token().equals(stale)
                && snap.expiresAt() != null
                && clock.instant().plus(REFRESH_MARGIN).isBefore(snap.expiresAt());
    }

    private void remember(String token, Instant expiresAt, CompletableFuture<String> mine) {
        synchronized (tokenLock) {
            cachedToken = token;
            tokenExpiresAt = expiresAt;
            if (inFlightRefresh == mine) {
                inFlightRefresh = null;
            }
        }
    }

    private void invalidateToken(String stale) {
        synchronized (tokenLock) {
            staleToken = stale == null ? "" : stale;
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
