package com.game.scene.storage;

import com.game.player.store.PlayerStore;
import com.game.player.store.PlayerStore.FinalSaveReceipt;
import com.game.player.store.PlayerStore.FinalSaveStatus;
import com.game.scene.world.PlayerRepository;
import com.game.scene.world.PlayerRepository.FinalSaveRegistration;
import com.game.scene.world.PlayerSave;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 最终快照接管与SQL待办补偿。没有本机WAL，也不维护另一份资产账。 */
public final class FinalSaveRecovery implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(FinalSaveRecovery.class);
    private static final Duration SQL_BUDGET=Duration.ofSeconds(2);
    private static final int SCAN_BATCH=16;
    private final PlayerStore store;
    private final Executor storage;
    private final Executor logic;
    private final String sourceInstance;
    private final LongSupplier nowMs;
    private final ConcurrentHashMap<Key,Job> unregistered=new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Key> turns=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Key,FinalSaveReceipt> awaitingApplied=new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<Key> probeTurns=new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 只有一个应用任务可读取大blob；扫描本身只读有界键列表。 */
    private final AtomicBoolean applying=new AtomicBoolean();
    private final AtomicBoolean probing=new AtomicBoolean();
    private final AtomicBoolean scanning=new AtomicBoolean();
    private final AtomicInteger pendingResults=new AtomicInteger();
    private final AtomicInteger failedResults=new AtomicInteger();
    private volatile long nextCleanup;
    private volatile boolean closed;
    private ScheduledFuture<?> scheduled;
    private volatile Consumer<FinalSaveReceipt> onApplied=ignored->{};
    private record Key(long player,long epoch) { }
    private static final class Job {
        final PlayerSave snapshot;
        final List<Consumer<FinalSaveRegistration>> waiters=new ArrayList<>();
        final AtomicBoolean running=new AtomicBoolean();
        volatile long nextAttempt;
        int attempts;
        volatile FinalSaveRegistration terminal;
        Job(PlayerSave snapshot){this.snapshot=snapshot;}
    }

    public FinalSaveRecovery(PlayerStore store,Executor storage,Executor logic,String sourceInstance,LongSupplier nowMs) {
        this.store=Objects.requireNonNull(store);this.storage=Objects.requireNonNull(storage);
        this.logic=Objects.requireNonNull(logic);this.sourceInstance=Objects.requireNonNull(sourceInstance);
        this.nowMs=Objects.requireNonNull(nowMs);
    }
    public synchronized void start(ScheduledExecutorService scheduler) {
        if(closed || scheduled!=null) throw new IllegalStateException("最终补偿生命周期不能重复启动");
        scheduled=scheduler.scheduleWithFixedDelay(this::sweep,0,500,TimeUnit.MILLISECONDS);
    }
    public synchronized void submit(PlayerSave snapshot,Consumer<FinalSaveRegistration> onRegistration) {
        Objects.requireNonNull(snapshot);Objects.requireNonNull(onRegistration);
        if(closed){deliver(onRegistration,new FinalSaveRegistration.Unconfirmed());return;}
        Key key=new Key(snapshot.playerId(),snapshot.ownerEpoch());
        Job candidate=new Job(snapshot);Job job=unregistered.putIfAbsent(key,candidate);
        if(job==null) {job=candidate;turns.add(key);}
        synchronized(job) {
            if(!job.snapshot.equals(snapshot)) {deliver(onRegistration,new FinalSaveRegistration.Conflict());return;}
            if(job.terminal!=null) {deliver(onRegistration,job.terminal);return;}
            job.waiters.add(onRegistration);
        }
        register(key,job);
    }
    private void register(Key key,Job job) {
        if(closed || job.terminal!=null || job.nextAttempt>nowMs.getAsLong() || !job.running.compareAndSet(false,true)) return;
        try {
            storage.execute(()->{
                try {
                    if(closed) return;
                    var position=job.snapshot.position();
                    if(position==null || !Double.isFinite(position.x()) || !Double.isFinite(position.y())
                            || !Double.isFinite(position.z())) {
                        finish(key,job,new FinalSaveRegistration.Conflict());return;
                    }
                    // protobuf消息不可变，序列化不经builder/重排；不为每个未登记玩家常驻第二份16MiB数组。
                    byte[] bytes=job.snapshot.state().toByteArray();
                    FinalSaveReceipt receipt=store.registerFinalSave(StoragePlayerRepository.toRow(job.snapshot),
                            bytes,sourceInstance,SQL_BUDGET);
                    switch(receipt.status()) {
                        case ACCEPTED,APPLIED -> {
                            if(receipt.playerId()!=key.player() || receipt.ownerEpoch()!=key.epoch()
                                    || receipt.saveSeq()!=1 || !sourceInstance.equals(receipt.sourceInstance())) {
                                finish(key,job,new FinalSaveRegistration.Conflict());return;
                            }
                            if(awaitingApplied.put(key,receipt)==null)probeTurns.add(key);
                            finish(key,job,new FinalSaveRegistration.Durable(new PlayerRepository.FinalSaveReceipt(
                                    receipt.playerId(),receipt.ownerEpoch(),receipt.saveSeq(),receipt.sourceInstance(),
                                    receipt.payloadDigest()),receipt.status()==FinalSaveStatus.APPLIED));
                            // ACCEPTED只证明TX1；应用一律从SQL due扫描进入，不能绕过持久next_attempt_at。
                            if(receipt.status()==FinalSaveStatus.APPLIED)applied(key,receipt);
                        }
                        case FENCED,NOT_FOUND -> finish(key,job,new FinalSaveRegistration.Fenced());
                        case CONFLICT -> finish(key,job,new FinalSaveRegistration.Conflict());
                        case TIMED_OUT -> retry(job);
                    }
                } catch(RuntimeException failure) {
                    // 提交应答丢失也在这里：同key/原bytes/source重投认重，不能当成未提交。
                    retry(job);log.warn("最终接管尚无确认，保留冻结快照 epoch={}",key.epoch(),failure);
                } finally {job.running.set(false);}
            });
        } catch(RejectedExecutionException full) {
            job.running.set(false);retry(job);
        }
    }
    private void retry(Job job) {
        int attempts=job.attempts=Math.min(20,job.attempts+1);
        long backoff=Math.min(30_000L,200L<<Math.min(attempts-1,7));
        long jitter=java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1,backoff/5));
        job.nextAttempt=nowMs.getAsLong()+backoff+jitter;
    }
    private void finish(Key key,Job job,FinalSaveRegistration result) {
        List<Consumer<FinalSaveRegistration>> waiters;
        synchronized(job) {
            if(job.terminal!=null) return;
            job.terminal=result;waiters=List.copyOf(job.waiters);job.waiters.clear();
        }
        // 先公布投递义务/拒绝，再撤下RAM快照义务，停机不能在两者之间读到虚假的全零。
        for(var waiter:waiters) deliver(waiter,result);
        synchronized(unregistered){
            if(!(result instanceof FinalSaveRegistration.Conflict))unregistered.remove(key,job);
            unregistered.notifyAll();
        }
    }
    private void deliver(Consumer<FinalSaveRegistration> callback,FinalSaveRegistration result) {
        dispatchResult(()->callback.accept(result),result.getClass().getSimpleName());
    }
    private void dispatchResult(Runnable callback,String kind) {
        synchronized(unregistered){pendingResults.incrementAndGet();}
        try {
            logic.execute(()->{
                try{callback.run();}
                catch(RuntimeException failure){
                    synchronized(unregistered){failedResults.updateAndGet(n->n==Integer.MAX_VALUE?n:n+1);}
                    log.error("最终结果回调异常，SQL凭据不撤销但停机必须unclean result={}",kind,failure);
                } finally {
                    synchronized(unregistered){pendingResults.decrementAndGet();unregistered.notifyAll();}
                }
            });
        } catch(RejectedExecutionException stopped) {
            synchronized(unregistered){
                failedResults.updateAndGet(n->n==Integer.MAX_VALUE?n:n+1);
                pendingResults.decrementAndGet();unregistered.notifyAll();
            }
            log.error("逻辑线程已停止，最终结果无法送达；SQL凭据不撤销但停机必须unclean result={}",kind);
        }
    }
    /** 定时器只投递任务；全部SQL仍在原有有界存储池执行。测试可显式推进此方法。 */
    public synchronized void sweep() {
        if(closed) return;
        for(int count=0;count<32;count++) {
            Key key=turns.poll();if(key==null)break;
            Job job=unregistered.get(key);if(job!=null){register(key,job);turns.add(key);}
        }
        if(!scanning.compareAndSet(false,true)) return;
        try {
            storage.execute(()->{
                try {
                    if(closed) return;
                    for(var key:store.dueFinalSaves(nowMs.getAsLong(),SCAN_BATCH)) {
                        if(closed) break;
                        apply(new Key(key.playerId(),key.ownerEpoch()));
                    }
                    long now=nowMs.getAsLong();
                    if(now>=nextCleanup) {
                        nextCleanup=now+60_000;
                        store.cleanRetiredFinalSaves(now-TimeUnit.DAYS.toMillis(7),16,SQL_BUDGET);
                    }
                } catch(RuntimeException failure){log.warn("最终SQL待办扫描失败，保留原待办",failure);}
                finally{scanning.set(false);if(!closed)probeApplied();}
            });
        } catch(RejectedExecutionException full){scanning.set(false);}
    }
    private void apply(Key key) {
        if(closed || !applying.compareAndSet(false,true)) return;
        try {
            storage.execute(()->{
                try {
                    if(closed) return;
                    var receipt=store.completeFinalSave(key.player(),key.epoch(),SQL_BUDGET);
                    if(receipt.status()==FinalSaveStatus.APPLIED) applied(key,receipt);
                    else postpone(key,receipt.status().name().toLowerCase(java.util.Locale.ROOT));
                } catch(RuntimeException failure) {
                    // 无论是否瞬时都保留SQL payload；永久错误停放/告警，不能按次数删除。
                    log.error("最终SQL应用未确认，持久待办保留 epoch={}",key.epoch(),failure);
                    postpone(key,"sql_unconfirmed");
                } finally{applying.set(false);}
            });
        } catch(RejectedExecutionException full){applying.set(false);}
    }
    /** 本地凭据只探测终态；应用只从SQL due列表进入，不绕过持久退避。 */
    private void probeApplied() {
        if(awaitingApplied.isEmpty() || !probing.compareAndSet(false,true))return;
        if(!applying.compareAndSet(false,true)){probing.set(false);return;}
        try {
            storage.execute(()->{
                try {
                    for(int count=0;count<8 && !closed;count++) {
                        Key key=probeTurns.poll();if(key==null)break;
                        if(!awaitingApplied.containsKey(key))continue;
                        try {
                            var receipt=store.probeFinalSave(key.player(),key.epoch(),SQL_BUDGET);
                            if(receipt.status()==FinalSaveStatus.APPLIED)applied(key,receipt);
                        } catch(RuntimeException failure){log.warn("最终接管终态暂不可读，保留本地凭据",failure);}
                        if(awaitingApplied.containsKey(key))probeTurns.add(key);
                    }
                } finally{applying.set(false);probing.set(false);}
            });
        } catch(RejectedExecutionException full){applying.set(false);probing.set(false);}
    }
    private void postpone(Key key,String result) {
        try{store.postponeFinalSave(key.player(),key.epoch(),nowMs.getAsLong()+5_000
                +java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000),result,SQL_BUDGET);}
        catch(RuntimeException failure){log.warn("最终待办退避元数据未写入，原payload不删除",failure);}
    }
    public int unregisteredCount(){return unregistered.size();}
    public int awaitingAppliedCount(){return awaitingApplied.size();}
    /** 独立于SQL未登记数：含投递拒绝/回调异常，以及预算结束仍在逻辑队列里的结果。 */
    public int undeliveredResultCount(){
        synchronized(unregistered){return (int)Math.min(Integer.MAX_VALUE,(long)pendingResults.get()+failedResults.get());}
    }
    public void onApplied(Consumer<FinalSaveReceipt> listener){onApplied=Objects.requireNonNull(listener);}
    private void applied(Key key,FinalSaveReceipt receipt) {
        FinalSaveReceipt expected=awaitingApplied.get(key);
        if(expected==null || receipt.status()!=FinalSaveStatus.APPLIED || receipt.playerId()!=key.player()
                || receipt.ownerEpoch()!=key.epoch() || receipt.saveSeq()!=expected.saveSeq()
                || expected.playerId()!=receipt.playerId() || expected.ownerEpoch()!=receipt.ownerEpoch()
                || !expected.sourceInstance().equals(receipt.sourceInstance())
                || !expected.payloadDigest().equals(receipt.payloadDigest())) return;
        if(awaitingApplied.remove(key,expected)) {
            dispatchResult(()->onApplied.accept(receipt),"APPLIED");
        }
    }
    public int awaitRegistered(long timeoutNanos) throws InterruptedException {
        long deadline=System.nanoTime()+Math.max(0,timeoutNanos);
        synchronized(unregistered) {
            while(!unregistered.isEmpty() || pendingResults.get()>0) {
                long remaining=deadline-System.nanoTime();if(remaining<=0) break;
                TimeUnit.NANOSECONDS.timedWait(unregistered,remaining);
            }
        }
        return unregistered.size();
    }
    /** 等待真实TX2及owner释放；TX1接管凭据不能作为全局维护的排空证据。 */
    public int awaitApplied(long timeoutNanos)throws InterruptedException {
        long until=System.nanoTime()+Math.max(0,timeoutNanos);
        synchronized(unregistered){
            while(unregisteredCount()!=0||awaitingAppliedCount()!=0||pendingResults.get()!=0||applying.get()||scanning.get()||probing.get()){
                long left=until-System.nanoTime();if(left<=0)break;
                TimeUnit.NANOSECONDS.timedWait(unregistered,Math.min(left,100_000_000L));
            }
            return (int)Math.min(Integer.MAX_VALUE,(long)unregisteredCount()+awaitingAppliedCount()+undeliveredResultCount()
                    +(applying.get()?1:0)+(scanning.get()?1:0)+(probing.get()?1:0));
        }
    }
    public synchronized boolean stopIfApplied(){
        if(unregisteredCount()!=0||awaitingAppliedCount()!=0||undeliveredResultCount()!=0||applying.get()||scanning.get()||probing.get())return false;
        closed=true;if(scheduled!=null)scheduled.cancel(false);return true;
    }
    @Override public synchronized void close() {
        if(closed) return;closed=true;if(scheduled!=null)scheduled.cancel(false);
        // 取消不抹掉未确认快照或SQL待办；每个等待者收到一次明确unclean结局。
        for(var entry:unregistered.entrySet()) {
            Job job=entry.getValue();List<Consumer<FinalSaveRegistration>> waiters;
            synchronized(job) {
                if(job.terminal!=null) continue;
                job.terminal=new FinalSaveRegistration.Unconfirmed();
                waiters=List.copyOf(job.waiters);job.waiters.clear();
            }
            for(var waiter:waiters)deliver(waiter,job.terminal);
        }
    }
}
