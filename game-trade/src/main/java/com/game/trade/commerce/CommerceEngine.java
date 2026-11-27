package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.common.deadline.Deadline;
import com.game.proto.*;
import com.game.proto.trade.*;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SQL合同与可靠资产腿的业务边界。成功只来自已提交玩家SQL账本，未知永不补发新seq。 */
public final class CommerceEngine {
    private static final Logger log = LoggerFactory.getLogger(CommerceEngine.class);
    /** 确定的持久数据错误，只隔离这一份合同，不能每轮阻塞全服。 */
    static final class CorruptContract extends SQLException {
        CorruptContract(String message) {
            super(message);
        }
        CorruptContract(String message, Throwable cause) {
            super(message, cause);
        }
    }
    final CommerceStore store;
    final LongSupplier ids;
    final Policy policy;
    final LongSupplier clock;
    private final com.game.api.proto.SessionContext actor;
    private final PlayerTradeGuard playerTradeGuard;
    private final SellerCreditService credits;
    private final ReferencePrice.Reader referencePrices;
    public CommerceEngine(CommerceStore store, LongSupplier ids, Policy policy, LongSupplier clock) {
        this(store, ids, policy, clock, null, null, null, null);
    }
    private CommerceEngine(CommerceStore store, LongSupplier ids, Policy policy, LongSupplier clock,
                            com.game.api.proto.SessionContext actor,
                            PlayerTradeGuard playerTradeGuard, SellerCreditService credits,
                            ReferencePrice.Reader referencePrices) {
        this.store = Objects.requireNonNull(store);
        this.ids = Objects.requireNonNull(ids);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.actor = actor;
        this.playerTradeGuard = playerTradeGuard;
        this.credits = credits;
        this.referencePrices = referencePrices;
    }
    /** 挂载游戏内当面交易防骗门禁（返回共享底层账本的新引擎视图）。 */
    public CommerceEngine withPlayerTradeGuard(PlayerTradeGuard guard) {
        ReferencePrice.Reader effectivePrices = referencePrices != null ? referencePrices : (guard == null ? null : guard.prices());
        return new CommerceEngine(store, ids, policy, clock, actor, guard, credits, effectivePrices);
    }
    /** 挂载卖家店铺信用服务（官方托管首次结案后幂等计入真实成交）。 */
    public CommerceEngine withSellerCreditService(SellerCreditService service) {
        return new CommerceEngine(store, ids, policy, clock, actor, playerTradeGuard, service, referencePrices);
    }
    /** 挂载近 7 日真实成交中位参考价查询器。 */
    public CommerceEngine withReferencePrices(ReferencePrice.Reader reader) {
        return new CommerceEngine(store, ids, policy, clock, actor, playerTradeGuard, credits, reader);
    }
    PlayerTradeGuard playerTradeGuard() { return playerTradeGuard; }
    SellerCreditService credits() { return credits; }
    ReferencePrice.Reader referencePrices() { return referencePrices; }
    /** 每次客户端调用显式绑定原会话；共享的号源/仓储不变，不存线程局部或进程级请求状态。 */
    CommerceEngine forActor(com.game.api.proto.SessionContext actor) {
        if (actor == null || actor.getPlayerId() == 0 || actor.getAccount().isEmpty() || actor.getOwnerRevision() <= 0)
            throw new Refusal(INVALID);
        return new CommerceEngine(store, ids, policy, clock, actor, playerTradeGuard, credits, referencePrices);
    }
    com.game.api.proto.SessionContext actor() { return actor; }
    private String playerRequestKey(long me, String request) {
        if (actor == null) return key("player", me, request);
        if (actor.getPlayerId() != me) throw new Refusal(INVALID);
        return key("player:" + Long.toUnsignedString(actor.getOwnerRevision()) + ":"
            + HexFormat.of().formatHex(sha(actor.getAccount().getBytes(StandardCharsets.UTF_8)).toByteArray()), me, request);
    }
    private void verifyActor(java.sql.Connection c, long me) throws SQLException {
        if (actor != null) {
            if (actor.getPlayerId() != me) throw new Refusal(INVALID);
            com.game.common.player.PlayerSessionAuthority.lock(c, me, actor.getAccount(), actor.getOwnerRevision(), 2);
        }
    }
    static ByteString sha(byte[] bytes) {
        try {
            return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
    static byte[] deterministic(Message v) {
        byte[] out = new byte[v.getSerializedSize()];
        try {
            var c = CodedOutputStream.newInstance(out);
            c.useDeterministicSerialization();
            v.writeTo(c);
            c.checkNoSpaceLeft();
            return out;
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    static String key(String scope, long player, String request) {
        return HexFormat.of().formatHex(
            sha((scope + ":" + Long.toUnsignedString(player) + ":" + request).getBytes(StandardCharsets.UTF_8))
                .toByteArray());
    }
    static TradeCommerceWorkflow.Builder workflow(TradeCommerceRow r) throws SQLException {
        try {
            return TradeCommerceWorkflow.parseFrom(r.getWorkflowProto()).toBuilder();
        } catch (InvalidProtocolBufferException e) {
            throw new CorruptContract("交易合同损坏", e);
        }
    }
    /** 只重放同一玩家的完整原请求；已受理合同不依赖当前拍品等易变状态。 */
    TradeCommerceRow priorRequest(long me, String request, Message input, Deadline deadline) throws SQLException {
        CommerceStore.active(deadline);
        if (me == 0 || request.length() < 8 || request.length() > 80)
            throw new Refusal(INVALID);
        var prior = store.byRequest(playerRequestKey(me, request));
        if (prior != null && !prior.getRequestDigest().equals(sha(deterministic(input))))
            throw new Refusal(REQUEST_CONFLICT);
        return prior;
    }
    @FunctionalInterface
    private interface PostLockCheck {
        void verify(Connection connection, long nowMs) throws SQLException;
    }

    TradeCommerceRow create(long me, String request, Message input, TradeCommerceRow.Builder row,
        TradeCommerceWorkflow.Builder w, Deadline deadline) throws SQLException {
        return create(me, request, input, row, w, deadline, null);
    }

    private TradeCommerceRow create(long me, String request, Message input, TradeCommerceRow.Builder row,
        TradeCommerceWorkflow.Builder w, Deadline deadline, PostLockCheck postLockCheck) throws SQLException {
        var prior = priorRequest(me, request, input, deadline);
        if (prior != null)
            return prior;
        String requestKey = playerRequestKey(me, request);
        ByteString digest = sha(deterministic(input));
        long now = clock.getAsLong();
        row.setCommerceId(ids.getAsLong())
            .setRequestKey(requestKey)
            .setRequestDigest(digest)
            .setCreatedMs(now)
            .setUpdatedMs(now)
            .setVersion(1)
            .setWorkflowProto(w.build().toByteString());
        try {
            return store.transaction(deadline, c -> {
                CommerceStore.lockAssetOwners(c, row.getSellerPlayerId(), row.getBuyerPlayerId());
                verifyActor(c, me);
                if (postLockCheck != null) {
                    postLockCheck.verify(c, now);
                }
                if (row.getSellerPlayerId() != 0) w.setSellerIdentity(CommerceStore.roleIdentity(c, row.getSellerPlayerId()));
                if (row.getBuyerPlayerId() != 0) w.setBuyerIdentity(CommerceStore.roleIdentity(c, row.getBuyerPlayerId()));
                row.setWorkflowProto(w.build().toByteString());
                row.setOnOrderBook(CommerceStore.computeOnOrderBook(row));
                TradeCommerceRow built = row.build();
                CommerceStore.insert(c, "trade_commerce", built);
                return built;
            });
        } catch (SQLException failure) {
            var stored = store.byRequest(requestKey);
            if (stored != null) {
                if (!stored.getRequestDigest().equals(digest))
                    throw new Refusal(REQUEST_CONFLICT);
                return stored;
            }
            throw failure;
        }
    }
    public TradeCommerceRow createPlayerTrade(long me, CreatePlayerTradeRequest in, Deadline d) throws SQLException {
        if (in.getBuyerPlayerId() == 0)
            throw new Refusal(INVALID);
        if (in.getBuyerPlayerId() == me)
            throw new Refusal(SELF);
        policy.selection(in.getSellerAssets());
        policy.selection(in.getBuyerAssets());
        PostLockCheck guardCheck = playerTradeGuard == null ? null : (c, nowMs) -> {
            var eval = playerTradeGuard.evaluate(
                    c, me, in.getBuyerPlayerId(), in.getSellerAssets(), in.getBuyerAssets(), nowMs);
            // shortcut: TradeCommerceWorkflow 与场景物品暂未落盘 48h 冷静锁契约，触发冷静锁时先 fail-closed 拒放，待契约扩展后升级。
            if (eval.requireCoolingLock()) {
                throw new Refusal(NOT_TRADABLE);
            }
        };
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(2)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setBuyerPlayerId(in.getBuyerPlayerId())
                .setExpiresMs(clock.getAsLong() + 900000),
            TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(in.getSellerAssets())
                .setBuyerAssets(in.getBuyerAssets())
                .setStage("offer_escrow"),
            d,
            guardCheck);
    }
    public TradeCommerceRow consignment(long me, CreateConsignmentRequest in, Deadline d) throws SQLException {
        policy.selection(in.getAssets());
        policy.price(in.getPrice(), in.getCurrencyType());
        if (in.getDurationMs() < 60000 || in.getDurationMs() > 30L * 86400000)
            throw new Refusal(INVALID);
        if (in.getDesignatedBuyerPlayerId() == me)
            throw new Refusal(SELF);
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(3)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setUnitPrice(in.getPrice())
                .setCurrencyType(in.getCurrencyType())
                .setExpiresMs(clock.getAsLong() + in.getDurationMs()),
            TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(in.getAssets())
                .setDesignatedBuyerPlayerId(in.getDesignatedBuyerPlayerId())
                .setStage("escrow"),
            d);
    }
    public TradeCommerceRow lot(long me, CreateAuctionLotRequest in, Deadline d) throws SQLException {
        policy.selection(in.getAssets());
        policy.price(in.getOpeningPrice(), in.getCurrencyType());
        if (in.getMinimumIncrement() <= 0 || in.getDurationMs() < 60000 || in.getDurationMs() > 7L * 86400000)
            throw new Refusal(INVALID);
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(5)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setUnitPrice(in.getOpeningPrice())
                .setCurrencyType(in.getCurrencyType())
                .setExpiresMs(clock.getAsLong() + in.getDurationMs()),
            TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(in.getAssets())
                .setMinimumIncrement(in.getMinimumIncrement())
                .setStage("escrow"),
            d);
    }
    public TradeCommerceRow market(long me, PlaceMarketOrderRequest in, Deadline d) throws SQLException {
        policy.price(in.getUnitPrice(), in.getCurrencyType());
        if (in.getQuantity() <= 0 || in.getQuantity() > 4096 || in.getMarketId() != 1
            || !policy.items().contains(in.getItemConfigId()) || in.getSideValue() < 1 || in.getSideValue() > 2
            || in.getUnitPrice() > Long.MAX_VALUE / in.getQuantity()
            || policy.markets().stream().noneMatch(m
                -> m.getMarketId() == 1 && m.getItemConfigId() == in.getItemConfigId()
                    && m.getCurrencyType() == in.getCurrencyType()))
            throw new Refusal(INVALID);
        var selection = TradeAssetSelection.newBuilder();
        if (in.getSideValue() == 1)
            selection.setCurrencyType(in.getCurrencyType()).setCurrencyAmount(in.getUnitPrice() * in.getQuantity());
        else
            selection.putItems(in.getItemConfigId(), in.getQuantity());
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(4)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setMarketId(in.getMarketId())
                .setItemConfigId(in.getItemConfigId())
                .setSide(in.getSideValue())
                .setUnitPrice(in.getUnitPrice())
                .setQuantity(in.getQuantity())
                .setCurrencyType(in.getCurrencyType()),
            TradeCommerceWorkflow.newBuilder().setSellerAssets(selection).setStage("escrow"), d);
    }
    public TradeCommerceRow get(long me, long id) throws SQLException {
        var r = store.get(id);
        if (r == null
            || r.getSellerPlayerId() != me && r.getBuyerPlayerId() != me && r.getKind() != 3 && r.getKind() != 5)
            throw new Refusal(MISSING);
        return r;
    }
    public TradeCommerceRow mutate(long me, long id, String action, Deadline d, String... requestId)
        throws SQLException {
        return store.transaction(d, c -> {
            if (actor != null) verifyActor(c, me);
            else if (action.equals("buy")) CommerceStore.lockAssetOwners(c, me);
            var current = store.lock(c, id);
            if (current == null)
                throw new Refusal(MISSING);
            var r = current.toBuilder();
            var w = workflow(current);
            long now = CommerceStore.sqlNow(c);
            switch (action) {
                case "confirm" -> {
                    if (r.getKind() != 2 || !w.getStage().equals("confirm") || r.getExpiresMs() <= now
                        || w.getCancelRequested())
                        throw new Refusal(CONFLICT);
                    if (me == r.getSellerPlayerId()) {
                        if (!w.getBuyerConfirmed() || !applied(leg(w, "buyer_debit")))
                            throw new Refusal(CONFLICT);
                        w.setSellerConfirmed(true);
                    } else if (me == r.getBuyerPlayerId()) {
                        if (!applied(leg(w, "seller_debit")))
                            throw new Refusal(CONFLICT);
                        w.setBuyerConfirmed(true);
                        if (!applied(leg(w, "buyer_debit"))) {
                            w.setStage("buyer_escrow");
                            r.setStatus(ESCROWING);
                        }
                    } else
                        throw new Refusal(MISSING);
                    if (w.getSellerConfirmed() && w.getBuyerConfirmed()) {
                        w.setStage("escrow");
                        r.setStatus(ESCROWING);
                    }
                }
                case "buy" -> {
                    if (requestId.length != 1 || requestId[0].length() < 8 || requestId[0].length() > 80)
                        throw new Refusal(INVALID);
                    String requestKey = key("consignment_buy", me, requestId[0]);
                    for (var prior : w.getLegsList())
                        if (prior.getRole().endsWith("_" + requestKey)) {
                            if (r.getBuyerPlayerId() == me && List.of("pay", "deliver", "done").contains(w.getStage()))
                                return current;
                            throw new Refusal(CONFLICT);
                        }
                    if (r.getKind() == 3 && r.getBuyerPlayerId() == me
                        && List.of("pay", "deliver", "done").contains(w.getStage()))
                        return current;
                    if (r.getKind() != 3 || !w.getStage().equals("open") || r.getBuyerPlayerId() != 0
                        || r.getExpiresMs() <= now)
                        throw new Refusal(CONFLICT);
                    if (me == r.getSellerPlayerId())
                        throw new Refusal(SELF);
                    if (w.getDesignatedBuyerPlayerId() != 0 && w.getDesignatedBuyerPlayerId() != me)
                        throw new Refusal(CONFLICT);
                    if (w.getQuantityReserved() >= 32)
                        throw new Refusal(CONFLICT);
                    r.setBuyerPlayerId(me).setStatus(ESCROWING);
                    w.setBuyerIdentity(CommerceStore.roleIdentity(c, me));
                    w.setBuyerAssets(TradeAssetSelection.newBuilder()
                                         .setCurrencyType(r.getCurrencyType())
                                         .setCurrencyAmount(r.getUnitPrice()))
                        .setQuantityReserved(w.getQuantityReserved() + 1)
                        .setStage("pay");
                    add(w, "buyer_debit_" + Long.toUnsignedString(w.getQuantityReserved()) + "_" + requestKey, me, 3,
                        bundle(w.getBuyerAssets()));
                }
                case "cancel" -> {
                    if (me != r.getSellerPlayerId() && me != r.getBuyerPlayerId())
                        throw new Refusal(MISSING);
                    if (r.getStatus() == CANCELLED)
                        return current;
                    if (r.getKind() == 2 && List.of("confirm", "offer_escrow", "buyer_escrow").contains(w.getStage())
                        || me == r.getSellerPlayerId()
                            && (List.of("open", "market_open").contains(w.getStage())
                                || w.getStage().equals("lot_open") && w.getHighestBidder() == 0)) {
                        w.setCancelRequested(true);
                        r.setStatus(CANCEL_PENDING);
                        store.expireDebits(c, id, now);
                    } else
                        throw new Refusal(CONFLICT);
                }
                default -> throw new Refusal(INVALID);
            }
            r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(clock.getAsLong());
            return store.save(c, r);
        });
    }

