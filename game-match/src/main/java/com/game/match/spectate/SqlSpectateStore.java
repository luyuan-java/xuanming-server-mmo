package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import com.game.match.gather.BattleNodes;
import com.game.match.global.GlobalWatchAuthority;
import com.game.match.global.pb.GlobalWatchingRow;
import com.game.match.metrics.MatchMetrics;
import com.game.match.placement.PlacementDialer;
import com.game.match.proto.BattlePlacement;
import com.game.proto.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 既有观战口的SQL适配。当前标记、固定落点、取消证明均来自SQL；Redis不参与授权。 */
public final class SqlSpectateStore implements SpectateStore,AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(SqlSpectateStore.class);
    /** 请求历史的保留期清理：一分钟一页、每页至多 1000 行（一天约 144 万行）。低频、有界；跑在恢复的定时线程上，一页的预算只会把下一次恢复 tick 推迟几秒。 */
    private static final long REQUEST_PURGE_PERIOD_MS=60_000;
    private static final int REQUEST_PURGE_BATCH=1000;
    private static final long REQUEST_PURGE_BUDGET_MS=3000;
    private final GlobalWatchAuthority authority;
    private final PlacementDialer dialer;
    private final BattleNodes nodes;
    private final MatchMetrics metrics;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("match-watch-recovery-tick").factory());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),Thread.ofVirtual().name("match-watch-recovery-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private long afterMs,afterPlayer;
    private long upperMs,upperPlayer;
    private boolean scanning;
    /** 翻页位置，只在定时线程上读写；进程重启从头再扫一遍即可。 */
    private GlobalWatchAuthority.RequestCursor purgeAfter=GlobalWatchAuthority.RequestCursor.START;
    /**
     * @param nodes   battle 目录：拿不到取消证明时判原实例是否已被替换
     * @param metrics 不带证明关闭与保留期清理的计数
     */
    public SqlSpectateStore(GlobalWatchAuthority authority,PlacementDialer dialer,BattleNodes nodes,MatchMetrics metrics){
        this.authority=authority;this.dialer=dialer;this.nodes=nodes;this.metrics=metrics;
    }
    public GlobalWatchAuthority authority(){return authority;}
    public void start(){
        timer.scheduleWithFixedDelay(this::recover,1000,1000,TimeUnit.MILLISECONDS);
        timer.scheduleWithFixedDelay(this::purgeRequests,REQUEST_PURGE_PERIOD_MS,REQUEST_PURGE_PERIOD_MS,TimeUnit.MILLISECONDS);
    }
    @Override public boolean sqlAuthority(){return true;}
    @Override public Entry entry(long player,Deadline d){throw new SecurityException("全球观战入口必须带原SessionContext");}
    @Override public Acquire acquire(long player,String mark,Deadline d){throw new SecurityException("全球观战保留必须带永久原UUID");}
    @Override public boolean release(long player,String mark,Deadline d){
        var row=authority.cancel(null,player,mark,d);if(row==null||row.getState()==5)return true;
        drive(row,d);var result=authority.read(player,d);
        if(result!=null&&result.getMark().equals(mark)&&result.getState()!=5)throw new Deadline.DependencyException("原观战取消尚无正证明");return true;
    }
    public void releaseForRequest(com.game.api.proto.SessionContext actor,String request,String mark,Deadline d){
        var row=authority.cancelForRequest(actor,request,mark,d);if(row==null||row.getState()==5)return;
        drive(row,d);var result=authority.read(actor.getPlayerId(),d);
        if(result!=null&&result.getMark().equals(mark)&&result.getState()!=5)throw new Deadline.DependencyException("换场取消尚无原操作证明");
    }
    public void releaseExit(com.game.proto.ObserverExitOriginal original,Deadline d){
        var row=authority.cancelExit(original,d);if(row==null||row.getState()==5)return;
        drive(row,d);if(authority.cancelExit(original,d)!=null)throw new Deadline.DependencyException("原参战意图退出观战仍待取消证明");
    }
    @Override public void releaseAsync(long player,String mark){try{workers.execute(()->{try{release(player,mark,Deadline.after(4000));}catch(RuntimeException failure){log.warn("原观战异步取消留待SQL恢复",failure);}});}catch(RejectedExecutionException ignored){/* SQL原件留存，公平恢复继续处理。 */}}
    @Override public Map<Long,String> marksOf(List<Long> players,Deadline d){return authority.marks(players,d);}
    @Override public Snapshot read(long battle,Deadline d){return authority.formation(battle,d);}
    @Override public Pick pickRandom(double random,Deadline d){return authority.pick(random,d);}
    // 以下仅展示清理；不能删除权威Formation、原参战票或观战许可。
    @Override public boolean evict(Eviction e,Deadline d){return false;}
    @Override public void evictAsync(List<Eviction> batch){}
    @Override public boolean publish(BattlePlacement placement,Deadline d){return read(placement.getBattleId(),d).published();}
    @Override public Listed list(int limit,Deadline d){return authority.list(limit,d);}
    @Override public Map<Long,Record> readPlacements(List<Long> ids,Deadline d){
        if(ids.size()>50)throw new IllegalArgumentException("观战批读超过50");Map<Long,Record> result=new LinkedHashMap<>();for(long id:new LinkedHashSet<>(ids))result.put(id,read(id,d).record());return result;
    }
    @Override public long sweep(Deadline d){return 0;}
    @Override public long watchableCount(Deadline d){return authority.watchableCount(d);}

    private synchronized void recover(){
        try {
            if(!scanning){var tail=authority.pendingTail(Deadline.after(1000));if(tail==null)return;upperMs=tail.getRetryMs();upperPlayer=tail.getPlayerId();scanning=true;}
            var rows=authority.pending(afterMs,afterPlayer,upperMs,upperPlayer,Deadline.after(1000));
            if(rows.isEmpty()){afterMs=0;afterPlayer=0;scanning=false;return;}
            for(var row:rows){
                try{workers.execute(()->{try{drive(row,Deadline.after(4000));}catch(RuntimeException failure){log.warn("原观战恢复保留未决记录",failure);}});}
                catch(RejectedExecutionException full){break;}
                // 仅推进实际受理项；坏归属/未知RPC不能永久霸占头八项。
                afterMs=row.getRetryMs();afterPlayer=row.getPlayerId();
            }
        }catch(RuntimeException failure){log.warn("读取全球观战恢复页失败，保留游标",failure);}
    }
    /** 删一页超过保留期的请求历史。失败只记日志、不动游标，下一轮重试：删除幂等，没有未知结局要收尾。 */
    void purgeRequests(){
        try{
            var result=authority.purgeRequests(purgeAfter,REQUEST_PURGE_BATCH,Deadline.after(REQUEST_PURGE_BUDGET_MS));
            purgeAfter=result.next();
            metrics.watchRequestsPurged(result.deleted());
        }catch(RuntimeException failure){log.warn("观战请求历史清理失败，下一轮重试",failure);}
    }
    void drive(GlobalWatchingRow expected,Deadline d){
        var work=authority.lease(expected,d);if(work==null)return;
        RemoveObserverResponse proof=null;boolean active=false;BattlePlacement placement;
        try {
            var add=AddObserverRequest.parseFrom(work.getAddRequest());placement=BattlePlacement.parseFrom(work.getPlacement());
            if(work.getState()==3){
                var query=QueryObserverRequest.newBuilder().setBattleId(add.getBattleId()).setObserverPlayerId(add.getObserverPlayerId())
                    .setSpectatorOperationId(add.getSpectatorOperationId()).setTargetInstanceId(add.getTargetInstanceId()).build();
                var answer=dialer.dial(placement,Duration.ofMillis(Math.min(1500,d.remainingMillis())),d,node->node.queryObserver(query));
                if(answer instanceof PlacementDialer.Dial.Replied<QueryObserverResponse> replied){
                    var value=replied.reply();boolean exact=value.getSpectatorOperationId().equals(add.getSpectatorOperationId())&&value.getTargetInstanceId().equals(add.getTargetInstanceId());
                    active=exact&&value.getState()==ObserverAdmissionState.OBSERVER_ADMISSION_ACTIVE;
                    // UNKNOWN或错实例不能当ABSENT；正常ACTIVE也不因恢复tick被清退。
                    if(!exact||value.getState()!=ObserverAdmissionState.OBSERVER_ADMISSION_ABSENT){settle(work,active,null,placement,d);return;}
                }else{settle(work,false,null,placement,d);return;}
            }
            var remove=RemoveObserverRequest.newBuilder().setBattleId(add.getBattleId()).setObserverPlayerId(add.getObserverPlayerId()).setReason("sql_watch_cancel")
                .setSpectatorOperationId(add.getSpectatorOperationId()).setAdmitBeforeMs(add.getAdmitBeforeMs()).setTargetInstanceId(add.getTargetInstanceId()).build();
            var outcome=dialer.dial(placement,Duration.ofMillis(Math.max(1,Math.min(2500,d.remainingMillis()))),d,node->node.removeObserver(remove));
            if(outcome instanceof PlacementDialer.Dial.Replied<RemoveObserverResponse> replied)proof=replied.reply();
        }catch(com.google.protobuf.InvalidProtocolBufferException corrupt){throw new Deadline.DependencyException("原观战Add或落点损坏",corrupt);}
        settle(work,active,proof,placement,d);
    }
    /**
     * 结算一次取消尝试（判据与放宽的理由见 {@link GlobalWatchAuthority#settled}）。拿不到证明时读一次目录：目录读不进持 P 锁的事务，查不到不算（条目过期、丢了租约、Redis 抖动都不代表实例已死）。
     * 这里读的是当下的目录，不走 {@link BattleInstanceCheck} 的缓存：恢复循环每轮至多 8 行，没有放大。
     */
    private void settle(GlobalWatchingRow work,boolean active,RemoveObserverResponse proof,BattlePlacement placement,Deadline d){
        boolean unproven=!active&&(proof==null||!proof.getCancelled());
        boolean replaced=unproven&&nodes.lookup(placement.getBattleNodeId(),placement.getBattleInstanceId(),d)==BattleNodes.Lookup.OTHER_INSTANCE;
        switch(authority.settled(work,active,proof,replaced,d)){
            case FORMATION_ENDED -> closedWithoutProof(work,MatchMetrics.UnprovenClose.FORMATION_ENDED);
            case INSTANCE_REPLACED -> closedWithoutProof(work,MatchMetrics.UnprovenClose.INSTANCE_REPLACED);
            case PROVEN,PENDING -> {}
        }
    }
    private void closedWithoutProof(GlobalWatchingRow work,MatchMetrics.UnprovenClose reason){
        metrics.watchUnprovenClose(reason);
        log.info("原观战不带取消证明关闭 player={} mark={} reason={}",Long.toUnsignedString(work.getPlayerId()),work.getMark(),reason);
    }
    /** 只在同run根cut且所有SQL原件已有终局后调用，返回真实timer/workers join结果。 */
    public boolean stopAndReport(long nanos)throws InterruptedException{
        long until=System.nanoTime()+Math.max(0,Math.min(nanos,10_000_000_000L));timer.shutdown();
        if(!timer.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS))return false;
        workers.shutdown();return workers.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS);
    }
    @Override public void close(){timer.shutdownNow();workers.shutdown();try{if(!workers.awaitTermination(5,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException stop){Thread.currentThread().interrupt();workers.shutdownNow();}}
}
