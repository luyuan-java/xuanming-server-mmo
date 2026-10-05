package com.game.trade.store;

import static com.game.trade.store.TradeMysqlFixture.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingPhase;
import com.game.proto.trade.ListingSort;
import com.game.proto.trade.ListingTab;
import com.game.proto.trade.MarketScope;
import com.game.trade.rules.ListingRules;
import com.game.trade.rules.ListingRules.PageWindow;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * {@link JdbcListingStore} 连真 MySQL 的测试（缺省跳过：{@code -Dxm.it.mysql=jdbc:mysql://127.0.0.1:3306}；一次性库，见 {@link TradeMysqlFixture}）。
 *
 * <p>移植基线 listing_repo_integration_test.go:120-247 的全部子测（主键冲突、寄售含 LOCKED 且新上架在前、列表不取描述、公示列表、分区 + 排序 + 分页、
 * LIKE 通配按字面量、数字搜索、收藏全流程、货架任意状态、详情全列与不存在），外加 trade-spec §9.3 的 Java 增项：≥ 2^63 的无符号号全流程、
 * 排序决胜列保证逐页翻完不重不漏、MEDIUMTEXT 为 NULL 的行读回空串、{@code utf8mb4_unicode_ci} 下 LIKE 不区分大小写；以及 §2.5 可见性总表、
 * 分页边界（页码钳制、MaxPage 封顶的 OFFSET）、收藏计数包括看不见的收藏（Q8）、每条 SELECT 带 MAX_EXECUTION_TIME 提示在真库上可执行、建表幂等。
 */
@EnabledIfSystemProperty(named = "xm.it.mysql", matches = ".+")
class JdbcListingStoreMysqlTest {

    /** 基线 itNow（listing_repo_integration_test.go:96）。 */
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final int WEAPON = ListingCategory.LISTING_CATEGORY_WEAPON_VALUE;
    private static final int ARMOR = ListingCategory.LISTING_CATEGORY_ARMOR_VALUE;
    private static final int NOTICE = ListingTab.LISTING_TAB_PUBLIC_NOTICE_VALUE;
    private static final int ON_SALE = ListingTab.LISTING_TAB_ON_SALE_VALUE;
    private static final int LISTED = ListingStatuses.LISTED;
    private static final int LOCKED = ListingStatuses.LOCKED;
    private static final int SOLD = ListingStatuses.SOLD;

    private static TradeMysqlFixture db;
    private JdbcListingStore store;

    @BeforeAll
    static void createDatabase() throws SQLException {
        db = TradeMysqlFixture.create();
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void reset() throws SQLException {
        db.truncate();
        store = db.store();
    }

    /** 基线 itListing（listing_repo_integration_test.go:98-107）：武器 / 子类 1。 */
    private static Listing listing(long id, long seller, int zone, String title, long price, int level, int status, long noticeEnd,
                                   long saleEnd) {
        return new Listing(id, seller, "", zone, zone, WEAPON, 1, title, level, price, status, "summary", "description of " + title,
                "icon_a", noticeEnd, saleEnd, NOW - 1000, NOW - 1000, 0);
    }

    private void insert(Listing... listings) {
        for (Listing l : listings) {
            store.insertListing(l, d());
        }
    }

    private static List<Long> ids(List<Listing> listings) {
        return listings.stream().map(Listing::listingId).toList();
    }

    private static ListingQuery onSale() {
        return ListingQuery.of(ON_SALE, WEAPON, NOW);
    }

    /** 基线 TestListingRepoIntegration 的夹具（listing_repo_integration_test.go:126-133）。 */
    private void seedBaseline() {
        insert(listing(101, 1, 1, "青锋剑", 500, 10, LISTED, NOW - HOUR, NOW + HOUR),          // 寄售中 zone1
                listing(102, 1, 1, "100%_纯钢剑", 300, 30, LISTED, NOW - HOUR, NOW + 2 * HOUR), // 寄售中 zone1，标题含通配符
                listing(103, 2, 2, "玄铁剑", 400, 20, LOCKED, NOW - HOUR, NOW + HOUR),          // 锁定 zone2，寄售列表可见
                listing(104, 1, 1, "公示剑", 900, 5, LISTED, NOW + HOUR, NOW + 3 * HOUR),       // 公示中
                listing(105, 1, 1, "过期剑", 100, 1, LISTED, NOW - 2 * HOUR, NOW - HOUR),       // 已过寄售期
                listing(106, 2, 2, "已售剑", 100, 1, SOLD, NOW - 2 * HOUR, NOW + HOUR));        // 已售
    }

    // ================================================================ 基线子测（listing_repo_integration_test.go:120-247）

    @Test
    void 主键冲突必须报错不许静默覆盖() throws SQLException {
        seedBaseline();
        Listing dup = listing(101, 9, 9, "冒名", 1, 1, LISTED, 0, NOW + HOUR);
        assertThatThrownBy(() -> store.insertListing(dup, d())).isInstanceOf(DependencyException.class)
                .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
        assertThat(store.getListing(101, d()).orElseThrow().title()).isEqualTo("青锋剑");
        assertThat(db.count("SELECT COUNT(*) FROM trade_listing")).isEqualTo(6);
    }

    @Test
    void 寄售列表含LOCKED不含公示过期已售_默认新上架在前() {
        seedBaseline();
        assertThat(store.countListings(onSale(), d())).isEqualTo(3);
        List<Listing> got = store.queryListings(onSale(), 0, 20, d());
        assertThat(ids(got)).containsExactly(103L, 102L, 101L);
        assertThat(got.get(0).description()).as("列表查询不取 description").isEmpty();
        assertThat(got.get(0).status()).isEqualTo(LOCKED);
        // 摘要列逐字段与插入的一致（除了 description）
        assertThat(got.get(2)).isEqualTo(listing(101, 1, 1, "青锋剑", 500, 10, LISTED, NOW - HOUR, NOW + HOUR).withoutDescription());
    }

    @Test
    void 公示列表() {
        seedBaseline();
        assertThat(ids(store.queryListings(onSale().withTab(NOTICE), 0, 20, d()))).containsExactly(104L);
        assertThat(store.countListings(onSale().withTab(NOTICE), d())).isEqualTo(1);
    }

    @Test
    void 分区过滤加排序加分页() {
        seedBaseline();
        ListingQuery q = onSale().withMarketZone(1).withSort(ListingSort.LISTING_SORT_PRICE_ASC_VALUE);
        assertThat(ids(store.queryListings(q, 0, 1, d()))).containsExactly(102L);
        assertThat(ids(store.queryListings(q, 1, 1, d()))).containsExactly(101L);
        assertThat(ids(store.queryListings(q.withSort(ListingSort.LISTING_SORT_REMAINING_ASC_VALUE), 0, 20, d())))
                .containsExactly(101L, 102L);
        // 其余排序
        assertThat(ids(store.queryListings(q.withSort(ListingSort.LISTING_SORT_PRICE_DESC_VALUE), 0, 20, d()))).containsExactly(101L, 102L);
        assertThat(ids(store.queryListings(q.withSort(ListingSort.LISTING_SORT_LEVEL_DESC_VALUE), 0, 20, d()))).containsExactly(102L, 101L);
        // 分区 2 只有锁定的 103；分区 0 = 全部区
        assertThat(ids(store.queryListings(onSale().withMarketZone(2), 0, 20, d()))).containsExactly(103L);
        assertThat(ids(store.queryListings(onSale().withMarketZone(0), 0, 20, d()))).containsExactly(103L, 102L, 101L);
        assertThat(store.countListings(onSale().withMarketZone(3), d())).isZero();
    }

    @Test
    void LIKE通配符按字面量匹配() {
        seedBaseline();
        // 搜索词 "%_" 经 EscapeLike 后的形状
        String pattern = ListingRules.likePattern("%_");
        assertThat(pattern).isEqualTo("%!%!_%");
        assertThat(ids(store.queryListings(onSale().withSearch(pattern, 0), 0, 20, d()))).as("只有标题里真含 %_ 的商品命中")
                .containsExactly(102L);
        // 只搜 "_"：没有转义时会匹配任意一个字符
        assertThat(ids(store.queryListings(onSale().withSearch(ListingRules.likePattern("_"), 0), 0, 20, d()))).containsExactly(102L);
        // 搜 "!"（转义符本身）
        assertThat(store.countListings(onSale().withSearch(ListingRules.likePattern("!"), 0), d())).isZero();
    }

    @Test
    void 数字搜索同时按编号() {
        seedBaseline();
        assertThat(ids(store.queryListings(onSale().withSearch("%101%", 101), 0, 20, d()))).containsExactly(101L);
        // 搜索与其他条件是 AND：按编号也找不到当前页签 / 类目以外的商品（§2.7）
        assertThat(store.countListings(onSale().withSearch("%104%", 104), d())).as("104 在公示页签").isZero();
        assertThat(store.countListings(ListingQuery.of(ON_SALE, ARMOR, NOW).withSearch("%101%", 101), d())).as("101 是武器").isZero();
        // 编号与标题取并集
        insert(listing(201, 1, 1, "剑 101 号", 1, 1, LISTED, 0, NOW + HOUR));
        assertThat(ids(store.queryListings(onSale().withSearch("%101%", 101), 0, 20, d()))).containsExactly(201L, 101L);
    }

    @Test
    void 收藏全流程() {
        seedBaseline();
        store.insertFavorite(9, 101, NOW, d());
        store.insertFavorite(9, 101, NOW + 5, d()); // 重复收藏必须幂等
        assertThat(store.countFavorites(9, d())).isEqualTo(1);
        assertThat(store.favoriteExists(9, 101, d())).isTrue();
        assertThat(store.favoriteExists(9, 102, d())).isFalse();
        assertThat(store.favoriteExists(10, 101, d())).isFalse();
        assertThat(store.favoriteIds(9, List.of(101L, 102L), d())).containsExactly(101L);
        assertThat(ids(store.queryListings(onSale().withFavoritesOf(9), 0, 20, d()))).containsExactly(101L);
        assertThat(store.countListings(onSale().withFavoritesOf(10), d())).isZero();

        store.deleteFavorite(9, 101, d());
        store.deleteFavorite(9, 101, d()); // 取消收藏必须幂等
        assertThat(store.favoriteExists(9, 101, d())).isFalse();
        assertThat(store.countFavorites(9, d())).isZero();
        assertThat(db.favoriteRetries).hasValue(0);
    }

    /** §1.9：重复收藏不刷新原收藏时间（ODKU 空更新）。 */
    @Test
    void 重复收藏不刷新收藏时间() throws SQLException {
        store.insertFavorite(9, 101, 111, d());
        store.insertFavorite(9, 101, 222, d());
        assertThat(db.queryU64("SELECT `created_ms` FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?", 9L, 101L))
                .isEqualTo(111L);
    }

    @Test
    void 货架含任意状态_新上架在前() {
        seedBaseline();
        assertThat(store.countSellerListings(2, d())).isEqualTo(2);
        assertThat(ids(store.querySellerListings(2, 0, 20, d()))).containsExactly(106L, 103L);
        assertThat(ids(store.querySellerListings(1, 0, 20, d()))).containsExactly(105L, 104L, 102L, 101L);
        assertThat(ids(store.querySellerListings(1, 1, 2, d()))).containsExactly(104L, 102L);
        assertThat(store.countSellerListings(3, d())).isZero();
        assertThat(store.querySellerListings(1, 0, 20, d())).allSatisfy(l -> assertThat(l.description()).isEmpty());
    }

    @Test
    void 详情取全列_不存在是空() {
        seedBaseline();
        Listing got = store.getListing(102, d()).orElseThrow();
        assertThat(got).isEqualTo(listing(102, 1, 1, "100%_纯钢剑", 300, 30, LISTED, NOW - HOUR, NOW + 2 * HOUR));
        assertThat(got.description()).isEqualTo("description of 100%_纯钢剑");
        assertThat(got.category()).isEqualTo(WEAPON);
        assertThat(store.getListing(999, d())).isEmpty();
        assertThat(store.getListing(Long.MAX_VALUE, d())).as("robot 用 MaxInt64 当不存在的编号").isEmpty();
    }

    // ================================================================ Java 增项（§9.3）

    /** ≥ 2^63 的无符号号全流程：插入、详情、按编号搜、收藏（批量 / 存在 / 计数 / 只看收藏 / 删除）、货架；所有 uint64 / uint32 列都按位往返。 */
    @Test
    void 大于2的63次方的无符号号全流程() {
        long id = 0x8000_0000_0000_0007L;       // 9223372036854775815
        long seller = -3L;                      // 2^64 − 3
        long player = 0x8000_0000_0000_0001L;
        Listing big = new Listing(id, seller, "acct", 0xFFFF_FFFF, 0x8000_0000, WEAPON, 5, "大号剑", 0xFFFF_FFFF, -2L, LISTED,
                "摘要", "描述", "icon_big", NOW - HOUR, -5L, -6L, -7L, -8L);
        Listing small = listing(7, seller, 0xFFFF_FFFF, "小号剑", 1, 1, LISTED, NOW - HOUR, NOW + HOUR);
        insert(big, small);

        assertThat(store.getListing(id, d())).contains(big);
        ListingQuery zone = onSale().withMarketZone(0xFFFF_FFFF);
        // 无符号排序：大号排在前（BIGINT UNSIGNED 按无符号比较）
        assertThat(ids(store.queryListings(zone, 0, 20, d()))).containsExactly(id, 7L);
        assertThat(ids(store.queryListings(zone.withSort(ListingSort.LISTING_SORT_PRICE_DESC_VALUE), 0, 20, d()))).containsExactly(id, 7L);
        assertThat(ids(store.queryListings(zone.withSubcategory(5), 0, 20, d()))).containsExactly(id);
        // 按编号搜（搜索词是十进制串，编号是位模式）
        String search = Long.toUnsignedString(id);
        long searchId = ListingRules.searchListingId(search);
        assertThat(searchId).isEqualTo(id);
        assertThat(ids(store.queryListings(zone.withSearch(ListingRules.likePattern(search), searchId), 0, 20, d()))).containsExactly(id);
        // 收藏
        store.insertFavorite(player, id, -9L, d());
        assertThat(store.favoriteExists(player, id, d())).isTrue();
        assertThat(store.favoriteIds(player, List.of(id, 7L), d())).containsExactly(id);
        assertThat(store.countFavorites(player, d())).isEqualTo(1);
        assertThat(ids(store.queryListings(zone.withFavoritesOf(player), 0, 20, d()))).containsExactly(id);
        // 货架
        assertThat(store.countSellerListings(seller, d())).isEqualTo(2);
        assertThat(store.querySellerListings(seller, 0, 20, d())).containsExactly(big.withoutDescription(), small.withoutDescription());
        store.deleteFavorite(player, id, d());
        assertThat(store.favoriteExists(player, id, d())).isFalse();
    }

    /** 排序决胜列：大量同价 / 同等级 / 同到期的商品逐页翻完不重不漏（jubaozhai.proto:46），并与 PageWindow 配合覆盖页码边界。 */
    @Test
    void 排序决胜列保证逐页翻完不重不漏() {
        List<Listing> rows = new ArrayList<>();
        for (int i = 1; i <= 23; i++) {
            rows.add(listing(1000 + i, 1, 1, "同价剑" + i, 500, 10, LISTED, NOW - HOUR, NOW + HOUR));
        }
        insert(rows.toArray(Listing[]::new));
        List<Long> ascending = rows.stream().map(Listing::listingId).toList();
        List<Long> descending = new ArrayList<>(ascending);
        java.util.Collections.reverse(descending);

        assertThat(pageThrough(ListingSort.LISTING_SORT_PRICE_ASC_VALUE, 5)).containsExactlyElementsOf(ascending);
        assertThat(pageThrough(ListingSort.LISTING_SORT_REMAINING_ASC_VALUE, 5)).containsExactlyElementsOf(ascending);
        assertThat(pageThrough(ListingSort.LISTING_SORT_PRICE_DESC_VALUE, 4)).containsExactlyElementsOf(descending);
        assertThat(pageThrough(ListingSort.LISTING_SORT_LEVEL_DESC_VALUE, 7)).containsExactlyElementsOf(descending);
        assertThat(pageThrough(ListingSort.LISTING_SORT_DEFAULT_VALUE, 20)).containsExactlyElementsOf(descending);
    }

    /** 按服务层的算法翻页：COUNT → PageWindow → 分页查询，直到最后一页。 */
    private List<Long> pageThrough(int sort, int pageSize) {
        ListingQuery q = onSale().withSort(sort);
        List<Long> out = new ArrayList<>();
        long total = store.countListings(q, d());
        PageWindow first = ListingRules.pageWindow(total, 1, pageSize, 100);
        for (int page = 1; page <= first.pageCount(); page++) {
            PageWindow w = ListingRules.pageWindow(total, page, pageSize, 100);
            out.addAll(ids(store.queryListings(q, w.offset(), pageSize, d())));
        }
        assertThat(new HashSet<>(out)).as("不重").hasSameSizeAs(out);
        return out;
    }

    /** 分页边界（§2.7 的分页矩阵落到真库）：末页偏短、超过末页按末页、MaxPage 封顶后的 OFFSET 超过总数时返回空页、空结果照样执行分页查询。 */
    @Test
    void 分页边界() {
        List<Listing> rows = new ArrayList<>();
        for (int i = 1; i <= 45; i++) {
            rows.add(listing(i, 1, 1, "剑" + i, i, 1, LISTED, 0, NOW + HOUR));
        }
        insert(rows.toArray(Listing[]::new));
        ListingQuery q = onSale().withSort(ListingSort.LISTING_SORT_PRICE_ASC_VALUE);
        long total = store.countListings(q, d());
        assertThat(total).isEqualTo(45);

        PageWindow p1 = ListingRules.pageWindow(total, 0, 20, 100);
        assertThat(ids(store.queryListings(q, p1.offset(), 20, d()))).hasSize(20).startsWith(1L);
        PageWindow last = ListingRules.pageWindow(total, 9999, 20, 100);
        assertThat(last).isEqualTo(new PageWindow(3, 3, 40));
        assertThat(ids(store.queryListings(q, last.offset(), 20, d()))).containsExactly(41L, 42L, 43L, 44L, 45L);
        // 恰好在末尾之后：空页
        assertThat(store.queryListings(q, 45, 20, d())).isEmpty();
        // MaxPage 封顶的最大 OFFSET（(100−1)×20 = 1980）远超总数：空页、不报错
        assertThat(store.queryListings(q, 1980, 20, d())).isEmpty();
        // 页长 1 翻到末页
        PageWindow single = ListingRules.pageWindow(total, 9999, 1, 100);
        assertThat(single).isEqualTo(new PageWindow(45, 45, 44));
        assertThat(ids(store.queryListings(q, single.offset(), 1, d()))).containsExactly(45L);
        // 空结果照样能跑分页查询
        ListingQuery none = onSale().withMarketZone(77);
        PageWindow empty = ListingRules.pageWindow(store.countListings(none, d()), 5, 10, 100);
        assertThat(empty).isEqualTo(new PageWindow(1, 1, 0));
        assertThat(store.queryListings(none, empty.offset(), 10, d())).isEmpty();
    }

    /** §1.5 / §5.9 第 8 条：手工插入的 MEDIUMTEXT NULL 读回空串（列表与详情都是）。 */
    @Test
    void NULL文本读回空串() throws SQLException {
        db.exec("INSERT INTO trade_listing (`listing_id`, `seller_player_id`, `seller_account`, `market_zone`, `seller_zone_at_listing`,"
                + " `category`, `subcategory`, `title`, `level`, `price_fen`, `status`, `summary`, `description`, `icon_key`,"
                + " `notice_end_ms`, `sale_end_ms`, `created_ms`, `updated_ms`, `version`)"
                + " VALUES (?, 1, NULL, 1, 1, ?, 0, NULL, 1, 1, ?, NULL, NULL, NULL, 0, ?, 0, 0, 0)", 77L, WEAPON, LISTED, NOW + HOUR);
        Listing got = store.getListing(77, d()).orElseThrow();
        assertThat(List.of(got.sellerAccount(), got.title(), got.summary(), got.description(), got.iconKey())).containsOnly("");
        assertThat(store.queryListings(onSale(), 0, 20, d())).singleElement().satisfies(l -> {
            assertThat(l.title()).isEmpty();
            assertThat(l.iconKey()).isEmpty();
        });
    }

    /** {@code title} 是 MEDIUMTEXT、跟随表排序规则 utf8mb4_unicode_ci：LIKE 不区分大小写（§1.5，两版相同）。 */
    @Test
    void LIKE不区分大小写() {
        insert(listing(1, 1, 1, "Dragon Sword", 1, 1, LISTED, 0, NOW + HOUR), listing(2, 1, 1, "盾", 1, 1, LISTED, 0, NOW + HOUR));
        assertThat(ids(store.queryListings(onSale().withSearch(ListingRules.likePattern("sWORD"), 0), 0, 20, d()))).containsExactly(1L);
        assertThat(ids(store.queryListings(onSale().withSearch(ListingRules.likePattern("DRAGON"), 0), 0, 20, d()))).containsExactly(1L);
    }

    /**
     * §2.5 可见性总表落到真库：同一批行在公示列表 / 寄售列表 / 货架里出现与否、读回之后的 phase 与 VisibleToBuyer。
     * 浏览没有卖家豁免（看自己全部商品走货架）；详情 / 收藏的卖家豁免由服务层判。
     */
    @Test
    void 可见性总表() {
        long seller = 5;
        record Row(long id, int status, long noticeEnd, long saleEnd, boolean notice, boolean onSale, boolean buyer, ListingPhase phase) {
        }
        Row[] matrix = {
                new Row(1, LISTED, NOW + HOUR, NOW + 2 * HOUR, true, false, true, ListingPhase.LISTING_PHASE_PUBLIC_NOTICE),
                new Row(2, LISTED, NOW - HOUR, NOW + HOUR, false, true, true, ListingPhase.LISTING_PHASE_ON_SALE),
                new Row(3, LISTED, NOW, NOW + HOUR, false, true, true, ListingPhase.LISTING_PHASE_ON_SALE),          // now == notice_end
                new Row(4, LISTED, NOW - 2 * HOUR, NOW - HOUR, false, false, false, ListingPhase.LISTING_PHASE_ENDED),
                new Row(5, LISTED, NOW - HOUR, NOW, false, false, false, ListingPhase.LISTING_PHASE_ENDED),          // now == sale_end
                new Row(6, LOCKED, NOW - HOUR, NOW + HOUR, false, true, true, ListingPhase.LISTING_PHASE_LOCKED),
                new Row(7, LOCKED, NOW + HOUR, NOW + 2 * HOUR, false, false, true, ListingPhase.LISTING_PHASE_LOCKED), // P1 到不了
                new Row(8, LOCKED, NOW - 2 * HOUR, NOW - HOUR, false, false, false, ListingPhase.LISTING_PHASE_LOCKED),
                new Row(9, SOLD, NOW - HOUR, NOW + HOUR, false, false, false, ListingPhase.LISTING_PHASE_ENDED),
                new Row(10, ListingStatuses.ESCROWING, NOW + HOUR, NOW + 2 * HOUR, false, false, false, ListingPhase.LISTING_PHASE_ENDED),
                new Row(11, ListingStatuses.RETURNED, NOW - HOUR, NOW + HOUR, false, false, false, ListingPhase.LISTING_PHASE_ENDED),
                new Row(12, LISTED, 0, NOW + HOUR, false, true, true, ListingPhase.LISTING_PHASE_ON_SALE),           // 无公示期
        };
        for (Row r : matrix) {
            insert(listing(r.id(), seller, 1, "矩阵剑" + r.id(), 100, 1, r.status(), r.noticeEnd(), r.saleEnd()));
        }
        Set<Long> noticeList = new HashSet<>(ids(store.queryListings(onSale().withTab(NOTICE), 0, 20, d())));
        Set<Long> saleList = new HashSet<>(ids(store.queryListings(onSale(), 0, 20, d())));
        Set<Long> shelf = new HashSet<>(ids(store.querySellerListings(seller, 0, 20, d())));
        for (Row r : matrix) {
            Listing got = store.getListing(r.id(), d()).orElseThrow();
            assertThat(noticeList.contains(r.id())).as("行 %d 公示列表", r.id()).isEqualTo(r.notice());
            assertThat(saleList.contains(r.id())).as("行 %d 寄售列表", r.id()).isEqualTo(r.onSale());
            assertThat(shelf).as("行 %d 货架（卖家本人看任意状态）", r.id()).contains(r.id());
            assertThat(ListingRules.visibleToBuyer(got, NOW, MarketScope.MARKET_SCOPE_GLOBAL, 0)).as("行 %d 详情 / 加收藏（非卖家）", r.id())
                    .isEqualTo(r.buyer());
            assertThat(ListingRules.visibleToBuyer(got, NOW, MarketScope.MARKET_SCOPE_ZONE, 1)).as("行 %d zone 同区", r.id())
                    .isEqualTo(r.buyer());
            assertThat(ListingRules.visibleToBuyer(got, NOW, MarketScope.MARKET_SCOPE_ZONE, 2)).as("行 %d zone 别区", r.id()).isFalse();
            assertThat(ListingRules.phase(got, NOW)).as("行 %d phase", r.id()).isEqualTo(r.phase());
        }
        assertThat(store.countSellerListings(seller, d())).isEqualTo(matrix.length);
    }

    /** 收藏计数不 join 商品表：过期的、已售的、甚至已经不存在的商品的收藏都计入上限（§2.7、Q8 保持基线 → 20002）。 */
    @Test
    void 收藏计数包括看不见的收藏() {
        insert(listing(1, 1, 1, "过期剑", 1, 1, LISTED, 0, NOW - HOUR), listing(2, 1, 1, "已售剑", 1, 1, SOLD, 0, NOW + HOUR),
                listing(3, 1, 1, "在售剑", 1, 1, LISTED, 0, NOW + HOUR));
        store.insertFavorite(9, 1, NOW, d());
        store.insertFavorite(9, 2, NOW, d());
        store.insertFavorite(9, 404, NOW, d()); // 商品不存在
        store.insertFavorite(10, 3, NOW, d());  // 别人的收藏不算
        long count = store.countFavorites(9, d());
        assertThat(count).isEqualTo(3);
        assertThat(ListingRules.favoriteLimitReached(count, 3)).as("上限 3：三条都看不见也照样卡住").isTrue();
        assertThat(ListingRules.favoriteLimitReached(count, 4)).isFalse();
        // 「只看收藏」只列时间窗内的商品：一条都列不出来
        assertThat(store.countListings(onSale().withFavoritesOf(9), d())).isZero();
        // 取消不查商品：能把它们逐个删掉，计数随之下降
        store.deleteFavorite(9, 404, d());
        assertThat(store.countFavorites(9, d())).isEqualTo(2);
    }

    /** 公示中的商品可收藏，「只看收藏 + 公示页签」能看到（robot 增项的存储半边）。 */
    @Test
    void 公示商品只看收藏() {
        insert(listing(1, 1, 1, "公示剑", 1, 1, LISTED, NOW + HOUR, NOW + 2 * HOUR), listing(2, 1, 1, "寄售剑", 1, 1, LISTED, 0, NOW + HOUR));
        store.insertFavorite(9, 1, NOW, d());
        store.insertFavorite(9, 2, NOW, d());
        assertThat(ids(store.queryListings(onSale().withTab(NOTICE).withFavoritesOf(9), 0, 20, d()))).containsExactly(1L);
        assertThat(ids(store.queryListings(onSale().withFavoritesOf(9), 0, 20, d()))).containsExactly(2L);
    }

    /** 每条 SELECT 都带 MAX_EXECUTION_TIME 提示：真库上全部可执行（提示写错的话 MySQL 只告警不报错，这里再核对一遍会话里的告警为空）。 */
    @Test
    void 每条语句在真库上可执行() throws SQLException {
        seedBaseline();
        ListingQuery all = onSale().withMarketZone(1).withSubcategory(1).withSearch("%1%", 1).withFavoritesOf(9)
                .withSort(ListingSort.LISTING_SORT_REMAINING_ASC_VALUE);
        assertThat(store.countListings(all, d())).isZero();
        assertThat(store.queryListings(all, 0, 20, d())).isEmpty();
        assertThat(store.countListings(all.withTab(NOTICE), d())).isZero();
        assertThat(store.favoriteIds(9, List.of(1L, 2L, 3L), d())).isEmpty();
        // 提示语法本身：直接在一条连接上执行带提示的语句，SHOW WARNINGS 必须为空
        try (var c = db.dataSource.getConnection(); var st = c.createStatement()) {
            st.executeQuery(ListingSql.withExecutionTimeHint(ListingSql.COUNT_LISTINGS, 1500)).close();
            try (var rs = st.executeQuery("SHOW WARNINGS")) {
                assertThat(rs.next()).as("MAX_EXECUTION_TIME 提示被 MySQL 认出（没有 1064 / 3126 之类的告警）").isFalse();
            }
        }
    }

    /** 建表幂等：再同步一次不报错、不改表；真库上的三条普通索引与主键都在（pbmysql 只扩不缩）。 */
    @Test
    void 建表幂等且索引齐全() throws SQLException {
        TradeTables.sync(db.dataSource, java.time.Duration.ofMinutes(1));
        List<String> listingIndexes = db.queryStrings("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trade_listing' ORDER BY INDEX_NAME");
        assertThat(listingIndexes).containsExactlyInAnyOrder("idx_trade_listing_0", "idx_trade_listing_1", "idx_trade_listing_2", "PRIMARY");
        List<String> favoriteIndexes = db.queryStrings("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trade_favorite' ORDER BY INDEX_NAME");
        assertThat(favoriteIndexes).containsExactlyInAnyOrder("idx_trade_favorite_0", "PRIMARY");
        List<String> collation = db.queryStrings("SELECT TABLE_COLLATION FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trade_listing'");
        assertThat(collation).containsExactly("utf8mb4_unicode_ci");
    }
}
