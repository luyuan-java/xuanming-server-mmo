package com.game.login.token;

/**
 * 令牌背后的数据（存在 Redis 里，JSON）。
 *
 * @param authType  签发这对令牌时的认证方式（refresh 轮换沿用它）
 * @param deviceId  设备号（目前没有来源，恒为空，同基线）
 * @param createdAt 签发的 Unix 秒
 * @param familyId  这对令牌所属的家族（一次完整认证及其全部轮换后继；16 字节家族号的 base64url，22 字符）。
 *                  升级前签的令牌为空串：它们的 access 不查家族，refresh 在下一次轮换时收编进新家族（基线 {@code TokenData.FamilyID}）
 */
public record TokenData(String account, String authType, String deviceId, long createdAt, String familyId) {
}
