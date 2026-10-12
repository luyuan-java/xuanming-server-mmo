package com.game.team.store;

import com.game.team.proto.TeamRecord;
import java.util.Map;

/**
 * S_READ_MEMBERS 的结果：记录与每名成员的索引出自同一次原子读（基线 store.go:356-364）。
 *
 * @param teamId  读的队伍
 * @param version 记录的 ver；0 = 记录不存在
 * @param record  记录；不存在为 null
 * @param nowMs   Redis TIME（毫秒）
 * @param indexes 记录里每名成员的 {@code (tid, epoch)}（不可变；记录不存在时为空）。只给 tid == 本队的成员推送，用这里的 epoch；
 *                tid 不等的成员如实回报（tid 缺失为 0、epoch 缺失为 0）
 */
public record MembersSnapshot(long teamId, long version, TeamRecord record, long nowMs, Map<Long, IndexEntry> indexes) {
}
