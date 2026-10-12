package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信开放平台认证（同 mmorpg {@code WeChatProvider}）：客户端 SDK 拿到的 OAuth {@code code} 作为 auth_token，
 * 这边调 {@code GET {endpoint}/sns/oauth2/access_token?appid&secret&code&grant_type=authorization_code} 换 openid / unionid；
 * errcode 非 0 或 openid 为空即失败。账号 = {@code wx_} + unionid（没有就用 openid）。
 * endpoint 缺省 {@code https://api.weixin.qq.com}，可指向本地假服务（同基线 sandbox_mock 的用法）。
 */
public final class WeChatProvider implements ExternalAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(WeChatProvider.class);
    static final String DEFAULT_ENDPOINT = "https://api.weixin.qq.com";
    private static final String NAME = "微信认证";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String appId;
    private final String appSecret;
    private final String endpoint;
    private final OAuthHttp http = new OAuthHttp();

    /**
     * @param appSecret 只从环境变量注入
     * @param endpoint  空 = 生产地址
     */
    public WeChatProvider(String appId, String appSecret, String endpoint) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("微信认证需要 app-id 与 app-secret（环境变量）");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : OAuthHttp.requireEndpoint(endpoint, NAME);
    }

    @Override
    public Optional<String> authenticate(String code) {
        if (code == null || code.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("appid", appId);
        query.put("secret", appSecret);
        query.put("code", code);
        query.put("grant_type", "authorization_code");
        String body = http.get(endpoint, "/sns/oauth2/access_token", query);
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            log.warn("微信认证应答不是合法 JSON，按认证失败处理");
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        int errcode = root.path("errcode").asInt(0);
        if (errcode != 0) {
            log.info("微信认证被拒 errcode={}", errcode);
            return Optional.empty();
        }
        String openid = root.path("openid").asText("");
        if (openid.isEmpty()) {
            return Optional.empty();
        }
        String unionid = root.path("unionid").asText("");
        return Optional.of("wx_" + (unionid.isEmpty() ? openid : unionid));
    }
}
