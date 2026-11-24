package com.game.player.store;

import com.game.common.deadline.Deadline;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 账号注销 / 角色删除的跨域挂起预检（角色栅栏，复用 {@link RolePendingFence}）。
 *
 * <p>调用方已持当前事务与角色锁；返回非空原因列表即阻断（如 {@code "role_listed"}、{@code "guild_leader"}、{@code "pending"}），
 * 抛 {@link SQLException} 按 fail-closed 要求稍后重试，绝不当成无挂起放行。
 */
@FunctionalInterface
public interface RoleDeletionPrecheck {

    String BLOCKER_ROLE_LISTED = "role_listed";
    String BLOCKER_GUILD_LEADER = "guild_leader";
    String BLOCKER_PENDING = "pending";

    /**
     * 检查一名角色是否存在跨域挂起。
     *
     * @param connection         当前事务连接
     * @param playerId           角色号
     * @param includeSelfService 是否包含需本人先处理的自助拦阻项（如帮主 {@code guild_leader}；本人申请为 true，运维代办为 false）
     */
    List<String> blockers(Connection connection, long playerId, boolean includeSelfService) throws SQLException;

    /** 无额外跨域表检查（仅用于只装载玩家核心表的隔离单测；player 行自身的交易锁与最终存档门禁仍由 PlayerStore 强制执行）。 */
    RoleDeletionPrecheck NONE = (connection, playerId, includeSelfService) -> List.of();

    /** 生产默认全量 SQL 角色栅栏：复用 {@link RolePendingFence} 全部跨域待办表 + 经济锁表 + 帮主检查。 */
    RoleDeletionPrecheck SQL_FULL = (connection, playerId, includeSelfService) -> {
        Deadline budget = Deadline.after(3000);
        List<String> out = new ArrayList<>();
        if (RolePendingFence.economicBlocked(connection, playerId, budget)) {
            out.add(BLOCKER_ROLE_LISTED);
        }
        if (RolePendingFence.blocked(connection, playerId, budget)) {
            out.add(BLOCKER_PENDING);
        }
        if (includeSelfService && isGuildLeader(connection, playerId)) {
            out.add(BLOCKER_GUILD_LEADER);
        }
        return out;
    };

    private static boolean isGuildLeader(Connection connection, long playerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT guild_id FROM guild WHERE leader_id = ? LIMIT 1")) {
            ps.setQueryTimeout(3);
            ps.setObject(1, playerId < 0 ? new BigInteger(Long.toUnsignedString(playerId)) : playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
