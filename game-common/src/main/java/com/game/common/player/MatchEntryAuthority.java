package com.game.common.player;

import java.sql.*;
import java.util.Collection;

/** 已持完整route/P后的入场互斥；只消费既有票与Team/Trial原launch，不另建占位或从当前owner补权。 */
public final class MatchEntryAuthority {
    private MatchEntryAuthority(){}
    private static boolean exists(Connection c,int timeout,String sql,Object...args)throws SQLException{
        try(var q=c.prepareStatement(sql)){q.setQueryTimeout(timeout);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var rows=q.executeQuery()){return rows.next();}}
    }
    public static boolean occupied(Connection c,long player,String ownTeam,long ownTrial,int timeout)throws SQLException{
        if(c.getAutoCommit()||player==0||ownTeam==null)throw new SQLException("入场互斥需要全员原事务");
        if(exists(c,timeout,"SELECT 1 FROM match_global_ticket WHERE player_id=? AND (state IS NULL OR state<>4) LIMIT 1",PlayerRouteAuthority.u64(player)))return true;
        return launchOccupied(c,player,ownTeam,ownTrial,timeout);
    }
    public static void requireFree(Connection c,Collection<Long> players,String ownTeam,long ownTrial,int timeout)throws SQLException{
        for(long player:players)if(occupied(c,player,ownTeam,ownTrial,timeout))throw new SQLException("角色已有未终局匹配票或原launch");
    }
    public static void requireNoPendingLaunches(Connection c,Collection<Long> players,String ownTeam,long ownTrial,int timeout)throws SQLException{
        if(c.getAutoCommit()||ownTeam==null)throw new SQLException("入场互斥需要原事务");
        for(long player:players)if(launchOccupied(c,player,ownTeam,ownTrial,timeout))throw new SQLException("角色已有另一个未终局原launch");
    }
    private static boolean launchOccupied(Connection c,long player,String ownTeam,long ownTrial,int timeout)throws SQLException{
        if(exists(c,timeout,"SELECT 1 FROM team_launch_role r LEFT JOIN team_launch l ON l.request_id=r.request_id WHERE r.player_id=? AND (r.state IS NULL OR r.state NOT IN(1,2) OR r.state=1 AND (l.request_id IS NULL OR l.state IS NULL OR l.state NOT IN(1,2,3,4,5) OR l.state IN(1,2,3) AND r.request_id<>?)) LIMIT 1",PlayerRouteAuthority.u64(player),ownTeam))return true;
        // Trial角色只有已知CLOSED才释放；原己方PREPARED在Match消费时另核完整原件/摘要/ACTIVE投影。
        return exists(c,timeout,"SELECT 1 FROM guild_trial_launch_role r LEFT JOIN guild_trial_launch l ON l.request_id=r.request_id WHERE r.player_id=? AND (r.state IS NULL OR r.state NOT IN(1,2) OR r.state=1 AND (r.request_id<>? OR l.request_id IS NULL OR l.state IS NULL OR l.state<>1)) LIMIT 1",PlayerRouteAuthority.u64(player),PlayerRouteAuthority.u64(ownTrial));
    }
}
