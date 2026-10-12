package com.game.trade.service;

import static com.game.trade.service.TradeServiceFixture.BUDGET_MS;
import static com.game.trade.service.TradeServiceFixture.HOUR_MS;
import static com.game.trade.service.TradeServiceFixture.MAX_FAVORITES;
import static com.game.trade.service.TradeServiceFixture.NOW_MS;
import static com.game.trade.service.TradeServiceFixture.PLAYER_A;
import static com.game.trade.service.TradeServiceFixture.PLAYER_B;
import static com.game.trade.service.TradeServiceFixture.PLAYER_C;
import static com.game.trade.service.TradeServiceFixture.PLAYER_U;
import static com.game.trade.service.TradeServiceFixture.ZONE_A;
import static com.game.trade.service.TradeServiceFixture.ZONE_B;
import static com.game.trade.service.TradeServiceFixture.browse;
import static com.game.trade.service.TradeServiceFixture.budget;
import static com.game.trade.service.TradeServiceFixture.onSale;
import static com.game.trade.service.TradeServiceFixture.storeDown;
import static com.game.trade.service.TradeServiceFixture.withStatus;
import static com.game.trade.service.TradeServiceFixture.withWindow;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.TipInfoMessage;
import com.game.proto.trade.BrowseListingsRequest;
import com.game.proto.trade.BrowseListingsResponse;
import com.game.proto.trade.GetListingDetailRequest;
import com.game.proto.trade.GetListingDetailResponse;
import com.game.proto.trade.GetMyShelfRequest;
import com.game.proto.trade.GetMyShelfResponse;
import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingPhase;
import com.game.proto.trade.ListingSection;
import com.game.proto.trade.ListingSort;
import com.game.proto.trade.ListingSummary;
import com.game.proto.trade.ListingTab;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SetFavoriteRequest;
import com.game.proto.trade.SetFavoriteResponse;
import com.game.trade.rules.TradeLimits;
import com.game.trade.rules.TradeTips;
import com.game.trade.service.FakeListingStore.FavoriteKey;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingStatuses;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 聚宝斋 4 个客户端方法的服务层单测（移植基线 jubaozhai_logic_test.go:316-980；trade-spec §9.2）：存储、归属区全部经接口注入假实现，时间固定。
 * 另加 Java 增项：应答形状纪律（成功不设 error_message、拒绝只设 error_message、tip 不带 parameters）、uint32 位模式、范围未指定的 fail-closed、
 * 归属区查询计数。
 */
class JubaozhaiServiceTest {

    private final TradeServiceFixture f = new TradeServiceFixture();

    private JubaozhaiService zone() {
        return f.service(MarketScope.MARKET_SCOPE_ZONE);
    }

    private JubaozhaiService global() {
        return f.service(MarketScope.MARKET_SCOPE_GLOBAL);
    }

    private static void assertRejectOnly(com.google.protobuf.Message response, int code) {
        FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
        TipInfoMessage tip = (TipInfoMessage) response.getField(field);
        assertThat(tip.getId()).isEqualTo(code);
        assertThat(tip.getParametersList()).as("tip 永不带 parameters").isEmpty();
        // 其余字段全是零值（SetFavorite 的 listing_id 例外，由调用方另行断言）
        for (FieldDescriptor other : response.getDescriptorForType().getFields()) {
            if (other.equals(field) || other.getName().equals("listing_id")) {
                continue;
            }
            if (other.isRepeated()) {
                assertThat(response.getRepeatedFieldCount(other)).as(other.getName()).isZero();
            } else {
                assertThat(response.hasField(other)).as(other.getName() + " 应是零值").isFalse();
            }
        }
    }

    // ================================================================ 无会话（jubaozhai_logic_test.go:316-345）

    @Test
    void 无会话_四个方法都回1005_不碰库不查归属区() {
        JubaozhaiService s = zone();

        assertRejectOnly(s.browseListings(0, browse().build(), budget()), TradeTips.INVALID_PARAMETER);
        assertRejectOnly(s.getListingDetail(0, GetListingDetailRequest.newBuilder().setListingId(1).build(), budget()),
                TradeTips.INVALID_PARAMETER);
        SetFavoriteResponse fav = s.setFavorite(0, SetFavoriteRequest.newBuilder().setListingId(1).setFavorite(true).build(), budget());
        assertRejectOnly(fav, TradeTips.INVALID_PARAMETER);
        assertThat(fav.getListingId()).as("拒绝也回填 listing_id").isEqualTo(1);
        assertRejectOnly(s.getMyShelf(0, GetMyShelfRequest.getDefaultInstance(), budget()), TradeTips.INVALID_PARAMETER);

        assertThat(f.store.calls).isEmpty();
        assertThat(f.homes.calls).isEmpty();
    }

