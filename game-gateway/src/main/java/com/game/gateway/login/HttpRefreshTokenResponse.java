package com.game.gateway.login;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code POST /api/refresh-token} 应答（恒 HTTP 200）：0 成功（新的一对）；401 refresh 无效 / 过期 / 已用过（客户端走完整重登）；
 * 500 login 不可用或内部错误。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HttpRefreshTokenResponse(int code, String message, String accessToken, String refreshToken,
                                       Long accessTokenExpire, Long refreshTokenExpire) {

    public static HttpRefreshTokenResponse ok(String accessToken, String refreshToken, long accessTokenExpire,
                                              long refreshTokenExpire) {
        return new HttpRefreshTokenResponse(HttpLoginResponse.CODE_OK, null, accessToken, refreshToken, accessTokenExpire,
                refreshTokenExpire);
    }

    public static HttpRefreshTokenResponse error(int code, String message) {
        return new HttpRefreshTokenResponse(code, message, null, null, null, null);
    }
}
