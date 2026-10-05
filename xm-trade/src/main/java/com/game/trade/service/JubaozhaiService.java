package com.game.trade.service;

import com.game.common.deadline.Deadline;
import com.game.common.player.HomeZones;
import com.game.proto.trade.BrowseListingsRequest;
import com.game.proto.trade.BrowseListingsResponse;
import com.game.proto.trade.GetListingDetailRequest;
import com.game.proto.trade.GetListingDetailResponse;
import com.game.proto.trade.GetMyShelfRequest;
import com.game.proto.trade.GetMyShelfResponse;
import com.game.proto.trade.ListingDetail;
import com.game.proto.trade.ListingSummary;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SetFavoriteRequest;
import com.game.proto.trade.SetFavoriteResponse;
import com.game.trade.dispatch.TradeMethods;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.rules.ListingRules;
import com.game.trade.rules.ListingRules.PageWindow;
import com.game.trade.rules.TradeTip;
import com.game.trade.service.HomeZoneResolver.Resolution;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingQuery;
import com.game.trade.store.ListingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聚宝斋只读面的 4 个客户端方法（基线 JubaozhaiLogic，jubaozhai_logic.go:93-322；trade-spec §3）：浏览 196、详情 197、收藏 198、货架 200。
 *
 * <p><b>全局规则</b>（§0.7，每个方法都照做）：
 * <ul>
 *   <li><b>业务结果一律 in-band</b>：拒绝与故障都只设应答的 {@code error_message}（tip 只有 id、<b>永不带 parameters</b>，{@link TradeTip#proto()}），
 *       其余字段全是零值；唯一例外是 SetFavorite 拒绝时也回填请求里的 {@code listing_id}（:273-276）。成功应答<b>不设</b> {@code error_message}。</li>
 *   <li><b>故障只有 1003</b>：存储（{@link ListingStore} 抛出的任何 RuntimeException，基线「返回的 error 一律是存储故障」）、归属区查询、范围配置非法；
 *       打 ERROR 日志，player_id 只进日志不进指标（storeFault，:354-358）。</li>
 *   <li><b>整请求预算</b>：调用方（派发器）在受理时刻建好 {@link Deadline}（3500 ms），归属区查询与全部 MySQL 共用它，各自再叠加单次上限
 *       （归属区 1500 ms、单条 SQL 2000 ms，由 {@code PlayerHomeZones} / {@code JdbcListingStore} 实现）；预算到期由依赖抛出 → in-band 1003。</li>
 *   <li><b>一次请求只取一次时间</b>（nowMillis，:424-431，负值按 0）：阶段推导、SQL 时间窗、{@code server_now_ms} 用同一个值。浏览与货架在查归属区 / 查库之前取，
 *       详情在 GetListing 之前取，收藏只在「加收藏」分支取（§2.1）。</li>
 *   <li><b>身份只取会话</b>：{@code me} 来自 gate 填的 {@code SessionContext.player_id}，请求体里刻意没有 player_id。{@code me == 0} → 1005
 *       （基线「无会话」；Java 经 gate 不可达，派发层先回信封 1003，T6；服务层仍保留判定）。</li>
 *   <li>不推送、不读在线目录、不读写业务 Redis、不移动任何资产（:16）。</li>
 * </ul>
 * 所有 uint32 / uint64 字段按位模式处理（§5.9 第 1 条），枚举一律按整数值判（第 2 条）。
 *
 * <p>阻塞（JDBC），只在 trade-worker 线程上调用；线程安全（无可变状态）。不预期的 RuntimeException（实现 bug）原样抛给派发器，
 * 由它回 in-band 1003、计 internal_error。
 */
public final class JubaozhaiService {

    private static final Logger log = LoggerFactory.getLogger(JubaozhaiService.class);

    private final ListingStore store;
    private final HomeZoneResolver homeZones;
    private final MarketSettings market;
    private final LongSupplier clockMs;

    /**
     * @param homeZones 归属区查询（生产 {@code PlayerHomeZones}，单次上限 1500 ms）
     * @param clockMs   服务端时间（Unix 毫秒；生产 {@code System::currentTimeMillis}，测试注入固定时钟）
     */
    public JubaozhaiService(ListingStore store, HomeZones homeZones, TradeMetrics metrics, MarketSettings market,
                            LongSupplier clockMs) {
        this.store = store;
        this.homeZones = new HomeZoneResolver(homeZones, metrics);
        this.market = market;
        this.clockMs = clockMs;
    }

    // ================================================================ 196 BrowseListings（jubaozhai_logic.go:93-179）

    /**
     * 浏览公示 / 寄售列表。顺序：会话 → 全部纯校验（零 I/O）→ 竞价拒绝（零 I/O）→ 取一次 now → 按范围定分区 → COUNT → 钳制页码 → 分页查询 → 收藏标记。
     * 参数非法优先于竞价：竞价分区配非法 tab 回 1005（jubaozhai_logic_test.go:374-376）。
     */
    public BrowseListingsResponse browseListings(long me, BrowseListingsRequest in, Deadline deadline) {
        final String method = TradeMethods.BROWSE_LISTINGS;
        if (me == 0) {
            return rejectBrowse(TradeTip.NO_SESSION);
        }
        // Go 的短路顺序：NormalizeSearch → ValidTab → ValidSection → ValidCategory → ValidSort（:109-113）
        String search = ListingRules.normalizeSearch(in.getSearch());
        if (search == null || !ListingRules.validTab(in.getTabValue()) || !ListingRules.validSection(in.getSectionValue())
                || !ListingRules.validCategory(in.getCategoryValue(), in.getSubcategory())
                || !ListingRules.validSort(in.getSortValue())) {
            return rejectBrowse(TradeTip.INVALID_BROWSE_REQUEST);
        }
        if (ListingRules.isAuction(in.getSectionValue())) {
            return rejectBrowse(TradeTip.AUCTION_DISABLED);
        }

        MarketScope scope = market.scope();
        int pageSize = ListingRules.clampPageSize(in.getPageSize(), market.defaultPageSize(), market.maxPageSize());
        long nowMs = nowMillis();

        ListingQuery query = ListingQuery.of(in.getTabValue(), in.getCategoryValue(), nowMs)
                .withSubcategory(in.getSubcategory())
                .withSort(in.getSortValue());
        if (scope == MarketScope.MARKET_SCOPE_ZONE) {
            // zone 范围：分区只认调用者归属区，客户端传的 zone_filter 一律忽略（:131-137）
            Resolution zone = homeZones.resolve(me, deadline, method, TradeTip.HOME_ZONE_UNKNOWN);
            if (zone.rejected()) {
                return rejectBrowse(zone.reject());
            }
            query = query.withMarketZone(zone.zone());
        } else if (scope == MarketScope.MARKET_SCOPE_GLOBAL) {
            query = query.withMarketZone(in.getZoneFilter()); // 0 = 全部区（:138-139）
        } else {
            // 启动校验已保证走不到；万一走到，按故障拒绝而不是退化成全服可见（:140-144）
            log.error("[trade] {}: 市场范围 {} 非法，拒绝请求 player={}", method, scope, Long.toUnsignedString(me));
            return rejectBrowse(TradeTip.SCOPE_UNSPECIFIED);
        }
        if (!search.isEmpty()) {
            // 纯数字的搜索词同时按商品编号精确匹配；0 不是合法编号、不参与（:145-151）
            query = query.withSearch(ListingRules.likePattern(search), ListingRules.searchListingId(search));
        }
        if (in.getFavoritesOnly()) {
            query = query.withFavoritesOf(me);
        }

        long total;
        try {
            total = store.countListings(query, deadline);
        } catch (RuntimeException e) {
            return rejectBrowse(storeFault(method, "CountListings", me, e));
        }
        PageWindow window = ListingRules.pageWindow(total, in.getPage(), pageSize, market.maxPage());
        List<Listing> page;
        try {
            page = store.queryListings(query, window.offset(), pageSize, deadline);
        } catch (RuntimeException e) {
            return rejectBrowse(storeFault(method, "QueryListings", me, e));
        }
        Set<Long> favorites;
        try {
            favorites = favoriteSet(me, page, deadline);
        } catch (RuntimeException e) {
            return rejectBrowse(storeFault(method, "FavoriteIDs", me, e));
        }
        return BrowseListingsResponse.newBuilder()
                .addAllListings(toSummaries(page, nowMs, me, favorites))
                .setTotalCount(ListingRules.totalCount(total))
                .setPage(window.page())
                .setPageSize(pageSize)
                .setPageCount(window.pageCount())
                .setMarketScope(scope)
                .setServerNowMs(nowMs)
                .build();
    }

    // ================================================================ 200 GetMyShelf（jubaozhai_logic.go:181-220）

    /**
     * 调用者自己上架的商品：任意状态（已结束显示 ENDED）、{@code listing_id DESC}、{@code is_mine} 恒真。不查归属区、不受市场范围约束；
     * 同样受 MaxPage 封顶。应答<b>没有</b> {@code market_scope}（jubaozhai.proto:144-152）。
     */
    public GetMyShelfResponse getMyShelf(long me, GetMyShelfRequest in, Deadline deadline) {
        final String method = TradeMethods.GET_MY_SHELF;
        if (me == 0) {
            return rejectShelf(TradeTip.NO_SESSION);
        }
        int pageSize = ListingRules.clampPageSize(in.getPageSize(), market.defaultPageSize(), market.maxPageSize());
        long nowMs = nowMillis();

        long total;
        try {
            total = store.countSellerListings(me, deadline);
        } catch (RuntimeException e) {
            return rejectShelf(storeFault(method, "CountSellerListings", me, e));
        }
        PageWindow window = ListingRules.pageWindow(total, in.getPage(), pageSize, market.maxPage());
        List<Listing> page;
        try {
            page = store.querySellerListings(me, window.offset(), pageSize, deadline);
        } catch (RuntimeException e) {
            return rejectShelf(storeFault(method, "QuerySellerListings", me, e));
        }
        Set<Long> favorites;
        try {
            favorites = favoriteSet(me, page, deadline);
        } catch (RuntimeException e) {
            return rejectShelf(storeFault(method, "FavoriteIDs", me, e));
        }
        return GetMyShelfResponse.newBuilder()
                .addAllListings(toSummaries(page, nowMs, me, favorites))
                .setTotalCount(ListingRules.totalCount(total))
                .setPage(window.page())
                .setPageSize(pageSize)
                .setPageCount(window.pageCount())
                .setServerNowMs(nowMs)
                .build();
    }

    // ================================================================ 197 GetListingDetail（jubaozhai_logic.go:222-258）

    /** 单件商品详情。不存在、已结束、或 zone 范围下属于别区 → 20000；卖家本人始终可见、不查归属区。拒绝时不带 detail。 */
    public GetListingDetailResponse getListingDetail(long me, GetListingDetailRequest in, Deadline deadline) {
        final String method = TradeMethods.GET_LISTING_DETAIL;
        if (me == 0) {
            return rejectDetail(TradeTip.NO_SESSION);
        }
        long listingId = in.getListingId();
        if (listingId == 0) {
            return rejectDetail(TradeTip.LISTING_ID_ZERO);
        }
        long nowMs = nowMillis();

        Visible visible = loadVisibleListing(method, me, listingId, nowMs, deadline);
        if (visible.reject() != null) {
            return rejectDetail(visible.reject());
        }
        boolean favorite;
        try {
            favorite = store.favoriteExists(me, listingId, deadline);
        } catch (RuntimeException e) {
            return rejectDetail(storeFault(method, "FavoriteExists", me, e));
        }
        Listing listing = visible.listing();
        return GetListingDetailResponse.newBuilder()
                .setDetail(ListingDetail.newBuilder()
                        .setSummary(toSummary(listing, nowMs, me, favorite))
                        .setDescription(listing.description()))
                .setServerNowMs(nowMs)
                .build();
    }

    // ================================================================ 198 SetFavorite（jubaozhai_logic.go:260-322）

    /**
     * 收藏 / 取消收藏。所有应答（含拒绝）都带回请求里的 {@code listing_id}。
     * <ul>
     *   <li>取消：直接 DELETE，幂等，不查商品、不查归属区、不取时间——已下架 / 看不见的商品的收藏也必须能删掉；</li>
     *   <li>收藏：商品必须对调用者可见（规则同详情）→ 已收藏直接回 true（<b>不计数、不判上限</b>）→ 计数 ≥ 上限回 20002 → 幂等写入。</li>
     * </ul>
     * 上限是<b>软上限</b>：计数与写入之间没有锁，同一玩家的并发收藏最多超出「在途请求数」条（:265-268；N3 不采纳）。计数包括已经看不见的收藏（Q8）。
     */
    public SetFavoriteResponse setFavorite(long me, SetFavoriteRequest in, Deadline deadline) {
        final String method = TradeMethods.SET_FAVORITE;
        long listingId = in.getListingId();
        if (me == 0) {
            return rejectFavorite(listingId, TradeTip.NO_SESSION);
        }
        if (listingId == 0) {
            return rejectFavorite(listingId, TradeTip.LISTING_ID_ZERO);
        }

        if (!in.getFavorite()) {
            try {
                store.deleteFavorite(me, listingId, deadline);
            } catch (RuntimeException e) {
                return rejectFavorite(listingId, storeFault(method, "DeleteFavorite", me, e));
            }
            return acceptFavorite(listingId, false);
        }

        long nowMs = nowMillis();
        Visible visible = loadVisibleListing(method, me, listingId, nowMs, deadline);
        if (visible.reject() != null) {
            return rejectFavorite(listingId, visible.reject());
        }
        boolean exists;
        try {
            exists = store.favoriteExists(me, listingId, deadline);
        } catch (RuntimeException e) {
            return rejectFavorite(listingId, storeFault(method, "FavoriteExists", me, e));
        }
        if (exists) {
            return acceptFavorite(listingId, true);
        }
        long count;
        try {
            count = store.countFavorites(me, deadline);
        } catch (RuntimeException e) {
            return rejectFavorite(listingId, storeFault(method, "CountFavorites", me, e));
        }
        if (ListingRules.favoriteLimitReached(count, market.maxFavoritesPerPlayer())) {
            return rejectFavorite(listingId, TradeTip.FAVORITE_LIMIT_REACHED);
        }
        try {
            store.insertFavorite(me, listingId, nowMs, deadline);
        } catch (RuntimeException e) {
            return rejectFavorite(listingId, storeFault(method, "InsertFavorite", me, e));
        }
        return acceptFavorite(listingId, true);
    }

    // ================================================================ 应答形状（派发层的 in-band 失败也用这几个）

    /** 浏览的拒绝 / 故障应答：只设 error_message（:101-103）。 */
    public static BrowseListingsResponse rejectBrowse(TradeTip tip) {
        return BrowseListingsResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    /** 详情的拒绝 / 故障应答：只设 error_message，不带 detail（:228-230）。 */
    public static GetListingDetailResponse rejectDetail(TradeTip tip) {
        return GetListingDetailResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    /** 收藏的拒绝 / 故障应答：error_message + 请求里的 listing_id，favorite = false（:274-276）。 */
    public static SetFavoriteResponse rejectFavorite(long listingId, TradeTip tip) {
        return SetFavoriteResponse.newBuilder().setErrorMessage(tip.proto()).setListingId(listingId).build();
    }

    /** 货架的拒绝 / 故障应答：只设 error_message（:186-188）。 */
    public static GetMyShelfResponse rejectShelf(TradeTip tip) {
        return GetMyShelfResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    private static SetFavoriteResponse acceptFavorite(long listingId, boolean favorite) {
        return SetFavoriteResponse.newBuilder().setListingId(listingId).setFavorite(favorite).build();
    }

    // ================================================================ 公共件（jubaozhai_logic.go:324-438）

    /** 取到的商品，或拒绝它的 tip（二者恰有一个非空）。 */
    private record Visible(Listing listing, TradeTip reject) {

        static Visible of(Listing listing) {
            return new Visible(listing, null);
        }

        static Visible rejected(TradeTip tip) {
            return new Visible(null, tip);
        }
    }

    /**
     * 取商品并按详情可见性判定（loadVisibleListing，:324-352）：GetListing（不存在 → 20000；失败 → 1003）→ 卖家豁免（不看状态、时间、分区，
     * 也不查归属区）→ ZONE 下查归属区（未映射 → 20001；失败 → 1003）→ VisibleToBuyer（否 → 20000，不泄露别区有哪些商品）。
     * 所以「查一件不存在的商品」不会去查归属区，没有归属区的玩家查不存在的商品得到 20000 而不是 20001（§2.4）。
     */
    private Visible loadVisibleListing(String method, long me, long listingId, long nowMs, Deadline deadline) {
        Optional<Listing> found;
        try {
            found = store.getListing(listingId, deadline);
        } catch (RuntimeException e) {
            return Visible.rejected(storeFault(method, "GetListing", me, e));
        }
        if (found.isEmpty()) {
            return Visible.rejected(TradeTip.LISTING_NOT_FOUND);
        }
        Listing listing = found.get();
        if (listing.sellerPlayerId() == me) {
            return Visible.of(listing);
        }
        MarketScope scope = market.scope();
        int callerHomeZone = 0;
        if (scope == MarketScope.MARKET_SCOPE_ZONE) {
            Resolution zone = homeZones.resolve(me, deadline, method, TradeTip.HOME_ZONE_UNKNOWN);
            if (zone.rejected()) {
                return Visible.rejected(zone.reject());
            }
            callerHomeZone = zone.zone();
        }
        if (!ListingRules.visibleToBuyer(listing, nowMs, scope, callerHomeZone)) {
            return Visible.rejected(TradeTip.LISTING_NOT_VISIBLE);
        }
        return Visible.of(listing);
    }

    /** 本页商品里调用者收藏过的（favoriteSet，:379-389）：空页返回空集合、不查库。 */
    private Set<Long> favoriteSet(long me, List<Listing> page, Deadline deadline) {
        if (page.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = new ArrayList<>(page.size());
        for (Listing listing : page) {
            ids.add(listing.listingId());
        }
        return store.favoriteIds(me, ids, deadline);
    }

    private static List<ListingSummary> toSummaries(List<Listing> page, long nowMs, long me, Set<Long> favorites) {
        List<ListingSummary> out = new ArrayList<>(page.size());
        for (Listing listing : page) {
            out.add(toSummary(listing, nowMs, me, favorites.contains(listing.listingId())));
        }
        return out;
    }

    /**
     * 存储行 → 客户端可见的摘要（toSummary，:399-418）。刻意不带 seller_player_id / seller_account（设计 §9）：客户端只需要知道「是不是我」。
     * phase 用本请求唯一一次取到的 now 推导；类目写整数值（{@code setCategoryValue}，不经 UNRECOGNIZED，§5.9 第 2 条）。
     */
    static ListingSummary toSummary(Listing listing, long nowMs, long me, boolean favorite) {
        return ListingSummary.newBuilder()
                .setListingId(listing.listingId())
                .setCategoryValue(listing.category())
                .setSubcategory(listing.subcategory())
                .setTitle(listing.title())
                .setLevel(listing.level())
                .setPriceFen(listing.priceFen())
                .setPhase(ListingRules.phase(listing, nowMs))
                .setNoticeEndMs(listing.noticeEndMs())
                .setSaleEndMs(listing.saleEndMs())
                .setMarketZone(listing.marketZone())
                .setIsFavorite(favorite)
                .setIsMine(listing.sellerPlayerId() == me)
                .setSummary(listing.summary())
                .setIconKey(listing.iconKey())
                .build();
    }

    /** 记一条存储故障日志并返回 1003（storeFault，:354-358）。player_id 只进日志，不进指标。 */
    private static TradeTip storeFault(String method, String op, long me, RuntimeException e) {
        log.error("[trade] {}: {} 失败 player={}", method, op, Long.toUnsignedString(me), e);
        return TradeTip.STORE_FAULT;
    }

    /** 取一次时间（nowMillis，:424-431）：时钟早于 1970 时按 0（不会发生，只防 uint64 回绕）。 */
    private long nowMillis() {
        return Math.max(0, clockMs.getAsLong());
    }
}