    // ================================================================ 浏览（:351-611）

    @Test
    void 浏览_纯校验矩阵_一律1005且零IO() {
        List<Consumer<BrowseListingsRequest.Builder>> cases = List.of(
                b -> b.setTab(ListingTab.LISTING_TAB_UNSPECIFIED),
                b -> b.setTabValue(3),
                b -> b.setTabValue(-1),
                b -> b.setSection(ListingSection.LISTING_SECTION_UNSPECIFIED),
                b -> b.setSectionValue(3),
                b -> b.setCategory(ListingCategory.LISTING_CATEGORY_UNSPECIFIED),
                b -> b.setCategoryValue(10),
                b -> b.setCategoryValue(-5),
                b -> b.setSubcategory(6),                                   // 武器子类越界
                b -> b.setSubcategory(0xFFFF_FFFF),                         // uint32 位模式：-1 不能被当成 ≤ 上限
                b -> b.setCategory(ListingCategory.LISTING_CATEGORY_SET).setSubcategory(1),
                b -> b.setSortValue(5),
                b -> b.setSortValue(-1),
                b -> b.setSearch("剑".repeat(TradeLimits.MAX_SEARCH_RUNES + 1)),
                b -> b.setSearch("a\nb"),
                b -> b.setSearch("a\u001cb"),                               // U+001C 不是 Go 空白、是控制字符
                b -> b.setSearch("a\uD800"),                                // 未配对代理（Java 对应「非法 UTF-8」）
                b -> b.setSection(ListingSection.LISTING_SECTION_AUCTION).setTab(ListingTab.LISTING_TAB_UNSPECIFIED));
        for (int i = 0; i < cases.size(); i++) {
            TradeServiceFixture fx = new TradeServiceFixture();
            BrowseListingsRequest.Builder in = browse();
            cases.get(i).accept(in);

            BrowseListingsResponse resp = fx.service(MarketScope.MARKET_SCOPE_ZONE).browseListings(PLAYER_B, in.build(), budget());

            assertThat(resp.getErrorMessage().getId()).as("用例 %d", i).isEqualTo(TradeTips.INVALID_PARAMETER);
            assertThat(fx.store.calls).as("用例 %d 不碰库", i).isEmpty();
            assertThat(fx.homes.calls).as("用例 %d 不查归属区", i).isEmpty();
        }
    }

    @Test
    void 浏览_合法边界_64字搜索与可trim空白() {
        for (String search : List.of("剑".repeat(TradeLimits.MAX_SEARCH_RUNES), "abc\n", " abc　", "   ")) {
            TradeServiceFixture fx = new TradeServiceFixture();
            BrowseListingsResponse resp = fx.service(MarketScope.MARKET_SCOPE_GLOBAL)
                    .browseListings(PLAYER_B, browse().setSearch(search).build(), budget());
            assertThat(resp.hasErrorMessage()).as(search).isFalse();
        }
    }

    @Test
    void 浏览_竞价分区回20003_零IO() {
        BrowseListingsResponse resp = zone().browseListings(PLAYER_B,
                browse().setSection(ListingSection.LISTING_SECTION_AUCTION).build(), budget());

        assertRejectOnly(resp, TradeTips.FEATURE_DISABLED);
        assertThat(f.store.calls).isEmpty();
        assertThat(f.homes.calls).isEmpty();
    }

    @Test
    void 浏览_zone范围按调用者归属区过滤_忽略zone_filter() {
        BrowseListingsResponse resp = zone().browseListings(PLAYER_B, browse().setZoneFilter(ZONE_B).build(), budget());

        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(f.store.lastQuery.marketZone()).isEqualTo(ZONE_A);
        assertThat(f.homes.calls).containsExactly(PLAYER_B);
        assertThat(resp.getMarketScope()).isEqualTo(MarketScope.MARKET_SCOPE_ZONE);
        assertThat(resp.getServerNowMs()).isEqualTo(NOW_MS);
        assertThat(f.homeLookups("ok")).isEqualTo(1);
    }

    @Test
    void 浏览_global范围遵守zone_filter_不查归属区() {
        for (int filter : new int[] {0, ZONE_B, 0xFFFF_FFFF}) {
            TradeServiceFixture fx = new TradeServiceFixture();
            BrowseListingsResponse resp = fx.service(MarketScope.MARKET_SCOPE_GLOBAL)
                    .browseListings(PLAYER_B, browse().setZoneFilter(filter).build(), budget());

            assertThat(resp.hasErrorMessage()).isFalse();
            assertThat(fx.store.lastQuery.marketZone()).isEqualTo(filter);
            assertThat(fx.homes.calls).isEmpty();
            assertThat(resp.getMarketScope()).isEqualTo(MarketScope.MARKET_SCOPE_GLOBAL);
        }
    }

