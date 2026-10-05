package com.game.trade.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingPhase;
import com.game.proto.trade.ListingSort;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SeedListingRequest;
import com.game.trade.rules.ListingRules.PageWindow;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingStatuses;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * 纯函数（trade-spec §9.1）：移植基线 phase_test.go 的表驱动用例与 constants_test.go:189 TestMaxSubcategory，外加 §5.9 / §9.1 的 Java 增项
 * （Go 空白与控制字符边界、码点计数、编号搜索、无符号位模式、未知枚举值）。
 */
class ListingRulesTest {

    private static final long NOW = 1000;
    private static final int LISTED = ListingStatuses.LISTED;
    private static final int LOCKED = ListingStatuses.LOCKED;
    private static final MarketScope ZONE = MarketScope.MARKET_SCOPE_ZONE;
    private static final MarketScope GLOBAL = MarketScope.MARKET_SCOPE_GLOBAL;

    /** 基线 listingRec（phase_test.go:13-15）。 */
    private static Listing rec(int status, long noticeEnd, long saleEnd, int zone) {
        return new Listing(1, 2, "", zone, zone, ListingCategory.LISTING_CATEGORY_WEAPON_VALUE, 1, "t", 1, 1, status,
                "", "", "", noticeEnd, saleEnd, 0, 0, 0);
    }

    // ================================================================ Phase（phase_test.go:17-40）

