package com.game.trade.service;

import com.game.common.RunMode;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.HomeZones;
import com.game.proto.trade.BrowseListingsRequest;
import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingSection;
import com.game.proto.trade.ListingTab;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SeedListingRequest;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingStatuses;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务单测的公共夹具（照基线 newFixture，jubaozhai_logic_test.go:270-308）：假存储、假归属区、假发号、固定时钟；
 * 玩家 A / B 在 zoneA、C 在 zoneB、U 没有归属区；收藏上限 3；整请求预算 3500 ms。
 */
public final class TradeServiceFixture {

    public static final long NOW_MS = 1_800_000_000_000L;
    public static final long HOUR_MS = 3_600_000L;

    public static final long PLAYER_A = 1001; // zoneA 卖家
    public static final long PLAYER_B = 1002; // zoneA 买家
    public static final long PLAYER_C = 1003; // zoneB
    public static final long PLAYER_U = 1009; // 没有归属区

    public static final int ZONE_A = 1;
    public static final int ZONE_B = 2;

    public static final int MAX_FAVORITES = 3;
    public static final long BUDGET_MS = 3500;

    /** 假归属区：记下每次查询的玩家与 Deadline；{@link #failure} 非 null 时抛它。 */
    public static final class FakeHomeZones implements HomeZones {
        public final Map<Long, Integer> zones = new HashMap<>();
        public final List<Long> calls = new ArrayList<>();
        public final List<Deadline> deadlines = new ArrayList<>();
        public RuntimeException failure;

        @Override
        public Map<Long, Integer> homeZones(List<Long> playerIds, Deadline deadline) {
            calls.addAll(playerIds);
            deadlines.add(deadline);
            if (failure != null) {
                throw failure;
            }
            Map<Long, Integer> out = new HashMap<>();
            for (long id : playerIds) {
                Integer zone = zones.get(id);
                if (zone != null) {
                    out.put(id, zone);
                }
            }
            return out;
        }
    }

    public final FakeListingStore store = new FakeListingStore();
    public final FakeHomeZones homes = new FakeHomeZones();
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final TradeMetrics metrics = new TradeMetrics(meters);
    public final AtomicLong nextListingId = new AtomicLong(5001);
    public final AtomicInteger idCalls = new AtomicInteger();
    public RuntimeException idFailure;
    public long clockMs = NOW_MS;

    public TradeServiceFixture() {
        homes.zones.put(PLAYER_A, ZONE_A);
        homes.zones.put(PLAYER_B, ZONE_A);
        homes.zones.put(PLAYER_C, ZONE_B);
    }

    public static MarketSettings market(MarketScope scope) {
        return new MarketSettings(scope, 20, 20, 100, MAX_FAVORITES);
    }

    public JubaozhaiService service(MarketScope scope) {
        return new JubaozhaiService(store, homes, metrics, market(scope), () -> clockMs);
    }

    public SeedListingService seeds(RunMode mode) {
        return new SeedListingService(store, homes, () -> {
            idCalls.incrementAndGet();
            if (idFailure != null) {
                throw idFailure;
            }
            return nextListingId.get();
        }, metrics, mode, () -> clockMs);
    }

    public static Deadline budget() {
        return Deadline.after(BUDGET_MS);
    }

    public static DependencyException storeDown() {
        return new DependencyException("trade 失败", new java.sql.SQLException("Communications link failure", "08S01"));
    }

    /** 卖家 seller 在 zone 上架、寄售中的武器（onSaleListing，jubaozhai_logic_test.go:318-326）。 */
    public static Listing onSale(long id, long seller, int zone) {
        return new Listing(id, seller, "acc", zone, zone, ListingCategory.LISTING_CATEGORY_WEAPON_VALUE, 1, "青锋剑", 10, 500,
                ListingStatuses.LISTED, "摘要", "详细描述", "icon_sword", NOW_MS - HOUR_MS, NOW_MS + HOUR_MS, NOW_MS - 2 * HOUR_MS,
                NOW_MS - 2 * HOUR_MS, 0);
    }

    public static Listing withStatus(Listing l, int status) {
        return new Listing(l.listingId(), l.sellerPlayerId(), l.sellerAccount(), l.marketZone(), l.sellerZoneAtListing(), l.category(),
                l.subcategory(), l.title(), l.level(), l.priceFen(), status, l.summary(), l.description(), l.iconKey(), l.noticeEndMs(),
                l.saleEndMs(), l.createdMs(), l.updatedMs(), l.version());
    }

    public static Listing withWindow(Listing l, long noticeEndMs, long saleEndMs) {
        return new Listing(l.listingId(), l.sellerPlayerId(), l.sellerAccount(), l.marketZone(), l.sellerZoneAtListing(), l.category(),
                l.subcategory(), l.title(), l.level(), l.priceFen(), l.status(), l.summary(), l.description(), l.iconKey(), noticeEndMs,
                saleEndMs, l.createdMs(), l.updatedMs(), l.version());
    }

    public static BrowseListingsRequest.Builder browse() {
        return BrowseListingsRequest.newBuilder()
                .setTab(ListingTab.LISTING_TAB_ON_SALE)
                .setSection(ListingSection.LISTING_SECTION_CONSIGNMENT)
                .setCategory(ListingCategory.LISTING_CATEGORY_WEAPON);
    }

    /** 合法的种子请求（validSeedRequest，phase_test.go:227-240）：武器 / 子类 1 / 60 级 / 123456 分、无公示期、寄售 1 小时。 */
    public static SeedListingRequest.Builder seedRequest() {
        return SeedListingRequest.newBuilder()
                .setSellerPlayerId(PLAYER_A)
                .setCategory(ListingCategory.LISTING_CATEGORY_WEAPON)
                .setSubcategory(1)
                .setTitle("青锋剑")
                .setLevel(60)
                .setPriceFen(123456)
                .setSummary("摘要")
                .setDescription("详细描述")
                .setIconKey("smoke_weapon")
                .setNoticeDurationMs(0)
                .setSaleDurationMs(HOUR_MS);
    }

    public double homeLookups(String result) {
        return meters.get("xm.trade.home.zone.lookups").tag("result", result).counter().count();
    }

    public double seedResults(String result) {
        return meters.get("xm.trade.seed.listings").tag("result", result).counter().count();
    }
}