    /**
     * 将商城报来的付款修订号单调写入官方合同（只增不减），非 MANUAL 合同回到 {@code official_deliver} 重新收敛发放。
     */
    public TradeCommerceRow applyPaymentRevision(long commerceId, int revision, Deadline d) throws SQLException {
        return store.transaction(d, c -> {
            var current = store.lock(c, commerceId);
            if (current == null) {
                throw new CorruptContract("官方订单合同不存在");
            }
            var w = workflow(current);
            if (current.getKind() != 1 || Integer.compareUnsigned(w.getPaymentRevision(), revision) >= 0) {
                return current;
            }
            var r = current.toBuilder();
            w.setPaymentRevision(revision).setPaymentRevoked((revision & 1) == 1);
            if (r.getStatus() != MANUAL) {
                w.setStage(STAGE_OFFICIAL_DELIVER);
                r.setStatus(DELIVERING);
            }
            r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(clock.getAsLong());
            return store.save(c, r);
        });
    }

    private record AdvanceOutcome(TradeCommerceRow saved, Optional<SellerCreditService.CompletedTradeFact> creditFact) {
        static final AdvanceOutcome SKIPPED = new AdvanceOutcome(null, Optional.empty());
    }

    public TradeCommerceRow advance(TradeCommerceRow prior, Deadline d) throws SQLException {
        var pw = workflow(prior);
        store.ensureSeqs(d, prior.getSellerPlayerId(), prior.getBuyerPlayerId(), pw.getHighestBidder());
        long[] opIds = new long[8];
        for (int i = 0; i < opIds.length; i++) opIds[i] = ids.getAsLong();
        AdvanceOutcome outcome = store.transaction(d, c -> {
            // 旧未终局合同缺少原件时隔离，禁止以恢复时的当前owner补出“原身份”。
            if ((prior.getKind() >= 1 && prior.getKind() <= 5 || prior.getKind() == BID_KIND)
                && (prior.getSellerPlayerId() != 0 && !pw.hasSellerIdentity()
                    || prior.getBuyerPlayerId() != 0 && !pw.hasBuyerIdentity()))
                throw new CorruptContract("交易合同缺少参与者原账号或归属修订");
            if (prior.getKind() != 6 && (pw.hasSellerIdentity() || pw.hasBuyerIdentity())) {
                CommerceStore.lockAssetOwners(c, prior.getSellerPlayerId(), prior.getBuyerPlayerId());
                if (prior.getSellerPlayerId() != 0 && pw.hasSellerIdentity()) com.game.common.player.PlayerSessionAuthority.lock(c, prior.getSellerPlayerId(),
                    pw.getSellerIdentity().getAccount(), pw.getSellerIdentity().getOwnerRevision(), 2);
                if (prior.getBuyerPlayerId() != 0 && pw.hasBuyerIdentity()) com.game.common.player.PlayerSessionAuthority.lock(c, prior.getBuyerPlayerId(),
                    pw.getBuyerIdentity().getAccount(), pw.getBuyerIdentity().getOwnerRevision(), 2);
            }
            if (prior.getKind() == 6 && pw.getRmb().getCharacterPlayerId() == 0) {
                if (Set.of("rmb_escrow", "rmb_return").contains(pw.getStage()))
                    com.game.common.player.PlayerSessionAuthority.lock(c, prior.getSellerPlayerId(), pw.getRmb().getSellerAccount(),
                        pw.getRmb().getSellerOwnerRevision(), 2);
                if (pw.getStage().equals("rmb_deliver"))
                    com.game.common.player.PlayerSessionAuthority.lock(c, prior.getBuyerPlayerId(), pw.getRmb().getBuyerAccount(),
                        pw.getRmb().getBuyerOwnerRevision(), 2);
            }
            var current = store.lock(c, prior.getCommerceId());
            // 原身份与点锁来自prior；新版本必须等下一轮用自身原件取锁，不能借旧观察推进。
            if (current == null || current.getVersion() != prior.getVersion())
                return AdvanceOutcome.SKIPPED;
            var r = current.toBuilder();
            var w = workflow(current);
            for (var l : w.getLegsList())
                if (l.getOutcome() != 0 && l.getOpId() == 0)
                    throw new CorruptContract("交易资产终局缺少原操作ID，拒绝重开扣款");
            long now = CommerceStore.sqlNow(c);
            for (var l : w.getLegsBuilderList()) {
                if (l.getOpId() == 0 || l.getOutcome() != 0)
                    continue;
                var rows = store.ops(c, "op_id=?", l.getOpId());
                if (rows.size() != 1)
                    throw new CorruptContract("交易资产待办丢失");
                var op = rows.getFirst();
                // 终局按状态判定；人工终结与已证实未扣的放弃刻意不写 durable，不能把另一方托管永远扣住。
                if (op.getStatus() == 1)
                    continue;
                if (op.getStatus() < 2 || op.getStatus() > 6)
                    throw new CorruptContract("交易资产待办状态非法");
                if (op.getStatus() == 5) {
                    w.setStage("manual");
                    r.setStatus(MANUAL);
                    r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(now);
                    TradeCommerceRow quarantined = store.save(c, r);
                    return new AdvanceOutcome(quarantined, Optional.empty());
                }
                if (op.getStatus() != 1) {
                    l.setOutcome(op.getStatus());
                    if (!op.getEscrowSnapshot().isEmpty())
                        try {
                            l.setSnapshot(AssetSnapshot.parseFrom(op.getEscrowSnapshot()));
                        } catch (InvalidProtocolBufferException e) {
                            throw new CorruptContract("托管快照损坏", e);
                        }
                    if (l.getStream() == 3 && applied(l) && !isOfficialReverseLeg(l.getRole()))
                        try {
                            transfer(l.build());
                        } catch (IllegalStateException corrupted) {
                            throw new CorruptContract("已扣出托管缺少可转交原件", corrupted);
                        }
                }
            }
            try {
                plan(r, w, now, policy);
            } catch (RuntimeException corrupted) {
                throw new CorruptContract("交易合同状态自相矛盾", corrupted);
            }
            marketBarrier(c, r, w);
            var pending = new ArrayList<>(w.getLegsBuilderList().stream().filter(l -> l.getOpId() == 0).toList());
            pending.sort((a, b) -> {
                int cmp = Long.compareUnsigned(a.getPlayerId(), b.getPlayerId());
                if (cmp == 0)
                    cmp = Integer.compare(a.getStream(), b.getStream());
                return cmp == 0 ? a.getRole().compareTo(b.getRole()) : cmp;
            });
            if (pending.size() > 8)
                throw new CorruptContract("单步交易腿数超过8");
            for (int i = 0; i < pending.size(); i++) {
                CommerceStore.active(d);
                var l = pending.get(i);
                l.setOpId(opIds[i]);
                var op = TradeAssetOpRow.newBuilder()
                             .setOpId(l.getOpId())
                             .setPlayerId(l.getPlayerId())
                             .setStream(l.getStream())
                             .setKind(l.getStream() == 3 ? 1 : 2)
                             .setStatus(1)
                             .setTxType(l.getStream() == 3 ? (isOfficialReverseLeg(l.getRole()) ? TX_PAYMENT_REVERSAL : 3) : 4)
                             .setRefKind(2)
                             .setRefId(r.getCommerceId())
                             .setPayload(l.getBundle().toByteString())
                             .setNextAttemptMs(now)
                             .setCreatedMs(now)
                             .setUpdatedMs(now)
                             .setLastSendMs(now);
                if (l.getStream() == 3 && r.getExpiresMs() > 0)
                    op.setDeadlineMs(r.getExpiresMs());
                if (l.getStream() == 3 && w.getCancelRequested())
                    op.setDeadlineMs(now);
                store.enqueue(c, op);
            }
            if (w.getStage().equals("done"))
                store.markConsumed(c, r.getCommerceId());
            long engineNow = clock.getAsLong();
            TradeCommerceWorkflow builtWorkflow = w.build();
            // 库时钟只裁决截止；updated_ms 是按引擎时钟扫描的调度键。
            r.setWorkflowProto(builtWorkflow.toByteString()).setUpdatedMs(engineNow);
            TradeCommerceRow saved = store.save(c, r);
            Optional<SellerCreditService.CompletedTradeFact> fact = current.getStatus() != SETTLED && saved.getStatus() == SETTLED
                    ? SellerCreditService.settledTradeFact(saved, builtWorkflow, engineNow)
                    : Optional.empty();
            return new AdvanceOutcome(saved, fact);
        });
        if (outcome != null && outcome.creditFact().isPresent()) {
            recordSettledCreditBestEffort(outcome.creditFact().get());
        }
        return outcome == null ? null : outcome.saved();
    }

