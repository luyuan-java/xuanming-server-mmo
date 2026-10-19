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
 * 这边调 {@code GET {endpoint}/oauth2.0/me?access_token&fmt=json[&unionid=1]}（兼容剥掉 JSONP 外壳）重新取 openid / unionid，
 * 从不信客户端给的 openid。账号 = {@code qq_} + unionid；拿不到 unionid 时按 {@link OAuthPolicy#requireUnionId()} 拒绝（缺省）
 * 或回落 openid；是否带 {@code unionid=1} 由 {@link OAuthPolicy#requestUnionId()} 决定。
 *
 * <p>失败分两类（同 mmorpg）：error 非 0、client_id 不等于本应用 app-id（令牌是发给别的应用的）返回空，登录回 2000；
 * HTTP 失败、坏 JSON、error=0 却没有 openid、缺必需的 unionid 抛 {@link AuthProviderUnavailableException}，登录回故障码。
 * QQ 的 error 码没有可靠的「哪些是应用侧故障」清单，拿不准的一律按凭证无效。
 */
public final class QqProvider implements ExternalAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(QqProvider.class);
    static final String DEFAULT_ENDPOINT = "https://graph.qq.com";
    private static final String NAME = "QQ 认证";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern JSONP = Pattern.compile("(?s)callback\\s*\\(\\s*(\\{.*\\})\\s*\\)\\s*;?");

    private final String appId;
    private final String endpoint;
    private final boolean requireUnionId;
    private final boolean requestUnionId;
    private final OAuthHttp http = new OAuthHttp();

    /**
     * @param endpoint 空 = 生产地址
     * @param policy   unionid 与地址策略
     */
    public QqProvider(String appId, String endpoint, OAuthPolicy policy) {
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("QQ 认证需要 app-id");
        }
        this.appId = appId;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT
                : OAuthHttp.requireEndpoint(endpoint, NAME, policy.allowHttpEndpoint());
        this.requireUnionId = policy.requireUnionId();
        this.requestUnionId = policy.requestUnionId();
    }

    @Override
    public Optional<String> authenticate(String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("access_token", accessToken);
        if (requestUnionId) {
            query.put("unionid", "1");
        }
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
            throw new AuthProviderUnavailableException("QQ 认证应答不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new AuthProviderUnavailableException("QQ 认证应答不是 JSON 对象");
        }
        int error = root.path("error").asInt(0);
        if (error != 0) {
            log.info("QQ 认证被拒 error={}", error);
            return Optional.empty();
        }
        String openid = root.path("openid").asText("");
        if (openid.isEmpty()) {
            throw new AuthProviderUnavailableException("QQ 认证应答 error=0 却没有 openid");
        }
        if (!appId.equals(root.path("client_id").asText(""))) {
            log.warn("QQ 认证 client_id 与本应用不符，按认证失败处理");
            return Optional.empty();
        }
        String unionid = root.path("unionid").asText("");
        if (unionid.isEmpty()) {
            if (requireUnionId) {
                throw new AuthProviderUnavailableException("QQ 认证没有返回 unionid（应用没有 unionid 权限；require-union-id=true）");
            }
            return Optional.of("qq_" + openid);
        }
        return Optional.of("qq_" + unionid);
    }
}