    @Test
    void 浏览_归属区未映射20001_故障1003_都不碰库() {
        BrowseListingsResponse unmapped = zone().browseListings(PLAYER_U, browse().build(), budget());
        assertRejectOnly(unmapped, TradeTips.HOME_ZONE_UNKNOWN);
        assertThat(f.store.calls).isEmpty();
        assertThat(f.homeLookups("unmapped")).isEqualTo(1);

        f.homes.failure = new DependencyException("读玩家资料失败");
        BrowseListingsResponse fault = zone().browseListings(PLAYER_B, browse().build(), budget());
        assertRejectOnly(fault, TradeTips.SERVICE_UNAVAILABLE);
        assertThat(f.store.calls).isEmpty();
        assertThat(f.homeLookups("error")).isEqualTo(1);
    }

    @Test
    void 浏览_范围未指定按故障拒绝_不退化成全服可见() {
        BrowseListingsResponse resp = f.service(MarketScope.MARKET_SCOPE_UNSPECIFIED).browseListings(PLAYER_B, browse().build(), budget());

        assertRejectOnly(resp, TradeTips.SERVICE_UNAVAILABLE);
        assertThat(f.store.calls).isEmpty();
        assertThat(f.homes.calls).isEmpty();
    }

    @Test
    void 浏览_分页矩阵() {
        record Case(long total, int page, int pageSize, int wantPage, int wantSize, int wantCount, long wantOffset, int wantTotal) {
        }
        List<Case> cases = List.of(
                new Case(45, 0, 0, 1, 20, 3, 0, 45),                 // 默认页长与首页
                new Case(45, 9999, 50, 3, 20, 3, 40, 45),            // 页长超上限钳到 20，页码超末页按末页
                new Case(9, 2, 4, 2, 4, 3, 4, 9),                    // 客户端显式页长 4
                new Case(0, 5, 10, 1, 10, 1, 0, 0),                  // 空结果
                new Case(100_000, 500, 20, 100, 20, 5000, 1980, 100_000),  // 页码上限 100，page_count 不封顶（N1）
                new Case(45, 0xFFFF_FFFF, 0xFFFF_FFFF, 3, 20, 3, 40, 45), // uint32 位模式：-1 不能当成小于上限
                new Case(1L << 32, 1, 20, 1, 20, (int) ((1L << 32) / 20 + 1), 0, 0xFFFF_FFFF)); // total_count 饱和
        for (Case c : cases) {
            TradeServiceFixture fx = new TradeServiceFixture();
            fx.store.total = c.total();
            BrowseListingsResponse resp = fx.service(MarketScope.MARKET_SCOPE_GLOBAL)
                    .browseListings(PLAYER_B, browse().setPage(c.page()).setPageSize(c.pageSize()).build(), budget());

            assertThat(resp.hasErrorMessage()).as(c.toString()).isFalse();
            assertThat(resp.getPage()).as(c.toString()).isEqualTo(c.wantPage());
            assertThat(resp.getPageSize()).as(c.toString()).isEqualTo(c.wantSize());
            assertThat(resp.getPageCount()).as(c.toString()).isEqualTo(c.wantCount());
            assertThat(resp.getTotalCount()).as(c.toString()).isEqualTo(c.wantTotal());
            assertThat(fx.store.lastOffset).as(c.toString()).isEqualTo(c.wantOffset());
            assertThat(fx.store.lastLimit).as("空结果照样执行分页查询 " + c).isEqualTo(c.wantSize());
        }
    }

    @Test
    void 浏览_查询带上请求与时钟() {
        global().browseListings(PLAYER_B, browse()
                .setTab(ListingTab.LISTING_TAB_PUBLIC_NOTICE)
                .setSort(ListingSort.LISTING_SORT_REMAINING_ASC)
                .setSubcategory(3)
                .setFavoritesOnly(true).build(), budget());

        var q = f.store.lastQuery;
        assertThat(q.tab()).isEqualTo(ListingTab.LISTING_TAB_PUBLIC_NOTICE_VALUE);
        assertThat(q.sort()).as("REMAINING 的具体列由存储层按 tab 选").isEqualTo(ListingSort.LISTING_SORT_REMAINING_ASC_VALUE);
        assertThat(q.category()).isEqualTo(ListingCategory.LISTING_CATEGORY_WEAPON_VALUE);
        assertThat(q.subcategory()).isEqualTo(3);
        assertThat(q.nowMs()).isEqualTo(NOW_MS);
        assertThat(q.favoritesOf()).as("favorites_only 按调用者过滤").isEqualTo(PLAYER_B);
        assertThat(q.titleLikePattern()).isEmpty();

        TradeServiceFixture fx = new TradeServiceFixture();
        fx.service(MarketScope.MARKET_SCOPE_GLOBAL).browseListings(PLAYER_B, browse().build(), budget());
        assertThat(fx.store.lastQuery.favoritesOf()).isZero();
    }

