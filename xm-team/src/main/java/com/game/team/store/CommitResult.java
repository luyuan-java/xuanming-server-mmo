package com.game.team.store;

import com.game.team.rules.Decision;
import java.util.Map;

/**
 * 一次成功的 S_COMMIT（基线 store.go:145-157）。
 *
 * <p>与基线的差别：基线的 {@code PushTip}（快照推送附带的原因，服务层在派发前设置，只有 6.4 整队开战的 MATCH_FAILED 用）不在这里，
 * 由服务层自己携带（本记录不可变）。
 *
 * @param teamId   提交的队伍
 * @param version  提交后的 ver（解散时是被删记录的最后一版 +1）
 * @param decision 提交的决策
 * @param indexes  joined / kept / left 每个人提交后的 {@code (tid, epoch)}，出自同一段 Lua（不可变，按 J、K、L 顺序迭代）
 * @param nowMs    <b>本轮 S_READ</b> 的 Redis 时钟（规则用的 now），不是 S_COMMIT 自己的 TIME（store.go:153、:804）
 */
public record CommitResult(long teamId, long version, Decision decision, Map<Long, IndexEntry> indexes, long nowMs) {
}
