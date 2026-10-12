package com.game.login.token;

/**
 * 一对新签的令牌。
 *
 * @param accessExpireSeconds  access token 过期的 Unix 秒
 * @param refreshExpireSeconds refresh token 过期的 Unix 秒
 */
public record TokenPair(String accessToken, String refreshToken, long accessExpireSeconds, long refreshExpireSeconds) {
}