    @Test
    void 浏览_搜索矩阵() {
        record Case(String search, String pattern, long id) {
        }
        List<Case> cases = List.of(
                new Case("", "", 0),
                new Case("   ", "", 0),
                new Case("  123  ", "%123%", 123),
                new Case("青锋", "%青锋%", 0),
                new Case("a_b%c!", "%a!_b!%c!!%", 0),
                new Case("0", "%0%", 0),
                new Case("007", "%007%", 7),
                new Case("+7", "%+7%", 0),
                new Case("１２３", "%１２３%", 0),
                new Case("SMK-1700000000-", "%SMK-1700000000-%", 0),
                new Case("18446744073709551615", "%18446744073709551615%", -1L),     // = uint64 上限，按编号
                new Case("99999999999999999999", "%99999999999999999999%", 0));     // 超出 uint64，只按标题
        for (Case c : cases) {
            TradeServiceFixture fx = new TradeServiceFixture();
            BrowseListingsResponse resp = fx.service(MarketScope.MARKET_SCOPE_GLOBAL)
                    .browseListings(PLAYER_B, browse().setSearch(c.search()).build(), budget());

            assertThat(resp.hasErrorMessage()).as(c.search()).isFalse();
            assertThat(fx.store.lastQuery.titleLikePattern()).as(c.search()).isEqualTo(c.pattern());
            assertThat(fx.store.lastQuery.searchListingId()).as(c.search()).isEqualTo(c.id());
        }
    }

    @Test
    void 浏览_摘要的is_mine_is_favorite_phase与透传字段() {
        Listing mine = onSale(11, PLAYER_B, ZONE_A);
        Listing notice = withWindow(onSale(12, PLAYER_A, ZONE_A), NOW_MS + HOUR_MS, NOW_MS + 2 * HOUR_MS);
        f.store.page = List.of(mine, notice);
        f.store.total = 2;
        f.store.favorites.put(new FavoriteKey(PLAYER_B, 12), NOW_MS);

        BrowseListingsResponse resp = zone().browseListings(PLAYER_B, browse().build(), budget());

        assertThat(resp.hasErrorMessage()).as("成功不设 error_message").isFalse();
        assertThat(resp.getListingsList()).hasSize(2);
        assertThat(f.store.lastFavoriteLookup).containsExactly(11L, 12L);
        ListingSummary first = resp.getListings(0);
        ListingSummary second = resp.getListings(1);
        assertThat(first.getIsMine()).isTrue();
        assertThat(first.getIsFavorite()).isFalse();
        assertThat(first.getPhase()).isEqualTo(ListingPhase.LISTING_PHASE_ON_SALE);
        assertThat(first.getTitle()).isEqualTo("青锋剑");
        assertThat(first.getPriceFen()).isEqualTo(500);
        assertThat(first.getMarketZone()).isEqualTo(ZONE_A);
        assertThat(first.getIconKey()).isEqualTo("icon_sword");
        assertThat(first.getSummary()).isEqualTo("摘要");
        assertThat(first.getCategory()).isEqualTo(ListingCategory.LISTING_CATEGORY_WEAPON);
        assertThat(first.getSubcategory()).isEqualTo(1);
        assertThat(first.getLevel()).isEqualTo(10);
        assertThat(first.getSaleEndMs()).isEqualTo(NOW_MS + HOUR_MS);
        assertThat(second.getIsMine()).isFalse();
        assertThat(second.getIsFavorite()).isTrue();
        assertThat(second.getPhase()).isEqualTo(ListingPhase.LISTING_PHASE_PUBLIC_NOTICE);
        assertThat(second.getNoticeEndMs()).isEqualTo(NOW_MS + HOUR_MS);
    }

    @Test
    void 浏览_空页不查收藏() {
        BrowseListingsResponse resp = global().browseListings(PLAYER_B, browse().build(), budget());

        assertThat(resp.getListingsList()).isEmpty();
        assertThat(resp.getPageCount()).isEqualTo(1);
        assertThat(f.store.called("FavoriteIDs")).isFalse();
    }

