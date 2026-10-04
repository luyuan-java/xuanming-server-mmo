package com.game.team.store;

/**
 * 被邀请人反查 ZSET {@code xm:{team}:invite:<pid>} 里的一项（基线 store.go:408-414）。
 *
 * @param teamId     队伍（ZSET 成员，无符号）
 * @param expireAtMs 邀请截止（score 按 float64 解析后截成整数，无符号）
 * @param score      Redis 返回的 score 原字符串（ISO-8859-1 解码，字节不变），只用于 {@link TeamStore#pruneInvite} 的 CAS 比较
 */
public record InviteIndexEntry(long teamId, long expireAtMs, String score) {
}
