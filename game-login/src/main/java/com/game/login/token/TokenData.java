package com.game.login.token;

/**
 * 令牌背后的数据（存在 Redis 里，JSON）。
 *
 * @param authType  签发这对令牌时的认证方式（refresh 轮换沿用它）
 * @param deviceId  设备号（目前没有来源，恒为空，同基线）
 * @param createdAt 签发的 Unix 秒
 * @param familyId  这对令牌所属的家族（一次完整认证及其全部轮换后继；16 字节家族号的 base64url，22 字符），与令牌自身嵌的家族号相同，
 *                  读出时核对，不一致（含空串）按损坏处理（基线 {@code TokenData.FamilyID}）
 */
public record TokenData(String account, String authType, String deviceId, long createdAt, String familyId) {
}
