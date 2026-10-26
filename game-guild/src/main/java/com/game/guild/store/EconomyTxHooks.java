package com.game.guild.store;

import java.sql.SQLException;
import java.util.List;

/**
 * 4.5 填上的核心事务钩子（guild-economy-spec §6.1、§6.2；guild-spec §6.3）：踢人 / 退帮 / 解散在删成员行与删申请之后、快照或删 guild 行
 * 之前，把这些玩家在本帮的未决捐献截止提前到 now（{@link JdbcEconomyStore#accelerateDonationDeadlines}，锁序位置 O）。
 * 解散继续在同一事务按完整主键删除活动进度（锁序位置 P）。
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

    /** G锁已由解散持有；候选读关闭后按主键顺序点删，不引入范围删除的锁序不确定性。 */
    @Override
    public void deleteGuildActivityProgress(GuildJdbc tx, long guildId) throws SQLException {
        var keys = tx.list("SELECT activity_id,period_key FROM guild_activity_progress WHERE guild_id=? ORDER BY activity_id,period_key",
                row -> new int[] {GuildJdbc.u32(row, 1), GuildJdbc.u32(row, 2)}, guildId);
        for (var key : keys)
            tx.update("DELETE FROM guild_activity_progress WHERE guild_id=? AND activity_id=? AND period_key=?", guildId, key[0], key[1]);
    }
}
