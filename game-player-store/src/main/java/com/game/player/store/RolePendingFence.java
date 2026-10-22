package com.game.player.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.math.BigInteger;
import com.game.common.deadline.Deadline;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 角色冻结/归属删除/维护覆盖的跨域挂起条件。调用者必须已持同一事务的player(P)锁；
 * 新增历练、奖励、邮件领取及匹配参与者也先取P，所以普通RC读取不会漏过并发提交。
 * 仅各域明确完整终态放行，未知状态和缺表/查询失败均拒绝；不在此修复、取消或删除业务待办。
 */
public final class RolePendingFence {
    private RolePendingFence() {}
    private static PreparedStatement prepare(Connection connection, Deadline budget, String query) throws SQLException {
        if (budget == null || budget.expired() || Thread.currentThread().isInterrupted()) throw new java.sql.SQLTimeoutException("跨域挂起核验已超过原请求预算");
        var statement = connection.prepareStatement(query);
        try {
            statement.setQueryTimeout((int)Math.max(1, Math.min(3, (budget.remainingMillis()+999)/1000)));
            return statement;
        } catch (SQLException | RuntimeException failure) {
            try { statement.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
    private static boolean exists(Connection connection, Deadline budget, String query, long... values) throws SQLException {
        try (var statement = prepare(connection,budget,query)) {
            for (int index=0;index<values.length;index++) {
                long value=values[index];
                statement.setObject(index+1,value < 0 ? new BigInteger(Long.toUnsignedString(value)) : value);
            }
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    /** 同事务DB毫秒时钟：保留TiDB原精度；十进制系数避免H2整数乘法溢出，无本机时钟回退。 */
    private static long databaseNowMs(Connection connection, Deadline budget) throws SQLException {
        try (var statement=prepare(connection,budget,"SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000.000");var rows=statement.executeQuery()) {
            if (!rows.next()) throw new SQLException("跨域挂起核验缺少数据库时钟");
            var value=rows.getBigDecimal(1);
            if (value==null) throw new SQLException("跨域挂起核验数据库时钟为空");
            final long now;
            try { now=value.longValueExact(); } catch (ArithmeticException malformed) { throw new SQLException("跨域挂起核验数据库时钟不是有效毫秒",malformed); }
            if (now<=0 || rows.next()) throw new SQLException("跨域挂起核验数据库时钟原件无效");
            if (budget.expired() || Thread.currentThread().isInterrupted()) throw new java.sql.SQLTimeoutException("跨域挂起核验数据库时钟超过原请求预算");
            return now;
        }
    }
    public static boolean blocked(Connection connection, long player) throws SQLException {
        return blocked(connection,player,Deadline.after(3000));
    }
    public static boolean blocked(Connection connection, long player, Deadline budget) throws SQLException {
        if (player == 0 || connection.getAutoCommit()) throw new SQLException("跨域资产围栏必须位于已锁P的事务");
        if (exists(connection,budget,"SELECT player_id FROM team_member_authority WHERE player_id=? AND (team_id<>0 OR team_id IS NULL) LIMIT 1",player)
            || exists(connection,budget,"SELECT player_id FROM team_launch_role WHERE player_id=? AND (state<>2 OR state IS NULL) LIMIT 1",player)) return true;
        long nowMs=databaseNowMs(connection,budget);
        return exists(connection,budget,"SELECT invitee_id FROM team_invite_authority WHERE (invitee_id=? OR initiator_player_id=?) AND (expires_ms>? OR expires_ms IS NULL) LIMIT 1",player,player,nowMs)
            || exists(connection,budget,"SELECT player_id FROM team_application_authority WHERE (player_id=? OR recipient_player_id=?) AND (expires_ms>? OR expires_ms IS NULL) LIMIT 1",player,player,nowMs)
            || exists(connection,budget,"SELECT request_id FROM guild_trial_launch_role WHERE player_id=? AND (state<>2 OR state IS NULL) LIMIT 1",player)
            // 私有同意直到大厅明确终局才释放；本域旧lobby时钟不充当角色过户的安全证明。
            || exists(connection,budget,"SELECT s.player_id FROM guild_activity_slot s LEFT JOIN guild_trial_lobby l ON l.lobby_id=s.lobby_id WHERE s.player_id=? AND (s.lobby_id IS NULL OR s.lobby_id<>0) AND "
                    + "(l.lobby_id IS NULL OR l.state IS NULL OR l.state<>4) LIMIT 1",player)
            || exists(connection,budget,"SELECT reward_id FROM guild_activity_reward_owed WHERE player_id=? AND (status<>1 OR status IS NULL) LIMIT 1",player)
            // APPLIED_PARTIAL/manual也未安全结清；只有原三种完整终态可让过户继续。
            || exists(connection,budget,"SELECT op_id FROM guild_asset_op WHERE player_id=? AND (status NOT IN(2,3,4) OR status IS NULL) LIMIT 1",player)
            || exists(connection,budget,"SELECT op_id FROM mail_asset_op WHERE player_id=? AND (status NOT IN(2,3,5) OR status IS NULL) LIMIT 1",player)
            || exists(connection,budget,"SELECT player_id FROM player_inbox_item WHERE shard=? AND player_id=? AND (state IS NULL OR state NOT IN(2,3,4,5)) LIMIT 1",com.game.common.inbox.InboxShards.rowShard(player),player)
            || exists(connection,budget,"SELECT player_id FROM match_global_ticket WHERE player_id=? AND (state<>4 OR state IS NULL) LIMIT 1",player)
            || exists(connection,budget,"SELECT player_id FROM match_global_watching WHERE player_id=? AND (state<>5 OR state IS NULL) LIMIT 1",player)
            // 邀请接受与global票在同一P事务交接；精确过期PENDING、ACCEPTED/DECLINED才没有独立邀请待办。
            || exists(connection,budget,"SELECT challenger_id FROM match_global_challenge WHERE (challenger_id=? OR target_id=?) AND "
                    + "((state=1 AND expires_ms>?) OR state NOT IN(1,2,3) OR state IS NULL OR (state=1 AND expires_ms IS NULL)) LIMIT 1",player,player,nowMs);
    }
    public static boolean blocked(JdbcTemplate sql, long player) {
        return Boolean.TRUE.equals(sql.execute((ConnectionCallback<Boolean>)connection -> blocked(connection,player)));
    }
    /** 身份修订/归属覆盖前的经济挂起条件；角色离帮政策由调用域独立处理。 */
    public static boolean economicBlocked(Connection connection,long player)throws SQLException{return economic(connection,player,0,false,Deadline.after(3000));}
    public static boolean economicBlocked(Connection connection,long player,Deadline budget)throws SQLException{return economic(connection,player,0,false,budget);}
    /** 合区保持原account/revision，稳定LISTED托管可随市场home迁移；未终结资产腿仍拒绝。 */
    public static boolean economicBlockedForHomeMove(Connection connection,long player)throws SQLException{return economic(connection,player,0,true,Deadline.after(3000));}
    /** Native已核原请求并在同P内写入的本次listing可排除；不得排除其它合同或任何资产腿。 */
    public static boolean economicBlocked(JdbcTemplate sql,long player,long ownListing){return Boolean.TRUE.equals(sql.execute((ConnectionCallback<Boolean>)c->economic(c,player,ownListing,false,Deadline.after(3000))));}
    private static String exact(String value){var result=new StringBuilder("(OCTET_LENGTH(status)="+value.length());for(int i=0;i<value.length();i++)result.append(" AND ASCII(SUBSTRING(status,").append(i+1).append(",1))=").append((int)value.charAt(i));return result.append(')').toString();}
    private static boolean economic(Connection c,long p,long own,boolean stableListing,Deadline budget)throws SQLException{
        if(p==0||c.getAutoCommit())throw new SQLException("经济挂起核验必须持同P事务");
        return exists(c,budget,"SELECT commerce_id FROM trade_commerce WHERE (seller_player_id=? OR buyer_player_id=?) AND (status IS NULL OR status NOT IN(7,11)) AND (?=0 OR commerce_id<>?) LIMIT 1",p,p,own,own)
            || exists(c,budget,"SELECT op_id FROM trade_asset_op WHERE player_id=? AND (status IS NULL OR status NOT IN(2,3,4) OR receipt_ack_pending IS NULL OR receipt_ack_pending<>0 OR business_consumed IS NULL OR business_consumed<>1) LIMIT 1",p)
            || exists(c,budget,"SELECT listing_id FROM trade_listing WHERE seller_player_id=? AND (status IS NULL OR status NOT IN("+(stableListing?"2,4,6,7":"4,6,7")+")) AND (?=0 OR listing_id<>?) LIMIT 1",p,own,own)
            || exists(c,budget,"SELECT listing_id FROM player_trade_freeze WHERE player_id=? AND (status IS NULL OR NOT("+exact("UNLOCKED")+" OR "+exact("TRANSFERRED")+")) AND (?=0 OR listing_id<>?) LIMIT 1",p,own,own)
            || exists(c,budget,"SELECT tx_id FROM player_trade_transfer WHERE player_id=? AND (status IS NULL OR NOT "+exact("APPLIED")+") LIMIT 1",p);
    }
}
