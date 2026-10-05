package com.game.trade.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.RunMode;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerProfiles;
import com.game.proto.trade.BrowseListingsResponse;
import com.game.proto.trade.GetListingDetailRequest;
import com.game.proto.trade.GetListingDetailResponse;
import com.game.proto.trade.GetMyShelfRequest;
import com.game.proto.trade.GetMyShelfResponse;
import com.game.proto.trade.ListingPhase;
import com.game.proto.trade.ListingSummary;
import com.game.proto.trade.ListingTab;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SeedListingResponse;
import com.game.proto.trade.SetFavoriteRequest;
import com.game.proto.trade.SetFavoriteResponse;
import com.game.trade.id.ListingIds;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.rules.TradeLimits;
import com.game.trade.rules.TradeTips;
import com.game.trade.store.JdbcListingStore;
import com.game.trade.store.TradeTables;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真 MySQL 上的整条只读面链路（缺省跳过：{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}；trade-spec §9.3 / §9.5 的服务层部分）：
 * 播种（真 {@link JdbcListingStore} + 真 {@link PlayerHomeZones} 读 {@code player.zone_id} + 雪花发号）→ 浏览（zone / global、zone_filter、按编号搜索）
 * → 详情 → 收藏 → 只看收藏 → 取消 → 货架。用一次性库 {@code xm_trade_flow_<随机>}：两张表经 {@link TradeTables#sync} 建，{@code player} 表只建
 * {@code PlayerProfiles} 读到的几列（这张表不归 xm-trade 所有，生产由 xm-player-store 建）；结束时删库，绝不碰 xm_java。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeFlowMysqlTest {

    private static final String BASE_URL = System.getProperty("xm.it.mysql");
    private static final String USER = System.getProperty("xm.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("XM_MYSQL_PASSWORD", "");
    private static final String PARAMS = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useAffectedRows=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',sql_mode='STRICT_TRANS_TABLES',innodb_lock_wait_timeout="
            + TradeLimits.LOCK_WAIT_TIMEOUT_SECONDS;

    private static final long SELLER = 9_000_000_000_000_000_001L;   // > 2^62，接近 2^63（雪花 player_id 的量级）
    private static final long BUYER = 9_000_000_000_000_000_002L;
    private static final long OTHER_ZONE = 9_000_000_000_000_000_003L;
    private static final long NO_ZONE = 9_000_000_000_000_000_004L;
    private static final int ZONE = 3;

    private String database;
    private DruidDataSource dataSource;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TradeMetrics metrics = new TradeMetrics(meters);
    private JdbcListingStore store;
    private PlayerHomeZones homeZones;

    @BeforeAll
    void setUp() throws SQLException {
        database = "xm_trade_flow_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(BASE_URL + "/" + PARAMS, USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE `" + database + "` DEFAULT CHARACTER SET utf8mb4");
        }
        dataSource = new DruidDataSource();
        dataSource.setUrl(BASE_URL + "/" + database + PARAMS);
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setMaxWait(10_000);
        dataSource.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        TradeTables.sync(dataSource, Duration.ofMinutes(1));
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE player (player_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL DEFAULT '',"
                    + " level INT NOT NULL DEFAULT 1, class_id INT NOT NULL DEFAULT 0, gender INT NOT NULL DEFAULT 0,"
                    + " appearance_id VARCHAR(64) NOT NULL DEFAULT '', zone_id INT UNSIGNED NOT NULL DEFAULT 0)");
            st.execute("INSERT INTO player (player_id, name, zone_id) VALUES (" + Long.toUnsignedString(SELLER) + ", 'seller', " + ZONE
                    + "), (" + Long.toUnsignedString(BUYER) + ", 'buyer', " + ZONE + "), (" + Long.toUnsignedString(OTHER_ZONE)
                    + ", 'other', " + (ZONE + 1) + "), (" + Long.toUnsignedString(NO_ZONE) + ", 'nozone', 0)");
        }
        store = new JdbcListingStore(dataSource::getConnection, 3, metrics);
        homeZones = new PlayerHomeZones(new PlayerProfiles(dataSource::getConnection, 3)::loadStrict,
                Duration.ofMillis(TradeLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS));
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (dataSource != null) {
            dataSource.close();
        }
        try (Connection c = DriverManager.getConnection(BASE_URL + "/" + PARAMS, USER, PASSWORD); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    private JubaozhaiService service(MarketScope scope) {
        return new JubaozhaiService(store, homeZones, metrics, new MarketSettings(scope, 20, 20, 100, 100), System::currentTimeMillis);
    }

    private static Deadline d() {
        return Deadline.after(3500);
    }

    @Test
    void 播种然后浏览_详情_收藏_货架() {
        String nonce = "FLOW-" + System.nanoTime();
        SeedListingService seeds = new SeedListingService(store, homeZones, new ListingIds(new Snowflake(5), () -> true)::nextId, metrics,
                RunMode.DEV, System::currentTimeMillis);
        SeedListingResponse onSale = seeds.seed(TradeServiceFixture.seedRequest().setSellerPlayerId(SELLER).setTitle(nonce + "-A").build(), d());
        SeedListingResponse notice = seeds.seed(TradeServiceFixture.seedRequest().setSellerPlayerId(SELLER).setTitle(nonce + "-AN")
                .setNoticeDurationMs(3_600_000L).build(), d());
        SeedListingResponse unmapped = seeds.seed(TradeServiceFixture.seedRequest().setSellerPlayerId(NO_ZONE).build(), d());

        assertThat(onSale.hasErrorMessage()).isFalse();
        assertThat(onSale.getMarketZone()).as("market_zone = 卖家 player.zone_id").isEqualTo(ZONE);
        assertThat(onSale.getListingId()).isPositive();
        assertThat(notice.getListingId()).isNotEqualTo(onSale.getListingId());
        assertThat(unmapped.getErrorMessage().getId()).isEqualTo(TradeTips.HOME_ZONE_UNKNOWN);

        // zone 范围：买家同区看得到寄售商品；别区玩家看不到；zone_filter 被忽略
        BrowseListingsResponse buyer = service(MarketScope.MARKET_SCOPE_ZONE)
                .browseListings(BUYER, TradeServiceFixture.browse().setSearch(nonce).setZoneFilter(ZONE + 1).build(), d());
        assertThat(buyer.hasErrorMessage()).isFalse();
        assertThat(buyer.getListingsList()).extracting(ListingSummary::getListingId).containsExactly(onSale.getListingId());
        ListingSummary summary = buyer.getListings(0);
        assertThat(summary.getPhase()).isEqualTo(ListingPhase.LISTING_PHASE_ON_SALE);
        assertThat(summary.getIsMine()).isFalse();
        assertThat(summary.getMarketZone()).isEqualTo(ZONE);
        assertThat(summary.getTitle()).isEqualTo(nonce + "-A");

        BrowseListingsResponse other = service(MarketScope.MARKET_SCOPE_ZONE)
                .browseListings(OTHER_ZONE, TradeServiceFixture.browse().setSearch(nonce).build(), d());
        assertThat(other.getListingsList()).isEmpty();
        BrowseListingsResponse noZone = service(MarketScope.MARKET_SCOPE_ZONE)
                .browseListings(NO_ZONE, TradeServiceFixture.browse().setSearch(nonce).build(), d());
        assertThat(noZone.getErrorMessage().getId()).isEqualTo(TradeTips.HOME_ZONE_UNKNOWN);

        // global 范围：别区玩家看得到；zone_filter 生效
        assertThat(service(MarketScope.MARKET_SCOPE_GLOBAL).browseListings(OTHER_ZONE,
                TradeServiceFixture.browse().setSearch(nonce).build(), d()).getListingsCount()).isEqualTo(1);
        assertThat(service(MarketScope.MARKET_SCOPE_GLOBAL).browseListings(OTHER_ZONE,
                TradeServiceFixture.browse().setSearch(nonce).setZoneFilter(ZONE + 1).build(), d()).getListingsCount()).isZero();

        // 按编号精确搜索（纯数字搜索词）；公示页签只看到公示商品
        assertThat(service(MarketScope.MARKET_SCOPE_ZONE).browseListings(BUYER,
                TradeServiceFixture.browse().setSearch(Long.toUnsignedString(onSale.getListingId())).build(), d())
                .getListingsList()).extracting(ListingSummary::getListingId).containsExactly(onSale.getListingId());
        BrowseListingsResponse noticeTab = service(MarketScope.MARKET_SCOPE_ZONE).browseListings(BUYER,
                TradeServiceFixture.browse().setTab(ListingTab.LISTING_TAB_PUBLIC_NOTICE).setSearch(nonce).build(), d());
        assertThat(noticeTab.getListingsList()).extracting(ListingSummary::getListingId).containsExactly(notice.getListingId());
        assertThat(noticeTab.getListings(0).getPhase()).isEqualTo(ListingPhase.LISTING_PHASE_PUBLIC_NOTICE);

        // 详情：同区买家受理；别区玩家 20000；卖家看自己 is_mine
        GetListingDetailResponse detail = service(MarketScope.MARKET_SCOPE_ZONE).getListingDetail(BUYER,
                GetListingDetailRequest.newBuilder().setListingId(onSale.getListingId()).build(), d());
        assertThat(detail.getDetail().getDescription()).isEqualTo("详细描述");
        assertThat(service(MarketScope.MARKET_SCOPE_ZONE).getListingDetail(OTHER_ZONE,
                GetListingDetailRequest.newBuilder().setListingId(onSale.getListingId()).build(), d()).getErrorMessage().getId())
                .isEqualTo(TradeTips.LISTING_NOT_FOUND);
        assertThat(service(MarketScope.MARKET_SCOPE_ZONE).getListingDetail(SELLER,
                GetListingDetailRequest.newBuilder().setListingId(onSale.getListingId()).build(), d()).getDetail().getSummary().getIsMine())
                .isTrue();

        // 收藏 → 只看收藏 → 取消 → 再看
        SetFavoriteResponse fav = service(MarketScope.MARKET_SCOPE_ZONE).setFavorite(BUYER,
                SetFavoriteRequest.newBuilder().setListingId(onSale.getListingId()).setFavorite(true).build(), d());
        assertThat(fav.getFavorite()).isTrue();
        assertThat(fav.getListingId()).isEqualTo(onSale.getListingId());
        BrowseListingsResponse favorites = service(MarketScope.MARKET_SCOPE_ZONE)
                .browseListings(BUYER, TradeServiceFixture.browse().setFavoritesOnly(true).build(), d());
        assertThat(favorites.getListingsList()).extracting(ListingSummary::getListingId).containsExactly(onSale.getListingId());
        assertThat(favorites.getListings(0).getIsFavorite()).isTrue();
        SetFavoriteResponse unfav = service(MarketScope.MARKET_SCOPE_ZONE).setFavorite(BUYER,
                SetFavoriteRequest.newBuilder().setListingId(onSale.getListingId()).setFavorite(false).build(), d());
        assertThat(unfav.hasErrorMessage()).isFalse();
        assertThat(unfav.getFavorite()).isFalse();
        assertThat(service(MarketScope.MARKET_SCOPE_ZONE)
                .browseListings(BUYER, TradeServiceFixture.browse().setFavoritesOnly(true).build(), d()).getListingsCount()).isZero();

        // 货架：卖家看到两条种子（任意状态），买家的货架是空的
        GetMyShelfResponse shelf = service(MarketScope.MARKET_SCOPE_ZONE).getMyShelf(SELLER, GetMyShelfRequest.getDefaultInstance(), d());
        assertThat(shelf.getListingsList()).extracting(ListingSummary::getListingId)
                .containsExactly(notice.getListingId(), onSale.getListingId());
        assertThat(shelf.getListingsList()).allMatch(ListingSummary::getIsMine);
        assertThat(service(MarketScope.MARKET_SCOPE_ZONE).getMyShelf(BUYER, GetMyShelfRequest.getDefaultInstance(), d())
                .getTotalCount()).isZero();

        assertThat(meters.get("xm.trade.home.zone.lookups").tag("result", "ok").counter().count()).isPositive();
        assertThat(meters.get("xm.trade.home.zone.lookups").tag("result", "unmapped").counter().count()).isEqualTo(2);
    }
}
