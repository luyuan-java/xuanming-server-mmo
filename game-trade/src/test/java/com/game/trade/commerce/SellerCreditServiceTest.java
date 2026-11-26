package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.*;

import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeRmbConsignmentWorkflow;
import com.game.proto.trade.TradeRoleIdentity;
import com.game.trade.commerce.SellerCreditService.*;
import com.game.trade.store.pb.TradeCommerceRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SellerCreditServiceTest {

    @Test
    void 卖家信用正常累积且角色过户后彻底清空并隔离迟到评价() throws Exception {
        long baseNow = 1_760_000_000_000L;
        Map<Long, RoleOwnership> owners = new HashMap<>();
        owners.put(1001L, new RoleOwnership("old-owner-acct", 1L));
        var svc = new SellerCreditService(
                new MemoryStore(),
                owners::get,
                new Policy(0L, 0L, 0, 100L, 0L));

        // 1) 原号主通过 20 名不同买家的真实托管成交累积成为五星高信誉老店（TRUSTED）
        for (int i = 1; i <= 20; i++) {
            var fact = new CompletedTradeFact(
                    String.format("order-old-%02d", i),
                    1001L,
                    "old-owner-acct",
                    1L,
                    2000L + i,
                    String.format("buyer-acct-%02d", i),
                    500L,
                    baseNow + i * 1000L);
            var sub = svc.submitReview(new ReviewRequest(fact, Rating.POSITIVE), fact.settledAtMs() + 500L);
            assertThat(sub.replayed()).isFalse();
            assertThat(sub.review().countedForScore()).isTrue();
        }
        var oldProfile = svc.getProfile(1001L, baseNow + 30_000L);
        assertThat(oldProfile.completedOrders()).isEqualTo(20L);
        assertThat(oldProfile.positiveReviews()).isEqualTo(20L);
        assertThat(oldProfile.creditScore()).isEqualTo(100);
        assertThat(oldProfile.starRatingTenths()).isEqualTo(50);
        assertThat(oldProfile.positiveRateBps()).isEqualTo(10_000);
        assertThat(oldProfile.riskTier()).isEqualTo(RiskTier.TRUSTED);
        assertThat(oldProfile.ownershipChanged()).isFalse();

        // 2) 角色过户给新号主（owner_revision: 1 -> 2, account 变更）：强制清空全部历史评分与成交
        long transferTime = baseNow + 100_000L;
        owners.put(1001L, new RoleOwnership("new-owner-acct", 2L));
        svc.onCharacterTransferred(1001L, "new-owner-acct", 2L, transferTime);

        var newProfile = svc.getProfile(1001L, transferTime + 1_000L);
        assertThat(newProfile.ownerAccount()).isEqualTo("new-owner-acct");
        assertThat(newProfile.ownerRevision()).isEqualTo(2L);
        assertThat(newProfile.ownershipChanged()).isTrue();
        assertThat(newProfile.ownershipChangedAtMs()).isEqualTo(transferTime);
        assertThat(newProfile.completedOrders()).isZero();
        assertThat(newProfile.positiveReviews()).isZero();
        assertThat(newProfile.positiveRateBps()).isZero();
        assertThat(newProfile.creditScore()).isEqualTo(SellerCreditService.DEFAULT_INITIAL_SCORE);
        assertThat(newProfile.starRatingTenths()).isEqualTo(SellerCreditService.DEFAULT_INITIAL_STAR_TENTHS);
        assertThat(newProfile.riskTier()).isEqualTo(RiskTier.NEW_OR_TRANSFERRED);

        // 3) 过户前旧交易的迟到好评只落旧 revision=1，绝不污染新号主（revision=2）
        var lateOldOrder = new CompletedTradeFact(
                "order-old-late-99", 1001L, "old-owner-acct", 1L, 2999L, "buyer-late-99", 500L, transferTime - 1_000L);
        svc.submitReview(new ReviewRequest(lateOldOrder, Rating.POSITIVE), transferTime + 2_000L);
        var stillClean = svc.getProfile(1001L, transferTime + 3_000L);
        assertThat(stillClean.completedOrders()).isZero();
        assertThat(stillClean.positiveReviews()).isZero();
        assertThat(stillClean.creditScore()).isEqualTo(SellerCreditService.DEFAULT_INITIAL_SCORE);
    }

    @Test
    void 拦截同账号自评与小额重复刷分并在违约后降级且外部过户自动懒重算() throws Exception {
        long now = 1_760_000_000_000L;
        Map<Long, RoleOwnership> owners = new HashMap<>();
        owners.put(3001L, new RoleOwnership("seller-acct", 1L));
        var svc = new SellerCreditService(
                new MemoryStore(),
                owners::get,
                new Policy(
                        SellerCreditService.DEFAULT_REVIEW_WINDOW_MS,
                        SellerCreditService.DEFAULT_PAIR_SCORE_WINDOW_MS,
                        1,
                        100L,
                        0L));

        // 1) 同账号自买自评直接拒绝 SELF
        assertRefusal(
                CommerceRules.SELF,
                () -> svc.submitReview(
                        new ReviewRequest(
                                new CompletedTradeFact(
                                        "self-order", 3001L, "seller-acct", 1L, 3002L, "seller-acct", 1000L, now),
                                Rating.POSITIVE),
                        now + 100L));

        // 2) 1 银两小额低于门槛：留痕但不涨分
        var lowSub = svc.submitReview(
                new ReviewRequest(
                        new CompletedTradeFact("tiny-order", 3001L, "seller-acct", 1L, 4001L, "buyer-a", 1L, now),
                        Rating.POSITIVE),
                now + 100L);
        assertThat(lowSub.review().countedForScore()).isFalse();
        assertThat(lowSub.profile().positiveReviews()).isZero();
        assertThat(lowSub.profile().creditScore()).isEqualTo(SellerCreditService.DEFAULT_INITIAL_SCORE);

        // 3) 同买家 7 天内第 1 笔计分、重放幂等、改评冲突、第 2 笔不计分
        var firstTrade = new CompletedTradeFact("pair-order-1", 3001L, "seller-acct", 1L, 4002L, "buyer-b", 500L, now);
        var sub1 = svc.submitReview(new ReviewRequest(firstTrade, Rating.POSITIVE), now + 200L);
        assertThat(sub1.replayed()).isFalse();
        assertThat(sub1.review().countedForScore()).isTrue();
        assertThat(sub1.profile().creditScore()).isEqualTo(62);

        var replaySub = svc.submitReview(new ReviewRequest(firstTrade, Rating.POSITIVE), now + 300L);
        assertThat(replaySub.replayed()).isTrue();
        assertRefusal(
                CommerceRules.REQUEST_CONFLICT,
                () -> svc.submitReview(new ReviewRequest(firstTrade, Rating.NEGATIVE), now + 300L));

        var secondTrade = new CompletedTradeFact("pair-order-2", 3001L, "seller-acct", 1L, 4002L, "buyer-b", 500L, now);
        var sub2 = svc.submitReview(new ReviewRequest(secondTrade, Rating.POSITIVE), now + 400L);
        assertThat(sub2.review().countedForScore()).isFalse();
        assertThat(sub2.profile().creditScore()).isEqualTo(62);

        // 4) 核实违约/拒付按 CaseID 幂等扣重分并立降为 RESTRICTED
        var viol = new ViolationRecord(
                "CASE-FRAUD-01", "pair-order-1", 3001L, "seller-acct", 1L, "confirmed chargeback fraud", now + 500L);
        var profViol = svc.recordViolation(viol);
        assertThat(profViol.violationCount()).isEqualTo(1L);
        assertThat(profViol.creditScore()).isEqualTo(37);
        assertThat(profViol.riskTier()).isEqualTo(RiskTier.RESTRICTED);
        var profViolReplay = svc.recordViolation(new ViolationRecord(
                "CASE-FRAUD-01", "pair-order-1", 3001L, "seller-acct", 1L, "confirmed chargeback fraud", now + 600L));
        assertThat(profViolReplay.creditScore()).isEqualTo(37);

        // 5) 未经回调的外部直接过户：下次 getProfile 自动按新 owner_revision 懒重算清空违约
        owners.put(3001L, new RoleOwnership("brand-new-owner", 2L));
        var lazyReset = svc.getProfile(3001L, now + 10_000L);
        assertThat(lazyReset.ownershipChanged()).isTrue();
        assertThat(lazyReset.violationCount()).isZero();
        assertThat(lazyReset.completedOrders()).isZero();
        assertThat(lazyReset.creditScore()).isEqualTo(SellerCreditService.DEFAULT_INITIAL_SCORE);
        assertThat(lazyReset.riskTier()).isEqualTo(RiskTier.NEW_OR_TRANSFERRED);
    }

    @Test
    void 异地陌生新设备触发信用清零且严禁洗白黑历史并拦截同环境刷分() throws Exception {
        long now = 1_760_000_000_000L;
        Map<Long, RoleOwnership> owners = new HashMap<>();
        owners.put(5001L, new RoleOwnership("seller-env", 1L));
        var svc = new SellerCreditService(
                new MemoryStore(),
                owners::get,
                new Policy(
                        SellerCreditService.DEFAULT_REVIEW_WINDOW_MS,
                        SellerCreditService.DEFAULT_PAIR_SCORE_WINDOW_MS,
                        1,
                        100L,
                        5_000L));

        // 1) 首次记录基线 IP + 设备，并攒 2 笔真实好评（60 -> 64 分）
        var u0 = svc.recordSellerEnvironment(
                new EnvironmentEvent(5001L, "seller-env", 1L, "203.0.113.10", "DEV-AAA", now));
        assertThat(u0.cleared()).isFalse();
        assertThat(u0.profile().creditScore()).isEqualTo(60);

        for (int i = 1; i <= 2; i++) {
            svc.submitReview(
                    new ReviewRequest(
                            new CompletedTradeFact(
                                    "init-good-" + i,
                                    5001L,
                                    "seller-env",
                                    1L,
                                    "",
                                    "",
                                    5900L + i,
                                    "init-buyer-" + i,
                                    "192.0.2." + i,
                                    "init-dev-" + i,
                                    500L,
                                    now + 500L),
                            Rating.POSITIVE),
                    now + 1_000L);
        }

        // 2) 同设备跨城换 IP -> 0 影响（保住 64 分）
        var uTrain = svc.recordSellerEnvironment(
                new EnvironmentEvent(5001L, "seller-env", 1L, "198.51.100.22", "dev-aaa", now + 2_000L));
        assertThat(uTrain.cleared()).isFalse();
        assertThat(uTrain.profile().creditScore()).isEqualTo(64);
        assertThat(uTrain.profile().positiveReviews()).isEqualTo(2L);

        // 3) 同 IP 下手机切电脑（新增常用设备 dev-home-pc）-> 0 影响
        var uHomePc = svc.recordSellerEnvironment(
                new EnvironmentEvent(5001L, "seller-env", 1L, "198.51.100.22", "dev-home-pc", now + 3_000L));
        assertThat(uHomePc.cleared()).isFalse();
        assertThat(uHomePc.profile().creditScore()).isEqualTo(64);

        // 4) 切回已登记常用手机并切新 IP -> 0 影响
        var uBack = svc.recordSellerEnvironment(
                new EnvironmentEvent(5001L, "seller-env", 1L, "198.51.100.33", "dev-aaa", now + 4_000L));
        assertThat(uBack.cleared()).isFalse();
        assertThat(uBack.profile().creditScore()).isEqualTo(64);

        // 5) 陌生新设备 dev-ccc + 新 IP 同时变化 -> 正面信用直接清 0（1.0★, RESTRICTED）
        var uWiped = svc.recordSellerEnvironment(
                new EnvironmentEvent(5001L, "seller-env", 1L, "198.51.100.88", "dev-ccc", now + 8_000L));
        assertThat(uWiped.cleared()).isTrue();
        assertThat(uWiped.profile().envCreditCleared()).isTrue();
        assertThat(uWiped.profile().envResetCount()).isEqualTo(1L);
        assertThat(uWiped.profile().creditScore()).isZero();
        assertThat(uWiped.profile().starRatingTenths()).isEqualTo(10);
        assertThat(uWiped.profile().completedOrders()).isZero();
        assertThat(uWiped.profile().positiveReviews()).isZero();
        assertThat(uWiped.profile().riskTier()).isEqualTo(RiskTier.RESTRICTED);
        assertThat(uWiped.profile().knownDevices()).isEqualTo(List.of("dev-ccc"));

        // 6) 冷静期内（2s < 5s）突击刷好评：不涨分
        var coolSub = svc.submitReview(
                new ReviewRequest(
                        new CompletedTradeFact(
                                "env-cool-order-1",
                                5001L,
                                "seller-env",
                                1L,
                                "",
                                "",
                                6001L,
                                "buyer-clean-1",
                                "192.0.2.10",
                                "dev-buyer-1",
                                500L,
                                now + 9_000L),
                        Rating.POSITIVE),
                now + 10_000L);
        assertThat(coolSub.review().countedForScore()).isFalse();
        assertThat(coolSub.profile().creditScore()).isZero();

        // 7) 过冷静期后，买卖双方同 IP 或同设备互评：严格拦截不计分
        var sameIp = svc.submitReview(
                new ReviewRequest(
                        new CompletedTradeFact(
                                "same-ip-order",
                                5001L,
                                "seller-env",
                                1L,
                                "",
                                "",
                                6002L,
                                "buyer-same-ip",
                                "198.51.100.88",
                                "dev-buyer-2",
                                500L,
                                now + 14_000L),
                        Rating.POSITIVE),
                now + 15_000L);
        assertThat(sameIp.review().countedForScore()).isFalse();
        assertThat(sameIp.profile().creditScore()).isZero();

        var sameDev = svc.submitReview(
                new ReviewRequest(
                        new CompletedTradeFact(
                                "same-dev-order",
                                5001L,
                                "seller-env",
                                1L,
                                "",
                                "",
                                6003L,
                                "buyer-same-dev",
                                "192.0.2.30",
                                "dev-ccc",
                                500L,
                                now + 14_000L),
                        Rating.POSITIVE),
                now + 15_000L);
        assertThat(sameDev.review().countedForScore()).isFalse();
        assertThat(sameDev.profile().creditScore()).isZero();

        // 8) 真实独立买家第 1 次好评从 0 起步（0 -> 2，绝不回弹到 60）；换小号同设备/同 IP 7 天内不计分
        var firstReal = svc.submitReview(
                new ReviewRequest(
                        new CompletedTradeFact(
                                "real-buyer-1",
                                5001L,
                                "seller-env",
                                1L,
                                "",
                                "",
                                6010L,
                                "alt-acct-1",
                                "192.0.2.50",
                                "emu-phone-1",
                                500L,
                                now + 15_000L),
                        Rating.POSITIVE),
                now + 16_000L);
        assertThat(firstReal.review().countedForScore()).isTrue();
        assertThat(firstReal.profile().creditScore()).isEqualTo(2);

        var altSameDev = svc.submitReview(
                new ReviewRequest(
                        new CompletedTradeFact(
                                "alt-same-dev",
                                5001L,
                                "seller-env",
                                1L,
                                "",
                                "",
                                6011L,
                                "alt-acct-2",
                                "192.0.2.51",
                                "emu-phone-1",
                                500L,
                                now + 15_000L),
                        Rating.POSITIVE),
                now + 16_100L);
        assertThat(altSameDev.review().countedForScore()).isFalse();
        assertThat(altSameDev.profile().creditScore()).isEqualTo(2);

        var altSameIp = svc.submitReview(
                new ReviewRequest(
                        new CompletedTradeFact(
                                "alt-same-ip",
                                5001L,
                                "seller-env",
                                1L,
                                "",
                                "",
                                6012L,
                                "alt-acct-3",
                                "192.0.2.50",
                                "emu-phone-2",
                                500L,
                                now + 15_000L),
                        Rating.POSITIVE),
                now + 16_200L);
        assertThat(altSameIp.review().countedForScore()).isFalse();
        assertThat(altSameIp.profile().creditScore()).isEqualTo(2);

        // 9) 防洗白：背上违约后再异地换机，违约计数严禁清零
        svc.recordViolation(new ViolationRecord(
                "CASE-NO-WASH-1", "real-buyer-1", 5001L, "seller-env", 1L, "fraud", now + 17_000L));
        var tryWash = svc.recordSellerEnvironment(
                new EnvironmentEvent(5001L, "seller-env", 1L, "198.51.100.99", "dev-wash-attempt", now + 18_000L));
        assertThat(tryWash.cleared()).isTrue();
        assertThat(tryWash.profile().violationCount()).isEqualTo(1L);
        assertThat(tryWash.profile().creditScore()).isZero();
        assertThat(tryWash.profile().riskTier()).isEqualTo(RiskTier.RESTRICTED);

        // 10) 官方正规过户换新主人（rev 1 -> 2）：新持有周期干净重置
        var transferred = svc.onCharacterTransferred(5001L, "new-clean-owner", 2L, now + 25_000L);
        assertThat(transferred.envCreditCleared()).isFalse();
        assertThat(transferred.envResetCount()).isZero();
        assertThat(transferred.violationCount()).isZero();
        assertThat(transferred.lastIp()).isEmpty();
        assertThat(transferred.lastDeviceId()).isEmpty();
        assertThat(transferred.knownDevices()).isEmpty();
        assertThat(transferred.creditScore()).isEqualTo(SellerCreditService.DEFAULT_INITIAL_SCORE);
    }

    @Test
    void 结案事实抽取仅计官方托管成交并过滤当面交易角色过户与自成交() {
        long now = 1_760_000_000_000L;
        var idSeller = TradeRoleIdentity.newBuilder().setAccount("acct-seller").setOwnerRevision(2).build();
        var idBuyer = TradeRoleIdentity.newBuilder().setAccount("acct-buyer").setOwnerRevision(1).build();

        // 1) 当面交易（kind=2）不计
        assertThat(SellerCreditService.settledTradeFact(
                        TradeCommerceRow.newBuilder()
                                .setCommerceId(11)
                                .setKind(2)
                                .setStatus(CommerceRules.SETTLED)
                                .setSellerPlayerId(101)
                                .setBuyerPlayerId(202)
                                .build(),
                        TradeCommerceWorkflow.newBuilder()
                                .setSellerIdentity(idSeller)
                                .setBuyerIdentity(idBuyer)
                                .build(),
                        now))
                .isEmpty();

        // 2) 固定价寄售（kind=3）提取完整事实
        var consign = SellerCreditService.settledTradeFact(
                TradeCommerceRow.newBuilder()
                        .setCommerceId(12)
                        .setKind(3)
                        .setStatus(CommerceRules.SETTLED)
                        .setSellerPlayerId(101)
                        .setBuyerPlayerId(202)
                        .setUnitPrice(8800)
                        .build(),
                TradeCommerceWorkflow.newBuilder()
                        .setSellerIdentity(idSeller)
                        .setBuyerIdentity(idBuyer)
                        .build(),
                now);
        assertThat(consign).isPresent();
        assertThat(consign.get().orderId()).isEqualTo("commerce:12");
        assertThat(consign.get().amountMinor()).isEqualTo(8800L);
        assertThat(consign.get().sellerOwnerRevision()).isEqualTo(2L);

        // 3) 市场撮合母单（side!=0）不计，仅子单（side==0）计入
        assertThat(SellerCreditService.settledTradeFact(
                        TradeCommerceRow.newBuilder()
                                .setCommerceId(13)
                                .setKind(4)
                                .setSide(2)
                                .setStatus(CommerceRules.SETTLED)
                                .setSellerPlayerId(101)
                                .setBuyerPlayerId(202)
                                .setUnitPrice(100)
                                .setQuantity(5)
                                .build(),
                        TradeCommerceWorkflow.newBuilder()
                                .setSellerIdentity(idSeller)
                                .setBuyerIdentity(idBuyer)
                                .build(),
                        now))
                .isEmpty();
        var fill = SellerCreditService.settledTradeFact(
                TradeCommerceRow.newBuilder()
                        .setCommerceId(14)
                        .setKind(4)
                        .setSide(0)
                        .setStatus(CommerceRules.SETTLED)
                        .setSellerPlayerId(101)
                        .setBuyerPlayerId(202)
                        .setUnitPrice(100)
                        .setQuantity(5)
                        .build(),
                TradeCommerceWorkflow.newBuilder()
                        .setSellerIdentity(idSeller)
                        .setBuyerIdentity(idBuyer)
                        .build(),
                now);
        assertThat(fill).isPresent();
        assertThat(fill.get().amountMinor()).isEqualTo(500L);

        // 4) 拍卖成交（kind=5）取最高出价者与成交价
        var lot = SellerCreditService.settledTradeFact(
                TradeCommerceRow.newBuilder()
                        .setCommerceId(15)
                        .setKind(5)
                        .setStatus(CommerceRules.SETTLED)
                        .setSellerPlayerId(101)
                        .build(),
                TradeCommerceWorkflow.newBuilder()
                        .setSellerIdentity(idSeller)
                        .setBuyerIdentity(idBuyer)
                        .setHighestBidder(303)
                        .setHighestBid(12000)
                        .build(),
                now);
        assertThat(lot).isPresent();
        assertThat(lot.get().buyerPlayerId()).isEqualTo(303L);
        assertThat(lot.get().amountMinor()).isEqualTo(12000L);

        // 5) 非角色人民币寄售计入且复用 MallOrderId；角色过户不计入
        var rmbItem = SellerCreditService.settledTradeFact(
                TradeCommerceRow.newBuilder()
                        .setCommerceId(16)
                        .setKind(6)
                        .setStatus(CommerceRules.SETTLED)
                        .setSellerPlayerId(101)
                        .setBuyerPlayerId(202)
                        .build(),
                TradeCommerceWorkflow.newBuilder()
                        .setRmb(TradeRmbConsignmentWorkflow.newBuilder()
                                .setSellerAccount("acct-seller")
                                .setSellerOwnerRevision(2)
                                .setBuyerAccount("acct-buyer")
                                .setPriceFen(3500)
                                .setMallOrderId("00112233-4455-6677-8899-aabbccddeeff"))
                        .build(),
                now);
        assertThat(rmbItem).isPresent();
        assertThat(rmbItem.get().orderId()).isEqualTo("00112233-4455-6677-8899-aabbccddeeff");
        assertThat(rmbItem.get().amountMinor()).isEqualTo(3500L);

        assertThat(SellerCreditService.settledTradeFact(
                        TradeCommerceRow.newBuilder()
                                .setCommerceId(17)
                                .setKind(6)
                                .setStatus(CommerceRules.SETTLED)
                                .setSellerPlayerId(101)
                                .setBuyerPlayerId(202)
                                .build(),
                        TradeCommerceWorkflow.newBuilder()
                                .setRmb(TradeRmbConsignmentWorkflow.newBuilder()
                                        .setSellerAccount("acct-seller")
                                        .setSellerOwnerRevision(2)
                                        .setBuyerAccount("acct-buyer")
                                        .setCharacterPlayerId(101)
                                        .setPriceFen(9900)
                                        .setMallOrderId("00112233-4455-6677-8899-aabbccddee99"))
                                .build(),
                        now))
                .isEmpty();

        // 6) 同账号自成交被过滤
        assertThat(SellerCreditService.settledTradeFact(
                        TradeCommerceRow.newBuilder()
                                .setCommerceId(18)
                                .setKind(3)
                                .setStatus(CommerceRules.SETTLED)
                                .setSellerPlayerId(101)
                                .setBuyerPlayerId(102)
                                .setUnitPrice(500)
                                .build(),
                        TradeCommerceWorkflow.newBuilder()
                                .setSellerIdentity(idSeller)
                                .setBuyerIdentity(TradeRoleIdentity.newBuilder().setAccount("acct-seller").setOwnerRevision(1))
                                .build(),
                        now))
                .isEmpty();
    }

    @Test
    void SqlStore单事务行锁防并发覆盖与违约提交原子性() throws Exception {
        long now = 1_760_000_000_000L;
        var connectionsOpened = new java.util.concurrent.atomic.AtomicInteger();
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        var rollbacks = new java.util.concurrent.atomic.AtomicInteger();
        var executedSql = new java.util.ArrayList<String>();
        var autoCommits = new java.util.ArrayList<Boolean>();
        var seenProfileRow = new java.util.concurrent.atomic.AtomicBoolean(false);

        javax.sql.DataSource ds = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(),
                new Class<?>[] {javax.sql.DataSource.class},
                (p, m, a) -> {
                    if (!"getConnection".equals(m.getName())) {
                        return null;
                    }
                    connectionsOpened.incrementAndGet();
                    return java.lang.reflect.Proxy.newProxyInstance(
                            java.sql.Connection.class.getClassLoader(),
                            new Class<?>[] {java.sql.Connection.class},
                            (cp, cm, ca) -> switch (cm.getName()) {
                                case "setAutoCommit" -> {
                                    autoCommits.add((Boolean) ca[0]);
                                    yield null;
                                }
                                case "commit" -> {
                                    commits.incrementAndGet();
                                    yield null;
                                }
                                case "rollback" -> {
                                    rollbacks.incrementAndGet();
                                    yield null;
                                }
                                case "prepareStatement" -> {
                                    String sql = (String) ca[0];
                                    executedSql.add(sql);
                                    yield java.lang.reflect.Proxy.newProxyInstance(
                                            java.sql.PreparedStatement.class.getClassLoader(),
                                            new Class<?>[] {java.sql.PreparedStatement.class},
                                            (sp, sm, sa) -> switch (sm.getName()) {
                                                case "executeUpdate" -> {
                                                    if (sql.startsWith("INSERT IGNORE INTO trade_seller_credit")) {
                                                        seenProfileRow.set(true);
                                                    }
                                                    yield 1;
                                                }
                                                case "executeQuery" -> {
                                                    boolean[] hasRow = {sql.contains("FROM trade_seller_credit WHERE seller_player_id")
                                                            && seenProfileRow.get()};
                                                    yield java.lang.reflect.Proxy.newProxyInstance(
                                                            java.sql.ResultSet.class.getClassLoader(),
                                                            new Class<?>[] {java.sql.ResultSet.class},
                                                            (rp, rm, ra) -> switch (rm.getName()) {
                                                                case "next" -> {
                                                                    boolean r = hasRow[0];
                                                                    hasRow[0] = false;
                                                                    yield r;
                                                                }
                                                                case "getString" -> switch ((Integer) ra[0]) {
                                                                    case 1 -> "9001";
                                                                    case 2 -> "1";
                                                                    case 3 -> "acct-s";
                                                                    case 5, 6, 7, 8, 9, 10, 11, 13, 14 -> "0";
                                                                    case 15, 16 -> "";
                                                                    case 17 -> "[]";
                                                                    case 21 -> "new_or_transferred";
                                                                    default -> "0";
                                                                };
                                                                case "getBoolean" -> false;
                                                                case "getInt" -> switch ((Integer) ra[0]) {
                                                                    case 18 -> 60;
                                                                    case 19 -> 30;
                                                                    default -> 0;
                                                                };
                                                                default -> null;
                                                            });
                                                }
                                                default -> null;
                                            });
                                }
                                default -> null;
                            });
                });

        var idSeq = new java.util.concurrent.atomic.AtomicLong(100);
        var svc = new SellerCreditService(
                new SqlStore(ds, idSeq::incrementAndGet, () -> now, new com.fasterxml.jackson.databind.ObjectMapper()),
                null,
                Policy.defaults());
        var viol = svc.recordViolation(new ViolationRecord(
                "CASE-TX-1", "ord-tx-1", 9001L, "acct-s", 1L, "fraud", now));
        assertThat(viol.violationCount()).isEqualTo(1L);
        assertThat(viol.riskTier()).isEqualTo(RiskTier.RESTRICTED);
        assertThat(connectionsOpened.get()).as("整笔违约读写必须复用同一 JDBC 连接").isEqualTo(1);
        assertThat(autoCommits).containsExactly(false);
        assertThat(commits.get()).isEqualTo(1);
        assertThat(rollbacks.get()).isZero();
        assertThat(executedSql).anyMatch(s -> s.contains("FROM trade_seller_credit") && s.contains("FOR UPDATE"));
        assertThat(executedSql).anyMatch(s -> s.startsWith("INSERT IGNORE INTO trade_seller_credit"));
    }

    private static void assertRefusal(int expectedTip, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(CommerceRules.Refusal.class)
                .extracting(e -> ((CommerceRules.Refusal) e).tip())
                .isEqualTo(expectedTip);
    }
}
