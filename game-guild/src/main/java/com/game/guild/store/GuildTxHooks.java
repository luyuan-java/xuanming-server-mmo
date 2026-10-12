package com.game.guild.store;

import java.sql.SQLException;
import java.util.List;

/**
 * 4.5 / 4.6 要插进核心事务里的步骤（guild-spec §6.3）。4.4 用 {@link #NONE}（空操作），位置与入参现在就钉好：4.5 / 4.6 只换实现，
 * 不动退帮 / 踢人 / 解散的事务骨架。4.5 的实现是 {@link EconomyTxHooks}（提前截止已填，活动进度仍是空操作，随 4.6）。
 *
 * <p>两个钩子都在调用方的事务里、用同一个 {@link GuildJdbc}（同一份子预算）执行，抛出的 {@link SQLException} 按事务基座的规则分类
 * （1213 重跑、1205 / 超子预算 → WRITE_CONFLICT、其余 → 依赖故障）。钩子里只许做完整主键等值的点锁 / 点改
 * （候选普通读 → 主键升序逐行点锁 → 带复核点改，economy_repo.go:370-426、activity_repo.go:532-561）。
 */
public interface GuildTxHooks {

    /**
     * 锁序位置 O（guild_asset_op）：把这些玩家在本帮的未决捐献截止时间提前到 {@code nowMs}（基线 accelerateDonationDeadlines）。
     * 调用位置：删成员行与删申请<b>之后</b>、快照或删 guild 行<b>之前</b>；成员行 X 锁仍持有到提交，捐献预留被挡在外面。
     * 调用方：踢人（{@code [target]}，guild_manage_repo.go:1433-1435）、退帮（{@code [p]}，:1639-1641）、解散（全体成员，:2315-2320）。
     *
     * @param nowMs 服务时钟毫秒，必须 &gt; 0（deadline_ms = 0 在 guild_asset_op 表示「永不中止」；存储在入口处已校验）
     */
    void accelerateDonationDeadlines(GuildJdbc tx, long guildId, List<Long> playerIds, long nowMs) throws SQLException;

    /**
     * 锁序位置 P（guild_activity_progress）：删掉本帮全部活动进度行（基线 deleteGuildActivityProgress，activity_repo.go:532-561）。
     * 只有解散调用：排在提前截止<b>之后</b>、删 guild 行<b>之前</b>（排到前面就是持着 P 回头取 A / O，guild_manage_repo.go:2321-2326）。
     */
    void deleteGuildActivityProgress(GuildJdbc tx, long guildId) throws SQLException;

    /** 4.4：两张表都还不存在，空操作。 */
    GuildTxHooks NONE = new GuildTxHooks() {
        @Override
        public void accelerateDonationDeadlines(GuildJdbc tx, long guildId, List<Long> playerIds, long nowMs) {
        }

        @Override
        public void deleteGuildActivityProgress(GuildJdbc tx, long guildId) {
        }
    };
}
