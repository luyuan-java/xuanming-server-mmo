package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.common.deadline.Deadline;
import com.game.proto.*;
import com.game.proto.trade.*;
import com.game.table.TradeErrorTip;
import com.game.trade.store.pb.TradeCommerceRow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/** 价格时间订单簿与最高价竞拍；guard只串行化同一市场/lot，裁决和待办同SQL提交。 */
public final class CommerceMatching {
    private final CommerceEngine e;
    public CommerceMatching(CommerceEngine engine) {
        e = engine;
    }
    public TradeCommerceRow bid(long me, long lotId, PlaceAuctionBidRequest input, Deadline d) throws SQLException {
        var lot = e.get(me, lotId);
        var w = CommerceEngine.workflow(lot);
        if (lot.getKind() != 5 || !w.getStage().equals("lot_open") || lot.getExpiresMs() <= e.clock.getAsLong())
            throw new Refusal(TradeErrorTip.trade_error.kTradeAuctionEnded_VALUE);
        if (me == lot.getSellerPlayerId())
            throw new Refusal(SELF);
        if (input.getAmount() < lot.getUnitPrice() || input.getAmount() <= 0
            || w.getHighestBid() > 0
                && (w.getMinimumIncrement() > Long.MAX_VALUE - w.getHighestBid()
                    || input.getAmount() < w.getHighestBid() + w.getMinimumIncrement()))
            throw new Refusal(TradeErrorTip.trade_error.kTradeBidTooLow_VALUE);
        return e.create(me, input.getClientRequestId(), input,
            TradeCommerceRow.newBuilder()
                .setKind(BID_KIND)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setParentId(lotId)
                .setUnitPrice(input.getAmount())
                .setCurrencyType(lot.getCurrencyType())
                .setExpiresMs(lot.getExpiresMs()),
            TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(TradeAssetSelection.newBuilder()
                        .setCurrencyType(lot.getCurrencyType())
                        .setCurrencyAmount(input.getAmount()))
                .setStage("bid_freeze"),
            d);
    }
    public void acceptBid(TradeCommerceRow candidate, Deadline d) throws SQLException {
        e.store.transaction(d, c -> {
            CommerceStore.execute(
                c, "INSERT IGNORE INTO trade_commerce_guard(guard_id) VALUES(?)", candidate.getParentId());
            try (var s = c.prepareStatement("SELECT guard_id FROM trade_commerce_guard WHERE guard_id=? FOR UPDATE")) {
                s.setQueryTimeout(2);
                s.setObject(1, new java.math.BigInteger(Long.toUnsignedString(candidate.getParentId())));
                try (var rs = s.executeQuery()) {
                    if (!rs.next())
                        throw new SQLException("竞拍守卫不存在");
                }
            }
            var lot = e.store.lock(c, candidate.getParentId());
            var lw = CommerceEngine.workflow(lot);
            var bid = e.store.lock(c, candidate.getCommerceId());
            var bw = CommerceEngine.workflow(bid);
            if (!bw.getStage().equals("bid_ready"))
                return null;
            long now = CommerceStore.sqlNow(c);
            boolean eligible = lw.getStage().equals("lot_open") && !lw.getCancelRequested() && lot.getExpiresMs() > now
                && bid.getUnitPrice() >= lot.getUnitPrice()
                && (lw.getHighestBid() == 0
                    || lw.getMinimumIncrement() <= Long.MAX_VALUE - lw.getHighestBid()
                        && bid.getUnitPrice() >= lw.getHighestBid() + lw.getMinimumIncrement());
            if (!eligible) {
                bw.setStage("bid_refund");
                e.store.save(c,
                    bid.toBuilder()
                        .setStatus(CANCEL_PENDING)
                        .setWorkflowProto(bw.build().toByteString())
                        .setUpdatedMs(now));
                return null;
            }
            if (lw.getWinningBidId() != 0) {
                var old = e.store.lock(c, lw.getWinningBidId());
                var ow = CommerceEngine.workflow(old);
                if (!ow.getStage().equals("bid_held"))
                    throw new SQLException("最高价冻结合同损坏");
                ow.setStage("bid_refund");
                e.store.save(c,
                    old.toBuilder()
                        .setStatus(CANCEL_PENDING)
                        .setWorkflowProto(ow.build().toByteString())
                        .setUpdatedMs(now));
            }
            lw.setHighestBid(bid.getUnitPrice())
                .setHighestBidder(bid.getSellerPlayerId())
                .setWinningBidId(bid.getCommerceId())
                .setBuyerAssets(bw.getSellerAssets());
            bw.setStage("bid_held");
            e.store.save(c,
                lot.toBuilder()
                    .setBuyerPlayerId(bid.getSellerPlayerId())
                    .setWorkflowProto(lw.build().toByteString())
                    .setUpdatedMs(now));
            e.store.save(c, bid.toBuilder().setWorkflowProto(bw.build().toByteString()).setUpdatedMs(now));
            return null;
        });
    }
    public void match(TradeCommerceRow taker, Deadline d) throws SQLException {
        if (taker.getKind() != 4 || taker.getSide() == 0 || taker.getQuantity() == taker.getFilledQuantity())
            return;
        long matchId = e.ids.getAsLong();
        e.store.transaction(d, c -> {
            byte[] key = CommerceEngine
                             .sha(("market:" + taker.getMarketId() + ":" + taker.getItemConfigId() + ":"
                                 + taker.getCurrencyType())
                                     .getBytes(StandardCharsets.UTF_8))
                             .toByteArray();
            long guard = ByteBuffer.wrap(key).getLong() | Long.MIN_VALUE;
            CommerceStore.execute(c, "INSERT IGNORE INTO trade_commerce_guard(guard_id) VALUES(?)", guard);
            try (var s = c.prepareStatement("SELECT guard_id FROM trade_commerce_guard WHERE guard_id=? FOR UPDATE")) {
                s.setQueryTimeout(2);
                s.setObject(1, new java.math.BigInteger(Long.toUnsignedString(guard)));
                try (var rs = s.executeQuery()) {
                    if (!rs.next())
                        throw new SQLException("市场守卫不存在");
                }
            }
            int opposite = taker.getSide() == 1 ? 2 : 1;
            String price = taker.getSide() == 1 ? "unit_price<=? ORDER BY unit_price ASC,created_ms,commerce_id"
                                                : "unit_price>=? ORDER BY unit_price DESC,created_ms,commerce_id";
            var candidates = e.store.list(c,
                "kind=4 AND side=? AND market_id=? AND item_config_id=? AND currency_type=? AND status=3 AND "
                    + "filled_quantity<quantity AND seller_player_id<>? AND " + price + " LIMIT 1",
                opposite, taker.getMarketId(), taker.getItemConfigId(), taker.getCurrencyType(),
                taker.getSellerPlayerId(), taker.getUnitPrice());
            if (candidates.isEmpty())
                return null;
            long other = candidates.getFirst().getCommerceId();
            long first = Long.compareUnsigned(taker.getCommerceId(), other) < 0 ? taker.getCommerceId() : other,
                 second = first == other ? taker.getCommerceId() : other;
            var a = e.store.lock(c, first);
            var b = e.store.lock(c, second);
            var aw = CommerceEngine.workflow(a);
            var bw = CommerceEngine.workflow(b);
            if (!aw.getStage().equals("market_open") || !bw.getStage().equals("market_open") || aw.getCancelRequested()
                || bw.getCancelRequested() || a.getFilledQuantity() >= a.getQuantity()
                || b.getFilledQuantity() >= b.getQuantity())
                return null;
            var buy = a.getSide() == 1 ? a : b;
            var sell = a.getSide() == 2 ? a : b;
            if (buy.getUnitPrice() < sell.getUnitPrice() || buy.getSellerPlayerId() == sell.getSellerPlayerId())
                return null;
            var maker = a.getCreatedMs() < b.getCreatedMs()
                    || a.getCreatedMs() == b.getCreatedMs()
                        && Long.compareUnsigned(a.getCommerceId(), b.getCommerceId()) < 0
                ? a
                : b;
            long unit = maker.getUnitPrice();
            int quantity =
                Math.min(buy.getQuantity() - buy.getFilledQuantity(), sell.getQuantity() - sell.getFilledQuantity());
            long now = CommerceStore.sqlNow(c);
            var mw = TradeCommerceWorkflow.newBuilder().setStage("deliver");
            add(mw, "buyer_receive", buy.getSellerPlayerId(), 4,
                AssetBundle.newBuilder()
                    .addItems(ItemGrant.newBuilder().setConfigId(buy.getItemConfigId()).setCount(quantity))
                    .build());
            add(mw, "seller_receive", sell.getSellerPlayerId(), 4,
                AssetBundle.newBuilder()
                    .addCurrencies(
                        CurrencyAmount.newBuilder().setCurrencyType(buy.getCurrencyType()).setAmount(quantity * unit))
                    .build());
            if (buy.getUnitPrice() > unit)
                add(mw, "buyer_surplus", buy.getSellerPlayerId(), 4,
                    AssetBundle.newBuilder()
                        .addCurrencies(CurrencyAmount.newBuilder()
                                .setCurrencyType(buy.getCurrencyType())
                                .setAmount(quantity * (buy.getUnitPrice() - unit)))
                        .build());
            var match = TradeCommerceRow.newBuilder()
                            .setCommerceId(matchId)
                            .setRequestKey("match:" + Long.toUnsignedString(matchId))
                            .setKind(4)
                            .setStatus(DELIVERING)
                            .setSellerPlayerId(sell.getSellerPlayerId())
                            .setBuyerPlayerId(buy.getSellerPlayerId())
                            .setMarketId(buy.getMarketId())
                            .setItemConfigId(buy.getItemConfigId())
                            .setUnitPrice(unit)
                            .setQuantity(quantity)
                            .setCurrencyType(buy.getCurrencyType())
                            .setCreatedMs(now)
                            .setUpdatedMs(now)
                            .setVersion(1)
                            .setWorkflowProto(mw.build().toByteString())
                            .setParentId(buy.getCommerceId())
                            .setCounterpartyId(sell.getCommerceId())
                            .build();
            CommerceStore.insert(c, "trade_commerce", match);
            var ar = a.toBuilder().setFilledQuantity(a.getFilledQuantity() + quantity).setUpdatedMs(now);
            var br = b.toBuilder().setFilledQuantity(b.getFilledQuantity() + quantity).setUpdatedMs(now);
            if (ar.getFilledQuantity() == ar.getQuantity())
                ar.setStatus(DELIVERING);
            if (br.getFilledQuantity() == br.getQuantity())
                br.setStatus(DELIVERING);
            e.store.save(c, ar);
            e.store.save(c, br);
            return null;
        });
    }
}
