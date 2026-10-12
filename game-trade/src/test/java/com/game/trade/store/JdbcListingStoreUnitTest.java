package com.game.trade.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingSort;
import com.game.proto.trade.ListingTab;
import com.game.trade.store.FakeJdbc.Call;
import com.game.trade.store.ListingSql.Stmt;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcListingStore} 的执行层（不连库，trade-spec §5.4、§5.5、§1.9）：每条语句真正发出去的原文（含 MAX_EXECUTION_TIME 提示）与绑定值、
 * 单次上限 {@code min(2000 ms, 请求剩余)} 落到取连接 / 提示 / 查询超时三处、预算用完不取连接、收藏写入的有界重试与错误分类、行映射
 * （无符号、NULL 文本）、连接与语句都归还。真库行为见 {@code JdbcListingStoreMysqlTest}。
 */
class JdbcListingStoreUnitTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final int WEAPON = ListingCategory.LISTING_CATEGORY_WEAPON_VALUE;
    private static final int ON_SALE = ListingTab.LISTING_TAB_ON_SALE_VALUE;
    private static final Pattern HINT = Pattern.compile("^SELECT /\\*\\+ MAX_EXECUTION_TIME\\((\\d+)\\) \\*/ (.*)$");

    private final FakeJdbc jdbc = new FakeJdbc();
    private final AtomicInteger retries = new AtomicInteger();
    private final List<Long> sleeps = new ArrayList<>();

    private JdbcListingStore store(int capSeconds, double jitter) {
        return new JdbcListingStore(jdbc, capSeconds, retries::incrementAndGet, () -> jitter, sleeps::add);
    }

    private JdbcListingStore store() {
        return store(3, 0.5);
    }

    private static Deadline d() {
        return Deadline.after(30_000);
    }

    /** 拆出提示里的毫秒数，并断言去掉提示后就是 {@link ListingSql} 的原文。 */
    private static long assertHinted(Call call, Stmt expected) {
        Matcher m = HINT.matcher(call.sql());
        assertThat(m.matches()).as("SELECT 必须带 MAX_EXECUTION_TIME 提示: %s", call.sql()).isTrue();
        assertThat("SELECT " + m.group(2)).isEqualTo(expected.sql());
        assertThat(call.args()).isEqualTo(expected.args());
        assertThat(call.query()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private void assertAllReturned() {
        assertThat(jdbc.closed).as("连接都要归还").isEqualTo(jdbc.opened);
        assertThat(jdbc.statementsClosed).as("语句都要关闭").isEqualTo(jdbc.calls.size());
    }

    // ================================================================ 读：语句原文与单次上限

    @Test
    void 浏览计数与分页() {
        ListingQuery q = ListingQuery.of(ON_SALE, WEAPON, NOW).withMarketZone(1).withSort(ListingSort.LISTING_SORT_LEVEL_DESC_VALUE);
        jdbc.rows(new Object[] {45L}).rows();
        JdbcListingStore s = store();
        assertThat(s.countListings(q, d())).isEqualTo(45);
        assertThat(s.queryListings(q, 40, 20, d())).isEmpty();

        assertThat(jdbc.calls).hasSize(2);
        long hint = assertHinted(jdbc.calls.get(0), ListingSql.countListings(q));
        assertThat(hint).isBetween(1L, 2_000L);
        assertHinted(jdbc.calls.get(1), ListingSql.queryListings(q, 40, 20));
        assertAllReturned();
    }

    /** 单次上限：请求剩余 30 s 时每次调用只给 2000 ms（取连接、提示、查询超时 ceil = 2 s，且不超过配置上限）。 */
    @Test
    void 单次调用上限是2000毫秒() {
        jdbc.rows(new Object[] {0L}).rows(new Object[] {0L});
        store(3, 0.5).countSellerListings(9, d());
        store(1, 0.5).countSellerListings(9, d());
        assertThat(jdbc.maxWaits).allSatisfy(w -> assertThat(w).isBetween(1L, 2_000L));
        assertThat(assertHinted(jdbc.calls.get(0), ListingSql.countSellerListings(9))).isBetween(1L, 2_000L);
        assertThat(jdbc.calls.get(0).queryTimeoutSeconds()).isEqualTo(2);
        assertThat(jdbc.calls.get(1).queryTimeoutSeconds()).as("配置上限 1 s").isEqualTo(1);
    }

    /** 请求剩余不足 2000 ms 时以剩余为准。 */
    @Test
    void 请求剩余更少时以剩余为准() {
        jdbc.rows(new Object[] {3L});
        assertThat(store().countFavorites(9, Deadline.after(600))).isEqualTo(3);
        assertThat(jdbc.maxWaits.get(0)).isLessThanOrEqualTo(600L);
        assertThat(assertHinted(jdbc.calls.get(0), ListingSql.countFavorites(9))).isLessThanOrEqualTo(600L);
        assertThat(jdbc.calls.get(0).queryTimeoutSeconds()).isEqualTo(1);
    }

    /** 请求预算已经用完：不取连接、不发语句，抛依赖故障（基线 ctx 已到期的调用立即失败）。 */
    @Test
    void 预算用完不取连接() {
        JdbcListingStore s = store();
        Deadline expired = Deadline.after(0);
        assertThatThrownBy(() -> s.countFavorites(9, expired)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> s.getListing(1, expired)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> s.insertFavorite(9, 1, NOW, expired)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> s.deleteFavorite(9, 1, expired)).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> s.insertListing(listing(1), expired)).isInstanceOf(DependencyException.class);
        assertThat(jdbc.maxWaits).isEmpty();
        assertThat(jdbc.calls).isEmpty();
    }

    @Test
    void 取连接失败与语句失败都是依赖故障() {
        jdbc.connectFailure = new SQLException("连不上");
        assertThatThrownBy(() -> store().favoriteExists(9, 1, d())).isInstanceOf(DependencyException.class)
                .hasRootCauseMessage("连不上");
        jdbc.connectFailure = null;
        jdbc.fail(new SQLException("Query execution was interrupted, maximum statement execution time exceeded", "HY000", 3024));
        assertThatThrownBy(() -> store().favoriteExists(9, 1, d())).isInstanceOf(DependencyException.class);
        assertAllReturned();
    }

    /** 查询描述非法（服务层已校验，走到就是程序错误）：不取连接，抛 IllegalArgumentException（调用方同样定性 1003）。 */
    @Test
    void 非法查询不碰库() {
        assertThatThrownBy(() -> store().countListings(ListingQuery.of(0, WEAPON, NOW), d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().queryListings(ListingQuery.of(ON_SALE, WEAPON, NOW).withSort(9), 0, 20, d()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.maxWaits).isEmpty();
    }

    // ================================================================ 行映射

    private static final long BIG = 0x8000_0000_0000_0005L;

    private static Listing listing(long id) {
        return new Listing(id, BIG, "acct", 0xFFFF_FFFF, 7, WEAPON, 0x8000_0000, "青锋剑", 1000, -2L, ListingStatuses.LOCKED,
                "摘要", "描述", "icon_a", 1_000, -1L, 900, 950, 3);
    }

    /** 按 SUMMARY_COLUMNS 的列序造一行（驱动对 bigint unsigned 给 BigInteger、int unsigned 给 Long、int 给 Integer）。 */
    private static Object[] row(Listing l, boolean withDescription, boolean nullTexts) {
        List<Object> cols = new ArrayList<>(List.of(
                big(l.listingId()), big(l.sellerPlayerId())));
        cols.add(nullTexts ? null : l.sellerAccount());
        cols.add(Integer.toUnsignedLong(l.marketZone()));
        cols.add(Integer.toUnsignedLong(l.sellerZoneAtListing()));
        cols.add(l.category());
        cols.add(Integer.toUnsignedLong(l.subcategory()));
        cols.add(nullTexts ? null : l.title());
        cols.add(Integer.toUnsignedLong(l.level()));
        cols.add(big(l.priceFen()));
        cols.add(l.status());
        cols.add(nullTexts ? null : l.summary());
        cols.add(nullTexts ? null : l.iconKey());
        cols.add(big(l.noticeEndMs()));
        cols.add(big(l.saleEndMs()));
        cols.add(big(l.createdMs()));
        cols.add(big(l.updatedMs()));
        cols.add(big(l.version()));
        if (withDescription) {
            cols.add(nullTexts ? null : l.description());
        }
        return cols.toArray();
    }

    private static BigInteger big(long bits) {
        return new BigInteger(Long.toUnsignedString(bits));
    }

    @Test
    void 列表行映射_无符号且不取描述() {
        Listing l = listing(BIG + 1);
        jdbc.rows(row(l, false, false), row(listing(2), false, false));
        List<Listing> got = store().querySellerListings(BIG, 0, 20, d());
        assertThat(got).containsExactly(l.withoutDescription(), listing(2).withoutDescription());
        assertHinted(jdbc.calls.get(0), ListingSql.querySellerListings(BIG, 0, 20));
    }

    @Test
    void 详情取全列_不存在是空() {
        Listing l = listing(101);
        jdbc.rows(row(l, true, false)).rows();
        assertThat(store().getListing(101, d())).contains(l);
        assertThat(store().getListing(999, d())).isEqualTo(Optional.empty());
        assertHinted(jdbc.calls.get(0), ListingSql.getListing(101));
        assertHinted(jdbc.calls.get(1), ListingSql.getListing(999));
        assertAllReturned();
    }

    /** §1.5 / §5.9 第 8 条：MEDIUMTEXT 读回 NULL 统一换成空串（不走 NPE 路径）。 */
    @Test
    void NULL文本读回空串() {
        jdbc.rows(row(listing(101), true, true));
        Listing got = store().getListing(101, d()).orElseThrow();
        assertThat(got.sellerAccount()).isEmpty();
        assertThat(got.title()).isEmpty();
        assertThat(got.summary()).isEmpty();
        assertThat(got.iconKey()).isEmpty();
        assertThat(got.description()).isEmpty();
    }

    // ================================================================ 收藏

    @Test
    void 收藏批量查_空列表不查库() {
        assertThat(store().favoriteIds(9, List.of(), d())).isEmpty();
        assertThat(jdbc.maxWaits).as("ids 为空不取连接").isEmpty();

        jdbc.rows(new Object[] {big(Long.MIN_VALUE)}, new Object[] {101L});
        Set<Long> got = store().favoriteIds(9, List.of(Long.MIN_VALUE, 101L, 102L), d());
        assertThat(got).containsExactlyInAnyOrder(Long.MIN_VALUE, 101L);
        assertHinted(jdbc.calls.get(0), ListingSql.favoriteIds(9, List.of(Long.MIN_VALUE, 101L, 102L)));
    }

    @Test
    void 收藏存在性() {
        jdbc.rows(new Object[] {1}).rows();
        assertThat(store().favoriteExists(9, 101, d())).isTrue();
        assertThat(store().favoriteExists(9, 102, d())).isFalse();
        assertHinted(jdbc.calls.get(0), ListingSql.favoriteExists(9, 101));
    }

    @Test
    void 写语句不带提示_原文与参数() {
        jdbc.updated(1).updated(0).updated(1);
        JdbcListingStore s = store();
        s.insertFavorite(9, 101, NOW, d());
        s.deleteFavorite(9, 101, d());
        s.insertListing(listing(101), d());
        assertThat(jdbc.calls).extracting(Call::sql).containsExactly(ListingSql.INSERT_FAVORITE, ListingSql.DELETE_FAVORITE,
                ListingSql.INSERT_LISTING);
        assertThat(jdbc.calls.get(0).args()).isEqualTo(ListingSql.insertFavorite(9, 101, NOW).args());
        assertThat(jdbc.calls.get(1).args()).isEqualTo(ListingSql.deleteFavorite(9, 101).args());
        assertThat(jdbc.calls.get(2).args()).isEqualTo(ListingSql.insertListing(listing(101)).args());
        assertThat(jdbc.calls).allSatisfy(c -> {
            assertThat(c.query()).isFalse();
            assertThat(c.queryTimeoutSeconds()).isBetween(1, 2);
        });
        assertThat(retries).hasValue(0);
        assertAllReturned();
    }

    /** L6：主键冲突按故障抛出、不重试（listing_repo.go:187-188）。 */
    @Test
    void 商品插入撞主键是故障且不重试() {
        jdbc.fail(FakeJdbc.sqlError(1062));
        assertThatThrownBy(() -> store().insertListing(listing(101), d())).isInstanceOf(DependencyException.class);
        assertThat(jdbc.calls).hasSize(1);
        assertThat(retries).hasValue(0);
    }

    /** §1.9：1213 / 1205 / 9007 整条重跑一次，退避 10 ms（jitter 0.5），计一次重跑。 */
    @Test
    void 收藏写入撞可重试错误重跑一次() {
        for (int code : new int[] {1213, 1205, 9007}) {
            int before = jdbc.calls.size();
            sleeps.clear();
            retries.set(0);
            jdbc.fail(FakeJdbc.sqlError(code)).updated(1);
            store().insertFavorite(9, 101, NOW, d());
            List<Call> sent = jdbc.calls.subList(before, jdbc.calls.size());
            assertThat(sent).as("错误号 %d", code).hasSize(2);
            assertThat(sent).extracting(Call::sql).containsOnly(ListingSql.INSERT_FAVORITE);
            assertThat(retries).as("错误号 %d", code).hasValue(1);
            assertThat(sleeps).as("错误号 %d", code).containsExactly(10L);
        }
        assertAllReturned();
    }

    /** 错误号沿 cause 链取：包了一层的 1213 照样重跑。 */
    @Test
    void 包在cause里的死锁也重跑() {
        jdbc.fail(new SQLException("外层", "HY000", 0, FakeJdbc.sqlError(1213))).updated(1);
        store().insertFavorite(9, 101, NOW, d());
        assertThat(retries).hasValue(1);
    }

    @Test
    void 两次都失败抛依赖故障() {
        jdbc.fail(FakeJdbc.sqlError(1213)).fail(FakeJdbc.sqlError(1213));
        assertThatThrownBy(() -> store().insertFavorite(9, 101, NOW, d())).isInstanceOf(DependencyException.class);
        assertThat(jdbc.calls).hasSize(2);
        assertThat(retries).hasValue(1);
        assertThat(jdbc.script).isEmpty();
    }

    @Test
    void 不可重试的错误不重跑() {
        for (SQLException e : new SQLException[] {FakeJdbc.sqlError(1062), FakeJdbc.sqlError(1146), new SQLException("断连")}) {
            int before = jdbc.calls.size();
            jdbc.fail(e);
            assertThatThrownBy(() -> store().insertFavorite(9, 101, NOW, d())).isInstanceOf(DependencyException.class);
            assertThat(jdbc.calls.size() - before).isEqualTo(1);
        }
        assertThat(retries).hasValue(0);
        assertThat(sleeps).isEmpty();
        assertAllReturned();
    }

    /** 剩余预算不够一次退避（≤ 12 ms）就不再重跑（基线可取消的退避：ctx 到期立刻返回）。 */
    @Test
    void 剩余预算不够退避就不重跑() {
        jdbc.fail(FakeJdbc.sqlError(1213)).updated(1);
        assertThatThrownBy(() -> store().insertFavorite(9, 101, NOW, Deadline.after(8))).isInstanceOf(DependencyException.class);
        assertThat(retries).hasValue(0);
        assertThat(sleeps).isEmpty();
        assertThat(jdbc.calls).hasSizeLessThanOrEqualTo(1);
    }

    /**
     * 退避 = 10 ms × (0.8 + 0.4 × jitter) 向下取整 → 8–12 ms；越界的 jitter 夹回 [0,1)（decide.go:144-155）。上沿与 Go 相同：
     * {@code 0.8 + 0.4 × Nextafter(1, 0)} 舍入成 1.2，所以夹回之后得到 12 而不是 11。
     */
    @Test
    void 退避区间() {
        assertThat(store(3, 0.0).backoffMillis()).isEqualTo(8);
        assertThat(store(3, 0.5).backoffMillis()).isEqualTo(10);
        assertThat(store(3, 0.999).backoffMillis()).isEqualTo(11);
        assertThat(store(3, 1.0).backoffMillis()).isEqualTo(12);
        assertThat(store(3, 7.0).backoffMillis()).isEqualTo(12);
        assertThat(store(3, -1.0).backoffMillis()).isEqualTo(8);
        assertThat(store(3, Double.NaN).backoffMillis()).isEqualTo(8);
    }

    @Test
    void 错误分类() {
        assertThat(JdbcListingStore.isRetryable(FakeJdbc.sqlError(1213))).isTrue();
        assertThat(JdbcListingStore.isRetryable(FakeJdbc.sqlError(1205))).isTrue();
        assertThat(JdbcListingStore.isRetryable(FakeJdbc.sqlError(9007))).isTrue();
        assertThat(JdbcListingStore.isRetryable(FakeJdbc.sqlError(1062))).isFalse();
        assertThat(JdbcListingStore.isRetryable(new SQLException("无错误号"))).isFalse();
        assertThat(JdbcListingStore.isRetryable(new RuntimeException("包装", FakeJdbc.sqlError(1213)))).isTrue();
        assertThat(JdbcListingStore.isRetryable(null)).isFalse();
        assertThat(JdbcListingStore.errorCode(new RuntimeException(new SQLException("x", "HY000", 0, FakeJdbc.sqlError(1205)))))
                .isEqualTo(1205);
    }
}