    @Test
    void 浏览_三类存储故障都是in_band1003() {
        for (String op : List.of("CountListings", "QueryListings", "FavoriteIDs")) {
            TradeServiceFixture fx = new TradeServiceFixture();
            fx.store.page = List.of(onSale(11, PLAYER_A, ZONE_A));
            fx.store.failOn.put(op, storeDown());

            BrowseListingsResponse resp = fx.service(MarketScope.MARKET_SCOPE_GLOBAL).browseListings(PLAYER_B, browse().build(), budget());

            assertRejectOnly(resp, TradeTips.SERVICE_UNAVAILABLE);
        }
        // 存储抛出的任何 RuntimeException 都是故障（基线「返回的 error 一律是存储故障」），包括查询描述非法
        TradeServiceFixture fx = new TradeServiceFixture();
        fx.store.failOn.put("CountListings", new IllegalArgumentException("未知排序"));
        assertRejectOnly(fx.service(MarketScope.MARKET_SCOPE_GLOBAL).browseListings(PLAYER_B, browse().build(), budget()),
                TradeTips.SERVICE_UNAVAILABLE);
    }

    // ================================================================ 详情（:617-690）

    @Test
    void 详情矩阵() {
        record Case(String name, MarketScope scope, long caller, long id, Consumer<TradeServiceFixture> prepare, int code,
                    boolean mine, boolean favorite, int homeLookups) {
        }
        Listing ended = withWindow(onSale(13, PLAYER_A, ZONE_A), NOW_MS - 2 * HOUR_MS, NOW_MS - 1);
        MarketScope z = MarketScope.MARKET_SCOPE_ZONE;
        MarketScope g = MarketScope.MARKET_SCOPE_GLOBAL;
        List<Case> cases = List.of(
                new Case("listing_id=0", z, PLAYER_B, 0, null, TradeTips.INVALID_PARAMETER, false, false, 0),
                new Case("不存在（不查归属区）", z, PLAYER_U, 999, null, TradeTips.LISTING_NOT_FOUND, false, false, 0),
                new Case("zone 同区买家可见", z, PLAYER_B, 11, null, 0, false, false, 1),
                new Case("zone 别区买家看不到", z, PLAYER_C, 11, null, TradeTips.LISTING_NOT_FOUND, false, false, 1),
                new Case("卖家看自己已结束的商品，不查归属区", z, PLAYER_A, 13, null, 0, true, false, 0),
                new Case("已结束对买家不可见", g, PLAYER_B, 13, null, TradeTips.LISTING_NOT_FOUND, false, false, 0),
                new Case("global 别区可见且不查归属区", g, PLAYER_C, 11, null, 0, false, false, 0),
                new Case("收藏标记", g, PLAYER_C, 11, fx -> fx.store.favorites.put(new FavoriteKey(PLAYER_C, 11), NOW_MS),
                        0, false, true, 0),
                new Case("买家归属区未映射", z, PLAYER_U, 11, null, TradeTips.HOME_ZONE_UNKNOWN, false, false, 1),
                new Case("归属区查询故障", z, PLAYER_B, 11, fx -> fx.homes.failure = new DependencyException("boom"),
                        TradeTips.SERVICE_UNAVAILABLE, false, false, 1),
                new Case("GetListing 故障", z, PLAYER_B, 11, fx -> fx.store.failOn.put("GetListing", storeDown()),
                        TradeTips.SERVICE_UNAVAILABLE, false, false, 0),
                new Case("FavoriteExists 故障", g, PLAYER_B, 11, fx -> fx.store.failOn.put("FavoriteExists", storeDown()),
                        TradeTips.SERVICE_UNAVAILABLE, false, false, 0),
                new Case("LOCKED 寄售期内对买家可见", g, PLAYER_B, 14, null, 0, false, false, 0),
                new Case("SOLD 对买家不可见", g, PLAYER_B, 15, null, TradeTips.LISTING_NOT_FOUND, false, false, 0),
                new Case("范围未指定：买家一律不可见、不查归属区", MarketScope.MARKET_SCOPE_UNSPECIFIED, PLAYER_B, 11, null,
                        TradeTips.LISTING_NOT_FOUND, false, false, 0),
                new Case("范围未指定：卖家豁免照样可见", MarketScope.MARKET_SCOPE_UNSPECIFIED, PLAYER_A, 11, null, 0, true, false, 0));
        for (Case c : cases) {
            TradeServiceFixture fx = new TradeServiceFixture();
            fx.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
            fx.store.listings.put(13L, ended);
            fx.store.listings.put(14L, withStatus(onSale(14, PLAYER_A, ZONE_A), ListingStatuses.LOCKED));
            fx.store.listings.put(15L, withStatus(onSale(15, PLAYER_A, ZONE_A), ListingStatuses.SOLD));
            if (c.prepare() != null) {
                c.prepare().accept(fx);
            }

            GetListingDetailResponse resp = fx.service(c.scope())
                    .getListingDetail(c.caller(), GetListingDetailRequest.newBuilder().setListingId(c.id()).build(), budget());

            assertThat(resp.getErrorMessage().getId()).as(c.name()).isEqualTo(c.code());
            assertThat(fx.homes.calls).as(c.name()).hasSize(c.homeLookups());
            if (c.code() != 0) {
                assertRejectOnly(resp, c.code());
                continue;
            }
            assertThat(resp.hasErrorMessage()).as(c.name()).isFalse();
            ListingSummary summary = resp.getDetail().getSummary();
            assertThat(summary.getListingId()).as(c.name()).isEqualTo(c.id());
            assertThat(summary.getIsMine()).as(c.name()).isEqualTo(c.mine());
            assertThat(summary.getIsFavorite()).as(c.name()).isEqualTo(c.favorite());
            assertThat(resp.getDetail().getDescription()).as(c.name()).isEqualTo("详细描述");
            assertThat(resp.getServerNowMs()).as(c.name()).isEqualTo(NOW_MS);
        }
    }

