package com.game.trade.rules;

import com.game.common.text.GoSpaces;
import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingPhase;
import com.game.proto.trade.ListingSection;
import com.game.proto.trade.ListingSort;
import com.game.proto.trade.ListingTab;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SeedListingRequest;
import com.game.trade.store.Listing;
import com.game.trade.store.ListingStatuses;
import java.util.OptionalInt;

/**
 * 聚宝斋逻辑层的纯函数（基线 go/trade/internal/logic/phase.go 全文 + constants.go:80-105 MaxSubcategory；trade-spec §2、§2.7、§5.9）：
 * 阶段推导、可见性、分页窗口、LIKE 转义、搜索规范化、输入校验、子类上限。全部不碰 I/O、不读时钟（时间由调用方每请求取一次后传入），
 * 任意线程可调。
 *
 * <p><b>Go 语义在 Java 里的边界</b>（§5.9），每一条都有单测：
 * <ul>
 *   <li>uint32 / uint64 是位模式：{@code page}、{@code page_size}、{@code subcategory}、{@code level} 等是 {@code int}，号、价格、毫秒是 {@code long}；
 *       钳制与比较一律 {@link Integer#compareUnsigned} / {@link Long#compareUnsigned}（否则 {@code page_size = 0xFFFFFFFF} 变成 −1、
 *       「大于上限」判不出来；{@code subcategory = 0xFFFFFFFF} 变成 −1、「≤ 上限」误判为真）；</li>
 *   <li>枚举一律按整数值（{@code getXxxValue()}）判：proto3 未知值在 Java 是 {@code UNRECOGNIZED}，对它调 {@code getNumber()} 会抛异常；
 *       基线要求未知值与负值都判非法；</li>
 *   <li>去空白按 Go {@code strings.TrimSpace}（{@link GoSpaces#trim}），不用 {@link String#strip()}；控制字符按 Go {@code unicode.IsControl}
 *       （{@link Character#isISOControl(int)}：U+0000–001F、U+007F–009F）；长度按码点（Go rune）计；</li>
 *   <li>「合法 UTF-8」：Java 字符串装不下非法 UTF-8（proto3 解析阶段就失败），对应的是<b>未配对的代理</b>——写进 utf8mb4 会变成 {@code ?}，
 *       一律判非法（同 GuildNames.displayName 的 Java 增项）；</li>
 *   <li>按编号搜索照 Go {@code strconv.ParseUint(s, 10, 64)}：只认 ASCII 数字、不认 {@code +}、可有前导零、溢出不按编号——不能直接用
 *       {@link Long#parseUnsignedLong}（它认 {@code +} 与全角数字）。</li>
 * </ul>
 */
public final class ListingRules {

    private static final int TAB_PUBLIC_NOTICE = ListingTab.LISTING_TAB_PUBLIC_NOTICE_VALUE;
    private static final int TAB_ON_SALE = ListingTab.LISTING_TAB_ON_SALE_VALUE;

    /** uint32 的最大值（{@code page_count} / {@code total_count} 饱和到它；jubaozhai_logic.go:433-438、phase.go:89-91）。 */
    public static final long UINT32_MAX = 0xFFFF_FFFFL;

    private ListingRules() {
    }

    // ================================================================ 阶段与可见性（phase.go:17-60）

    /**
     * 按存储状态与同一次 now 推导展示阶段（Phase，phase.go:17-38）：公示期 / 寄售期不落状态。
     * <pre>
     *   LISTED 且 now &lt; notice_end_ms → PUBLIC_NOTICE
     *   LISTED 且 now &lt; sale_end_ms   → ON_SALE
     *   LOCKED                         → LOCKED（不看时间）
     *   其余（含 LISTED 已过寄售期）    → ENDED
     * </pre>
     * 边界：{@code now == notice_end_ms} 算寄售，{@code now == sale_end_ms} 算结束，{@code notice_end_ms = 0} 直接寄售（phase_test.go:26-28）。
     */
    public static ListingPhase phase(Listing listing, long nowMs) {
        return phase(listing.status(), listing.noticeEndMs(), listing.saleEndMs(), nowMs);
    }

