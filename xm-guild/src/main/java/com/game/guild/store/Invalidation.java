package com.game.guild.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次已提交的写要失效的缓存键（基线 invalidateAfterCommit(ctx, op, G, players...) 的入参，guild_manage_repo.go:524-574；
 * 失效矩阵见 guild-spec §1.11）。服务在提交之后交给缓存失效组件（先同步失效一次、失败的键交后台有界重试，永不抛），
 * 之后才发推送（§4.4）。
 *
 * @param op        指标标签（{@code xm_guild_cache_invalidation_failures_total{op}}）
 * @param guildId   要失效的帮会快照；0 = 不失效帮会快照
 * @param playerIds 要失效的「玩家 → 帮会」映射；里面的 0 由失效组件忽略
 */
public record Invalidation(GuildTxOp op, long guildId, List<Long> playerIds) {

    public Invalidation {
        playerIds = List.copyOf(playerIds);
    }

    /** 不失效任何键（例：幂等任命、申请、撤回、审批拒绝与 CommitThenReject 分支）。 */
    public static Invalidation none(GuildTxOp op) {
        return new Invalidation(op, 0, List.of());
    }

    /** 失效帮会快照 {@code guildId}（可为 0）与这些玩家的映射。 */
    public static Invalidation of(GuildTxOp op, long guildId, long... playerIds) {
        List<Long> players = new ArrayList<>(playerIds.length);
        for (long p : playerIds) {
            players.add(p);
        }
        return new Invalidation(op, guildId, players);
    }

    /** 失效帮会快照 {@code guildId} 与这些玩家的映射。 */
    public static Invalidation of(GuildTxOp op, long guildId, List<Long> playerIds) {
        return new Invalidation(op, guildId, playerIds);
    }

    /** 没有任何要失效的键（帮会为 0、玩家全为 0 或为空）。 */
    public boolean isEmpty() {
        if (guildId != 0) {
            return false;
        }
        for (long p : playerIds) {
            if (p != 0) {
                return false;
            }
        }
        return true;
    }
}
