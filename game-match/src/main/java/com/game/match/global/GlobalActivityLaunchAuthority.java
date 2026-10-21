package com.game.match.global;

import com.game.match.global.pb.GlobalTicketRow;
import com.game.pbmysql.PbMysql;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.guild.GuildTrialLaunchOriginal;
import com.game.proto.match.StartActivityBattleRequest;
import com.google.protobuf.ByteString;
import java.sql.*;
import java.util.*;

/** 活动调用只能消费guild已提交的同意原件；调用方先持完整route/P，本类再锁launch/role。 */
final class GlobalActivityLaunchAuthority {
    private GlobalActivityLaunchAuthority(){}
    static void lock(Connection c,ByteString payload,List<GlobalTicketRow> members)throws Exception {
        var request=StartActivityBattleRequest.parseFrom(payload);var context=request.getActivityContext();
        if(context.getKind()!=eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL||request.getRequestId()==0||context.getLaunchRequestId()!=request.getRequestId())throw new SQLException("活动缺少正规历练launch原件");
        GuildTrialLaunchOriginal original;byte[] raw;long created;
        try(var q=c.prepareStatement("SELECT state,original_proto,request_digest,created_ms FROM guild_trial_launch WHERE request_id=? FOR UPDATE")){
            q.setQueryTimeout(3);q.setObject(1,PbMysql.uint64(request.getRequestId()));try(var row=q.executeQuery()){
                if(!row.next()||row.getInt(1)!=1)throw new SQLException("活动原launch不处于PREPARED");raw=row.getBytes(2);created=Long.parseUnsignedLong(row.getString(4));
                if(raw==null||raw.length==0||raw.length>65536||!java.security.MessageDigest.isEqual(java.security.MessageDigest.getInstance("SHA-256").digest(raw),row.getBytes(3)))throw new SQLException("活动原launch摘要损坏");
                original=GuildTrialLaunchOriginal.parseFrom(raw);
            }
        }
        if(original.getRequestId()!=request.getRequestId()||original.getCreatedMs()!=created||original.getGuildId()==0||original.getActivityId()==0||original.getPeriodKey()==0||original.getGuildPeriodKey()==0||original.getDungeonId()==0||original.getRosterCount()<2||original.getRosterCount()>5)throw new SQLException("活动永久原件范围非法");
        List<Long> order=new ArrayList<>();order.add(original.getInitiatorPlayerId());Map<Long,GlobalTicketRow> identities=new HashMap<>();long previous=0;
        for(var role:original.getRosterList()){
            if(role.getPlayerId()==0||role.getAccount().isEmpty()||role.getOwnerRevision()<=0||previous!=0&&Long.compareUnsigned(previous,role.getPlayerId())>=0)throw new SQLException("活动授权集合身份或规范顺序非法");previous=role.getPlayerId();
            identities.put(role.getPlayerId(),GlobalTicketRow.newBuilder().setPlayerId(role.getPlayerId()).setOwnerAccount(role.getAccount()).setOwnerRevision(role.getOwnerRevision()).build());
            if(role.getPlayerId()!=original.getInitiatorPlayerId())order.add(role.getPlayerId());
        }
        if(!identities.containsKey(original.getInitiatorPlayerId())||members.size()!=identities.size())throw new SQLException("活动发起者或完整授权名单不同");
        var expected=StartActivityBattleRequest.newBuilder().setRequestId(original.getRequestId()).setBattleConfigId(original.getDungeonId()).addAllMemberPlayerIds(order)
            .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(original.getGuildId()).setActivityId(original.getActivityId()).setPeriodKey(original.getPeriodKey()).setGuildPeriodKey(original.getGuildPeriodKey()).setInitiatorPlayerId(original.getInitiatorPlayerId()).setLaunchRequestId(original.getRequestId())).build();
        if(!expected.equals(request)||!order.equals(members.stream().map(GlobalTicketRow::getPlayerId).toList()))throw new SQLException("活动Start正文不是原launch的完整确定请求");
        for(var member:members){var role=identities.get(member.getPlayerId());if(role==null||!member.getOwnerAccount().equals(role.getOwnerAccount())||member.getOwnerRevision()!=role.getOwnerRevision())throw new SQLException("活动不能从当前profile替换原同意身份");}
        try(var q=c.prepareStatement("SELECT player_id,account,owner_revision,state FROM guild_trial_launch_role WHERE request_id=? ORDER BY player_id FOR UPDATE")){
            q.setQueryTimeout(3);q.setObject(1,PbMysql.uint64(request.getRequestId()));try(var rows=q.executeQuery()){
                int count=0;while(rows.next()){long player=Long.parseUnsignedLong(rows.getString(1));var role=identities.get(player);
                    if(role==null||!role.getOwnerAccount().equals(rows.getString(2))||Long.parseUnsignedLong(rows.getString(3))!=role.getOwnerRevision()||rows.getInt(4)!=1)throw new SQLException("活动原角色ACTIVE投影不同");count++;}
                if(count!=identities.size())throw new SQLException("活动原角色投影不完整");
            }
        }
    }
}
