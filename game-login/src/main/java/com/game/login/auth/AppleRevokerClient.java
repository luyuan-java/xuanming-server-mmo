package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.login.compliance.AccountDeletionService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sign in with Apple 授权撤销客户端（对齐 mmorpg {@code apple_revoke.go}）：
 * 申请注销时当场用授权码调 {@code POST /auth/token} 换取一次性撤销凭据，提交注销后立刻调 {@code POST /auth/revoke}，不落盘、不记录。
 * {@code client_secret} 使用 PKCS#8 PEM EC P-256 私钥现签 ES256 JWT（5 分钟有效，IEEE P1363 64 字节 R||S）。
 */
public final class AppleRevokerClient implements AccountDeletionService.AppleRevoker, AutoCloseable {

    static final String APPLE_AUDIENCE = "https://appleid.apple.com";
    static final String DEFAULT_ENDPOINT = "https://appleid.apple.com";
    static final String TOKEN_PATH = "/auth/token";
    static final String REVOKE_PATH = "/auth/revoke";
    static final long SECRET_LIFETIME_SECONDS = 300L;
    static final int MAX_CODE_BYTES = 4096;
    private static final String HINT_SEP = ":";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final String clientId;
    private final String teamId;
    private final String keyId;
    private final ECPrivateKey privateKey;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final Clock clock;

    public AppleRevokerClient(String clientId, String teamId, String keyId, String privateKeyPem,
                              String endpoint, boolean allowHttpEndpoint) {
        this(clientId, teamId, keyId, privateKeyPem, endpoint, allowHttpEndpoint, OAuthHttp.TIMEOUT, Clock.systemUTC());
    }

    AppleRevokerClient(String clientId, String teamId, String keyId, String privateKeyPem,
                       String endpoint, boolean allowHttpEndpoint, Duration timeout, Clock clock) {
        if (clientId == null || clientId.isBlank() || teamId == null || teamId.isBlank() || keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Apple 授权撤销需要 client-id、team-id 与 key-id");
        }
        this.clientId = clientId;
        this.teamId = teamId;
        this.keyId = keyId;
        this.privateKey = parseEcP256PrivateKey(privateKeyPem);
        String resolved = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT
                : OAuthHttp.requireEndpoint(endpoint, "Apple 撤销", allowHttpEndpoint);
        this.baseUrl = OAuthHttp.trimSlash(resolved);
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String exchangeCode(String authorizationCode) throws InvalidAppleCodeException {
        if (authorizationCode == null || authorizationCode.isEmpty()
                || authorizationCode.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            throw new InvalidAppleCodeException("apple authorization code missing or too long");
        }
        String secret = signClientSecret();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", secret);
        form.put("code", authorizationCode);
        form.put("grant_type", "authorization_code");
        FormResponse resp = postForm(TOKEN_PATH, form);
        if (resp.status() != 200) {
            String errCode = appleErrorCode(resp.body());
            if ("invalid_grant".equals(errCode)) {
                throw new InvalidAppleCodeException("apple authorization code invalid or already used");
            }
            throw new AuthProviderUnavailableException("apple: token endpoint status " + resp.status() + " (" + errCode + ")");
        }
        JsonNode root;
        try {
            root = JSON.readTree(resp.body());
        } catch (Exception e) {
            throw new AuthProviderUnavailableException("apple: bad token response json");
        }
        String refresh = root.path("refresh_token").asText("");
        if (!refresh.isEmpty()) {
            return "refresh_token" + HINT_SEP + refresh;
        }
        String access = root.path("access_token").asText("");
        if (!access.isEmpty()) {
            return "access_token" + HINT_SEP + access;
        }
        throw new AuthProviderUnavailableException("apple: token response carries no token");
    }

    @Override
    public void revoke(String encodedGrant) {
        if (encodedGrant == null || encodedGrant.isEmpty()) {
            throw new AuthProviderUnavailableException("apple: nothing to revoke");
        }
        int sep = encodedGrant.indexOf(HINT_SEP);
        String hint = sep > 0 ? encodedGrant.substring(0, sep) : "refresh_token";
        String token = sep > 0 ? encodedGrant.substring(sep + 1) : encodedGrant;
        if (token.isEmpty()) {
            throw new AuthProviderUnavailableException("apple: empty revoke token");
        }
        String secret = signClientSecret();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", secret);
        form.put("token", token);
        form.put("token_type_hint", hint);
        FormResponse resp = postForm(REVOKE_PATH, form);
        if (resp.status() != 200) {
            throw new AuthProviderUnavailableException("apple: revoke endpoint status " + resp.status()
                    + " (" + appleErrorCode(resp.body()) + ")");
        }
    }

    String signClientSecret() {
        try {
            long now = clock.instant().getEpochSecond();
            byte[] headerJson = JSON.writeValueAsBytes(Map.of("alg", "ES256", "kid", keyId));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", teamId);
            claims.put("iat", now);
            claims.put("exp", now + SECRET_LIFETIME_SECONDS);
            claims.put("aud", APPLE_AUDIENCE);
            claims.put("sub", clientId);
            byte[] claimsJson = JSON.writeValueAsBytes(claims);
            String signingInput = B64URL.encodeToString(headerJson) + "." + B64URL.encodeToString(claimsJson);
            Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
            sig.initSign(privateKey);
            sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + B64URL.encodeToString(sig.sign());
        } catch (Exception e) {
            throw new AuthProviderUnavailableException("apple: sign client secret failed: " + e.getClass().getSimpleName());
        }
    }

    private record FormResponse(int status, byte[] body) {
    }

    private FormResponse postForm(String path, Map<String, String> form) {
        StringJoiner sj = new StringJoiner("&");
        form.forEach((k, v) -> sj.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            URI uri = URI.create(baseUrl + path);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(sj.toString(), StandardCharsets.UTF_8))
                    .build();
            // 2xx 与 4xx 均需读取应答体（受 64 KiB 上限保护），以区分 invalid_grant 与渠道故障
            pending = http.sendAsync(req, info -> new OAuthHttp.LimitedBody());
            HttpResponse<byte[]> resp = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new FormResponse(resp.statusCode(), resp.body());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AuthProviderUnavailableException("apple: http error: " + cause.getClass().getSimpleName());
        } catch (TimeoutException | RuntimeException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            throw new AuthProviderUnavailableException("apple: http error: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            if (pending != null) {
                pending.cancel(true);
            }
            Thread.currentThread().interrupt();
            throw new AuthProviderUnavailableException("apple: http interrupted");
        }
    }

