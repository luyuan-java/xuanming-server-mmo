package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.ClientRequest;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.trade.BrowseListingsRequest;
import com.game.proto.trade.BrowseListingsResponse;
import com.game.proto.trade.GetListingDetailResponse;
import com.game.proto.trade.GetMyShelfResponse;
import com.game.proto.trade.ListingCategory;
import com.game.proto.trade.ListingDetail;
import com.game.proto.trade.ListingPhase;
import com.game.proto.trade.ListingSection;
import com.game.proto.trade.ListingSummary;
import com.game.proto.trade.ListingTab;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SetFavoriteResponse;
import com.game.robot.RobotOptions;
import com.game.robot.UsageException;
import com.game.robot.client.Received;
import com.game.table.CommonErrorTip;
import com.game.table.TradeErrorTip;
import com.google.protobuf.Descriptors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** trade 场景里不连服务端就能钉住的部分：子命令与选项、节拍、种子合法、应答形状与不变量判定、摘要比对、无符号输出、23 {1003} 识别。 */
class TradeScenarioTest {

    /** 大于 Long.MAX_VALUE 的编号（uint64 位模式）。 */
    private static final long BIG = 0x8000_0000_0000_0001L;
    private static final long MS = 1_000_000L;
    private static final Map<String, String> ENV = Map.of(RobotOptions.PASSWORD_ENV, "p");

