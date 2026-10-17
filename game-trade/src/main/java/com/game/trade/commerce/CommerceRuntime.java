package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.AssetSeqState;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 有界恢复循环；关闭时先取消并等工作线程，再由Spring关闭RPC客户端/计时器/数据源。 */
public final class CommerceRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CommerceRuntime.class);
    private final CommerceEngine e;
    private final CommerceMatching matching;
    private final ReliableAssetDelivery delivery;
    private final PersistedAssetLedgerReader ledger;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("trade-commerce-recovery").factory());
    private final AtomicBoolean started = new AtomicBoolean();
    private final OfficialMallClient mall;
    public CommerceRuntime(CommerceEngine e, ReliableAssetDelivery delivery, PersistedAssetLedgerReader ledger) {
        this(e, delivery, ledger, null);
    }
    public CommerceRuntime(
        CommerceEngine e, ReliableAssetDelivery delivery, PersistedAssetLedgerReader ledger, OfficialMallClient mall) {
        this.e = e;
        this.matching = new CommerceMatching(e);
        this.delivery = delivery;
        this.ledger = ledger;
        this.mall = mall;
    }
    public void start() {
        if (started.compareAndSet(false, true))
            worker.scheduleWithFixedDelay(() -> {
                try {
                    tick(Deadline.after(10000));
                } catch (Exception failure) {
                    if (!Thread.currentThread().isInterrupted())
                        log.error("交易合同/资产恢复失败", failure);
                }
            }, 0, 1, TimeUnit.SECONDS);
    }
    static AssetOpRequest request(TradeAssetOpRow op) throws InvalidProtocolBufferException {
        return AssetOpRequest.newBuilder()
            .setPlayerId(op.getPlayerId())
            .setStreamValue(op.getStream())
            .setStreamEpoch(op.getStreamEpoch())
            .setSeq(op.getSeq())
            .setCorrelationId(op.getOpId())
            .setTxType(op.getTxType())
            .setBundle(AssetBundle.parseFrom(op.getPayload()))
            .build();
    }
    public void tick(Deadline budget) throws SQLException {
        long now = e.clock.getAsLong();
        for (var candidate :
            e.store.ops("status=1 AND next_attempt_ms<=? AND lease_until_ms<=? ORDER BY op_id LIMIT 50", now, now)) {
            if (budget.remainingMillis() < 500)
                break;
            var op = claim(candidate.getOpId(), budget);
            if (op == null)
                continue;
            send(op, budget);
        }
        for (var row : e.store.list("status IN (1,3,4,6,8) ORDER BY updated_ms,commerce_id LIMIT 100")) {
            if (budget.remainingMillis() < 500)
                break;
            var w = CommerceEngine.workflow(row);
            if (w.getStage().equals("bid_ready")) {
                matching.acceptBid(row, budget);
                continue;
            }
            if (w.getStage().equals("bid_held")) {
                var parent = e.store.get(row.getParentId());
                if (parent != null && parent.getStatus() == SETTLED)
                    e.store.transaction(budget, c -> {
                        var r = e.store.lock(c, row.getCommerceId());
                        var cw = CommerceEngine.workflow(r);
                        if (cw.getStage().equals("bid_held")) {
                            cw.setStage("done");
                            e.store.save(c,
                                r.toBuilder()
                                    .setStatus(SETTLED)
                                    .setWorkflowProto(cw.build().toByteString())
                                    .setUpdatedMs(e.clock.getAsLong()));
                            CommerceStore.execute(c,
                                "UPDATE trade_asset_op SET business_consumed=1 WHERE ref_kind=2 AND ref_id=?",
                                r.getCommerceId());
                        }
                        return null;
                    });
                continue;
            }
            e.advance(row, budget);
            if (row.getKind() == 4 && row.getSide() != 0)
                matching.match(row, budget);
        }
        for (var op : e.store.ops("receipt_ack_pending=1 AND status=2 AND durable=1 ORDER BY op_id LIMIT 50")) {
            if (budget.remainingMillis() < 300)
                break;
            ack(op, budget);
        }
        if (mall != null && budget.remainingMillis() > 300)
            try {
                mall.tick(budget);
            } catch (Exception unknown) {
                if (unknown instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                log.error("商城交付恢复失败，原订单与资产seq保留", unknown);
            }
    }
    private TradeAssetOpRow claim(long id, Deadline d) throws SQLException {
        return e.store.transaction(d, c -> {
            var rows = e.store.ops(c, "op_id=? FOR UPDATE", id);
            if (rows.isEmpty())
                return null;
            var op = rows.getFirst();
            long now = e.clock.getAsLong();
            if (op.getStatus() != 1 || op.getLeaseUntilMs() > now || op.getNextAttemptMs() > now)
                return null;
            long token;
            do {
                token = ThreadLocalRandom.current().nextLong();
            } while (token == 0);
            CommerceStore.execute(c,
                "UPDATE trade_asset_op SET "
                    + "lease_token=?,lease_until_ms=?,attempts=attempts+1,last_send_ms=?,updated_ms=? WHERE op_id=? "
                    + "AND "
                    + "status=1",
                token, now + 10000, now, now, id);
            return op.toBuilder()
                .setLeaseToken(token)
                .setLeaseUntilMs(now + 10000)
                .setAttempts(op.getAttempts() + 1)
                .setLastSendMs(now)
                .build();
        });
    }
    private void send(TradeAssetOpRow op, Deadline budget) throws SQLException {
        AssetOpRequest request;
        try {
            request = request(op);
        } catch (InvalidProtocolBufferException corrupted) {
            log.error("交易资产待办损坏，需要人工处理op={}", Long.toUnsignedString(op.getOpId()));
            reschedule(op, 3600000, budget);
            return;
        }
        AssetRpc rpc = op.getDeadlineMs() != 0 && op.getDeadlineMs() <= e.clock.getAsLong() ? AssetRpc.ABORT_DEBIT
            : op.getStream() == 3                                                           ? AssetRpc.DEBIT
                                                                                            : AssetRpc.CREDIT;
        try {
            Deadline call = Deadline.after(Math.min(2500, budget.remainingMillis() - 100));
            call.await(delivery.deliver(rpc, request, call), "交易资产投递");
        } catch (RuntimeException unknown) {
            if (Thread.currentThread().isInterrupted())
                return;
        }
        // SQL为终结证据；即使RPC丢包、离线或误报durable，也只据同事务存档确认。
        var read = ledger.read(op.getPlayerId());
        if (!(read instanceof PersistedAssetLedgerReader.Read.Loaded loaded)) {
            reschedule(op, backoff(op), budget);
            return;
        }
        var proof = loaded.ledger();
        var outcome = proof.outcomeOf(op.getStream(), op.getStreamEpoch(), op.getSeq());
        if (!outcome.conclusive()) {
            reschedule(op, backoff(op), budget);
            return;
        }
        var receipt = proof.receipt(op.getStream(), op.getStreamEpoch(), op.getSeq());
        if (op.getStream() == 3 && outcome.state() == AssetSeqState.APPLIED
            && (request.getBundle().getItemUuidsCount() > 0 || request.getBundle().getPetId() != 0)
            && receipt == null) {
            reschedule(op, backoff(op), budget);
            return;
        }
        if (receipt != null
            && (receipt.getCorrelationId() != op.getOpId()
                || !receipt.getRequestDigest().equals(AssetOpSignatures.requestDigest(request)))) {
            reschedule(op, 60000, budget);
            return;
        }
        int status = outcome.state() == AssetSeqState.APPLIED ? (outcome.partial() ? 5 : 2)
            : op.getDeadlineMs() != 0 && rpc == AssetRpc.ABORT_DEBIT && outcome.rejectionReason() == 0 ? 4
                                                                                                       : 3;
        var snapshot = receipt == null ? com.google.protobuf.ByteString.EMPTY : receipt.getSnapshotProto();
        long now = e.clock.getAsLong();
        e.store.transaction(budget, c -> {
            var rows = e.store.ops(c, "op_id=? FOR UPDATE", op.getOpId());
            if (rows.isEmpty() || rows.getFirst().getStatus() != 1)
                return null;
            CommerceStore.execute(c,
                "UPDATE trade_asset_op SET "
                    + "status=?,durable=1,last_outcome=?,last_reason=?,escrow_snapshot=?,receipt_ack_pending=?,lease_"
                    + "until_"
                    + "ms=0,next_attempt_ms=?,updated_ms=? WHERE op_id=? AND status=1",
                status, outcome.state() == AssetSeqState.APPLIED ? 1 : 2, outcome.rejectionReason(), snapshot,
                receipt != null && status == 2, now, now, op.getOpId());
            return null;
        });
    }
    private void ack(TradeAssetOpRow op, Deadline budget) throws SQLException {
        try {
            var req = request(op);
            Deadline call = Deadline.after(Math.min(1500, budget.remainingMillis() - 50));
            var answer = call.await(delivery.deliver(AssetRpc.ACK_RECEIPT, req, call), "交易收据释放");
            if (!answer.answered() || answer.response().getOutcome() != AssetOutcome.ASSET_OUTCOME_APPLIED)
                return;
            var read = ledger.read(op.getPlayerId());
            if (!(read instanceof PersistedAssetLedgerReader.Read.Loaded loaded)
                || loaded.ledger().classify(op.getStream(), op.getStreamEpoch(), op.getSeq()) != AssetSeqState.APPLIED
                || loaded.ledger().receipt(op.getStream(), op.getStreamEpoch(), op.getSeq()) != null)
                return;
            e.store.transaction(budget, c -> {
                CommerceStore.execute(c,
                    "UPDATE trade_asset_op SET receipt_ack_pending=0 WHERE op_id=? AND status=2 AND durable=1",
                    op.getOpId());
                return null;
            });
        } catch (
            InvalidProtocolBufferException | RuntimeException unknown) { /*同一ACK待办保留，禁止因未知结果伪造完成*/
        }
    }
    private static long backoff(TradeAssetOpRow op) {
        return Math.min(60000, 1000L << Math.min(6, op.getAttempts()));
    }
    private void reschedule(TradeAssetOpRow op, long delay, Deadline d) throws SQLException {
        if (d.expired() || Thread.currentThread().isInterrupted())
            return;
        long now = e.clock.getAsLong();
        e.store.transaction(d, c -> {
            CommerceStore.execute(c,
                "UPDATE trade_asset_op SET lease_until_ms=0,next_attempt_ms=?,updated_ms=? WHERE op_id=? AND status=1 "
                    + "AND lease_token=?",
                now + delay, now, op.getOpId(), op.getLeaseToken());
            return null;
        });
    }
    @Override
    public void close() {
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS))
                throw new IllegalStateException("交易恢复线程未排空");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("交易恢复关闭被中断", e);
        }
    }
}
