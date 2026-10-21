package com.game.match.global;

import com.game.match.global.pb.GlobalTicketRow;
import com.game.pbmysql.PbMysql;
import com.game.proto.team.*;
import com.google.protobuf.ByteString;
import java.sql.*;
import java.util.*;

/** Match不依赖game-team模块；在全员P锁之后核Team持久原件，随后才允许direct/票提交。 */
final class GlobalTeamLaunchAuthority {
    private GlobalTeamLaunchAuthority() { }
    static TeamLaunchOriginal original(ByteString bytes) {
        try {
            var original=TeamLaunchOriginal.parseFrom(bytes);
            if(bytes.isEmpty()||!original.hasActor()||!original.hasRequest()||original.getTeamId()==0||original.getTeamVersion()<=0
                    ||original.getLockToken().isEmpty()||original.getMembersCount()<1||original.getMembersCount()>5
                    ||original.getActor().getPlayerId()==0||original.getActor().getOwnerAccount().isEmpty()||original.getActor().getOwnerRevision()<=0
                    ||original.getRequest().getExpectedTeamId()!=original.getTeamId()||original.getEnqueuedMs()<=0||original.getNotBeforeMs()<original.getEnqueuedMs()
                    ||original.getLockTtlSeconds()<1||original.getLockTtlSeconds()>com.game.api.match.MatchBudgets.MAX_TEAM_LOCK_SECONDS)
                throw new IllegalArgumentException("整队开局原件字段缺失或越界");
            GlobalMatchStore.validateRequestId(original.getRequest().getRequestId());
            var members=original.getMembersList().stream().sorted(Comparator.comparingInt(TeamLaunchMember::getRosterOrder)).toList();
            Set<Long> ids=new HashSet<>();Set<String> tickets=new HashSet<>();
            for(int i=0;i<members.size();i++) {
                var member=members.get(i);var role=member.getOriginal();
                if(member.getRosterOrder()!=i+1||role.getPlayerId()==0||role.getOwnerAccount().isEmpty()||role.getOwnerRevision()<=0
                        ||member.getTicket().isEmpty()||!ids.add(role.getPlayerId())||!tickets.add(member.getTicket()))throw new IllegalArgumentException("整队原名单/显式顺序或身份无效");
            }
            if(!ids.contains(original.getActor().getPlayerId())||!ids.equals(original.getZonesMap().keySet())||original.getZonesMap().values().stream().anyMatch(zone->zone==0))
                throw new IllegalArgumentException("整队原位置或发起者不在名单");
            return original;
        }catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new IllegalArgumentException("整队开局原件损坏",invalid);}
    }
    static void bind(com.game.match.global.pb.GlobalDirectIntentRow key,TeamLaunchOriginal original,ByteString payload){
        var request=GlobalMatchStore.validateRequestId(original.getRequest().getRequestId());var actor=original.getActor();
        if(key.getKind()!=1||key.getOwnerId()!=actor.getPlayerId()||!key.getOwnerAccount().equals(actor.getOwnerAccount())||key.getOwnerRevision()!=actor.getOwnerRevision()||key.getRequestHigh()!=request.getMostSignificantBits()||key.getRequestLow()!=request.getLeastSignificantBits()||!payload.equals(original.getRequest().toByteString()))throw new SecurityException("整队业务键未绑定完整原发起者/UUID/正文");
    }
    static int lock(Connection c,TeamLaunchOriginal original,ByteString raw,List<GlobalTicketRow> members)throws Exception {
        if(members.size()!=original.getMembersCount())throw new SecurityException("整队开局成员数量不同");
        var ordered=original.getMembersList().stream().sorted(Comparator.comparingInt(TeamLaunchMember::getRosterOrder)).toList();
        for(int i=0;i<ordered.size();i++) {
            var expected=ordered.get(i);var actual=members.get(i);var role=expected.getOriginal();
            if(actual.getPlayerId()!=role.getPlayerId()||!actual.getOwnerAccount().equals(role.getOwnerAccount())||actual.getOwnerRevision()!=role.getOwnerRevision()
                    ||!actual.getTicket().equals(expected.getTicket())||actual.getConfigId()!=original.getRequest().getBattleConfigId()||actual.getTeamId()!=original.getTeamId())
                throw new SecurityException("Match票与整队原名单不同");
            if(actual.getZoneId()!=original.getZonesOrThrow(role.getPlayerId())||actual.getZoneId()!=GlobalQueueUnits.home(c,role.getPlayerId()))throw new SQLException("整队原home与已锁路由不同，不能补当前区");
        }
        com.game.common.player.MatchEntryAuthority.requireNoPendingLaunches(c,members.stream().map(GlobalTicketRow::getPlayerId).toList(),original.getRequest().getRequestId(),0,3);
        TeamRecord current;long currentVersion;boolean tombstone;String activeLaunch;
        try(var q=c.prepareStatement("SELECT version,record_proto,tombstone,active_launch_id FROM team_authority WHERE team_id=? FOR UPDATE")) {
            q.setQueryTimeout(3);q.setObject(1,PbMysql.uint64(original.getTeamId()));
            try(var row=q.executeQuery()) {
                if(!row.next())throw new SQLException("整队SQL权威记录缺失");
                currentVersion=Long.parseUnsignedLong(row.getString(1));current=TeamRecord.parseFrom(row.getBytes(2));tombstone=row.getBoolean(3);activeLaunch=row.getString(4);
            }
        }
        try(var q=c.prepareStatement("SELECT team_id,team_version,lock_token,actor_player_id,owner_account,owner_revision,request_proto,record_proto,state,original_proto FROM team_launch WHERE request_id=? FOR UPDATE")) {
            q.setQueryTimeout(3);q.setString(1,original.getRequest().getRequestId());
            try(var row=q.executeQuery()) {
                if(!row.next()||Long.parseUnsignedLong(row.getString(1))!=original.getTeamId()||Long.parseUnsignedLong(row.getString(2))!=original.getTeamVersion()
                        ||!original.getLockToken().equals(row.getString(3))||Long.parseUnsignedLong(row.getString(4))!=original.getActor().getPlayerId()
                        ||!original.getActor().getOwnerAccount().equals(row.getString(5))||Long.parseUnsignedLong(row.getString(6))!=original.getActor().getOwnerRevision()
                        ||!original.getRequest().toByteString().equals(ByteString.copyFrom(row.getBytes(7)))||!raw.equals(ByteString.copyFrom(row.getBytes(10))))
                    throw new SecurityException("整队SQL原launch与提交原件不同");
                int state=row.getInt(9);if(state<1||state>5)throw new SQLException("整队原launch状态未知");
                if(state==4||state==5)return state; // 仅允许Match重放已有决定；调用方不得据此创建新意图。
                try(var roles=c.prepareStatement("SELECT player_id,owner_account,owner_revision,state FROM team_launch_role WHERE request_id=? ORDER BY player_id FOR UPDATE")){
                    roles.setQueryTimeout(3);roles.setString(1,original.getRequest().getRequestId());try(var records=roles.executeQuery()){
                        Map<Long,com.game.proto.team.TeamOriginalRole> expected=new HashMap<>();original.getMembersList().forEach(member->expected.put(member.getOriginal().getPlayerId(),member.getOriginal()));int count=0;
                        while(records.next()){var identity=expected.get(Long.parseUnsignedLong(records.getString(1)));if(identity==null||!identity.getOwnerAccount().equals(records.getString(2))||identity.getOwnerRevision()!=Long.parseUnsignedLong(records.getString(3))||records.getInt(4)!=1)throw new SQLException("Team原ACTIVE角色投影不同");count++;}
                        if(count!=expected.size())throw new SQLException("Team原ACTIVE角色投影不完整");
                    }
                }
                var before=TeamRecord.parseFrom(row.getBytes(8));
                // 共享TeamRecord解析保留私有owner字段的unknown原件；全member PB比较，不丢弃身份未知字段。
                var comparator=Comparator.comparingLong((TeamMemberRecord member)->Integer.toUnsignedLong(member.getJoinSeq()))
                        .thenComparing((a,b)->Long.compareUnsigned(a.getPlayerId(),b.getPlayerId()));
                List<Long> roster=new ArrayList<>();roster.add(current.getLeaderId());current.getMembersList().stream().sorted(comparator)
                        .map(TeamMemberRecord::getPlayerId).filter(player->player!=current.getLeaderId()).forEach(roster::add);
                if(tombstone||currentVersion<=0||currentVersion<original.getTeamVersion()||current.getLeaderId()!=original.getActor().getPlayerId()
                        ||!original.getRequest().getRequestId().equals(activeLaunch)
                        ||current.getMembersCount()!=original.getMembersCount()||current.getMatchLockRosterCount()!=original.getMembersCount()
                        ||!roster.equals(ordered.stream().map(member->member.getOriginal().getPlayerId()).toList())
                        ||before.getLeaderId()!=current.getLeaderId()||!current.getMatchLockToken().equals(original.getLockToken())
                        ||!before.getMatchLockToken().equals(original.getLockToken())||!current.getMembersList().stream().sorted(comparator).toList().equals(before.getMembersList().stream().sorted(comparator).toList())
                        ||!new HashSet<>(current.getMatchLockRosterList()).equals(new HashSet<>(original.getMembersList().stream().map(m->m.getOriginal().getPlayerId()).toList())))
                    throw new SecurityException("整队原leader/锁/完整名册已经变更");
                return state;
            }
        }
    }
}
