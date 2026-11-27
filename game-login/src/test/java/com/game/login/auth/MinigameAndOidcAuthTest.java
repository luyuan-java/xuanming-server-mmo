package com.game.login.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.discovery.RedisProperties;
import com.game.login.LoginAuthProperties;
import com.game.login.LoginConfiguration;
import com.game.login.LoginProperties;
import com.game.login.compliance.AccountDeletionService;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 覆盖微信/抖音小游戏登录（L2219）、Apple/Google RS256 OIDC 与一次性消费（L2449/L2562）、
 * Apple 授权撤销（L2562）、官方通行证 Casdoor OIDC（L2981）及配置启动门禁。
 */
class MinigameAndOidcAuthTest {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private String startServer(Map<String, com.sun.net.httpserver.HttpHandler> routes) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        routes.forEach((path, handler) -> server.createContext(path, handler));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void replyJson(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void 微信小游戏与App同unionid得同一账号_45011按凭证无效_不泄sessionKey() throws Exception {
        AtomicReference<String> lastQuery = new AtomicReference<>();
        String base = startServer(Map.of(
                "/sns/jscode2session", ex -> {
                    String q = ex.getRequestURI().getRawQuery();
                    lastQuery.set(q);
                    if (q.contains("js_code=ok_union")) {
                        replyJson(ex, 200, "{\"openid\":\"op1\",\"unionid\":\"u_same\",\"session_key\":\"SUPER_SECRET_SK\"}");
                    } else if (q.contains("js_code=no_union")) {
                        replyJson(ex, 200, "{\"openid\":\"op2\",\"session_key\":\"SUPER_SECRET_SK\"}");
                    } else if (q.contains("js_code=rate_45011")) {
                        replyJson(ex, 200, "{\"errcode\":45011,\"errmsg\":\"freq limit\"}");
                    } else if (q.contains("js_code=fault_40001")) {
                        replyJson(ex, 200, "{\"errcode\":40001,\"errmsg\":\"invalid secret\"}");
                    } else if (q.contains("js_code=no_openid")) {
                        replyJson(ex, 200, "{\"errcode\":0,\"session_key\":\"SUPER_SECRET_SK\"}");
                    } else {
                        replyJson(ex, 200, "{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
                    }
                },
                "/sns/oauth2/access_token", ex ->
                        replyJson(ex, 200, "{\"openid\":\"op_app\",\"unionid\":\"u_same\"}")
        ));

        OAuthPolicy strict = new OAuthPolicy(true, true, true);
        try (WeChatMinigameProvider mg = new WeChatMinigameProvider("wx_mg", "sec_mg", base, strict);
             WeChatProvider app = new WeChatProvider("wx_app", "sec_app", base, strict);
             WeChatMinigameProvider fallback = new WeChatMinigameProvider("wx_mg", "sec_mg", base,
                     new OAuthPolicy(false, true, true))) {
            var mgId = mg.authenticateWithIdentity("ok_union");
            var appId = app.authenticateWithIdentity("app_code");
            assertThat(mgId).isPresent();
            assertThat(appId).isPresent();
            assertThat(mgId.get().account()).isEqualTo("wx_u_same").isEqualTo(appId.get().account());
            assertThat(mgId.get().openId()).isEqualTo("op1");
            assertThat(lastQuery.get()).contains("appid=wx_mg").contains("js_code=ok_union").contains("grant_type=authorization_code");

            // 45011 在 code2Session 上是按用户限频 -> 凭证无效（空）
            assertThat(mg.authenticate("rate_45011")).isEmpty();
            assertThat(mg.authenticate("bad_code")).isEmpty();

            // 渠道故障与缺 unionid / 缺 openid -> AuthProviderUnavailableException 且绝不带 session_key
            assertThatThrownBy(() -> mg.authenticate("fault_40001"))
                    .isInstanceOf(AuthProviderUnavailableException.class);
            assertThatThrownBy(() -> mg.authenticate("no_openid"))
                    .isInstanceOf(AuthProviderUnavailableException.class)
                    .hasMessageNotContaining("SUPER_SECRET_SK");
            assertThatThrownBy(() -> mg.authenticate("no_union"))
                    .isInstanceOf(AuthProviderUnavailableException.class)
                    .hasMessageNotContaining("SUPER_SECRET_SK");

            // 显式允许回落 openid
            assertThat(fallback.authenticate("no_union")).contains("wx_op2");
        }
    }

    @Test
    void 抖音小游戏带Json请求头_错误一律按凭证无效_支持unionid与回落() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        String base = startServer(Map.of(
                "/mgplatform/api/apps/jscode2session", ex -> {
                    contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
                    String q = ex.getRequestURI().getRawQuery();
                    if (q.contains("code=dy_ok")) {
                        replyJson(ex, 200, "{\"error\":0,\"openid\":\"dy_op\",\"unionid\":\"dy_u1\",\"session_key\":\"DY_SK\"}");
                    } else if (q.contains("code=dy_nounion")) {
                        replyJson(ex, 200, "{\"error\":0,\"openid\":\"dy_op2\",\"session_key\":\"DY_SK\"}");
                    } else if (q.contains("code=dy_err")) {
                        replyJson(ex, 200, "{\"error\":-1,\"errcode\":40018,\"errmsg\":\"bad code\"}");
                    } else {
                        replyJson(ex, 200, "{\"error\":0,\"session_key\":\"DY_SK\"}");
                    }
                }
        ));
        try (DouyinMinigameProvider strict = new DouyinMinigameProvider("tt_app", "tt_sec", base,
                new OAuthPolicy(true, true, true));
             DouyinMinigameProvider fallback = new DouyinMinigameProvider("tt_app", "tt_sec", base,
                     new OAuthPolicy(false, true, true))) {
            var ok = strict.authenticateWithIdentity("dy_ok");
            assertThat(ok).isPresent();
            assertThat(ok.get().account()).isEqualTo("dy_dy_u1");
            assertThat(ok.get().openId()).isEqualTo("dy_op");
            assertThat(contentType.get()).isEqualTo("application/json");

            assertThat(strict.authenticate("dy_err")).isEmpty();
            assertThatThrownBy(() -> strict.authenticate("dy_nounion"))
                    .isInstanceOf(AuthProviderUnavailableException.class)
                    .hasMessageNotContaining("DY_SK");
            assertThatThrownBy(() -> strict.authenticate("dy_no_openid"))
                    .isInstanceOf(AuthProviderUnavailableException.class);
            assertThat(fallback.authenticate("dy_nounion")).contains("dy_dy_op2");
        }
    }

    @Test
    void Apple与Google身份令牌RS256验签_算法混淆拒绝_一次性消费防重放与JWKS缓存限速() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp1 = kpg.generateKeyPair();
        KeyPair kp2 = kpg.generateKeyPair();
        KeyPairGenerator smallKpg = KeyPairGenerator.getInstance("RSA");
        smallKpg.initialize(1024);
        KeyPair smallKp = smallKpg.generateKeyPair();

        AtomicInteger jwksHits = new AtomicInteger();
        AtomicReference<String> jwksBody = new AtomicReference<>(
                "{\"keys\":[" + rsaJwk("k1", (RSAPublicKey) kp1.getPublic()) + ","
                        + rsaJwk("small", (RSAPublicKey) smallKp.getPublic()) + "]}");
        AtomicInteger jwksStatus = new AtomicInteger(200);
        CountDownLatch slowGate = new CountDownLatch(0);
        AtomicReference<CountDownLatch> blockFetch = new AtomicReference<>(slowGate);

        String base = startServer(Map.of(
                "/auth/keys", ex -> {
                    jwksHits.incrementAndGet();
                    try {
                        blockFetch.get().await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    replyJson(ex, jwksStatus.get(), jwksBody.get());
                },
                "/oauth2/v3/certs", ex -> {
                    jwksHits.incrementAndGet();
                    replyJson(ex, 200, jwksBody.get());
                }
        ));

        MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
        Set<String> usedKeys = ConcurrentHashMap.newKeySet();
        IdTokenReplayGuard replay = (key, ttl) -> usedKeys.add(key);
        RedisKeys keys = new RedisKeys(new GameNamespace("test"));

        IdTokenVerifier appleVerifier = new IdTokenVerifier("apple", List.of(IdTokenProvider.APPLE_ISSUER),
                "com.example.game", base + "/auth/keys", new OAuthHttp(Duration.ofSeconds(3)), clock,
                Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofSeconds(3));
        try (IdTokenProvider apple = new IdTokenProvider("apple", "apple_", appleVerifier, replay, keys);
             IdTokenProvider google = IdTokenProvider.google("12345.apps.googleusercontent.com", base, true, replay, keys)) {
            long now = clock.instant().getEpochSecond();

            // 1. 合法 Apple 令牌首次通过，二次重放按凭证无效拒绝（一次性消费）
            String appleJwt = signRs256(kp1.getPrivate(), "RS256", "k1",
                    Map.of("iss", IdTokenProvider.APPLE_ISSUER, "aud", "com.example.game",
                            "sub", "apple-sub-1", "iat", now - 10, "exp", now + 600));
            assertThat(apple.authenticate(appleJwt)).contains("apple_apple-sub-1");
            assertThat(apple.authenticate(appleJwt)).isEmpty();
            assertThat(jwksHits.get()).isEqualTo(1);

            // 2. 算法混淆（alg=none / HS256）、篡改、过期超 60s、不足 2048 位公钥、超 16 KiB 均拒绝
            String algNone = B64URL.encodeToString("{\"alg\":\"none\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8))
                    + "." + B64URL.encodeToString(("{\"iss\":\"https://appleid.apple.com\",\"aud\":\"com.example.game\",\"sub\":\"s\",\"iat\":"
                    + now + ",\"exp\":" + (now + 600) + "}").getBytes(StandardCharsets.UTF_8)) + ".sig";
            assertThat(apple.authenticate(algNone)).isEmpty();
            assertThat(apple.authenticate(appleJwt + "x")).isEmpty();
            String expiredJwt = signRs256(kp1.getPrivate(), "RS256", "k1",
                    Map.of("iss", IdTokenProvider.APPLE_ISSUER, "aud", "com.example.game",
                            "sub", "s2", "iat", now - 300, "exp", now - 61));
            assertThat(apple.authenticate(expiredJwt)).isEmpty();
            String smallKeyJwt = signRs256(smallKp.getPrivate(), "RS256", "small",
                    Map.of("iss", IdTokenProvider.APPLE_ISSUER, "aud", "com.example.game",
                            "sub", "s3", "iat", now, "exp", now + 600));
            assertThat(apple.authenticate(smallKeyJwt)).isEmpty();
            assertThat(apple.authenticate("a".repeat(17_000))).isEmpty();

            // 3. 未知 kid 距上次拉取不足 1 分钟不重拉 -> 按凭证无效拒绝；满 1 分钟后重拉换到 k2 通过
            jwksBody.set("{\"keys\":[" + rsaJwk("k1", (RSAPublicKey) kp1.getPublic()) + ","
                    + rsaJwk("k2", (RSAPublicKey) kp2.getPublic()) + "]}");
            String k2Jwt = signRs256(kp2.getPrivate(), "RS256", "k2",
                    Map.of("iss", IdTokenProvider.APPLE_ISSUER, "aud", "com.example.game",
                            "sub", "rotated-sub", "iat", now, "exp", now + 600));
            assertThat(apple.authenticate(k2Jwt)).isEmpty();
            assertThat(jwksHits.get()).isEqualTo(1);
            clock.advance(Duration.ofSeconds(61));
            assertThat(apple.authenticate(k2Jwt)).contains("apple_rotated-sub");
            assertThat(jwksHits.get()).isEqualTo(2);

            // 4. 缓存过 1h 新鲜期但未超 24h，即使上游 500 仍可凭旧公钥放行
            jwksStatus.set(500);
            clock.advance(Duration.ofHours(2));
            long later = clock.instant().getEpochSecond();
            String staleOkJwt = signRs256(kp2.getPrivate(), "RS256", "k2",
                    Map.of("iss", IdTokenProvider.APPLE_ISSUER, "aud", List.of("other", "com.example.game"),
                            "sub", "stale-sub", "iat", later, "exp", later + 600));
            assertThat(apple.authenticate(staleOkJwt)).contains("apple_stale-sub");

            // 5. Google 接受两种 iss（含与不含 https://）
            long realNow = Instant.now().getEpochSecond();
            String g1 = signRs256(kp1.getPrivate(), "RS256", "k1",
                    Map.of("iss", "accounts.google.com", "aud", "12345.apps.googleusercontent.com",
                            "sub", "g-user-1", "iat", realNow - 5, "exp", realNow + 600));
            String g2 = signRs256(kp1.getPrivate(), "RS256", "k1",
                    Map.of("iss", "https://accounts.google.com", "aud", "12345.apps.googleusercontent.com",
                            "sub", "g-user-2", "iat", realNow - 5, "exp", realNow + 600));
            assertThat(google.authenticate(g1)).contains("google_g-user-1");
            assertThat(google.authenticate(g2)).contains("google_g-user-2");

            // 6. 重放登记存储故障 -> fail-closed 抛 AuthProviderUnavailableException
            IdTokenProvider brokenReplay = new IdTokenProvider("apple", "apple_", appleVerifier,
                    (k, ttl) -> { throw new RuntimeException("redis down"); }, keys);
            String jwt3 = signRs256(kp2.getPrivate(), "RS256", "k2",
                    Map.of("iss", IdTokenProvider.APPLE_ISSUER, "aud", "com.example.game",
                            "sub", "sub-fail", "iat", later, "exp", later + 600));
            assertThatThrownBy(() -> brokenReplay.authenticate(jwt3))
                    .isInstanceOf(AuthProviderUnavailableException.class);
        }

        // 7. 并发请求共用一次 JWKS 拉取（single-flight）
        jwksHits.set(0);
        jwksStatus.set(200);
        CountDownLatch hold = new CountDownLatch(1);
        blockFetch.set(hold);
        IdTokenVerifier sfVerifier = new IdTokenVerifier("apple", List.of(IdTokenProvider.APPLE_ISSUER),
                "com.example.game", base + "/auth/keys", new OAuthHttp(Duration.ofSeconds(3)), clock,
                Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofSeconds(3));
        try (IdTokenProvider sfApple = new IdTokenProvider("apple", "apple_", sfVerifier, null, keys)) {
            long t = clock.instant().getEpochSecond();
            String tok = signRs256(kp1.getPrivate(), "RS256", "k1",
                    Map.of("iss", IdTokenProvider.APPLE_ISSUER, "aud", "com.example.game",
                            "sub", "sf-sub", "iat", t, "exp", t + 600));
            var pool = Executors.newFixedThreadPool(4);
            try {
                Future<Optional<String>> f1 = pool.submit(() -> sfApple.authenticate(tok));
                Future<Optional<String>> f2 = pool.submit(() -> sfApple.authenticate(tok));
                Future<Optional<String>> f3 = pool.submit(() -> sfApple.authenticate(tok));
                Thread.sleep(80);
                hold.countDown();
                assertThat(f1.get(3, TimeUnit.SECONDS)).contains("apple_sf-sub");
                assertThat(f2.get(3, TimeUnit.SECONDS)).contains("apple_sf-sub");
                assertThat(f3.get(3, TimeUnit.SECONDS)).contains("apple_sf-sub");
                assertThat(jwksHits.get()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void 官方通行证只收idToken_先核类型再核2小时上限_拒绝空白与超191字节() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String base = startServer(Map.of(
                "/.well-known/jwks", ex -> replyJson(ex, 200, "{\"keys\":[" + rsaJwk("p1", (RSAPublicKey) kp.getPublic()) + "]}")
        ));
        Set<String> used = ConcurrentHashMap.newKeySet();
        RedisKeys keys = new RedisKeys(new GameNamespace("test"));
        long now = Instant.now().getEpochSecond();
        try (PassportProvider passport = new PassportProvider(base, "game-client", null, true, (k, ttl) -> used.add(k), keys)) {
            // 1. 合法 id-token -> passport_<sub> 且带 identityIssuedAt = iat，二次重放拒绝
            String valid = signRs256(kp.getPrivate(), "RS256", "p1",
                    Map.of("iss", base, "aud", "game-client", "sub", "uuid-1234",
                            "tokenType", "id-token", "iat", now - 20, "exp", now + 1800));
            var id = passport.authenticateWithIdentity(valid);
            assertThat(id).isPresent();
            assertThat(id.get().account()).isEqualTo("passport_uuid-1234");
            assertThat(id.get().identityIssuedAt()).isEqualTo(now - 20);
            assertThat(passport.authenticateWithIdentity(valid)).isEmpty();

            // 2. access-token / refresh-token（即便有效期 168h）也只按凭证无效拒绝，不报渠道配置故障
            String refreshTok = signRs256(kp.getPrivate(), "RS256", "p1",
                    Map.of("iss", base, "aud", "game-client", "sub", "uuid-1234",
                            "tokenType", "refresh-token", "iat", now, "exp", now + 168L * 3600L));
            assertThat(passport.authenticate(refreshTok)).isEmpty();

            // 3. id-token 有效期超 2h -> 配置故障 AuthProviderUnavailableException
            String tooLong = signRs256(kp.getPrivate(), "RS256", "p1",
                    Map.of("iss", base, "aud", "game-client", "sub", "uuid-1234",
                            "tokenType", "id-token", "iat", now, "exp", now + 7201));
            assertThatThrownBy(() -> passport.authenticate(tooLong))
                    .isInstanceOf(AuthProviderUnavailableException.class);

            // 4. sub 含空白或总长度 > 191 字节 -> 凭证无效
            String withSpace = signRs256(kp.getPrivate(), "RS256", "p1",
                    Map.of("iss", base, "aud", "game-client", "sub", "bad sub",
                            "tokenType", "id-token", "iat", now, "exp", now + 600));
            assertThat(passport.authenticate(withSpace)).isEmpty();
            String tooLongSub = signRs256(kp.getPrivate(), "RS256", "p1",
                    Map.of("iss", base, "aud", "game-client", "sub", "x".repeat(183),
                            "tokenType", "id-token", "iat", now, "exp", now + 600));
            assertThat(passport.authenticate(tooLongSub)).isEmpty();
        }
    }

    @Test
    void Apple授权撤销签ES256换票并撤销_invalidGrant返回无效授权码() throws Exception {
        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ec = ecGen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(ec.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        AtomicReference<String> lastTokenForm = new AtomicReference<>();
        AtomicReference<String> lastRevokeForm = new AtomicReference<>();
        String base = startServer(Map.of(
                "/auth/token", ex -> {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    lastTokenForm.set(body);
                    if (body.contains("code=valid_code")) {
                        replyJson(ex, 200, "{\"refresh_token\":\"rt_secret_123\"}");
                    } else if (body.contains("code=bad_grant")) {
                        replyJson(ex, 400, "{\"error\":\"invalid_grant\"}");
                    } else {
                        replyJson(ex, 400, "{\"error\":\"invalid_client\"}");
                    }
                },
                "/auth/revoke", ex -> {
                    lastRevokeForm.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    replyJson(ex, 200, "{}");
                }
        ));

        try (AppleRevokerClient client = new AppleRevokerClient("com.game.ios", "TEAM123", "KEY456", pem, base, true)) {
            // 验证 client_secret ES256 签名能被对应 EC 公钥验过
            String jwt = client.signClientSecret();
            String[] parts = jwt.split("\\.");
            assertThat(parts).hasSize(3);
            Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
            verify.initVerify(ec.getPublic());
            verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

            String grant = client.exchangeCode("valid_code");
            assertThat(lastTokenForm.get()).contains("grant_type=authorization_code");
            client.revoke(grant);
            assertThat(lastRevokeForm.get()).contains("token=rt_secret_123").contains("token_type_hint=refresh_token");

            assertThatThrownBy(() -> client.exchangeCode("bad_grant"))
                    .isInstanceOf(AccountDeletionService.AppleRevoker.InvalidAppleCodeException.class);
            assertThatThrownBy(() -> client.exchangeCode("bad_client"))
                    .isInstanceOf(AuthProviderUnavailableException.class);
        }

        // 非法 PEM 拒绝构造
        assertThatThrownBy(() -> new AppleRevokerClient("c", "t", "k", "not-a-pem", base, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 启动门禁_微信App与小游戏同AppId拒启_生产通行证缺管理凭据或同ClientId拒启() throws Exception {
        LoginProperties prod = new LoginProperties(LoginProperties.MODE_PROD, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        RedisProperties redisProps = new RedisProperties(null, null, null, null, null, null, null);
        RedisKeys keys = new RedisKeys(new GameNamespace("test"));

        // 1. 微信 App 与微信小游戏 AppId 相同拒启
        LoginAuthProperties dupWechat = new LoginAuthProperties(null, null,
                new LoginAuthProperties.WeChat("same_wx", null, true), null, null,
                new LoginAuthProperties.WeChatMinigame("same_wx", null, true), null, null, null, null);
        Map<String, String> env = new HashMap<>();
        env.put(LoginAuthProperties.WECHAT_SECRET_ENV, "s1");
        env.put(LoginAuthProperties.WECHAT_MINIGAME_SECRET_ENV, "s2");
        assertThatThrownBy(() -> invokeBuildProviders(dupWechat, redisProps, prod, keys, env::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app-id");

        // 2. 生产模式通行证缺管理凭据拒启
        LoginAuthProperties passportNoAdmin = new LoginAuthProperties(null, null, null, null, null, null, null, null, null,
                new LoginAuthProperties.Passport("https://id.example.com", "pub-app", null, null, null));
        assertThatThrownBy(() -> invokeBuildProviders(passportNoAdmin, redisProps, prod, keys, k -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin-client-id");

        // 3. 通行证 admin-client-id == client-id 拒启
        LoginAuthProperties passportSameClient = new LoginAuthProperties(null, null, null, null, null, null, null, null, null,
                new LoginAuthProperties.Passport("https://id.example.com", "pub-app", null, "pub-app", null));
        assertThatThrownBy(() -> invokeBuildProviders(passportSameClient, redisProps, prod, keys,
                k -> "secret-val"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id");

        // 4. Google client-id 不以 .apps.googleusercontent.com 结尾拒启
        assertThatThrownBy(() -> IdTokenProvider.google("bad-client-id", null, false, null, keys))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void invokeBuildProviders(LoginAuthProperties auth, RedisProperties redisProps, LoginProperties props,
                                             RedisKeys keys, java.util.function.UnaryOperator<String> env) throws Exception {
        var m = LoginConfiguration.class.getDeclaredMethod("buildExternalAuthProviders",
                LoginAuthProperties.class, RedisProperties.class, LoginProperties.class,
                org.redisson.api.RedissonClient.class, RedisKeys.class, java.util.function.UnaryOperator.class);
        m.setAccessible(true);
        try {
            ExternalAuthProviders p = (ExternalAuthProviders) m.invoke(null, auth, redisProps, props, null, keys, env);
            p.close();
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private static String rsaJwk(String kid, RSAPublicKey pub) {
        String n = B64URL.encodeToString(unsignedBytes(pub.getModulus()));
        String e = B64URL.encodeToString(unsignedBytes(pub.getPublicExponent()));
        return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + kid + "\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"}";
    }

    private static byte[] unsignedBytes(BigInteger bi) {
        byte[] raw = bi.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] stripped = new byte[raw.length - 1];
            System.arraycopy(raw, 1, stripped, 0, stripped.length);
            return stripped;
        }
        return raw;
    }

    private static String signRs256(PrivateKey key, String alg, String kid, Map<String, Object> claims) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String header = B64URL.encodeToString(mapper.writeValueAsBytes(Map.of("alg", alg, "kid", kid)));
        String payload = B64URL.encodeToString(mapper.writeValueAsBytes(claims));
        String input = header + "." + payload;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + B64URL.encodeToString(sig.sign());
    }

    private static final class MutableClock extends Clock {
        private Instant current;
        MutableClock(Instant initial) { this.current = initial; }
        void advance(Duration d) { this.current = this.current.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
