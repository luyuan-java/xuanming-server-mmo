package com.game.trade.dispatch;

import java.util.List;

/**
 * 聚宝斋的方法名（{@code message_id.txt} 的键 = 服务裸名 + 方法名；trade-spec §0.2）。消息号一律从 {@code MessageIdRegistry} 按名字解析，
 * 代码里不写数字。
 *
 * <ul>
 *   <li>{@link #SERVICE}（jubaozhai.proto:154-161）标了 {@code OptionIsClientProtocolService}：gate 把它的 4 个号路由到 group {@code trade}；</li>
 *   <li>{@link #ADMIN_SERVICE}（trade_admin.proto:43-45）刻意<b>不是</b>客户端服务：199 经 gate 走「路由为空」被丢弃（§0.8），理论上到不了这里；
 *       万一到了，派发器回信封 1003、计 forbidden（基线会话白名单 PermissionDenied，session.go:81-84）。Java 的播种走管理端口 HTTP（T5）。</li>
 * </ul>
 */
public final class TradeMethods {

    /** proto/trade/jubaozhai.proto 的客户端服务名。 */
    public static final String SERVICE = "ClientPlayerJubaozhai";

    /** 196：浏览公示 / 寄售列表（jubaozhai.proto:157）。 */
    public static final String BROWSE_LISTINGS = "BrowseListings";
    /** 197：商品详情（jubaozhai.proto:158）。 */
    public static final String GET_LISTING_DETAIL = "GetListingDetail";
    /** 198：收藏 / 取消收藏（jubaozhai.proto:159）。 */
    public static final String SET_FAVORITE = "SetFavorite";
    /** 200：我的货架（jubaozhai.proto:160）。 */
    public static final String GET_MY_SHELF = "GetMyShelf";

    /** 客户端可发的 4 个方法（基线会话白名单 ClientMethods，session.go:40-47）。 */
    public static final List<String> COMMERCE_REQUESTS = List.of("ListOfficialSkus","CreateOfficialOrder","GetCommerceOrder","CancelCommerceOrder","ListCommerceOrders","CreatePlayerTrade","ConfirmPlayerTrade","CreateConsignment","BuyConsignment","CancelConsignment","PlaceMarketOrder","CancelMarketOrder","ListMarketOrders","GetMarketDepth","ListTradeMarkets","CreateAuctionLot","PlaceAuctionBid","GetAuctionLot","CancelAuctionLot","BrowseCommerceOffers","CreateWantedSkillBookOrder","FulfillWantedSkillBookOrder","ListWantedSkillBookOrders");
    public static final List<String> RMB_REQUESTS = List.of("CreateRmbConsignment", "GetRmbConsignment", "ListRmbConsignments",
        "BuyRmbConsignment", "CancelRmbConsignment", "GetRmbOrder", "ListRmbOrders", "CancelRmbOrder",
        "ListSellerIncomes", "CreateWithdrawal", "GetWithdrawal");
    /** 481-484：卖家店铺信用查询、买家成交评价、近 7 日中位参考价与当面交易限额查询。 */
    public static final String GET_SELLER_CREDIT = "GetSellerCredit";
    public static final String SUBMIT_SELLER_REVIEW = "SubmitSellerReview";
    public static final String GET_MARKET_REFERENCE_PRICE = "GetMarketReferencePrice";
    public static final String GET_PLAYER_TRADE_QUOTA = "GetPlayerTradeQuota";
    public static final List<String> CREDIT_REQUESTS = List.of(
        GET_SELLER_CREDIT, SUBMIT_SELLER_REVIEW, GET_MARKET_REFERENCE_PRICE, GET_PLAYER_TRADE_QUOTA);
    public static final List<String> CLIENT_REQUESTS = java.util.stream.Stream.of(
        List.of(BROWSE_LISTINGS, GET_LISTING_DETAIL, SET_FAVORITE, GET_MY_SHELF),
        COMMERCE_REQUESTS, RMB_REQUESTS, CREDIT_REQUESTS).flatMap(List::stream).toList();

    /** proto/trade/trade_admin.proto 的内部服务名。 */
    public static final String ADMIN_SERVICE = "TradeAdmin";
    /** 199：dev 播种（内部；Java 走 {@code POST /admin/trade/seed-listing}）。也是 {@code game.trade.requests} 里 forbidden 的 method 标签。 */
    public static final String SEED_LISTING = "SeedListing";
    /** 496：内部环境信号上报（Java 走 {@code POST /admin/trade/seller-environment}）。 */
    public static final String RECORD_SELLER_ENVIRONMENT = "RecordSellerEnvironment";
    /** 497：内部核实违约记账（Java 走 {@code POST /admin/trade/seller-violation}）。 */
    public static final String RECORD_SELLER_VIOLATION = "RecordSellerViolation";
    /** 禁止客户端上行的 TradeAdmin 内部方法集合（上行一律回信封 1003、计 forbidden）。 */
    public static final List<String> FORBIDDEN_ADMIN_METHODS = List.of(
        SEED_LISTING, RECORD_SELLER_ENVIRONMENT, RECORD_SELLER_VIOLATION);

    private TradeMethods() {
    }
}
