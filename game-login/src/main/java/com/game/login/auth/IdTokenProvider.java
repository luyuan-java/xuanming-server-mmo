package com.game.login.auth;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apple / Google OIDC 身份令牌登录认证器（对齐 mmorpg {@code IdTokenProvider}）：
 * <ul>
 *   <li>{@code apple}：账号 {@code apple_<sub>}，{@code iss = https://appleid.apple.com}，JWKS {@code /auth/keys}；</li>
 *   <li>{@code google}：账号 {@code google_<sub>}，{@code iss ∈ {https://accounts.google.com, accounts.google.com}}，JWKS {@code /oauth2/v3/certs}；</li>
 *   <li>验签通过后经 {@link IdTokenReplayGuard} 按 SHA-256 摘要做一次性消费（TTL 到 {@code exp + 1m}），重放按凭证无效拒绝，存储故障按渠道故障拒绝。</li>
 * </ul>
 */
public final class IdTokenProvider implements ExternalAuthProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IdTokenProvider.class);
    public static final String APPLE_AUTH_TYPE = "apple";
    public static final String GOOGLE_AUTH_TYPE = "google";

    static final String APPLE_ISSUER = "https://appleid.apple.com";
    static final String APPLE_DEFAULT_ENDPOINT = "https://appleid.apple.com";
    static final String APPLE_JWKS_PATH = "/auth/keys";

    static final List<String> GOOGLE_ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");
    static final String GOOGLE_DEFAULT_ENDPOINT = "https://www.googleapis.com";
    static final String GOOGLE_JWKS_PATH = "/oauth2/v3/certs";
    static final String GOOGLE_CLIENT_ID_SUFFIX = ".apps.googleusercontent.com";

    private final String authType;
    private final String prefix;
    private final IdTokenVerifier verifier;
    private final IdTokenReplayGuard replayGuard;
    private final RedisKeys redisKeys;

    IdTokenProvider(String authType, String prefix, IdTokenVerifier verifier,
                    IdTokenReplayGuard replayGuard, RedisKeys redisKeys) {
        this.authType = Objects.requireNonNull(authType, "authType");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.replayGuard = replayGuard;
        this.redisKeys = redisKeys;
    }

    public static IdTokenProvider apple(String clientId, String endpoint, boolean allowHttpEndpoint,
                                        IdTokenReplayGuard replayGuard, RedisKeys redisKeys) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("Apple 登录需要 client-id（Bundle ID）");
        }
        String base = endpoint == null || endpoint.isBlank() ? APPLE_DEFAULT_ENDPOINT
                : OAuthHttp.requireEndpoint(endpoint, "Apple 登录 JWKS", allowHttpEndpoint);
        IdTokenVerifier verifier = new IdTokenVerifier(APPLE_AUTH_TYPE, List.of(APPLE_ISSUER), clientId,
                OAuthHttp.trimSlash(base) + APPLE_JWKS_PATH);
        return new IdTokenProvider(APPLE_AUTH_TYPE, "apple_", verifier, replayGuard, redisKeys);
    }

    public static IdTokenProvider google(String clientId, String endpoint, boolean allowHttpEndpoint,
                                         IdTokenReplayGuard replayGuard, RedisKeys redisKeys) {
        if (clientId == null || clientId.isBlank() || !clientId.endsWith(GOOGLE_CLIENT_ID_SUFFIX)) {
            throw new IllegalArgumentException("Google 登录 client-id 须以 " + GOOGLE_CLIENT_ID_SUFFIX + " 结尾");
        }
        String base = endpoint == null || endpoint.isBlank() ? GOOGLE_DEFAULT_ENDPOINT
                : OAuthHttp.requireEndpoint(endpoint, "Google 登录 JWKS", allowHttpEndpoint);
        IdTokenVerifier verifier = new IdTokenVerifier(GOOGLE_AUTH_TYPE, GOOGLE_ISSUERS, clientId,
                OAuthHttp.trimSlash(base) + GOOGLE_JWKS_PATH);
        return new IdTokenProvider(GOOGLE_AUTH_TYPE, "google_", verifier, replayGuard, redisKeys);
    }

    @Override
    public Optional<String> authenticate(String identityToken) {
        return authenticateWithIdentity(identityToken).map(VerifiedIdentity::account);
    }

    @Override
    public Optional<VerifiedIdentity> authenticateWithIdentity(String identityToken) {
        if (verifier.audience().isEmpty()) {
            throw new AuthProviderUnavailableException(authType + ": missing client_id");
        }
        Optional<IdTokenVerifier.VerifiedClaims> verified = verifier.verifyClaims(identityToken, null);
        if (verified.isEmpty()) {
            return Optional.empty();
        }
        IdTokenVerifier.VerifiedClaims claims = verified.get();
        if (!consumeOnce(authType, identityToken, verifier.usedFor(claims), replayGuard, redisKeys)) {
            return Optional.empty();
        }
        String account = prefix + claims.sub();
        log.info("oidc id-token auth ok provider={} account_len={}", authType, account.length());
        return Optional.of(new VerifiedIdentity(account, "", claims.iat()));
    }

    static boolean consumeOnce(String authType, String rawToken, Duration ttl,
                               IdTokenReplayGuard replayGuard, RedisKeys redisKeys) {
        if (replayGuard == null) {
            return true;
        }
        String digest = sha256Hex(rawToken);
        String key = redisKeys != null
                ? redisKeys.loginIdTokenUsed(authType, digest)
                : "id-token-used:" + authType + ":" + digest;
        try {
            return replayGuard.firstUse(key, ttl);
        } catch (RuntimeException e) {
            throw new AuthProviderUnavailableException(authType + ": record identity token use failed: "
                    + e.getClass().getSimpleName());
        }
    }

    static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        verifier.close();
    }
}
