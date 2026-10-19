package com.game.login.auth;

/**
 * 微信 / QQ 认证的 unionid 与接口地址策略（同 mmorpg {@code AuthProviders.*.RequireUnionId / RequestUnionId} 与 Endpoint 门禁）。
 *
 * @param requireUnionId    拿不到 unionid 时拒绝登录（抛 {@link AuthProviderUnavailableException}），不回落 openid。
 *                          openid 按应用区分，回落后同一玩家在第二个应用里会变成另一个账号且无法合并；生产应为 true
 * @param requestUnionId    QQ 请求 {@code /oauth2.0/me} 时带 {@code unionid=1}（微信不用这一项）；为 false 时 requireUnionId 也必须为 false
 * @param allowHttpEndpoint 接口地址允许 {@code http://}（只给 {@code game.login.mode=dev} 的本地假服务）；否则只接受 {@code https://}
 */
public record OAuthPolicy(boolean requireUnionId, boolean requestUnionId, boolean allowHttpEndpoint) {

    /** 生产缺省：要求 unionid、请求 unionid、只接受 https。 */
    public static final OAuthPolicy PRODUCTION = new OAuthPolicy(true, true, false);

    public OAuthPolicy {
        if (requireUnionId && !requestUnionId) {
            throw new IllegalArgumentException("不请求 unionid 就永远拿不到它：request-union-id=false 时 require-union-id 也必须为 false");
        }
    }
}