    /** {@link #phase(Listing, long)} 的原始参数形式（时间都是 uint64 位模式）。 */
    public static ListingPhase phase(int status, long noticeEndMs, long saleEndMs, long nowMs) {
        if (status == ListingStatuses.LISTED) {
            if (Long.compareUnsigned(nowMs, noticeEndMs) < 0) {
                return ListingPhase.LISTING_PHASE_PUBLIC_NOTICE;
            }
            if (Long.compareUnsigned(nowMs, saleEndMs) < 0) {
                return ListingPhase.LISTING_PHASE_ON_SALE;
            }
            return ListingPhase.LISTING_PHASE_ENDED;
        }
        if (status == ListingStatuses.LOCKED) {
            return ListingPhase.LISTING_PHASE_LOCKED;
        }
        return ListingPhase.LISTING_PHASE_ENDED;
    }

    /**
     * 非卖家的调用者能否看到这件商品（VisibleToBuyer，phase.go:40-60）：
     * {@code status ∈ {LISTED, LOCKED}} 且 {@code now < sale_end_ms} 且（GLOBAL，或 ZONE 下 {@code callerHomeZone ≠ 0 && market_zone == callerHomeZone}）。
     * 公示中的商品对买家可见（只是不可买）；scope 为 UNSPECIFIED / 未知时恒不可见（fail-closed）。
     *
     * <p>卖家豁免（{@code seller_player_id == caller} 直接可见、不查归属区，jubaozhai_logic.go:334-336）由服务层在调用本函数之前判。
     *
     * @param callerHomeZone ZONE 范围下查到的调用者归属区（uint32 位模式）；GLOBAL 下不查、传 0
     */
    public static boolean visibleToBuyer(Listing listing, long nowMs, MarketScope scope, int callerHomeZone) {
        int status = listing.status();
        if (status != ListingStatuses.LISTED && status != ListingStatuses.LOCKED) {
            return false;
        }
        if (Long.compareUnsigned(nowMs, listing.saleEndMs()) >= 0) {
            return false;
        }
        if (scope == MarketScope.MARKET_SCOPE_GLOBAL) {
            return true;
        }
        if (scope == MarketScope.MARKET_SCOPE_ZONE) {
            return callerHomeZone != 0 && listing.marketZone() == callerHomeZone;
        }
        return false;
    }

    /**
     * 配置字符串 → 市场范围（MarketConf.ScopeEnum，config.go:204-214）：只认字面 {@code zone} / {@code global}（区分大小写），
     * 其余一律 UNSPECIFIED——浏览遇到它回 1003、详情 / 收藏判不可见，都是 fail-closed。启动校验另行拒绝非法值（config.go:305-307）。
     */
    public static MarketScope scopeOf(String configured) {
        if ("global".equals(configured)) {
            return MarketScope.MARKET_SCOPE_GLOBAL;
        }
        if ("zone".equals(configured)) {
            return MarketScope.MARKET_SCOPE_ZONE;
        }
        return MarketScope.MARKET_SCOPE_UNSPECIFIED;
    }

    // ================================================================ 分页（phase.go:62-101；jubaozhai_logic.go:433-438）

    /**
     * 钳制页长（ClampPageSize，phase.go:62-74）：0 → 缺省；超过上限 → 上限；结果仍是 0 → 1（配置已保证 &gt; 0，只防除零）。
     * 三个参数都是 uint32 位模式，按无符号比较。
     */
    public static int clampPageSize(int requested, int defaultSize, int maxSize) {
        int size = requested == 0 ? defaultSize : requested;
        if (Integer.compareUnsigned(size, maxSize) > 0) {
            size = maxSize;
        }
        return size == 0 ? 1 : size;
    }

    /**
     * 一页的窗口。
     *
     * @param page      钳制后的页码（uint32 位模式，1 起）
     * @param pageCount 页数 {@code max(1, ceil(total / pageSize))}，饱和到 2^32−1；<b>不受 MaxPage 封顶</b>（N1：客户端可能看到 5000 页、最多翻到 100）
     * @param offset    {@code (page − 1) × pageSize}（uint64 位模式）
     */
    public record PageWindow(int page, int pageCount, long offset) {
    }

