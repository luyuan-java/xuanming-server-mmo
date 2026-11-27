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
    /** 2026-10-22 评审修正（L947）：单行推进预算必须从整轮截止推出并预留推迟与 ACK 余量。 */
    static final long RESERVE_ACK_MS = 500L;
    static final long RESERVE_DEFER_MS = 300L;
    static final long MIN_STEP_BUDGET_MS = 200L;
    static final long MAX_STEP_BUDGET_MS = 5_000L;

    /** 按整轮剩余预算计算单行合同推进子预算（预留 ACK 500ms + 推迟 300ms、取剩余一半、上限 5s、不足 200ms 不开新行）。 */
    static long stepBudgetMs(long remainingMillis) {
        long available = remainingMillis - RESERVE_ACK_MS - RESERVE_DEFER_MS;
        if (available < MIN_STEP_BUDGET_MS) {
            return 0L;
        }
        return Math.min(MAX_STEP_BUDGET_MS, Math.max(MIN_STEP_BUDGET_MS, available / 2L));
    }

    /** 将合同推进异常归为五类（conflict / role_conflict / owner_changed / corrupt / too_many_pending / retry）。 */
    static String classifyContractFailure(TradeCommerceRow row, Throwable failure) {
        if (failure instanceof CommerceEngine.OwnerChangedContract) {
            return row.getKind() == 6 ? "retry" : "owner_changed";
        }
        if (failure instanceof CommerceEngine.CorruptContract) {
            return "corrupt";
        }
        if (failure instanceof CommerceStore.PendingBackpressure) {
            return "too_many_pending";
        }
        if (failure instanceof CommerceRules.Refusal ref && ref.tip() == CommerceRules.CONFLICT) {
            return "role_conflict";
        }
        if (failure instanceof java.util.ConcurrentModificationException
                || (failure instanceof SQLException sqlEx && "40001".equals(sqlEx.getSQLState()))) {
            return "conflict";
        }
        return "retry";
    }
    private final CommerceEngine e;
    private final CommerceMatching matching;
    private final ReliableAssetDelivery delivery;
    private final PersistedAssetLedgerReader ledger;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("trade-commerce-recovery").factory());
    private final AtomicBoolean started = new AtomicBoolean();
    private final OfficialMallClient mall;
    private final RmbConsignmentService rmb;
    private volatile com.game.common.workshard.WorkShard.Membership shardMembership;
    private volatile com.game.common.workshard.LoopLiveness liveness;
    // 仅由驱动 tick 的单个工作线程持有；尝试后前移、到表尾再绕回，不让离线玩家占满首页。
    private long ackAfterOpId;
    public CommerceRuntime(CommerceEngine e, ReliableAssetDelivery delivery, PersistedAssetLedgerReader ledger) {
        this(e, delivery, ledger, null);
    }
    public CommerceRuntime(
        CommerceEngine e, ReliableAssetDelivery delivery, PersistedAssetLedgerReader ledger, OfficialMallClient mall) {
        this(e, delivery, ledger, mall, null);
    }
    public CommerceRuntime(CommerceEngine e, ReliableAssetDelivery delivery, PersistedAssetLedgerReader ledger,
                           OfficialMallClient mall, RmbConsignmentService rmb) {
        this.e = e;
        this.matching = new CommerceMatching(e);
        this.delivery = delivery;
        this.ledger = ledger;
        this.mall = mall;
        this.rmb = rmb;
    }

    /** 挂载 64 片 HRW 后台分工成员表（未挂载或成员表为空时本副本承担全部 64 片）。 */
    public CommerceRuntime withShardMembership(com.game.common.workshard.WorkShard.Membership membership) {
        this.shardMembership = membership;
        return this;
    }

    /** 挂载后台循环活性探针与每轮 Throwable 兜底。 */
    public CommerceRuntime withLiveness(com.game.common.workshard.LoopLiveness liveness) {
        this.liveness = liveness;
        return this;
    }
    public void start() {
        if (started.compareAndSet(false, true)) {
            worker.scheduleWithFixedDelay(this::runScheduledRound, 0, 1, TimeUnit.SECONDS);
        }
    }

    void runScheduledRound() {
        var live = this.liveness;
        Runnable step = () -> {
            try {
                tick(Deadline.after(10000));
            } catch (Exception failure) {
                if (!Thread.currentThread().isInterrupted())
                    log.error("交易合同/资产恢复失败", failure);
            }
        };
        if (live != null) {
            live.runGuarded(step);
        } else {
            try {
                step.run();
            } catch (Throwable failure) {
                log.error("交易恢复循环未捕获异常，本轮停留在未知结局并继续下一拍", failure);
            }
        }
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
        if (rmb != null && budget.remainingMillis() > 500) {
            try { rmb.poll(Deadline.after(Math.min(2500, budget.remainingMillis()))); }
            catch (Exception unknown) {
                if (unknown instanceof InterruptedException) Thread.currentThread().interrupt();
                log.error("人民币寄售扫描失败，原游标与合同保留", unknown);
            }
        }
        // 扫描预算先于离线资产重试分配；首页慢玩家不能耗尽后页订单的准入机会。
        if (mall != null && budget.remainingMillis() > 500)
            try {
                mall.tick(Deadline.after(Math.min(2500, budget.remainingMillis())));
            } catch (Exception unknown) {
                if (unknown instanceof InterruptedException)
                    Thread.currentThread().interrupt();
                log.error("商城交付恢复失败，原订单与资产seq保留", unknown);
            }
        var membership = this.shardMembership;
        var opFilter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                : membership.sqlFilter("trade_op", "player_id");
        var commerceFilter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                : membership.sqlFilter("trade_commerce", "commerce_id");
        var ackFilter = membership == null ? com.game.common.workshard.WorkShard.SqlFilter.ALL
                : membership.sqlFilter("trade_ack", "op_id");
        long now = e.clock.getAsLong();
        var pendingOps = opFilter.isAll()
                ? e.store.ops("status=1 AND next_attempt_ms<=? AND lease_until_ms<=? ORDER BY op_id LIMIT ?", now, now, 50)
                : e.store.pendingOps(now, 50, opFilter);
        for (var candidate : pendingOps) {
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
        var dueRows = commerceFilter.isAll()
                ? e.store.due(e.clock.getAsLong(), 100)
                : e.store.due(e.clock.getAsLong(), 100, commerceFilter);
        for (var row : dueRows) {
            long rowBudgetMs = stepBudgetMs(budget.remainingMillis());
            if (rowBudgetMs < MIN_STEP_BUDGET_MS) {
                break;
            }
            try {
                step(row, Deadline.after(rowBudgetMs));
            } catch (SQLException | RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                // 即使单行子预算耗尽，只要整轮仍留有预留余量就必须执行分类/推迟，防止卡住的队头行反复饥饿后续合同与 ACK。
                long deferBudgetMs = Math.min(RESERVE_DEFER_MS, Math.max(100L, budget.remainingMillis()));
                deferContract(row, failure, Deadline.after(deferBudgetMs));
            }
        }
        var acks = ackFilter.isAll()
                ? e.store.ackDue(ackAfterOpId, 50)
                : e.store.ackDue(ackAfterOpId, 50, ackFilter);
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
    }
    private void step(TradeCommerceRow row, Deadline budget) throws SQLException {
        if (row.getKind() == 6) {
            if (rmb == null) throw new SQLException("人民币寄售恢复未装配");
            try { rmb.advance(row, budget); }
            catch (SQLException failure) { throw failure; }
            catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new SQLException("人民币寄售原合同保留，等待恢复", failure);
            }
            return;
        }
        var w = CommerceEngine.workflow(row);
        if (w.getStage().equals("bid_ready")) {
            matching.acceptBid(row, budget);
            return;
        }
        if (w.getStage().equals("bid_held")) {
            settleHeldBid(row, budget);
            return;
        }
        TradeCommerceRow advanced = e.advance(row, budget);
        TradeCommerceRow candidate = advanced != null ? advanced : row;
        if (candidate.getKind() == 4 && candidate.getSide() != 0 && candidate.getOnOrderBook()) {
            try {
                matching.match(candidate, budget);
            } catch (SQLException matchFailure) {
                // 撮合失败（锁竞争/瞬态冲突）时把 taker 调度键拉回下一轮，避免卡在 1 小时稳态窗口里
                try {
                    e.store.wake(candidate.getCommerceId(), e.clock.getAsLong(),
                            Deadline.after(Math.min(1000, Math.max(200, budget.remainingMillis()))));
                } catch (SQLException | RuntimeException ignored) {
                }
                throw matchFailure;
            }
        }
    }
    private void settleHeldBid(TradeCommerceRow row, Deadline budget) throws SQLException {
        var parent = e.store.get(row.getParentId());
        if (parent == null)
            throw new CommerceEngine.CorruptContract("持有竞价的拍品不存在");
        int parentStatus = parent.getStatus();
        if (parentStatus == CANCELLED) {
            var lw = CommerceEngine.workflow(parent);
            if (!heldBidKeepsFunds(lw, row.getCommerceId())) {
                throw new CommerceEngine.CorruptContract(
                        "拍品按撤单结案，卖家所得腿却发出过或赢家那份货到过，出价冻结款不能自动退回");
            }
        } else if (parentStatus != SETTLED) {
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
            if (parentStatus == CANCELLED) {
                w.setStage("bid_refund");
                e.store.save(c, current.toBuilder().setStatus(CANCEL_PENDING)
                        .setWorkflowProto(w.build().toByteString()).setUpdatedMs(e.clock.getAsLong()));
                return null;
            }
            w.setStage("done");
            e.store.markConsumed(c, current.getCommerceId());
            e.store.save(c, current.toBuilder().setStatus(SETTLED)
                                .setWorkflowProto(w.build().toByteString()).setUpdatedMs(e.clock.getAsLong()));
            return null;
        });
    }
    private void deferContract(TradeCommerceRow row, Exception failure, Deadline budget) {
        String category = classifyContractFailure(row, failure);
        // 观察值并发变化（conflict）不推迟，留待下一轮立即重试。
        if ("conflict".equals(category)) {
            log.info("交易合同并发冲突不推迟、下一拍立即重试 class=conflict commerce_id={} version={}",
                    Long.toUnsignedString(row.getCommerceId()), row.getVersion());
            return;
        }
        if ("owner_changed".equals(category) || "corrupt".equals(category))
            try {
                boolean quarantined = "owner_changed".equals(category)
                        ? e.store.quarantine(row, CommerceRules.MANUAL_REASON_OWNER_CHANGED, failure.getMessage(),
                                e.clock.getAsLong(), budget)
                        : e.store.quarantine(row, budget);
                if (quarantined) {
                    log.error("交易合同隔离为人工 class={} commerce_id={} version={}", category,
                        Long.toUnsignedString(row.getCommerceId()), row.getVersion(), failure);
                    return;
                }
            } catch (SQLException | RuntimeException quarantineFailure) {
                log.error("交易合同隔离失败 commerce_id={}", Long.toUnsignedString(row.getCommerceId()), quarantineFailure);
            }
        // 角色冻结/投票中暂态冲突（role_conflict）分钟级退避（5 分钟）并单独告警。
        long base = "role_conflict".equals(category) ? 300_000L
            : "too_many_pending".equals(category) ? 60_000L : 10_000L;
        long until = e.clock.getAsLong() + (long) (base * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
        try {
            boolean deferred = e.store.defer(row, until, budget);
            if ("too_many_pending".equals(category))
                log.info("交易合同背压推迟 class={} commerce_id={} deferred={} until_ms={}", category,
                    Long.toUnsignedString(row.getCommerceId()), deferred, until);
            else if ("role_conflict".equals(category))
                log.warn("交易合同角色冲突分钟级退避 class={} commerce_id={} deferred={} until_ms={}", category,
                    Long.toUnsignedString(row.getCommerceId()), deferred, until);
            else
                log.error("交易合同单行失败 class={} commerce_id={} deferred={} until_ms={}", category,
                    Long.toUnsignedString(row.getCommerceId()), deferred, until, failure);
        } catch (SQLException | RuntimeException deferFailure) {
            failure.addSuppressed(deferFailure);
            log.error("交易合同推迟失败 class={} commerce_id={}", category, Long.toUnsignedString(row.getCommerceId()), failure);
        }
    }

    /** 玩家落地唤醒：将指定玩家名下尚未到期的 PENDING 交易资产待办立即拉回可投递时刻（assetop:wake）。 */
    public int wakePlayer(long playerId, Deadline budget) throws SQLException {
        if (playerId == 0L) {
            return 0;
        }
        long now = e.clock.getAsLong();
        return e.store.transaction(budget, c -> CommerceStore.execute(c,
                "UPDATE trade_asset_op SET next_attempt_ms=?,updated_ms=? WHERE player_id=? AND status=1 AND next_attempt_ms>?",
                now, now, playerId, now));
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
        Boolean finalized = e.store.transaction(budget, c -> {
            var rows = e.store.ops(c, "op_id=? FOR UPDATE", op.getOpId());
            if (rows.isEmpty() || rows.getFirst().getStatus() != 1)
                return false;
            CommerceStore.execute(c,
                "UPDATE trade_asset_op SET "
                    + "status=?,durable=1,last_outcome=?,last_reason=?,escrow_snapshot=?,receipt_ack_pending=?,lease_"
                    + "until_"
                    + "ms=0,next_attempt_ms=?,updated_ms=? WHERE op_id=? AND status=1",
                status, outcome.state() == AssetSeqState.APPLIED ? 1 : 2, outcome.rejectionReason(), snapshot,
                receipt != null && !receipt.getSnapshotProto().isEmpty() && status == 2, now, now, op.getOpId());
            return true;
        });
        // 事件唤醒：在资产指令终结事务提交之后单独唤醒所属交易合同（不在 op 行锁里锁合同行）。
        if (Boolean.TRUE.equals(finalized) && op.getRefKind() == 2 && op.getRefId() != 0L) {
            try {
                e.store.wake(op.getRefId(), e.clock.getAsLong(),
                        Deadline.after(Math.min(1000, Math.max(200, budget.remainingMillis()))));
            } catch (SQLException | RuntimeException wakeFailure) {
                log.warn("资产指令终结后唤醒交易合同失败，合同将在 30 秒兜底周期推进 commerce_id={} op_id={}",
                        Long.toUnsignedString(op.getRefId()), Long.toUnsignedString(op.getOpId()), wakeFailure);
            }
        }
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
        var live = this.liveness;
        if (live != null) {
            live.close();
        }
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
