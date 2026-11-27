package com.game.match.rating;

import com.game.api.PlayerInboxService;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.completion.pb.BattleCompletionRow;
import com.game.match.global.GlobalMatchStore;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.TerminalDeliveryKind;
import com.game.match.metrics.MatchMetrics.TerminalDeliveryOutcome;
import com.game.match.completion.pb.BattlePlayerCompletionRow;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.player_inbox.BattleSettlementInboxRequest;
import com.game.proto.player_inbox.BattleSettlementInboxResponse;
import com.game.proto.player_inbox.PlayerInboxItemState;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 截止裁决与两个待投游标共用永久SQL来源。全局Kafka与个人Inbox是独立去向，都只重放原字节。 */
public final class BattleCompletionReplay implements AutoCloseable {
    @FunctionalInterface public interface Results {
        CompletableFuture<Void> publish(BattleResultEvent event,ByteString original);
    }
    private static final Logger log=LoggerFactory.getLogger(BattleCompletionReplay.class);
    private final BattleCompletionStore store;
    private final GlobalMatchStore formations;
    private final PlayerInboxService inbox;
    private final Results results;
    private final String secret;
    private final ServiceCallSignatures replies;
    private final LongSupplier clock;
    private final MatchMetrics metrics;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("battle-terminal-replay").daemon(true).factory());
    private final java.util.concurrent.locks.ReentrantLock turn=new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean quiesced;
    private static final long BACKLOG_SAMPLE_INTERVAL_SECONDS = 10L;
    private volatile com.game.common.workshard.WorkShard.Membership workShard;
    private volatile com.game.common.workshard.LoopLiveness liveness;
    private volatile com.game.match.spectate.RankedSpectateIndex rankedSpectateIndex;
    private long deadlineCursor,deadlineUpper,resultCursor;
    private final PlayerScanCursor firstPlayers=new PlayerScanCursor(),waitingPlayers=new PlayerScanCursor();
    public BattleCompletionReplay(BattleCompletionStore store,GlobalMatchStore formations,PlayerInboxService inbox,Results results,
                                  String secret,LongSupplier clock,MatchMetrics metrics) {
        this.store=Objects.requireNonNull(store);this.formations=Objects.requireNonNull(formations);
        this.inbox=Objects.requireNonNull(inbox);this.results=Objects.requireNonNull(results);
        this.secret=secret;this.clock=Objects.requireNonNull(clock);
        this.metrics=Objects.requireNonNull(metrics);
        this.replies=new ServiceCallSignatures(Map.of("inbox",secret),clock);
    }
    /** 挂载 64 片 HRW 后台分工（整局按 battle_id % 64，个人按 player_id % 64；截止自动放弃不看归属）。 */
    public BattleCompletionReplay withWorkShard(com.game.common.workshard.WorkShard.Membership membership) {
        this.workShard = membership;
        return this;
    }
    /** 挂载后台循环活性探针与每轮 Throwable 兜底。 */
    public BattleCompletionReplay withLiveness(com.game.common.workshard.LoopLiveness liveness) {
        this.liveness = liveness;
        return this;
    }
    /** 挂载 V7 16 桶高段排位观战索引：终局确认后即时摘除。 */
    public BattleCompletionReplay withRankedSpectateIndex(com.game.match.spectate.RankedSpectateIndex index) {
        this.rankedSpectateIndex = index;
        return this;
    }
    public void start(){
        worker.scheduleWithFixedDelay(this::roundSafely,0,500,TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::sampleBacklog,BACKLOG_SAMPLE_INTERVAL_SECONDS,BACKLOG_SAMPLE_INTERVAL_SECONDS,TimeUnit.SECONDS);
    }
    void roundSafely() {
        var live = this.liveness;
        Runnable step = () -> {
            try{round();}catch(RuntimeException failure){log.warn("战斗永久来源扫描暂不可读，保留游标，类别={}",failure.getClass().getSimpleName());}
        };
        if (live != null) {
            live.runGuarded(step);
        } else {
            try {
                step.run();
            } catch (Throwable failure) {
                log.error("战斗终局重投循环异常，保留游标并继续下一拍", failure);
            }
        }
    }
    /** 单次至多16截止+16全局+8首投+8等待APPLIED；各类固定上界轮转，异常也推进游标。 */
    public void round(){if(quiesced||!turn.tryLock())return;try{roundOwned();}finally{turn.unlock();}}
    private void roundOwned() {
        var deadlines=formations.terminalDeadlineCandidates(deadlineCursor,deadlineUpper);
        deadlineUpper=deadlines.upperBattle();
        if(deadlines.battles().isEmpty()){deadlineCursor=0;deadlineUpper=0;}
        for(long battle:deadlines.battles()) {
            try{formations.resolveTerminalAfterDeadline(battle);}
            catch(RuntimeException failure){log.warn("截止终局仍未知，保留原成局与票据，类别={}",failure.getClass().getSimpleName());}
            finally{deadlineCursor=battle;}
            if(Thread.currentThread().isInterrupted())return;
        }
        var membership = this.workShard;
        var resultFilter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                : membership.sqlFilter("match_terminal_result", "battle_id");
        var globals=resultFilter.isAll() ? store.pendingResults(resultCursor) : store.pendingResults(resultCursor, resultFilter);
        if(globals.isEmpty())resultCursor=0;
        for(var source:globals) {
            try{publish(source);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(Exception failure){log.warn("全局终局重投未确认，保留原件，类别={}",failure.getClass().getSimpleName());}
            finally{resultCursor=source.getBattleId();}
            if(Thread.currentThread().isInterrupted())return;
        }
        var playerFilter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                : membership.sqlFilter("match_terminal_player", "player_id");
        deliverPending(true,firstPlayers,playerFilter);
        if(Thread.currentThread().isInterrupted())return;
        deliverPending(false,waitingPlayers,playerFilter);
    }

    /** 采样个人结算首投（inbox_seq == 0）积压并写入两个 gauge；无积压写 0，读失败保留上一次读数并打 ERROR。 */
    public void sampleBacklog() {
        try {
            var backlog = store.firstDeliveryBacklog();
            metrics.outcomeOutboxBacklog(TerminalDeliveryKind.FIRST, backlog.rows(), backlog.oldestPendingAgeSeconds());
        } catch (RuntimeException failure) {
            log.error("[match] failed to sample first-delivery outbox backlog; keeping previous gauge reading: {}",
                    failure.getMessage(), failure);
        }
    }
    private static final class PlayerScanCursor { long battle,player,upper; }
    private void deliverPending(boolean first,PlayerScanCursor cursor,com.game.common.workshard.WorkShard.SqlFilter playerFilter) {
        var batch=playerFilter.isAll()
                ? store.pendingInbox(first,cursor.battle,cursor.player,cursor.upper)
                : store.pendingInbox(first,cursor.battle,cursor.player,cursor.upper,playerFilter);
        cursor.upper=batch.upperBattle();
        if(batch.sources().isEmpty()){cursor.battle=0;cursor.player=0;cursor.upper=0;}
        for(var source:batch.sources()) {
            try{deliver(source);}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(Exception failure){log.warn("个人终局未取得Inbox永久APPLIED，保留来源和ready票，类别={}",failure.getClass().getSimpleName());}
            finally{cursor.battle=source.getBattleId();cursor.player=source.getPlayerId();}
            if(Thread.currentThread().isInterrupted())return;
        }
    }
    private void publish(BattleCompletionRow source)throws Exception {
        var outcome = TerminalDeliveryOutcome.CORRUPT;
        try {
            if(source.getTerminalDecision()!=1||source.getOutcomeProtocolVersion()!=1||source.getSourceKind()!=2
                    ||!source.getOriginalDigest().equals(BattleCompletionStore.digest(source.getOriginal())))
                throw new SecurityException("全局终局持久原件无效");
            var event=BattleResultEvent.parseFrom(source.getOriginal());BattleCompletionStore.validateTerminal(source.getOriginal());
            if(event.getBattleId()!=source.getBattleId())throw new SecurityException("全局终局身份不同");
            outcome = TerminalDeliveryOutcome.FAILED;
            var acknowledgement=results.publish(event,source.getOriginal());
            if(acknowledgement==null)throw new IllegalStateException("全局结果发布没有确认句柄");
            acknowledgement.get(3250,TimeUnit.MILLISECONDS);
            outcome = TerminalDeliveryOutcome.STORE_ERROR;
            store.resultPublished(source);
            var rankedIdx = this.rankedSpectateIndex;
            if (rankedIdx != null) {
                rankedIdx.remove(source.getBattleId());
            }
            outcome = TerminalDeliveryOutcome.PUBLISHED;
        } finally {
            observeDelivery(TerminalDeliveryKind.RESULT, outcome);
        }
    }
    private void deliver(BattlePlayerCompletionRow source)throws Exception {
        boolean first=source.getInboxSeq()==0;
        var kind = first ? TerminalDeliveryKind.FIRST : TerminalDeliveryKind.WAITING;
        var outcome = TerminalDeliveryOutcome.STORE_ERROR;
        try {
            // 沿用原顺序：先在原 owner/行锁下领取退避，再校验原件和发 RPC。
            if (!store.claimInboxAttempt(source)) {
                outcome = TerminalDeliveryOutcome.CLAIM_LOST;
                return;
            }
            outcome = TerminalDeliveryOutcome.CORRUPT;
            if(source.getOwnerAccount().isBlank()||source.getOwnerRevision()==0||source.getIssuedAtMs()<=0
                    ||!source.getOriginalDigest().equals(BattleCompletionStore.digest(source.getOriginal())))
                throw new SecurityException("个人终局原身份或正文损坏");
            outcome = TerminalDeliveryOutcome.FAILED;
            String method=first?PlayerInboxService.BATTLE_ENQUEUE_METHOD:PlayerInboxService.BATTLE_LOOKUP_METHOD;
            var request=BattleSettlementInboxRequest.newBuilder().setPlayerId(source.getPlayerId()).setBattleId(source.getBattleId())
                    .setExpectedOwnerAccount(source.getOwnerAccount()).setExpectedOwnerRevision(source.getOwnerRevision())
                    .setIssuedAtMs(source.getIssuedAtMs()).setSettlementPayload(source.getOriginal()).build();
            var signed=ServiceCallSignatures.sign(method,request,"match",secret,clock.getAsLong());
            var pending=first?inbox.enqueueBattleSettlement(signed):inbox.lookupBattleSettlement(signed);
            if(pending==null)throw new IllegalStateException("Inbox未返回确认句柄");
            var response=pending.get(3250,TimeUnit.MILLISECONDS);
            verifyReceipt(method,request,response,source.getInboxSeq());
            boolean applied = response.getState()==PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED;
            outcome = TerminalDeliveryOutcome.STORE_ERROR;
            store.inboxReceipt(source,response.getSeq(),applied);
            outcome = applied ? TerminalDeliveryOutcome.APPLIED : TerminalDeliveryOutcome.PENDING;
        } finally {
            observeDelivery(kind, outcome);
        }
    }

    /** 指标注册或记录故障不能覆盖原投递结局、改变中断处理，或阻断后续来源。 */
    private void observeDelivery(TerminalDeliveryKind kind, TerminalDeliveryOutcome outcome) {
        try {
            metrics.terminalDelivery(kind, outcome);
        } catch (RuntimeException failure) {
            log.warn("终局投递指标记录失败，类别={}", failure.getClass().getSimpleName());
        }
    }
    /** 回复必须绑定本次方法、原全文摘要、永久seq和内容摘要，单独passed或seq非零不授予到账。 */
    public void verifyReceipt(String method,BattleSettlementInboxRequest request,BattleSettlementInboxResponse response,long knownSeq) {
        if(response==null||!"inbox".equals(replies.verify(method+"/reply",response))
                ||!response.getRequestDigest().equals(ServiceCallSignatures.digest(request))
                ||response.getErrorMessage().getId()!=0||response.getPlayerId()!=request.getPlayerId()
                ||response.getBattleId()!=request.getBattleId()||response.getSeq()==0||knownSeq!=0&&knownSeq!=response.getSeq()
                ||!MessageDigest.isEqual(contentDigest(request),response.getContentSha256().toByteArray())
                ||response.getState()!=PlayerInboxItemState.PLAYER_INBOX_ITEM_PENDING
                    &&response.getState()!=PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED)
            throw new SecurityException("Inbox原件收据未知、终态非法或身份不符");
    }
    private static byte[] contentDigest(BattleSettlementInboxRequest request) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(12).putInt(PlayerInboxMessageType.PLAYER_INBOX_MSG_BATTLE_SETTLEMENT_VALUE)
                    .putLong(request.getIssuedAtMs()).array());
            return digest.digest(request.getSettlementPayload().toByteArray());
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public boolean stopAndReport(long timeoutNanos)throws InterruptedException {
        long until=System.nanoTime()+Math.max(0,timeoutNanos);
        if(!turn.tryLock(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS))return false;
        try{if(store.maintenanceOutstanding()!=0||formations.maintenanceOutstanding()!=0)return false;quiesced=true;worker.shutdown();}
        finally{turn.unlock();}
        return worker.awaitTermination(Math.max(0,until-System.nanoTime()),TimeUnit.NANOSECONDS);
    }
    @Override public void close() {
        var live = this.liveness;
        if (live != null) {
            live.close();
        }
        worker.shutdownNow();
        try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))log.error("战斗终局恢复线程尚未退出，不能假定投递已终结");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
