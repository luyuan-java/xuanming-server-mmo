package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.trade.*;
import com.game.trade.admin.SellerCreditAdminController;
import com.game.trade.admin.TradeAdminAuthFilter;
import com.game.trade.dispatch.TradeMethods;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.rules.TradeTips;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证聚宝斋 481-484 客户端信用/行情/当面限额 RPC、496-497 管理端口内部接口、RMB 定向买家/争议冻结解析与 SQL 参数绑定。
 */
class EconomyCreditEndpointsTest {

    private static final long NOW_MS = 1_760_000_000_000L;

    @Test
    void 卖家信用_参考价与当面限额客户端接口全链路脱敏与校验() throws Exception {
        var credits = new SellerCreditService(
                new SellerCreditService.MemoryStore(),
                id -> id == 7001L
                        ? new SellerCreditService.RoleOwnership("acct-7001", 2L)
                        : null,
                SellerCreditService.Policy.defaults());
        credits.recordSellerEnvironment(new SellerCreditService.EnvironmentEvent(
                7001L, "acct-7001", 2L, "203.0.113.1", "dev-home", NOW_MS - 5000));
        credits.recordViolation(new SellerCreditService.ViolationRecord(
                "CASE-LOGIC-1", "ord-1", 7001L, "acct-7001", 2L, "fraud", NOW_MS - 3000));
        var cleared = credits.recordSellerEnvironment(new SellerCreditService.EnvironmentEvent(
                7001L, "acct-7001", 2L, "198.51.100.77", "dev-stranger", NOW_MS - 1000));
        assertThat(cleared.cleared()).isTrue();

        var policy = new PlayerTradeGuard.Policy(
                PlayerTradeGuard.MODE_STRICT_CONSUMABLES,
                Set.of(502, 501),
                10_000L,
                CurrencyType.CURRENCY_TYPE_GOLD_VALUE,
                0L, 0, 0L, false);
        var refReader = (ReferencePrice.Reader) (item, currency, now) ->
                new ReferencePrice.View(item, currency, 24_800L, 26, false);
        var guard = new PlayerTradeGuard(policy, null, refReader, (player, currency, dayStart) -> 2_500L);

        var service = new CommerceClientService(
                null, null, null, null, RmbConsignmentService.TextGuard.ALLOW,
                credits, refReader, guard, () -> NOW_MS);
        var session = SessionContext.newBuilder().setPlayerId(8001L).build();
        var deadline = Deadline.after(3000);

        // 1) GetSellerCredit：命中异地换机清 0 且保留违约，返回受限档且完全脱敏
        SellerCreditResponse creditResp = (SellerCreditResponse) service.handle(
                TradeMethods.GET_SELLER_CREDIT, session,
                GetSellerCreditRequest.newBuilder().setSellerPlayerId(7001L).build(),
                SellerCreditResponse.getDefaultInstance(), deadline);
        assertThat(creditResp.hasErrorMessage()).isFalse();
        assertThat(creditResp.getServerNowMs()).isEqualTo(NOW_MS);
        assertThat(creditResp.getProfile().getEnvCreditCleared()).isTrue();
        assertThat(creditResp.getProfile().getCreditScore()).isZero();
        assertThat(creditResp.getProfile().getStarRatingTenths()).isEqualTo(10);
        assertThat(creditResp.getProfile().getViolationCount()).isEqualTo(1);
        assertThat(creditResp.getProfile().getRiskTier()).isEqualTo("restricted");

        // 2) GetMarketReferencePrice：返回真实近 7 日 p50 与有效样本量；非法参数返回 INVALID
        GetMarketReferencePriceResponse p50Resp = (GetMarketReferencePriceResponse) service.handle(
                TradeMethods.GET_MARKET_REFERENCE_PRICE, session,
                GetMarketReferencePriceRequest.newBuilder()
                        .setItemConfigId(3403)
                        .setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE).build(),
                GetMarketReferencePriceResponse.getDefaultInstance(), deadline);
        assertThat(p50Resp.hasErrorMessage()).isFalse();
        assertThat(p50Resp.getMedianUnitPrice()).isEqualTo(24_800L);
        assertThat(p50Resp.getSampleCount()).isEqualTo(26);
        assertThat(p50Resp.getInsufficientSample()).isFalse();
        assertThat(p50Resp.getWindowDays()).isEqualTo(7);

