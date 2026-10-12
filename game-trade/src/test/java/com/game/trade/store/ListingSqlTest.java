package com.game.trade.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingSort;
import com.game.proto.trade.ListingTab;
import com.game.trade.store.ListingSql.Filter;
import com.game.trade.store.ListingSql.Stmt;
import com.game.trade.store.pb.TradeListingRow;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * SQL 构造的纯单测（不连库；基线 listing_repo_test.go 全文，trade-spec §9.3「不连库」）：条件、参数顺序与类型、排序映射、列清单、
 * 收藏写入的锁模式，以及每条语句（L1–L6、F1–F5）的原文。真库行为见 {@code JdbcListingStoreMysqlTest}。
 */
class ListingSqlTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final Integer LISTED = ListingStatuses.LISTED;
    private static final Integer LOCKED = ListingStatuses.LOCKED;
    private static final int WEAPON = ListingCategory.LISTING_CATEGORY_WEAPON_VALUE;
    private static final int NOTICE = ListingTab.LISTING_TAB_PUBLIC_NOTICE_VALUE;
    private static final int ON_SALE = ListingTab.LISTING_TAB_ON_SALE_VALUE;
    private static final String ON_SALE_WINDOW = " WHERE `status` IN (?, ?) AND `notice_end_ms` <= ? AND `sale_end_ms` > ?";

    // ================================================================ buildListingFilter（listing_repo_test.go:21-86）

    @Test
    void 公示中加类目() {
        assertFilter(ListingQuery.of(NOTICE, WEAPON, NOW),
                " WHERE `status` = ? AND `notice_end_ms` > ? AND `category` = ?",
                LISTED, NOW, WEAPON);
    }

    @Test
    void 寄售中含LOCKED加分区加子类() {
        assertFilter(ListingQuery.of(ON_SALE, WEAPON, NOW).withSubcategory(2).withMarketZone(7),
                ON_SALE_WINDOW + " AND `market_zone` = ? AND `category` = ? AND `subcategory` = ?",
                LISTED, LOCKED, NOW, NOW, 7L, WEAPON, 2L);
    }

    @Test
    void 非数字搜索只按标题() {
        assertFilter(ListingQuery.of(ON_SALE, WEAPON, NOW).withSearch("%a!_b%", 0),
                ON_SALE_WINDOW + " AND `category` = ? AND `title` LIKE ? ESCAPE '!'",
                LISTED, LOCKED, NOW, NOW, WEAPON, "%a!_b%");
    }

    @Test
    void 数字搜索同时按编号() {
        assertFilter(ListingQuery.of(ON_SALE, WEAPON, NOW).withSearch("%123%", 123),
                ON_SALE_WINDOW + " AND `category` = ? AND (`listing_id` = ? OR `title` LIKE ? ESCAPE '!')",
                LISTED, LOCKED, NOW, NOW, WEAPON, 123L, "%123%");
    }

    @Test
    void 只看收藏() {
        assertFilter(ListingQuery.of(NOTICE, WEAPON, NOW).withFavoritesOf(42),
                " WHERE `status` = ? AND `notice_end_ms` > ? AND `category` = ? AND "
                        + "EXISTS (SELECT 1 FROM trade_favorite f WHERE f.`player_id` = ? AND f.`listing_id` = trade_listing.`listing_id`)",
                LISTED, NOW, WEAPON, 42L);
    }

    /** 全部条件同时出现时的拼接顺序：页签 → 分区 → 类目 → 子类 → 搜索 → 收藏。 */
    @Test
    void 全部条件的拼接顺序() {
        assertFilter(ListingQuery.of(ON_SALE, WEAPON, NOW).withMarketZone(3).withSubcategory(1).withSearch("%9%", 9).withFavoritesOf(5),
                ON_SALE_WINDOW + " AND `market_zone` = ? AND `category` = ? AND `subcategory` = ?"
                        + " AND (`listing_id` = ? OR `title` LIKE ? ESCAPE '!')"
                        + " AND EXISTS (SELECT 1 FROM trade_favorite f WHERE f.`player_id` = ? AND f.`listing_id` = trade_listing.`listing_id`)",
                LISTED, LOCKED, NOW, NOW, 3L, WEAPON, 1L, 9L, "%9%", 5L);
    }

    /** 编号只在有搜索词时生效（listing_repo.go:386-394）。 */
    @Test
    void 没有搜索词时编号不生效() {
        assertFilter(ListingQuery.of(NOTICE, WEAPON, NOW).withSearch("", 123),
                " WHERE `status` = ? AND `notice_end_ms` > ? AND `category` = ?",
                LISTED, NOW, WEAPON);
    }

    /** Java 增项：uint32 / uint64 位模式的绑定值（≥ 2^31 / 2^63 不能按负数绑）。 */
    @Test
    void 无符号参数的绑定形式() {
        long hugeId = 0x8000_0000_0000_0001L;
        long hugeNow = -1L;
        Filter f = ListingSql.filter(new ListingQuery(0xFFFF_FFFF, NOTICE, WEAPON, 0x8000_0000, "%x%", hugeId, hugeId, 0, hugeNow));
        assertThat(f.args()).containsExactly(LISTED, new BigInteger("18446744073709551615"), 4294967295L, WEAPON, 2147483648L,
                new BigInteger("9223372036854775809"), "%x%", new BigInteger("9223372036854775809"));
    }

    @Test
    void 不完整的查询必须报错() {
        assertThatThrownBy(() -> ListingSql.filter(ListingQuery.of(0, WEAPON, NOW)))
                .as("缺 tab 必须报错，不能退化成不带状态条件的全表查询").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingSql.filter(ListingQuery.of(3, WEAPON, NOW))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingSql.filter(ListingQuery.of(ON_SALE, 0, NOW)))
                .as("缺类目必须报错").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingSql.countListings(ListingQuery.of(-1, WEAPON, NOW))).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertFilter(ListingQuery q, String wantSql, Object... wantArgs) {
        Filter f = ListingSql.filter(q);
        assertThat(f.where()).isEqualTo(wantSql);
        assertThat(f.args()).containsExactly(wantArgs);
        assertBindTypes(f.args(), wantArgs);
        assertThat(placeholders(f.where())).as("占位符与参数个数").isEqualTo(f.args().size());
    }

    /** 参数的 Java 类型必须逐个相同（Integer = 有符号 int 列，Long = 无符号列）：containsExactly 只按 equals 比，Integer 2 ≠ Long 2 已能区分，这里再显式核一遍。 */
    private static void assertBindTypes(List<Object> got, Object[] want) {
        for (int i = 0; i < want.length; i++) {
            assertThat(got.get(i)).as("第 %d 个参数的类型", i + 1).isExactlyInstanceOf(want[i].getClass());
        }
    }

    // ================================================================ listingOrderBy（listing_repo_test.go:88-118）

    @Test
    void 排序映射() {
        assertThat(ListingSql.orderBy(ListingSort.LISTING_SORT_DEFAULT_VALUE, ON_SALE)).isEqualTo("`listing_id` DESC");
        assertThat(ListingSql.orderBy(ListingSort.LISTING_SORT_PRICE_ASC_VALUE, ON_SALE)).isEqualTo("`price_fen` ASC, `listing_id` ASC");
        assertThat(ListingSql.orderBy(ListingSort.LISTING_SORT_PRICE_DESC_VALUE, ON_SALE)).isEqualTo("`price_fen` DESC, `listing_id` DESC");
        assertThat(ListingSql.orderBy(ListingSort.LISTING_SORT_LEVEL_DESC_VALUE, ON_SALE)).isEqualTo("`level` DESC, `listing_id` DESC");
        assertThat(ListingSql.orderBy(ListingSort.LISTING_SORT_REMAINING_ASC_VALUE, NOTICE)).isEqualTo("`notice_end_ms` ASC, `listing_id` ASC");
        assertThat(ListingSql.orderBy(ListingSort.LISTING_SORT_REMAINING_ASC_VALUE, ON_SALE)).isEqualTo("`sale_end_ms` ASC, `listing_id` ASC");
        assertThatThrownBy(() -> ListingSql.orderBy(99, ON_SALE)).as("未知排序必须报错，不能回落到任意 ORDER BY")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListingSql.orderBy(-1, ON_SALE)).isInstanceOf(IllegalArgumentException.class);
        // proto 里每个排序值都必须有映射，且都以 listing_id 作最终决胜列
        for (ListingSort sort : ListingSort.values()) {
            if (sort == ListingSort.UNRECOGNIZED) {
                continue;
            }
            for (int tab : new int[] {NOTICE, ON_SALE}) {
                String order = ListingSql.orderBy(sort.getNumber(), tab);
                assertThat(order).as("%s 的决胜列", sort).matches(".*`listing_id` (ASC|DESC)$");
            }
        }
    }

    // ================================================================ 收藏写入的锁模式（listing_repo_test.go:120-138）

    /**
     * 重复键检查取什么锁由语句形状决定：INSERT IGNORE 取 S，排在同一条删除标记记录后面的并发收藏会同时拿到、再各自升 X 就成环（1213）；
     * ODKU 直接取 X。改回 INSERT IGNORE 之后功能用例照样绿、死锁却回来了，而能抓住它的真库回归默认不跑，所以在这里用纯文本再拦一道。
     */
    @Test
    void 收藏写入是ODKU不是INSERT_IGNORE() {
        String normalized = String.join(" ", ListingSql.INSERT_FAVORITE.trim().split("\\s+")).toUpperCase(Locale.ROOT);
        assertThat(normalized).as("必须是 INSERT … ON DUPLICATE KEY UPDATE（重复键检查直接取 X）").contains("ON DUPLICATE KEY UPDATE");
        // 按词比对：INSERT LOW_PRIORITY IGNORE 之类的写法同样取 S
        assertThat(Arrays.asList(normalized.split(" "))).as("不得带 IGNORE").doesNotContain("IGNORE");
        // 空更新：不刷新原收藏时间
        assertThat(ListingSql.INSERT_FAVORITE).endsWith("ON DUPLICATE KEY UPDATE `created_ms` = `created_ms`");
        // 商品插入：主键冲突按故障，不许 IGNORE / ODKU（listing_repo.go:187-188）
        String listing = ListingSql.INSERT_LISTING.toUpperCase(Locale.ROOT);
        assertThat(listing).doesNotContain("IGNORE").doesNotContain("DUPLICATE");
    }

    // ================================================================ 列清单（listing_repo_test.go:140-158）

    @Test
    void 列清单18与19列且覆盖表的全部字段() {
        List<String> summary = columns(ListingSql.SUMMARY_COLUMNS);
        List<String> detail = columns(ListingSql.DETAIL_COLUMNS);
        assertThat(summary).hasSize(ListingSql.SUMMARY_COLUMN_COUNT).hasSize(18);
        assertThat(detail).hasSize(ListingSql.DETAIL_COLUMN_COUNT).hasSize(19);
        // 详情列 = 表的全部字段；摘要列 = 去掉 description、其余按字段号顺序（JdbcListingStore.readListing 按这个顺序读）
        List<String> fields = TradeListingRow.getDescriptor().getFields().stream().map(FieldDescriptor::getName).toList();
        assertThat(detail).containsExactlyInAnyOrderElementsOf(fields);
        List<String> withoutDescription = new ArrayList<>(fields);
        withoutDescription.remove("description");
        assertThat(summary).containsExactlyElementsOf(withoutDescription);
        assertThat(detail.get(18)).isEqualTo("description");
        // 列名一律加反引号
        for (String col : ListingSql.DETAIL_COLUMNS.split(", ")) {
            assertThat(col).matches("`[a-z_]+`");
        }
    }

    private static List<String> columns(String list) {
        return Arrays.stream(list.split(",")).map(s -> s.trim().replace("`", "")).toList();
    }

    // ================================================================ 每条语句（listing_repo.go L1–L6、F1–F5，逐字）

    @Test
    void 浏览两条语句() {
        ListingQuery q = ListingQuery.of(ON_SALE, WEAPON, NOW).withMarketZone(1).withSort(ListingSort.LISTING_SORT_PRICE_ASC_VALUE);
        Stmt count = ListingSql.countListings(q);
        assertThat(count.sql()).isEqualTo("SELECT COUNT(*) FROM trade_listing" + ON_SALE_WINDOW
                + " AND `market_zone` = ? AND `category` = ?");
        assertThat(count.args()).containsExactly(LISTED, LOCKED, NOW, NOW, 1L, WEAPON);

        Stmt page = ListingSql.queryListings(q, 40, 20);
        assertThat(page.sql()).isEqualTo("SELECT " + ListingSql.SUMMARY_COLUMNS + " FROM trade_listing" + ON_SALE_WINDOW
                + " AND `market_zone` = ? AND `category` = ? ORDER BY `price_fen` ASC, `listing_id` ASC LIMIT ? OFFSET ?");
        assertThat(page.args()).containsExactly(LISTED, LOCKED, NOW, NOW, 1L, WEAPON, 20L, 40L);
        assertThat(page.sql()).doesNotContain("`description`");
    }

    @Test
    void 货架两条语句() {
        assertStmt(ListingSql.countSellerListings(9), "SELECT COUNT(*) FROM trade_listing WHERE `seller_player_id` = ?", 9L);
        assertStmt(ListingSql.querySellerListings(9, 20, 4),
                "SELECT " + ListingSql.SUMMARY_COLUMNS + " FROM trade_listing WHERE `seller_player_id` = ?"
                        + " ORDER BY `listing_id` DESC LIMIT ? OFFSET ?",
                9L, 4L, 20L);
    }

    @Test
    void 详情与插入() {
        assertStmt(ListingSql.getListing(101), "SELECT " + ListingSql.DETAIL_COLUMNS + " FROM trade_listing WHERE `listing_id` = ?", 101L);
        Listing l = new Listing(101, 7, "acct", 1, 2, WEAPON, 3, "青锋剑", 60, 123_456, ListingStatuses.LISTED, "摘要", "描述",
                "icon_a", 1_000, 2_000, 900, 950, 0);
        Stmt insert = ListingSql.insertListing(l);
        assertThat(insert.sql()).isEqualTo("INSERT INTO trade_listing (" + ListingSql.DETAIL_COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        // 按详情列序：description 在最后
        assertThat(insert.args()).containsExactly(101L, 7L, "acct", 1L, 2L, WEAPON, 3L, "青锋剑", 60L, 123_456L, LISTED, "摘要",
                "icon_a", 1_000L, 2_000L, 900L, 950L, 0L, "描述");
        assertThat(placeholders(insert.sql())).isEqualTo(19);
    }

    @Test
    void 收藏五条语句() {
        assertStmt(ListingSql.favoriteIds(9, List.of(101L, 102L, 103L)),
                "SELECT `listing_id` FROM trade_favorite WHERE `player_id` = ? AND `listing_id` IN (?,?,?)", 9L, 101L, 102L, 103L);
        assertStmt(ListingSql.favoriteIds(9, List.of(101L)),
                "SELECT `listing_id` FROM trade_favorite WHERE `player_id` = ? AND `listing_id` IN (?)", 9L, 101L);
        assertThatThrownBy(() -> ListingSql.favoriteIds(9, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertStmt(ListingSql.favoriteExists(9, 101), "SELECT 1 FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?", 9L, 101L);
        assertStmt(ListingSql.countFavorites(9), "SELECT COUNT(*) FROM trade_favorite WHERE `player_id` = ?", 9L);
        assertStmt(ListingSql.insertFavorite(9, 101, NOW),
                "INSERT INTO trade_favorite (`player_id`, `listing_id`, `created_ms`) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE `created_ms` = `created_ms`", 9L, 101L, NOW);
        assertStmt(ListingSql.deleteFavorite(9, 101), "DELETE FROM trade_favorite WHERE `player_id` = ? AND `listing_id` = ?", 9L, 101L);
    }

    @Test
    void 大号的绑定值() {
        long big = Long.MIN_VALUE; // 2^63
        Object bigBind = new BigInteger("9223372036854775808");
        assertThat(ListingSql.getListing(big).args()).containsExactly(bigBind);
        assertThat(ListingSql.insertFavorite(big, -1L, 5).args())
                .containsExactly(bigBind, new BigInteger("18446744073709551615"), 5L);
        assertThat(ListingSql.querySellerListings(big, -2L, 0xFFFF_FFFF).args())
                .containsExactly(bigBind, 4294967295L, new BigInteger("18446744073709551614"));
        assertThat(ListingSql.u64(0)).isEqualTo(0L);
        assertThat(ListingSql.u64(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(ListingSql.u32(-1)).isEqualTo(4294967295L);
    }

    /** §5.4：SELECT 加 MAX_EXECUTION_TIME 提示（毫秒，至少 1）；写语句原样。 */
    @Test
    void 执行时限提示() {
        assertThat(ListingSql.withExecutionTimeHint(ListingSql.COUNT_FAVORITES, 1999))
                .isEqualTo("SELECT /*+ MAX_EXECUTION_TIME(1999) */ COUNT(*) FROM trade_favorite WHERE `player_id` = ?");
        assertThat(ListingSql.withExecutionTimeHint(ListingSql.FAVORITE_EXISTS, 0))
                .startsWith("SELECT /*+ MAX_EXECUTION_TIME(1) */ 1 FROM trade_favorite");
        assertThat(ListingSql.withExecutionTimeHint(ListingSql.INSERT_FAVORITE, 500)).isEqualTo(ListingSql.INSERT_FAVORITE);
        assertThat(ListingSql.withExecutionTimeHint(ListingSql.DELETE_FAVORITE, 500)).isEqualTo(ListingSql.DELETE_FAVORITE);
    }

    private static void assertStmt(Stmt stmt, String sql, Object... args) {
        assertThat(stmt.sql()).isEqualTo(sql);
        assertThat(stmt.args()).containsExactly(args);
        assertBindTypes(stmt.args(), args);
        assertThat(placeholders(stmt.sql())).as("占位符与参数个数").isEqualTo(stmt.args().size());
    }

    private static int placeholders(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }
}
