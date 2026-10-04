package com.game.gateway.login;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * {@code POST /api/login} 应答（客户端契约，恒 HTTP 200，结果在 {@code code}；没有值的字段不出现）：
 * 0 成功（角色列表与令牌）；100 开服限流排队（{@code message="QUEUEING"}、{@code retry_after_ms}、{@code queue_pos}，过一会原样重发）；
 * 429 限流（{@code message} = {@code IP_RATE_LIMIT} / {@code ACCOUNT_COOLDOWN}）；101 排队超时只在契约里，基线与 Java 都不产生；
 * 401 认证被拒（login 回的任何业务错误，message 带 {@code upstream_err=<tip>}）；500 login 不可用或内部错误。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HttpLoginResponse(int code, String message, Long retryAfterMs, Long queuePos, List<PlayerInfo> players,
                                String accessToken, String refreshToken, Long accessTokenExpire, Long refreshTokenExpire) {

    public static final int CODE_OK = 0;
    public static final int CODE_QUEUEING = 100;
    public static final int CODE_RATE_LIMITED = 429;
    public static final int CODE_AUTH_REJECTED = 401;
    public static final int CODE_INTERNAL = 500;

    /** 角色摘要。level 恒为 0：login 的角色列表（loginpb.AccountSimplePlayer）不带等级（基线同）。 */
    public record PlayerInfo(long playerId, String name, int level) {
    }

    public static HttpLoginResponse ok(List<PlayerInfo> players, String accessToken, String refreshToken,
                                       long accessTokenExpire, long refreshTokenExpire) {
        return new HttpLoginResponse(CODE_OK, null, null, null, players, accessToken, refreshToken, accessTokenExpire,
                refreshTokenExpire);
    }

    /** 开服限流排队（同基线 LoginResponse.queueing）。 */
    public static HttpLoginResponse queueing(long retryAfterMs, long queuePos) {
        return new HttpLoginResponse(CODE_QUEUEING, "QUEUEING", retryAfterMs, queuePos, null, null, null, null, null);
    }

    public static HttpLoginResponse error(int code, String message) {
        return new HttpLoginResponse(code, message, null, null, null, null, null, null, null);
    }
}
