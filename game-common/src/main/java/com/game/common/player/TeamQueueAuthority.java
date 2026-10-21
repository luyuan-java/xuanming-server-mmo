package com.game.common.player;

import com.game.proto.team.TeamLaunchOriginal;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;

/** Team名册提交与Match撤队共用同一SQL取消协议。调用者先持完整route/P、Team、launch锁。 */
public final class TeamQueueAuthority {
    public enum Result { NOT_QUEUE,CANCELLED,CLAIMED }
    private TeamQueueAuthority(){}
    private static Object u64(long bits){return bits>=0?bits:new BigInteger(Long.toUnsignedString(bits));}
    private static PreparedStatement query(Connection c,int timeout,String sql,Object...args)throws SQLException {
        var q=c.prepareStatement(sql);try{q.setQueryTimeout(timeout);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);return q;}catch(SQLException bad){q.close();throw bad;}
    }
    private static void update(Connection c,int timeout,String sql,Object...args)throws SQLException {try(var q=query(c,timeout,sql,args)){q.executeUpdate();}}
    public static Result cancel(Connection c,TeamLaunchOriginal original,byte[] raw,int timeout)throws SQLException {
        if(c.getAutoCommit())throw new SQLException("整队取消必须与原业务提交共事务");
        try{if(!TeamLaunchOriginal.parseFrom(raw).equals(original))throw new SQLException("整队取消解析原件与永久字节不同");}catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("整队取消原件损坏",bad);}
        int count=original.getMembersCount(),target=original.getRequest().getTargetTeamSize();
        if(target<=count){
            try(var q=query(c,timeout,"SELECT original_proto FROM team_launch WHERE request_id=? FOR UPDATE",original.getRequest().getRequestId());var row=q.executeQuery()){
                if(!row.next()||!Arrays.equals(row.getBytes(1),raw))throw new SQLException("整队取消必须使用SQL原launch字节");
            }return Result.NOT_QUEUE;
        }
        UUID request=UUID.fromString(original.getRequest().getRequestId());if(!request.toString().equals(original.getRequest().getRequestId())||request.equals(new UUID(0,0)))throw new SQLException("整队取消原UUID无效");
        if(count<1||target>com.game.common.team.TeamPolicyContract.CAPACITY||original.getActor().getOwnerAccount().isEmpty()||original.getActor().getOwnerRevision()<=0)throw new SQLException("整队取消缺原件");
        try(var q=query(c,timeout,"SELECT original_proto FROM team_launch WHERE request_id=? FOR UPDATE",request.toString());var row=q.executeQuery()){
            if(!row.next()||!Arrays.equals(row.getBytes(1),raw))throw new SQLException("整队取消必须使用SQL原launch字节");
        }
        String unit="t:"+Long.toUnsignedString(original.getTeamId())+":"+request;
        var actor=original.getActor();byte[] payload=original.getRequest().toByteArray();
        update(c,timeout,"INSERT IGNORE INTO match_global_direct_intent(kind,owner_id,request_high,request_low,payload,battle_id,cancelled,owner_account,owner_revision) VALUES(1,?,?,?,?,0,0,?,?)",
            u64(actor.getPlayerId()),u64(request.getMostSignificantBits()),u64(request.getLeastSignificantBits()),payload,actor.getOwnerAccount(),u64(actor.getOwnerRevision()));
        long battle;
        try(var q=query(c,timeout,"SELECT battle_id,payload,owner_account,owner_revision FROM match_global_direct_intent WHERE kind=1 AND owner_id=? AND request_high=? AND request_low=? FOR UPDATE",u64(actor.getPlayerId()),u64(request.getMostSignificantBits()),u64(request.getLeastSignificantBits()));var row=q.executeQuery()){
            if(!row.next()||!Arrays.equals(payload,row.getBytes(2))||!actor.getOwnerAccount().equals(row.getString(3))||Long.parseUnsignedLong(row.getString(4))!=actor.getOwnerRevision())throw new SQLException("整队取消direct原件不同");
            battle=Long.parseUnsignedLong(row.getString(1));
        }
        boolean exists;int state=0;long unitBattle=0;
        try(var q=query(c,timeout,"SELECT state,battle_id,team_id,launch_original FROM match_global_queue_unit WHERE unit_id=? FOR UPDATE",unit);var row=q.executeQuery()){
            exists=row.next();if(exists){state=row.getInt(1);unitBattle=Long.parseUnsignedLong(row.getString(2));if(Long.parseUnsignedLong(row.getString(3))!=original.getTeamId()||!Arrays.equals(raw,row.getBytes(4)))throw new SQLException("整队取消unit原件不同");}
        }
        if(exists&&(state==2||state==3))return Result.CLAIMED;
        if(exists&&state!=1&&state!=4)throw new SQLException("整队取消单位状态未知");
        if(battle!=0||unitBattle!=0){
            if(battle==0||unitBattle!=battle||state!=4)throw new SQLException("整队终局证明不一致");
            try(var q=query(c,timeout,"SELECT state FROM match_global_formation WHERE battle_id=? FOR UPDATE",u64(battle));var row=q.executeQuery()){if(!row.next()||row.getInt(1)!=5)return Result.CLAIMED;}
            return Result.CANCELLED;
        }
        long stamp;try(var q=query(c,timeout,"SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)");var row=q.executeQuery()){if(!row.next())throw new SQLException("缺少整队取消SQL时钟");stamp=row.getLong(1);}
        if(!exists){
            // 先取消、后迟到Start：完整原launch留永久closed单位，不能把缺记录当作已取消。
            update(c,timeout,"INSERT INTO match_global_queue_unit(unit_id,state,mode,config_id,target_size,member_count,enqueued_ms,not_before_ms,expires_ms,preferred_region,original_tickets,launch_original,team_id,battle_id,profile_digest) VALUES(?,4,5,?,?,?,?,?,0,0,?,?,?,0,?)",
                unit,Integer.toUnsignedLong(original.getRequest().getBattleConfigId()),target,count,u64(original.getEnqueuedMs()),u64(original.getNotBeforeMs()),new byte[0],raw,u64(original.getTeamId()),new byte[0]);
        }
        var ordered=original.getMembersList().stream().sorted((a,b)->Long.compareUnsigned(a.getOriginal().getPlayerId(),b.getOriginal().getPlayerId())).toList();
        for(var member:ordered){
            var role=member.getOriginal();
            try(var q=query(c,timeout,"SELECT ticket,queue_unit_id,state,owner_account,owner_revision FROM match_global_ticket WHERE player_id=? FOR UPDATE",u64(role.getPlayerId()));var row=q.executeQuery()){
                if(row.next()&&unit.equals(row.getString(2))){
                    if(!member.getTicket().equals(row.getString(1))||!role.getOwnerAccount().equals(row.getString(4))||Long.parseUnsignedLong(row.getString(5))!=role.getOwnerRevision()||(row.getInt(3)!=1&&row.getInt(3)!=4))throw new SQLException("取消时完整单位原票不同");
                    update(c,timeout,"UPDATE match_global_ticket SET state=4,formation_id=0,selected_region=0 WHERE player_id=? AND queue_unit_id=?",u64(role.getPlayerId()),unit);
                    update(c,timeout,"DELETE FROM match_global_ticket_region WHERE player_id=?",u64(role.getPlayerId()));
                }else if(exists&&state==1)throw new SQLException("排队单位缺少完整原票，拒绝半单取消");
            }
            update(c,timeout,"INSERT IGNORE INTO match_global_join_intent(player_id,request_id,ticket_record,cancelled,request_high,request_low,owner_account,owner_revision) VALUES(?,?,?,1,?,?,?,?)",u64(role.getPlayerId()),request.toString(),new byte[0],u64(request.getMostSignificantBits()),u64(request.getLeastSignificantBits()),role.getOwnerAccount(),u64(role.getOwnerRevision()));
            try(var q=query(c,timeout,"SELECT owner_account,owner_revision FROM match_global_join_intent WHERE player_id=? AND request_high=? AND request_low=? FOR UPDATE",u64(role.getPlayerId()),u64(request.getMostSignificantBits()),u64(request.getLeastSignificantBits()));var row=q.executeQuery()){
                if(!row.next()||!role.getOwnerAccount().equals(row.getString(1))||Long.parseUnsignedLong(row.getString(2))!=role.getOwnerRevision())throw new SQLException("取消墓碑属于其他原归属");
            }
            update(c,timeout,"UPDATE match_global_join_intent SET cancelled=1 WHERE player_id=? AND request_high=? AND request_low=?",u64(role.getPlayerId()),u64(request.getMostSignificantBits()),u64(request.getLeastSignificantBits()));
        }
        update(c,timeout,"UPDATE match_global_direct_intent SET cancelled=1 WHERE kind=1 AND owner_id=? AND request_high=? AND request_low=?",u64(actor.getPlayerId()),u64(request.getMostSignificantBits()),u64(request.getLeastSignificantBits()));
        update(c,timeout,"UPDATE match_global_queue_unit SET state=4 WHERE unit_id=?",unit);
        update(c,timeout,"DELETE FROM match_global_queue_unit_region WHERE unit_id=?",unit);
        update(c,timeout,"UPDATE team_launch SET state=5,battle_id=0,updated_ms=? WHERE request_id=? AND original_proto=?",stamp,request.toString(),raw);
        return Result.CANCELLED;
    }
}
