package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信小游戏认证（{@code auth_type = "wechat_minigame"}，对齐 mmorpg {@code WeChatMiniGameProvider}）：
 * 调 {@code GET {endpoint}/sns/jscode2session?appid&secret&js_code&grant_type=authorization_code} 换 openid / unionid，
 * 账号名与 App 微信共用 {@code wx_} 命名空间。
 *
 * <p>不读取、不记录 {@code session_key} 与 {@code errmsg}；errcode 沿用 {@link WeChatProvider#PROVIDER_SIDE_ERRCODES}，
 * 但 {@code 45011} 在 code2Session 属于按用户限频，按凭证无效拒绝而非渠道故障。
 */
public final class WeChatMinigameProvider implements ExternalAuthProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WeChatMinigameProvider.class);
    public static final String AUTH_TYPE = "wechat_minigame";
    static final String DEFAULT_ENDPOINT = "https://api.weixin.qq.com";
    private static final String NAME = "微信小游戏认证";
    private static final int ERRCODE_USER_RATE_LIMIT = 45011;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String appId;
    private final String appSecret;
    private final String endpoint;
    private final boolean requireUnionId;
    private final OAuthHttp http;

    public WeChatMinigameProvider(String appId, String appSecret, String endpoint, OAuthPolicy policy) {
        this(appId, appSecret, endpoint, policy, new OAuthHttp());
    }

    WeChatMinigameProvider(String appId, String appSecret, String endpoint, OAuthPolicy policy, OAuthHttp http) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("微信小游戏认证需要 app-id 与 app-secret（环境变量）");
        }
        if (!appSecret.equals(appSecret.strip())) {
            throw new IllegalArgumentException("微信小游戏认证的 app-secret 含首尾空白");
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
        query.put("js_code", code);
        query.put("grant_type", "authorization_code");
        String body = http.get(endpoint, "/sns/jscode2session", query);
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            throw new AuthProviderUnavailableException("微信小游戏认证应答不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new AuthProviderUnavailableException("微信小游戏认证应答不是 JSON 对象");
        }
        int errcode = root.path("errcode").asInt(0);
        if (errcode != 0) {
            if (isProviderSideErrCode(errcode)) {
                throw new AuthProviderUnavailableException("微信小游戏认证渠道侧错误 errcode=" + errcode);
            }
            log.info("wechat minigame auth rejected errcode={}", errcode);
            return Optional.empty();
        }
        String openid = root.path("openid").asText("");
        if (openid.isEmpty()) {
            throw new AuthProviderUnavailableException("微信小游戏认证应答 errcode=0 却没有 openid");
        }
        String unionid = root.path("unionid").asText("");
        if (unionid.isEmpty()) {
            if (requireUnionId) {
                throw new AuthProviderUnavailableException("微信小游戏认证没有返回 unionid（require-union-id=true）");
            }
            return Optional.of(new VerifiedIdentity("wx_" + openid, openid));
        }
        return Optional.of(new VerifiedIdentity("wx_" + unionid, openid));
    }

    static boolean isProviderSideErrCode(int errcode) {
        return errcode != ERRCODE_USER_RATE_LIMIT && WeChatProvider.PROVIDER_SIDE_ERRCODES.contains(errcode);
    }

    @Override
    public void close() {
        http.close();
    }
}
