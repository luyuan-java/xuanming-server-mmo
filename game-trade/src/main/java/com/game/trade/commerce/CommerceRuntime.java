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
import com.game.trade.store.pb.TradeCommerceRow;
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
    // 仅由驱动 tick 的单个工作线程持有；尝试后前移、到表尾再绕回，不让离线玩家占满首页。
    private long ackAfterOpId;
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
    /** 只由本工作线程或未启动 worker 的测试串行调用；ACK 游标不允许并发驱动。 */
    public void tick(Deadline budget) throws SQLException {
        long now = e.clock.getAsLong();
        for (var candidate :
            e.store.ops("status=1 AND next_attempt_ms<=? AND lease_until_ms<=? ORDER BY op_id LIMIT 50", now, now)) {
            if (budget.remainingMillis() < 500)
                break;
            TradeAssetOpRow op = candidate;
            try {
                op = claim(candidate.getOpId(), budget);
                if (op != null)
                    send(op, Deadline.after(Math.min(5000, budget.remainingMillis())));
            } catch (SQLException | RuntimeException failure) {
                if (budget.expired() || Thread.currentThread().isInterrupted())
                    break;
                log.error("交易资产单行投递失败，保留原seq op_id={}", Long.toUnsignedString(candidate.getOpId()), failure);
                if (op != null)
                    try {
                        reschedule(op, backoff(op), budget);
                    } catch (SQLException | RuntimeException deferFailure) {
                        log.error("交易资产单行推迟失败 op_id={}", Long.toUnsignedString(op.getOpId()), deferFailure);
                    }
            }
        }
        for (var row : e.store.due(e.clock.getAsLong(), 100)) {
            if (budget.remainingMillis() < 500)
                break;
            try {
                step(row, Deadline.after(Math.min(5000, budget.remainingMillis())));
            } catch (SQLException | RuntimeException failure) {
                if (budget.expired() || Thread.currentThread().isInterrupted())
                    break;
                deferContract(row, failure, budget);
            }
        }
        var acks = e.store.ackDue(ackAfterOpId, 50);
        for (var op : acks) {
            if (budget.remainingMillis() < 300)
                return;
            try {
                ack(op, budget);
            } catch (SQLException failure) {
                log.error("交易收据ACK落库失败，原待办保留 op_id={}", Long.toUnsignedString(op.getOpId()), failure);
            }
            ackAfterOpId = op.getOpId();
        }
        if (acks.size() < 50)
            ackAfterOpId = 0;
        if (mall != null && budget.remainingMillis() > 300)
            try {
                mall.tick(budget);
            } catch (Exception unknown) {
                if (unknown instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                log.error("商城交付恢复失败，原订单与资产seq保留", unknown);
            }
    }
    private void step(TradeCommerceRow row, Deadline budget) throws SQLException {
        var w = CommerceEngine.workflow(row);
        if (w.getStage().equals("bid_ready")) {
            matching.acceptBid(row, budget);
            return;
        }
        if (w.getStage().equals("bid_held")) {
            settleHeldBid(row, budget);
            return;
        }
        e.advance(row, budget);
        if (row.getKind() == 4 && row.getSide() != 0)
            matching.match(row, budget);
    }
    private void settleHeldBid(TradeCommerceRow row, Deadline budget) throws SQLException {
        var parent = e.store.get(row.getParentId());
        if (parent == null)
            throw new CommerceEngine.CorruptContract("持有竞价的拍品不存在");
        if (parent.getStatus() != SETTLED) {
            long now = e.clock.getAsLong();
            e.store.defer(row, Math.min(Math.max(parent.getExpiresMs(), now + 30000), now + 3600000), budget);
            return;
        }
        e.store.transaction(budget, c -> {
            var current = e.store.lock(c, row.getCommerceId());
            if (current == null)
                return null;
            var w = CommerceEngine.workflow(current);
            if (!w.getStage().equals("bid_held"))
                return null;
            w.setStage("done");
            e.store.markConsumed(c, current.getCommerceId());
            e.store.save(c, current.toBuilder().setStatus(SETTLED)
                                .setWorkflowProto(w.build().toByteString()).setUpdatedMs(e.clock.getAsLong()));
            return null;
        });
    }
    private void deferContract(TradeCommerceRow row, Exception failure, Deadline budget) {
        String category = failure instanceof CommerceEngine.CorruptContract ? "corrupt"
            : failure instanceof CommerceStore.PendingBackpressure ? "too_many_pending" : "retry";
        if (category.equals("corrupt"))
            try {
                if (e.store.quarantine(row, budget)) {
                    log.error("交易合同隔离为人工 class={} commerce_id={} version={}", category,
                        Long.toUnsignedString(row.getCommerceId()), row.getVersion(), failure);
                    return;
                }
            } catch (SQLException | RuntimeException quarantineFailure) {
                log.error("交易合同隔离失败 commerce_id={}", Long.toUnsignedString(row.getCommerceId()), quarantineFailure);
            }
        long base = category.equals("too_many_pending") ? 60000 : 10000;
        long until = e.clock.getAsLong() + (long) (base * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
        try {
            boolean deferred = e.store.defer(row, until, budget);
            if (category.equals("too_many_pending"))
                log.info("交易合同背压推迟 class={} commerce_id={} deferred={} until_ms={}", category,
                    Long.toUnsignedString(row.getCommerceId()), deferred, until);
            else
                log.error("交易合同单行失败 class={} commerce_id={} deferred={} until_ms={}", category,
                    Long.toUnsignedString(row.getCommerceId()), deferred, until, failure);
        } catch (SQLException | RuntimeException deferFailure) {
            failure.addSuppressed(deferFailure);
            log.error("交易合同推迟失败 class={} commerce_id={}", category, Long.toUnsignedString(row.getCommerceId()), failure);
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
            var answer = call.await(delivery.deliver(rpc, request, call), "交易资产投递");
            if (!answer.answered())
                log.warn("交易资产RPC依赖失败，继续查原seq的SQL结局 op_id={}", Long.toUnsignedString(op.getOpId()), answer.error());
        } catch (RuntimeException unknown) {
            if (Thread.currentThread().isInterrupted())
                return;
            log.warn("交易资产RPC未知，继续查原seq的SQL结局 op_id={}", Long.toUnsignedString(op.getOpId()), unknown);
        }
        // SQL为终结证据；即使RPC丢包、离线或误报durable，也只据同事务存档确认。
        var read = ledger.read(op.getPlayerId());
        if (read instanceof PersistedAssetLedgerReader.Read.Failed failed)
            log.warn("交易资产SQL结局读取失败，保留原seq op_id={} reason={}",
                Long.toUnsignedString(op.getOpId()), failed.reason(), failed.cause());
        if (!(read instanceof PersistedAssetLedgerReader.Read.Loaded loaded)) {
            reschedule(op, backoff(op), budget);
            return;
        }
        var proof = loaded.ledger();
        var outcome = proof.outcomeOf(op.getStream(), op.getStreamEpoch(), op.getSeq());
        if (!outcome.conclusive() || !proof.identityMatches(op.getStream(), op.getStreamEpoch(), op.getSeq(),
                op.getOpId(), AssetOpSignatures.requestDigest(request))) {
            reschedule(op, backoff(op), budget);
            return;
        }
        var receipt = proof.receipt(op.getStream(), op.getStreamEpoch(), op.getSeq());
        if (op.getStream() == 3 && outcome.state() == AssetSeqState.APPLIED
            && (request.getBundle().getItemUuidsCount() > 0 || request.getBundle().getPetId() != 0)
            && (receipt == null || receipt.getSnapshotProto().isEmpty())) {
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
                receipt != null && !receipt.getSnapshotProto().isEmpty() && status == 2, now, now, op.getOpId());
            return null;
        });
    }
    private void ack(TradeAssetOpRow op, Deadline budget) throws SQLException {
        try {
            var req = request(op);
            var before = ledger.read(op.getPlayerId());
            if (before instanceof PersistedAssetLedgerReader.Read.Loaded loaded
                && loaded.ledger().classify(op.getStream(), op.getStreamEpoch(), op.getSeq()) == AssetSeqState.BEHIND_WINDOW
                && op.getStatus() == 2 && op.getDurable() && !op.getEscrowSnapshot().isEmpty()) {
                // 原业务已提交并持有完整快照；这里仅清理释放待办，不以滑出窗口证明任何新业务结局。
                e.store.transaction(budget, c -> {
                    CommerceStore.execute(c,
                        "UPDATE trade_asset_op SET receipt_ack_pending=0 WHERE op_id=? AND status=2 AND durable=1",
                        op.getOpId());
                    return null;
                });
                return;
            }
            Deadline call = Deadline.after(Math.min(1500, budget.remainingMillis() - 50));
            var answer = call.await(delivery.deliver(AssetRpc.ACK_RECEIPT, req, call), "交易收据释放");
            if (!answer.answered()) {
                log.warn("交易收据ACK依赖失败，原待办保留 op_id={}", Long.toUnsignedString(op.getOpId()), answer.error());
                return;
            }
            if (answer.response().getOutcome() != AssetOutcome.ASSET_OUTCOME_APPLIED)
                return;
            var read = ledger.read(op.getPlayerId());
            if (read instanceof PersistedAssetLedgerReader.Read.Failed failed) {
                log.warn("交易收据ACK的SQL确认失败，原待办保留 op_id={}", Long.toUnsignedString(op.getOpId()), failed.cause());
                return;
            }
            if (!(read instanceof PersistedAssetLedgerReader.Read.Loaded loaded)
                || loaded.ledger().classify(op.getStream(), op.getStreamEpoch(), op.getSeq()) != AssetSeqState.APPLIED
                || !loaded.ledger().identityMatches(op.getStream(), op.getStreamEpoch(), op.getSeq(), op.getOpId(),
                    AssetOpSignatures.requestDigest(req))
                || !loaded.ledger().receipt(op.getStream(), op.getStreamEpoch(), op.getSeq()).getSnapshotProto().isEmpty())
                return;
            e.store.transaction(budget, c -> {
                CommerceStore.execute(c,
                    "UPDATE trade_asset_op SET receipt_ack_pending=0 WHERE op_id=? AND status=2 AND durable=1",
                    op.getOpId());
                return null;
            });
        } catch (InvalidProtocolBufferException | RuntimeException unknown) {
            // 同一ACK待办保留并越过本行；下一轮绕回重试，坏载荷不能挡住其它玩家。
            log.warn("交易收据ACK未完成 op_id={}", Long.toUnsignedString(op.getOpId()), unknown);
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
