package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抖音小游戏认证（{@code auth_type = "douyin_minigame"}，对齐 mmorpg {@code DouyinMiniGameProvider}）：
 * 调 {@code GET {endpoint}/mgplatform/api/apps/jscode2session?appid&secret&code}（带 {@code Content-Type: application/json}）
 * 换 openid / unionid，账号名 {@code dy_<unionid>}（显式允许回落时 {@code dy_<openid>}）。
 *
 * <p>只接收 {@code code}、不接收 {@code anonymous_code}；不解析、不记录 {@code session_key} / {@code errmsg}；
 * {@code error != 0 || errcode != 0} 一律按凭证无效拒绝。
 */
public final class DouyinMinigameProvider implements ExternalAuthProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DouyinMinigameProvider.class);
    public static final String AUTH_TYPE = "douyin_minigame";
    static final String DEFAULT_ENDPOINT = "https://minigame.zijieapi.com";
    private static final String NAME = "抖音小游戏认证";
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String appId;
    private final String appSecret;
    private final String endpoint;
    private final boolean requireUnionId;
    private final OAuthHttp http;

    public DouyinMinigameProvider(String appId, String appSecret, String endpoint, OAuthPolicy policy) {
        this(appId, appSecret, endpoint, policy, new OAuthHttp());
    }

    DouyinMinigameProvider(String appId, String appSecret, String endpoint, OAuthPolicy policy, OAuthHttp http) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("抖音小游戏认证需要 app-id 与 app-secret（环境变量）");
        }
        if (!appSecret.equals(appSecret.strip())) {
            throw new IllegalArgumentException("抖音小游戏认证的 app-secret 含首尾空白");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT
                : OAuthHttp.requireEndpoint(endpoint, NAME, policy.allowHttpEndpoint());
        this.requireUnionId = policy.requireUnionId();
        this.http = http;
    }

    @Override
    public Optional<String> authenticate(String code) {
        return authenticateWithIdentity(code).map(VerifiedIdentity::account);
    }

    @Override
    public Optional<VerifiedIdentity> authenticateWithIdentity(String code) {
        if (code == null || code.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("appid", appId);
        query.put("secret", appSecret);
        query.put("code", code);
        String body = http.get(endpoint, "/mgplatform/api/apps/jscode2session", query, JSON_HEADERS);
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            throw new AuthProviderUnavailableException("抖音小游戏认证应答不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new AuthProviderUnavailableException("抖音小游戏认证应答不是 JSON 对象");
        }
        int error = root.path("error").asInt(0);
        int errcode = root.path("errcode").asInt(0);
        if (error != 0 || errcode != 0) {
            log.info("douyin minigame auth rejected error={} errcode={}", error, errcode);
            return Optional.empty();
        }
        String openid = root.path("openid").asText("");
        if (openid.isEmpty()) {
            throw new AuthProviderUnavailableException("抖音小游戏认证应答 error=0 却没有 openid");
        }
        String unionid = root.path("unionid").asText("");
        if (unionid.isEmpty()) {
            if (requireUnionId) {
                throw new AuthProviderUnavailableException("抖音小游戏认证没有返回 unionid（require-union-id=true）");
            }
            return Optional.of(new VerifiedIdentity("dy_" + openid, openid));
        }
        return Optional.of(new VerifiedIdentity("dy_" + unionid, openid));
    }

    @Override
    public void close() {
        http.close();
    }
}
