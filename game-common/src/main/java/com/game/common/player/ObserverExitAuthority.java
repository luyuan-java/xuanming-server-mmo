package com.game.common.player;

import com.game.proto.AddObserverRequest;
import com.game.proto.ObserverExitOriginal;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;

/** 新参战意图在全员P锁内首捕获观战退出原件；同事务封闭原head的后续Add许可。 */
public final class ObserverExitAuthority {
    private ObserverExitAuthority(){}
    private static Object u64(long value){return value>=0?value:new BigInteger(Long.toUnsignedString(value));}
    private static PreparedStatement query(Connection c,int timeout,String sql,Object...args)throws SQLException{
        var q=c.prepareStatement(sql);try{q.setQueryTimeout(timeout);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);return q;}catch(SQLException bad){q.close();throw bad;}
    }
    /** 入参每项只使用player/account/revision；调用者必须把返回原件与新业务意图在本事务共同提交，重放不得再调。 */
    public static List<ObserverExitOriginal> capture(Connection c,Collection<ObserverExitOriginal> roles,int timeout)throws SQLException {
        if(c.getAutoCommit()||roles.size()>10)throw new SQLException("观战退出捕获需要有界原业务事务");
        List<ObserverExitOriginal> result=new ArrayList<>();Set<Long> unique=new HashSet<>();
        for(var role:roles.stream().sorted((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId())).toList()){
            if(role.getPlayerId()==0||role.getOwnerAccount().isEmpty()||role.getOwnerRevision()<=0||!unique.add(role.getPlayerId()))throw new SQLException("退出捕获缺少原角色身份或重复");
            String operation="";byte[] addBytes=new byte[0];int currentState=5;
            try(var q=query(c,timeout,"SELECT operation_id,owner_account,owner_revision,state,add_request FROM match_global_watching WHERE player_id=? FOR UPDATE",u64(role.getPlayerId()));var row=q.executeQuery()){
                if(row.next()){
                    currentState=row.getInt(4);if(row.wasNull()||currentState<1||currentState>5)throw new SQLException("退出捕获的观战状态未知");
                    if(currentState!=5){operation=row.getString(1);addBytes=row.getBytes(5);
                        if(!role.getOwnerAccount().equals(row.getString(2))||Long.parseUnsignedLong(row.getString(3))!=role.getOwnerRevision()||operation==null||operation.isEmpty()||addBytes==null)throw new SQLException("观战退出不能用当前归属补旧原件");
                        if(currentState!=1&&addBytes.length==0)throw new SQLException("已许可观战缺少原Add，不能推断安全退出");
                    }
                }
            }
            String head=null;
            try(var q=query(c,timeout,"SELECT request_id FROM match_global_watch_head WHERE player_id=? FOR UPDATE",u64(role.getPlayerId()));var row=q.executeQuery()){if(row.next())head=row.getString(1);}
            if(head==null||head.isEmpty()){if(currentState!=5)throw new SQLException("原观战缺少永久head");continue;}
            // 尚未许可Add的head与新参战意图同事务关闭；迟到handler必须先核此永久回执。
            if(!head.equals(operation)){
                byte[] headAdd=null;boolean owner=false,cancelled=false;
                try(var q=query(c,timeout,"SELECT owner_account,owner_revision,cancelled,original_add FROM match_global_watch_request WHERE player_id=? AND request_id=? FOR UPDATE",u64(role.getPlayerId()),head);var row=q.executeQuery()){
                    if(!row.next())throw new SQLException("观战head缺少永久请求回执");
                    owner=role.getOwnerAccount().equals(row.getString(1))&&Long.parseUnsignedLong(row.getString(2))==role.getOwnerRevision();cancelled=row.getBoolean(3);headAdd=row.getBytes(4);
                    if(headAdd==null)throw new SQLException("观战永久Add列损坏");
                }
                if(owner&&!cancelled){
                    if(headAdd.length!=0)throw new SQLException("head已许可Add但缺少对应当前原件");
                    sealUnprepared(c,role,head,timeout);
                    result.add(identity(role).setOperationId(head).setHeadRequestId(head).build());
                }
            }
            if(currentState==5)continue;
            byte[] permanentAdd;boolean cancelled;
            try(var q=query(c,timeout,"SELECT owner_account,owner_revision,cancelled,original_add FROM match_global_watch_request WHERE player_id=? AND request_id=? FOR UPDATE",u64(role.getPlayerId()),operation);var row=q.executeQuery()){
                if(!row.next()||!role.getOwnerAccount().equals(row.getString(1))||Long.parseUnsignedLong(row.getString(2))!=role.getOwnerRevision())throw new SQLException("原观战缺少同身份永久回执");
                cancelled=row.getBoolean(3);permanentAdd=row.getBytes(4);
            }
            if(!Arrays.equals(permanentAdd,addBytes)||cancelled)throw new SQLException("当前观战与永久Add原件不一致");
            var exit=identity(role).setOperationId(operation).setHeadRequestId(head);
            if(addBytes.length==0){
                sealUnprepared(c,role,operation,timeout);
                try(var q=query(c,timeout,"UPDATE match_global_watching SET state=5 WHERE player_id=? AND operation_id=? AND state=1 AND add_request=?",u64(role.getPlayerId()),operation,new byte[0])){if(q.executeUpdate()!=1)throw new SQLException("未许可Add的原观战已经变化");}
            }else try{
                var add=AddObserverRequest.parseFrom(addBytes);
                if(add.getObserverPlayerId()!=role.getPlayerId()||!add.getSpectatorOperationId().equals(operation)||add.getTargetInstanceId().isEmpty()||add.getAdmitBeforeMs()==0)throw new SQLException("原观战Add身份或许可无效");
                try(var q=query(c,timeout,"SELECT host,port,node_id,instance_id FROM match_global_formation WHERE battle_id=?",u64(add.getBattleId()));var row=q.executeQuery()){
                    if(!row.next()||!add.getTargetInstanceId().equals(row.getString(4)))throw new SQLException("原观战固定落点缺失或已变");
                    exit.setAdd(add).setRpcHost(row.getString(1)).setRpcPort(row.getInt(2)).setNodeId(row.getInt(3)).setInstanceId(row.getString(4));
                    if(exit.getRpcHost().isEmpty()||exit.getRpcPort()==0||exit.getNodeId()==0)throw new SQLException("退出捕获缺少固定RPC目标");
                }
            }catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new SQLException("观战退出原Add损坏",bad);}
            result.add(exit.build());
        }return List.copyOf(result);
    }
    private static ObserverExitOriginal.Builder identity(ObserverExitOriginal role){return ObserverExitOriginal.newBuilder().setPlayerId(role.getPlayerId()).setOwnerAccount(role.getOwnerAccount()).setOwnerRevision(role.getOwnerRevision());}
    private static void sealUnprepared(Connection c,ObserverExitOriginal role,String operation,int timeout)throws SQLException {
        try(var q=query(c,timeout,"UPDATE match_global_watch_request SET cancelled=1 WHERE player_id=? AND request_id=? AND owner_account=? AND owner_revision=? AND original_add=?",u64(role.getPlayerId()),operation,role.getOwnerAccount(),u64(role.getOwnerRevision()),new byte[0])){if(q.executeUpdate()!=1)throw new SQLException("未许可Add的永久原件已经变化");}
    }
    public static boolean provedClosed(Connection c,ObserverExitOriginal original,int timeout)throws SQLException {
        try(var q=query(c,timeout,"SELECT owner_account,owner_revision,cancelled,original_add FROM match_global_watch_request WHERE player_id=? AND request_id=?",u64(original.getPlayerId()),original.getOperationId());var row=q.executeQuery()){
            if(!row.next()||!original.getOwnerAccount().equals(row.getString(1))||Long.parseUnsignedLong(row.getString(2))!=original.getOwnerRevision())throw new SQLException("退出证明缺少原身份回执");
            if(!Arrays.equals(row.getBytes(4),original.hasAdd()?original.getAdd().toByteArray():new byte[0]))throw new SQLException("退出证明的原Add不同");return row.getBoolean(3);
        }
    }
}