        GetMarketReferencePriceResponse badP50 = (GetMarketReferencePriceResponse) service.handle(
                TradeMethods.GET_MARKET_REFERENCE_PRICE, session,
                GetMarketReferencePriceRequest.newBuilder().setItemConfigId(0).setCurrencyType(999).build(),
                GetMarketReferencePriceResponse.getDefaultInstance(), deadline);
        assertThat(badP50.getErrorMessage().getId()).isEqualTo(TradeTips.INVALID_PARAMETER);

        // 3) GetPlayerTradeQuota：返回方案 A 与今日剩余额度 7,500 / 10,000（耗材列表按升序确定性输出）
        GetPlayerTradeQuotaResponse quotaResp = (GetPlayerTradeQuotaResponse) service.handle(
                TradeMethods.GET_PLAYER_TRADE_QUOTA, session,
                GetPlayerTradeQuotaRequest.newBuilder().setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE).build(),
                GetPlayerTradeQuotaResponse.getDefaultInstance(), deadline);
        assertThat(quotaResp.hasErrorMessage()).isFalse();
        assertThat(quotaResp.getMode()).isEqualTo(PlayerTradeGuard.MODE_STRICT_CONSUMABLES);
        assertThat(quotaResp.getDailyCurrencyLimit()).isEqualTo(10_000L);
        assertThat(quotaResp.getDailyCurrencyUsed()).isEqualTo(2_500L);
        assertThat(quotaResp.getDailyCurrencyRemaining()).isEqualTo(7_500L);
        assertThat(quotaResp.getConsumableItemIdsList()).containsExactly(501, 502);

        // 4) acquaintance_locked 模式缺省阈值自动回填
        var bGuard = new PlayerTradeGuard(
                new PlayerTradeGuard.Policy(PlayerTradeGuard.MODE_ACQUAINTANCE_LOCKED, Set.of(), 0L, 0, 0L, 0, 0L, true),
                null, null, null);
        var bService = new CommerceClientService(null, null, null, null, null, null, null, bGuard, () -> NOW_MS);
        GetPlayerTradeQuotaResponse bQuota = (GetPlayerTradeQuotaResponse) bService.handle(
                TradeMethods.GET_PLAYER_TRADE_QUOTA, session,
                GetPlayerTradeQuotaRequest.newBuilder().setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE).build(),
                GetPlayerTradeQuotaResponse.getDefaultInstance(), deadline);
        assertThat(bQuota.getMinFriendDurationMs()).isEqualTo(PlayerTradeGuard.DEFAULT_MIN_FRIEND_DURATION_MS);
        assertThat(bQuota.getMaxReferenceDeviationPct()).isEqualTo(PlayerTradeGuard.DEFAULT_MAX_REFERENCE_DEVIATION_PCT);
        assertThat(bQuota.getCoolingLockDurationMs()).isEqualTo(PlayerTradeGuard.DEFAULT_COOLING_LOCK_DURATION_MS);
    }

    @Test
    void 买家评价校验权威订单归属_评语内容安全与幂等重放() {
        var credits = new SellerCreditService(
                new SellerCreditService.MemoryStore(),
                id -> new SellerCreditService.RoleOwnership("acct-seller", 1L),
                SellerCreditService.Policy.defaults());

        TradeCommerceWorkflow wf = TradeCommerceWorkflow.newBuilder()
                .setStage("done")
                .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("acct-seller").setOwnerRevision(1L))
                .setBuyerIdentity(TradeRoleIdentity.newBuilder().setAccount("acct-buyer").setOwnerRevision(1L))
                .build();
        DataSource fakeOrderSource = singleCommerceRowDataSource(
                9001L, 3, CommerceRules.SETTLED, 7001L, 8001L, 50_000L, NOW_MS - 10_000L, wf.toByteArray());
        var engine = new CommerceEngine(
                new CommerceStore(fakeOrderSource),
                new AtomicLong(100)::incrementAndGet,
                new CommerceRules.Policy(Set.of(), Set.of(), Set.of(0), false, List.of()),
                () -> NOW_MS)
                .withSellerCreditService(credits);

        RmbConsignmentService.TextGuard guard = (player, account, text, d) ->
                text.contains("违禁词") ? CommerceRules.TITLE_SENSITIVE : 0;
        var service = new CommerceClientService(engine, null, null, null, guard);
        var buyerSession = SessionContext.newBuilder().setPlayerId(8001L).setAccount("acct-buyer").build();
        var otherSession = SessionContext.newBuilder().setPlayerId(8002L).setAccount("acct-other").build();
        var deadline = Deadline.after(3000);

        // 敏感评语先被内容安全拒绝
        SubmitSellerReviewResponse sensitive = (SubmitSellerReviewResponse) service.handle(
                TradeMethods.SUBMIT_SELLER_REVIEW, buyerSession,
                SubmitSellerReviewRequest.newBuilder()
                        .setOrderId("commerce:9001")
                        .setRating(SellerReviewRating.SELLER_REVIEW_RATING_POSITIVE)
                        .setComment("这里有违禁词").build(),
                SubmitSellerReviewResponse.getDefaultInstance(), deadline);
        assertThat(sensitive.getErrorMessage().getId()).isEqualTo(TradeTips.TITLE_SENSITIVE);

        // 非买家越权评价拒绝 ORDER_NOT_FOUND
        SubmitSellerReviewResponse stranger = (SubmitSellerReviewResponse) service.handle(
                TradeMethods.SUBMIT_SELLER_REVIEW, otherSession,
                SubmitSellerReviewRequest.newBuilder()
                        .setOrderId("9001")
                        .setRating(SellerReviewRating.SELLER_REVIEW_RATING_POSITIVE).build(),
                SubmitSellerReviewResponse.getDefaultInstance(), deadline);
        assertThat(stranger.getErrorMessage().getId()).isEqualTo(TradeTips.ORDER_NOT_FOUND);

        // 正常好评计分并入账；同评重放返回 replayed=true；改评返回 REQUEST_CONFLICT
        SubmitSellerReviewResponse first = (SubmitSellerReviewResponse) service.handle(
                TradeMethods.SUBMIT_SELLER_REVIEW, buyerSession,
                SubmitSellerReviewRequest.newBuilder()
                        .setOrderId("commerce:9001")
                        .setRating(SellerReviewRating.SELLER_REVIEW_RATING_POSITIVE)
                        .setComment("  发货很快  ").build(),
                SubmitSellerReviewResponse.getDefaultInstance(), deadline);
        assertThat(first.hasErrorMessage()).isFalse();
        assertThat(first.getCountedForScore()).isTrue();
        assertThat(first.getReplayed()).isFalse();
        assertThat(first.getProfile().getPositiveReviews()).isEqualTo(1);
        assertThat(first.getProfile().getCreditScore()).isEqualTo(62);

        SubmitSellerReviewResponse replay = (SubmitSellerReviewResponse) service.handle(
                TradeMethods.SUBMIT_SELLER_REVIEW, buyerSession,
                SubmitSellerReviewRequest.newBuilder()
                        .setOrderId("9001")
                        .setRating(SellerReviewRating.SELLER_REVIEW_RATING_POSITIVE).build(),
                SubmitSellerReviewResponse.getDefaultInstance(), deadline);
        assertThat(replay.hasErrorMessage()).isFalse();
        assertThat(replay.getReplayed()).isTrue();

        SubmitSellerReviewResponse conflict = (SubmitSellerReviewResponse) service.handle(
                TradeMethods.SUBMIT_SELLER_REVIEW, buyerSession,
                SubmitSellerReviewRequest.newBuilder()
                        .setOrderId("commerce:9001")
                        .setRating(SellerReviewRating.SELLER_REVIEW_RATING_NEGATIVE).build(),
                SubmitSellerReviewResponse.getDefaultInstance(), deadline);
        assertThat(conflict.getErrorMessage().getId()).isEqualTo(TradeTips.REQUEST_CONFLICT);
    }

    @Test
    void 内部管理端口496与497鉴权_高铁换IP不清零_异地换机清零但保留违约() throws Exception {
        var credits = new SellerCreditService(
                new SellerCreditService.MemoryStore(),
                id -> new SellerCreditService.RoleOwnership("acct-7010", 1L),
                SellerCreditService.Policy.defaults());
        var controller = new SellerCreditAdminController(credits, () -> NOW_MS);
        var filter = new TradeAdminAuthFilter("admin-secret", new TradeMetrics(new SimpleMeterRegistry()));

        // 1) 未过鉴权直调拒绝 401
        MockHttpServletRequest unauthed = new MockHttpServletRequest("POST", SellerCreditAdminController.ENVIRONMENT_PATH);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.recordSellerEnvironment(unauthed))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        // 2) 记录常用设备 + 高铁仅换 IP 不清零
        byte[] r1Bytes = postAdmin(filter, SellerCreditAdminController.ENVIRONMENT_PATH,
                RecordSellerEnvironmentRequest.newBuilder()
                        .setSellerPlayerId(7010L).setOwnerAccount("acct-7010").setOwnerRevision(1L)
                        .setClientIp("203.0.113.5").setDeviceId("phone-a").build().toByteArray(),
                controller::recordSellerEnvironment);
        RecordSellerEnvironmentResponse r1 = RecordSellerEnvironmentResponse.parseFrom(r1Bytes);
        assertThat(r1.hasErrorMessage()).isFalse();
        assertThat(r1.getEnvCreditCleared()).isFalse();

        byte[] r2Bytes = postAdmin(filter, SellerCreditAdminController.ENVIRONMENT_PATH,
                RecordSellerEnvironmentRequest.newBuilder()
                        .setSellerPlayerId(7010L).setOwnerAccount("acct-7010").setOwnerRevision(1L)
                        .setClientIp("198.51.100.88").setDeviceId("phone-a").build().toByteArray(),
                controller::recordSellerEnvironment);
        assertThat(RecordSellerEnvironmentResponse.parseFrom(r2Bytes).getEnvCreditCleared()).isFalse();

        // 3) 写入核实违约黑历史
        byte[] v1Bytes = postAdmin(filter, SellerCreditAdminController.VIOLATION_PATH,
                RecordSellerViolationRequest.newBuilder()
                        .setCaseId("DISPUTE-9001").setOrderId("commerce:9001")
                        .setSellerPlayerId(7010L).setSellerAccount("acct-7010").setSellerOwnerRevision(1L)
                        .setReason("verified_chargeback").build().toByteArray(),
                controller::recordSellerViolation);
        RecordSellerViolationResponse v1 = RecordSellerViolationResponse.parseFrom(v1Bytes);
        assertThat(v1.hasErrorMessage()).isFalse();
        assertThat(v1.getProfile().getViolationCount()).isEqualTo(1);

        // 4) 异地新 IP + 陌生新设备同时变化 -> 正面信用清 0，但保留 1 次违约黑历史
        byte[] r3Bytes = postAdmin(filter, SellerCreditAdminController.ENVIRONMENT_PATH,
                RecordSellerEnvironmentRequest.newBuilder()
                        .setSellerPlayerId(7010L).setOwnerAccount("acct-7010").setOwnerRevision(1L)
                        .setClientIp("192.0.2.99").setDeviceId("stranger-pc").build().toByteArray(),
                controller::recordSellerEnvironment);
        RecordSellerEnvironmentResponse r3 = RecordSellerEnvironmentResponse.parseFrom(r3Bytes);
        assertThat(r3.getEnvCreditCleared()).isTrue();
        assertThat(r3.getProfile().getCreditScore()).isZero();
        assertThat(r3.getProfile().getViolationCount()).isEqualTo(1);
    }

    @Test
    void RMB寄售收入争议冻结字段与定向买家标记准确映射到协议() throws Exception {
        var json = new ObjectMapper();
        var income = RmbConsignmentClient.income(json.readTree(
                "{\"orderId\":\"ord-1\",\"grossFen\":10000,\"feeFen\":500,\"netFen\":9500,"
                        + "\"reservedFen\":1000,\"paidFen\":2000,\"forfeitedFen\":0,"
                        + "\"disputeFrozenFen\":3000,\"disputeCaseId\":\"CASE-DISP-9\",\"availableMs\":1760000000000}"));
        assertThat(income.getDisputeFrozenFen()).isEqualTo(3000L);
        assertThat(income.getDisputeCaseId()).isEqualTo("CASE-DISP-9");

        var designatedListing = RmbConsignmentClient.listing(json.readTree(
                "{\"listingId\":\"101\",\"sellerPlayerId\":\"7001\",\"marketZone\":1,\"assetKind\":\"ITEMS\","
                        + "\"title\":\"龙鳞\",\"priceFen\":8800,\"feeBps\":500,\"coolingMs\":0,"
                        + "\"noticeEndMs\":1000,\"saleEndMs\":9000,\"state\":\"LISTED\",\"phase\":\"ON_SALE\","
                        + "\"orderId\":null,\"designatedBuyerAccount\":\"acct-buyer\"}"));
        assertThat(designatedListing.getDesignatedOnly()).isTrue();

        var publicListing = RmbConsignmentClient.listing(json.readTree(
                "{\"listingId\":\"102\",\"sellerPlayerId\":\"7001\",\"marketZone\":1,\"assetKind\":\"ITEMS\","
                        + "\"title\":\"玄铁\",\"priceFen\":8800,\"feeBps\":500,\"coolingMs\":0,"
                        + "\"noticeEndMs\":1000,\"saleEndMs\":9000,\"state\":\"LISTED\",\"phase\":\"ON_SALE\","
                        + "\"orderId\":null,\"designatedBuyerAccount\":null}"));
        assertThat(publicListing.getDesignatedOnly()).isFalse();
    }

    @Test
    void SqlStore执行参数化SQL并正确绑定无符号ID与评语() throws Exception {
        List<String> executedSql = new ArrayList<>();
        List<List<Object>> boundArgs = new ArrayList<>();
        DataSource recording = recordingWriteDataSource(executedSql, boundArgs);
        var sqlStore = new SellerCreditService.SqlStore(recording, new AtomicLong(500)::incrementAndGet, () -> NOW_MS, new ObjectMapper());

        boolean marked = sqlStore.markOrderCompleted("commerce:9001", new SellerCreditService.CreditKey(7001L, 2L));
        assertThat(marked).isTrue();
        sqlStore.saveReview(new SellerCreditService.ReviewRecord(
                "commerce:9001", 7001L, 2L, 8001L, "acct-buyer", "1.2.3.4", "dev-1",
                SellerCreditService.Rating.POSITIVE, true, "靠谱卖家", NOW_MS));
        sqlStore.saveViolation(new SellerCreditService.ViolationRecord(
                "CASE-1", "commerce:9001", 7001L, "acct-seller", 2L, "fraud", NOW_MS));

        assertThat(executedSql).hasSize(3);
        assertThat(executedSql.get(0)).contains("INSERT IGNORE INTO trade_seller_completed_order");
        assertThat(executedSql.get(1)).contains("INSERT INTO trade_seller_review").contains("comment");
        assertThat(boundArgs.get(1)).contains("靠谱卖家");
        assertThat(executedSql.get(2)).contains("INSERT INTO trade_seller_violation");
    }

    @FunctionalInterface
    private interface ControllerEndpoint {
        org.springframework.http.ResponseEntity<byte[]> invoke(jakarta.servlet.http.HttpServletRequest request) throws Exception;
    }

    private static byte[] postAdmin(TradeAdminAuthFilter filter, String path, byte[] body, ControllerEndpoint endpoint) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setServletPath(path);
        req.setContent(body);
        req.addHeader(TradeAdminAuthFilter.TOKEN_HEADER, "admin-secret");
        req.addHeader(TradeAdminAuthFilter.OPERATOR_HEADER, "ops-risk");
        MockHttpServletResponse res = new MockHttpServletResponse();
        final byte[][] out = new byte[1][];
        filter.doFilter(req, res, (r, w) -> {
            try {
                var entity = endpoint.invoke((jakarta.servlet.http.HttpServletRequest) r);
                assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
                out[0] = entity.getBody();
                ((HttpServletResponse) w).setStatus(entity.getStatusCode().value());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        return out[0];
    }

    private static DataSource singleCommerceRowDataSource(
            long commerceId, int kind, int status, long sellerId, long buyerId,
            long unitPrice, long updatedMs, byte[] workflowProto) {
        Map<String, Object> row = Map.ofEntries(
                Map.entry("commerce_id", Long.toUnsignedString(commerceId)),
                Map.entry("request_key", "req-1"),
                Map.entry("request_digest", new byte[0]),
                Map.entry("kind", Integer.toUnsignedString(kind)),
                Map.entry("status", Integer.toUnsignedString(status)),
                Map.entry("seller_player_id", Long.toUnsignedString(sellerId)),
                Map.entry("buyer_player_id", Long.toUnsignedString(buyerId)),
                Map.entry("market_id", "0"),
                Map.entry("item_config_id", "0"),
                Map.entry("side", "0"),
                Map.entry("unit_price", Long.toUnsignedString(unitPrice)),
                Map.entry("quantity", "1"),
                Map.entry("filled_quantity", "0"),
                Map.entry("currency_type", "0"),
                Map.entry("expires_ms", "0"),
                Map.entry("created_ms", Long.toUnsignedString(updatedMs)),
                Map.entry("updated_ms", Long.toUnsignedString(updatedMs)),
                Map.entry("version", "2"),
                Map.entry("workflow_proto", workflowProto),
                Map.entry("parent_id", "0"),
                Map.entry("counterparty_id", "0"));
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) {
                        return (Connection) Proxy.newProxyInstance(
                                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
                                    if ("prepareStatement".equals(cm.getName())) {
                                        final long[] requested = new long[1];
                                        return (PreparedStatement) Proxy.newProxyInstance(
                                                PreparedStatement.class.getClassLoader(),
                                                new Class<?>[] {PreparedStatement.class}, (sp, sm, sa) -> {
                                                    if ("setObject".equals(sm.getName()) && sa[1] instanceof Number n) {
                                                        requested[0] = n.longValue();
                                                    } else if ("executeQuery".equals(sm.getName())) {
                                                        boolean[] hasRow = {requested[0] == commerceId};
                                                        return (ResultSet) Proxy.newProxyInstance(
                                                                ResultSet.class.getClassLoader(),
                                                                new Class<?>[] {ResultSet.class}, (rp, rm, ra) -> switch (rm.getName()) {
                                                                    case "next" -> {
                                                                        boolean r = hasRow[0];
                                                                        hasRow[0] = false;
                                                                        yield r;
                                                                    }
                                                                    case "getString" -> String.valueOf(row.get(ra[0]));
                                                                    case "getBytes" -> (byte[]) row.get(ra[0]);
                                                                    case "getInt" -> Integer.parseInt(String.valueOf(row.get(ra[0])));
                                                                    case "getLong" -> Long.parseLong(String.valueOf(row.get(ra[0])));
                                                                    case "getBoolean" -> Boolean.parseBoolean(String.valueOf(row.get(ra[0])));
                                                                    default -> null;
                                                                });
                                                    }
                                                    return null;
                                                });
                                    }
                                    return null;
                                });
                    }
                    return null;
                });
    }

    private static DataSource recordingWriteDataSource(List<String> sqls, List<List<Object>> allArgs) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (p, m, a) -> {
                    if ("getConnection".equals(m.getName())) {
                        return (Connection) Proxy.newProxyInstance(
                                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
                                    if ("prepareStatement".equals(cm.getName())) {
                                        sqls.add((String) ca[0]);
                                        List<Object> args = new ArrayList<>();
                                        allArgs.add(args);
                                        return (PreparedStatement) Proxy.newProxyInstance(
                                                PreparedStatement.class.getClassLoader(),
                                                new Class<?>[] {PreparedStatement.class}, (sp, sm, sa) -> {
                                                    if (sm.getName().startsWith("set") && sa != null && sa.length >= 2) {
                                                        args.add(sa[1]);
                                                    } else if ("executeUpdate".equals(sm.getName())) {
                                                        return 1;
                                                    }
                                                    return null;
                                                });
                                    }
                                    return null;
                                });
                    }
                    return null;
                });
    }
}
