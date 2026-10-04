package com.game.login.token;

/**
 * 令牌背后的数据（存在 Redis 里，JSON）。
 *
 * @param authType  签发这对令牌时的认证方式（refresh 轮换沿用它）
 * @param deviceId  设备号（目前没有来源，恒为空，同基线）
 * @param createdAt 签发的 Unix 秒
 */
public record TokenData(String account, String authType, String deviceId, long createdAt) {
}
