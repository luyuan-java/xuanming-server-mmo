package com.game.common.player;

import java.sql.*;
import java.util.Collection;
import java.util.HashSet;
import com.game.proto.guild.GuildTrialLaunchOriginal;
import com.game.proto.guild.GuildTrialLobbyView;

/** 已持完整route/P后的入场互斥；只消费既有票与Team/Trial原launch，不另建占位或从当前owner补权。 */
public final class MatchEntryAuthority {
    private MatchEntryAuthority(){}
    private static boolean exists(Connection c,int timeout,String sql,Object...args)throws SQLException{
        try(var q=c.prepareStatement(sql)){q.setQueryTimeout(timeout);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var rows=q.executeQuery()){return rows.next();}}
    }
    public static boolean occupied(Connection c,long player,String ownTeam,long ownTrial,int timeout)throws SQLException{
        if(c.getAutoCommit()||player==0||ownTeam==null)throw new SQLException("入场互斥需要全员原事务");
        if(exists(c,timeout,"SELECT 1 FROM match_global_ticket WHERE player_id=? AND (state IS NULL OR state<>4) LIMIT 1",PlayerRouteAuthority.u64(player)))return true;
        return launchOccupied(c,player,ownTeam,ownTrial,timeout) || trialSlotOccupied(c,player,ownTrial,null,timeout);
    }
    public static void requireFree(Connection c,Collection<Long> players,String ownTeam,long ownTrial,int timeout)throws SQLException{
        for(long player:players)if(occupied(c,player,ownTeam,ownTrial,timeout))throw new SQLException("角色已有未终局匹配票或原launch");
    }
    public static void requireNoPendingLaunches(Connection c,Collection<Long> players,String ownTeam,long ownTrial,int timeout)throws SQLException{
        if(c.getAutoCommit()||ownTeam==null)throw new SQLException("入场互斥需要原事务");
        for(long player:players)if(launchOccupied(c,player,ownTeam,ownTrial,timeout)
                ||trialSlotOccupied(c,player,ownTrial,null,timeout))throw new SQLException("角色已有另一个未终局原launch或历练大厅");
    }

    /** 首次历练launch尚未存在，只能以已持全P/G/大厅锁的完整同意原件排除自己的真实大厅。 */
    public static void requireFreeForTrial(Connection c,GuildTrialLaunchOriginal original,int timeout)throws SQLException{
        if(c.getAutoCommit())throw new SQLException("历练入场互斥需要原事务");
        validateOriginal(original);
        for(var role:original.getRosterList()){
            long player=role.getPlayerId();
            if(exists(c,timeout,"SELECT 1 FROM match_global_ticket WHERE player_id=? AND (state IS NULL OR state<>4) LIMIT 1",PlayerRouteAuthority.u64(player))
                    ||launchOccupied(c,player,"",original.getRequestId(),timeout)
                    ||trialSlotOccupied(c,player,original.getRequestId(),original,timeout))throw new SQLException("历练成员已有其它入场占用或原大厅不符");
        }
    }
    private static boolean launchOccupied(Connection c,long player,String ownTeam,long ownTrial,int timeout)throws SQLException{
        // Java 4/5分别是已核原F结束/失败，且Team同tx把role置2；父终局但role仍ACTIVE不是完整释放证明。
        if(exists(c,timeout,"SELECT 1 FROM team_launch_role r LEFT JOIN team_launch l ON l.request_id=r.request_id WHERE r.player_id=? AND (l.request_id IS NULL OR l.state IS NULL OR r.state IS NULL OR NOT (r.state=2 AND l.state IN(4,5) OR r.state=1 AND l.state IN(1,2,3) AND r.request_id<>'' AND r.request_id=?)) LIMIT 1",PlayerRouteAuthority.u64(player),ownTeam))return true;
        // Trial角色只有已知CLOSED才释放；原己方PREPARED在Match消费时另核完整原件/摘要/ACTIVE投影。
        return exists(c,timeout,"SELECT 1 FROM guild_trial_launch_role r LEFT JOIN guild_trial_launch l ON l.request_id=r.request_id WHERE r.player_id=? AND (l.request_id IS NULL OR l.state IS NULL OR r.state IS NULL OR NOT (r.state=2 AND l.state=3 OR r.state=1 AND r.request_id<>0 AND r.request_id=? AND l.state=1)) LIMIT 1",PlayerRouteAuthority.u64(player),PlayerRouteAuthority.u64(ownTrial));
    }

    private static GuildTrialLaunchOriginal persistedTrial(Connection c,long request,int timeout)throws SQLException{
        try(var q=c.prepareStatement("SELECT state,original_proto,request_digest FROM guild_trial_launch WHERE request_id=?")){
            q.setQueryTimeout(timeout);q.setObject(1,PlayerRouteAuthority.u64(request));try(var row=q.executeQuery()){
                if(!row.next()||row.getInt(1)!=1)throw new SQLException("己方历练缺少原PREPARED launch");
                byte[] raw=row.getBytes(2),digest=row.getBytes(3);
                if(raw==null||raw.length==0||raw.length>65536||digest==null)throw new SQLException("己方历练原件缺失");
                try{
                    if(!java.security.MessageDigest.isEqual(java.security.MessageDigest.getInstance("SHA-256").digest(raw),digest))throw new SQLException("己方历练原件摘要损坏");
                    var original=GuildTrialLaunchOriginal.parseFrom(raw);validateOriginal(original);
                    if(original.getRequestId()!=request)throw new SQLException("己方历练原件请求不符");
                    return original;
                }catch(java.io.IOException|java.security.GeneralSecurityException failure){throw new SQLException("己方历练原件损坏",failure);}
            }
        }
    }
    private static void validateOriginal(GuildTrialLaunchOriginal original)throws SQLException{
        if(original==null||original.getRequestId()==0||original.getGuildId()==0||original.getActivityId()==0
                ||original.getPeriodKey()==0||original.getGuildPeriodKey()==0||original.getDungeonId()==0
                ||original.getCreatedMs()<=0||original.getRosterCount()<2||original.getRosterCount()>5||!original.getUnknownFields().asMap().isEmpty())throw new SQLException("历练原件范围不完整");
        var players=new HashSet<Long>();long previous=0;
        for(var role:original.getRosterList()){
            if(role.getPlayerId()==0||role.getAccount().isEmpty()||role.getOwnerRevision()<=0||!players.add(role.getPlayerId())
                    ||previous!=0&&Long.compareUnsigned(previous,role.getPlayerId())>=0)throw new SQLException("历练原授权名单未规范化");
            previous=role.getPlayerId();
        }
        if(!players.contains(original.getInitiatorPlayerId()))throw new SQLException("历练原发起者缺席");
    }
    private static boolean trialSlotOccupied(Connection c,long player,long ownTrial,GuildTrialLaunchOriginal preparing,int timeout)throws SQLException{
        try(var q=c.prepareStatement("SELECT s.lobby_id,l.state,l.view_proto,l.guild_id,l.activity_id FROM guild_activity_slot s LEFT JOIN guild_trial_lobby l ON l.lobby_id=s.lobby_id WHERE s.player_id=?")){
            q.setQueryTimeout(timeout);q.setObject(1,PlayerRouteAuthority.u64(player));try(var row=q.executeQuery()){
                if(!row.next())return ownTrial!=0; // 己方例外必须有每个原成员的实际私有slot。
                String rawLobby=row.getString(1);if(rawLobby==null)return true;
                long lobby;try{lobby=Long.parseUnsignedLong(rawLobby);}catch(NumberFormatException invalid){throw new SQLException("历练slot原ID非法",invalid);}
                if(lobby==0)return ownTrial!=0;
                int state=row.getInt(2);if(row.wasNull()||state<1||state>4)return true;
                if(state==4)return ownTrial!=0;
                // 不读expires_ms：TTL既不释放大厅，也不赋予其它匹配的进入权限。
                if(ownTrial==0||lobby!=ownTrial)return true;
                var original=preparing==null?persistedTrial(c,ownTrial,timeout):preparing;
                try{
                    byte[] rawView=row.getBytes(3);if(rawView==null||rawView.length==0||rawView.length>65536)return true;
                    var view=GuildTrialLobbyView.parseFrom(rawView);
                    var expected=original.getRosterList().stream().map(role->role.getPlayerId()).collect(java.util.stream.Collectors.toSet());
                    var actual=new HashSet<>(view.getMemberPlayerIdsList());
                    if(original.getRequestId()!=lobby||view.getLobbyId()!=lobby||view.getStateValue()!=state
                            ||original.getGuildId()!=Long.parseUnsignedLong(row.getString(4))||original.getActivityId()!=row.getInt(5)
                            ||view.getInitiatorPlayerId()!=original.getInitiatorPlayerId()||actual.size()!=view.getMemberPlayerIdsCount()
                            ||!actual.equals(expected)||!expected.contains(player))return true;
                    if(state!=(preparing!=null?1:2))return true;
                    return false;
                }catch(java.io.IOException|IllegalArgumentException invalid){throw new SQLException("历练真实大厅原件损坏",invalid);}
            }
        }
    }
}
