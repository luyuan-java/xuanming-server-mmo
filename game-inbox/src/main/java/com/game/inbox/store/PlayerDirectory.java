package com.game.inbox.store;

import com.game.common.deadline.Deadline;
import java.sql.SQLException;
import java.util.OptionalLong;

/**
 * 收件箱对玩家表的两种只读需求（Java 版的玩家归属与 owner_epoch 都在玩家表 {@code player}，game-player-store 建表）：
 * <ul>
 *   <li>入箱前的玩家存在校验（设计 §6.2 第 2 步：输错的 player_id 不留永远 PENDING 的行）；</li>
 *   <li>围栏上界 R（IB-8）：{@code player.owner_epoch} 的当前值（只增不减：login 夺权 +1、跨节点交出 +1）。</li>
 * </ul>
 * 读失败抛 {@link SQLException}（调用方回 kPlayerInboxStoreUnavailable，fail-closed，不改任何状态）。
 */
@FunctionalInterface
public interface PlayerDirectory {

    /** 玩家当前的 owner_epoch；玩家不存在为空。 */
    OptionalLong ownerEpoch(Deadline budget, long playerId) throws SQLException;
}