    /**
     * 按总数与（已钳制的）页长算出实际页码、页数与 OFFSET（PageWindow，phase.go:76-101）：
     * <pre>
     *   page_count = max(1, ceil(total / pageSize))，饱和到 2^32−1
     *   page       = min(max(page, 1), page_count)；maxPage &gt; 0 时再 min(page, maxPage)
     *   offset     = (page − 1) × pageSize
     * </pre>
     * 全部参数是位模式（total uint64，其余 uint32）。基线 {@code (total + size − 1) / size} 在 total 接近 2^64 时会回绕；COUNT(*) 恒 &lt; 2^63，
     * Java 用无符号除法加余数判断，在可达范围内与基线相同。
     */
    public static PageWindow pageWindow(long total, int page, int pageSize, int maxPage) {
        long size = pageSize == 0 ? 1 : Integer.toUnsignedLong(pageSize);
        long count = Long.divideUnsigned(total, size);
        if (Long.remainderUnsigned(total, size) != 0) {
            count++;
        }
        if (Long.compareUnsigned(count, 1) < 0) {
            count = 1;
        }
        if (Long.compareUnsigned(count, UINT32_MAX) > 0) {
            count = UINT32_MAX;
        }
        int pageCount = (int) count;
        int clamped = page == 0 ? 1 : page;
        if (Integer.compareUnsigned(clamped, pageCount) > 0) {
            clamped = pageCount;
        }
        if (maxPage != 0 && Integer.compareUnsigned(clamped, maxPage) > 0) {
            clamped = maxPage;
        }
        long offset = (Integer.toUnsignedLong(clamped) - 1) * size;
        return new PageWindow(clamped, pageCount, offset);
    }

    /** 应答 {@code total_count}：uint64 计数饱和到 2^32−1（clampUint32，jubaozhai_logic.go:433-438），返回 uint32 位模式。 */
    public static int totalCount(long total) {
        return Long.compareUnsigned(total, UINT32_MAX) > 0 ? (int) UINT32_MAX : (int) total;
    }

    // ================================================================ 搜索（phase.go:103-155；jubaozhai_logic.go:145-151）

