package com.game.login.auth;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 官方通行证（Casdoor OIDC，{@code auth_type = "passport"}，对齐 mmorpg {@code PassportProvider}）。
 *
 * <p>复用 {@link IdTokenVerifier} 并强制执行通行证三条专有防线：
 * <ol>
 *   <li>{@code tokenType == "id-token"}（排在有效期校验之前：Casdoor 的 access-token / refresh-token 按凭证无效拒绝，不算渠道故障）；</li>
 *   <li>{@code passport_<sub>} 不含任何空白字符且 UTF-8 长度 &le; 191 字节；</li>
 *   <li>{@code exp > iat} 且 {@code exp - iat <= 2h}（超过 2 小时按渠道配置故障 {@link AuthProviderUnavailableException} 拒绝）。</li>
 * </ol>
 * 通过后执行一次性消费并返回带 {@code identityIssuedAt = iat} 的 {@link VerifiedIdentity}。
 */
public final class PassportProvider implements ExternalAuthProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PassportProvider.class);
    public static final String AUTH_TYPE = "passport";
    public static final String ACCOUNT_PREFIX = "passport_";
    static final String REQUIRED_TOKEN_TYPE = "id-token";
    static final String JWKS_PATH = "/.well-known/jwks";
    static final int MAX_ACCOUNT_BYTES = 191;
    static final long MAX_TOKEN_LIFETIME_SECONDS = 2L * 3600L;

    private final String issuer;
    private final IdTokenVerifier verifier;
    private final IdTokenReplayGuard replayGuard;
    private final RedisKeys redisKeys;

    public PassportProvider(String issuer, String clientId, String endpoint, boolean allowHttpEndpoint,
                            IdTokenReplayGuard replayGuard, RedisKeys redisKeys) {
        if (issuer == null || issuer.isBlank() || clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("官方通行证需要 issuer 与 client-id");
        }
        if (issuer.endsWith("/")) {
            throw new IllegalArgumentException("官方通行证 issuer 不能带末尾斜杠");
        }
        OAuthHttp.requireEndpoint(issuer, "官方通行证 issuer", allowHttpEndpoint);
        String base = endpoint == null || endpoint.isBlank() ? issuer
                : OAuthHttp.requireEndpoint(endpoint, "官方通行证 endpoint", allowHttpEndpoint);
        this.issuer = issuer;
        this.verifier = new IdTokenVerifier(AUTH_TYPE, List.of(issuer), clientId,
                OAuthHttp.trimSlash(base) + JWKS_PATH);
        this.replayGuard = replayGuard;
        this.redisKeys = redisKeys;
    }

    PassportProvider(String issuer, IdTokenVerifier verifier, IdTokenReplayGuard replayGuard, RedisKeys redisKeys) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.replayGuard = replayGuard;
        this.redisKeys = redisKeys;
    }

    public static String accountForSubject(String sub) {
        return ACCOUNT_PREFIX + sub;
    }

    /** 从账号名提取通行证 sub；不是通行证账号返回空。 */
    public static Optional<String> subjectOf(String account) {
        if (account == null || !account.startsWith(ACCOUNT_PREFIX) || account.length() <= ACCOUNT_PREFIX.length()) {
            return Optional.empty();
        }
        return Optional.of(account.substring(ACCOUNT_PREFIX.length()));
    }

    @Override
    public Optional<String> authenticate(String identityToken) {
        return authenticateWithIdentity(identityToken).map(VerifiedIdentity::account);
    }

    @Override
    public Optional<VerifiedIdentity> authenticateWithIdentity(String identityToken) {
        if (issuer.isEmpty() || verifier.audience().isEmpty()) {
            throw new AuthProviderUnavailableException("passport: missing issuer or client_id");
        }
        // REQUIRED_TOKEN_TYPE ("id-token") 在 IdTokenVerifier 中排在有效期检查之前
        Optional<IdTokenVerifier.VerifiedClaims> verified = verifier.verifyClaims(identityToken, REQUIRED_TOKEN_TYPE);
        if (verified.isEmpty()) {
            return Optional.empty();
        }
        IdTokenVerifier.VerifiedClaims claims = verified.get();
        String sub = claims.sub();
        if (containsWhitespace(sub)) {
            return Optional.empty();
        }
        String account = accountForSubject(sub);
        if (account.getBytes(StandardCharsets.UTF_8).length > MAX_ACCOUNT_BYTES) {
            return Optional.empty();
        }
        if (claims.exp() <= claims.iat()) {
            return Optional.empty();
        }
        long lifetimeSeconds = claims.exp() - claims.iat();
        if (lifetimeSeconds > MAX_TOKEN_LIFETIME_SECONDS) {
            throw new AuthProviderUnavailableException(
                    "passport: token lifetime " + lifetimeSeconds + "s exceeds max " + MAX_TOKEN_LIFETIME_SECONDS + "s");
        }
        if (!IdTokenProvider.consumeOnce(AUTH_TYPE, identityToken, verifier.usedFor(claims), replayGuard, redisKeys)) {
            return Optional.empty();
        }
        log.info("passport auth ok account_len={}", account.length());
        return Optional.of(new VerifiedIdentity(account, "", claims.iat()));
    }

    private static boolean containsWhitespace(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    @Override
    public void close() {
        verifier.close();
    }
}