    @Test
    void 阶段推导() {
        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("公示中", new Object[] {rec(LISTED, 2000, 3000, 1), ListingPhase.LISTING_PHASE_PUBLIC_NOTICE});
        cases.put("now == notice_end 进入寄售", new Object[] {rec(LISTED, 1000, 3000, 1), ListingPhase.LISTING_PHASE_ON_SALE});
        cases.put("无公示期直接寄售", new Object[] {rec(LISTED, 0, 3000, 1), ListingPhase.LISTING_PHASE_ON_SALE});
        cases.put("now == sale_end 结束", new Object[] {rec(LISTED, 500, 1000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.put("LOCKED", new Object[] {rec(LOCKED, 500, 3000, 1), ListingPhase.LISTING_PHASE_LOCKED});
        cases.put("LOCKED 不看时间", new Object[] {rec(LOCKED, 0, 500, 1), ListingPhase.LISTING_PHASE_LOCKED});
        cases.put("SOLD", new Object[] {rec(ListingStatuses.SOLD, 0, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.put("ESCROWING", new Object[] {rec(ListingStatuses.ESCROWING, 2000, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.put("UNSPECIFIED", new Object[] {rec(ListingStatuses.UNSPECIFIED, 2000, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        // Java 增项：其余 P3 状态与库里出现的未知值也都是 ENDED
        cases.put("RETURNING", new Object[] {rec(ListingStatuses.RETURNING, 0, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.put("RETURNED", new Object[] {rec(ListingStatuses.RETURNED, 0, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.put("ESCROW_REJECTED", new Object[] {rec(ListingStatuses.ESCROW_REJECTED, 0, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.put("未知状态 99", new Object[] {rec(99, 0, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.put("负状态", new Object[] {rec(-1, 0, 3000, 1), ListingPhase.LISTING_PHASE_ENDED});
        cases.forEach((name, c) -> assertThat(ListingRules.phase((Listing) c[0], NOW)).as(name).isEqualTo(c[1]));
    }

    /** Java 增项：毫秒字段 ≥ 2^63（位模式为负）按无符号比较。 */
    @Test
    void 阶段推导按无符号比较时间() {
        long huge = 0x8000_0000_0000_0000L;
        assertThat(ListingRules.phase(rec(LISTED, huge, huge + 1, 1), NOW)).isEqualTo(ListingPhase.LISTING_PHASE_PUBLIC_NOTICE);
        assertThat(ListingRules.phase(rec(LISTED, 0, huge, 1), NOW)).isEqualTo(ListingPhase.LISTING_PHASE_ON_SALE);
        assertThat(ListingRules.phase(rec(LISTED, 0, 3000, 1), huge)).isEqualTo(ListingPhase.LISTING_PHASE_ENDED);
        assertThat(ListingRules.phase(LISTED, -1L, -1L, -2L)).isEqualTo(ListingPhase.LISTING_PHASE_PUBLIC_NOTICE);
    }

    // ================================================================ VisibleToBuyer（phase_test.go:42-70）

    @Test
    void 买家可见性() {
        record Case(String name, Listing rec, MarketScope scope, int caller, boolean want) {
        }
        Case[] cases = {
                new Case("zone 同区寄售中", rec(LISTED, 0, 3000, 1), ZONE, 1, true),
                new Case("zone 同区公示中", rec(LISTED, 2000, 3000, 1), ZONE, 1, true),
                new Case("zone 别区", rec(LISTED, 0, 3000, 1), ZONE, 2, false),
                new Case("zone 调用者 home_zone=0 一律不可见", rec(LISTED, 0, 3000, 0), ZONE, 0, false),
                new Case("global 不看分区", rec(LISTED, 0, 3000, 1), GLOBAL, 0, true),
                new Case("LOCKED 寄售期内可见", rec(LOCKED, 0, 3000, 1), ZONE, 1, true),
                new Case("LISTED 已过寄售期", rec(LISTED, 0, 1000, 1), GLOBAL, 0, false),
                new Case("LOCKED 已过寄售期", rec(LOCKED, 0, 999, 1), GLOBAL, 0, false),
                new Case("SOLD", rec(ListingStatuses.SOLD, 0, 3000, 1), GLOBAL, 0, false),
                new Case("scope 未指定 fail-closed", rec(LISTED, 0, 3000, 1), MarketScope.MARKET_SCOPE_UNSPECIFIED, 1, false),
                // Java 增项
                new Case("scope UNRECOGNIZED fail-closed", rec(LISTED, 0, 3000, 1), MarketScope.UNRECOGNIZED, 1, false),
                new Case("ESCROWING 不可见", rec(ListingStatuses.ESCROWING, 0, 3000, 1), GLOBAL, 0, false),
                new Case("LOCKED 公示期内（P1 到不了）可见", rec(LOCKED, 2000, 3000, 1), GLOBAL, 0, true),
                new Case("zone 分区 ≥ 2^31 按位相等", rec(LISTED, 0, 3000, 0x8000_0001), ZONE, 0x8000_0001, true),
        };
        for (Case c : cases) {
            assertThat(ListingRules.visibleToBuyer(c.rec(), NOW, c.scope(), c.caller())).as(c.name()).isEqualTo(c.want());
        }
    }

    @Test
    void 范围字符串只认字面zone与global() {
        assertThat(ListingRules.scopeOf("zone")).isEqualTo(ZONE);
        assertThat(ListingRules.scopeOf("global")).isEqualTo(GLOBAL);
        for (String bad : new String[] {"", "Zone", "GLOBAL", " zone", "zone ", "all"}) {
            assertThat(ListingRules.scopeOf(bad)).as(bad).isEqualTo(MarketScope.MARKET_SCOPE_UNSPECIFIED);
        }
        assertThat(ListingRules.scopeOf(null)).isEqualTo(MarketScope.MARKET_SCOPE_UNSPECIFIED);
    }

    // ================================================================ ClampPageSize / PageWindow（phase_test.go:72-110）

    @Test
    void 钳制页长() {
        int[][] cases = {{0, 20, 20, 20}, {4, 20, 20, 4}, {20, 20, 20, 20}, {50, 20, 20, 20}, {0, 10, 20, 10}};
        for (int[] c : cases) {
            assertThat(ListingRules.clampPageSize(c[0], c[1], c[2])).as("ClampPageSize(%d, %d, %d)", c[0], c[1], c[2]).isEqualTo(c[3]);
        }
        // 只防除零
        assertThat(ListingRules.clampPageSize(0, 0, 20)).isEqualTo(1);
        assertThat(ListingRules.clampPageSize(5, 20, 0)).isEqualTo(1);
        // Java 增项：0xFFFFFFFF 是 2^32−1 而不是 −1，必须钳到上限
        assertThat(ListingRules.clampPageSize(0xFFFF_FFFF, 20, 20)).isEqualTo(20);
        assertThat(ListingRules.clampPageSize(0x8000_0000, 20, 20)).isEqualTo(20);
    }

    @Test
    void 分页窗口() {
        record Case(String name, long total, int page, int size, int maxPage, int wantPage, int wantCount, long wantOffset) {
        }
        Case[] cases = {
                new Case("空结果至少一页", 0, 1, 20, 100, 1, 1, 0),
                new Case("page=0 视为 1", 45, 0, 20, 100, 1, 3, 0),
                new Case("正好整除", 40, 2, 20, 100, 2, 2, 20),
                new Case("超过末页按末页", 45, 9999, 20, 100, 3, 3, 40),
                new Case("页码上限", 100000, 500, 20, 100, 100, 5000, 1980),
                new Case("maxPage=0 不设上限", 100000, 500, 20, 0, 500, 5000, 9980),
                new Case("页长 4", 9, 3, 4, 100, 3, 3, 8),
                // jubaozhai_logic_test.go:472-476 的分页矩阵（页长已钳制）
                new Case("45 / 0 / 缺省 20", 45, 0, 20, 100, 1, 3, 0),
                new Case("45 / 9999 / 钳到 20", 45, 9999, 20, 100, 3, 3, 40),
                new Case("9 / 2 / 4", 9, 2, 4, 100, 2, 3, 4),
                new Case("0 / 5 / 10", 0, 5, 10, 100, 1, 1, 0),
                // 页长 0 只防除零（按 1）
                new Case("页长 0 按 1", 3, 2, 0, 100, 2, 3, 1),
        };
        for (Case c : cases) {
            PageWindow w = ListingRules.pageWindow(c.total(), c.page(), c.size(), c.maxPage());
            assertThat(w).as(c.name()).isEqualTo(new PageWindow(c.wantPage(), c.wantCount(), c.wantOffset()));
        }
    }

    /** Java 增项（§9.1）：页码 0xFFFFFFFF 钳到末页；page_count / total_count 饱和到 2^32−1。 */
    @Test
    void 分页窗口的无符号边界() {
        assertThat(ListingRules.pageWindow(45, 0xFFFF_FFFF, 20, 100)).isEqualTo(new PageWindow(3, 3, 40));
        assertThat(ListingRules.pageWindow(45, 0xFFFF_FFFF, 20, 0)).isEqualTo(new PageWindow(3, 3, 40));
        // total = 2^32 × 20 + 1：页数超过 uint32，饱和到 0xFFFFFFFF；maxPage 封顶页码而不封顶页数（N1）
        long total = (1L << 32) * 20 + 1;
        PageWindow w = ListingRules.pageWindow(total, 0xFFFF_FFFF, 20, 100);
        assertThat(w.pageCount()).isEqualTo(0xFFFF_FFFF);
        assertThat(w.page()).isEqualTo(100);
        assertThat(w.offset()).isEqualTo(1980);
        // 不封顶时最后一页的偏移超过 2^32 但仍在 long 内
        PageWindow last = ListingRules.pageWindow(total, 0xFFFF_FFFF, 20, 0);
        assertThat(last.page()).isEqualTo(0xFFFF_FFFF);
        assertThat(last.offset()).isEqualTo((0xFFFF_FFFFL - 1) * 20);
        // total ≥ 2^63（位模式为负）也按无符号算
        assertThat(ListingRules.pageWindow(-1L, 1, 20, 100).pageCount()).isEqualTo(0xFFFF_FFFF);

        assertThat(ListingRules.totalCount(0)).isZero();
        assertThat(ListingRules.totalCount(45)).isEqualTo(45);
        assertThat(ListingRules.totalCount(0xFFFF_FFFFL)).isEqualTo(0xFFFF_FFFF);
        assertThat(ListingRules.totalCount(1L << 32)).isEqualTo(0xFFFF_FFFF);
        assertThat(ListingRules.totalCount(-1L)).isEqualTo(0xFFFF_FFFF);
    }

    // ================================================================ EscapeLike / 搜索（phase_test.go:112-128、:174-194）

    @Test
    void LIKE转义() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("青锋剑", "青锋剑");
        cases.put("100%", "100!%");
        cases.put("a_b", "a!_b");
        cases.put("a!b", "a!!b");
        cases.put("!%_", "!!!%!_");
        cases.put("%%", "!%!%");
        cases.put("a\\b", "a\\b"); // 反斜杠不是转义符，原样保留
        cases.put("SMK-1-", "SMK-1-");
        cases.put("𠀀_", "𠀀!_"); // Java 增项：代理对原样
        cases.forEach((in, want) -> assertThat(ListingRules.escapeLike(in)).as(in).isEqualTo(want));

        assertThat(ListingRules.likePattern("")).isEmpty();
        assertThat(ListingRules.likePattern("%_")).isEqualTo("%!%!_%");
        assertThat(ListingRules.likePattern("SMK-1")).isEqualTo("%SMK-1%");
    }

    @Test
    void 搜索词规范化() {
        Map<String, String> ok = new LinkedHashMap<>();
        ok.put("", "");
        ok.put("   ", "");
        ok.put("  青锋  ", "青锋");
        ok.put(" " + "剑".repeat(64) + " ", "剑".repeat(64));
        // §7.3：\n 是 Go 空白，trim 后受理
        ok.put("abc\n", "abc");
        // Java 增项：U+0085、U+00A0、U+3000 是 Go 空白（GoSpaces），被 trim
        ok.put("\u0085abc ", "abc");
        ok.put("　青锋　", "青锋");
        // 64 个码点（含代理对）受理
        ok.put("𠀀".repeat(64), "𠀀".repeat(64));
        ok.forEach((raw, want) -> assertThat(ListingRules.normalizeSearch(raw)).as("[%s]", raw).isEqualTo(want));

        String[] bad = {
                "剑".repeat(65), "𠀀".repeat(65), "a\u0000b", "a\nb",
                // U+001C 不是 Go 空白、是控制字符：不被 trim，且判非法（§7.3）
                "a\u001cb", "\u001cabc",
                // 未配对的代理 = Java 里的「非法 UTF-8」
                "a\ud800b", "\udc00",
        };
        for (String raw : bad) {
            assertThat(ListingRules.normalizeSearch(raw)).as("[%s]", raw).isNull();
        }
    }

    /** jubaozhai_logic_test.go:525-554 的搜索矩阵 + §5.9 第 5 条 / §9.1 的 Java 增项。 */
    @Test
    void 按编号搜索照Go的ParseUint() {
        Map<String, Long> cases = new LinkedHashMap<>();
        cases.put("123", 123L);
        cases.put("007", 7L);
        cases.put("0", 0L);
        cases.put("", 0L);
        cases.put("+5", 0L);
        cases.put("+7", 0L);
        cases.put("-5", 0L);
        cases.put("１２３", 0L); // 全角数字
        cases.put("٣", 0L);     // 阿拉伯-印度数字
        cases.put("12a", 0L);
        cases.put("1 2", 0L);
        cases.put("1_000", 0L);
        cases.put("99999999999999999999", 0L);
        cases.put("18446744073709551615", -1L); // = uint64 上限，按编号（位模式 −1）
        cases.put("18446744073709551616", 0L);  // 溢出，只按标题
        cases.put("9223372036854775808", Long.MIN_VALUE); // 2^63
        cases.forEach((s, want) -> assertThat(ListingRules.searchListingId(s)).as("[%s]", s).isEqualTo(want));
        // "  123  " 先经规范化
        assertThat(ListingRules.searchListingId(ListingRules.normalizeSearch("  123  "))).isEqualTo(123L);
    }

    // ================================================================ ValidText / ValidIconKey（phase_test.go:130-172）

    @Test
    void 展示文本校验() {
        Map<String, Boolean> cases = new LinkedHashMap<>();
        cases.put("", true);
        cases.put("青锋剑", true);
        cases.put("剑".repeat(64), true);
        cases.put("剑".repeat(65), false);
        cases.put("a\nb", false);
        cases.put("a\tb", false);
        cases.put("a\u0000", false);
        cases.put("a\u007f", false);
        cases.put("a\u0085", false);
        cases.put("a\ud800", false);           // 非法 UTF-8 的 Java 对应物
        // Java 增项：控制字符边界
        cases.put("a\u001f", false);
        cases.put("a\u009f", false);
        cases.put("a ", true);            // 不换行空格不是控制字符
        cases.put("a​", true);            // 零宽空格（Cf）不是 Go 的控制字符
        cases.put("𠀀".repeat(64), true);      // 按码点计
        cases.put("𠀀".repeat(65), false);
        cases.forEach((s, want) -> assertThat(ListingRules.validText(s, 64)).as("[%s]", s).isEqualTo(want));
    }

    @Test
    void 图标键校验() {
        Map<String, Boolean> cases = new LinkedHashMap<>();
        cases.put("", true);
        cases.put("icon_sword_01", true);
        cases.put("a".repeat(64), true);
        cases.put("a".repeat(65), false);
        cases.put("Icon", false);
        cases.put("icon-sword", false);
        cases.put("icon sword", false);
        cases.put("图标", false);
        cases.put("../etc", false);
        cases.put("A", false);
        cases.forEach((s, want) -> assertThat(ListingRules.validIconKey(s)).as("[%s]", s).isEqualTo(want));
    }

    // ================================================================ 枚举（phase_test.go:196-224；constants_test.go:189）

    @Test
    void 枚举校验() {
        assertThat(ListingRules.validTab(1)).isTrue();
        assertThat(ListingRules.validTab(2)).isTrue();
        for (int bad : new int[] {0, 3, -1, Integer.MIN_VALUE}) {
            assertThat(ListingRules.validTab(bad)).as("tab %d", bad).isFalse();
        }
        assertThat(ListingRules.validSection(1)).isTrue();
        assertThat(ListingRules.validSection(2)).isTrue();
        for (int bad : new int[] {0, 3, -1}) {
            assertThat(ListingRules.validSection(bad)).as("section %d", bad).isFalse();
        }
        assertThat(ListingRules.isAuction(2)).isTrue();
        assertThat(ListingRules.isAuction(1)).isFalse();
        // proto 里每个排序值都合法，未声明的（含负数）都非法
        for (ListingSort sort : ListingSort.values()) {
            if (sort != ListingSort.UNRECOGNIZED) {
                assertThat(ListingRules.validSort(sort.getNumber())).as(sort.name()).isTrue();
            }
        }
        assertThat(ListingRules.validSort(5)).isFalse();
        assertThat(ListingRules.validSort(-1)).isFalse();

        int weapon = ListingCategory.LISTING_CATEGORY_WEAPON_VALUE;
        assertThat(ListingRules.validCategory(weapon, 0)).isTrue();
        assertThat(ListingRules.validCategory(weapon, 5)).isTrue();
        assertThat(ListingRules.validCategory(weapon, 6)).isFalse();
        int currency = ListingCategory.LISTING_CATEGORY_CURRENCY_VALUE;
        assertThat(ListingRules.validCategory(currency, 0)).isTrue();
        assertThat(ListingRules.validCategory(currency, 1)).isFalse();
        assertThat(ListingRules.validCategory(ListingCategory.LISTING_CATEGORY_SET_VALUE, 1)).as("套装子类 1").isFalse();
        assertThat(ListingRules.validCategory(0, 0)).isFalse();
        assertThat(ListingRules.validCategory(10, 0)).isFalse();
        assertThat(ListingRules.validCategory(-1, 0)).isFalse();
        // Java 增项：子类 0xFFFFFFFF 是 2^32−1 而不是 −1，不能被「≤ 上限」放过
        assertThat(ListingRules.validCategory(weapon, 0xFFFF_FFFF)).isFalse();
        assertThat(ListingRules.validCategory(weapon, 0x8000_0000)).isFalse();
    }

    @Test
    void 子类上限表() {
        Map<Integer, Integer> want = new LinkedHashMap<>();
        want.put(ListingCategory.LISTING_CATEGORY_CHARACTER_VALUE, 4);
        want.put(ListingCategory.LISTING_CATEGORY_PET_VALUE, 6);
        want.put(ListingCategory.LISTING_CATEGORY_WEAPON_VALUE, 5);
        want.put(ListingCategory.LISTING_CATEGORY_ARMOR_VALUE, 5);
        want.put(ListingCategory.LISTING_CATEGORY_SET_VALUE, 0);
        want.put(ListingCategory.LISTING_CATEGORY_TREASURE_VALUE, 0);
        want.put(ListingCategory.LISTING_CATEGORY_JEWELRY_VALUE, 0);
        want.put(ListingCategory.LISTING_CATEGORY_SUMMONING_ORDER_VALUE, 2);
        want.put(ListingCategory.LISTING_CATEGORY_CURRENCY_VALUE, 0);
        want.forEach((category, limit) -> assertThat(ListingRules.maxSubcategory(category)).as("类目 %d", category)
                .isEqualTo(OptionalInt.of(limit)));
        for (int bad : new int[] {0, 10, -1}) {
            assertThat(ListingRules.maxSubcategory(bad)).as("类目 %d", bad).isEmpty();
        }
        // proto 里每个非 0 类目都必须在表里表态，新增类目漏登记会在这里红
        for (ListingCategory c : ListingCategory.values()) {
            if (c != ListingCategory.UNRECOGNIZED && c.getNumber() != 0) {
                assertThat(ListingRules.maxSubcategory(c.getNumber())).as("proto 类目 %s 没有登记子类上限", c).isPresent();
            }
        }
    }

    // ================================================================ 收藏上限

    @Test
    void 收藏上限按无符号比较() {
        assertThat(ListingRules.favoriteLimitReached(99, 100)).isFalse();
        assertThat(ListingRules.favoriteLimitReached(100, 100)).isTrue();
        assertThat(ListingRules.favoriteLimitReached(101, 100)).isTrue();
        assertThat(ListingRules.favoriteLimitReached(0, 1)).isFalse();
        assertThat(ListingRules.favoriteLimitReached(-1L, 100)).as("计数 ≥ 2^63").isTrue();
        assertThat(ListingRules.favoriteLimitReached(100, 0xFFFF_FFFF)).as("上限 2^32−1 不是 −1").isFalse();
    }

    // ================================================================ ValidSeedRequest（phase_test.go:226-306）

    /** 一份能通过校验的种子请求，负向用例在它上面逐项改坏（phase_test.go:227-241）。 */
    private static SeedListingRequest validSeed() {
        return SeedListingRequest.newBuilder()
                .setSellerPlayerId(101)
                .setCategory(ListingCategory.LISTING_CATEGORY_WEAPON)
                .setSubcategory(1)
                .setTitle("SMK-1-A")
                .setLevel(10)
                .setPriceFen(500)
                .setSummary("摘要")
                .setDescription("描述")
                .setIconKey("icon_sword")
                .setNoticeDurationMs(0)
                .setSaleDurationMs(3_600_000L)
                .build();
    }

    private static SeedListingRequest mutate(UnaryOperator<SeedListingRequest.Builder> change) {
        return change.apply(validSeed().toBuilder()).build();
    }

    @Test
    void 种子请求校验() {
        assertThat(ListingRules.validSeedRequest(validSeed())).as("基准种子请求应合法").isTrue();
        long maxSale = TradeLimits.MAX_SALE_DURATION_MS;
        long maxNotice = TradeLimits.MAX_NOTICE_DURATION_MS;

        Map<String, UnaryOperator<SeedListingRequest.Builder>> valid = new LinkedHashMap<>();
        valid.put("寄售期恰好上限", b -> b.setSaleDurationMs(maxSale));
        valid.put("公示期恰好上限", b -> b.setNoticeDurationMs(maxNotice));
        valid.put("价格恰好上限", b -> b.setPriceFen(TradeLimits.MAX_PRICE_FEN));
        valid.put("等级恰好上限", b -> b.setLevel(TradeLimits.MAX_LEVEL));
        valid.put("空图标键", b -> b.setIconKey(""));
        valid.put("空摘要与描述", b -> b.setSummary("").setDescription(""));
        valid.put("游戏币无子类", b -> b.setCategory(ListingCategory.LISTING_CATEGORY_CURRENCY).setSubcategory(0));
        // Java 增项：标题不 trim 地存，首尾空格合法（原文过 ValidText）
        valid.put("标题带首尾空格", b -> b.setTitle("  剑  "));
        valid.put("标题 64 个码点", b -> b.setTitle("𠀀".repeat(64)));
        valid.forEach((name, change) -> assertThat(ListingRules.validSeedRequest(mutate(change))).as(name).isTrue());

        Map<String, UnaryOperator<SeedListingRequest.Builder>> invalid = new LinkedHashMap<>();
        invalid.put("seller=0", b -> b.setSellerPlayerId(0));
        invalid.put("类目 0", b -> b.setCategory(ListingCategory.LISTING_CATEGORY_UNSPECIFIED));
        invalid.put("类目 10", b -> b.setCategoryValue(10));
        invalid.put("类目负数", b -> b.setCategoryValue(-1));
        invalid.put("子类越界", b -> b.setSubcategory(6));
        invalid.put("子类 0xFFFFFFFF", b -> b.setSubcategory(0xFFFF_FFFF));
        invalid.put("标题为空", b -> b.setTitle(""));
        invalid.put("标题全空白", b -> b.setTitle("   "));
        invalid.put("标题全 Go 空白", b -> b.setTitle("　\u0085"));
        invalid.put("标题超长", b -> b.setTitle("剑".repeat(TradeLimits.MAX_TITLE_RUNES + 1)));
        invalid.put("标题含换行", b -> b.setTitle("a\nb"));
        invalid.put("标题首尾换行", b -> b.setTitle("剑\n")); // trim 后非空，但原文含控制字符
        invalid.put("摘要超长", b -> b.setSummary("a".repeat(TradeLimits.MAX_SUMMARY_RUNES + 1)));
        invalid.put("描述超长", b -> b.setDescription("a".repeat(TradeLimits.MAX_DESCRIPTION_RUNES + 1)));
        invalid.put("描述含控制字符", b -> b.setDescription("a\u0001"));
        invalid.put("图标键字符集", b -> b.setIconKey("Icon-A"));
        invalid.put("图标键大写 A", b -> b.setIconKey("A"));
        invalid.put("等级超限", b -> b.setLevel(TradeLimits.MAX_LEVEL + 1));
        invalid.put("等级 0xFFFFFFFF", b -> b.setLevel(0xFFFF_FFFF));
        invalid.put("价格 0", b -> b.setPriceFen(0));
        invalid.put("价格超限", b -> b.setPriceFen(TradeLimits.MAX_PRICE_FEN + 1));
        invalid.put("价格 ≥ 2^63", b -> b.setPriceFen(Long.MIN_VALUE));
        invalid.put("寄售期 0", b -> b.setSaleDurationMs(0));
        invalid.put("寄售期超限", b -> b.setSaleDurationMs(maxSale + 1));
        invalid.put("寄售期 ≥ 2^63", b -> b.setSaleDurationMs(-1L));
        invalid.put("公示期超限", b -> b.setNoticeDurationMs(maxNotice + 1));
        invalid.put("公示期 ≥ 2^63", b -> b.setNoticeDurationMs(-1L));
        invalid.forEach((name, change) -> assertThat(ListingRules.validSeedRequest(mutate(change))).as(name).isFalse());
    }
}