    static ECPrivateKey parseEcP256PrivateKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Apple 撤销私钥不能为空");
        }
        String normalized = pem.replace("\r", "").trim();
        int begin = normalized.indexOf("-----BEGIN PRIVATE KEY-----");
        int end = normalized.indexOf("-----END PRIVATE KEY-----");
        if (begin < 0 || end <= begin) {
            throw new IllegalArgumentException("Apple 撤销私钥须为 PKCS#8 PEM（BEGIN PRIVATE KEY）");
        }
        String b64 = normalized.substring(begin + "-----BEGIN PRIVATE KEY-----".length(), end)
                .replaceAll("\\s+", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Apple 撤销私钥 Base64 解码失败");
        }
        ECPrivateKey key;
        try {
            key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Apple 撤销私钥不是合法的 EC PKCS#8 私钥");
        }
        if (!isP256(key.getParams())) {
            throw new IllegalArgumentException("Apple 撤销私钥必须是 EC P-256（secp256r1）曲线");
        }
        return key;
    }

    private static boolean isP256(ECParameterSpec params) {
        if (params == null) {
            return false;
        }
        try {
            AlgorithmParameters p256 = AlgorithmParameters.getInstance("EC");
            p256.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec expected = p256.getParameterSpec(ECParameterSpec.class);
            return expected.getOrder().equals(params.getOrder())
                    && expected.getCurve().equals(params.getCurve())
                    && expected.getGenerator().equals(params.getGenerator());
        } catch (Exception e) {
            return false;
        }
    }

    private static String appleErrorCode(byte[] body) {
        if (body == null || body.length == 0) {
            return "unknown";
        }
        try {
            String err = JSON.readTree(body).path("error").asText("");
            if (err.isEmpty() || err.length() > 64) {
                return "unknown";
            }
            return err;
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
