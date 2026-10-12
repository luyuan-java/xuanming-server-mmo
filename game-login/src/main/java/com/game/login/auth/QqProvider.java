package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QQ 互联认证（同 mmorpg {@code QQProvider}）：客户端 SDK 拿到的 access_token（不是 code）作为 auth_token，
 * 这边调 {@code GET {endpoint}/oauth2.0/me?access_token&unionid=1&fmt=json}（兼容剥掉 JSONP 外壳）重新取 openid / unionid，
 * 从不信客户端给的 openid；error 非 0、openid 为空、client_id 不等于本应用 app-id 都失败。账号 = {@code qq_} + unionid（没有就用 openid）。
 */
public final class QqProvider implements ExternalAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(QqProvider.class);
    static final String DEFAULT_ENDPOINT = "https://graph.qq.com";
    private static final String NAME = "QQ 认证";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern JSONP = Pattern.compile("(?s)callback\\s*\\(\\s*(\\{.*\\})\\s*\\)\\s*;?");

    private final String appId;
    private final String endpoint;
    private final OAuthHttp http = new OAuthHttp();

    /** @param endpoint 空 = 生产地址 */
    public QqProvider(String appId, String endpoint) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("QQ 认证需要 app-id");
        }
        this.appId = appId;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : OAuthHttp.requireEndpoint(endpoint, NAME);
    }

    @Override
    public Optional<String> authenticate(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", accessToken);
        query.put("unionid", "1");
        query.put("fmt", "json");
        String body = http.get(endpoint, "/oauth2.0/me", query).strip();
        Matcher jsonp = JSONP.matcher(body);
        if (jsonp.find()) {
            body = jsonp.group(1);
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            log.warn("QQ 认证应答不是合法 JSON，按认证失败处理");
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        int error = root.path("error").asInt(0);
        if (error != 0) {
            log.info("QQ 认证被拒 error={}", error);
            return Optional.empty();
        }
        String openid = root.path("openid").asText("");
        if (openid.isEmpty()) {
            return Optional.empty();
        }
        if (!appId.equals(root.path("client_id").asText(""))) {
            log.warn("QQ 认证 client_id 与本应用不符，按认证失败处理");
            return Optional.empty();
        }
        String unionid = root.path("unionid").asText("");
        return Optional.of("qq_" + (unionid.isEmpty() ? openid : unionid));
    }
}
