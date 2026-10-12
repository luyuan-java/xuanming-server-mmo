package com.game.guild.store;

import java.sql.SQLException;
import java.util.List;

/**
 * 4.5 填上的核心事务钩子（guild-economy-spec §6.1、§6.2；guild-spec §6.3）：踢人 / 退帮 / 解散在删成员行与删申请之后、快照或删 guild 行
 * 之前，把这些玩家在本帮的未决捐献截止提前到 now（{@link JdbcEconomyStore#accelerateDonationDeadlines}，锁序位置 O）。
 * 活动进度（锁序位置 P）随 4.6，仍是空操作。
 *
 * <p>装配：{@code new JdbcGuildStore(guildTx, EconomyTxHooks.INSTANCE)}（替换 4.4 的 {@link GuildTxHooks#NONE}）。无状态、线程安全。
 */
public final class EconomyTxHooks implements GuildTxHooks {

    public static final EconomyTxHooks INSTANCE = new EconomyTxHooks();

    private EconomyTxHooks() {
    }

    @Override
    public void accelerateDonationDeadlines(GuildJdbc tx, long guildId, List<Long> playerIds, long nowMs) throws SQLException {
        JdbcEconomyStore.accelerateDonationDeadlines(tx, guildId, playerIds, nowMs);
    }

    /** 4.6 才建 guild_activity_progress 表：空操作。 */
    @Override
    public void deleteGuildActivityProgress(GuildJdbc tx, long guildId) {
    }
}
