package com.game.team.rules;

/**
 * 需要写进被邀请人反查索引 {@code xm:{team}:invite:<inviteeId>} 的一项（提交脚本的 IA 集合；基线 rules.go:139-143 InviteIndexAdd）。
 *
 * @param inviteeId  被邀请人（无符号）
 * @param expireAtMs 邀请截止（Redis TIME 毫秒，无符号），即 ZSET 的 score
 */
public record InviteAdd(long inviteeId, long expireAtMs) {
}