    @Test
    void 详情_卖家看已结束商品的phase是ENDED_LOCKED不看时间() {
        f.store.listings.put(13L, withWindow(onSale(13, PLAYER_A, ZONE_A), NOW_MS - 2 * HOUR_MS, NOW_MS));
        f.store.listings.put(14L, withStatus(withWindow(onSale(14, PLAYER_A, ZONE_A), NOW_MS - 2 * HOUR_MS, NOW_MS - 1),
                ListingStatuses.LOCKED));

        GetListingDetailResponse ended = zone().getListingDetail(PLAYER_A, GetListingDetailRequest.newBuilder().setListingId(13).build(),
                budget());
        GetListingDetailResponse locked = zone().getListingDetail(PLAYER_A, GetListingDetailRequest.newBuilder().setListingId(14).build(),
                budget());

        assertThat(ended.getDetail().getSummary().getPhase()).as("now == sale_end 算结束").isEqualTo(ListingPhase.LISTING_PHASE_ENDED);
        assertThat(locked.getDetail().getSummary().getPhase()).isEqualTo(ListingPhase.LISTING_PHASE_LOCKED);
    }

    // ================================================================ 收藏（:696-823）

    @Test
    void 取消收藏幂等_不查商品不查归属区不取时间() {
        f.store.favorites.put(new FavoriteKey(PLAYER_B, 11), NOW_MS);
        f.clockMs = -1; // 取消分支不取时间：取了也不影响结果，这里只为证明不依赖时钟

        for (int i = 0; i < 2; i++) {
            SetFavoriteResponse resp = zone().setFavorite(PLAYER_B,
                    SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(false).build(), budget());
            assertThat(resp.hasErrorMessage()).isFalse();
            assertThat(resp.getFavorite()).isFalse();
            assertThat(resp.getListingId()).isEqualTo(11);
        }
        assertThat(f.store.deletedFavorites).hasSize(2);
        assertThat(f.store.called("GetListing")).as("已下架商品的收藏也要能删").isFalse();
        assertThat(f.homes.calls).isEmpty();
    }

