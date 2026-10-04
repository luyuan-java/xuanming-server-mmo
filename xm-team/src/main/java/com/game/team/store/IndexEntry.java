package com.game.team.store;

/**
 * 某玩家索引在一次原子操作中的 {@code (tid, epoch)}（基线 store.go:123-127）。推送视图只能用这里的 epoch，
 * 且只给 tid 与视图 team_id 一致的人推（team-spec §1.4）。
 *
 * @param teamId 无符号；0 = 无队
 * @param epoch  无符号
 */
public record IndexEntry(long teamId, long epoch) {
}
