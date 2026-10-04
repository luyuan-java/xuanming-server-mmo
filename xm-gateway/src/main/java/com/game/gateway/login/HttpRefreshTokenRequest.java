package com.game.gateway.login;

/** {@code POST /api/refresh-token} 请求体：当前的 refresh token（成功即作废、换新的一对）。 */
public record HttpRefreshTokenRequest(String refreshToken) {
}