    @Test
    void 收藏_可见商品成功_写入时刻是本请求的now() {
        f.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));

        SetFavoriteResponse resp = zone().setFavorite(PLAYER_B, SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(true).build(),
                budget());

        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getFavorite()).isTrue();
        assertThat(resp.getListingId()).isEqualTo(11);
        assertThat(f.store.insertedFavorites).hasSize(1);
        assertThat(f.store.insertedFavorites.get(0)).containsExactly(PLAYER_B, 11, NOW_MS);
    }

    @Test
    void 收藏_已收藏直接回true_不计数不判上限不插入() {
        f.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
        f.store.favorites.put(new FavoriteKey(PLAYER_B, 11), NOW_MS);
        f.store.favoriteCountOverride = (long) MAX_FAVORITES;

        SetFavoriteResponse resp = zone().setFavorite(PLAYER_B, SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(true).build(),
                budget());

        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getFavorite()).isTrue();
        assertThat(f.store.called("CountFavorites")).isFalse();
        assertThat(f.store.insertedFavorites).isEmpty();
    }

    @Test
    void 收藏_达到上限回20002_低于上限一条仍可收藏() {
        f.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
        f.store.favoriteCountOverride = (long) MAX_FAVORITES;

        SetFavoriteResponse full = zone().setFavorite(PLAYER_B, SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(true).build(),
                budget());
        assertRejectOnly(full, TradeTips.FAVORITE_LIMIT_REACHED);
        assertThat(full.getListingId()).isEqualTo(11);
        assertThat(full.getFavorite()).isFalse();
        assertThat(f.store.insertedFavorites).isEmpty();

        f.store.favoriteCountOverride = (long) MAX_FAVORITES - 1;
        SetFavoriteResponse ok = zone().setFavorite(PLAYER_B, SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(true).build(),
                budget());
        assertThat(ok.hasErrorMessage()).isFalse();
        assertThat(f.store.insertedFavorites).hasSize(1);
    }

    @Test
    void 收藏_别区与不存在回20000_卖家可收藏自己已结束的商品() {
        f.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
        f.store.listings.put(13L, withWindow(onSale(13, PLAYER_A, ZONE_A), NOW_MS - 2 * HOUR_MS, NOW_MS - 1));

        SetFavoriteResponse otherZone = zone().setFavorite(PLAYER_C,
                SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(true).build(), budget());
        assertRejectOnly(otherZone, TradeTips.LISTING_NOT_FOUND);
        assertThat(otherZone.getListingId()).isEqualTo(11);

        SetFavoriteResponse missing = global().setFavorite(PLAYER_B,
                SetFavoriteRequest.newBuilder().setListingId(999).setFavorite(true).build(), budget());
        assertRejectOnly(missing, TradeTips.LISTING_NOT_FOUND);
        assertThat(missing.getListingId()).isEqualTo(999);

        SetFavoriteResponse seller = zone().setFavorite(PLAYER_A,
                SetFavoriteRequest.newBuilder().setListingId(13).setFavorite(true).build(), budget());
        assertThat(seller.hasErrorMessage()).as("卖家豁免（N4 不采纳）").isFalse();
        assertThat(seller.getFavorite()).isTrue();
    }

    @Test
    void 收藏_listing_id为0两个方向都回1005_不碰库() {
        for (boolean favorite : new boolean[] {true, false}) {
            SetFavoriteResponse resp = global().setFavorite(PLAYER_B, SetFavoriteRequest.newBuilder().setFavorite(favorite).build(), budget());
            assertRejectOnly(resp, TradeTips.INVALID_PARAMETER);
            assertThat(resp.getListingId()).isZero();
        }
        assertThat(f.store.calls).isEmpty();
    }

    @Test
    void 收藏_各步存储故障都是in_band1003且回填listing_id() {
        record Case(String op, boolean favorite) {
        }
        for (Case c : List.of(new Case("DeleteFavorite", false), new Case("GetListing", true), new Case("FavoriteExists", true),
                new Case("CountFavorites", true), new Case("InsertFavorite", true))) {
            TradeServiceFixture fx = new TradeServiceFixture();
            fx.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
            fx.store.failOn.put(c.op(), storeDown());

            SetFavoriteResponse resp = fx.service(MarketScope.MARKET_SCOPE_GLOBAL).setFavorite(PLAYER_B,
                    SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(c.favorite()).build(), budget());

            assertRejectOnly(resp, TradeTips.SERVICE_UNAVAILABLE);
            assertThat(resp.getListingId()).as(c.op()).isEqualTo(11);
        }
    }

    @Test
    void 收藏_公示中的商品可收藏_归属区未映射回20001() {
        f.store.listings.put(12L, withWindow(onSale(12, PLAYER_A, ZONE_A), NOW_MS + HOUR_MS, NOW_MS + 2 * HOUR_MS));

        SetFavoriteResponse notice = zone().setFavorite(PLAYER_B,
                SetFavoriteRequest.newBuilder().setListingId(12).setFavorite(true).build(), budget());
        assertThat(notice.hasErrorMessage()).isFalse();
        assertThat(notice.getFavorite()).isTrue();

        SetFavoriteResponse unmapped = zone().setFavorite(PLAYER_U,
                SetFavoriteRequest.newBuilder().setListingId(12).setFavorite(true).build(), budget());
        assertRejectOnly(unmapped, TradeTips.HOME_ZONE_UNKNOWN);
    }

    // ================================================================ 货架（:829-871）

    @Test
    void 货架_只按调用者查_不查归属区_任意状态_is_mine恒真_没有market_scope() {
        Listing sold = withStatus(onSale(21, PLAYER_A, ZONE_A), ListingStatuses.SOLD);
        f.store.page = List.of(onSale(22, PLAYER_A, ZONE_A), sold);
        f.store.total = 2;
        f.store.favorites.put(new FavoriteKey(PLAYER_A, 22), NOW_MS);

        GetMyShelfResponse resp = zone().getMyShelf(PLAYER_A, GetMyShelfRequest.newBuilder().setPage(7).setPageSize(50).build(), budget());

        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(f.store.lastSeller).isEqualTo(PLAYER_A);
        assertThat(f.homes.calls).isEmpty();
        assertThat(resp.getPage()).isEqualTo(1);
        assertThat(resp.getPageSize()).isEqualTo(20);
        assertThat(resp.getPageCount()).isEqualTo(1);
        assertThat(resp.getTotalCount()).isEqualTo(2);
        assertThat(resp.getServerNowMs()).isEqualTo(NOW_MS);
        assertThat(resp.getListingsList()).allMatch(ListingSummary::getIsMine);
        assertThat(resp.getListings(0).getIsFavorite()).isTrue();
        assertThat(resp.getListings(1).getPhase()).isEqualTo(ListingPhase.LISTING_PHASE_ENDED);
        assertThat(GetMyShelfResponse.getDescriptor().findFieldByName("market_scope")).isNull();
    }

    @Test
    void 货架_存储故障都是in_band1003() {
        for (String op : List.of("CountSellerListings", "QuerySellerListings", "FavoriteIDs")) {
            TradeServiceFixture fx = new TradeServiceFixture();
            fx.store.page = List.of(onSale(22, PLAYER_A, ZONE_A));
            fx.store.failOn.put(op, storeDown());

            GetMyShelfResponse resp = fx.service(MarketScope.MARKET_SCOPE_ZONE).getMyShelf(PLAYER_A, GetMyShelfRequest.getDefaultInstance(),
                    budget());

            assertRejectOnly(resp, TradeTips.SERVICE_UNAVAILABLE);
        }
    }

    @Test
    void 摘要不带卖家身份字段() {
        for (FieldDescriptor field : ListingSummary.getDescriptor().getFields()) {
            assertThat(field.getName()).doesNotContain("seller");
        }
    }

    // ================================================================ 整请求预算（:923-980）

    /** 一次带 I/O 的成功请求。 */
    private record BudgetCall(String name, Function<TradeServiceFixture, Integer> call) {
    }

    private static List<BudgetCall> budgetCalls(Deadline deadline) {
        List<BudgetCall> calls = new ArrayList<>();
        calls.add(new BudgetCall("BrowseListings", fx -> fx.service(MarketScope.MARKET_SCOPE_ZONE)
                .browseListings(PLAYER_B, browse().build(), deadline).getErrorMessage().getId()));
        calls.add(new BudgetCall("GetListingDetail", fx -> fx.service(MarketScope.MARKET_SCOPE_ZONE)
                .getListingDetail(PLAYER_B, GetListingDetailRequest.newBuilder().setListingId(11).build(), deadline)
                .getErrorMessage().getId()));
        calls.add(new BudgetCall("SetFavorite", fx -> fx.service(MarketScope.MARKET_SCOPE_ZONE)
                .setFavorite(PLAYER_B, SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(true).build(), deadline)
                .getErrorMessage().getId()));
        calls.add(new BudgetCall("GetMyShelf", fx -> fx.service(MarketScope.MARKET_SCOPE_ZONE)
                .getMyShelf(PLAYER_A, GetMyShelfRequest.getDefaultInstance(), deadline).getErrorMessage().getId()));
        return calls;
    }

    @Test
    void 每个方法的归属区查询与全部存储调用共用同一个预算() {
        Deadline deadline = budget();
        for (BudgetCall c : budgetCalls(deadline)) {
            TradeServiceFixture fx = new TradeServiceFixture();
            fx.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
            fx.store.page = List.of(onSale(11, PLAYER_A, ZONE_A));
            fx.store.total = 1;

            assertThat(c.call().apply(fx)).as(c.name()).isZero();

            List<Deadline> all = new ArrayList<>(fx.homes.deadlines);
            all.addAll(fx.store.deadlines);
            assertThat(all).as(c.name() + " 必须真正走到 I/O").isNotEmpty();
            assertThat(all).as(c.name() + "：串行 I/O 必须共用一个整请求预算").allMatch(d -> d == deadline);
        }
    }

    @Test
    void 依赖不响应时预算到期仍in_band1003_且早于服务端预算() {
        record Case(String blockOp, int callIndex) {
        }
        for (Case c : List.of(new Case("CountListings", 0), new Case("InsertFavorite", 2), new Case("FavoriteIDs", 3))) {
            TradeServiceFixture fx = new TradeServiceFixture();
            fx.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
            fx.store.page = List.of(onSale(11, PLAYER_A, ZONE_A));
            fx.store.blockOn.add(c.blockOp());
            Deadline deadline = Deadline.after(300);

            long start = System.nanoTime();
            int code = budgetCalls(deadline).get(c.callIndex()).call().apply(fx);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(code).as(c.blockOp()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
            assertThat(fx.store.called(c.blockOp())).as("必须真正卡在 " + c.blockOp()).isTrue();
            assertThat(elapsedMs).as("预算 300 ms 到期就返回，远早于 %d ms 的整请求预算", BUDGET_MS).isLessThan(BUDGET_MS);
        }
    }

    @Test
    void 时钟早于1970按0() {
        f.clockMs = -5;
        BrowseListingsResponse resp = global().browseListings(PLAYER_B, browse().build(), budget());
        assertThat(resp.getServerNowMs()).isZero();
        assertThat(f.store.lastQuery.nowMs()).isZero();
    }
}