    void recordSettledCreditBestEffort(SellerCreditService.CompletedTradeFact fact) {
        if (credits == null || fact == null) {
            return;
        }
        try {
            credits.recordCompletedTrade(fact);
        } catch (SQLException | RuntimeException failure) {
            log.error("官方托管真实成交记入卖家信用失败，不阻塞主账本 order_id={} seller_id={}",
                    fact.orderId(), Long.toUnsignedString(fact.sellerPlayerId()), failure);
        }
    }
    private void marketBarrier(Connection c, TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w)
        throws SQLException {
        if (r.getKind() != 4 || r.getSide() == 0)
            return;
        var children = store.list(
            c, "kind=4 AND side=0 AND (parent_id=? OR counterparty_id=?)", r.getCommerceId(), r.getCommerceId());
        boolean pending = children.stream().anyMatch(v -> v.getStatus() != SETTLED && v.getStatus() != CANCELLED);
        if (children.stream().anyMatch(v -> v.getStatus() == MANUAL)) {
            w.setStage("manual");
            r.setStatus(MANUAL);
            return;
        }
        if (w.getStage().equals("done") && pending) {
            w.setStage("market_wait");
            r.setStatus(CANCEL_PENDING);
        }
        if (w.getStage().equals("market_wait") && !pending) {
            w.setStage("done");
            r.setStatus(CANCELLED);
        }
        if (w.getStage().equals("market_open") && r.getQuantity() == r.getFilledQuantity() && !pending) {
            w.setStage("done");
            r.setStatus(SETTLED);
        }
    }
}
