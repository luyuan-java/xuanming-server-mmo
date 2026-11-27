package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OIDC 身份令牌（RS256 + JWKS 缓存）通用校验器（对齐 mmorpg {@code idtoken.go}），供 Apple / Google / 官方通行证共用。
 *
 * <p>校验顺序固定：长度上限（16 KiB）→ 3 段 JWS → 头部只接受 {@code alg=RS256} 与非空 {@code kid} → 按 {@code kid}
 * 从缓存取公钥（&ge; 2048 位 RSA）验签 → 签名通过后才解析声明 → 核 {@code iss}/{@code aud}/{@code sub} 与时钟偏差（&plusmn;60s）的
 * {@code exp}/{@code iat}/{@code nbf}。
 *
 * <p>JWKS 缓存：新鲜期 1h；过期后认识的 {@code kid} 直接用旧公钥并在后台单飞刷新；不认识的 {@code kid} 在距上次拉取满 1m 时等待一次单飞刷新，
 * 不足 1m 直接按凭证无效拒绝；拉取失败时 24h 内的旧公钥照用；等待按请求时限超时但不取消共享拉取。日志与异常绝不携带令牌原文。
 */
final class IdTokenVerifier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IdTokenVerifier.class);
    static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(1);
    static final Duration DEFAULT_MIN_REFETCH = Duration.ofMinutes(1);
    static final Duration DEFAULT_MAX_STALE = Duration.ofHours(24);
    static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration LEEWAY = Duration.ofMinutes(1);
    static final int MIN_RSA_BITS = 2048;
    static final int MAX_TOKEN_BYTES = 16 << 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    record VerifiedClaims(String iss, String sub, List<String> aud, long exp, long iat, Long nbf, String tokenType) {
    }

    private record FetchOutcome(AuthProviderUnavailableException error) {
        static final FetchOutcome OK = new FetchOutcome(null);
    }

    private final String provider;
    private final List<String> issuers;
    private final String audience;
    private final String jwksUrl;
    private final OAuthHttp http;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration minRefetch;
    private final Duration maxStale;
    private final Duration waitTimeout;
    private final ExecutorService fetchExecutor;

    private final Object lock = new Object();
    private Map<String, RSAPublicKey> keys = Map.of();
    private Instant freshUntil = Instant.EPOCH;
    private Instant staleUntil = Instant.EPOCH;
    private Instant lastFetch = null;
    private CompletableFuture<FetchOutcome> inflight;

    IdTokenVerifier(String provider, List<String> issuers, String audience, String jwksUrl) {
        this(provider, issuers, audience, jwksUrl, new OAuthHttp(), Clock.systemUTC(),
                DEFAULT_CACHE_TTL, DEFAULT_MIN_REFETCH, DEFAULT_MAX_STALE, DEFAULT_WAIT_TIMEOUT);
    }

    IdTokenVerifier(String provider, List<String> issuers, String audience, String jwksUrl,
                    OAuthHttp http, Clock clock, Duration cacheTtl, Duration minRefetch,
                    Duration maxStale, Duration waitTimeout) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.issuers = List.copyOf(Objects.requireNonNull(issuers, "issuers"));
        this.audience = audience == null ? "" : audience;
        this.jwksUrl = Objects.requireNonNull(jwksUrl, "jwksUrl");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.minRefetch = Objects.requireNonNull(minRefetch, "minRefetch");
        this.maxStale = Objects.requireNonNull(maxStale, "maxStale");
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
        this.fetchExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("login-jwks-" + provider).daemon(true).factory());
    }

    String audience() {
        return audience;
    }

    /**
     * 验签并核声明。返回空表示令牌本身无效（格式、算法、签名、声明、未知 kid）；
     * 拉取公钥失败或等待超时抛 {@link AuthProviderUnavailableException}。
     *
     * @param requiredTokenType 非空时要求 {@code tokenType} 逐字匹配（且排在有效期校验之前，供 Casdoor 通行证用）
     */
    Optional<VerifiedClaims> verifyClaims(String raw, String requiredTokenType) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_TOKEN_BYTES
                || raw.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES) {
            return Optional.empty();
        }
        int dot1 = raw.indexOf('.');
        if (dot1 <= 0) {
            return Optional.empty();
        }
        int dot2 = raw.indexOf('.', dot1 + 1);
        if (dot2 <= dot1 + 1 || dot2 == raw.length() - 1 || raw.indexOf('.', dot2 + 1) >= 0) {
            return Optional.empty();
        }
        String headerSeg = raw.substring(0, dot1);
        String payloadSeg = raw.substring(dot1 + 1, dot2);
        String sigSeg = raw.substring(dot2 + 1);

        JsonNode header = decodeJsonSegment(headerSeg);
        if (header == null || !header.isObject()) {
            return Optional.empty();
        }
        String alg = header.path("alg").asText("");
        String kid = header.path("kid").asText("");
        if (!"RS256".equals(alg) || kid.isEmpty()) {
            return Optional.empty();
        }
        byte[] sigBytes = decodeBase64UrlNoPad(sigSeg);
        if (sigBytes == null || sigBytes.length == 0) {
            return Optional.empty();
        }

        RSAPublicKey pubKey = resolveKey(kid);
        if (pubKey == null) {
            return Optional.empty();
        }
        byte[] signingInput = raw.substring(0, dot2).getBytes(StandardCharsets.US_ASCII);
        if (!verifyRs256(pubKey, signingInput, sigBytes)) {
            return Optional.empty();
        }

        JsonNode payload = decodeJsonSegment(payloadSeg);
        if (payload == null || !payload.isObject()) {
            return Optional.empty();
        }
        return checkClaims(payload, requiredTokenType);
    }

    /** 一次性消费保留时长：到 {@code exp + 1m} 为止，至少 1 秒。 */
    Duration usedFor(VerifiedClaims claims) {
        Instant expireAt = Instant.ofEpochSecond(claims.exp()).plus(LEEWAY);
        Duration ttl = Duration.between(clock.instant(), expireAt);
        return ttl.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : ttl;
    }

    private Optional<VerifiedClaims> checkClaims(JsonNode payload, String requiredTokenType) {
        String iss = payload.path("iss").isTextual() ? payload.path("iss").asText() : "";
        if (!issuers.contains(iss)) {
            return Optional.empty();
        }
        List<String> aud = parseAudience(payload.get("aud"));
        if (aud == null || !aud.contains(audience)) {
            return Optional.empty();
        }
        String sub = payload.path("sub").isTextual() ? payload.path("sub").asText() : "";
        if (sub.isEmpty()) {
            return Optional.empty();
        }
        String tokenType = payload.path("tokenType").isTextual() ? payload.path("tokenType").asText() : "";
        if (requiredTokenType != null && !requiredTokenType.isEmpty() && !requiredTokenType.equals(tokenType)) {
            // 先于有效期检查：非 id-token（如 Casdoor access-token / refresh-token）一律按凭证无效拒绝，不走配置故障
            return Optional.empty();
        }
        Long exp = parseIntegralLong(payload.get("exp"));
        Long iat = parseIntegralLong(payload.get("iat"));
        if (exp == null || iat == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!now.isBefore(Instant.ofEpochSecond(exp).plus(LEEWAY))) {
            return Optional.empty();
        }
        if (Instant.ofEpochSecond(iat).isAfter(now.plus(LEEWAY))) {
            return Optional.empty();
        }
        Long nbf = null;
        JsonNode nbfNode = payload.get("nbf");
        if (nbfNode != null && !nbfNode.isNull()) {
            nbf = parseIntegralLong(nbfNode);
            if (nbf == null || Instant.ofEpochSecond(nbf).isAfter(now.plus(LEEWAY))) {
                return Optional.empty();
            }
        }
        return Optional.of(new VerifiedClaims(iss, sub, aud, exp, iat, nbf, tokenType));
    }

    /** 按 kid 解析公钥；返回 null 表示 kid 不在有效公钥集中（凭证无效）；拉取失败或等超时抛 AuthProviderUnavailableException。 */
    private RSAPublicKey resolveKey(String kid) {
        CompletableFuture<FetchOutcome> pending;
        synchronized (lock) {
            Instant now = clock.instant();
            RSAPublicKey cached = keys.get(kid);
            if (cached != null && now.isBefore(staleUntil)) {
                if (!now.isBefore(freshUntil) && inflight == null && mayFetchLocked(now)) {
                    startFetchLocked(now);
                }
                return cached;
            }
            pending = inflight;
            if (pending == null) {
                if (!mayFetchLocked(now)) {
                    return missLocked(now, null);
                }
                pending = startFetchLocked(now);
            }
        }

        FetchOutcome outcome;
        try {
            outcome = pending.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 不取消共享拉取：避免先来的请求超时拖垮后面等待的并发请求
            throw new AuthProviderUnavailableException(provider + ": wait for signing keys timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthProviderUnavailableException(provider + ": interrupted waiting for signing keys");
        } catch (Exception e) {
            throw new AuthProviderUnavailableException(provider + ": signing keys fetch failed");
        }

        synchronized (lock) {
            Instant now = clock.instant();
            RSAPublicKey cached = keys.get(kid);
            if (cached != null && now.isBefore(staleUntil)) {
                return cached;
            }
            return missLocked(now, outcome == null ? null : outcome.error());
        }
    }

    private boolean mayFetchLocked(Instant now) {
        return lastFetch == null || !Duration.between(lastFetch, now).minus(minRefetch).isNegative();
    }

    private RSAPublicKey missLocked(Instant now, AuthProviderUnavailableException fetchErr) {
        if (fetchErr != null) {
            throw fetchErr;
        }
        if (now.isBefore(freshUntil)) {
            return null;
        }
        throw new AuthProviderUnavailableException(provider + ": signing keys unavailable");
    }

    private CompletableFuture<FetchOutcome> startFetchLocked(Instant now) {
        CompletableFuture<FetchOutcome> future = new CompletableFuture<>();
        inflight = future;
        lastFetch = now;
        fetchExecutor.execute(() -> {
            Map<String, RSAPublicKey> fetched = null;
            AuthProviderUnavailableException err = null;
            try {
                fetched = fetchKeys();
            } catch (AuthProviderUnavailableException e) {
                err = e;
            } catch (RuntimeException e) {
                err = new AuthProviderUnavailableException(provider + ": fetch JWKS error: " + e.getClass().getSimpleName());
            }
            synchronized (lock) {
                if (err == null && fetched != null) {
                    Instant fetchedAt = clock.instant();
                    keys = fetched;
                    freshUntil = fetchedAt.plus(cacheTtl);
                    staleUntil = fetchedAt.plus(maxStale);
                } else if (err != null) {
                    log.warn("jwks fetch failed provider={} reason={}", provider, err.getMessage());
                }
                inflight = null;
            }
            future.complete(err == null ? FetchOutcome.OK : new FetchOutcome(err));
        });
        return future;
    }

    private Map<String, RSAPublicKey> fetchKeys() {
        String body = http.getUrl(jwksUrl);
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            throw new AuthProviderUnavailableException(provider + ": bad JWKS json");
        }
        JsonNode keysNode = root == null ? null : root.get("keys");
        if (keysNode == null || !keysNode.isArray()) {
            throw new AuthProviderUnavailableException(provider + ": JWKS has no keys array");
        }
        Map<String, RSAPublicKey> parsed = new HashMap<>();
        for (JsonNode k : keysNode) {
            if (!k.isObject()) {
                continue;
            }
            String kty = k.path("kty").asText("");
            String kid = k.path("kid").asText("");
            String use = k.path("use").asText("");
            String alg = k.path("alg").asText("");
            if (!"RSA".equals(kty) || kid.isEmpty()
                    || (!use.isEmpty() && !"sig".equals(use))
                    || (!alg.isEmpty() && !"RS256".equals(alg))) {
                continue;
            }
            RSAPublicKey pub = parseRsaPublicKey(k.path("n").asText(""), k.path("e").asText(""));
            if (pub != null) {
                parsed.put(kid, pub);
            }
        }
        if (parsed.isEmpty()) {
            throw new AuthProviderUnavailableException(provider + ": JWKS has no usable RS256 key");
        }
        return Map.copyOf(parsed);
    }

    static RSAPublicKey parseRsaPublicKey(String nB64, String eB64) {
        if (nB64 == null || nB64.isEmpty() || eB64 == null || eB64.isEmpty()) {
            return null;
        }
        byte[] nBytes = decodeBase64UrlNoPad(nB64);
        byte[] eBytes = decodeBase64UrlNoPad(eB64);
        if (nBytes == null || nBytes.length == 0 || eBytes == null || eBytes.length == 0) {
            return null;
        }
        BigInteger n = new BigInteger(1, nBytes);
        BigInteger e = new BigInteger(1, eBytes);
        if (n.bitLength() < MIN_RSA_BITS) {
            return null;
        }
        if (e.compareTo(BigInteger.valueOf(3)) < 0
                || e.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                || !e.testBit(0)) {
            return null;
        }
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            return (RSAPublicKey) key;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean verifyRs256(RSAPublicKey key, byte[] input, byte[] sigBytes) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(input);
            return sig.verify(sigBytes);
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonNode decodeJsonSegment(String segment) {
        byte[] raw = decodeBase64UrlNoPad(segment);
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] decodeBase64UrlNoPad(String segment) {
        if (segment == null || segment.isEmpty() || segment.indexOf('=') >= 0) {
            return null;
        }
        try {
            return B64URL.decode(segment);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> parseAudience(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        if (node.isArray()) {
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (!item.isTextual()) {
                    return null;
                }
                out.add(item.asText());
            }
            return out;
        }
        return null;
    }

    private static Long parseIntegralLong(JsonNode node) {
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToLong()) {
            return null;
        }
        return node.longValue();
    }

    @Override
    public void close() {
        fetchExecutor.shutdownNow();
        http.close();
    }
}
