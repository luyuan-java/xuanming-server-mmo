package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import java.util.List;

/**
 * 论剑 V7 擂台直播独立 16 桶高段排位索引（对齐主仓 {@code spectate:ranked:active:{srk:0..15}}）：
 * 防止高频 PVE 战局稀释可观战列表，使高段正式排位局始终排在赛事局之后、普通战局之前并按巅峰分降序展示。
 */
public interface RankedSpectateIndex {

    int BUCKET_COUNT = 16;

    /** 按 battle_id 无符号取模 16 映射桶号（0..15）。 */
    static int bucketOf(long battleId) {
        return (int) Long.remainderUnsigned(battleId, BUCKET_COUNT);
    }

    /** 生成带 Redis Cluster hash tag 的桶键（{@code <prefix>:spectate:ranked:active:{srk:<0..15>}}）。 */
    static String bucketKey(String prefix, int bucket) {
        if (bucket < 0 || bucket >= BUCKET_COUNT) {
            throw new IllegalArgumentException("排位观战桶号越界: " + bucket);
        }
        String p = (prefix == null || prefix.isBlank()) ? "" : (prefix.endsWith(":") ? prefix : prefix + ":");
        return p + "spectate:ranked:active:{srk:" + bucket + "}";
    }

    /** 登记公开的高段正式排位局（score = createdAtMs）。 */
    void record(long battleId, long createdAtMs);

    /** 终局或失效时从对应桶中摘除。 */
    void remove(long battleId);

    /** 清扫全部 16 桶中早于 cutoffMs 的过期成员。 */
    long sweepExpired(long cutoffMs);

    /** 读取当前活跃高段排位局 battle_id（含 1 秒进程内大厅缓存，按 createdAtMs 降序至多返回 limit 条）。 */
    List<Long> activeBattles(int limit, long nowMs, Deadline deadline);
}
