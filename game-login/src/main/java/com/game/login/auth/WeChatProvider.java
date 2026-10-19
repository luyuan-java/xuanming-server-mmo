package com.game.login.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信开放平台认证（同 mmorpg {@code WeChatProvider}）：客户端 SDK 拿到的 OAuth {@code code} 作为 auth_token，
 * 这边调 {@code GET {endpoint}/sns/oauth2/access_token?appid&secret&code&grant_type=authorization_code} 换 openid / unionid。
 * 账号 = {@code wx_} + unionid；拿不到 unionid 时按 {@link OAuthPolicy#requireUnionId()} 拒绝（缺省）或回落 openid。
 * endpoint 缺省 {@code https://api.weixin.qq.com}，可指向本地假服务（同基线 sandbox_mock 的用法，只在 dev 模式允许 http）。
 *
 * <p>失败分两类（同 mmorpg）：玩家凭证无效（code 无效 / 已被使用等 errcode）返回空，登录回 2000；渠道或本服务渠道配置的问题
 * （HTTP 失败、坏 JSON、errcode=0 却没有 openid、{@link #PROVIDER_SIDE_ERRCODES}、缺必需的 unionid）抛
 * {@link AuthProviderUnavailableException}，登录回故障码。
 */
public final class WeChatProvider implements ExternalAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(WeChatProvider.class);
    static final String DEFAULT_ENDPOINT = "https://api.weixin.qq.com";
    private static final String NAME = "微信认证";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 指向本服务的渠道配置或微信自身状态、而不是玩家凭证的 errcode（与 mmorpg {@code wechatErrCodeIsProviderSide} 同一份清单）：
     * -1 系统繁忙；40001 / 40125 AppSecret 无效；40013 AppId 无效；41002 / 41004 缺 AppId / AppSecret；40164 出口 IP 不在白名单；
     * 45009 / 45011 接口调用量超限。其余 errcode 按凭证无效处理：拿不准的不算故障（把玩家侧拒绝误标成故障会刷满屏假告警）。
     */
    static final Set<Integer> PROVIDER_SIDE_ERRCODES = Set.of(-1, 40001, 40013, 40125, 40164, 41002, 41004, 45009, 45011);

    private final String appId;
    private final String appSecret;
    private final String endpoint;
    private final boolean requireUnionId;
    private final OAuthHttp http = new OAuthHttp();

    /**
     * @param appSecret 只从环境变量注入
     * @param endpoint  空 = 生产地址
     * @param policy    unionid 与地址策略（微信不用 requestUnionId）
     */
    public WeChatProvider(String appId, String appSecret, String endpoint, OAuthPolicy policy) {
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("微信认证需要 app-id 与 app-secret（环境变量）");
        }
        if (!appSecret.equals(appSecret.strip())) {
            // 常见于 Secret 带进的行尾换行：原样发给微信只会得到「AppSecret 无效」，而且看日志查不出来
            throw new IllegalArgumentException("微信认证的 app-secret 含首尾空白（常见于 Secret 带进的换行）");
        }
        this.appId = appId;
        this.appSecret = appSecret;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT
                : OAuthHttp.requireEndpoint(endpoint, NAME, policy.allowHttpEndpoint());
        this.requireUnionId = policy.requireUnionId();
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
            throw new AuthProviderUnavailableException("微信认证应答不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new AuthProviderUnavailableException("微信认证应答不是 JSON 对象");
        }
        int errcode = root.path("errcode").asInt(0);
        if (errcode != 0) {
            if (PROVIDER_SIDE_ERRCODES.contains(errcode)) {
                throw new AuthProviderUnavailableException("微信认证渠道侧错误 errcode=" + errcode);
            }
            log.info("微信认证被拒 errcode={}", errcode);
            return Optional.empty();
        }
        String openid = root.path("openid").asText("");
        if (openid.isEmpty()) {
            throw new AuthProviderUnavailableException("微信认证应答 errcode=0 却没有 openid");
        }
        String unionid = root.path("unionid").asText("");
        if (unionid.isEmpty()) {
            if (requireUnionId) {
                throw new AuthProviderUnavailableException("微信认证没有返回 unionid（应用未绑定开放平台主体；require-union-id=true）");
            }
            return Optional.of("wx_" + openid);
        }
        return Optional.of("wx_" + unionid);
    }
}