    @Test
    void trade子命令_账号带td标签_三个账号等长_缺省范围zone与播种地址() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("trade", "--run-tag", "x1"), ENV, 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.TRADE);
        assertThat(options.tradeScope()).as("Q9：缺省 zone，与 xm-trade 的 application.yaml 一致").isEqualTo(MarketScope.MARKET_SCOPE_ZONE);
        assertThat(options.tradeAdminUrl()).isEqualTo("http://127.0.0.1:18111");
        String a = TradeScenario.accountName(options.accountPrefix(), options.runTag(), "a");
        assertThat(a).isEqualTo("robot_java_tdx1_a");
        for (String suffix : List.of("b", "c")) {
            assertThat(TradeScenario.accountName(options.accountPrefix(), options.runTag(), suffix)).hasSize(a.length());
        }
        assertThat(RobotOptions.usage()).contains("|trade|", "  trade ", "--trade-scope", "XM_ROBOT_TRADE_SCOPE",
                "--trade-admin-url", "XM_ROBOT_TRADE_ADMIN_URL");
    }

    @Test
    void trade范围_global可选_环境变量可设_命令行优先_其他取值与非http地址报错() throws Exception {
        assertThat(RobotOptions.parse(List.of("trade", "--trade-scope", "global"), ENV, 0).tradeScope())
                .isEqualTo(MarketScope.MARKET_SCOPE_GLOBAL);
        Map<String, String> env = Map.of(RobotOptions.PASSWORD_ENV, "p", "XM_ROBOT_TRADE_SCOPE", "global",
                "XM_ROBOT_TRADE_ADMIN_URL", "http://10.0.0.5:18111/");
        RobotOptions fromEnv = RobotOptions.parse(List.of("trade"), env, 0);
        assertThat(fromEnv.tradeScope()).isEqualTo(MarketScope.MARKET_SCOPE_GLOBAL);
        assertThat(fromEnv.tradeAdminUrl()).isEqualTo("http://10.0.0.5:18111");
        assertThat(RobotOptions.parse(List.of("trade", "--trade-scope=zone"), env, 0).tradeScope())
                .isEqualTo(MarketScope.MARKET_SCOPE_ZONE);
        assertThatThrownBy(() -> RobotOptions.parse(List.of("trade", "--trade-scope", "Zone"), ENV, 0))
                .isInstanceOf(UsageException.class).hasMessageContaining("--trade-scope");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("trade", "--trade-scope", "all"), ENV, 0))
                .hasMessageContaining("--trade-scope");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("trade", "--trade-admin-url", "127.0.0.1:18111"), ENV, 0))
                .hasMessageContaining("--trade-admin-url");
    }

    @Test
    void 节拍_相邻请求隔300毫秒_任意1秒内同号不超过4条_低于198的每秒5条() {
        GuildScenario.Pacer pacer = new GuildScenario.Pacer(TradeScenario.REQUEST_SPACING, TradeScenario.SAME_ID_WINDOW,
                TradeScenario.SAME_ID_MAX_IN_WINDOW);
        List<Long> sent = new ArrayList<>();
        long now = 0;
        for (int i = 0; i < 20; i++) {
            now += pacer.delayNanos(198, now);
            pacer.record(198, now);
            sent.add(now);
            now += 3 * MS; // 往返耗时
        }
        for (long start : sent) {
            long inWindow = sent.stream().filter(t -> t >= start && t - start < 1000 * MS).count();
            assertThat(inWindow).as("从 %d ms 起 1 秒内", start / MS).isLessThanOrEqualTo(4);
        }
        for (int i = 1; i < sent.size(); i++) {
            assertThat(sent.get(i) - sent.get(i - 1)).isGreaterThanOrEqualTo(TradeScenario.REQUEST_SPACING.toNanos());
        }
    }

    @Test
    void 种子与请求都在基线校验范围内_整包小于gate的1KB() {
        SeedListingRequest seed = TradeScenario.seedRequest(BIG, TradeScenario.nonce() + "-GATE", TradeScenario.NOTICE_DURATION_MS,
                TradeScenario.SALE_DURATION_MS);
        // ValidSeedRequest（phase.go:188-213）：标题 ≤ 64 字、摘要 ≤ 128、描述 ≤ 512、icon [a-z0-9_] ≤ 64 字节、level ≤ 1000、
        // price ∈ [1, 1e10]、寄售 ∈ [1 ms, 90 天]、公示 ≤ 30 天
        assertThat(seed.getTitle().codePointCount(0, seed.getTitle().length())).isLessThanOrEqualTo(64);
        assertThat(seed.getSummary().codePointCount(0, seed.getSummary().length())).isLessThanOrEqualTo(128);
        assertThat(seed.getDescription().codePointCount(0, seed.getDescription().length())).isLessThanOrEqualTo(512);
        assertThat(seed.getIconKey()).matches("[a-z0-9_]{1,64}");
        assertThat(seed.getLevel()).isBetween(0, 1000);
        assertThat(seed.getPriceFen()).isBetween(1L, 10_000_000_000L);
        assertThat(seed.getSaleDurationMs()).isBetween(1L, Duration.ofDays(90).toMillis());
        assertThat(seed.getNoticeDurationMs()).isLessThanOrEqualTo(Duration.ofDays(30).toMillis());
        assertThat(TradeScenario.ENDED_SALE_DURATION_MS).isEqualTo(1L);
        assertThat(seed.getCategory()).isEqualTo(ListingCategory.LISTING_CATEGORY_WEAPON);
        assertThat(seed.getSubcategory()).isBetween(1, TradeScenario.WEAPON_MAX_SUBCATEGORY);
        assertThat(frameSize(seed.toByteString().size())).isLessThan(1024);
        // 65 个汉字的搜索：超的是服务端的 64 字上限，不是 gate 的 1 KB 包长（拒绝才来自 xm-trade）
        BrowseListingsRequest longSearch = TradeScenario.onSale("搜".repeat(TradeScenario.MAX_SEARCH_CODE_POINTS + 1)).build();
        assertThat(frameSize(longSearch.getSerializedSize())).isLessThan(1024);
        assertThat(TradeScenario.MISSING_LISTING_ID).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void nonce不是纯数字_不含LIKE通配与转义符_浏览请求的固定条件() {
        String nonce = TradeScenario.nonce();
        assertThat(nonce).matches("SMK-\\d{16,20}").doesNotContain("%", "_", "!", "\\");
        BrowseListingsRequest onSale = TradeScenario.onSale(nonce).build();
        assertThat(onSale.getTab()).isEqualTo(ListingTab.LISTING_TAB_ON_SALE);
        assertThat(onSale.getSection()).isEqualTo(ListingSection.LISTING_SECTION_CONSIGNMENT);
        assertThat(onSale.getPage()).isEqualTo(1);
        assertThat(onSale.getZoneFilter()).isZero();
        assertThat(TradeScenario.notice(nonce).build().getTab()).isEqualTo(ListingTab.LISTING_TAB_PUBLIC_NOTICE);
        assertThat(TradeScenario.otherZone(1)).isEqualTo(2);
        assertThat(TradeScenario.otherZone(-1)).as("uint32 上限回绕到 0（0 = 全部区）时取本区 − 1").isEqualTo(-2);
    }

    @Test
    void 分页自洽_按无符号算() {
        assertThat(TradeScenario.pageProblem(45, 3, 20, 3)).isEmpty();
        assertThat(TradeScenario.pageProblem(0, 1, 20, 1)).as("空结果 page_count 至少 1").isEmpty();
        assertThat(TradeScenario.pageProblem(2, 2, 1, 2)).isEmpty();
        assertThat(TradeScenario.pageProblem(-1, 1, -1, 1)).as("total = page_size = 2^32 − 1").isEmpty();
        assertThat(TradeScenario.pageProblem(-1, 1, 1, -1)).as("page_count = 2^32 − 1").isEmpty();
        assertThat(TradeScenario.pageProblem(45, 3, 20, 2)).contains("page_count=2", "= 3");
        assertThat(TradeScenario.pageProblem(0, 1, 0, 1)).isEqualTo("page_size=0");
        assertThat(TradeScenario.pageProblem(45, 0, 20, 3)).contains("page=0");
        assertThat(TradeScenario.pageProblem(45, 4, 20, 3)).contains("page=4");
    }

    @Test
    void 浏览不变量_范围_服务端时间_分页() {
        BrowseListingsResponse ok = BrowseListingsResponse.newBuilder().setTotalCount(2).setPage(1).setPageSize(20).setPageCount(1)
                .setMarketScope(MarketScope.MARKET_SCOPE_ZONE).setServerNowMs(1).build();
        assertThat(TradeScenario.browseProblem(ok, MarketScope.MARKET_SCOPE_ZONE)).isEmpty();
        assertThat(TradeScenario.browseProblem(ok, MarketScope.MARKET_SCOPE_GLOBAL)).contains("market_scope=zone，期望 global");
        assertThat(TradeScenario.browseProblem(ok.toBuilder().setMarketScopeValue(9).build(), MarketScope.MARKET_SCOPE_ZONE))
                .as("未知枚举值不抛异常").contains("UNSPECIFIED(9)");
        assertThat(TradeScenario.browseProblem(ok.toBuilder().setServerNowMs(0).build(), MarketScope.MARKET_SCOPE_ZONE))
                .contains("server_now_ms=0");
        assertThat(TradeScenario.browseProblem(ok.toBuilder().setPageCount(2).build(), MarketScope.MARKET_SCOPE_ZONE))
                .contains("page_count=2");
        GetMyShelfResponse shelf = GetMyShelfResponse.newBuilder().setTotalCount(3).setPage(1).setPageSize(20).setPageCount(1)
                .setServerNowMs(1).build();
        assertThat(TradeScenario.shelfProblem(shelf)).isEmpty();
        assertThat(TradeScenario.shelfProblem(shelf.toBuilder().setServerNowMs(0).setPage(0).build()))
                .contains("server_now_ms=0", "page=0");
    }

    @Test
    void 应答形状_成功不设error_message_拒绝只带error_message_收藏另带listing_id_tip不带parameters() {
        assertThat(TradeScenario.tipProblem(BrowseListingsResponse.newBuilder().setServerNowMs(1).build(), 0, Set.of())).isEmpty();
        assertThat(TradeScenario.tipProblem(BrowseListingsResponse.newBuilder().setErrorMessage(TipInfoMessage.getDefaultInstance())
                .build(), 0, Set.of())).contains("成功应答设了 error_message");
        BrowseListingsResponse rejected = BrowseListingsResponse.newBuilder().setErrorMessage(tip(TIP_FEATURE_DISABLED)).build();
        assertThat(TradeScenario.tipProblem(rejected, TIP_FEATURE_DISABLED, Set.of())).isEmpty();
        assertThat(TradeScenario.tipProblem(rejected.toBuilder().setServerNowMs(1).setMarketScope(MarketScope.MARKET_SCOPE_ZONE).build(),
                TIP_FEATURE_DISABLED, Set.of())).contains("[market_scope, server_now_ms]");
        assertThat(TradeScenario.tipProblem(rejected, TIP_INVALID_PARAMETER, Set.of())).contains("期望 tip=" + TIP_INVALID_PARAMETER);
        BrowseListingsResponse withParams = BrowseListingsResponse.newBuilder()
                .setErrorMessage(tip(TIP_FEATURE_DISABLED).toBuilder().addParameters("auction")).build();
        assertThat(TradeScenario.tipProblem(withParams, TIP_FEATURE_DISABLED, Set.of())).contains("parameters");

        SetFavoriteResponse favRejected = SetFavoriteResponse.newBuilder().setErrorMessage(tip(TIP_NOT_FOUND)).setListingId(BIG).build();
        assertThat(TradeScenario.tipProblem(favRejected, TIP_NOT_FOUND, Set.of("listing_id"))).isEmpty();
        assertThat(TradeScenario.tipProblem(favRejected, TIP_NOT_FOUND, Set.of())).contains("[listing_id]");
        assertThat(TradeScenario.tipProblem(favRejected.toBuilder().setFavorite(true).build(), TIP_NOT_FOUND, Set.of("listing_id")))
                .contains("[favorite]");
        assertThat(TradeScenario.tipOf(GetListingDetailResponse.getDefaultInstance())).isZero();
        assertThat(TradeScenario.describeTip(withParams.getErrorMessage())).isEqualTo("tip=" + TIP_FEATURE_DISABLED + " parameters=[auction]");
        assertThat(TradeScenario.hint(TIP_HOME_ZONE_UNKNOWN)).contains("zone_id");
        assertThat(TradeScenario.seedHint(TIP_HOME_ZONE_UNKNOWN)).contains("归属区");
    }

    @Test
    void 每个聚宝斋应答第1个字段都是error_message_199是内部服务的号() {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        List<MessageMethod> jubaozhai = registry.all().stream().filter(m -> m.serviceName().equals("ClientPlayerJubaozhai")).toList();
        assertThat(jubaozhai).extracting(MessageMethod::methodName)
                .containsExactlyInAnyOrder("BrowseListings", "GetListingDetail", "SetFavorite", "GetMyShelf");
        for (MessageMethod method : jubaozhai) {
            assertThat(method.clientService()).as(method.key()).isTrue();
            Descriptors.FieldDescriptor field = method.responsePrototype().getDescriptorForType().findFieldByName("error_message");
            assertThat(field).as(method.key()).isNotNull();
            assertThat(field.getNumber()).as(method.key()).isEqualTo(1);
            assertThat(field.getMessageType()).as(method.key()).isEqualTo(TipInfoMessage.getDescriptor());
        }
        MessageMethod seed = registry.byId(registry.requireId("TradeAdmin", "SeedListing")).orElseThrow();
        assertThat(seed.clientService()).as("199 不是客户端协议服务：gate 丢弃").isFalse();
        assertThat(seed.requestPrototype().getDescriptorForType()).isEqualTo(SeedListingRequest.getDescriptor());
    }

    @Test
    void 摘要比对_逐字段_号按无符号_寄售时长由公示结束推出() {
        SeedListingRequest seed = TradeScenario.seedRequest(7, "SMK-1-A", 0, TradeScenario.SALE_DURATION_MS);
        ListingSummary ok = summary(seed, BIG, ListingPhase.LISTING_PHASE_ON_SALE, false, 1, 1_000L);
        assertThat(TradeScenario.summaryProblem(ok, seed, ListingPhase.LISTING_PHASE_ON_SALE, false, 1)).isEmpty();
        assertThat(TradeScenario.summaryProblem(ok.toBuilder().setTitle("x").build(), seed, ListingPhase.LISTING_PHASE_ON_SALE, false, 1))
                .contains("9223372036854775809", "title=x");
        assertThat(TradeScenario.summaryProblem(ok, seed, ListingPhase.LISTING_PHASE_ENDED, false, 1)).contains("phase=LISTING_PHASE_ON_SALE");
        assertThat(TradeScenario.summaryProblem(ok.toBuilder().setPhaseValue(42).build(), seed, ListingPhase.LISTING_PHASE_ON_SALE,
                false, 1)).as("未知阶段不抛异常").contains("未知(42)");
        assertThat(TradeScenario.summaryProblem(ok, seed, ListingPhase.LISTING_PHASE_ON_SALE, true, 1)).contains("is_mine=false");
        assertThat(TradeScenario.summaryProblem(ok, seed, ListingPhase.LISTING_PHASE_ON_SALE, false, -1)).contains("期望 4294967295");
        assertThat(TradeScenario.summaryProblem(ok.toBuilder().setLevel(61).build(), seed, ListingPhase.LISTING_PHASE_ON_SALE, false, 1))
                .contains("level=61");
        assertThat(TradeScenario.summaryProblem(ok.toBuilder().setIconKey("other").build(), seed, ListingPhase.LISTING_PHASE_ON_SALE,
                false, 1)).contains("icon_key=other");
        assertThat(TradeScenario.summaryProblem(ok.toBuilder().setSaleEndMs(ok.getNoticeEndMs() + 1).build(), seed,
                ListingPhase.LISTING_PHASE_ON_SALE, false, 1)).contains("寄售时长");

        GetListingDetailResponse detail = GetListingDetailResponse.newBuilder().setServerNowMs(5)
                .setDetail(ListingDetail.newBuilder().setSummary(ok).setDescription(seed.getDescription())).build();
        assertThat(TradeScenario.detailProblem(detail, seed, ListingPhase.LISTING_PHASE_ON_SALE, false, 1)).isEmpty();
        assertThat(TradeScenario.detailProblem(detail.toBuilder().setServerNowMs(0).build(), seed, ListingPhase.LISTING_PHASE_ON_SALE,
                false, 1)).contains("server_now_ms=0");
        assertThat(TradeScenario.detailProblem(detail.toBuilder().clearDetail().build(), seed, ListingPhase.LISTING_PHASE_ON_SALE,
                false, 1)).contains("没有 detail.summary");
        assertThat(TradeScenario.detailProblem(detail.toBuilder().setDetail(detail.getDetail().toBuilder().setDescription("别的")).build(),
                seed, ListingPhase.LISTING_PHASE_ON_SALE, false, 1)).contains("description=别的");
    }

    @Test
    void 查找_标题_去重_描述按无符号() {
        SeedListingRequest seed = TradeScenario.seedRequest(7, "SMK-1-A", 0, TradeScenario.SALE_DURATION_MS);
        ListingSummary big = summary(seed, BIG, ListingPhase.LISTING_PHASE_ENDED, true, 1, 1_000L).toBuilder().setIsFavorite(true).build();
        List<ListingSummary> listings = List.of(big);
        assertThat(TradeScenario.find(listings, BIG)).isSameAs(big);
        assertThat(TradeScenario.find(listings, 1)).isNull();
        assertThat(TradeScenario.hasTitle(listings, "SMK-1-A")).isTrue();
        assertThat(TradeScenario.hasTitle(listings, "SMK-1-GATE")).isFalse();
        assertThat(TradeScenario.distinct(1, 2, BIG)).isTrue();
        assertThat(TradeScenario.distinct(1, 2, 1)).isFalse();
        assertThat(TradeScenario.describe(big)).isEqualTo("9223372036854775809:SMK-1-A:LISTING_PHASE_ENDED:mine:fav");
        assertThat(TradeScenario.describe(SetFavoriteResponse.newBuilder().setListingId(-1L).setFavorite(true).build()))
                .isEqualTo("tip=0 listing_id=18446744073709551615 favorite=true");
        assertThat(TradeScenario.describe(BrowseListingsResponse.newBuilder().setTotalCount(-1).setPage(1).setPageCount(-1)
                .setPageSize(20).setMarketScope(MarketScope.MARKET_SCOPE_GLOBAL).addListings(big).build()))
                .contains("scope=global", "total=4294967295", "page=1/4294967295", "9223372036854775809");
        assertThat(TradeScenario.scopeName(MarketScope.MARKET_SCOPE_ZONE_VALUE)).isEqualTo("zone");
        assertThat(TradeScenario.uid(-1L)).isEqualTo("18446744073709551615");
    }

    @Test
    void 只有不带请求号的23_1003才算gate不可达() {
        int sendTip = 23;
        assertThat(TradeScenario.Bot.isUnavailableTip(received(sendTip, 0, tip(TIP_SERVICE_UNAVAILABLE)), sendTip)).isTrue();
        assertThat(TradeScenario.Bot.isUnavailableTip(received(sendTip, 0, tip(1006)), sendTip)).as("23 {1006} 是 GM 闸").isFalse();
        assertThat(TradeScenario.Bot.isUnavailableTip(received(sendTip, 5, tip(TIP_SERVICE_UNAVAILABLE)), sendTip)).isFalse();
        assertThat(TradeScenario.Bot.isUnavailableTip(received(196, 0, tip(TIP_SERVICE_UNAVAILABLE)), sendTip)).isFalse();
        assertThat(TradeScenario.Bot.envelopeHint(TIP_SERVICE_UNAVAILABLE)).contains("xm-trade 不在");
        assertThat(TradeScenario.Bot.envelopeHint(1008)).contains("限频");
    }

    // ================================================================ 夹具

    private static final int TIP_NOT_FOUND = TradeErrorTip.trade_error.kTradeListingNotFound_VALUE;
    private static final int TIP_HOME_ZONE_UNKNOWN = TradeErrorTip.trade_error.kTradeHomeZoneUnknown_VALUE;
    private static final int TIP_FEATURE_DISABLED = TradeErrorTip.trade_error.kTradeFeatureDisabled_VALUE;
    private static final int TIP_INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int TIP_SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }

    /** 整个 ClientRequest 帧的大小（消息号、请求号取最大值）。 */
    private static int frameSize(int bodyBytes) {
        return ClientRequest.newBuilder().setId(Long.MAX_VALUE).setMessageId(Integer.MAX_VALUE)
                .setBody(com.google.protobuf.ByteString.copyFrom(new byte[bodyBytes])).build().getSerializedSize();
    }

    private static ListingSummary summary(SeedListingRequest seed, long id, ListingPhase phase, boolean mine, int zone, long noticeEnd) {
        return ListingSummary.newBuilder().setListingId(id).setCategory(seed.getCategory()).setSubcategory(seed.getSubcategory())
                .setTitle(seed.getTitle()).setLevel(seed.getLevel()).setPriceFen(seed.getPriceFen()).setPhase(phase)
                .setNoticeEndMs(noticeEnd).setSaleEndMs(noticeEnd + seed.getSaleDurationMs()).setMarketZone(zone)
                .setIsMine(mine).setSummary(seed.getSummary()).setIconKey(seed.getIconKey()).build();
    }

    private static Received received(int messageId, long requestId, TipInfoMessage body) {
        return new Received(0, 0, MessageContent.newBuilder().setMessageId(messageId).setId(requestId)
                .setSerializedMessage(body.toByteString()).build());
    }
}
