package com.game.team.store;

import com.game.team.proto.TeamRecord;

/**
 * 一次 S_READ 的结果：玩家索引与某队记录出自同一次原子读（基线 store.go:110-121；视图同源的前提，team-spec §4.1）。
 * 所有 id、epoch、ver、时间都是无符号 64 位。
 *
 * @param playerId         读的是谁的索引
 * @param playerTeamId     玩家索引当前 tid；0 = 无队（含索引缺失）
 * @param playerEpoch      玩家索引的 epoch；索引缺失时为 {@code nowMs}（不回退，见 {@link TeamScript#READ}）
 * @param teamId           本次读的队伍
 * @param version          记录的 ver；0 = 记录不存在
 * @param record           记录；不存在为 null
 * @param recordTtlSeconds 记录剩余 TTL（秒）：-2 不存在 / -1 没有 TTL
 * @param nowMs            Redis TIME（毫秒）：规则层的 now
 */
public record Snapshot(long playerId, long playerTeamId, long playerEpoch, long teamId, long version, TeamRecord record,
                       long recordTtlSeconds, long nowMs) {
}
