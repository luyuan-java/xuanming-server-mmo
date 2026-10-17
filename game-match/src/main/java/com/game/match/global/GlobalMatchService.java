package com.game.match.global;

import com.game.api.BattleNodeService;
import com.game.api.proto.*;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.common.token.NetworkProbes;
import com.game.discovery.NodeDirectory;
import com.game.match.MatchProperties;
import com.game.match.gather.*;
import com.game.match.global.pb.*;
import com.game.match.id.MatchIds;
import com.game.match.placement.PlacementStore;
import com.game.match.port.NodeCalls;
import com.game.match.port.PlayerStatusReader;
import com.game.match.proto.BattlePlacement;
import com.game.match.rating.RatingReader;
import com.game.match.support.*;
import com.game.proto.*;
import com.game.proto.match.*;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 全球匹配完整提交边界：SQL 成局后只对固定节点固定操作备战/建房，UNKNOWN 永不释放玩家。 */
public final class GlobalMatchService implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(GlobalMatchService.class);
    private final GlobalMatchStore store;
    private final GlobalMatchProperties config;
    private final MatchProperties props;
    private final NodeDirectory<BattleNodeInfo> directory;
    private final MatchIds ids;
    private final RatingReader ratings;
    private final ScenePreparer scenes;
    private final NodeCalls<BattleNodeService> calls;
    private final PlayerStatusReader players;
    private final PlacementStore projections;
    private final GatherHooks hooks;
    private final java.security.SecureRandom seeds=new java.security.SecureRandom();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots=new Semaphore(16);
    private final Set<Long> running=ConcurrentHashMap.newKeySet();
    // 仅候选分页游标，丢失只会从队首重新扫描，不参与权威状态。
    private long scanMs,scanPlayer;

    public GlobalMatchService(GlobalMatchStore store,GlobalMatchProperties config,MatchProperties props,
            NodeDirectory<BattleNodeInfo> directory,MatchIds ids,RatingReader ratings,ScenePreparer scenes,
            NodeCalls<BattleNodeService> calls,PlayerStatusReader players,PlacementStore projections,GatherHooks hooks) {
        this.store=store;this.config=config;this.props=props;this.directory=directory;this.ids=ids;this.ratings=ratings;
        this.scenes=scenes;this.calls=calls;this.players=players;this.projections=projections;this.hooks=hooks;
    }
    public GlobalMatchStore store(){return store;}
    public boolean enabled(){return config.enabled();}
    private String binding(SessionContext session) {
        return NetworkProbes.binding(session.getGateInstanceId(),Integer.toUnsignedString(session.getSessionId()),session.getPlayerId());
    }
    private void current(SessionContext session,Deadline deadline) {
        if(session.getPlayerId()==0||session.getSessionId()==0)throw new IllegalArgumentException("缺少会话身份");
        var presence=players.presence(session.getPlayerId(),deadline);
        if(presence.isEmpty()||!presence.get().getGateInstanceId().equals(session.getGateInstanceId())
                ||presence.get().getSessionId()!=session.getSessionId())throw new IllegalArgumentException("会话已被替换或尚未登记");
    }
    public GetNetworkProbePlanResponse plan(SessionContext session,Deadline deadline) {
        current(session,deadline);
        long stamp=store.now(),expiry=stamp+config.profileTtlSeconds()*1000L;
        String id=UUID.randomUUID().toString(),binding=binding(session);
        var response=GetNetworkProbePlanResponse.newBuilder().setRequired(true).setPlanId(id).setExpiresAtMs(expiry);
        Set<Integer> regions=new HashSet<>();
        for(var n:directory.list(0).stream().sorted(Comparator.comparingInt(BattleNodeInfo::getNodeId)).toList()) {
            if(regions.size()>=16)break;
            if(!n.getAccepting()||n.getPhysicalRegion()==0||n.getInstanceId().isBlank()||n.getClientHost().isBlank()
                    ||n.getClientPort()==0||!regions.add(n.getPhysicalRegion()))continue;
            var grant=NetworkProbeGrant.newBuilder().setPlanId(id).setPlayerId(session.getPlayerId()).setSessionBinding(binding)
                    .setPhysicalRegion(n.getPhysicalRegion()).setTargetNodeId(n.getNodeId()).setTargetInstanceId(n.getInstanceId()).setExpiresAtMs(expiry).build();
            response.addTargets(NetworkProbeTarget.newBuilder().setPhysicalRegion(n.getPhysicalRegion()).setHost(n.getClientHost())
                    .setPort(n.getClientPort()).setGrant(NetworkProbes.sign(config.probeSecret(),NetworkProbes.GRANT,grant)));
        }
        if(response.getTargetsCount()==0)throw new IllegalStateException("没有合格的探测机房");
        store.plan(session.getPlayerId(),GlobalProbeProfile.newBuilder().setPlanId(id).setSessionBinding(binding).build(),expiry);
        return response.build();
    }
    public CommitNetworkProbeResponse commit(SessionContext session,CommitNetworkProbeRequest request,Deadline deadline) throws Exception {
        current(session,deadline);
        if(request.getReceiptsCount()<1||request.getReceiptsCount()>16)throw new IllegalArgumentException("回执数量无效");
        long stamp=store.now(),expiry=Long.MAX_VALUE;
        var profile=GlobalProbeProfile.newBuilder().setPlanId(request.getPlanId()).setSessionBinding(binding(session));
        Set<Integer> regions=new HashSet<>();
        for(var signed:request.getReceiptsList()) {
            if(!NetworkProbes.verify(config.probeSecret(),NetworkProbes.RECEIPT,signed))throw new IllegalArgumentException("回执签名无效");
            var r=NetworkProbeReceipt.parseFrom(signed.getPayload());var g=r.getGrant();
            if(g.getPlayerId()!=session.getPlayerId()||!g.getPlanId().equals(request.getPlanId())||!g.getSessionBinding().equals(binding(session))
                    ||g.getPhysicalRegion()==0||!regions.add(g.getPhysicalRegion())||r.getSampleCount()!=3||r.getRttMs()<1
                    ||r.getMeasuredAtMs()>stamp||r.getMeasuredAtMs()>g.getExpiresAtMs()||r.getExpiresAtMs()>g.getExpiresAtMs()
                    ||r.getExpiresAtMs()<=stamp)throw new IllegalArgumentException("回执身份或期限无效");
            expiry=Math.min(expiry,r.getExpiresAtMs());profile.addReceiptPayloads(signed.getPayload());
        }
        if(GlobalRegionPolicy.latencies(profile.build(),stamp,config.hardLatencyMs()).isEmpty())throw new IllegalArgumentException("所有机房超过硬延迟上限");
        store.commitProfile(session.getPlayerId(),profile.build(),expiry);
        return CommitNetworkProbeResponse.newBuilder().setExpiresAtMs(expiry).build();
    }
    public JoinQueueResponse join(SessionContext session,JoinQueueRequest request,Deadline deadline) {
        try {GlobalMatchStore.validateRequestId(request.getRequestId());}
        catch(IllegalArgumentException e){return JoinQueueResponse.newBuilder().setErrorMessage(com.game.proto.TipInfoMessage.newBuilder().setId(MatchTips.INVALID_PARAMETER)).build();}
        try {
            current(session,deadline);
            int mode=request.getModeValue(),size=MatchModes.requiredPlayers(mode,props.pveTeamSizeFor(request.getBattleConfigId()));
            if(size<1) return MatchTips.joinRejected(MatchTip.JOIN_MODE_NOT_OPEN);
            if(players.inBattle(session.getPlayerId(),deadline))return MatchTips.joinRejected(MatchTip.JOIN_IN_BATTLE);
            var location=players.location(session.getPlayerId(),deadline);
            if(location.location()==null||location.status()!=com.game.discovery.location.PlayerLocationDirectory.LocationStatus.ONLINE)
                return MatchTips.joinRejected(MatchTip.JOIN_NOT_IN_SCENE);
            var old=store.ticket(session.getPlayerId());releaseIfEnded(old);
            var row=store.join(session.getPlayerId(),request.getRequestId(),request.getNetworkPlanId(),binding(session),mode,
                    request.getBattleConfigId(),size,location.location().getZoneId(),ratings.loadCentiOrDefault(session.getPlayerId()));
            return row.getRequestId().equals(request.getRequestId())?MatchTips.joinAccepted(row.getTicket()):MatchTips.joinAlreadyQueued(row.getTicket());
        } catch(RuntimeException e) {log.warn("全球排队拒绝或依赖失败: {}",e.getClass().getSimpleName());return MatchTips.joinRejected(MatchTip.BUSY);}
    }
    public GetQueueStatusResponse status(long player) {
        return status(player, "");
    }
    public GetQueueStatusResponse status(long player, String request) {
        if(!request.isEmpty())GlobalMatchStore.validateRequestId(request);
        var row=store.ticket(player);
        if(releaseIfEnded(row))row=store.ticket(player);
        var response=GetQueueStatusResponse.newBuilder().setRequestCancelled(store.requestCancelled(player, request));
        if(row.getState()<1||row.getState()>3)return response.setState(QueueState.QUEUE_STATE_NOT_QUEUED).build();
        return response.setState(row.getState()==1?QueueState.QUEUE_STATE_QUEUED:row.getState()==2?QueueState.QUEUE_STATE_MATCHED:QueueState.QUEUE_STATE_READY)
                .setQueueTicket(row.getTicket()).setBattleId(row.getFormationId()).setSelectedRegion(row.getSelectedRegion())
                .setQueuedSeconds((int)Math.min(Integer.MAX_VALUE,Math.max(0,store.now()-row.getEnqueuedMs())/1000)).setRetryAfterMs(1000).build();
    }
    public boolean releaseIfEnded(GlobalTicketRow ticket) {
        if(ticket.getState()!=3)return false;
        var f=store.formation(ticket.getFormationId());if(f==null)return false;
        try {var state=query(f).getState();if(state==BattleCreationState.BATTLE_CREATION_ABSENT||state==BattleCreationState.BATTLE_CREATION_ABORTED) {
            store.releaseReady(ticket.getPlayerId(),ticket.getTicket(),ticket.getFormationId());return true;
        }}catch(Exception e){log.debug("全球已建房票据仍待权威查询");}return false;
    }
    public void round() {
        if(!config.enabled())return;
        try {
            for(var f:store.pending())launch(f,false);
            if(!ids.leaseValid()||slots.availablePermits()==0)return;
            var candidates=store.candidates(scanMs,scanPlayer);
            if(candidates.isEmpty()){scanMs=0;scanPlayer=0;return;}
            var last=candidates.getLast();scanMs=last.getEnqueuedMs();scanPlayer=last.getPlayerId();
            var nodes=directory.list(0);
            while(slots.availablePermits()>0) {
                var choice=GlobalRegionPolicy.choose(candidates,nodes,store.now(),config);if(choice==null)break;
                var id=ids.nextBattleId();if(id.isEmpty())return;
                try {var f=store.claim(choice.members(),formation(id.getAsLong(),choice.node(),choice.members().getFirst().getMode(),
                            choice.members().getFirst().getConfigId()).setRequeueOnFailure(true).build(),false);launch(f,true);}
                catch(RuntimeException e){log.debug("成局竞争失败，下一轮重读");}
                Set<Long> selected=new HashSet<>();choice.members().forEach(t->selected.add(t.getPlayerId()));
                candidates=candidates.stream().filter(t->!selected.contains(t.getPlayerId())).toList();
            }
        }catch(RuntimeException e){log.error("全球凑单/恢复本轮失败",e);}
    }
    private GlobalFormationRow.Builder formation(long battle,BattleNodeInfo n,int mode,int configId) {
        return GlobalFormationRow.newBuilder().setBattleId(battle).setOperationId(UUID.randomUUID().toString()).setRegion(n.getPhysicalRegion())
                .setNodeId(n.getNodeId()).setInstanceId(n.getInstanceId()).setHost(n.getRpcHost()).setPort(n.getRpcPort()).setMode(mode).setConfigId(configId);
    }
    public GatherResult gather(GatherPlan plan) {
        try {
            GlobalFormationRow f=null;
            if(plan.presetBattleId()!=0)f=store.formation(plan.presetBattleId());
            else if(!plan.members().isEmpty()){
                var ticket=store.ticket(plan.members().getFirst());
                if(ticket.getFormationId()!=0&&ticket.getTicket().equals(plan.tickets().get(plan.members().getFirst())))f=store.formation(ticket.getFormationId());
            }
            if(f==null||f.getMode()!=plan.mode().getNumber()||f.getConfigId()!=plan.battleConfigId()
                    ||!f.getMembersList().stream().map(GlobalMember::getPlayerId).toList().equals(plan.members()))
                return GatherResult.failed(GatherOutcome.INTERNAL,plan.presetBattleId());
            for(var member:f.getMembersList())if(!plan.tickets().isEmpty()&&!member.getTicket().equals(plan.tickets().get(member.getPlayerId())))return GatherResult.failed(GatherOutcome.INTERNAL,f.getBattleId());
            if(f.getState()==4)return GatherResult.success(f.getBattleId());
            if(f.getState()==5)return GatherResult.failed(GatherOutcome.CREATE_REJECTED,f.getBattleId());
            f=store.acquireWork(f.getBattleId());
            if(f.getState()==1)return execute(f);
            if(f.getCreateRequest().isEmpty())abort(f);else recover(f);f=store.formation(f.getBattleId());
            return f.getState()==4?GatherResult.success(f.getBattleId()):GatherResult.failed(GatherOutcome.CREATE_FAILED_ROOM_ALIVE,f.getBattleId());
        }catch(Exception e){log.error("全球直接开局恢复未完成",e);return GatherResult.failed(GatherOutcome.INTERNAL,plan.presetBattleId());}
    }
    /** 调用者成功回包前提交；稳定业务键、全员占用、固定地域成局一次 SQL 提交。 */
    public GlobalFormationRow reserveDirect(GlobalDirectIntentRow key,ByteString payload,int mode,int configId,List<Long> roster,
            Map<Long,String> tickets,long teamId,BattleActivityContext activity) {
        try {
            var prior=store.direct(key);
            if(prior!=null){if(!prior.getPayload().equals(payload)||prior.getBattleId()==0)throw new IllegalArgumentException("业务意图已经决定或参数不同");
                return store.formation(prior.getBattleId());}
            List<GlobalTicketRow> members=new ArrayList<>();
            long stamp=store.now();
            for(long player:roster) {
                var p=store.profile(player);
                members.add(GlobalTicketRow.newBuilder().setPlayerId(player).setMode(mode).setConfigId(configId).setTeamId(teamId)
                        .setTicket(tickets.getOrDefault(player,"")).setTeamSize(roster.size()).setState(1).setEnqueuedMs(stamp).setExpiresMs(p.getExpiresMs()).setProfile(p.getPayload()).build());
            }
            // 点名整队必须所有人同一机房均合格；不借队长 RTT 替代其他成员。
            var relaxed=new GlobalMatchProperties(true,config.requireTiDb(),1,config.hardLatencyMs(),config.profileTtlSeconds(),config.probeSecret());
            var choice=GlobalRegionPolicy.choose(members,directory.list(0),stamp+1000,relaxed);
            if(choice==null)throw new IllegalStateException("没有满足全员硬延迟的共同机房");
            long battle=ids.nextBattleId().orElseThrow();
            var f=formation(battle,choice.node(),mode,configId);
            if(activity!=null)f.setActivityContext(activity.toByteString());
            return store.reserveDirect(key,payload,members,f.build());
        }catch(Exception e){throw new Deadline.DependencyException("全球原业务成局尚未确认",e);}
    }
    public void launchCommitted(GlobalFormationRow f){if(f.getState()<4)launch(f,f.getState()==1);}
    private void launch(GlobalFormationRow f,boolean fresh) {
        if(!running.add(f.getBattleId()))return;
        if(!slots.tryAcquire()){running.remove(f.getBattleId());return;}
        try{workers.execute(()->{GlobalFormationRow work=null;try{work=store.acquireWork(f.getBattleId());if(work.getState()==1)execute(work);else if(work.getCreateRequest().isEmpty())abort(work);else recover(work);}
            catch(Exception e){if(work!=null)try{store.unknown(work);}catch(RuntimeException stale){log.debug("全球工作租约已转移");}log.error("全球成局恢复未完成",e);}
            finally{running.remove(f.getBattleId());slots.release();}});}
        catch(RejectedExecutionException e){running.remove(f.getBattleId());slots.release();}
    }
    private GatherResult execute(GlobalFormationRow initial) {
        var f=initial;
        try {
            hooks.beforePrepare(f.getMembersList().stream().map(GlobalMember::getPlayerId).toList());
            List<Long> roster=f.getMembersList().stream().map(GlobalMember::getPlayerId).toList();
            int[] teams=TeamAssignment.assign(MatchMode.forNumber(f.getMode()),roster,ratings.loadAllCentiOrDefault(roster));
            List<BattlePlayerSnapshot> snapshots=new ArrayList<>();List<FingerprintCheck.Member> fingerprints=new ArrayList<>();
            for(int i=0;i<roster.size();i++) {
                long player=roster.get(i);
                var result=scenes.prepare(PrepareBattleRequest.newBuilder().setPlayerId(player).setBattleId(f.getBattleId()).setBattleNodeId(f.getNodeId())
                        .setDeadlineMs(f.getDeadlineMs()).setPrepareDeadlineMs(Math.min(f.getCreatedMs()+120_000,f.getRetryMs()-1000)).build(),endpoint->store.prepared(initial,GlobalPreparedEndpoint.newBuilder().setPlayerId(player).setZoneId(endpoint.zoneId())
                        .setNodeId(endpoint.nodeId()).setHost(endpoint.target().host()).setPort(endpoint.target().port()).setInstanceId(endpoint.target().instanceId()).build()));
                if(!(result instanceof ScenePreparer.Prepare.Ok ok)){abort(f);return GatherResult.failed(GatherOutcome.PREPARE_FAILED,f.getBattleId());}
                snapshots.add(ok.response().getSnapshot().toBuilder().setTeamIndex(teams[i]).build());
                fingerprints.add(new FingerprintCheck.Member(player,ok.response().getTableFingerprint()));
            }
            var fingerprint=FingerprintCheck.check(props.tableFingerprintMode(),fingerprints);
            if(!fingerprint.proceed()){abort(f);return GatherResult.failed(GatherOutcome.FINGERPRINT_MISMATCH,f.getBattleId());}
            var request=CreateBattleRequest.newBuilder().setBattleId(f.getBattleId()).setBattleConfigId(f.getConfigId()).setMatchMode(f.getMode())
                    .setCreatedAtMs(store.now()).setDeadlineMs(f.getDeadlineMs()).setSeed(seeds.nextLong()).addAllPlayers(snapshots)
                    .setTableFingerprint(fingerprint.passThrough()).setRequiredPhysicalRegion(f.getRegion()).setPlacementOperationId(f.getOperationId())
                    .setTargetInstanceId(f.getInstanceId()).setAdmitBeforeMs(Math.min(f.getCreatedMs()+120_000,f.getRetryMs()-1000));
            if(!f.getActivityContext().isEmpty())request.setActivityContext(BattleActivityContext.parseFrom(f.getActivityContext()));
            f=store.saveIntent(f,NetworkProbes.bytes(request.build()));
            return create(f);
        }catch(Exception e){try{store.unknown(f);}catch(RuntimeException stale){log.debug("全球工作租约已转移");}log.error("全球备战/建房等待恢复",e);return GatherResult.failed(GatherOutcome.CREATE_FAILED_ROOM_ALIVE,f.getBattleId());}
    }
    private GatherResult create(GlobalFormationRow f)throws Exception {
        var request=CreateBattleRequest.parseFrom(f.getCreateRequest());
        store.checkWork(f);
        var result=invoke(f,s->s.createBattle(request));
        if(result.getAdmission()==BattleAdmission.BATTLE_ADMISSION_ADMITTED
                &&CreateBattleResponse.parseFrom(result.getResponse()).getErrorMessage().getId()==0) {
            store.finish(f,true);project(f,request);return GatherResult.success(f.getBattleId());
        }
        // 拒绝也写入精确实例墓碑，防并发恢复中已经在途的同操作创建迟到复活。
        abort(f);return GatherResult.failed(GatherOutcome.CREATE_REJECTED,f.getBattleId());
    }
    private void recover(GlobalFormationRow f)throws Exception {
        var state=query(f).getState();
        switch(state) {
            case BATTLE_CREATION_PRESENT -> {store.finish(f,true);project(f,CreateBattleRequest.parseFrom(f.getCreateRequest()));}
            case BATTLE_CREATION_ABORTED -> abort(f);
            case BATTLE_CREATION_ABSENT -> {if(!f.getCreateRequest().isEmpty()&&store.now()<CreateBattleRequest.parseFrom(f.getCreateRequest()).getAdmitBeforeMs())create(f);else abort(f);}
            default -> {try{store.unknown(f);}catch(RuntimeException stale){log.debug("全球工作租约已转移");}}
        }
    }
    private void abort(GlobalFormationRow f)throws Exception {
        store.checkWork(f);
        invoke(f,s->s.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(f.getBattleId()).setPlacementOperationId(f.getOperationId())
                .setTargetInstanceId(f.getInstanceId()).setReason("global_match_abort_if_absent").build()));
        var state=query(f).getState();
        if(state==BattleCreationState.BATTLE_CREATION_PRESENT){store.finish(f,true);return;}
        if(state!=BattleCreationState.BATTLE_CREATION_ABORTED)throw new IllegalStateException("撤销尚未由固定执行端确认");
        store.checkWork(f);
        var latest=store.formation(f.getBattleId());
        // 只有固定执行端确认墓碑后才能解冻、回队或结束票据。
        for(var endpoint:latest.getPreparedList())scenes.cancel(endpoint.getPlayerId(),f.getBattleId(),new ScenePreparer.Endpoint(endpoint.getZoneId(),
                endpoint.getNodeId(),new NodeRpcClients.Target(endpoint.getHost(),endpoint.getPort(),endpoint.getInstanceId())));
        store.finish(f,false);
    }
    private QueryBattleCreationResponse query(GlobalFormationRow f)throws Exception {
        return invoke(f,s->s.queryBattleCreation(QueryBattleCreationRequest.newBuilder().setBattleId(f.getBattleId()).setPlacementOperationId(f.getOperationId())
                .setTargetInstanceId(f.getInstanceId()).build()));
    }
    private <R> R invoke(GlobalFormationRow f,Function<BattleNodeService,CompletableFuture<R>> action)throws Exception {
        return calls.callRemembered(new NodeRpcClients.Target(f.getHost(),f.getPort(),f.getInstanceId()),Duration.ofSeconds(3),action).get(3250,TimeUnit.MILLISECONDS);
    }
    private void project(GlobalFormationRow f,CreateBattleRequest request) {
        var p=BattlePlacement.newBuilder().setBattleId(f.getBattleId()).setMode(f.getMode()).setBattleConfigId(f.getConfigId()).setCreatedAtMs(f.getCreatedMs())
                .setDeadlineMs(f.getDeadlineMs()).setBattleNodeId(f.getNodeId()).setBattleInstanceId(f.getInstanceId()).setRpcHost(f.getHost()).setRpcPort(f.getPort()).setAttempt(1);
        request.getPlayersList().forEach(s->p.addPlayerNames(s.getPlayerName()));
        projections.write(p.build());try{hooks.onStarted(p.build());}catch(RuntimeException e){log.warn("观战展示投影稍后恢复",e);}
    }
    public RequestBattleTicketResponse reissue(long player,long battle) {
        try {
            var f=store.formation(battle);if(f==null||f.getState()==5)return RequestBattleTicketResponse.newBuilder().setErrorMessage(MatchTip.REISSUE_BATTLE_GONE.proto()).build();
            var state=query(f).getState();
            if(state==BattleCreationState.BATTLE_CREATION_ABSENT||state==BattleCreationState.BATTLE_CREATION_ABORTED)
                return RequestBattleTicketResponse.newBuilder().setErrorMessage(MatchTip.REISSUE_BATTLE_GONE.proto()).build();
            if(state!=BattleCreationState.BATTLE_CREATION_PRESENT)throw new IllegalStateException("建房结局未知");
            // battle 自己检查参战者/观众名单，SQL 落点不依赖 Redis 展示索引。
            var result=invoke(f,s->s.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setPlayerId(player).setBattleId(battle).build()));
            return RequestBattleTicketResponse.newBuilder().setAssignment(result.getAssignment()).setErrorMessage(result.getErrorMessage()).build();
        }catch(Exception e){return RequestBattleTicketResponse.newBuilder().setErrorMessage(MatchTip.REISSUE_BATTLE_UNAVAILABLE.proto()).build();}
    }
    @Override public void close(){workers.shutdown();try{if(!workers.awaitTermination(10,TimeUnit.SECONDS))workers.shutdownNow();}
        catch(InterruptedException e){Thread.currentThread().interrupt();workers.shutdownNow();}}
}
