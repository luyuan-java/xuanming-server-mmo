package com.game.team.store;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.PlayerSessionAuthority;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.proto.team.TeamChangeReason;
import com.game.team.proto.*;
import com.game.team.rules.*;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;

/**
 * TeamStore内部的SQL实现。先预读有界参与者，再按uint64升序锁全部P，最后锁Team/索引/回执。
 * 预读版本变化只回到事务外重试；绝不在拿到Team锁后补P。SQL原件、epoch墓碑和请求回执无TTL。
 */
final class SqlTeamAuthority {
    private static final String NOW="CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)";
    private final AuthorityJdbc sql;
    private final SessionContext actor;
    private final String method, requestId;
    private final ByteString payload, digest;
    SqlTeamAuthority(DataSource source){this(new AuthorityJdbc(source),null,"","",ByteString.EMPTY,ByteString.EMPTY);}
    private SqlTeamAuthority(AuthorityJdbc sql,SessionContext actor,String method,String requestId,ByteString payload,ByteString digest){
        this.sql=sql;this.actor=actor;this.method=method;this.requestId=requestId;this.payload=payload;this.digest=digest;
    }
    SqlTeamAuthority forActor(SessionContext original,String method,Message request){
        if(original==null||original.getPlayerId()==0||original.getAccount().isEmpty()||original.getOwnerRevision()<=0)
            throw new SecurityException("组队请求缺少原会话身份");
        var field=request.getDescriptorForType().findFieldByName("request_id");
        String id=field==null?"":(String)request.getField(field);
        // 纯读没有业务UUID；所有写由派发契约和此处共同约束。
        if(!Set.of("GetMyTeam","ListMyInvites").contains(method)){
            UUID uuid=UUID.fromString(id);
            if(!uuid.toString().equals(id)||(uuid.getMostSignificantBits()==0&&uuid.getLeastSignificantBits()==0))throw new IllegalArgumentException("组队请求号必须是非空规范UUID");
        }
        byte[] canonical=deterministic(request);
        return new SqlTeamAuthority(sql,original,method,id,ByteString.copyFrom(canonical),sha(canonical));
    }
    boolean bound(){return actor!=null;}
    private void requireActor(){if(actor==null)throw new SecurityException("生产组队写入必须保留原会话");}
    private <T>T run(Deadline d,AuthorityJdbc.Work<T> work,boolean write){
        try{return write?sql.tx(d,work):sql.snapshot(d,work);}catch(SQLException|SecurityException e){throw new DependencyException("组队SQL权威不可用或原身份已失效",e);}
    }
    private void actorLock(AuthorityJdbc.Session c,Deadline d)throws SQLException{
        requireActor();PlayerSessionAuthority.lock(c.connection(),actor.getPlayerId(),actor.getAccount(),actor.getOwnerRevision(),timeout(d));
    }
    private static int timeout(Deadline d){return (int)Math.max(1,Math.min(3,(d.remainingMillis()+999)/1000));}
    private static ByteString sha(byte[] bytes){try{return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static byte[] deterministic(com.google.protobuf.MessageLite message){byte[] bytes=new byte[message.getSerializedSize()];var output=com.google.protobuf.CodedOutputStream.newInstance(bytes);output.useDeterministicSerialization();try{message.writeTo(output);output.checkNoSpaceLeft();return bytes;}catch(java.io.IOException e){throw new IllegalStateException("Team原件序列化失败",e);}}
    private static TeamRecord parse(byte[] bytes)throws SQLException{try{return bytes==null||bytes.length==0?null:TeamRecord.parseFrom(bytes);}catch(com.google.protobuf.InvalidProtocolBufferException e){throw new SQLException("队伍原PB损坏",e);}}
    private static long now(AuthorityJdbc.Session c)throws SQLException{return c.one("SELECT "+NOW,r->r.getLong(1));}
    private record TeamRow(long version,TeamRecord record,boolean tombstone){}
    private static TeamRow team(AuthorityJdbc.Session c,long id,boolean lock)throws SQLException{
        return c.one("SELECT version,record_proto,tombstone,active_launch_id FROM team_authority WHERE team_id=?"+(lock?" FOR UPDATE":""),r->{
            long version=r.getLong(1);var record=parse(r.getBytes(2));boolean dead=r.getBoolean(3);String active=r.getString(4);
            if(version==0||active==null||dead&&(record!=null||!active.isEmpty())||!dead&&(record==null||record.getTeamId()!=id||!active.equals(record.getLaunchRequestId())||record.getMembersCount()<1||record.getMembersCount()>TeamLimits.CAPACITY||record.getApplicationsCount()>TeamLimits.MAX_APPLICATIONS||record.getInvitesCount()>TeamLimits.MAX_INVITES_PER_TEAM))throw new SQLException("SQL队伍版本、墓碑或原件关联不一致");
            return new TeamRow(version,record,dead);
        },AuthorityJdbc.u64(id));
    }
    private static TeamMemberAuthorityRow member(AuthorityJdbc.Session c,long id,boolean lock)throws SQLException{
        var row=c.one("SELECT team_id,epoch,owner_account,owner_revision FROM team_member_authority WHERE player_id=?"+(lock?" FOR UPDATE":""),r->TeamMemberAuthorityRow.newBuilder().setPlayerId(id).setTeamId(r.getLong(1)).setEpoch(r.getLong(2)).setOwnerAccount(r.getString(3)).setOwnerRevision(r.getLong(4)).build(),AuthorityJdbc.u64(id));
        return row==null?TeamMemberAuthorityRow.newBuilder().setPlayerId(id).build():row;
    }
    private Snapshot snapshot(AuthorityJdbc.Session c,long player,long team,boolean lock)throws SQLException{
        var row=team==0?null:team(c,team,lock);
        var index=player==0?TeamMemberAuthorityRow.getDefaultInstance():member(c,player,lock);
        return new Snapshot(player,index.getTeamId(),index.getEpoch(),team,row==null?0:row.version(),row==null||row.tombstone()?null:row.record(),Long.MAX_VALUE,now(c));
    }
    Snapshot read(long player,long team,Deadline d){return run(d,c->{if(bound())actorLock(c,d);return snapshot(c,player,team,false);},bound());}
    FreeRead readFree(long player,Deadline d){
        try{return FreeRead.ok(run(d,c->{if(bound())actorLock(c,d);var i=member(c,player,false);return snapshot(c,player,i.getTeamId(),false);},bound()),false);}
        catch(DependencyException e){return FreeRead.failed(false,e);}
    }
    MembersSnapshot readMembers(long teamId,Deadline d){return run(d,c->{
        var row=team(c,teamId,false);long time=now(c);if(row==null||row.tombstone())return new MembersSnapshot(teamId,row==null?0:row.version(),null,time,Map.of());
        Map<Long,IndexEntry> indexes=new LinkedHashMap<>();for(long p:TeamRules.memberIds(row.record())){var i=member(c,p,false);if(i.getTeamId()!=teamId)throw new SQLException("SQL队伍成员与永久索引不一致");indexes.put(p,new IndexEntry(i.getTeamId(),i.getEpoch()));}
        return new MembersSnapshot(teamId,row.version(),row.record(),time,Map.copyOf(indexes));
    },false);}
    InviteList invites(long player,Deadline d){return run(d,c->{if(bound())actorLock(c,d);long time=now(c);
        var entries=c.list("SELECT team_id,expires_ms FROM team_invite_authority WHERE invitee_id=? AND expires_ms>? ORDER BY expires_ms,team_id LIMIT 11",r->new InviteIndexEntry(r.getLong(1),r.getLong(2),r.getString(2)),AuthorityJdbc.u64(player),time);
        if(entries.size()>TeamLimits.MAX_PENDING_INVITES_PER_INVITEE)throw new SQLException("邀请索引超出硬上限");return new InviteList(time,entries);
    },bound());}
    boolean pruneInvite(long player,long team,String expiry,Deadline d){
        // 读路径不修复SQL事实；索引与原PB必须在同一变更事务更新，只有已经过期的本项可删除。
        return run(d,c->{actorLock(c,d);if(actor.getPlayerId()!=player)throw new SecurityException("只能清理自己的过期邀请");return c.update("DELETE FROM team_invite_authority WHERE invitee_id=? AND team_id=? AND expires_ms=? AND expires_ms<="+NOW,AuthorityJdbc.u64(player),AuthorityJdbc.u64(team),Long.parseUnsignedLong(expiry))!=0;},true);
    }
    private static SortedSet<Long> participants(TeamRecord record,long caller,long target){
        var result=new TreeSet<Long>(Long::compareUnsigned);if(caller!=0)result.add(caller);if(target!=0)result.add(target);
        if(record!=null){for(var m:record.getMembersList())result.add(m.getPlayerId());for(var a:record.getApplicationsList()){result.add(a.getPlayerId());var original=consent(a.getConsentOriginal());result.add(original.getInitiator().getPlayerId());result.add(original.getRecipient().getPlayerId());}for(var i:record.getInvitesList()){result.add(i.getInviteeId());var original=consent(i.getConsentOriginal());result.add(original.getInitiator().getPlayerId());result.add(original.getRecipient().getPlayerId());}}
        int maximum=2+TeamLimits.CAPACITY+3*TeamLimits.MAX_APPLICATIONS+3*TeamLimits.MAX_INVITES_PER_TEAM;
        if(result.size()>maximum||result.contains(0L))throw new SecurityException("队伍参与者集合越界");return result;
    }
    private static SortedSet<Long> participants(Snapshot snapshot,long caller,long target){return participants(TeamRules.pruneExpired(snapshot.record(),snapshot.nowMs()),caller,target);}
    private static Map<Long,SessionState> sessions(SessionLoader loader,Snapshot snapshot,Deadline d){
        if(loader==null||snapshot.record()==null)return Map.of();try{return loader.load(TeamRules.memberIds(snapshot.record()),d);}catch(RuntimeException unavailable){return Map.of();}
    }
    private record Owner(String account,long revision,int zone){}
    private Map<Long,Owner> lockPeople(AuthorityJdbc.Session c,SortedSet<Long> people,Deadline d)throws SQLException{
        requireActor();if(!people.contains(actor.getPlayerId()))throw new SecurityException("发起原身份不在锁集合内");
        Map<Long,Owner> result=new HashMap<>();
        com.game.common.player.PlayerRouteAuthority.lock(c.connection(),people,timeout(d));
        for(long player:people){
            var owner=c.one("SELECT account,owner_revision,trade_listing_id,zone_id FROM player WHERE player_id=? FOR UPDATE",r->{String account=r.getString(1);long revision=r.getLong(2);long zone=r.getLong(4);if(account==null||account.isEmpty()||revision<=0||!"0".equals(r.getString(3))||zone==0||zone>0xffff_ffffL)throw new SQLException("队伍参与角色缺失或冻结");return new Owner(account,revision,(int)zone);},AuthorityJdbc.u64(player));
            if(owner==null)throw new SecurityException("队伍参与角色不存在");result.put(player,owner);
            if(player==actor.getPlayerId())PlayerSessionAuthority.lock(c.connection(),player,actor.getAccount(),actor.getOwnerRevision(),timeout(d));
        }
        return result;
    }
    private static void verify(String account,long revision,Owner owner){if(owner==null||account.isEmpty()||revision<=0||!account.equals(owner.account())||revision!=owner.revision())throw new SecurityException("旧归属队伍原件不能恢复或授权");}
    private static com.game.proto.team.TeamConsentOriginal consent(ByteString raw){try{var original=com.game.proto.team.TeamConsentOriginal.parseFrom(raw);if(!original.hasInitiator()||!original.hasRecipient()||original.getPlayerId()==0)throw new SecurityException("同意原件缺少双方身份");return original;}catch(com.google.protobuf.InvalidProtocolBufferException e){throw new SecurityException("同意原件损坏",e);}}
    private static void verifyConsent(ByteString raw,Map<Long,Owner> owners){var original=consent(raw);for(var role:List.of(original.getInitiator(),original.getRecipient()))verify(role.getOwnerAccount(),role.getOwnerRevision(),owners.get(role.getPlayerId()));}
    private static ByteString consent(long player,long initiator,long recipient,Map<Long,Owner> owners){var a=owners.get(initiator);var b=owners.get(recipient);if(a==null||b==null)throw new SecurityException("同意双方未持P锁");return ByteString.copyFrom(deterministic(com.game.proto.team.TeamConsentOriginal.newBuilder().setPlayerId(player)
            .setInitiator(com.game.proto.team.TeamOriginalRole.newBuilder().setPlayerId(initiator).setOwnerAccount(a.account()).setOwnerRevision(a.revision()))
            .setRecipient(com.game.proto.team.TeamOriginalRole.newBuilder().setPlayerId(recipient).setOwnerAccount(b.account()).setOwnerRevision(b.revision())).build()));}
    private static void verifyRecord(TeamRecord rec,Map<Long,Owner> owners){if(rec==null)return;for(var m:rec.getMembersList())verify(m.getOwnerAccount(),m.getOwnerRevision(),owners.get(m.getPlayerId()));}
    private static void verifyJoining(TeamRecord before,Op op,Map<Long,Owner> owners){
        if(before==null)return;
        if(op.kind()==OpKind.RESPOND_INVITE&&op.accept()){var i=TeamRules.findInvite(before,op.caller());if(i!=null){verify(i.getOwnerAccount(),i.getOwnerRevision(),owners.get(op.caller()));verifyConsent(i.getConsentOriginal(),owners);}}
        if(op.kind()==OpKind.HANDLE_APPLICATION&&op.accept()){var a=TeamRules.findApplication(before,op.target());if(a!=null){verify(a.getOwnerAccount(),a.getOwnerRevision(),owners.get(op.target()));verifyConsent(a.getConsentOriginal(),owners);}}
    }
    private static Decision identities(Decision decision,TeamRecord before,Map<Long,Owner> owners){
        if(!decision.changed()||decision.record()==null)return decision;var rec=decision.record().toBuilder();
        for(int i=0;i<rec.getMembersCount();i++){var m=rec.getMembers(i);var o=owners.get(m.getPlayerId());if(o==null)throw new SecurityException("提交成员未持P锁");rec.setMembers(i,m.toBuilder().setOwnerAccount(o.account()).setOwnerRevision(o.revision()));}
        for(int i=0;i<rec.getApplicationsCount();i++){var a=rec.getApplications(i);if(before==null||TeamRules.findApplication(before,a.getPlayerId())==null){var o=owners.get(a.getPlayerId());rec.setApplications(i,a.toBuilder().setOwnerAccount(o.account()).setOwnerRevision(o.revision()).setConsentOriginal(consent(a.getPlayerId(),a.getPlayerId(),rec.getLeaderId(),owners)));}}
        for(int i=0;i<rec.getInvitesCount();i++){var inv=rec.getInvites(i);if(before==null||TeamRules.findInvite(before,inv.getInviteeId())==null||decision.invitedPlayer()==inv.getInviteeId()){var o=owners.get(inv.getInviteeId());rec.setInvites(i,inv.toBuilder().setOwnerAccount(o.account()).setOwnerRevision(o.revision()).setConsentOriginal(consent(inv.getInviteeId(),inv.getInviterId(),inv.getInviteeId(),owners)));}}
        return withRecord(decision,rec.build());
    }
    private static Decision withRecord(Decision d,TeamRecord rec){return new Decision(d.code(),d.param(),d.changed(),rec,d.joined(),d.kept(),d.left(),d.invitesAdded(),d.invitesRemoved(),d.reason(),d.actor(),d.leaderOfflineTransferred(),d.disbanded(),d.revokedInvitees(),d.rejectedApplicant(),d.invitedPlayer());}
    private TeamActionRow action(AuthorityJdbc.Session c,boolean lock)throws SQLException{
        if(requestId.isEmpty())return null;
        var result=c.one("SELECT method,request_digest,owner_account,owner_revision,result_proto,response_proto FROM team_action WHERE player_id=? AND request_id=?"+(lock?" FOR UPDATE":""),r->TeamActionRow.newBuilder().setPlayerId(actor.getPlayerId()).setRequestId(requestId).setMethod(r.getString(1)).setRequestDigest(ByteString.copyFrom(r.getBytes(2))).setOwnerAccount(r.getString(3)).setOwnerRevision(r.getLong(4)).setResultProto(ByteString.copyFrom(r.getBytes(5))).setResponseProto(ByteString.copyFrom(r.getBytes(6))).build(),AuthorityJdbc.u64(actor.getPlayerId()),requestId);
        if(result!=null&&(!method.equals(result.getMethod())||!digest.equals(result.getRequestDigest())||!actor.getAccount().equals(result.getOwnerAccount())||actor.getOwnerRevision()!=result.getOwnerRevision()))throw new SecurityException("同一组队请求号原件不符");return result;
    }
    private void saveAction(AuthorityJdbc.Session c,MutateResult result)throws SQLException{
        if(requestId.isEmpty())return;byte[] receipt=encode(result).toByteArray();
        c.update("INSERT INTO team_action(player_id,request_id,method,request_digest,owner_account,owner_revision,result_proto,response_proto) VALUES(?,?,?,?,?,?,?,?)",AuthorityJdbc.u64(actor.getPlayerId()),requestId,method,digest.toByteArray(),actor.getAccount(),actor.getOwnerRevision(),receipt,new byte[0]);
    }
    MutateResult replay(Deadline d){requireActor();return run(d,c->{actorLock(c,d);var a=action(c,false);return a==null||a.getResultProto().isEmpty()?null:decode(a.getResultProto());},true);}
    com.game.proto.team.TeamResponse response(Deadline d){requireActor();return run(d,c->{actorLock(c,d);var a=action(c,false);if(a==null||a.getResponseProto().isEmpty())return null;try{return com.game.proto.team.TeamResponse.parseFrom(a.getResponseProto());}catch(com.google.protobuf.InvalidProtocolBufferException e){throw new SQLException("组队回执回包损坏",e);}},true);}
    void completeResponse(com.game.proto.team.TeamResponse response,Deadline d){requireActor();if(requestId.isEmpty())return;run(d,c->{actorLock(c,d);var existing=action(c,true);
        if(existing==null)c.update("INSERT INTO team_action(player_id,request_id,method,request_digest,owner_account,owner_revision,result_proto,response_proto) VALUES(?,?,?,?,?,?,?,?)",AuthorityJdbc.u64(actor.getPlayerId()),requestId,method,digest.toByteArray(),actor.getAccount(),actor.getOwnerRevision(),new byte[0],response.toByteArray());
        else if(existing.getResponseProto().isEmpty())c.update("UPDATE team_action SET response_proto=? WHERE player_id=? AND request_id=?",response.toByteArray(),AuthorityJdbc.u64(actor.getPlayerId()),requestId);return null;},true);}
    MutateResult mutate(Bind bind,Op op,SessionLoader sessions,RuleConfig config,Deadline d){
        requireActor();if(op.caller()!=actor.getPlayerId())throw new SecurityException("组队操作与原身份不符");
        long bindingTarget=op.target();
        if(op.kind()==OpKind.APPLY)try{bindingTarget=com.game.proto.team.ApplyJoinTeamRequest.parseFrom(payload).getTargetPlayerId();}catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new DependencyException("申请原请求损坏",invalid);}
        final long originalTarget=bindingTarget;
        for(int attempt=0;attempt<TeamStore.COMMIT_RETRIES;attempt++){
            Snapshot before=read(bind.playerId(),bind.teamId(),d);var people=participants(before,op.caller(),originalTarget);
            Map<Long,SessionState> states=sessions(sessions,before,d);
            try{return run(d,c->{var owners=lockPeople(c,people,d);var policy=com.game.common.team.TeamPolicyAuthority.load(c.connection(),timeout(d));if(policy.allowCrossZone()!=config.allowCrossZone())throw new SQLException("组队写策略与SQL初次准入原件不同");Snapshot snap=snapshot(c,bind.playerId(),bind.teamId(),true);
                if(snap.version()!=before.version()||!participants(snap,op.caller(),originalTarget).equals(people))throw new Changed();
                if(op.kind()!=OpKind.REFRESH){var a=action(c,true);if(a!=null){
                    if(!a.getResultProto().isEmpty())return decode(a.getResultProto());
                    if(!a.getResponseProto().isEmpty())try{var response=com.game.proto.team.TeamResponse.parseFrom(a.getResponseProto());return result(response.getErrorMessage().getId()==0?Outcome.UNCHANGED:Outcome.REJECTED,response.getErrorMessage().getId(),0,snap,Decision.unchanged(),null);}catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new SQLException("请求回包原件损坏",invalid);}
                }}
                verifyRecord(snap.record(),owners);verifyJoining(TeamRules.pruneExpired(snap.record(),snap.nowMs()),op,owners);
                if((op.kind()==OpKind.CREATE||op.kind()==OpKind.APPLY)&&op.callerZone()!=owners.get(op.caller()).zone()||op.kind()==OpKind.INVITE&&op.targetZone()!=owners.get(op.target()).zone())throw new SQLException("home预读已变化，拒绝以旧归属区提交新队伍意图");
                MutateResult result;
                if(op.kind()==OpKind.APPLY&&(originalTarget==0||member(c,originalTarget,true).getTeamId()!=bind.teamId()))result=result(Outcome.REJECTED,TeamTips.STATE_CHANGED,0,snap,Decision.unchanged(),null);
                else if(bind.mode()==Bind.Mode.CALLER&&(bind.teamId()==0||snap.playerTeamId()!=bind.teamId()))result=result(Outcome.NOT_BOUND,0,0,snap,Decision.unchanged(),null);
                else if(op.kind()!=OpKind.CREATE&&snap.record()==null)result=result(Outcome.RECORD_MISSING,0,0,snap,Decision.unchanged(),null);
                else if(op.kind()==OpKind.CREATE&&snap.version()!=0)result=result(Outcome.REJECTED,TeamTips.STATE_CHANGED,0,snap,Decision.unchanged(),null);
                else {
                    Decision decision=TeamRules.apply(op.withCallerTeamId(snap.playerTeamId()),snap.record(),snap.nowMs(),states,config);
                    if(decision.code()==TeamTips.IN_MATCH&&snap.record()!=null&&!snap.record().getLaunchRequestId().isEmpty()){
                        // 先纯判定：无权/无效操作不取消队伍。合法名册变化只在同事务原unit取消证明后提交。
                        var unlocked=snap.record().toBuilder().clearLaunchRequestId().clearMatchLockToken().clearMatchLockExpireAtMs().clearMatchLockRoster().build();
                        Decision candidate=TeamRules.apply(op.withCallerTeamId(snap.playerTeamId()),unlocked,snap.nowMs(),states,config);
                        if(candidate.code()==0&&candidate.changed()&&constraints(c,snap,candidate)==0){
                            var original=storedLaunchForRecord(c,snap.record(),true);if(original==null||original.getOriginalProto().isEmpty())throw new SQLException("排队名册变动缺少永久原launch");
                            com.game.proto.team.TeamLaunchOriginal captured;try{captured=com.game.proto.team.TeamLaunchOriginal.parseFrom(original.getOriginalProto());}catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new SQLException("原launch损坏",invalid);}
                            if(com.game.common.player.TeamQueueAuthority.cancel(c.connection(),captured,original.getOriginalProto().toByteArray(),timeout(d))==com.game.common.player.TeamQueueAuthority.Result.CANCELLED){
                                decision=candidate;c.update("UPDATE team_launch_role SET state=2 WHERE request_id=?",original.getRequestId());c.update("UPDATE team_launch SET active=0 WHERE request_id=?",original.getRequestId());
                            }
                        }
                    }
                    decision=identities(decision,snap.record(),owners);
                    if(decision.code()!=0)result=result(Outcome.REJECTED,decision.code(),decision.param(),snap,decision,null);
                    else if(!decision.changed())result=result(Outcome.UNCHANGED,0,0,snap,decision,null);
                    else {int rejected=constraints(c,snap,decision);if(rejected!=0)result=result(Outcome.REJECTED,rejected,0,snap,Decision.unchanged(),null);
                        else {var commit=commit(c,snap,decision,owners);result=result(Outcome.COMMITTED,0,0,snap,decision,commit);}}
                }
                if(op.kind()!=OpKind.REFRESH)saveAction(c,result);return result;
            },true);}catch(Changed conflict){AuthorityJdbcCheck(d);}
        }
        return result(Outcome.REJECTED,TeamTips.STATE_CHANGED,0,null,Decision.unchanged(),null);
    }
    private static final class Changed extends RuntimeException {}
    private static void AuthorityJdbcCheck(Deadline d){try{AuthorityJdbc.check(d);}catch(SQLException e){throw new DependencyException("组队请求预算到期",e);}}
    private static MutateResult result(Outcome o,int code,long param,Snapshot snap,Decision decision,CommitResult commit){return new MutateResult(o,code,param,snap,decision,commit,List.of(),false,0);}
    com.game.proto.team.TeamView projectMatchView(com.game.proto.team.TeamView view,Deadline d){
        if(view.getTeamId()==0)return view;
        return run(d,c->{
            var players=view.getMembersList().stream().map(com.game.proto.team.TeamMemberView::getPlayerId).toList();
            com.game.common.player.PlayerRouteAuthority.lock(c.connection(),players,timeout(d));if(bound())actorLock(c,d);
            var current=team(c,view.getTeamId(),false);var result=view.toBuilder().setMatchFillSupported(true);
            if(current==null||current.tombstone()||current.version()!=view.getVersion()||current.record().getLaunchRequestId().isEmpty())return result.build();
            var launch=storedLaunchForRecord(c,current.record(),false);if(launch==null)throw new SQLException("队伍显示缺少原launch");
            var unit=c.one("SELECT state,target_size,enqueued_ms,config_id,launch_original FROM match_global_queue_unit WHERE unit_id=?",r->{
                if(!Arrays.equals(launch.getOriginalProto().toByteArray(),r.getBytes(5)))throw new SQLException("队伍排队显示原件不符");
                int state=r.getInt(1);if(state<1||state>4)throw new SQLException("队伍排队显示状态未知");
                if(state==1){int target=r.getInt(2);long enqueued=r.getLong(3);if(target<=view.getMembersCount()||target>TeamLimits.CAPACITY||enqueued<=0)throw new SQLException("队伍排队显示目标或SQL时钟无效");result.setMatchState(com.game.proto.team.TeamMatchState.TEAM_MATCH_STATE_QUEUED).setMatchTargetSize(target).setMatchQueuedAtMs(enqueued).setMatchBattleConfigId(r.getInt(4));}
                return true;
            },"t:"+Long.toUnsignedString(view.getTeamId())+":"+launch.getRequestId());
            return result.build();
        },true);
    }

    PinnedResult lockMatch(Snapshot expected,long caller,String token,List<Long> roster,long expiry,Map<Long,Integer> zones,int lockSeconds,Deadline d){
        requireActor();if(requestId.isEmpty()||!method.equals("StartTeamMatch")||caller!=actor.getPlayerId())throw new SecurityException("开局缺少原业务请求");
        return run(d,c->{var owners=lockPeople(c,participants(expected,caller,0),d);var snap=snapshot(c,caller,expected.teamId(),true);
            var previous=launch(c,requestId,true);if(previous!=null)return PinnedResult.committed(launchCommit(previous));
            if(snap.version()!=expected.version()||!Objects.equals(snap.record(),expected.record()))return PinnedResult.retry(List.of());
            verifyRecord(snap.record(),owners);Decision decision=TeamRules.lockMatch(snap.record(),caller,token,roster,expiry,snap.nowMs());
            if(decision.code()!=0)return PinnedResult.rejected(decision.code(),decision.param());
            com.game.common.player.MatchEntryAuthority.requireFree(c.connection(),roster,"",0,timeout(d));
            decision=withRecord(decision,decision.record().toBuilder().setLaunchRequestId(requestId).build());
            if(constraints(c,snap,decision)!=0)throw new SQLException("开局原成员索引不一致");var committed=commit(c,snap,decision,owners);
            if(!zones.keySet().equals(new HashSet<>(roster))||zones.values().stream().anyMatch(z->z==0)||lockSeconds<1)throw new SQLException("开局原位置/预算不完整");
            // 预检位置只是在线可达性输入；原launch的区服事实必须来自本事务完整P锁住的SQL home。
            Map<Long,Integer> homes=new LinkedHashMap<>();for(long p:roster)homes.put(p,owners.get(p).zone());
            var prepared=TeamLaunchSubmission.newBuilder().putAllZones(homes).setLockTtlSeconds(lockSeconds);
            committed.indexes().forEach((p,i)->prepared.addIndexes(TeamMemberAuthorityRow.newBuilder().setPlayerId(p).setTeamId(i.teamId()).setEpoch(i.epoch())));
            var submission=prepared.build();
            com.game.proto.team.StartTeamMatchRequest request;try{request=com.game.proto.team.StartTeamMatchRequest.parseFrom(payload);}catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new SQLException("开局原请求损坏",invalid);}
            var original=com.game.proto.team.TeamLaunchOriginal.newBuilder().setActor(com.game.proto.team.TeamOriginalRole.newBuilder().setPlayerId(caller).setOwnerAccount(actor.getAccount()).setOwnerRevision(actor.getOwnerRevision()))
                    .setTeamId(snap.teamId()).setTeamVersion(committed.version()).setLockToken(token).setRequest(request).putAllZones(homes).setLockTtlSeconds(lockSeconds).setEnqueuedMs(snap.nowMs()).setNotBeforeMs(snap.nowMs());
            int order=0;for(long p:roster){var o=owners.get(p);original.addMembers(com.game.proto.team.TeamLaunchMember.newBuilder().setOriginal(com.game.proto.team.TeamOriginalRole.newBuilder().setPlayerId(p).setOwnerAccount(o.account()).setOwnerRevision(o.revision())).setRosterOrder(++order).setTicket(TeamStore.ticketId(requestId,p)));}
            var roles=new ArrayList<com.game.proto.ObserverExitOriginal>();for(long p:roster){var o=owners.get(p);roles.add(com.game.proto.ObserverExitOriginal.newBuilder().setPlayerId(p).setOwnerAccount(o.account()).setOwnerRevision(o.revision()).build());}
            // 首次原Start只捕获一次：同完整P冻结旧watch head并把固定退出原件与launch原子提交。
            original.addAllObserverExits(com.game.common.player.ObserverExitAuthority.capture(c.connection(),roles,timeout(d)));
            c.update("INSERT INTO team_launch(request_id,team_id,team_version,lock_token,actor_player_id,owner_account,owner_revision,request_proto,record_proto,state,battle_id,updated_ms,original_proto,submission_proto,active) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)",requestId,AuthorityJdbc.u64(snap.teamId()),AuthorityJdbc.u64(committed.version()),token,AuthorityJdbc.u64(caller),actor.getAccount(),actor.getOwnerRevision(),payload.toByteArray(),decision.record().toByteArray(),1,0,snap.nowMs(),deterministic(original.build()),submission.toByteArray());
            for(long p:roster){var o=owners.get(p);c.update("INSERT INTO team_launch_role(player_id,request_id,state,owner_account,owner_revision) VALUES(?,?,1,?,?)",AuthorityJdbc.u64(p),requestId,o.account(),o.revision());}
            return PinnedResult.committed(committed);
        },true);
    }
    private TeamLaunchRow readLaunchRow(AuthorityJdbc.Session c,String id,boolean lock)throws SQLException{
        var row=c.one("SELECT team_id,team_version,lock_token,actor_player_id,owner_account,owner_revision,request_proto,record_proto,state,battle_id,updated_ms,original_proto,submission_proto,active FROM team_launch WHERE request_id=?"+(lock?" FOR UPDATE":""),r->TeamLaunchRow.newBuilder().setRequestId(id).setTeamId(r.getLong(1)).setTeamVersion(r.getLong(2)).setLockToken(r.getString(3)).setActorPlayerId(r.getLong(4)).setOwnerAccount(r.getString(5)).setOwnerRevision(r.getLong(6)).setRequestProto(ByteString.copyFrom(r.getBytes(7))).setRecordProto(ByteString.copyFrom(r.getBytes(8))).setState(r.getInt(9)).setBattleId(r.getLong(10)).setUpdatedMs(r.getLong(11)).setOriginalProto(ByteString.copyFrom(r.getBytes(12))).setSubmissionProto(ByteString.copyFrom(r.getBytes(13))).setActive(r.getBoolean(14)).build(),id);
        return row;
    }
    private TeamLaunchRow launch(AuthorityJdbc.Session c,String id,boolean lock)throws SQLException{
        var row=readLaunchRow(c,id,lock);
        if(row!=null&&(row.getActorPlayerId()!=actor.getPlayerId()||!row.getOwnerAccount().equals(actor.getAccount())||row.getOwnerRevision()!=actor.getOwnerRevision()||!row.getRequestProto().equals(payload)))throw new SecurityException("开局UUID与原件不一致");return row;
    }
    // 持久开局属于已锁住的Team，不属于随后Leave/Get等请求的UUID或payload。
    // 当前Start重放仍走launch()；此处只核原行与冻结Team及完整TeamLaunchOriginal。
    private TeamLaunchRow storedLaunchForRecord(AuthorityJdbc.Session c,TeamRecord record,boolean lock)throws SQLException{
        var row=readLaunchRow(c,record.getLaunchRequestId(),lock);if(row==null)return null;
        var originalRecord=parse(row.getRecordProto().toByteArray());
        com.game.proto.team.TeamLaunchOriginal captured;
        try{captured=com.game.proto.team.TeamLaunchOriginal.parseFrom(row.getOriginalProto());}
        catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new SQLException("永久开局原件损坏",invalid);}
        UUID id;try{id=UUID.fromString(row.getRequestId());}catch(IllegalArgumentException invalid){throw new SQLException("永久开局UUID损坏",invalid);}
        var start=captured.getRequest();var owner=captured.getActor();
        if(!id.toString().equals(row.getRequestId())||id.equals(new UUID(0,0))||row.getActorPlayerId()==0||row.getOwnerAccount().isEmpty()||row.getOwnerRevision()<=0
                ||!start.getRequestId().equals(row.getRequestId())||!row.getRequestProto().equals(ByteString.copyFrom(deterministic(start)))
                ||captured.getTeamId()!=row.getTeamId()||captured.getTeamVersion()!=row.getTeamVersion()||!captured.getLockToken().equals(row.getLockToken())
                ||owner.getPlayerId()!=row.getActorPlayerId()||!owner.getOwnerAccount().equals(row.getOwnerAccount())||owner.getOwnerRevision()!=row.getOwnerRevision()
                ||row.getTeamId()!=record.getTeamId()||!row.getRequestId().equals(record.getLaunchRequestId())||!row.getLockToken().equals(record.getMatchLockToken())
                ||originalRecord==null||originalRecord.getTeamId()!=record.getTeamId()||originalRecord.getLeaderId()!=record.getLeaderId()
                ||!originalRecord.getLaunchRequestId().equals(row.getRequestId())||!originalRecord.getMatchLockToken().equals(row.getLockToken())
                ||!originalRecord.getMembersList().equals(record.getMembersList())||!originalRecord.getMatchLockRosterList().equals(record.getMatchLockRosterList())
                ||captured.getMembersCount()!=record.getMatchLockRosterCount())throw new SecurityException("当前Team与永久开局原件不一致");
        for(int i=0;i<captured.getMembersCount();i++){
            var capturedMember=captured.getMembers(i);var role=capturedMember.getOriginal();
            var member=record.getMembersList().stream().filter(m->m.getPlayerId()==role.getPlayerId()).findFirst().orElse(null);
            if(role.getPlayerId()!=record.getMatchLockRoster(i)||capturedMember.getRosterOrder()!=i+1||!capturedMember.getTicket().equals(TeamStore.ticketId(row.getRequestId(),role.getPlayerId()))
                    ||member==null||!role.getOwnerAccount().equals(member.getOwnerAccount())||role.getOwnerRevision()!=member.getOwnerRevision())throw new SecurityException("永久开局完整名册与原归属不一致");
        }
        return row;
    }
    private CommitResult launchCommit(TeamLaunchRow row)throws SQLException{
        var record=parse(row.getRecordProto().toByteArray());if(record==null||record.getMembersCount()==0||!record.getMatchLockToken().equals(row.getLockToken())||!record.getLaunchRequestId().equals(row.getRequestId()))throw new SQLException("队伍原开局损坏");
        Map<Long,IndexEntry> indexes=new LinkedHashMap<>();try{for(var m:TeamLaunchSubmission.parseFrom(row.getSubmissionProto()).getIndexesList())indexes.put(m.getPlayerId(),new IndexEntry(m.getTeamId(),m.getEpoch()));}catch(com.google.protobuf.InvalidProtocolBufferException e){throw new SQLException("开局索引原件损坏",e);}
        if(!indexes.keySet().equals(new HashSet<>(TeamRules.memberIds(record))))throw new SQLException("开局索引原件不完整");
        var decision=new Decision(0,0,true,record,List.of(),TeamRules.memberIds(record),List.of(),List.of(),List.of(),TeamChangeReason.TEAM_CHANGE_REASON_MATCH_STARTED,row.getActorPlayerId(),false,false,List.of(),0,0);
        return new CommitResult(row.getTeamId(),row.getTeamVersion(),decision,indexes,row.getUpdatedMs());
    }
    CommitResult resumeLaunch(Deadline d){requireActor();if(requestId.isEmpty())return null;return run(d,c->{actorLock(c,d);var row=launch(c,requestId,false);return row==null?null:launchCommit(row);},true);}
    TeamLaunchSubmission submission(Deadline d){return run(d,c->{actorLock(c,d);var row=launch(c,requestId,false);if(row==null)throw new SQLException("开局原件不存在");try{return TeamLaunchSubmission.parseFrom(row.getSubmissionProto());}catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new SQLException("开局提交原件损坏",invalid);}},true);}
    ByteString original(Deadline d){return run(d,c->{actorLock(c,d);var row=launch(c,requestId,false);if(row==null||row.getOriginalProto().isEmpty())throw new SQLException("开局正规原件缺失");return row.getOriginalProto();},true);}
    private record DirectProof(long battle,String account,long revision,byte[] payload,boolean cancelled){}
    private record QueueProof(int state,long battle,long team,byte[] original){}
    LockRelease release(long teamId,String token,boolean ok,Deadline d){
        requireActor();var before=read(0,teamId,d);if(before.record()==null)return LockRelease.stopped(EndMatchStop.RECORD_MISSING);
        return run(d,c->{var owners=lockPeople(c,participants(before,actor.getPlayerId(),0),d);var snap=snapshot(c,0,teamId,true);var rec=snap.record();
            if(rec==null)return LockRelease.stopped(EndMatchStop.RECORD_MISSING);if(!rec.getMatchLockToken().equals(token))return LockRelease.stopped(EndMatchStop.TOKEN_MISMATCH);
            if(snap.version()!=before.version())return LockRelease.attempted(PinnedResult.retry(List.of()));verifyRecord(rec,owners);
            var original=storedLaunchForRecord(c,rec,true);if(original==null)throw new SQLException("业务开战锁缺少永久原件");
            var originalRecord=parse(original.getRecordProto().toByteArray());
            if(originalRecord==null||Long.compareUnsigned(snap.version(),original.getTeamVersion())<0||rec.getLeaderId()!=originalRecord.getLeaderId()
                    ||!rec.getMembersList().equals(originalRecord.getMembersList())||!rec.getMatchLockRosterList().equals(originalRecord.getMatchLockRosterList())
                    ||!rec.getMatchLockToken().equals(original.getLockToken()))throw new SecurityException("当前业务名单不再匹配永久原开局");
            // 只认同SQL域Match已持久终局；服务层布尔值/TTL/网络异常都不能证明未开战。
            var id=UUID.fromString(original.getRequestId());
            DirectProof proof=c.one("SELECT battle_id,owner_account,owner_revision,payload,cancelled FROM match_global_direct_intent WHERE kind=1 AND owner_id=? AND request_high=? AND request_low=? FOR UPDATE",r->new DirectProof(r.getLong(1),r.getString(2),r.getLong(3),r.getBytes(4),r.getInt(5)==1),AuthorityJdbc.u64(original.getActorPlayerId()),AuthorityJdbc.u64(id.getMostSignificantBits()),AuthorityJdbc.u64(id.getLeastSignificantBits()));
            if(proof==null)return LockRelease.stopped(EndMatchStop.DEADLINE);
            if(!original.getOwnerAccount().equals(proof.account())||original.getOwnerRevision()!=proof.revision()||!Arrays.equals(original.getRequestProto().toByteArray(),proof.payload()))throw new SecurityException("Match终局证明未绑定原开局");
            long battle=proof.battle();
            var unit=c.one("SELECT state,battle_id,team_id,launch_original FROM match_global_queue_unit WHERE unit_id=? FOR UPDATE",r->new QueueProof(r.getInt(1),r.getLong(2),r.getLong(3),r.getBytes(4)),"t:"+Long.toUnsignedString(teamId)+":"+original.getRequestId());
            if(unit!=null&&(unit.team()!=teamId||!Arrays.equals(unit.original(),original.getOriginalProto().toByteArray())))throw new SQLException("整队单位不匹配原launch");
            if(unit!=null&&(unit.state()==1||unit.state()==2))return LockRelease.stopped(EndMatchStop.DEADLINE);
            if(unit!=null&&unit.state()!=3&&unit.state()!=4)throw new SQLException("整队单位状态未知");
            Integer state;
            if(battle==0){if(unit==null||unit.state()!=4||unit.battle()!=0||!proof.cancelled())return LockRelease.stopped(EndMatchStop.DEADLINE);state=5;}
            else {if(unit!=null&&unit.battle()!=battle)return LockRelease.stopped(EndMatchStop.DEADLINE);state=c.one("SELECT state FROM match_global_formation WHERE battle_id=? FOR UPDATE",r->r.getInt(1),AuthorityJdbc.u64(battle));}
            if(state==null||(state!=4&&state!=5))return LockRelease.stopped(EndMatchStop.DEADLINE);
            Decision decision=TeamRules.releaseMatchLock(rec,token,state==4,snap.nowMs(),Map.of());decision=withRecord(decision,decision.record().toBuilder().clearLaunchRequestId().build());
            var result=commit(c,snap,decision,owners);c.update("UPDATE team_launch SET state=?,battle_id=?,updated_ms=?,active=0 WHERE request_id=?",state,AuthorityJdbc.u64(battle),snap.nowMs(),original.getRequestId());
            c.update("UPDATE team_launch_role SET state=2 WHERE request_id=?",original.getRequestId());return LockRelease.attempted(PinnedResult.committed(result));
        },true);
    }
    private static int constraints(AuthorityJdbc.Session c,Snapshot snap,Decision decision)throws SQLException{
        CommitSets.validate(snap.teamId(),decision,snap.record());
        for(long player:decision.joined()){var i=member(c,player,true);if(i.getTeamId()!=0&&i.getTeamId()!=snap.teamId())return TeamTips.MEMBER_IN_TEAM;}
        for(long player:decision.kept())if(member(c,player,true).getTeamId()!=snap.teamId())throw new SQLException("永久成员索引不符，拒绝覆盖");
        var rec=decision.record();if(rec!=null&&(rec.getApplicationsCount()>TeamLimits.MAX_APPLICATIONS||rec.getInvitesCount()>TeamLimits.MAX_INVITES_PER_TEAM))throw new SQLException("队伍待处理集合超限");
        for(var added:decision.invitesAdded()){
            if(member(c,added.inviteeId(),true).getTeamId()!=0)return TeamTips.MEMBER_IN_TEAM;
            if(c.count("SELECT COUNT(*) FROM team_invite_authority WHERE invitee_id=? AND team_id<>? AND expires_ms>"+NOW,AuthorityJdbc.u64(added.inviteeId()),AuthorityJdbc.u64(snap.teamId()))>=TeamLimits.MAX_PENDING_INVITES_PER_INVITEE)return TeamTips.INVITE_LIMIT;
        }
        return 0;
    }
    private static long next(long value)throws SQLException{if(value==-1L)throw new SQLException("队伍版本或epoch已耗尽");return value+1;}
    private CommitResult commit(AuthorityJdbc.Session c,Snapshot snap,Decision decision,Map<Long,Owner> owners)throws SQLException{
        long version=next(snap.version());var rec=decision.record();Map<Long,IndexEntry> indexes=new LinkedHashMap<>();
        var affected=new TreeSet<Long>(Long::compareUnsigned);affected.addAll(decision.joined());affected.addAll(decision.kept());affected.addAll(decision.left());
        for(long p:affected){var old=member(c,p,true);boolean leave=decision.left().contains(p);long teamId=leave?0:snap.teamId();long epoch=old.getEpoch();if(leave||decision.joined().contains(p))epoch=next(epoch);
            var owner=owners.get(p);if(owner==null)throw new SecurityException("提交成员没有原P锁");
            c.update("INSERT INTO team_member_authority(player_id,team_id,epoch,owner_account,owner_revision) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE team_id=VALUES(team_id),epoch=VALUES(epoch),owner_account=VALUES(owner_account),owner_revision=VALUES(owner_revision)",AuthorityJdbc.u64(p),AuthorityJdbc.u64(teamId),AuthorityJdbc.u64(epoch),owner.account(),owner.revision());indexes.put(p,new IndexEntry(teamId,epoch));}
        c.update("DELETE FROM team_invite_authority WHERE team_id=?",AuthorityJdbc.u64(snap.teamId()));
        c.update("DELETE FROM team_application_authority WHERE team_id=?",AuthorityJdbc.u64(snap.teamId()));
        if(rec!=null)for(var inv:rec.getInvitesList())c.update("INSERT INTO team_invite_authority(invitee_id,team_id,expires_ms,owner_account,owner_revision,initiator_player_id) VALUES(?,?,?,?,?,?)",AuthorityJdbc.u64(inv.getInviteeId()),AuthorityJdbc.u64(snap.teamId()),AuthorityJdbc.u64(inv.getExpireAtMs()),inv.getOwnerAccount(),inv.getOwnerRevision(),AuthorityJdbc.u64(consent(inv.getConsentOriginal()).getInitiator().getPlayerId()));
        if(rec!=null)for(var app:rec.getApplicationsList())c.update("INSERT INTO team_application_authority(player_id,team_id,expires_ms,owner_account,owner_revision,recipient_player_id) VALUES(?,?,?,?,?,?)",AuthorityJdbc.u64(app.getPlayerId()),AuthorityJdbc.u64(snap.teamId()),AuthorityJdbc.u64(app.getExpireAtMs()),app.getOwnerAccount(),app.getOwnerRevision(),AuthorityJdbc.u64(consent(app.getConsentOriginal()).getRecipient().getPlayerId()));
        byte[] raw=rec==null?new byte[0]:rec.toByteArray(),projection=rec==null?new byte[0]:TeamStore.projectionOf(rec).toByteArray();
        String active=rec==null?"":rec.getLaunchRequestId();
        if(snap.version()==0)c.update("INSERT INTO team_authority(team_id,version,record_proto,tombstone,projection_proto,active_launch_id) VALUES(?,?,?,?,?,?)",AuthorityJdbc.u64(snap.teamId()),AuthorityJdbc.u64(version),raw,rec==null,projection,active);
        else if(c.update("UPDATE team_authority SET version=?,record_proto=?,tombstone=?,projection_proto=?,active_launch_id=? WHERE team_id=? AND version=?",AuthorityJdbc.u64(version),raw,rec==null,projection,active,AuthorityJdbc.u64(snap.teamId()),AuthorityJdbc.u64(snap.version()))!=1)throw new SQLException("队伍版本CAS失败");
        return new CommitResult(snap.teamId(),version,decision,Map.copyOf(indexes),snap.nowMs());
    }
    private static TeamMutationReceipt encode(MutateResult r){
        var s=r.snapshot();var d=r.decision();var b=TeamMutationReceipt.newBuilder().setOutcome(r.outcome().ordinal()).setCode(r.code()).setParam(r.param());
        if(s!=null){b.setTeamId(s.teamId()).setVersion(r.commit()==null?s.version():r.commit().version()).setCallerTeamId(s.playerTeamId()).setCallerEpoch(s.playerEpoch()).setNowMs(s.nowMs());if(s.record()!=null)b.setBeforeProto(s.record().toByteString());}
        if(d.record()!=null)b.setAfterProto(d.record().toByteString());b.addAllJoined(d.joined()).addAllKept(d.kept()).addAllLeft(d.left()).setReason(d.reason().getNumber()).setActor(d.actor()).setLeaderTransferred(d.leaderOfflineTransferred()).setDisbanded(d.disbanded()).addAllRevokedInvitees(d.revokedInvitees()).setRejectedApplicant(d.rejectedApplicant()).setInvitedPlayer(d.invitedPlayer());
        if(r.commit()!=null)r.commit().indexes().forEach((p,i)->b.addIndexes(TeamMemberAuthorityRow.newBuilder().setPlayerId(p).setTeamId(i.teamId()).setEpoch(i.epoch())));return b.build();
    }
    private MutateResult decode(ByteString raw)throws SQLException{try{
        var r=TeamMutationReceipt.parseFrom(raw);if(r.getOutcome()>=Outcome.values().length)throw new SQLException("组队回执结局未知");var outcome=Outcome.values()[r.getOutcome()];
        TeamRecord before=parse(r.getBeforeProto().toByteArray()),after=parse(r.getAfterProto().toByteArray());
        var snap=new Snapshot(actor.getPlayerId(),r.getCallerTeamId(),r.getCallerEpoch(),r.getTeamId(),r.getVersion(),before,Long.MAX_VALUE,r.getNowMs());
        var reason=TeamChangeReason.forNumber(r.getReason());if(reason==null)throw new SQLException("组队回执原因未知");
        var decision=new Decision(r.getCode(),r.getParam(),outcome==Outcome.COMMITTED,after,r.getJoinedList(),r.getKeptList(),r.getLeftList(),List.of(),List.of(),reason,r.getActor(),r.getLeaderTransferred(),r.getDisbanded(),r.getRevokedInviteesList(),r.getRejectedApplicant(),r.getInvitedPlayer());
        Map<Long,IndexEntry> indexes=new LinkedHashMap<>();for(var i:r.getIndexesList())indexes.put(i.getPlayerId(),new IndexEntry(i.getTeamId(),i.getEpoch()));
        return result(outcome,r.getCode(),r.getParam(),snap,decision,outcome==Outcome.COMMITTED?new CommitResult(r.getTeamId(),r.getVersion(),decision,Map.copyOf(indexes),r.getNowMs()):null);
    }catch(com.google.protobuf.InvalidProtocolBufferException e){throw new SQLException("组队原请求回执损坏",e);}}
}
