package com.game.match.rating;

import com.game.api.PlayerInboxService;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.match.completion.pb.BattleCompletionRow;
import com.game.match.global.GlobalMatchStore;
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
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("battle-terminal-replay").daemon(true).factory());
    private long deadlineCursor,deadlineUpper,resultCursor,playerBattleCursor,playerCursor;
    public BattleCompletionReplay(BattleCompletionStore store,GlobalMatchStore formations,PlayerInboxService inbox,Results results,String secret,LongSupplier clock) {
        this.store=Objects.requireNonNull(store);this.formations=Objects.requireNonNull(formations);
        this.inbox=Objects.requireNonNull(inbox);this.results=Objects.requireNonNull(results);
        this.secret=secret;this.clock=Objects.requireNonNull(clock);
        this.replies=new ServiceCallSignatures(Map.of("inbox",secret),clock);
    }
    public void start(){worker.scheduleWithFixedDelay(this::roundSafely,0,500,TimeUnit.MILLISECONDS);}
    private void roundSafely() {
        try{round();}catch(RuntimeException failure){log.warn("战斗永久来源扫描暂不可读，保留游标，类别={}",failure.getClass().getSimpleName());}
    }
    /** 单次至多16截止+16全局+16个人；每条错误仍推进本轮游标，下一轮回头恢复，禁止队首页饥饿。 */
    public synchronized void round() {
        var deadlines=formations.terminalDeadlineCandidates(deadlineCursor,deadlineUpper);
        deadlineUpper=deadlines.upperBattle();
        if(deadlines.battles().isEmpty()){deadlineCursor=0;deadlineUpper=0;}
        for(long battle:deadlines.battles()) {
            try{formations.resolveTerminalAfterDeadline(battle);}
            catch(RuntimeException failure){log.warn("截止终局仍未知，保留原成局与票据，类别={}",failure.getClass().getSimpleName());}
            finally{deadlineCursor=battle;}
            if(Thread.currentThread().isInterrupted())return;
        }
        var globals=store.pendingResults(resultCursor);
        if(globals.isEmpty())resultCursor=0;
        for(var source:globals) {
            try{publish(source);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(Exception failure){log.warn("全局终局重投未确认，保留原件，类别={}",failure.getClass().getSimpleName());}
            finally{resultCursor=source.getBattleId();}
            if(Thread.currentThread().isInterrupted())return;
        }
        var people=store.pendingPlayers(playerBattleCursor,playerCursor);
        if(people.isEmpty()){playerBattleCursor=0;playerCursor=0;}
        for(var source:people) {
            try{deliver(source);}catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
            catch(Exception failure){log.warn("个人终局未取得Inbox永久APPLIED，保留来源和ready票，类别={}",failure.getClass().getSimpleName());}
            finally{playerBattleCursor=source.getBattleId();playerCursor=source.getPlayerId();}
            if(Thread.currentThread().isInterrupted())return;
        }
    }
    private void publish(BattleCompletionRow source)throws Exception {
        if(source.getTerminalDecision()!=1||source.getOutcomeProtocolVersion()!=1||source.getSourceKind()!=2
                ||!source.getOriginalDigest().equals(BattleCompletionStore.digest(source.getOriginal())))
            throw new SecurityException("全局终局持久原件无效");
        var event=BattleResultEvent.parseFrom(source.getOriginal());BattleCompletionStore.validateTerminal(source.getOriginal());
        if(event.getBattleId()!=source.getBattleId())throw new SecurityException("全局终局身份不同");
        var acknowledgement=results.publish(event,source.getOriginal());
        if(acknowledgement==null)throw new IllegalStateException("全局结果发布没有确认句柄");
        acknowledgement.get(3250,TimeUnit.MILLISECONDS);store.resultPublished(source);
    }
    private void deliver(BattlePlayerCompletionRow source)throws Exception {
        if(source.getOwnerAccount().isBlank()||source.getOwnerRevision()==0||source.getIssuedAtMs()<=0
                ||!source.getOriginalDigest().equals(BattleCompletionStore.digest(source.getOriginal())))
            throw new SecurityException("个人终局原身份或正文损坏");
        boolean first=source.getInboxSeq()==0;
        String method=first?PlayerInboxService.BATTLE_ENQUEUE_METHOD:PlayerInboxService.BATTLE_LOOKUP_METHOD;
        var request=BattleSettlementInboxRequest.newBuilder().setPlayerId(source.getPlayerId()).setBattleId(source.getBattleId())
                .setExpectedOwnerAccount(source.getOwnerAccount()).setExpectedOwnerRevision(source.getOwnerRevision())
                .setIssuedAtMs(source.getIssuedAtMs()).setSettlementPayload(source.getOriginal()).build();
        var signed=ServiceCallSignatures.sign(method,request,"match",secret,clock.getAsLong());
        var pending=first?inbox.enqueueBattleSettlement(signed):inbox.lookupBattleSettlement(signed);
        if(pending==null)throw new IllegalStateException("Inbox未返回确认句柄");
        var response=pending.get(3250,TimeUnit.MILLISECONDS);
        verifyReceipt(method,request,response,source.getInboxSeq());
        store.inboxReceipt(source,response.getSeq(),response.getState()==PlayerInboxItemState.PLAYER_INBOX_ITEM_APPLIED);
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
    @Override public void close() {
        worker.shutdownNow();
        try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))log.error("战斗终局恢复线程尚未退出，不能假定投递已终结");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
