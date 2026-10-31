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
    private com.game.match.metrics.MatchMetrics metrics;
    public GlobalMatchService withMetrics(com.game.match.metrics.MatchMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        return this;
    }
    private com.game.match.spectate.SqlSpectateStore spectators;
    public GlobalMatchService withSpectators(com.game.match.spectate.SqlSpectateStore spectators){this.spectators=spectators;return this;}
    private void exitSpectators(List<ObserverExitOriginal> originals,Deadline deadline){
        if(originals.isEmpty())return;
        if(spectators==null)throw new IllegalStateException("原观战退出SQL端口未装配");
        for(var original:originals)spectators.releaseExit(original,deadline);
    }
    private final java.security.SecureRandom seeds=new java.security.SecureRandom();
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots=new Semaphore(16);
    private final Set<Long> running=ConcurrentHashMap.newKeySet();
    // 仅候选分页游标，丢失只会从队首重新扫描，不参与权威状态。
    private long scanMs,scanPlayer;
    private long readyMs,readyPlayer;
    private long unitMs;
    private String unitId="";
    private volatile java.util.function.Consumer<GlobalChallengeRow> challengeNotifications=row->{};
    public void challengeNotifications(java.util.function.Consumer<GlobalChallengeRow> notifications){challengeNotifications=Objects.requireNonNull(notifications);}

    public GlobalMatchService(GlobalMatchStore store,GlobalMatchProperties config,MatchProperties props,
            NodeDirectory<BattleNodeInfo> directory,MatchIds ids,RatingReader ratings,ScenePreparer scenes,
            NodeCalls<BattleNodeService> calls,PlayerStatusReader players,PlacementStore projections,GatherHooks hooks) {
        this.store=store;this.config=config;this.props=props;this.directory=directory;this.ids=ids;this.ratings=ratings;
        this.scenes=scenes;this.calls=calls;this.players=players;this.projections=projections;this.hooks=hooks;
    }
    private final ScheduledExecutorService admissionTimer=Executors.newScheduledThreadPool(2,Thread.ofPlatform().name("match-admission-recovery-",0).factory());
    private final Semaphore joinRecoverySlots=new Semaphore(2),directRecoverySlots=new Semaphore(2);
    private final Set<String> admissions=ConcurrentHashMap.newKeySet();
    private GlobalJoinIntentRow joinUpper;
    private GlobalDirectIntentRow directUpper;
    private long joinPlayer,joinHigh,joinLow,directOwner,directHigh,directLow;
    private int directKind;
    /** 两条独立有界恢复循环，只沿永久原件推进；游标是可丢缓存，不授予业务权限。 */
    public void closeRoots(String run){store.closeRoots(run);}
    public boolean stopAndReport(long timeoutNanos)throws InterruptedException{
        if(!store.rootsClosed()||store.maintenanceOutstanding()!=0||!running.isEmpty()||!admissions.isEmpty())return false;
        long until=System.nanoTime()+Math.max(0,timeoutNanos);admissionTimer.shutdown();
        if(!admissionTimer.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS))return false;
        workers.shutdown();return workers.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS)
                &&running.isEmpty()&&admissions.isEmpty()&&store.maintenanceOutstanding()==0;
    }
    public void start(){
        admissionTimer.scheduleWithFixedDelay(()->{try{recoverJoins();}catch(RuntimeException failure){log.warn("原Join恢复扫描待重读，类别={}",failure.getClass().getSimpleName());}},500,500,TimeUnit.MILLISECONDS);
        admissionTimer.scheduleWithFixedDelay(()->{try{recoverDirects();}catch(RuntimeException failure){log.warn("原direct恢复扫描待重读，类别={}",failure.getClass().getSimpleName());}},500,500,TimeUnit.MILLISECONDS);
    }
    private void recoverJoins(){
        if(joinUpper==null){joinUpper=store.pendingJoinUpper();joinPlayer=joinHigh=joinLow=0;if(joinUpper==null)return;}
        var rows=store.pendingJoins(joinPlayer,joinHigh,joinLow,joinUpper);if(rows.isEmpty()){joinUpper=null;return;}
        for(var row:rows){String key="j:"+Long.toUnsignedString(row.getPlayerId())+":"+row.getRequestId();
            if(admissions.contains(key)){advanceJoin(row);continue;}if(!joinRecoverySlots.tryAcquire())return;
            if(!admissions.add(key)){joinRecoverySlots.release();advanceJoin(row);continue;}
            try{workers.execute(()->{try{resumeJoin(row,Deadline.after(3000));}catch(RuntimeException unknown){log.debug("原Join未确认，保留原票待恢复");}finally{admissions.remove(key);joinRecoverySlots.release();}});advanceJoin(row);}
            catch(RejectedExecutionException closing){admissions.remove(key);joinRecoverySlots.release();return;}
        }
    }
    private void advanceJoin(GlobalJoinIntentRow row){joinPlayer=row.getPlayerId();joinHigh=row.getRequestHigh();joinLow=row.getRequestLow();}
    private void recoverDirects(){
        if(directUpper==null){directUpper=store.pendingDirectUpper();directKind=0;directOwner=directHigh=directLow=0;if(directUpper==null)return;}
        var rows=store.pendingDirects(directKind,directOwner,directHigh,directLow,directUpper);if(rows.isEmpty()){directUpper=null;return;}
        for(var row:rows){String key="d:"+row.getKind()+":"+Long.toUnsignedString(row.getOwnerId())+":"+Long.toUnsignedString(row.getRequestHigh())+":"+Long.toUnsignedString(row.getRequestLow());
            if(admissions.contains(key)){advanceDirect(row);continue;}if(!directRecoverySlots.tryAcquire())return;
            if(!admissions.add(key)){directRecoverySlots.release();advanceDirect(row);continue;}
            try{workers.execute(()->{try{var formation=resumeDirect(row,Deadline.after(3000));if(formation.getBattleId()!=0&&formation.getState()<4)launchCommitted(formation);}catch(Exception unknown){log.debug("原direct未确认，保留全员原票待恢复");}finally{admissions.remove(key);directRecoverySlots.release();}});advanceDirect(row);}
            catch(RejectedExecutionException closing){admissions.remove(key);directRecoverySlots.release();return;}
        }
    }
    private void advanceDirect(GlobalDirectIntentRow row){directKind=row.getKind();directOwner=row.getOwnerId();directHigh=row.getRequestHigh();directLow=row.getRequestLow();}
    public GlobalMatchStore store(){return store;}
    public boolean enabled(){return config.enabled();}
    private String binding(SessionContext session) {
        try {
            var original=SessionContext.newBuilder().setPlayerId(session.getPlayerId()).setAccount(session.getAccount()).setOwnerRevision(session.getOwnerRevision())
                    .setGateInstanceId(session.getGateInstanceId()).setSessionId(session.getSessionId()).build();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(NetworkProbes.bytes(original)));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void current(SessionContext session,Deadline deadline) {
        if(session.getPlayerId()==0||session.getSessionId()==0||session.getAccount().isEmpty()||session.getOwnerRevision()<=0||session.getGateInstanceId().isEmpty())
            throw new IllegalArgumentException("缺少原账号/修订会话身份或超出Java修订范围");
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
        store.plan(session.getPlayerId(),GlobalProbeProfile.newBuilder().setPlanId(id).setSessionBinding(binding)
                .setOwnerAccount(session.getAccount()).setOwnerRevision(session.getOwnerRevision()).build(),expiry);
        return response.build();
    }
    public CommitNetworkProbeResponse commit(SessionContext session,CommitNetworkProbeRequest request,Deadline deadline) throws Exception {
        current(session,deadline);
        if(request.getReceiptsCount()<1||request.getReceiptsCount()>16)throw new IllegalArgumentException("回执数量无效");
        long stamp=store.now(),expiry=Long.MAX_VALUE;
        var profile=GlobalProbeProfile.newBuilder().setPlanId(request.getPlanId()).setSessionBinding(binding(session))
                .setOwnerAccount(session.getAccount()).setOwnerRevision(session.getOwnerRevision());
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
        var response = joinOriginal(session, request, deadline);
        if (metrics != null) {
            var outcome = response.getErrorMessage().equals(MatchTip.JOIN_TEAM_SIZE_OVER_LIMIT.proto())
                    ? com.game.match.metrics.MatchMetrics.JoinOutcome.TEAM_SIZE_OVER_LIMIT
                    : switch (response.getErrorCode()) {
                        case MatchTips.IN_BATTLE -> com.game.match.metrics.MatchMetrics.JoinOutcome.IN_BATTLE;
                        case MatchTips.ALREADY_QUEUED -> com.game.match.metrics.MatchMetrics.JoinOutcome.ALREADY_QUEUED;
                        case MatchTips.MODE_NOT_OPEN -> com.game.match.metrics.MatchMetrics.JoinOutcome.MODE_NOT_OPEN;
                        case MatchTips.TEAM_SIZE_NOT_CONFIGURED -> com.game.match.metrics.MatchMetrics.JoinOutcome.NO_TEAM_SIZE;
                        case MatchTips.NOT_IN_SCENE -> com.game.match.metrics.MatchMetrics.JoinOutcome.NOT_IN_SCENE;
                        case 0 -> response.hasErrorMessage() ? com.game.match.metrics.MatchMetrics.JoinOutcome.INTERNAL
                                : com.game.match.metrics.MatchMetrics.JoinOutcome.OK;
                        default -> com.game.match.metrics.MatchMetrics.JoinOutcome.INTERNAL;
                    };
            int size = request.getTeamSize() == 0 ? props.pveTeamSizeFor(request.getBattleConfigId()) : request.getTeamSize();
            metrics.joinQueue(request.getModeValue(), size, outcome);
        }
        return response;
    }

    private JoinQueueResponse joinOriginal(SessionContext session,JoinQueueRequest request,Deadline deadline) {
        try {GlobalMatchStore.validateRequestId(request.getRequestId());}
        catch(IllegalArgumentException e){return JoinQueueResponse.newBuilder().setErrorMessage(com.game.proto.TipInfoMessage.newBuilder().setId(MatchTips.INVALID_PARAMETER)).build();}
        try {
            current(session,deadline);
            var replay=store.existingJoin(session,request);if(replay!=null){if(!replay.getResponsePayload().isEmpty())try{return JoinQueueResponse.parseFrom(replay.getResponsePayload());}catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new IllegalStateException("原Join回执损坏",bad);}return resumeJoin(replay,deadline);}
            int mode=request.getModeValue(),maximum=props.pveTeamSizeFor(request.getBattleConfigId());
            int size=MatchModes.requestedPlayers(mode,maximum,request.getTeamSize());
            if(mode==MatchModes.PVE_TEAM&&maximum==0)return MatchTips.joinRejected(MatchTip.JOIN_TEAM_SIZE_NOT_CONFIGURED);
            if(size<1) return MatchTips.joinRejected(mode==MatchModes.PVE_TEAM
                    ? MatchTip.JOIN_TEAM_SIZE_OVER_LIMIT : MatchTip.JOIN_MODE_NOT_OPEN);
            if(players.inBattle(session.getPlayerId(),deadline))return MatchTips.joinRejected(MatchTip.JOIN_IN_BATTLE);
            var location=players.location(session.getPlayerId(),deadline);
            if(location.location()==null||location.status()!=com.game.discovery.location.PlayerLocationDirectory.LocationStatus.ONLINE)
                return MatchTips.joinRejected(MatchTip.JOIN_NOT_IN_SCENE);
            var old=store.ticket(session);releaseIfEnded(old);
            var prepared=store.prepareJoin(session,request,size,location.location().getZoneId(),ratings.loadCentiOrDefault(session.getPlayerId()),binding(session));
            if(!prepared.getResponsePayload().isEmpty())try{return JoinQueueResponse.parseFrom(prepared.getResponsePayload());}catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new IllegalStateException("原Join回执损坏",bad);}
            return resumeJoin(prepared,deadline);
        } catch(RuntimeException e) {log.warn("全球排队拒绝或依赖失败: {}",e.getClass().getSimpleName());return MatchTips.joinRejected(MatchTip.BUSY);}
    }
    private JoinQueueResponse resumeJoin(GlobalJoinIntentRow original,Deadline deadline){
        try {
            var actor=SessionContext.newBuilder().setPlayerId(original.getPlayerId()).setAccount(original.getOwnerAccount()).setOwnerRevision(original.getOwnerRevision()).build();
            if(!original.getTicketRecord().isEmpty()){
                var row=GlobalTicketRow.parseFrom(original.getTicketRecord());return store.completeJoin(actor,original.getRequestId(),row.getRequestId().equals(original.getRequestId())?MatchTips.joinAccepted(row.getTicket()):MatchTips.joinAlreadyQueued(row.getTicket()));
            }
            var request=JoinQueueRequest.parseFrom(original.getRequestPayload());var pending=GlobalTicketRow.parseFrom(original.getAdmissionTicket());
            List<ObserverExitOriginal> exits=new ArrayList<>();for(var bytes:original.getObserverExitsList())exits.add(ObserverExitOriginal.parseFrom(bytes));exitSpectators(exits,deadline);
            if(deadline.expired())throw new Deadline.DependencyException("原Join退出观战预算到期");
            if(store.closeObsoleteJoin(original,deadline))return MatchTips.joinRejected(MatchTip.BUSY);
            var row=store.join(actor,original.getRequestId(),request.getNetworkPlanId(),original.getSessionBinding(),pending.getMode(),pending.getConfigId(),pending.getTeamSize(),pending.getZoneId(),pending.getRatingCenti());
            var reply=store.completeJoin(actor,original.getRequestId(),row.getRequestId().equals(original.getRequestId())?MatchTips.joinAccepted(row.getTicket()):MatchTips.joinAlreadyQueued(row.getTicket()));
            if(row.getTeamSize()==1&&row.getState()==1&&!deadline.expired())tryImmediate(row);
            return reply;
        }catch(com.google.protobuf.InvalidProtocolBufferException bad){throw new IllegalStateException("原Join持久原件损坏",bad);}
    }
    private void tryImmediate(GlobalTicketRow ticket){
        try{var choice=GlobalRegionPolicy.choose(List.of(ticket),directory.list(0),store.now(),config);if(choice==null)return;
            var battle=ids.nextBattleId();if(battle.isEmpty())return;launch(store.claim(choice.members(),formation(battle.getAsLong(),choice.node(),ticket.getMode(),ticket.getConfigId()).setRequeueOnFailure(true).build(),false),true);
        }catch(RuntimeException race){log.debug("单人原准入由全球恢复继续，不另造请求");}
    }
    public GetQueueStatusResponse status(long player) {
        return status(player, "");
    }
    public GetQueueStatusResponse status(long player, String request) {
        throw new IllegalArgumentException("全球客户端查票必须传原会话");
    }
    public GetQueueStatusResponse status(SessionContext session,String request) {
        long player=session.getPlayerId();
        if(!request.isEmpty())GlobalMatchStore.validateRequestId(request);
        var row=store.ticket(session);
        if(releaseIfEnded(row))row=store.ticket(session);
        var response=GetQueueStatusResponse.newBuilder().setRequestCancelled(store.requestCancelled(session, request));
        if(row.getState()<0||row.getState()>3||row.getTicket().isEmpty())return response.setState(QueueState.QUEUE_STATE_NOT_QUEUED).build();
        return response.setState(row.getState()<=1?QueueState.QUEUE_STATE_QUEUED:row.getState()==2?QueueState.QUEUE_STATE_MATCHED:QueueState.QUEUE_STATE_READY)
                .setQueueTicket(row.getTicket()).setBattleId(row.getFormationId()).setSelectedRegion(row.getSelectedRegion())
                .setQueuedSeconds((int)Math.min(Integer.MAX_VALUE,Math.max(0,store.now()-row.getEnqueuedMs())/1000)).setRetryAfterMs(1000).build();
    }
    public boolean releaseIfEnded(GlobalTicketRow ticket) {
        if(ticket.getState()!=3)return false;
        var f=store.formation(ticket.getFormationId());if(f==null)return false;
        if(f.getMembersList().stream().noneMatch(m->m.getPlayerId()==ticket.getPlayerId()&&m.getTicket().equals(ticket.getTicket())))return false;
        var terminal=store.terminal(f.getBattleId());
        if(terminal!=null&&terminal.getOutcomeProtocolVersion()==1) {
            try {
                // releaseReady在同P锁内核此人的永久Inbox APPLIED，不能因房间消失释放全员。
                store.releaseReady(ticket.getPlayerId(),ticket.getTicket(),f.getBattleId());return true;
            } catch(RuntimeException pending) { return false; }
        }
        try {var state=query(f).getState();
            if(state==BattleCreationState.BATTLE_CREATION_ABSENT) {
                // 单次ABSENT不能排除在途Create；先给同原operation落取消墓碑，再只认ABORTED正证明。
                invoke(f,s->s.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(f.getBattleId()).setPlacementOperationId(f.getOperationId())
                        .setTargetInstanceId(f.getInstanceId()).setReason("global_match_abort_if_absent").build()));
                state=query(f).getState();
            }
            if(state==BattleCreationState.BATTLE_CREATION_ABORTED) {
            for(var member:f.getMembersList())store.releaseReady(member.getPlayerId(),member.getTicket(),f.getBattleId());return true;
        }}catch(Exception e){log.debug("全球已建房票据仍待权威查询");}return false;
    }
    public synchronized void round() {
        if(!config.enabled())return;
        try {
            for(var f:store.pending())launch(f,false);
            sweepReady();
            boolean repaired=store.repairRegions();
            if(!ids.leaseValid()||slots.availablePermits()==0)return;
            if(roundUnits(repaired))return;
            var candidates=store.candidates(scanMs,scanPlayer);
            if(candidates.isEmpty()){scanMs=0;scanPlayer=0;return;}
            var nodes=GlobalRegionPolicy.nodes(directory.list(0));long stamp=store.now();
            // 本区先完整尝试这批锚点，再跨区；只推进实际尝试项，后续锚点不能被提前跳过。
            for(int pass=0;pass<(repaired?2:1);pass++)for(var anchor:candidates) {
                if(pass==0){scanMs=anchor.getEnqueuedMs();scanPlayer=anchor.getPlayerId();}
                if(anchor.getTeamSize()<1||anchor.getTeamSize()>10)continue;
                Map<Integer,Integer> rtts=GlobalRegionPolicy.latencies(GlobalProbeProfile.parseFrom(anchor.getProfile()),stamp,config.hardLatencyMs());
                for(var node:nodes.values()) {
                    int region=node.getPhysicalRegion();
                    if(!rtts.containsKey(region)||(pass==0&&anchor.getPreferredRegion()!=region)
                            ||(pass==1&&stamp-anchor.getEnqueuedMs()<config.waitSeconds()*1000L))continue;
                    var peers=store.peers(anchor,region,pass==1,stamp);
                    if(peers.size()+1!=anchor.getTeamSize())continue;
                    List<GlobalTicketRow> members=new ArrayList<>();members.add(anchor);members.addAll(peers);
                    var id=ids.nextBattleId();if(id.isEmpty())return;
                    try{var f=store.claim(members,formation(id.getAsLong(),node,anchor.getMode(),anchor.getConfigId()).setRequeueOnFailure(true).build(),false);launch(f,true);}
                    catch(RuntimeException e){log.debug("成局竞争失败，下一轮重读");}
                    return;
                }
            }
        }catch(Exception e){log.error("全球凑单/恢复本轮失败",e);}
    }
    private boolean roundUnits(boolean repaired)throws Exception {
        var anchors=store.unitCandidates(unitMs,unitId);
        if(anchors.isEmpty()){unitMs=0;unitId="";return false;}
        var nodes=GlobalRegionPolicy.nodes(directory.list(0));long stamp=store.now();
        List<GlobalQueueUnitRow> tried=new ArrayList<>();
        for(int pass=0;pass<(repaired?2:1);pass++){
            var budget=Deadline.after(pass==0?1000:2000);
            for(var anchor:pass==0?anchors:tried){
                if(budget.expired())break;
                if(pass==0){unitMs=anchor.getEnqueuedMs();unitId=anchor.getUnitId();}
                if(anchor.getExpiresMs()<=stamp){store.cancelUnit(null,anchor.getUnitId());continue;}
                if(anchor.getNotBeforeMs()>stamp)continue;
                boolean retired=false,unavailable=false;
                for(var member:anchor.getOriginalTicketsList()){
                    if(budget.expired()){unavailable=true;break;}
                    var location=players.location(member.getPlayerId(),budget);
                    if(location.status()==com.game.discovery.location.PlayerLocationDirectory.LocationStatus.LOGGED_OUT||location.status()==com.game.discovery.location.PlayerLocationDirectory.LocationStatus.MISSING){retired=true;break;}
                    if(location.status()==com.game.discovery.location.PlayerLocationDirectory.LocationStatus.RECONNECT_LEASE)unavailable=true;
                }
                if(retired){store.cancelUnit(null,anchor.getUnitId());continue;}if(unavailable)continue;
                for(var node:nodes.values()){
                    if(budget.expired())break;int region=node.getPhysicalRegion();
                    boolean eligible=true;
                    for(var member:anchor.getOriginalTicketsList())if(!GlobalRegionPolicy.latencies(GlobalProbeProfile.parseFrom(member.getProfile()),stamp,config.hardLatencyMs()).containsKey(region)
                            ||pass==0&&member.getPreferredRegion()!=region||pass==1&&stamp-member.getEnqueuedMs()<config.waitSeconds()*1000L){eligible=false;break;}
                    if(!eligible)continue;
                    var peers=store.unitPeers(anchor,region,pass==1,stamp,budget);if(budget.expired())break;
                    var packed=GlobalQueueUnits.pack(anchor,peers);if(packed.isEmpty())continue;
                    var members=packed.stream().flatMap(unit->unit.getOriginalTicketsList().stream()).toList();
                    var battle=ids.nextBattleId();if(battle.isEmpty())return true;
                    try{var f=store.claim(members,formation(battle.getAsLong(),node,anchor.getMode(),anchor.getConfigId()).setRequeueOnFailure(true).build(),false);launch(f,true);return true;}
                    catch(RuntimeException race){log.debug("完整单位提交发生竞争，保留原单位下一轮重读");break;}
                }
                if(pass==0&&!budget.expired())tried.add(anchor);
            }
        }return false;
    }
    private void sweepReady() {
        var rows=store.readyCandidates(readyMs,readyPlayer);
        if(rows.isEmpty()){readyMs=0;readyPlayer=0;return;}
        int submitted=0;
        for(var ticket:rows) {
            if(submitted>=2||slots.availablePermits()<=4)return;
            readyMs=ticket.getEnqueuedMs();readyPlayer=ticket.getPlayerId();
            if(!running.add(ticket.getFormationId()))continue;
            if(!slots.tryAcquire()){running.remove(ticket.getFormationId());return;}
            submitted++;
            try{workers.execute(()->{try{releaseIfEnded(ticket);}finally{running.remove(ticket.getFormationId());slots.release();}});}
            catch(RejectedExecutionException closing){running.remove(ticket.getFormationId());slots.release();}
        }
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
            if(prior!=null){if(prior.getCancelled()||!prior.getPayload().equals(payload))throw new IllegalArgumentException("业务意图已经决定或参数不同");
                if(prior.getBattleId()!=0)return store.formation(prior.getBattleId());return resumeDirect(prior,Deadline.after(4000));}
            long stamp=store.now();
            var members=directMembers(mode,configId,roster,tickets,teamId,stamp);
            var f=directFormation(members,mode,configId,stamp);
            if(activity!=null)f.setActivityContext(activity.toByteString());
            return resumeDirect(store.prepareDirect(key,payload,members,f.build()),Deadline.after(4000));
        }catch(Exception e){throw new Deadline.DependencyException("全球原业务成局尚未确认",e);}
    }
    private GlobalFormationRow resumeDirect(GlobalDirectIntentRow original,Deadline deadline)throws Exception {
        List<ObserverExitOriginal> exits=new ArrayList<>();for(var bytes:original.getObserverExitsList())exits.add(ObserverExitOriginal.parseFrom(bytes));exitSpectators(exits,deadline);
        if(deadline.expired())throw new Deadline.DependencyException("原direct退出观战仍待恢复");
        var result=store.completeDirect(original);
        if(original.getKind()==3){var actor=SessionContext.newBuilder().setPlayerId(original.getOwnerId()).setAccount(original.getOwnerAccount()).setOwnerRevision(original.getOwnerRevision()).build();
            try{challengeNotifications.accept(store.invitation(actor,original.getRequestLow()));}catch(RuntimeException advisory){log.warn("原挑战结果推送留待客户端同请求查询");}}
        return result;
    }
    private List<GlobalTicketRow> directMembers(int mode,int configId,List<Long> roster,Map<Long,String> tickets,long teamId,long stamp)throws Exception {
        List<GlobalTicketRow> members=new ArrayList<>();
        for(long player:roster) {
            var p=store.profile(player);var original=GlobalProbeProfile.parseFrom(p.getPayload());
            members.add(GlobalTicketRow.newBuilder().setPlayerId(player).setMode(mode).setConfigId(configId).setTeamId(teamId)
                    .setTicket(tickets.getOrDefault(player,"")).setTeamSize(roster.size()).setState(1).setEnqueuedMs(stamp).setExpiresMs(p.getExpiresMs()).setProfile(p.getPayload())
                    .setOwnerAccount(original.getOwnerAccount()).setOwnerRevision(original.getOwnerRevision()).setPreferredRegion(GlobalRegionPolicy.preferred(original,stamp)).build());
        }
        return members;
    }
    public GlobalFormationRow reserveTeam(TeamTicketsRequest request) {
        try {
            var original=GlobalTeamLaunchAuthority.original(request.getLaunchOriginal());
            var ordered=original.getMembersList().stream().sorted(Comparator.comparingInt(com.game.proto.team.TeamLaunchMember::getRosterOrder)).toList();
            var roster=ordered.stream().map(member->member.getOriginal().getPlayerId()).toList();Map<Long,String> tickets=new LinkedHashMap<>();
            ordered.forEach(member->tickets.put(member.getOriginal().getPlayerId(),member.getTicket()));
            if(request.getTeamId()!=original.getTeamId()||request.getInitiatorPlayerId()!=original.getActor().getPlayerId()
                    ||!request.getOwnerAccount().equals(original.getActor().getOwnerAccount())||request.getOwnerRevision()!=original.getActor().getOwnerRevision()
                    ||request.getBattleConfigId()!=original.getRequest().getBattleConfigId()||!request.getRequestId().equals(original.getRequest().getRequestId())
                    ||!request.getClientRequest().equals(original.getRequest().toByteString())||!request.getRosterList().equals(roster)
                    ||!request.getTicketIdsMap().equals(tickets)||!request.getZonesMap().equals(original.getZonesMap()))throw new SecurityException("整队RPC与完整持久launch原件不同");
            var key=GlobalMatchStore.directUuidKey(1,request.getInitiatorPlayerId(),request.getRequestId()).toBuilder().setOwnerAccount(request.getOwnerAccount()).setOwnerRevision(request.getOwnerRevision()).build();
            var replay=store.teamReplay(key,request.getClientRequest(),request.getLaunchOriginal());if(replay!=null)return replay;
            long stamp=store.now();int mode=MatchMode.MATCH_MODE_PVE_TEAM_VALUE;
            int target=original.getRequest().getTargetTeamSize();
            if(target>props.pveTeamSizeFor(request.getBattleConfigId())||target!=0&&target<roster.size())throw new IllegalArgumentException("整队目标人数超出副本范围");
            var prior=store.direct(key);GlobalDirectIntentRow prepared;
            if(prior!=null&&prior.getAdmissionPrepared()&&prior.hasStartOriginal())prepared=store.prepareTeam(key,request.getLaunchOriginal(),prior.getStartOriginal().getTicketsList(),prior.getStartOriginal().getFormation());
            else {var initial=directMembers(mode,request.getBattleConfigId(),roster,tickets,request.getTeamId(),stamp).stream().map(member->member.toBuilder().setZoneId(original.getZonesOrThrow(member.getPlayerId())).build()).toList();
                prepared=store.prepareTeam(key,request.getLaunchOriginal(),initial,target>roster.size()?GlobalFormationRow.getDefaultInstance():directFormation(initial,mode,request.getBattleConfigId(),stamp).build());}
            var members=prepared.getStartOriginal().getTicketsList();exitSpectators(original.getObserverExitsList(),Deadline.after(3000));
            if(store.closeObsoleteTeam(prepared,Deadline.after(3000))){var closed=store.teamReplay(key,request.getClientRequest(),request.getLaunchOriginal());if(closed==null)throw new IllegalStateException("Team终局证明尚未确认");return closed;}
            if(target>roster.size())return store.enqueueTeam(key,request.getLaunchOriginal(),members);
            return store.reserveTeam(key,request.getClientRequest(),request.getLaunchOriginal(),members,prepared.getStartOriginal().getFormation());
        }catch(Exception e){throw new Deadline.DependencyException("整队SQL原launch仍待确认",e);}
    }
    private GlobalFormationRow.Builder directFormation(List<GlobalTicketRow> members,int mode,int configId,long stamp) {
        // 点名整队只要求同机房全员硬延迟合格，不沿用排队的跨区等待门槛。
        var relaxed=new GlobalMatchProperties(true,config.requireTiDb(),1,config.hardLatencyMs(),config.profileTtlSeconds(),config.probeSecret());
        var choice=GlobalRegionPolicy.choose(members,directory.list(0),stamp+1000,relaxed);
        if(choice==null)throw new IllegalStateException("没有满足全员硬延迟的共同机房");
        return formation(ids.nextBattleId().orElseThrow(),choice.node(),mode,configId);
    }
    public GlobalChallengeRow decideChallenge(SessionContext session,RespondChallengeRequest request) {return decideChallenge(session,request,Deadline.after(4000));}
    public GlobalChallengeRow decideChallenge(SessionContext session,RespondChallengeRequest request,Deadline deadline) {
        try {
            var original=store.invitation(session,request.getChallengeId());
            var payload=request.toBuilder().setPlayerId(session.getPlayerId()).build().toByteString();
            GlobalChallengeRow result;
            if(original.getState()!=1||!request.getAccept())result=store.decideChallenge(session,request.getChallengeId(),payload,request.getAccept(),List.of(),null);
            else {long stamp=store.now();int mode=MatchMode.MATCH_MODE_PVP_CHALLENGE_VALUE;
                var members=directMembers(mode,original.getConfigId(),List.of(original.getChallengerId(),original.getTargetId()),Map.of(),0,stamp);
                result=store.decideChallenge(session,request.getChallengeId(),payload,true,members,directFormation(members,mode,original.getConfigId(),stamp).build());}
            if(result.getState()==4){var key=GlobalDirectIntentRow.newBuilder().setKind(3).setOwnerId(result.getTargetId()).setRequestLow(result.getChallengeId()).setOwnerAccount(result.getTargetAccount()).setOwnerRevision(result.getTargetRevision()).build();
                resumeDirect(store.direct(key),deadline);return store.invitation(session,result.getChallengeId());}
            return result;
        }catch(Exception e){throw new Deadline.DependencyException("切磋SQL原决定尚未确认",e);}
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
                    .setTargetInstanceId(f.getInstanceId()).setAdmitBeforeMs(Math.min(f.getCreatedMs()+120_000,f.getRetryMs()-1000))
                    .setOutcomeProtocolVersion(1);
            if(!f.getActivityContext().isEmpty())request.setActivityContext(BattleActivityContext.parseFrom(f.getActivityContext()));
            // 观战可见性建房时决定一次，随 formation 持久的 create_request 冻结；之后观战各处只读这份原件，不按模式重算
            request.setSpectateVisibility(com.game.match.spectate.SpectateVisibilityPolicy.decide(MatchMode.forNumber(f.getMode()),
                    request.hasActivityContext()?request.getActivityContext():null));
            var preflight=query(f);
            f=store.saveIntent(f,NetworkProbes.bytes(request.build()),preflight);
            return create(f);
        }catch(Exception e){try{store.unknown(f);}catch(RuntimeException stale){log.debug("全球工作租约已转移");}log.error("全球备战/建房等待恢复",e);return GatherResult.failed(GatherOutcome.CREATE_FAILED_ROOM_ALIVE,f.getBattleId());}
    }
    private GatherResult create(GlobalFormationRow f)throws Exception {
        var request=CreateBattleRequest.parseFrom(f.getCreateRequest());
        var known=store.terminal(f.getBattleId());
        if(known!=null&&known.getAdoptedOutcomeObserved()&&known.getAdoptedOutcomeProtocolVersion()!=1)
            throw new SecurityException("执行端已明确未采用v1，禁止继续Create");
        store.checkWork(f);
        var result=invoke(f,s->s.createBattle(request));
        if(result.getAdmission()==BattleAdmission.BATTLE_ADMISSION_ADMITTED
                &&CreateBattleResponse.parseFrom(result.getResponse()).getErrorMessage().getId()==0) {
            if(request.getOutcomeProtocolVersion()==1) {
                var actual=CreateBattleResponse.parseFrom(result.getResponse());
                store.adopted(f,actual.getAdoptedOutcomeProtocolVersion(),actual.getTargetInstanceId());
            }
            store.finish(f,true);project(f,request);return GatherResult.success(f.getBattleId());
        }
        // 拒绝也写入精确实例墓碑，防并发恢复中已经在途的同操作创建迟到复活。
        abort(f);return GatherResult.failed(GatherOutcome.CREATE_REJECTED,f.getBattleId());
    }
    private void recover(GlobalFormationRow f)throws Exception {
        var actual=query(f);var state=actual.getState();
        switch(state) {
            case BATTLE_CREATION_PRESENT -> {
                if(CreateBattleRequest.parseFrom(f.getCreateRequest()).getOutcomeProtocolVersion()==1)
                    store.adopted(f,actual.getAdoptedOutcomeProtocolVersion(),actual.getTargetInstanceId());
                store.finish(f,true);project(f,CreateBattleRequest.parseFrom(f.getCreateRequest()));
            }
            case BATTLE_CREATION_ABORTED -> abort(f);
            case BATTLE_CREATION_ABSENT -> {if(!f.getCreateRequest().isEmpty()&&store.now()<CreateBattleRequest.parseFrom(f.getCreateRequest()).getAdmitBeforeMs())create(f);else abort(f);}
            default -> {try{store.unknown(f);}catch(RuntimeException stale){log.debug("全球工作租约已转移");}}
        }
    }
    void abort(GlobalFormationRow f)throws Exception {
        if(f.getCreateRequest().isEmpty()){
            // 空内存快照不是证明。Store 在同一 SQL 事务核工作代次和原件后收尾，失租旧 worker 再也写不出 Create。
            var uncreated=store.finishUncreated(f);
            cancelPrepared(uncreated);
            return;
        }
        store.checkWork(f);
        invoke(f,s->s.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(f.getBattleId()).setPlacementOperationId(f.getOperationId())
                .setTargetInstanceId(f.getInstanceId()).setReason("global_match_abort_if_absent").build()));
        var actual=query(f);var state=actual.getState();
        if(state==BattleCreationState.BATTLE_CREATION_PRESENT){
            if(CreateBattleRequest.parseFrom(f.getCreateRequest()).getOutcomeProtocolVersion()==1)
                store.adopted(f,actual.getAdoptedOutcomeProtocolVersion(),actual.getTargetInstanceId());
            store.finish(f,true);return;
        }
        if(state!=BattleCreationState.BATTLE_CREATION_ABORTED)throw new IllegalStateException("撤销尚未由固定执行端确认");
        store.checkWork(f);
        var latest=store.formation(f.getBattleId());
        // 只有固定执行端确认墓碑后才能解冻、回队或结束票据。
        cancelPrepared(latest);
        store.finish(f,false);
    }
    private void cancelPrepared(GlobalFormationRow f){
        // 已持久原端点 + 原 battle_id；失败由备战期限 reaper 收尾，迟到取消不能解开新局。
        for(var endpoint:f.getPreparedList())scenes.cancel(endpoint.getPlayerId(),f.getBattleId(),new ScenePreparer.Endpoint(endpoint.getZoneId(),
                endpoint.getNodeId(),new NodeRpcClients.Target(endpoint.getHost(),endpoint.getPort(),endpoint.getInstanceId())));
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
        // 投影抄建房原件里冻结的可见性：开局钩子据此只公开公开局（私密局的投影照写，补签靠它定位）
        p.setSpectateVisibility(request.getSpectateVisibilityValue());
        projections.write(p.build());try{hooks.onStarted(p.build());}catch(RuntimeException e){log.warn("观战展示投影稍后恢复",e);}
    }
    public RequestBattleTicketResponse reissue(long player,long battle) {
        throw new SecurityException("全球补票必须保留原会话身份");
    }
    public RequestBattleTicketResponse reissue(SessionContext actor,long battle,Deadline deadline) {
        try {
            current(actor,deadline);
            if(spectators==null)throw new IllegalStateException("SQL参战/观战准入未装配");
            String operation=spectators.authority().reissue(actor,battle,deadline);
            var f=store.formation(battle);if(f==null||f.getState()==5)return RequestBattleTicketResponse.newBuilder().setErrorMessage(MatchTip.REISSUE_BATTLE_GONE.proto()).build();
            var state=query(f).getState();
            if(state==BattleCreationState.BATTLE_CREATION_ABSENT||state==BattleCreationState.BATTLE_CREATION_ABORTED)
                return RequestBattleTicketResponse.newBuilder().setErrorMessage(MatchTip.REISSUE_BATTLE_GONE.proto()).build();
            if(state!=BattleCreationState.BATTLE_CREATION_PRESENT)throw new IllegalStateException("建房结局未知");
            // battle 自己检查参战者/观众名单，SQL 落点不依赖 Redis 展示索引。
            var result=invoke(f,s->s.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setPlayerId(actor.getPlayerId()).setBattleId(battle)
                .setSpectatorOperationId(operation).setTargetInstanceId(f.getInstanceId()).build()));
            return RequestBattleTicketResponse.newBuilder().setAssignment(result.getAssignment()).setErrorMessage(result.getErrorMessage()).build();
        }catch(Exception e){return RequestBattleTicketResponse.newBuilder().setErrorMessage(MatchTip.REISSUE_BATTLE_UNAVAILABLE.proto()).build();}
    }
    @Override public void close(){admissionTimer.shutdownNow();workers.shutdown();try{if(!workers.awaitTermination(10,TimeUnit.SECONDS))workers.shutdownNow();}
        catch(InterruptedException e){Thread.currentThread().interrupt();workers.shutdownNow();}}
}
