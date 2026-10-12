package com.game.guild.asset;

import com.game.guild.rules.GuildLimits;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 回档分歧检查的查询条件（基线 AppliedOpsQuery，asset_op_divergence_repo.go:41-53；guild-economy-spec §2.13、§3.6）：
 * 「这些玩家自快照时刻以来，有没有已终结为已应用（APPLIED / APPLIED_PARTIAL）的帮会资产指令」。正确性建立在
 * 「终态行 next_attempt_ms = 终结时刻」上（终结与人工终结同写，§1.2 第 4 条）。
 *
 * <p>4.5 只交付提供方与仓储（Q7）；消费方随 7.2 回档接入。本类另带提供方要用的两个纯判定：入参校验
 * （{@link #requestError}，validateListAppliedRequest）与保留期下界（{@link #retentionCutoffMs}）。
 *
 * @param zoneId    0 = 不按 zone 收窄；非 0 时 LEFT JOIN guild，保留本 zone 与已解散帮会（guild 行已删）的行
 * @param playerIds 1..{@value GuildLimits#APPLIED_OPS_MAX_PLAYER_IDS} 个（去零、去重由提供方保证；重复只让 IN 冗余）
 * @param sinceMs   只返回 next_attempt_ms <b>严格大于</b>它的终态行（无符号）
 * @param afterOpId 游标：只返回 op_id 严格大于它的行；首页 0
 * @param limit     本页至多返回的行数，1..{@value GuildLimits#APPLIED_OPS_MAX_PAGE_LIMIT}
 */
public record AppliedOpsQuery(int zoneId, List<Long> playerIds, long sinceMs, long afterOpId, int limit) {

    public AppliedOpsQuery {
        playerIds = List.copyOf(playerIds);
    }

    /** Store 层兜底（validateAppliedOpsQuery，asset_op_divergence_repo.go:146-159）：越界直接拒绝、不碰库；null = 合法。 */
    public String invalidReason() {
        if (playerIds.isEmpty()) {
            return "player_ids is empty";
        }
        if (playerIds.size() > GuildLimits.APPLIED_OPS_MAX_PLAYER_IDS) {
            return playerIds.size() + " player_ids, limit " + GuildLimits.APPLIED_OPS_MAX_PLAYER_IDS;
        }
        if (limit < 1 || limit > GuildLimits.APPLIED_OPS_MAX_PAGE_LIMIT) {
            return "limit " + limit + " out of [1, " + GuildLimits.APPLIED_OPS_MAX_PAGE_LIMIT + "]";
        }
        if (sinceMs == 0) {
            return "since_ms is 0";
        }
        return null;
    }

    /**
     * 提供方的入参判定（validateListAppliedRequest，guild_internal_server.go:193-226），全部先于 SQL；文案照抄基线（E11 改成应答内结果码
     * INVALID_ARGUMENT 时随结果带出）。
     *
     * @param limit 请求里的 limit（uint32 位模式；0 = 缺省）
     * @return null = 合法；否则是错误文案
     */
    public static String requestError(List<Long> playerIds, long sinceMs, int limit) {
        if (playerIds == null || playerIds.isEmpty()) {
            return "player_ids is required";
        }
        if (playerIds.size() > GuildLimits.APPLIED_OPS_MAX_PLAYER_IDS) {
            return "player_ids has " + playerIds.size() + " entries, limit " + GuildLimits.APPLIED_OPS_MAX_PLAYER_IDS;
        }
        Set<Long> seen = new HashSet<>();
        for (long id : playerIds) {
            if (id == 0) {
                return "player_ids must not contain 0";
            }
            if (!seen.add(id)) {
                return "player_ids must not contain duplicates";
            }
        }
        if (sinceMs == 0) {
            return "since_ms is required";
        }
        if (Integer.compareUnsigned(limit, GuildLimits.APPLIED_OPS_MAX_PAGE_LIMIT) > 0) {
            return "limit " + Integer.toUnsignedString(limit) + " exceeds " + GuildLimits.APPLIED_OPS_MAX_PAGE_LIMIT;
        }
        return null;
    }

    /** 生效的页长：请求 0 → {@value GuildLimits#APPLIED_OPS_MAX_PAGE_LIMIT}，否则原值（已经过 {@link #requestError}）。 */
    public static int effectiveLimit(int requested) {
        return requested == 0 ? GuildLimits.APPLIED_OPS_MAX_PAGE_LIMIT : requested;
    }

    /**
     * 保留期下界（retentionCutoffMs，guild_internal_server.go:51-59）：{@code now + 3600000 − TerminalRetention}；since_ms 小于它即
     * 不可证明（终态行已可能被清理删掉）。now 早于「保留期 − 余量」时取 0（没有任何 since_ms 早于它）。
     *
     * @param terminalRetentionMs 终态行保留期（Java 的 Spring 缺省恒为 30 d，通道关闭时也有值；≤ 0 由提供方回 UNAVAILABLE）
     */
    public static long retentionCutoffMs(long nowMs, long terminalRetentionMs) {
        long safety = GuildLimits.APPLIED_OPS_RETENTION_SAFETY_MS;
        if (Long.compareUnsigned(nowMs + safety, terminalRetentionMs) <= 0) {
            return 0;
        }
        return nowMs + safety - terminalRetentionMs;
    }
}
