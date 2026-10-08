package com.game.team.store;

import com.game.proto.TipInfoMessage;
import com.game.team.rules.Decision;
import java.util.Map;

/**
 * 一次成功的 S_COMMIT（基线 store.go:145-157）。不可变。
 *
 * @param teamId   提交的队伍
 * @param version  提交后的 ver（解散时是被删记录的最后一版 +1）
 * @param decision 提交的决策
 * @param indexes  joined / kept / left 每个人提交后的 {@code (tid, epoch)}，出自同一段 Lua（不可变，按 J、K、L 顺序迭代）
 * @param nowMs    <b>本轮 S_READ</b> 的 Redis 时钟（规则用的 now），不是 S_COMMIT 自己的 TIME（store.go:153、:804）
 * @param pushTip  快照推送附带的原因（{@code TeamSnapshotS2C.tip}，基线 store.go:154-156 PushTip）；null = 不带。存储层不填不读：
 *                 服务层在派发推送前用 {@link #withPushTip} 换一份带原因的副本，只有整队开战建票失败的 MATCH_FAILED 用（4026[pid]）；
 *                 只随推送下发，不进存储
 */
public record CommitResult(long teamId, long version, Decision decision, Map<Long, IndexEntry> indexes, long nowMs,
                           TipInfoMessage pushTip) {

    /** 不带推送原因的提交结果（存储层只产出这一种）。 */
    public CommitResult(long teamId, long version, Decision decision, Map<Long, IndexEntry> indexes, long nowMs) {
        this(teamId, version, decision, indexes, nowMs, null);
    }

    /** 同一次提交、换上推送原因的副本（{@code tip} 为 null 即去掉原因）。 */
    public CommitResult withPushTip(TipInfoMessage tip) {
        return new CommitResult(teamId, version, decision, indexes, nowMs, tip);
    }
}
