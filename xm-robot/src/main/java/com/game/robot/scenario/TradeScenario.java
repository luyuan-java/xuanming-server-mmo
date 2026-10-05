package com.game.robot.scenario;

import com.game.contract.MessageIdRegistry;
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
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SeedListingResponse;
import com.game.proto.trade.SetFavoriteRequest;
import com.game.proto.trade.SetFavoriteResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.client.TradeAdminClient;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.CommonErrorTip;
import com.game.table.TradeErrorTip;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 聚宝斋只读面端到端（对应 mmorpg robot/trade_smoke_scenario.go，三个同区机器人 A（卖家）/ B（买家）/ C（卖家兼买家）经 gate → xm-trade；
 * trade-spec §4.9、§4.10、§9.6）。基线第 0–9 步照跑（Go :195-526）：
 * <ol>
 *   <li>第 0 步 A / B / C 进场。本机切片只有一个 zone，C 与 A 同区（相当于基线 {@code cross_zone = false}，Go :190-193）；</li>
 *   <li>第 1 步 经 xm-trade 播种接口（{@link TradeAdminClient}，T5；基线 gRPC 直连 TradeAdmin）造 A 寄售、C 寄售、A 公示三条武器（子类 1）种子，
 *       标题带本轮 nonce；断言 {@code market_zone} = 卖家归属区 = 本区（Go :234-279）；</li>
 *   <li>第 2 步 A 经 gate 发 199 {@code TradeAdmin.SeedListing} → gate 按不认识的号丢弃：没有任何回包；只发一次（非法包阈值 50），
 *       之后同一连接照常可用（Go :281-298）；</li>
 *   <li>第 3 步 市场范围：B 看到 A（摘要逐字段与种子一致、ON_SALE、is_mine = false）。单区下的范围断言（§4.10）：zone → 传
 *       {@code zone_filter = 别区} 仍看到 A（过滤被忽略）；global → 同样的过滤让 A 消失、{@code zone_filter = 本区} 仍看到 A（Go :300-370）；</li>
 *   <li>第 4 步 公示页签与寄售页签互斥；第 5 步 页长 50 回显 20、page = 9999 钳到末页；第 6 步 拍卖 20003（Go :372-424）；</li>
 *   <li>第 7 步 详情：B 看 A 受理、不存在的编号 20000、A 看自己 is_mine = true（Go :426-464）；</li>
 *   <li>第 8 步 收藏 → 只看收藏 → 取消 → 再看（Go :466-497）；第 9 步 货架只含自己的（Go :499-526）。</li>
 * </ol>
 * Java 增项（钉住契约细节，§9.6）：参数非法矩阵（search 65 字、category 0、武器子类 6、section 0、tab 0、sort 5、拍卖 + tab 0）→ 1005；
 * SetFavorite / 详情 id 0 → 1005（收藏回填 0）；收藏不存在的编号 20000、取消不存在的编号受理；search 带 {@code %} / {@code _} 按字面量；
 * 用编号纯数字搜索命中；已结束种子（公示 0、寄售 1 ms）卖家详情 ENDED、买家 20000、货架可见、寄售列表看不到；公示中的商品可收藏；
 * page / page_size 为 0 回显 1 / 20；拒绝应答只带 error_message（收藏另带 listing_id）、成功应答不设 error_message、tip 不带 parameters（§0.3）。
 *
 * <p>跨区步骤（C 在别区：zone 下互不可见、zone_filter 不能扩大范围；global 下 zone_filter = zone_b 只剩 C；C 看 A 的详情）只在有第二个 zone 时跑，
 * 本机切片跳过并记观察（T10、Q3）。账号是 run-tag 新号（基线固定 robot_9401–9403）。限频：196 / 197 / 200 每秒 10 条、198 每秒 5 条
 * （messagelimiter.json:183-206），同一机器人相邻请求隔 {@link #REQUEST_SPACING}，任意 1 s 内同号至多 4 条。
 * 「不存在」的编号用 {@code Long.MAX_VALUE}（雪花号恒 &lt; 2^63 且远小于它，Go :109-111）。
 */
public final class TradeScenario {

    private static final String SERVICE = "ClientPlayerJubaozhai";
    private static final String REF = "PARITY「聚宝斋只读面」行";
    /** 同一机器人相邻请求的最小间隔（trade-spec §4.10）。 */
    static final Duration REQUEST_SPACING = Duration.ofMillis(300);
    /** 同号退火窗口与上限：300 ms 间隔下任意 1.1 s 至多 4 条，低于 198 的每秒 5 条（窗口只是兜底）。 */
    static final Duration SAME_ID_WINDOW = Duration.ofMillis(1100);
    static final int SAME_ID_MAX_IN_WINDOW = 4;
    /** 第 2 步：探测请求的应答回来之后再等这么久，确认 199 也没有迟到的回包。 */
    static final Duration ADMIN_VIA_GATE_GRACE = Duration.ofSeconds(1);

    /** xm-trade {@code xm.trade.market.max-page-size} / {@code default-page-size}（= trade.yaml Market，config_test.go:57-63 钉住 20 / 20）。 */
    static final int SERVER_MAX_PAGE_SIZE = 20;
    static final int SERVER_DEFAULT_PAGE_SIZE = 20;
    static final int OVERSIZE_PAGE_SIZE = 50;
    static final int BEYOND_LAST_PAGE = 9999;
    /** 搜索上限 64 个字符（MaxSearchRunes），65 个即非法。 */
    static final int MAX_SEARCH_CODE_POINTS = 64;
    /** 武器子类上限 5（constants.go:81-105），6 即非法。 */
    static final int WEAPON_MAX_SUBCATEGORY = 5;
    /** ListingSort 只认 0..4。 */
    static final int UNKNOWN_SORT = 5;

    /** 种子的固定参数（Go :96-107）：武器 / 子类 1「枪」/ 60 级 / 123456 分 / 寄售 1 h；公示种子另带 1 h 公示期。 */
    static final ListingCategory CATEGORY = ListingCategory.LISTING_CATEGORY_WEAPON;
    static final int SUBCATEGORY = 1;
    static final int LEVEL = 60;
    static final long PRICE_FEN = 123_456L;
    static final String ICON_KEY = "smoke_weapon";
    static final long SALE_DURATION_MS = Duration.ofHours(1).toMillis();
    static final long NOTICE_DURATION_MS = Duration.ofHours(1).toMillis();
    /** 已结束种子：无公示期、寄售 1 ms（种完立刻结束）。 */
    static final long ENDED_SALE_DURATION_MS = 1L;
    /** 取详情 / 收藏用的「不存在」编号（Go tradeSmokeMissingListingID）。 */
    static final long MISSING_LISTING_ID = Long.MAX_VALUE;

    private static final int TIP_NOT_FOUND = TradeErrorTip.trade_error.kTradeListingNotFound_VALUE;
    private static final int TIP_HOME_ZONE_UNKNOWN = TradeErrorTip.trade_error.kTradeHomeZoneUnknown_VALUE;
    private static final int TIP_FAVORITE_LIMIT = TradeErrorTip.trade_error.kTradeFavoriteLimitReached_VALUE;
    private static final int TIP_FEATURE_DISABLED = TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE;
    private static final int TIP_INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int TIP_SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    private static final int TIP_RATE_LIMITED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;

    private final PlayerFlow flow;
    private final TradeAdminClient admin;
    private final String accountA;
    private final String accountB;
    private final String accountC;
    private final int zoneId;
    private final MarketScope expectScope;
    private final Duration requestTimeout;
    private final int browse;
    private final int detail;
    private final int favorite;
    private final int shelf;
    private final int seedViaGate;
    private final int sendTip;
    private final CheckReport report = new CheckReport();
    private final List<GameConnection> connections = new ArrayList<>();

    /**
     * @param admin       xm-trade 播种接口
     * @param zoneId      本区（新号建角所在区 = 归属区；第 1 步断言 market_zone 等于它）
     * @param expectScope 期望的市场范围（{@code --trade-scope}，须与 xm-trade 的 {@code xm.trade.market.scope} 一致）
     */
    public TradeScenario(PlayerFlow flow, MessageIdRegistry registry, TradeAdminClient admin, String accountPrefix,
                         String runTag, int zoneId, MarketScope expectScope, Duration requestTimeout) {
        this.flow = flow;
        this.admin = admin;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.accountC = accountName(accountPrefix, runTag, "c");
        this.zoneId = zoneId;
        this.expectScope = expectScope;
        this.requestTimeout = requestTimeout;
        this.browse = registry.requireId(SERVICE, "BrowseListings");
        this.detail = registry.requireId(SERVICE, "GetListingDetail");
        this.favorite = registry.requireId(SERVICE, "SetFavorite");
        this.shelf = registry.requireId(SERVICE, "GetMyShelf");
        this.seedViaGate = registry.requireId("TradeAdmin", "SeedListing");
        this.sendTip = registry.requireId("SceneClientPlayerCommon", "SendTipToClient");
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "td" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        } finally {
            connections.forEach(GameConnection::close);
        }
        return report;
    }

    private void runChecks() throws RobotException {
        // ---- 第 0 步：进场（单 zone：C 与 A 同区） ----
        Bot a = enter("A", accountA);
        Bot b = enter("B", accountB);
        Bot c = enter("C", accountC);
        report.note("A=" + uid(a.id()) + " B=" + uid(b.id()) + " C=" + uid(c.id()) + " zone=" + Integer.toUnsignedString(zoneId)
                + " 期望范围=" + scopeName(expectScope.getNumber()) + " 播种接口=" + admin.baseUrl());

        // ---- 第 1 步：经 xm-trade 播种接口造种子 ----
        String nonce = nonce();
        SeedListingRequest seedA = seedRequest(a.id(), nonce + "-A", 0, SALE_DURATION_MS);
        SeedListingRequest seedC = seedRequest(c.id(), nonce + "-C", 0, SALE_DURATION_MS);
        SeedListingRequest seedNotice = seedRequest(a.id(), nonce + "-AN", NOTICE_DURATION_MS, SALE_DURATION_MS);
        SeedListingRequest seedEnded = seedRequest(a.id(), nonce + "-AE", 0, ENDED_SALE_DURATION_MS);
        String gateTitle = nonce + "-GATE";
        long listingA = seed("A 寄售", seedA);
        long listingC = seed("C 寄售", seedC);
        long listingNotice = seed("A 公示 1 h", seedNotice);
        long listingEnded = seed("A 已结束（寄售 1 ms）", seedEnded);
        report.note("nonce=" + nonce + " listing_a=" + uid(listingA) + " listing_c=" + uid(listingC) + " listing_an="
                + uid(listingNotice) + " listing_ae=" + uid(listingEnded));
        report.check(distinct(listingA, listingC, listingNotice, listingEnded), "第 1 步 四次播种得到四个不同的编号（不幂等，§3.7）",
                uid(listingA) + " / " + uid(listingC) + " / " + uid(listingNotice) + " / " + uid(listingEnded), REF);

        // ---- 第 2 步：TradeAdmin 对客户端不可达（gate 丢弃，不回包） ----
        int mark = a.mark();
        long viaGateId = a.send(seedViaGate, seedRequest(a.id(), gateTitle, 0, SALE_DURATION_MS));
        GetMyShelfResponse probe = a.call(shelf, GetMyShelfRequest.newBuilder().setPage(1).build(), GetMyShelfResponse.parser());
        Optional<Received> late = a.connection().await(mark,
                r -> r.requestId() == viaGateId || r.messageId() == seedViaGate || isUnavailableTip(r), ADMIN_VIA_GATE_GRACE);
        report.check(late.isEmpty() && tipOf(probe) == 0 && a.connection().isOpen(),
                "第 2 步 A 经 gate 发 199 TradeAdmin.SeedListing → 没有任何回包（不回信封、不推 23 {1003}），之后同一连接 GetMyShelf 照常受理",
                late.map(r -> "收到 message_id=" + r.messageId() + " id=" + r.requestId() + " 信封 tip=" + r.envelopeTipId()
                                + "：gate 白名单收了 199")
                        .orElse("探测 GetMyShelf " + describeTip(errorOf(probe))), REF);

        // ---- 第 3 步：市场范围 ----
        BrowseListingsResponse bOnSale = browse(b, onSale(nonce), "第 3 步 B 浏览寄售页签（search = 本轮 nonce）");
        must(bOnSale.getMarketScopeValue() == expectScope.getNumber(), "第 3 步 应答 market_scope 与 --trade-scope 一致",
                "market_scope=" + scopeName(bOnSale.getMarketScopeValue()) + "，期望 " + scopeName(expectScope.getNumber())
                        + "（xm-trade 的 xm.trade.market.scope 与 --trade-scope 不一致？两种范围各重启一次 xm-trade 跑）");
        checkSummary(bOnSale.getListingsList(), listingA, seedA, ListingPhase.LISTING_PHASE_ON_SALE, false,
                "第 3 步 B 在寄售页签看到同区 A 的商品，摘要逐字段与种子一致（ON_SALE、is_mine = false、market_zone = 本区）");
        report.check(find(bOnSale.getListingsList(), listingC) != null, "第 3 步 B 看到同区 C 的商品（单区：两种范围都可见）",
                describe(bOnSale), REF);
        report.check(!hasTitle(bOnSale.getListingsList(), gateTitle), "第 2 步复核 经 gate 发的种子「" + gateTitle + "」不在浏览结果里",
                describe(bOnSale), REF);
        report.check(Integer.toUnsignedLong(bOnSale.getTotalCount()) == 2 && bOnSale.getListingsCount() == 2,
                "第 3 步 本轮 nonce 在寄售页签恰好 2 条（A、C；公示中的 AN、已结束的 AE 不在）", describe(bOnSale), REF);
        int otherZone = otherZone(zoneId);
        if (expectScope == MarketScope.MARKET_SCOPE_ZONE) {
            BrowseListingsResponse filtered = browse(b, onSale(nonce).setZoneFilter(otherZone),
                    "第 3 步 zone 范围下 B 传 zone_filter=" + Integer.toUnsignedString(otherZone));
            report.check(find(filtered.getListingsList(), listingA) != null && find(filtered.getListingsList(), listingC) != null,
                    "第 3 步 zone 范围：zone_filter 被忽略，B 仍看到本区的 A、C（客户端参数不能改变市场范围）", describe(filtered), REF);
        } else {
            BrowseListingsResponse filtered = browse(b, onSale(nonce).setZoneFilter(otherZone),
                    "第 3 步 global 范围下 B 传 zone_filter=" + Integer.toUnsignedString(otherZone));
            report.check(filtered.getListingsCount() == 0 && filtered.getTotalCount() == 0,
                    "第 3 步 global 范围：zone_filter=别区 → 本区的 A、C 都不在", describe(filtered), REF);
            BrowseListingsResponse own = browse(b, onSale(nonce).setZoneFilter(zoneId),
                    "第 3 步 global 范围下 B 传 zone_filter=" + Integer.toUnsignedString(zoneId));
            report.check(find(own.getListingsList(), listingA) != null && find(own.getListingsList(), listingC) != null,
                    "第 3 步 global 范围：zone_filter=本区 → 仍看到 A、C", describe(own), REF);
        }
        report.note("跨区步骤（C 在别区：zone 下 B 看不到 C、C 看不到 A、zone_filter=zone_b 不能扩大范围；global 下 zone_filter=zone_b 只剩 C；"
                + "详情 C 看 A zone 下 20000 / global 下受理）：本机切片只有一个 zone，跳过（T10 / Q3）；由 xm-trade 服务单测与 MySQL 测试覆盖");

        // ---- 第 4 步：公示页签 ----
        report.check(find(bOnSale.getListingsList(), listingNotice) == null && find(bOnSale.getListingsList(), listingEnded) == null,
                "第 4 步 公示中的 AN、已结束的 AE 不在寄售列表", describe(bOnSale), REF);
        BrowseListingsResponse bNotice = browse(b, notice(nonce), "第 4 步 B 浏览公示页签");
        checkSummary(bNotice.getListingsList(), listingNotice, seedNotice, ListingPhase.LISTING_PHASE_PUBLIC_NOTICE, false,
                "第 4 步 B 在公示列表看到 A 的 AN，PUBLIC_NOTICE、摘要与种子一致");
        report.check(find(bNotice.getListingsList(), listingA) == null && find(bNotice.getListingsList(), listingC) == null
                        && find(bNotice.getListingsList(), listingEnded) == null,
                "第 4 步 已进入寄售的 A、C 与已结束的 AE 不在公示列表", describe(bNotice), REF);

        // ---- 第 5 步：分页钳制 ----
        BrowseListingsResponse zeroPage = browse(b, onSale(nonce).setPage(0).setPageSize(0), "第 5 步 page = 0 / page_size = 0");
        report.check(zeroPage.getPage() == 1 && zeroPage.getPageSize() == SERVER_DEFAULT_PAGE_SIZE,
                "第 5 步 page = 0 视为 1、page_size = 0 取缺省 " + SERVER_DEFAULT_PAGE_SIZE, describe(zeroPage), REF);
        BrowseListingsResponse oversize = browse(b, onSale(nonce).setPageSize(OVERSIZE_PAGE_SIZE),
                "第 5 步 page_size = " + OVERSIZE_PAGE_SIZE);
        report.check(oversize.getPageSize() == SERVER_MAX_PAGE_SIZE && oversize.getListingsCount() <= SERVER_MAX_PAGE_SIZE,
                "第 5 步 page_size = " + OVERSIZE_PAGE_SIZE + " → 回显 " + SERVER_MAX_PAGE_SIZE + "、条数不超过它", describe(oversize), REF);
        BrowseListingsResponse beyond = browse(b, onSale(nonce).setPage(BEYOND_LAST_PAGE).setPageSize(1),
                "第 5 步 page = " + BEYOND_LAST_PAGE + " / page_size = 1");
        report.check(beyond.getPage() == beyond.getPageCount() && beyond.getPageCount() == 2 && beyond.getListingsCount() == 1,
                "第 5 步 page = " + BEYOND_LAST_PAGE + " 钳到末页（本轮寄售 2 条、页长 1 → page = page_count = 2、恰好 1 条）",
                describe(beyond), REF);

        // ---- 第 6 步：拍卖分区未开放 ----
        expect(b, browse, onSale(nonce).setSection(ListingSection.LISTING_SECTION_AUCTION).build(),
                BrowseListingsResponse.parser(), TIP_FEATURE_DISABLED, "第 6 步 拍卖分区 → 20003（只带 error_message）");

        // ---- Java 增项：参数非法矩阵（纯校验，任一失败 1005，参数非法优先于拍卖） ----
        expect(b, browse, onSale("搜".repeat(MAX_SEARCH_CODE_POINTS + 1)).build(), BrowseListingsResponse.parser(),
                TIP_INVALID_PARAMETER, "search " + (MAX_SEARCH_CODE_POINTS + 1) + " 个汉字 → 1005");
        expect(b, browse, onSale(nonce).setCategory(ListingCategory.LISTING_CATEGORY_UNSPECIFIED).build(),
                BrowseListingsResponse.parser(), TIP_INVALID_PARAMETER, "category = 0 → 1005");
        expect(b, browse, onSale(nonce).setSubcategory(WEAPON_MAX_SUBCATEGORY + 1).build(), BrowseListingsResponse.parser(),
                TIP_INVALID_PARAMETER, "武器子类 " + (WEAPON_MAX_SUBCATEGORY + 1) + " → 1005");
        expect(b, browse, onSale(nonce).setSection(ListingSection.LISTING_SECTION_UNSPECIFIED).build(),
                BrowseListingsResponse.parser(), TIP_INVALID_PARAMETER, "section = 0 → 1005");
        expect(b, browse, onSale(nonce).setTab(ListingTab.LISTING_TAB_UNSPECIFIED).build(), BrowseListingsResponse.parser(),
                TIP_INVALID_PARAMETER, "tab = 0 → 1005");
        expect(b, browse, onSale(nonce).setSortValue(UNKNOWN_SORT).build(), BrowseListingsResponse.parser(),
                TIP_INVALID_PARAMETER, "sort = " + UNKNOWN_SORT + " → 1005");
        expect(b, browse, onSale(nonce).setSection(ListingSection.LISTING_SECTION_AUCTION)
                        .setTab(ListingTab.LISTING_TAB_UNSPECIFIED).build(), BrowseListingsResponse.parser(),
                TIP_INVALID_PARAMETER, "拍卖分区 + tab = 0 → 1005（参数非法优先于 20003）");

        // ---- Java 增项：搜索语义（LIKE 通配按字面量、按编号） ----
        BrowseListingsResponse percent = browse(b, onSale(nonce + "%"), "search = nonce + \"%\"");
        report.check(percent.getTotalCount() == 0 && percent.getListingsCount() == 0,
                "search 里的 % 按字面量匹配（本轮标题不含 %：0 条；当通配符会命中 A、C）", describe(percent), REF);
        BrowseListingsResponse underscore = browse(b, onSale(nonce + "_A"), "search = nonce + \"_A\"");
        report.check(underscore.getTotalCount() == 0 && underscore.getListingsCount() == 0,
                "search 里的 _ 按字面量匹配（「" + nonce + "-A」不含「_A」：0 条；当通配符会命中 A）", describe(underscore), REF);
        BrowseListingsResponse byId = browse(b, onSale(uid(listingA)), "search = A 的编号");
        report.check(find(byId.getListingsList(), listingA) != null, "纯数字 search 按编号精确匹配到 A", describe(byId), REF);

        // ---- 第 7 步：详情 ----
        GetListingDetailResponse bDetail = expect(b, detail, detailOf(listingA), GetListingDetailResponse.parser(), 0,
                "第 7 步 B 取 A 的详情");
        report.check(detailProblem(bDetail, seedA, ListingPhase.LISTING_PHASE_ON_SALE, false, zoneId).isEmpty(),
                "第 7 步 详情摘要与种子一致（ON_SALE、is_mine = false）、描述一致、server_now_ms ≠ 0",
                orOk(detailProblem(bDetail, seedA, ListingPhase.LISTING_PHASE_ON_SALE, false, zoneId)), REF);
        expect(b, detail, detailOf(MISSING_LISTING_ID), GetListingDetailResponse.parser(), TIP_NOT_FOUND,
                "第 7 步 不存在的编号 " + uid(MISSING_LISTING_ID) + " → 20000（只带 error_message）");
        GetListingDetailResponse aDetail = expect(a, detail, detailOf(listingA), GetListingDetailResponse.parser(), 0,
                "第 7 步 A 取自己的 A");
        report.check(detailProblem(aDetail, seedA, ListingPhase.LISTING_PHASE_ON_SALE, true, zoneId).isEmpty(),
                "第 7 步 卖家看自己的商品 is_mine = true", orOk(detailProblem(aDetail, seedA, ListingPhase.LISTING_PHASE_ON_SALE,
                        true, zoneId)), REF);
        // Java 增项：已结束种子（卖家豁免 / 买家不可见）、id 0
        GetListingDetailResponse endedSeller = expect(a, detail, detailOf(listingEnded), GetListingDetailResponse.parser(), 0,
                "已结束的 AE：卖家详情受理（卖家豁免，不看状态与时间）");
        report.check(detailProblem(endedSeller, seedEnded, ListingPhase.LISTING_PHASE_ENDED, true, zoneId).isEmpty(),
                "已结束的 AE：卖家详情 phase = ENDED、摘要与种子一致", orOk(detailProblem(endedSeller, seedEnded,
                        ListingPhase.LISTING_PHASE_ENDED, true, zoneId)), REF);
        expect(b, detail, detailOf(listingEnded), GetListingDetailResponse.parser(), TIP_NOT_FOUND,
                "已结束的 AE：买家详情 → 20000（不可见即不存在）");
        expect(b, detail, detailOf(0), GetListingDetailResponse.parser(), TIP_INVALID_PARAMETER, "详情 listing_id = 0 → 1005");

        // ---- 第 8 步：收藏 ----
        SetFavoriteResponse favOn = expect(b, favorite, favoriteOf(listingA, true), SetFavoriteResponse.parser(), 0,
                "第 8 步 B 收藏 A");
        report.check(favOn.getListingId() == listingA && favOn.getFavorite(), "第 8 步 收藏应答 listing_id = A、favorite = true",
                describe(favOn), REF);
        BrowseListingsResponse favView = browse(b, onSale(nonce).setFavoritesOnly(true), "第 8 步 B 只看收藏");
        ListingSummary favA = find(favView.getListingsList(), listingA);
        report.check(favA != null && favA.getIsFavorite() && find(favView.getListingsList(), listingC) == null,
                "第 8 步 只看收藏：A 在且 is_favorite = true，没收藏的 C 不在", describe(favView), REF);
        SetFavoriteResponse favOff = expect(b, favorite, favoriteOf(listingA, false), SetFavoriteResponse.parser(), 0,
                "第 8 步 B 取消收藏 A");
        report.check(favOff.getListingId() == listingA && !favOff.getFavorite(), "第 8 步 取消应答 listing_id = A、favorite = false",
                describe(favOff), REF);
        BrowseListingsResponse unfavView = browse(b, onSale(nonce).setFavoritesOnly(true), "第 8 步 取消后再只看收藏");
        report.check(find(unfavView.getListingsList(), listingA) == null, "第 8 步 取消收藏后只看收藏不再返回 A",
                describe(unfavView), REF);
        // Java 增项：收藏的边界（拒绝也回填 listing_id）
        SetFavoriteResponse favZero = expect(b, favorite, favoriteOf(0, true), SetFavoriteResponse.parser(),
                TIP_INVALID_PARAMETER, "SetFavorite listing_id = 0 → 1005", "listing_id");
        report.check(favZero.getListingId() == 0 && !favZero.getFavorite(), "SetFavorite(0) 拒绝应答回填 listing_id = 0、favorite = false",
                describe(favZero), REF);
        SetFavoriteResponse favMissing = expect(b, favorite, favoriteOf(MISSING_LISTING_ID, true), SetFavoriteResponse.parser(),
                TIP_NOT_FOUND, "收藏不存在的编号 → 20000", "listing_id");
        report.check(favMissing.getListingId() == MISSING_LISTING_ID && !favMissing.getFavorite(),
                "收藏被拒也回填请求里的 listing_id（§0.3 唯一例外）", describe(favMissing), REF);
        SetFavoriteResponse unfavMissing = expect(b, favorite, favoriteOf(MISSING_LISTING_ID, false), SetFavoriteResponse.parser(),
                0, "取消收藏不存在的编号 → 受理（取消不查商品）");
        report.check(unfavMissing.getListingId() == MISSING_LISTING_ID && !unfavMissing.getFavorite(),
                "取消不存在的编号：listing_id 回显、favorite = false", describe(unfavMissing), REF);
        // Java 增项：公示中的商品对买家可见，可以收藏
        SetFavoriteResponse favNotice = expect(b, favorite, favoriteOf(listingNotice, true), SetFavoriteResponse.parser(), 0,
                "公示中的 AN 可收藏");
        report.check(favNotice.getListingId() == listingNotice && favNotice.getFavorite(), "收藏 AN 应答 favorite = true",
                describe(favNotice), REF);
        BrowseListingsResponse favNoticeView = browse(b, notice(nonce).setFavoritesOnly(true), "只看收藏 + 公示页签");
        ListingSummary favAn = find(favNoticeView.getListingsList(), listingNotice);
        report.check(favAn != null && favAn.getIsFavorite() && favAn.getPhaseValue() == ListingPhase.LISTING_PHASE_PUBLIC_NOTICE_VALUE,
                "只看收藏 + 公示页签：AN 在、is_favorite = true、PUBLIC_NOTICE", describe(favNoticeView), REF);
        SetFavoriteResponse unfavNotice = expect(b, favorite, favoriteOf(listingNotice, false), SetFavoriteResponse.parser(), 0,
                "取消收藏 AN（不留过期收藏占名额，§2.7）");
        report.check(!unfavNotice.getFavorite(), "取消 AN 应答 favorite = false", describe(unfavNotice), REF);

        // ---- 第 9 步：货架 ----
        GetMyShelfResponse aShelf = expect(a, shelf, shelfPage1(), GetMyShelfResponse.parser(), 0, "第 9 步 A 的货架第 1 页");
        report.check(shelfProblem(aShelf).isEmpty(), "第 9 步 货架应答 server_now_ms ≠ 0、分页自洽", orOk(shelfProblem(aShelf)), REF);
        List<String> missingMine = new ArrayList<>();
        for (long id : new long[] {listingA, listingNotice, listingEnded}) {
            ListingSummary s = find(aShelf.getListingsList(), id);
            if (s == null || !s.getIsMine()) {
                missingMine.add(uid(id) + (s == null ? " 不在" : " is_mine = false"));
            }
        }
        report.check(missingMine.isEmpty() && Integer.toUnsignedLong(aShelf.getTotalCount()) == 3,
                "第 9 步 A 的货架恰好是本轮的 A、AN、AE 三条（新号），都 is_mine = true",
                missingMine.isEmpty() ? describe(aShelf) : String.join("；", missingMine) + " " + describe(aShelf), REF);
        ListingSummary endedOnShelf = find(aShelf.getListingsList(), listingEnded);
        report.check(endedOnShelf != null && endedOnShelf.getPhaseValue() == ListingPhase.LISTING_PHASE_ENDED_VALUE,
                "货架包含任意状态：已结束的 AE 可见、phase = ENDED", endedOnShelf == null ? "AE 不在货架" : describe(endedOnShelf), REF);
        GetMyShelfResponse bShelf = expect(b, shelf, shelfPage1(), GetMyShelfResponse.parser(), 0, "第 9 步 B 的货架");
        report.check(bShelf.getTotalCount() == 0 && bShelf.getListingsCount() == 0 && bShelf.getServerNowMs() != 0,
                "第 9 步 B 没上架过：货架为空（货架按会话身份过滤）", describe(bShelf), REF);
        GetMyShelfResponse cShelf = expect(c, shelf, shelfPage1(), GetMyShelfResponse.parser(), 0, "第 9 步 C 的货架");
        ListingSummary cOwn = find(cShelf.getListingsList(), listingC);
        report.check(cOwn != null && cOwn.getIsMine() && cShelf.getTotalCount() == 1,
                "第 9 步 C 的货架只有自己的 C（is_mine = true）", describe(cShelf), REF);
    }

    // ================================================================ 播种与请求构造

    /** 播一条种子：受理、编号非 0 是后续步骤的前提（不满足即中止）；market_zone 必须是卖家归属区 = 本区。 */
    private long seed(String label, SeedListingRequest request) throws RobotException {
        SeedListingResponse response;
        try {
            response = admin.seedListing(request);
        } catch (RobotException e) {
            must(false, "第 1 步 经 xm-trade 播种接口造「" + label + "」", e.getMessage());
            throw e;
        }
        int tip = errorOf(response).getId();
        must(tip == 0 && response.getListingId() != 0, "第 1 步 经 xm-trade 播种接口造「" + label + "」：受理且 listing_id ≠ 0",
                describeTip(errorOf(response)) + seedHint(tip) + " listing_id=" + uid(response.getListingId()));
        report.check(response.getMarketZone() == zoneId, "第 1 步「" + label + "」market_zone = 卖家归属区 = 本区（服务端按卖家查，请求里没有这个字段）",
                "market_zone=" + Integer.toUnsignedString(response.getMarketZone()) + "，期望 " + Integer.toUnsignedString(zoneId), REF);
        return response.getListingId();
    }

    /** 武器类种子（Go tradeSmokeSeedRequest）；noticeMs = 0 表示立即寄售。 */
    static SeedListingRequest seedRequest(long seller, String title, long noticeMs, long saleMs) {
        return SeedListingRequest.newBuilder()
                .setSellerPlayerId(seller)
                .setCategory(CATEGORY)
                .setSubcategory(SUBCATEGORY)
                .setTitle(title)
                .setLevel(LEVEL)
                .setPriceFen(PRICE_FEN)
                .setSummary("trade-smoke 种子")
                .setDescription("trade-smoke 种子商品 " + title)
                .setIconKey(ICON_KEY)
                .setNoticeDurationMs(noticeMs)
                .setSaleDurationMs(saleMs)
                .build();
    }

    /** 寄售页签、一口价分区、武器 / 子类 1、缺省排序、第 1 页（Go onSale）。 */
    static BrowseListingsRequest.Builder onSale(String search) {
        return BrowseListingsRequest.newBuilder()
                .setTab(ListingTab.LISTING_TAB_ON_SALE)
                .setSection(ListingSection.LISTING_SECTION_CONSIGNMENT)
                .setCategory(CATEGORY)
                .setSubcategory(SUBCATEGORY)
                .setSearch(search)
                .setSort(ListingSort.LISTING_SORT_DEFAULT)
                .setPage(1);
    }

    static BrowseListingsRequest.Builder notice(String search) {
        return onSale(search).setTab(ListingTab.LISTING_TAB_PUBLIC_NOTICE);
    }

    private static GetListingDetailRequest detailOf(long listingId) {
        return GetListingDetailRequest.newBuilder().setListingId(listingId).build();
    }

    private static SetFavoriteRequest favoriteOf(long listingId, boolean on) {
        return SetFavoriteRequest.newBuilder().setListingId(listingId).setFavorite(on).build();
    }

    private static GetMyShelfRequest shelfPage1() {
        return GetMyShelfRequest.newBuilder().setPage(1).build();
    }

    /**
     * 本轮标题前缀 {@code SMK-<纪元纳秒>}（Go tradeSmokeNonce）：没有下架，种子逐轮累积，所有浏览都以它作 search、按 listing_id 找自己的商品。
     * 不含 LIKE 通配符与转义符，也不是纯数字（不会走编号精确匹配）。
     */
    static String nonce() {
        Instant now = Instant.now();
        return "SMK-" + (now.getEpochSecond() * 1_000_000_000L + now.getNano());
    }

    /** 单区下的「别区」：本区 + 1（uint32 回绕到 0 时取本区 − 1，0 在 global 下是「全部区」）。 */
    static int otherZone(int zone) {
        int other = zone + 1;
        return other == 0 ? zone - 1 : other;
    }

    // ================================================================ 发请求与判定

    /** 发请求并断言业务 tip（应答体 error_message）与应答形状；信封拒绝、23 {1003} 与超时抛出（请求没到 xm-trade 业务逻辑）。 */
    private <T extends Message> T expect(Bot bot, int messageId, Message request, Parser<T> parser, int want, String name,
                                         String... echoed) throws RobotException {
        T response = bot.call(messageId, request, parser);
        String problem = tipProblem(response, want, Set.of(echoed));
        report.check(problem.isEmpty(), name, describeTip(errorOf(response)) + (problem.isEmpty() ? "" : "；" + problem), REF);
        return response;
    }

    /** 期望受理的浏览：tip / 形状之外再核对每次浏览都必须成立的不变量（Go browse）。 */
    private BrowseListingsResponse browse(Bot bot, BrowseListingsRequest.Builder request, String name) throws RobotException {
        BrowseListingsResponse response = bot.call(browse, request.build(), BrowseListingsResponse.parser());
        List<String> problems = new ArrayList<>();
        String tip = tipProblem(response, 0, Set.of());
        if (!tip.isEmpty()) {
            problems.add(tip);
        }
        String invariants = browseProblem(response, expectScope);
        if (!invariants.isEmpty()) {
            problems.add(invariants);
        }
        report.check(problems.isEmpty(), name + "：受理，market_scope / server_now_ms / 分页自洽",
                describe(response) + (problems.isEmpty() ? "" : "；" + String.join("；", problems)), REF);
        return response;
    }

    private void checkSummary(List<ListingSummary> listings, long listingId, SeedListingRequest seed, ListingPhase phase,
                              boolean mine, String name) {
        ListingSummary s = find(listings, listingId);
        String problem = s == null ? "列表里没有 " + uid(listingId) + "（共 " + listings.size() + " 条）"
                : summaryProblem(s, seed, phase, mine, zoneId);
        report.check(problem.isEmpty(), name, problem.isEmpty() ? describe(s) : problem, REF);
    }

    /** 后续步骤依赖的检查：不通过即中止（记一条失败后抛出）。 */
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，后续步骤依赖它");
        }
    }

    private Bot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        connections.add(player.connection());
        return new Bot(name, player, requestTimeout, sendTip);
    }

    private boolean isUnavailableTip(Received r) {
        return Bot.isUnavailableTip(r, sendTip);
    }

    // ================================================================ 纯函数（TradeScenarioTest 钉住）

    /**
     * 应答的 tip 与形状（trade-spec §0.3）：tip = want、不带 parameters；成功时不设 error_message；拒绝时只设 error_message
     * （外加 {@code echoed} 里的字段，SetFavorite 回填 listing_id）。
     *
     * @return 空串 = 符合；否则是不符之处
     */
    static String tipProblem(Message response, int want, Set<String> echoed) {
        TipInfoMessage tip = errorOf(response);
        List<String> problems = new ArrayList<>();
        if (tip.getId() != want) {
            problems.add("期望 tip=" + want + hint(tip.getId()));
        }
        if (tip.getParametersCount() > 0) {
            problems.add("tip 带了 parameters（基线 tipOf 永不带）");
        }
        if (want == 0 && hasErrorMessage(response)) {
            problems.add("成功应答设了 error_message（基线成功时不设）");
        }
        if (want != 0) {
            List<String> extra = new ArrayList<>();
            for (Descriptors.FieldDescriptor field : response.getAllFields().keySet()) {
                if (!field.getName().equals("error_message") && !echoed.contains(field.getName())) {
                    extra.add(field.getName());
                }
            }
            if (!extra.isEmpty()) {
                problems.add("拒绝应答除 error_message 外还带了 " + extra + "（基线拒绝时其余字段全是零值）");
            }
        }
        return String.join("；", problems);
    }

    /** 每次浏览都必须成立的不变量（Go browse :693-713）。@return 空串 = 符合 */
    static String browseProblem(BrowseListingsResponse response, MarketScope expect) {
        List<String> problems = new ArrayList<>();
        if (response.getMarketScopeValue() != expect.getNumber()) {
            problems.add("market_scope=" + scopeName(response.getMarketScopeValue()) + "，期望 " + scopeName(expect.getNumber()));
        }
        if (response.getServerNowMs() == 0) {
            problems.add("server_now_ms=0");
        }
        String page = pageProblem(response.getTotalCount(), response.getPage(), response.getPageSize(), response.getPageCount());
        if (!page.isEmpty()) {
            problems.add(page);
        }
        return String.join("；", problems);
    }

    /** 货架应答：server_now_ms ≠ 0、分页自洽（货架没有 market_scope）。@return 空串 = 符合 */
    static String shelfProblem(GetMyShelfResponse response) {
        List<String> problems = new ArrayList<>();
        if (response.getServerNowMs() == 0) {
            problems.add("server_now_ms=0");
        }
        String page = pageProblem(response.getTotalCount(), response.getPage(), response.getPageSize(), response.getPageCount());
        if (!page.isEmpty()) {
            problems.add(page);
        }
        return String.join("；", problems);
    }

    /**
     * 分页自洽（Go tradeSmokePageCountConsistent + 页码范围）：page_size ≠ 0、page_count = max(1, ⌈total / page_size⌉)、1 ≤ page ≤ page_count。
     * 四个值都是 uint32 位模式。@return 空串 = 符合
     */
    static String pageProblem(int total, int page, int pageSize, int pageCount) {
        long t = Integer.toUnsignedLong(total);
        long p = Integer.toUnsignedLong(page);
        long size = Integer.toUnsignedLong(pageSize);
        long count = Integer.toUnsignedLong(pageCount);
        if (size == 0) {
            return "page_size=0";
        }
        long want = Math.max(1, (t + size - 1) / size);
        if (count != want) {
            return "page_count=" + count + "，期望 max(1, ⌈" + t + " / " + size + "⌉) = " + want;
        }
        if (p == 0 || p > count) {
            return "page=" + p + " 不在 [1, page_count=" + count + "] 内";
        }
        return "";
    }

    /**
     * 列表 / 详情里的摘要与种子一致（Go tradeSmokeCheckSummary）：标题、类目、子类、等级、价格、摘要、图标、阶段、is_mine、market_zone，
     * 以及 {@code sale_end_ms − notice_end_ms = 寄售时长}（服务端按 notice_end + sale 写入，§1.2；也就蕴含基线的 sale_end > notice_end）。
     *
     * @return 空串 = 符合；否则是不符之处
     */
    static String summaryProblem(ListingSummary s, SeedListingRequest seed, ListingPhase phase, boolean mine, int zone) {
        String id = uid(s.getListingId());
        if (!s.getTitle().equals(seed.getTitle())) {
            return "商品 " + id + " title=" + s.getTitle() + "，期望 " + seed.getTitle();
        }
        if (s.getCategoryValue() != seed.getCategoryValue() || s.getSubcategory() != seed.getSubcategory()) {
            return "商品 " + id + " category=" + s.getCategoryValue() + " subcategory=" + Integer.toUnsignedString(s.getSubcategory())
                    + "，期望 " + seed.getCategoryValue() + " / " + Integer.toUnsignedString(seed.getSubcategory());
        }
        if (s.getLevel() != seed.getLevel() || s.getPriceFen() != seed.getPriceFen()) {
            return "商品 " + id + " level=" + Integer.toUnsignedString(s.getLevel()) + " price_fen=" + uid(s.getPriceFen())
                    + "，期望 " + Integer.toUnsignedString(seed.getLevel()) + " / " + uid(seed.getPriceFen());
        }
        if (!s.getSummary().equals(seed.getSummary()) || !s.getIconKey().equals(seed.getIconKey())) {
            return "商品 " + id + " summary=" + s.getSummary() + " icon_key=" + s.getIconKey() + "，期望 " + seed.getSummary()
                    + " / " + seed.getIconKey();
        }
        if (s.getPhaseValue() != phase.getNumber()) {
            return "商品 " + id + " phase=" + phaseName(s.getPhaseValue()) + "，期望 " + phase;
        }
        if (s.getIsMine() != mine) {
            return "商品 " + id + " is_mine=" + s.getIsMine() + "，期望 " + mine;
        }
        if (s.getMarketZone() != zone) {
            return "商品 " + id + " market_zone=" + Integer.toUnsignedString(s.getMarketZone()) + "，期望 " + Integer.toUnsignedString(zone);
        }
        if (s.getSaleEndMs() - s.getNoticeEndMs() != seed.getSaleDurationMs()
                || Long.compareUnsigned(s.getSaleEndMs(), s.getNoticeEndMs()) <= 0) {
            return "商品 " + id + " notice_end_ms=" + uid(s.getNoticeEndMs()) + " sale_end_ms=" + uid(s.getSaleEndMs())
                    + "，期望 sale_end − notice_end = 寄售时长 " + uid(seed.getSaleDurationMs()) + " ms";
        }
        return "";
    }

    /** 详情应答：server_now_ms ≠ 0、有摘要且与种子一致、描述一致（Go tradeSmokeCheckDetail）。@return 空串 = 符合 */
    static String detailProblem(GetListingDetailResponse response, SeedListingRequest seed, ListingPhase phase, boolean mine,
                                int zone) {
        if (response.getServerNowMs() == 0) {
            return "详情应答 server_now_ms=0";
        }
        if (!response.hasDetail() || !response.getDetail().hasSummary()) {
            return "详情受理但应答里没有 detail.summary";
        }
        String summary = summaryProblem(response.getDetail().getSummary(), seed, phase, mine, zone);
        if (!summary.isEmpty()) {
            return summary;
        }
        if (!response.getDetail().getDescription().equals(seed.getDescription())) {
            return "商品 " + uid(response.getDetail().getSummary().getListingId()) + " description=" + response.getDetail().getDescription()
                    + "，期望 " + seed.getDescription();
        }
        return "";
    }

    static ListingSummary find(List<ListingSummary> listings, long listingId) {
        for (ListingSummary s : listings) {
            if (s.getListingId() == listingId) {
                return s;
            }
        }
        return null;
    }

    static boolean hasTitle(List<ListingSummary> listings, String title) {
        return listings.stream().anyMatch(s -> s.getTitle().equals(title));
    }

    static boolean distinct(long... ids) {
        return java.util.Arrays.stream(ids).distinct().count() == ids.length;
    }

    /** 应答的 {@code error_message}（所有聚宝斋应答的第 1 个字段，见 TradeScenarioTest）；没设时为缺省实例。 */
    static TipInfoMessage errorOf(Message response) {
        Descriptors.FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
        if (field == null || !response.hasField(field)) {
            return TipInfoMessage.getDefaultInstance();
        }
        return (TipInfoMessage) response.getField(field);
    }

    static int tipOf(Message response) {
        return errorOf(response).getId();
    }

    private static boolean hasErrorMessage(Message response) {
        Descriptors.FieldDescriptor field = response.getDescriptorForType().findFieldByName("error_message");
        return field != null && response.hasField(field);
    }

    static String describeTip(TipInfoMessage tip) {
        return "tip=" + tip.getId() + (tip.getParametersCount() > 0 ? " parameters=" + tip.getParametersList() : "");
    }

    /** 常见的环境类拒绝码翻成可读原因（Go expect :679-689）。 */
    static String hint(int tip) {
        if (tip == TIP_HOME_ZONE_UNKNOWN) {
            return "（角色归属区未确认：xm_java.player.zone_id 为 0）";
        }
        if (tip == TIP_SERVICE_UNAVAILABLE) {
            return "（xm-trade 存储 / 归属区查询故障或请求预算到期，看 xm-trade 错误日志）";
        }
        if (tip == TIP_INVALID_PARAMETER) {
            return "（请求校验不通过）";
        }
        if (tip == TIP_FAVORITE_LIMIT) {
            return "（收藏数已达上限）";
        }
        return "";
    }

    /** 播种被拒的提示（Go tradeSmokeSeed :753-763）。 */
    static String seedHint(int tip) {
        if (tip == TIP_HOME_ZONE_UNKNOWN) {
            return "（卖家没有归属区：xm_java.player.zone_id 为 0）";
        }
        if (tip == TIP_SERVICE_UNAVAILABLE) {
            return "（xm-trade 侧存储 / 归属区查询 / 发号故障：看 xm-trade 日志，确认 trade_listing 已建、listing_id 发号租约有效）";
        }
        if (tip == TIP_INVALID_PARAMETER) {
            return "（种子参数没通过 xm-trade 校验）";
        }
        return "";
    }

    /** 市场范围的选项写法（zone / global），未知值写成 {@code UNSPECIFIED(n)}。 */
    public static String scopeName(int scope) {
        if (scope == MarketScope.MARKET_SCOPE_ZONE_VALUE) {
            return "zone";
        }
        if (scope == MarketScope.MARKET_SCOPE_GLOBAL_VALUE) {
            return "global";
        }
        return "UNSPECIFIED(" + scope + ")";
    }

    private static String phaseName(int phase) {
        ListingPhase known = ListingPhase.forNumber(phase);
        return known == null ? "未知(" + phase + ")" : known.name();
    }

    /** 浏览 / 货架摘要：分页与条目（编号、阶段、标记）。号一律按无符号十进制。 */
    static String describe(BrowseListingsResponse r) {
        return describeTip(errorOf(r)) + " scope=" + scopeName(r.getMarketScopeValue()) + " total=" + Integer.toUnsignedString(r.getTotalCount())
                + " page=" + Integer.toUnsignedString(r.getPage()) + "/" + Integer.toUnsignedString(r.getPageCount())
                + " page_size=" + Integer.toUnsignedString(r.getPageSize()) + " listings=" + describe(r.getListingsList());
    }

    static String describe(GetMyShelfResponse r) {
        return describeTip(errorOf(r)) + " total=" + Integer.toUnsignedString(r.getTotalCount()) + " page="
                + Integer.toUnsignedString(r.getPage()) + "/" + Integer.toUnsignedString(r.getPageCount()) + " listings="
                + describe(r.getListingsList());
    }

    static String describe(SetFavoriteResponse r) {
        return describeTip(errorOf(r)) + " listing_id=" + uid(r.getListingId()) + " favorite=" + r.getFavorite();
    }

    static String describe(List<ListingSummary> listings) {
        List<String> parts = new ArrayList<>();
        for (ListingSummary s : listings) {
            parts.add(describe(s));
        }
        return parts.toString();
    }

    static String describe(ListingSummary s) {
        return uid(s.getListingId()) + ":" + s.getTitle() + ":" + phaseName(s.getPhaseValue()) + (s.getIsMine() ? ":mine" : "")
                + (s.getIsFavorite() ? ":fav" : "");
    }

    private static String orOk(String problem) {
        return problem.isEmpty() ? "一致" : problem;
    }

    static String uid(long id) {
        return Long.toUnsignedString(id);
    }

    /**
     * 一个已进场的机器人。只在场景线程上使用；每次发送先过 {@link GuildScenario.Pacer}（{@link #REQUEST_SPACING} 间隔）。
     * 等应答时同时盯着 23 {1003}：gate 没把聚宝斋的号路由到后端时只推 23、不回应答，立即判失败而不是干等超时（Go :646-649）。
     */
    static final class Bot {

        final String name;
        final EnteredPlayer player;
        private final Duration requestTimeout;
        private final int sendTip;
        private final GuildScenario.Pacer pacer = new GuildScenario.Pacer(REQUEST_SPACING, SAME_ID_WINDOW, SAME_ID_MAX_IN_WINDOW);

        Bot(String name, EnteredPlayer player, Duration requestTimeout, int sendTip) {
            this.name = name;
            this.player = player;
            this.requestTimeout = requestTimeout;
            this.sendTip = sendTip;
        }

        long id() {
            return player.playerId();
        }

        GameConnection connection() {
            return player.connection();
        }

        int mark() {
            return connection().inbox().size();
        }

        /** 发请求等应答；信封错误、23 {1003} 与超时抛出：它们说明请求没到 xm-trade 业务逻辑，不能当作 xm-trade 给的码去断言。 */
        <T extends Message> T call(int messageId, Message body, Parser<T> parser) throws RobotException {
            pace(messageId);
            GameConnection connection = connection();
            int mark = connection.inbox().size();
            long requestId = connection.send(messageId, body);
            Optional<Received> got = connection.await(mark,
                    r -> (r.messageId() == messageId && r.requestId() == requestId) || isUnavailableTip(r, sendTip), requestTimeout);
            if (got.isEmpty()) {
                throw new RobotException(name + "：" + requestTimeout.toMillis() + " ms 内没有收到 message_id=" + messageId + " id="
                        + requestId + " 的应答" + connection.describeSince(mark));
            }
            Received reply = got.get();
            if (reply.messageId() == sendTip) {
                throw new RobotException(name + "：发 message_id=" + messageId + " 后 gate 推了 23 {1003}、不回应答：gate 没把聚宝斋的号路由到"
                        + " xm-trade（gate 版本过旧，没有 trade 后端）");
            }
            if (reply.envelopeTipId() != 0) {
                throw new RobotException(name + "：message_id=" + messageId + " 的应答是信封错误 tip=" + reply.envelopeTipId()
                        + envelopeHint(reply.envelopeTipId()));
            }
            return reply.parse(parser);
        }

        /** 只发不等。 */
        long send(int messageId, Message body) throws RobotException {
            pace(messageId);
            return connection().send(messageId, body);
        }

        /** 23 {1003} 推送（requestId = 0）。 */
        static boolean isUnavailableTip(Received r, int sendTip) {
            if (r.messageId() != sendTip || r.requestId() != 0) {
                return false;
            }
            TipInfoMessage tip = r.parseOrNull(TipInfoMessage.parser());
            return tip != null && tip.getId() == TIP_SERVICE_UNAVAILABLE;
        }

        static String envelopeHint(int tip) {
            if (tip == TIP_SERVICE_UNAVAILABLE) {
                return "（请求没到 xm-trade 业务逻辑：xm-trade 不在 / Dubbo 调用失败或超时 / 方法被热关停 / 会话 player_id = 0 / "
                        + "请求体解析失败；看 gate 与 xm-trade 日志）";
            }
            if (tip == TIP_RATE_LIMITED) {
                return "（gate 限频：请求太密）";
            }
            return "";
        }

        private void pace(int messageId) throws RobotException {
            long waitNanos = pacer.delayNanos(messageId, System.nanoTime());
            if (waitNanos > 0) {
                try {
                    Thread.sleep(Duration.ofNanos(waitNanos));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RobotException("等待被中断", e);
                }
            }
            pacer.record(messageId, System.nanoTime());
        }
    }
}
