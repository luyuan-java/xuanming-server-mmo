package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.common.deadline.Deadline;
import com.game.common.player.HomeZones;
import com.game.proto.TipInfoMessage;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.trade.*;
import com.game.trade.dispatch.TradeMethods;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.Message;
import java.sql.SQLException;
import java.util.*;
import java.util.function.LongSupplier;

/** 客户端只选已有资产；应答摘要来自已提交托管快照，所有写入经同一合同引擎。 */
public final class CommerceClientService {
    private static final int MAX_SELLER_REVIEW_COMMENT_CODEPOINTS = 200;
    private static final int REFERENCE_WINDOW_DAYS = 7;

    private final CommerceEngine engine;
    private final HomeZones homes;
    private final OfficialMallClient mall;
    private final RmbConsignmentService rmb;
    private final RmbConsignmentService.TextGuard textGuard;
    private final SellerCreditService credits;
    private final ReferencePrice.Reader referencePrices;
    private final PlayerTradeGuard playerTradeGuard;
    private final LongSupplier clock;

    public CommerceClientService(CommerceEngine engine, HomeZones homes) {
        this(engine, homes, null, null, null, null, null, null, null);
    }
    public CommerceClientService(CommerceEngine engine, HomeZones homes, OfficialMallClient mall) {
        this(engine, homes, mall, null, null, null, null, null, null);
    }
    public CommerceClientService(CommerceEngine engine, HomeZones homes, OfficialMallClient mall, RmbConsignmentService rmb) {
        this(engine, homes, mall, rmb, null, null, null, null, null);
    }
    public CommerceClientService(CommerceEngine engine, HomeZones homes, OfficialMallClient mall, RmbConsignmentService rmb,
                                 RmbConsignmentService.TextGuard textGuard) {
        this(engine, homes, mall, rmb, textGuard, null, null, null, null);
    }
    public CommerceClientService(CommerceEngine engine, HomeZones homes, OfficialMallClient mall, RmbConsignmentService rmb,
                                 RmbConsignmentService.TextGuard textGuard, SellerCreditService credits,
                                 ReferencePrice.Reader referencePrices, PlayerTradeGuard playerTradeGuard,
                                 LongSupplier clock) {
        this.engine = engine;
        this.homes = homes;
        this.mall = mall;
        this.rmb = rmb;
        this.textGuard = textGuard == null ? RmbConsignmentService.TextGuard.ALLOW : textGuard;
        this.credits = credits != null ? credits : (engine == null ? null : engine.credits());
        this.referencePrices = referencePrices != null ? referencePrices : (engine == null ? null : engine.referencePrices());
        this.playerTradeGuard = playerTradeGuard != null ? playerTradeGuard : (engine == null ? null : engine.playerTradeGuard());
        this.clock = clock != null ? clock : (engine != null ? engine.clock : System::currentTimeMillis);
    }
    public Message handle(String method, com.game.api.proto.SessionContext session, Message input, Message prototype, Deadline d) {
        try {
            if (TradeMethods.CREDIT_REQUESTS.contains(method)) {
                if (session == null || session.getPlayerId() == 0) {
                    return rejection(prototype, INVALID);
                }
                if (TradeMethods.SUBMIT_SELLER_REVIEW.equals(method) && engine != null && session.getOwnerRevision() > 0) {
                    if (session.getAccount().isEmpty()) {
                        return rejection(prototype, INVALID);
                    }
                    engine.store.transaction(d, c -> {
                        com.game.common.player.PlayerSessionAuthority.lock(
                                c, session.getPlayerId(), session.getAccount(), session.getOwnerRevision(), 2);
                        return null;
                    });
                }
                return handleCredit(method, session.getPlayerId(), session.getAccount(), input, prototype, d);
            }
            if (!RmbConsignmentService.METHODS.contains(method)) {
                if (engine == null) return rejection(prototype, com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
                var authorized = new CommerceClientService(engine.forActor(session), homes, mall, rmb, textGuard,
                        credits, referencePrices, playerTradeGuard, clock);
                return authorized.handle(method, session.getPlayerId(), input, prototype, d);
            }
            if (rmb == null) return rejection(prototype, com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
            return rmb.client(method, session, input, d);
        }
        catch (Refusal denied) { return rejection(prototype, denied.tip()); }
        catch (SecurityException denied) { return rejection(prototype, CONFLICT); }
        catch (OfficialMallClient.HttpFailure failure) {
            return rejection(prototype, switch (failure.status()) { case 400 -> INVALID; case 404 -> MISSING;
                case 409 -> CONFLICT; default -> com.game.table.CommonErrorTip.common_error.kServiceUnavailable_VALUE; });
        }
        catch (IllegalArgumentException invalid) { return rejection(prototype, INVALID); }
        catch (Exception unknown) {
            if (unknown instanceof InterruptedException) Thread.currentThread().interrupt();
            return rejection(prototype, com.game.table.CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        }
    }
    public Message handle(String method, long me, Message input, Message prototype, Deadline d) {
        if (TradeMethods.CREDIT_REQUESTS.contains(method)) {
            return handleCredit(method, me, "", input, prototype, d);
        }
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
                return mall.client(method, engine.actor(), input, d);
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
                case "CreateConsignment" -> {
                    var in = (CreateConsignmentRequest) input;
                    if (in.getDesignatedBuyerPlayerId() != 0 && homes.homeZoneOf(in.getDesignatedBuyerPlayerId(), d) == 0)
                        throw new Refusal(com.game.table.TradeErrorTip.trade_error.kTradeCounterpartyUnknown_VALUE);
                    yield order(engine.consignment(me, in, d));
                }
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
                        var official = mall.list(engine.actor(), previous, limit(in.getLimit(), 100), d);
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
                case "CreateWantedSkillBookOrder" -> {
                    var in = (CreateWantedSkillBookOrderRequest) input;
                    var row = engine.createWantedSkillBookOrder(me, "", in, d);
                    yield wantedResponse(row, me);
                }
                case "FulfillWantedSkillBookOrder" -> {
                    var in = (FulfillWantedSkillBookOrderRequest) input;
                    var row = engine.fulfillWantedSkillBookOrder(
                            me, id(in.getOrderId()), in.getClientRequestId(), in.getSealedItemId(), d);
                    yield wantedResponse(row, me);
                }
                case "ListWantedSkillBookOrders" -> listWanted((ListWantedSkillBookOrdersRequest) input, me);
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
            .setDesignatedBuyerPlayerId(w.getDesignatedBuyerPlayerId())
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

    private WantedSkillBookOrderResponse wantedResponse(TradeCommerceRow r, long viewerId) throws SQLException {
        return WantedSkillBookOrderResponse.newBuilder()
                .setOrder(WantedSkillBook.view(r, CommerceEngine.workflow(r).build(), viewerId, engine.wantedSkills()))
                .setServerNowMs(engine.clock.getAsLong())
                .build();
    }

    private Message listWanted(ListWantedSkillBookOrdersRequest in, long viewerId) throws SQLException {
        if (engine.wantedSkills() == null) {
            throw new Refusal(WantedSkillBook.ERR_DISABLED);
        }
        int limit = in.getLimit() == 0 ? 20 : limit(in.getLimit(), 50);
        int filterSkill = in.getFilterSkillTableId();
        var out = ListWantedSkillBookOrdersResponse.newBuilder().setServerNowMs(engine.clock.getAsLong());
        if (in.getOnlyMine()) {
            var rows = engine.listMyWantedSkillBookOrders(viewerId, WantedSkillBook.MyCursor.parse(in.getCursor()), limit);
            var session = engine.actor();
            for (var r : rows) {
                var w = CommerceEngine.workflow(r).build();
                if (session != null && w.hasBuyerIdentity()
                        && (!session.getAccount().equals(w.getBuyerIdentity().getAccount())
                            || session.getOwnerRevision() != w.getBuyerIdentity().getOwnerRevision())) {
                    continue;
                }
                if (filterSkill != 0 && w.getWantedSkillBook().getSkillTableId() != filterSkill) {
                    continue;
                }
                out.addOrders(WantedSkillBook.view(r, w, viewerId, engine.wantedSkills()));
            }
            if (rows.size() == limit) {
                var last = rows.getLast();
                out.setNextCursor(new WantedSkillBook.MyCursor(last.getCreatedMs(), last.getCommerceId()).format());
            }
            return out.build();
        }
        var rows = engine.listOpenWantedSkillBookOrders(WantedSkillBook.BoardCursor.parse(in.getCursor()), limit);
        for (var r : rows) {
            var w = CommerceEngine.workflow(r).build();
            if (filterSkill != 0 && w.getWantedSkillBook().getSkillTableId() != filterSkill) {
                continue;
            }
            out.addOrders(WantedSkillBook.view(r, w, viewerId, engine.wantedSkills()));
        }
        if (rows.size() == limit) {
            var last = rows.getLast();
            out.setNextCursor(new WantedSkillBook.BoardCursor(last.getUnitPrice(), last.getCommerceId()).format());
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
                         + "AND market_id=1 AND item_config_id=? AND currency_type=? AND status=3 AND on_order_book=1 AND "
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

    private Message handleCredit(String method, long me, String account, Message input, Message prototype, Deadline d) {
        long nowMs = clock.getAsLong();
        try {
            CommerceStore.active(d);
            if (me == 0) {
                throw new Refusal(INVALID);
            }
            return switch (method) {
                case TradeMethods.GET_SELLER_CREDIT -> getSellerCredit(me, (GetSellerCreditRequest) input, nowMs);
                case TradeMethods.SUBMIT_SELLER_REVIEW ->
                        submitSellerReview(me, account, (SubmitSellerReviewRequest) input, nowMs, d);
                case TradeMethods.GET_MARKET_REFERENCE_PRICE ->
                        getMarketReferencePrice((GetMarketReferencePriceRequest) input, nowMs);
                case TradeMethods.GET_PLAYER_TRADE_QUOTA ->
                        getPlayerTradeQuota(me, (GetPlayerTradeQuotaRequest) input, nowMs);
                default -> rejection(prototype, com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
            };
        } catch (Refusal denied) {
            return creditRejection(prototype, denied.tip(), nowMs);
        } catch (SQLException | RuntimeException failure) {
            return creditRejection(prototype, com.game.table.CommonErrorTip.common_error.kServiceUnavailable_VALUE, nowMs);
        }
    }

    private SellerCreditResponse getSellerCredit(long me, GetSellerCreditRequest in, long nowMs) throws SQLException {
        if (credits == null) {
            throw new Refusal(com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
        }
        long targetId = in.getSellerPlayerId() == 0 ? me : in.getSellerPlayerId();
        SellerCreditService.Profile prof = credits.getProfile(targetId, nowMs);
        return SellerCreditResponse.newBuilder()
                .setProfile(SellerCreditService.toView(prof))
                .setServerNowMs(nowMs)
                .build();
    }

    private SubmitSellerReviewResponse submitSellerReview(
            long me, String account, SubmitSellerReviewRequest in, long nowMs, Deadline d) throws SQLException {
        if (credits == null || engine == null) {
            throw new Refusal(com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE);
        }
        SellerCreditService.Rating rating = SellerCreditService.Rating.fromCode(in.getRatingValue());
        if (rating == SellerCreditService.Rating.UNSPECIFIED) {
            throw new Refusal(INVALID);
        }
        String comment = in.getComment().trim();
        if (comment.codePointCount(0, comment.length()) > MAX_SELLER_REVIEW_COMMENT_CODEPOINTS) {
            throw new Refusal(INVALID);
        }
        if (!comment.isEmpty()) {
            int rejectTip;
            try {
                rejectTip = textGuard.checkTradeTitle(me, account == null ? "" : account, comment, d);
            } catch (RuntimeException unavailable) {
                throw new Refusal(TEXT_CHECK_UNAVAILABLE);
            }
            if (rejectTip != 0) {
                throw new Refusal(rejectTip);
            }
        }
        long orderNum = parseCommerceOrderId(in.getOrderId());
        TradeCommerceRow rec = engine.store.get(orderNum);
        if (rec == null) {
            throw new Refusal(MISSING);
        }
        TradeCommerceWorkflow wf = CommerceEngine.workflow(rec).build();
        var factOpt = SellerCreditService.settledTradeFact(rec, wf, rec.getUpdatedMs());
        if (factOpt.isEmpty()) {
            throw new Refusal(CONFLICT);
        }
        SellerCreditService.CompletedTradeFact fact = factOpt.get();
        if (fact.buyerPlayerId() != me) {
            throw new Refusal(MISSING);
        }
        // 换主后新账号不得替前任买家的历史成交打差评/改信誉分
        if (!fact.buyerAccount().isEmpty()
                && (account == null || account.isEmpty() || !fact.buyerAccount().equals(account))) {
            throw new Refusal(MISSING);
        }
        SellerCreditService.ReviewSubmission sub = credits.submitReview(
                new SellerCreditService.ReviewRequest(fact, rating, comment), nowMs);
        return SubmitSellerReviewResponse.newBuilder()
                .setCountedForScore(sub.review().countedForScore())
                .setReplayed(sub.replayed())
                .setProfile(SellerCreditService.toView(sub.profile()))
                .setServerNowMs(nowMs)
                .build();
    }

    private GetMarketReferencePriceResponse getMarketReferencePrice(GetMarketReferencePriceRequest in, long nowMs)
            throws SQLException {
        int itemConfigId = in.getItemConfigId();
        int currencyType = in.getCurrencyType();
        GetMarketReferencePriceResponse.Builder out = GetMarketReferencePriceResponse.newBuilder()
                .setItemConfigId(itemConfigId)
                .setCurrencyType(currencyType)
                .setWindowDays(REFERENCE_WINDOW_DAYS)
                .setServerNowMs(nowMs);
        if (itemConfigId == 0 || !isKnownCurrency(currencyType)) {
            return out.setErrorMessage(TipInfoMessage.newBuilder().setId(INVALID).build()).build();
        }
        if (referencePrices == null) {
            return out.setErrorMessage(TipInfoMessage.newBuilder()
                    .setId(com.game.table.TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE).build()).build();
        }
        ReferencePrice.View ref = referencePrices.referencePrice(itemConfigId, currencyType, nowMs);
        return out.setMedianUnitPrice(ref.medianUnitPrice())
                .setSampleCount(ref.sampleCount())
                .setInsufficientSample(ref.insufficientSample())
                .build();
    }

    private GetPlayerTradeQuotaResponse getPlayerTradeQuota(long me, GetPlayerTradeQuotaRequest in, long nowMs)
            throws SQLException {
        int currency = in.getCurrencyType();
        GetPlayerTradeQuotaResponse.Builder out = GetPlayerTradeQuotaResponse.newBuilder()
                .setCurrencyType(currency)
                .setServerNowMs(nowMs);
        if (!isKnownCurrency(currency)) {
            return out.setErrorMessage(TipInfoMessage.newBuilder().setId(INVALID).build()).build();
        }
        PlayerTradeGuard.Policy p = playerTradeGuard == null ? PlayerTradeGuard.Policy.disabled() : playerTradeGuard.policy();
        boolean acquaintance = PlayerTradeGuard.MODE_ACQUAINTANCE_LOCKED.equals(p.mode());
        long minFriendMs = p.minFriendDurationMs() == 0 && acquaintance
                ? PlayerTradeGuard.DEFAULT_MIN_FRIEND_DURATION_MS : p.minFriendDurationMs();
        int maxDevPct = p.maxReferenceDeviationPct() == 0 && acquaintance
                ? PlayerTradeGuard.DEFAULT_MAX_REFERENCE_DEVIATION_PCT : p.maxReferenceDeviationPct();
        long coolingLockMs = p.coolingLockDurationMs() == 0 && acquaintance
                ? PlayerTradeGuard.DEFAULT_COOLING_LOCK_DURATION_MS : p.coolingLockDurationMs();
        long limit = p.maxDailyCurrencyAmount();
        long used = 0L;
        if (playerTradeGuard != null && playerTradeGuard.dailyOut() != null) {
            long dayStart = Long.divideUnsigned(nowMs, PlayerTradeGuard.DAY_MS) * PlayerTradeGuard.DAY_MS;
            used = playerTradeGuard.dailyOut().dailyCurrencyOutflow(me, currency, dayStart);
        }
        long remaining = Long.compareUnsigned(limit, used) > 0 ? limit - used : 0L;
        List<Integer> sortedConsumables = p.consumableItems().stream().sorted(Integer::compareUnsigned).toList();
        return out.setMode(p.mode())
                .setDailyCurrencyLimit(limit)
                .setDailyCurrencyUsed(used)
                .setDailyCurrencyRemaining(remaining)
                .addAllConsumableItemIds(sortedConsumables)
                .setMinFriendDurationMs(minFriendMs)
                .setMaxReferenceDeviationPct(maxDevPct)
                .setCoolingLockDurationMs(coolingLockMs)
                .build();
    }

    private static boolean isKnownCurrency(int currencyType) {
        return currencyType >= 0 && CurrencyType.forNumber(currencyType) != null;
    }

    private static long parseCommerceOrderId(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("commerce:")) {
            trimmed = trimmed.substring("commerce:".length());
        }
        if (trimmed.isEmpty()) {
            throw new Refusal(INVALID);
        }
        try {
            long parsed = Long.parseUnsignedLong(trimmed);
            if (parsed == 0) {
                throw new Refusal(INVALID);
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new Refusal(INVALID);
        }
    }

    private static Message creditRejection(Message prototype, int tip, long nowMs) {
        var b = prototype.newBuilderForType();
        b.setField(prototype.getDescriptorForType().findFieldByName("error_message"),
                TipInfoMessage.newBuilder().setId(tip).build());
        var nowField = prototype.getDescriptorForType().findFieldByName("server_now_ms");
        if (nowField != null) {
            b.setField(nowField, nowMs);
        }
        return b.build();
    }
}
