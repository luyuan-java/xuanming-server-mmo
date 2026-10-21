package com.game.friend.store;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerSessionAuthority;
import java.sql.Connection;
import java.sql.SQLException;

/** 原会话请求身份；不查询当前修订来替换原件，不在线程之间保存隐式状态。 */
record FriendActor(long playerId, String account, long ownerRevision) {
    FriendActor {
        if (playerId == 0 || account == null || account.isEmpty() || ownerRevision <= 0) {
            throw new SecurityException("好友请求缺少有效的原会话账号与归属修订");
        }
    }

    void requirePlayer(long player) {
        if (player != playerId) {
            throw new SecurityException("好友请求主体与原会话不符");
        }
    }

    /** 所有 P 锁先于领域锁，双方按 unsigned 升序；冻结目标也不能产生新的关系变更。 */
    void lock(Connection connection, Deadline deadline, int timeoutCap, long... players) throws SQLException {
        if (players.length == 0) {
            players = new long[] {playerId};
        }
        boolean includesActor = false;
        for (long player : players) {
            includesActor |= player == playerId;
        }
        if (!includesActor) {
            throw new SecurityException("好友事务没有包含原会话角色");
        }
        long[] ordered = players.clone();
        // 实际最多两个 P；不用 signed 的 Arrays.sort 处理 uint64 玩家号。
        for (int i = 1; i < ordered.length; i++) {
            long value = ordered[i];
            int j = i - 1;
            while (j >= 0 && Long.compareUnsigned(ordered[j], value) > 0) {
                ordered[j + 1] = ordered[j--];
            }
            ordered[j + 1] = value;
        }
        com.game.common.player.PlayerRouteAuthority.lock(connection,java.util.Arrays.stream(ordered).boxed().toList(),timeout(deadline,timeoutCap));
        for (int i = 0; i < ordered.length; i++) {
            long player = ordered[i];
            if (i > 0 && player == ordered[i - 1]) continue;
            int timeout = timeout(deadline, timeoutCap);
            if (player == playerId) {
                PlayerSessionAuthority.lock(connection, playerId, account, ownerRevision, timeout);
            } else {
                // 目标不存在仍沿用原好友语义；存在时必须在同一事务锁住，禁止修改冻结角色的关系。
                try (var statement = connection.prepareStatement("SELECT trade_listing_id FROM player WHERE player_id=? FOR UPDATE")) {
                    statement.setQueryTimeout(timeout);
                    statement.setObject(1, JdbcFriendStore.uid(player));
                    try (var row = statement.executeQuery()) {
                        if (row.next() && row.getLong(1) != 0) {
                            throw new SecurityException("好友目标角色已冻结");
                        }
                    }
                }
            }
        }
    }

    static int timeout(Deadline deadline, int cap) throws SQLException {
        long remaining = deadline.remainingMillis();
        if (remaining <= 0) throw new SQLException("好友原会话验证已超过请求预算");
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1, (remaining + 999) / 1000));
        return cap > 0 ? Math.min(cap, timeout) : timeout;
    }
}