    /**
     * 把搜索词转成 LIKE 字面量（EscapeLike，phase.go:103-112）：单遍替换 {@code !→!!}、{@code %→!%}、{@code _→!_}，已替换出的 {@code !}
     * 不会被二次转义；反斜杠原样保留。配合 SQL 里固定的 {@code ESCAPE '!'}：反斜杠在 MySQL 字符串字面量里的语义受 sql_mode
     * {@code NO_BACKSLASH_ESCAPES} 影响，{@code '!'} 在任何 sql_mode 下都是普通字符（§5.9 第 9 条）。
     */
    public static String escapeLike(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                out.append('!');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 浏览的 LIKE 模式 {@code "%" + escapeLike(search) + "%"}（jubaozhai_logic.go:145-146）；search 为空表示不搜，返回 {@code ""}。 */
    public static String likePattern(String normalizedSearch) {
        return normalizedSearch.isEmpty() ? "" : "%" + escapeLike(normalizedSearch) + "%";
    }

    /**
     * 去掉首尾空白后校验搜索词（NormalizeSearch，phase.go:147-155）：Go 口径 trim → {@link #validText}（≤ 64 码点）。
     *
     * @return trim 之后的词，可能为空（= 不搜）；非法（超长 / 控制字符 / 未配对代理）返回 {@code null}
     */
    public static String normalizeSearch(String raw) {
        String search = GoSpaces.trim(raw);
        return validText(search, TradeLimits.MAX_SEARCH_RUNES) ? search : null;
    }

    /**
     * 纯数字的搜索词同时按商品编号精确匹配（jubaozhai_logic.go:147-150：{@code strconv.ParseUint(search, 10, 64)} 成功时写 SearchListingID）。
     * 只接受非空的 ASCII {@code [0-9]+}（不接受 {@code +}、全角数字）；可有前导零（{@code "007"} → 7）；超出 uint64 不按编号；
     * 解析出 0 也就等于不按编号（listing_repo.go:387）。
     *
     * @return 编号的 uint64 位模式；0 = 不按编号
     */
    public static long searchListingId(String normalizedSearch) {
        if (normalizedSearch.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < normalizedSearch.length(); i++) {
            char c = normalizedSearch.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
        }
        try {
            return Long.parseUnsignedLong(normalizedSearch);
        } catch (NumberFormatException overflow) {
            return 0;
        }
    }

    // ================================================================ 文本与资源键（phase.go:114-145）

    /**
     * 校验展示文本（ValidText，phase.go:114-131）：合法 UTF-8（Java：无未配对代理）、不含控制字符（含换行 / 制表）、码点数 ≤ maxRunes。空串合法。
     * 控制字符会破坏客户端单行排版与日志行，且没有任何合法的展示用途。
     */
    public static boolean validText(String s, int maxRunes) {
        int runes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isISOControl(cp) || isLoneSurrogate(cp)) {
                return false;
            }
            runes++;
            if (runes > maxRunes) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * 校验图标资源键（ValidIconKey，phase.go:133-145）：空串合法（客户端用类目默认图标）；否则 ≤ 64 字节且只含 {@code [a-z0-9_]}。
     * 合法字符都是单字节，按 char 数判长度与按字节判结论相同（非 ASCII 字符本来就非法）。
     */
    public static boolean validIconKey(String s) {
        if (s.length() > TradeLimits.MAX_ICON_KEY_LEN) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    // ================================================================ 枚举（phase.go:157-186；constants.go:80-105）

    /**
     * 类目的子类编码上限（MaxSubcategory，constants.go:80-105；0 = 该类目没有子类）。编码是服务端与客户端的共同契约：k（1 起）=
     * 客户端 {@code JubaozhaiCatalog.SubcategoriesFor(类目)} 的第 k 个标签；客户端改标签顺序或数量时，这张表必须同步改。
     *
     * @return 上限；类目本身非法（0、未知值、负数）时为空
     */
    public static OptionalInt maxSubcategory(int category) {
        return switch (category) {
            case ListingCategory.LISTING_CATEGORY_CHARACTER_VALUE -> OptionalInt.of(4);        // 破军 玄霄 逐风 丹心（门派）
            case ListingCategory.LISTING_CATEGORY_PET_VALUE -> OptionalInt.of(6);              // 普通 灵兽 变异 神兽 元灵 其他
            case ListingCategory.LISTING_CATEGORY_WEAPON_VALUE -> OptionalInt.of(5);           // 枪 爪 剑 扇 锤
            case ListingCategory.LISTING_CATEGORY_ARMOR_VALUE -> OptionalInt.of(5);            // 男帽 女帽 男衣 女衣 鞋子
            case ListingCategory.LISTING_CATEGORY_SET_VALUE,
                 ListingCategory.LISTING_CATEGORY_TREASURE_VALUE,
                 ListingCategory.LISTING_CATEGORY_JEWELRY_VALUE,
                 ListingCategory.LISTING_CATEGORY_CURRENCY_VALUE -> OptionalInt.of(0);
            case ListingCategory.LISTING_CATEGORY_SUMMONING_ORDER_VALUE -> OptionalInt.of(2);  // 神兽召唤令 元灵召唤令
            default -> OptionalInt.empty();
        };
    }

    /** 类目 ∈ 1..9 且子类（uint32 位模式）≤ 该类目上限（ValidCategory，phase.go:157-161；0 = 全部 / 无子类）。 */
    public static boolean validCategory(int category, int subcategory) {
        OptionalInt limit = maxSubcategory(category);
        return limit.isPresent() && Integer.compareUnsigned(subcategory, limit.getAsInt()) <= 0;
    }

    /** 只接受公示 / 寄售两个页签（ValidTab，phase.go:163-166）。 */
    public static boolean validTab(int tab) {
        return tab == TAB_PUBLIC_NOTICE || tab == TAB_ON_SALE;
    }

    /** 只接受寄售 / 竞价两个分区（ValidSection，phase.go:168-172；竞价在全部校验通过后另回 20003）。 */
    public static boolean validSection(int section) {
        return section == ListingSection.LISTING_SECTION_CONSIGNMENT_VALUE || section == ListingSection.LISTING_SECTION_AUCTION_VALUE;
    }

    /** 竞价分区（jubaozhai_logic.go:114-116）。 */
    public static boolean isAuction(int section) {
        return section == ListingSection.LISTING_SECTION_AUCTION_VALUE;
    }

    /** 只接受 proto 里声明过的排序 0..4（ValidSort，phase.go:174-186）。 */
    public static boolean validSort(int sort) {
        return switch (sort) {
            case ListingSort.LISTING_SORT_DEFAULT_VALUE,
                 ListingSort.LISTING_SORT_PRICE_ASC_VALUE,
                 ListingSort.LISTING_SORT_PRICE_DESC_VALUE,
                 ListingSort.LISTING_SORT_LEVEL_DESC_VALUE,
                 ListingSort.LISTING_SORT_REMAINING_ASC_VALUE -> true;
            default -> false;
        };
    }

    // ================================================================ 收藏与播种

    /**
     * 收藏是否已达上限（jubaozhai_logic.go:307-313：{@code count >= MaxFavoritesPerPlayer} → 20002）。count 是 uint64、上限是 uint32，按无符号比较。
     * 计数包括已经看不见的收藏（Q8 保持基线）；软上限：计数与写入之间没有锁（jubaozhai_logic.go:260-268）。
     */
    public static boolean favoriteLimitReached(long count, int maxFavorites) {
        return Long.compareUnsigned(count, Integer.toUnsignedLong(maxFavorites)) >= 0;
    }

    /**
     * 校验 SeedListing 的全部输入（ValidSeedRequest，phase.go:188-213）：seller ≠ 0；类目与子类合法；{@code TrimSpace(title)} 非空且<b>原文</b>
     * 过 {@link #validText}(64)；摘要 ≤ 128、描述 ≤ 512（都过 validText）；icon_key 为空或 ≤ 64 字节且只含 {@code [a-z0-9_]}；level ≤ 1000；
     * price ∈ [1, 1e10]；寄售时长 ∈ [1 ms, 90 天]；公示时长 ≤ 30 天。数值字段按无符号比较。
     */
    public static boolean validSeedRequest(SeedListingRequest in) {
        if (in.getSellerPlayerId() == 0) {
            return false;
        }
        if (!validCategory(in.getCategoryValue(), in.getSubcategory())) {
            return false;
        }
        if (GoSpaces.trim(in.getTitle()).isEmpty() || !validText(in.getTitle(), TradeLimits.MAX_TITLE_RUNES)) {
            return false;
        }
        if (!validText(in.getSummary(), TradeLimits.MAX_SUMMARY_RUNES)) {
            return false;
        }
        if (!validText(in.getDescription(), TradeLimits.MAX_DESCRIPTION_RUNES)) {
            return false;
        }
        if (!validIconKey(in.getIconKey())) {
            return false;
        }
        if (Integer.compareUnsigned(in.getLevel(), TradeLimits.MAX_LEVEL) > 0) {
            return false;
        }
        if (in.getPriceFen() == 0 || Long.compareUnsigned(in.getPriceFen(), TradeLimits.MAX_PRICE_FEN) > 0) {
            return false;
        }
        if (in.getSaleDurationMs() == 0 || Long.compareUnsigned(in.getSaleDurationMs(), TradeLimits.MAX_SALE_DURATION_MS) > 0) {
            return false;
        }
        return Long.compareUnsigned(in.getNoticeDurationMs(), TradeLimits.MAX_NOTICE_DURATION_MS) <= 0;
    }

    /** {@link String#codePointAt} 遇到未配对的代理时原样返回那个 char 值。 */
    private static boolean isLoneSurrogate(int cp) {
        return cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE;
    }
}
