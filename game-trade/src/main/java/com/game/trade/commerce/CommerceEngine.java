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

/** SQL合同与可靠资产腿的业务边界。成功只来自已提交玩家SQL账本，未知永不补发新seq。 */
public final class CommerceEngine {
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
    public CommerceEngine(CommerceStore store, LongSupplier ids, Policy policy, LongSupplier clock) {
        this(store, ids, policy, clock, null);
    }
    private CommerceEngine(CommerceStore store, LongSupplier ids, Policy policy, LongSupplier clock,
                            com.game.api.proto.SessionContext actor) {
        this.store = Objects.requireNonNull(store);
        this.ids = Objects.requireNonNull(ids);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.actor = actor;
    }
    /** 每次客户端调用显式绑定原会话；共享的号源/仓储不变，不存线程局部或进程级请求状态。 */
    CommerceEngine forActor(com.game.api.proto.SessionContext actor) {
        if (actor == null || actor.getPlayerId() == 0 || actor.getAccount().isEmpty() || actor.getOwnerRevision() <= 0)
            throw new Refusal(INVALID);
        return new CommerceEngine(store, ids, policy, clock, actor);
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
    TradeCommerceRow create(long me, String request, Message input, TradeCommerceRow.Builder row,
        TradeCommerceWorkflow.Builder w, Deadline deadline) throws SQLException {
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
                if (row.getSellerPlayerId() != 0) w.setSellerIdentity(CommerceStore.roleIdentity(c, row.getSellerPlayerId()));
                if (row.getBuyerPlayerId() != 0) w.setBuyerIdentity(CommerceStore.roleIdentity(c, row.getBuyerPlayerId()));
                row.setWorkflowProto(w.build().toByteString());
                CommerceStore.insert(c, "trade_commerce", row.build());
                return row.build();
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
            d);
    }
    public TradeCommerceRow consignment(long me, CreateConsignmentRequest in, Deadline d) throws SQLException {
        policy.selection(in.getAssets());
        policy.price(in.getPrice(), in.getCurrencyType());
        if (in.getDurationMs() < 60000 || in.getDurationMs() > 30L * 86400000)
            throw new Refusal(INVALID);
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(3)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setUnitPrice(in.getPrice())
                .setCurrencyType(in.getCurrencyType())
                .setExpiresMs(clock.getAsLong() + in.getDurationMs()),
            TradeCommerceWorkflow.newBuilder().setSellerAssets(in.getAssets()).setStage("escrow"), d);
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

    public void advance(TradeCommerceRow prior, Deadline d) throws SQLException {
        var pw = workflow(prior);
        store.ensureSeqs(d, prior.getSellerPlayerId(), prior.getBuyerPlayerId(), pw.getHighestBidder());
        long[] opIds = new long[8];
        for (int i = 0; i < opIds.length; i++) opIds[i] = ids.getAsLong();
        store.transaction(d, c -> {
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
                return null;
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
                    store.save(c, r);
                    return null;
                }
                if (op.getStatus() != 1) {
                    l.setOutcome(op.getStatus());
                    if (!op.getEscrowSnapshot().isEmpty())
                        try {
                            l.setSnapshot(AssetSnapshot.parseFrom(op.getEscrowSnapshot()));
                        } catch (InvalidProtocolBufferException e) {
                            throw new CorruptContract("托管快照损坏", e);
                        }
                    if (l.getStream() == 3 && applied(l))
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
                             .setTxType(l.getStream() == 3 ? 3 : 4)
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
            // 库时钟只裁决截止；updated_ms 是按引擎时钟扫描的调度键。
            r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(clock.getAsLong());
            store.save(c, r);
            return null;
        });
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
