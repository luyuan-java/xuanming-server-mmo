package com.game.common.player;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 服务间原会话身份的提交验证；在领域事务首部调用，保持player锁到业务提交。
 * Java native修订范围为1..Long.MAX_VALUE（初值1，递增/导入到上界拒绝）；线协议仍用uint64。
 * 高半区值不能在此借当前修订替换或溢出为负数后继续；未知、越界一律拒绝。
 */
public final class PlayerSessionAuthority {
    private PlayerSessionAuthority() {}
    public static void lock(Connection connection, long playerId, String originalAccount, long originalRevision,
                            int queryTimeoutSeconds) throws SQLException {
        if (playerId == 0 || originalAccount == null || originalAccount.isEmpty() || originalRevision <= 0
                || queryTimeoutSeconds <= 0 || connection.getAutoCommit())
            throw new SecurityException("玩家原会话身份或事务边界无效");
        PlayerRouteAuthority.lock(connection,java.util.List.of(playerId),queryTimeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT account,owner_revision,trade_listing_id FROM player WHERE player_id=? FOR UPDATE")) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            statement.setObject(1, playerId < 0 ? new BigInteger(Long.toUnsignedString(playerId)) : playerId);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new SecurityException("玩家原会话角色不存在");
                long revision;
                try { revision = Long.parseUnsignedLong(row.getString(2)); }
                catch (NumberFormatException invalid) { throw new SecurityException("玩家账号归属修订格式无效"); }
                if (revision <= 0) throw new SecurityException("Java账号归属修订超出1..LongMax，拒绝继续");
                if (!originalAccount.equals(row.getString(1)) || originalRevision != revision || !"0".equals(row.getString(3)))
                    throw new SecurityException("玩家账号归属或原会话修订已失效");
            }
        }
    }
}
