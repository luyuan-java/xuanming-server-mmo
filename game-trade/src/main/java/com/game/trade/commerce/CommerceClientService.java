package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.common.deadline.Deadline;
import com.game.common.player.HomeZones;
import com.game.proto.TipInfoMessage;
import com.game.proto.trade.*;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.Message;
import java.sql.SQLException;
import java.util.*;

/** 客户端只选已有资产；应答摘要来自已提交托管快照，所有写入经同一合同引擎。 */
public final class CommerceClientService {
    private final CommerceEngine engine;
    private final HomeZones homes;
    private final OfficialMallClient mall;
    public CommerceClientService(CommerceEngine engine, HomeZones homes) {
        this(engine, homes, null);
    }
    public CommerceClientService(CommerceEngine engine, HomeZones homes, OfficialMallClient mall) {
        this.engine = engine;
        this.homes = homes;
        this.mall = mall;
    }
    public Message handle(String method, long me, Message input, Message prototype, Deadline d) {
        if (engine == null)
            return rejection(prototype, com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
        try {
            CommerceStore.active(d);
            if (method.equals("ListOfficialSkus") || method.equals("CreateOfficialOrder")
                || method.equals("GetCommerceOrder") && ((GetCommerceOrderRequest) input).getOrderId().contains("-")
                || method.equals("CancelCommerceOrder")
                    && ((CancelCommerceOrderRequest) input).getOrderId().contains("-")) {
                if (mall == null)
                    return rejection(prototype, com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
                return mall.client(method, me, input, d);
            }
            return switch (method) {
                case "CreatePlayerTrade" -> {
                    var in = (CreatePlayerTradeRequest) input;
                    if (homes.homeZoneOf(in.getBuyerPlayerId(), d) == 0)
                        throw new Refusal(com.game.table.TradeErrorTip.trade_error.kTradeCounterpartyUnknown_VALUE);
                    yield order(engine.createPlayerTrade(me, in, d));
                }
                case "ConfirmPlayerTrade" ->
                    order(engine.mutate(me, id(((ConfirmPlayerTradeRequest) input).getOrderId()), "confirm", d));
                case "CreateConsignment" -> order(engine.consignment(me, (CreateConsignmentRequest) input, d));
                case "BuyConsignment" -> {
                    var in = (BuyConsignmentRequest) input;
                    if (in.getClientRequestId().length() < 8)
                        throw new Refusal(INVALID);
                    yield order(engine.mutate(me, id(in.getListingId()), "buy", d, in.getClientRequestId()));
                }
                case "CancelConsignment" ->
                    order(engine.mutate(me, id(((CancelConsignmentRequest) input).getListingId()), "cancel", d));
                case "CancelCommerceOrder" ->
                    order(engine.mutate(me, id(((CancelCommerceOrderRequest) input).getOrderId()), "cancel", d));
                case "GetCommerceOrder" -> order(engine.get(me, id(((GetCommerceOrderRequest) input).getOrderId())));
                case "ListCommerceOrders" -> {
                    var in = (ListCommerceOrdersRequest) input;
                    var out = ListCommerceOrdersResponse.newBuilder().setServerNowMs(engine.clock.getAsLong());
                    String after = in.getCursor();
                    if (mall != null && (after.isEmpty() || after.startsWith("mall:"))) {
                        String previous = after.startsWith("mall:") ? after.substring(5) : "";
                        var official = mall.list(me, previous, limit(in.getLimit(), 100), d);
                        if (!official.isEmpty()) {
                            out.addAllOrders(official).setNextCursor("mall:" + official.getLast().getOrderId());
                            yield out.build();
                        }
                    }
                    if (after.startsWith("mall:"))
                        after = "";
                    for (var r : engine.store.list("commerce_id>? AND kind<>1 AND (seller_player_id=? OR "
                                 + "buyer_player_id=?) ORDER BY commerce_id LIMIT ?",
                             cursor(after), me, me, limit(in.getLimit(), 100)))
                        out.addOrders(view(r)).setNextCursor(Long.toUnsignedString(r.getCommerceId()));
                    yield out.build();
                }
                case "PlaceMarketOrder" ->
                    MarketOrderResponse.newBuilder()
                        .setOrder(market(engine.market(me, (PlaceMarketOrderRequest) input, d)))
                        .build();
                case "CancelMarketOrder" ->
                    MarketOrderResponse.newBuilder()
                        .setOrder(market(
                            engine.mutate(me, id(((CancelMarketOrderRequest) input).getMarketOrderId()), "cancel", d)))
                        .build();
                case "ListMarketOrders" -> {
                    var in = (ListMarketOrdersRequest) input;
                    var out = ListMarketOrdersResponse.newBuilder();
                    for (var r : engine.store.list(
                             "commerce_id>? AND seller_player_id=? AND kind=4 AND side<>0 ORDER BY commerce_id LIMIT ?",
                             cursor(in.getCursor()), me, limit(in.getLimit(), 100)))
                        out.addOrders(market(r)).setNextCursor(Long.toUnsignedString(r.getCommerceId()));
                    yield out.build();
                }
                case "ListTradeMarkets" ->
                    ListTradeMarketsResponse.newBuilder().addAllMarkets(engine.policy.markets()).build();
                case "GetMarketDepth" -> depth((GetMarketDepthRequest) input, d);
                case "CreateAuctionLot" ->
                    AuctionLotResponse.newBuilder()
                        .setLot(lot(engine.lot(me, (CreateAuctionLotRequest) input, d)))
                        .setServerNowMs(engine.clock.getAsLong())
                        .build();
                case "PlaceAuctionBid" -> {
                    var in = (PlaceAuctionBidRequest) input;
                    new CommerceMatching(engine).bid(me, id(in.getLotId()), in, d);
                    yield AuctionLotResponse.newBuilder()
                        .setLot(lot(engine.get(me, id(in.getLotId()))))
                        .setServerNowMs(engine.clock.getAsLong())
                        .build();
                }
                case "GetAuctionLot" -> {
                    var r = engine.get(me, id(((GetAuctionLotRequest) input).getLotId()));
                    if (r.getKind() != 5)
                        throw new Refusal(MISSING);
                    yield AuctionLotResponse.newBuilder()
                        .setLot(lot(r))
                        .setServerNowMs(engine.clock.getAsLong())
                        .build();
                }
                case "CancelAuctionLot" ->
                    AuctionLotResponse.newBuilder()
                        .setLot(lot(engine.mutate(me, id(((CancelAuctionLotRequest) input).getLotId()), "cancel", d)))
                        .setServerNowMs(engine.clock.getAsLong())
                        .build();
                case "BrowseCommerceOffers" -> browse((BrowseCommerceOffersRequest) input);
                default -> rejection(prototype, com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
            };
        } catch (Refusal denied) {
            return rejection(prototype, denied.tip());
        } catch (SQLException | IllegalArgumentException failure) {
            return rejection(prototype,
                failure instanceof IllegalArgumentException
                    ? INVALID
                    : com.game.table.CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException)
                Thread.currentThread().interrupt();
            return rejection(prototype, com.game.table.CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        }
    }
    public static Message rejection(Message p, int tip) {
        var b = p.newBuilderForType();
        b.setField(
            p.getDescriptorForType().findFieldByName("error_message"), TipInfoMessage.newBuilder().setId(tip).build());
        return b.build();
    }
    static long id(String value) {
        try {
            long id = Long.parseUnsignedLong(value);
            if (id == 0 || !Long.toUnsignedString(id).equals(value))
                throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new Refusal(INVALID);
        }
    }
    static long cursor(String value) {
        return value.isEmpty() ? 0 : id(value);
    }
    static int limit(int requested, int max) {
        return Math.min(Math.max(requested, 1), max);
    }
    CommerceOrderView view(TradeCommerceRow r) throws SQLException {
        var w = CommerceEngine.workflow(r);
        return CommerceOrderView.newBuilder()
            .setOrderId(r.getKind() == 1 ? w.getExternalOrderSn() : Long.toUnsignedString(r.getCommerceId()))
            .setKindValue(r.getKind() == BID_KIND ? 5 : r.getKind())
            .setStateValue(r.getStatus())
            .setSellerPlayerId(r.getSellerPlayerId())
            .setBuyerPlayerId(r.getBuyerPlayerId())
            .setAmount(r.getKind() == 1 ? w.getExternalAmount() : r.getUnitPrice())
            .setCurrency(r.getKind() == 1 ? w.getExternalCurrency() : Integer.toUnsignedString(r.getCurrencyType()))
            .setCreatedMs(r.getCreatedMs())
            .setVersion(r.getVersion())
            .setSellerConfirmed(w.getSellerConfirmed())
            .setBuyerConfirmed(w.getBuyerConfirmed())
            .setRelatedId(Long.toUnsignedString(r.getKind() == 1 ? w.getSkuId() : r.getParentId()))
            .build();
    }
    String title(int config) {
        return engine.policy.markets()
            .stream()
            .filter(m -> m.getItemConfigId() == config)
            .map(TradeMarketView::getTitle)
            .findFirst()
            .orElse("");
    }
    void summary(TradeAssetSelection selection, TradeCommerceLeg leg,
        java.util.function.Consumer<TradeOfferInstance> item, java.util.function.Consumer<TradeOfferInstance> pet) {
        selection.getItemsMap().forEach((config, count)
                                            -> item.accept(TradeOfferInstance.newBuilder()
                                                    .setConfigId(config)
                                                    .setCount(count)
                                                    .setName(title(config))
                                                    .build()));
        if (leg == null || leg.getOutcome() != 2)
            return;
        for (var i : leg.getSnapshot().getItemsList())
            item.accept(TradeOfferInstance.newBuilder()
                    .setInstanceId(i.getItemUuid())
                    .setConfigId(i.getConfigId())
                    .setCount(i.getStackSize())
                    .setName(title(i.getConfigId()))
                    .build());
        if (leg.getSnapshot().hasPet()) {
            var p = leg.getSnapshot().getPet();
            pet.accept(TradeOfferInstance.newBuilder()
                    .setInstanceId(p.getPetId())
                    .setConfigId(p.getPetTableId())
                    .setName(p.getName())
                    .setLevel(p.getLevel())
                    .build());
        }
    }
    CommerceOrderResponse order(TradeCommerceRow r) throws SQLException {
        var w = CommerceEngine.workflow(r);
        var out = CommerceOrderResponse.newBuilder()
                      .setOrder(view(r))
                      .setSellerAssets(w.getSellerAssets())
                      .setBuyerAssets(w.getBuyerAssets())
                      .setServerNowMs(engine.clock.getAsLong());
        var a = leg(w, "seller_debit");
        var b = leg(w, "buyer_debit");
        summary(w.getSellerAssets(), a == null ? null : a.build(), out::addSellerItemInstances, out::setSellerPet);
        summary(w.getBuyerAssets(), b == null ? null : b.build(), out::addBuyerItemInstances, out::setBuyerPet);
        return out.build();
    }
    MarketOrderView market(TradeCommerceRow r) throws SQLException {
        return MarketOrderView.newBuilder()
            .setMarketOrderId(Long.toUnsignedString(r.getCommerceId()))
            .setMarketId(r.getMarketId())
            .setItemConfigId(r.getItemConfigId())
            .setSideValue(r.getSide())
            .setQuantity(r.getQuantity())
            .setFilledQuantity(r.getFilledQuantity())
            .setUnitPrice(r.getUnitPrice())
            .setCurrencyType(r.getCurrencyType())
            .setState(CommerceEngine.workflow(r).getStage())
            .setCreatedMs(r.getCreatedMs())
            .build();
    }
    AuctionLotView lot(TradeCommerceRow r) throws SQLException {
        var w = CommerceEngine.workflow(r);
        return AuctionLotView.newBuilder()
            .setLotId(Long.toUnsignedString(r.getCommerceId()))
            .setSellerPlayerId(r.getSellerPlayerId())
            .setCurrencyType(r.getCurrencyType())
            .setOpeningPrice(r.getUnitPrice())
            .setMinimumIncrement(w.getMinimumIncrement())
            .setHighestBid(w.getHighestBid())
            .setHighestBidder(w.getHighestBidder())
            .setEndMs(r.getExpiresMs())
            .setState(w.getStage())
            .setVersion(r.getVersion())
            .setOrderId(Long.toUnsignedString(r.getCommerceId()))
            .build();
    }
    Message browse(BrowseCommerceOffersRequest in) throws SQLException {
        if (in.getKindValue() != 3 && in.getKindValue() != 5)
            throw new Refusal(INVALID);
        var out = BrowseCommerceOffersResponse.newBuilder().setServerNowMs(engine.clock.getAsLong());
        for (var r :
            engine.store.list("kind=? AND status=3 AND commerce_id>? AND expires_ms>? ORDER BY commerce_id LIMIT ?",
                in.getKindValue(), cursor(in.getCursor()), engine.clock.getAsLong(), limit(in.getLimit(), 100))) {
            out.setNextCursor(Long.toUnsignedString(r.getCommerceId()));
            var w = CommerceEngine.workflow(r);
            if (!Set.of("open", "lot_open").contains(w.getStage()))
                continue;
            var v = CommerceOfferView.newBuilder().setOrder(view(r)).setAssets(w.getSellerAssets());
            var a = leg(w, "seller_debit");
            summary(w.getSellerAssets(), a == null ? null : a.build(), v::addItemInstances, v::setPet);
            if (r.getKind() == 5)
                v.setAuction(lot(r));
            out.addOffers(v);
        }
        return out.build();
    }
    Message depth(GetMarketDepthRequest in, Deadline d) throws SQLException {
        var market = engine.policy.markets()
                         .stream()
                         .filter(m -> m.getItemConfigId() == in.getItemConfigId())
                         .findFirst()
                         .orElseThrow(() -> new Refusal(INVALID));
        if (in.getMarketId() != 1)
            throw new Refusal(INVALID);
        var out = GetMarketDepthResponse.newBuilder().setServerNowMs(engine.clock.getAsLong());
        engine.store.transaction(d, c -> {
            for (int side : new int[] {1, 2})
                try (var q = c.prepareStatement(
                         "SELECT unit_price,SUM(quantity-filled_quantity) FROM trade_commerce WHERE kind=4 AND side=? "
                         + "AND market_id=1 AND item_config_id=? AND currency_type=? AND status=3 AND "
                         + "filled_quantity<quantity GROUP BY unit_price ORDER BY unit_price "
                         + (side == 1 ? "DESC" : "ASC") + " LIMIT ?")) {
                    q.setQueryTimeout(2);
                    q.setInt(1, side);
                    q.setInt(2, in.getItemConfigId());
                    q.setInt(3, market.getCurrencyType());
                    q.setInt(4, limit(in.getLimit(), 50));
                    try (var rows = q.executeQuery()) {
                        while (rows.next()) {
                            var v = MarketDepthLevel.newBuilder()
                                        .setUnitPrice(rows.getLong(1))
                                        .setQuantity(rows.getLong(2));
                            if (side == 1)
                                out.addBids(v);
                            else
                                out.addAsks(v);
                        }
                    }
                }
            return null;
        });
        return out.build();
    }
}
