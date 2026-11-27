package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.proto.AssetBundle;
import com.game.proto.AssetItemInstance;
import com.game.proto.AssetPetInstance;
import com.game.proto.AssetSnapshot;
import com.game.proto.CurrencyAmount;
import com.game.proto.ItemGrant;
import com.game.proto.common.base.AdminApproval;
import com.game.proto.common.base.ServiceCallAuth;
import com.game.proto.trade.ManualContractMode;
import com.game.proto.trade.ManualContractVerdict;
import com.game.proto.trade.ResolveManualContractRequest;
import com.game.proto.trade.ResolveManualContractResponse;
import com.game.proto.trade.TradeCommerceLeg;
import com.game.proto.trade.TradeCommerceManualAction;
import com.game.proto.trade.TradeCommerceQuarantine;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeRoleIdentity;
import com.game.trade.admin.ManualContractAdminController;
import com.game.trade.admin.TradeAdminAuthFilter;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 覆盖 2026-11-12 交易转人工四类残余处置（redirect / partial / void-auction / partial-fill market）、二次 refund 防护与管理端口。 */
class ManualContractOpsTest {

    private static TradeCommerceQuarantine quarantine(String stage, int status, String reason) {
        return TradeCommerceQuarantine.newBuilder()
                .setStage(stage).setStatus(status).setReason(reason).setDetail("test").setAtMs(1_000L).build();
    }

    private static ManualContractPlanner.Request baseReq(ManualContractPlanner.Action action) {
        return new ManualContractPlanner.Request(
                100L, 2L, action, "ops-alice", "TICKET-101 verified",
                List.of(), "", 0L, 0L, 0L, "", "");
    }

    @Test
    void redirect改绑核对原账号与他方冲突_未发腿原地改投_失败发放腿补发到目标角色_双变更时保持人工() {
        AssetBundle gold = AssetBundle.newBuilder()
                .addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(1).setAmount(500)).build();
        TradeCommerceWorkflow.Builder wf = TradeCommerceWorkflow.newBuilder()
                .setStage("manual")
                .setQuarantine(quarantine("deliver", DELIVERING, MANUAL_REASON_OWNER_CHANGED))
                .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("acc-s").setOwnerRevision(1).build())
                .setBuyerIdentity(TradeRoleIdentity.newBuilder().setAccount("acc-b").setOwnerRevision(1).build())
                .addLegs(TradeCommerceLeg.newBuilder().setRole("buyer_receive").setPlayerId(200L).setEscrowOwnerId(100L)
                        .setStream(4).setOpId(901L).setOutcome(3).setBundle(gold).build())
                .addLegs(TradeCommerceLeg.newBuilder().setRole("seller_receive").setPlayerId(100L).setEscrowOwnerId(200L)
                        .setStream(4).setOpId(0L).setBundle(gold).build());
        TradeCommerceRow.Builder row = TradeCommerceRow.newBuilder()
                .setCommerceId(100L).setKind(3).setStatus(MANUAL).setVersion(2L)
                .setSellerPlayerId(100L).setBuyerPlayerId(200L);

        // 1. 拒绝改绑到他人账号或合同另一方
        var badTargetReq = new ManualContractPlanner.Request(
                100L, 2L, ManualContractPlanner.Action.REDIRECT, "ops-alice", "TICKET-1",
                List.of(), "buyer", 300L, 0L, 0L, "", "");
        var buyerChanged = List.of(new ManualContractPlanner.PartyRoleChange("buyer", 200L, "deleted"));
        assertThatThrownBy(() -> ManualContractPlanner.planManualAction(
                row.clone(), wf.clone(),
                new ManualContractPlanner.Context(badTargetReq, buyerChanged,
                        ManualContractPlanner.RoleFact.present(300L, "other-acc", 1L, false), null, null, null, null, null)))
                .isInstanceOf(ManualContractPlanner.NotAllowedException.class).hasMessageContaining("不属于原账号");

        var selfOtherReq = new ManualContractPlanner.Request(
                100L, 2L, ManualContractPlanner.Action.REDIRECT, "ops-alice", "TICKET-1",
                List.of(), "buyer", 100L, 0L, 0L, "", "");
        assertThatThrownBy(() -> ManualContractPlanner.planManualAction(
                row.clone(), wf.clone(),
                new ManualContractPlanner.Context(selfOtherReq, buyerChanged,
                        ManualContractPlanner.RoleFact.present(100L, "acc-b", 1L, false), null, null, null, null, null)))
                .isInstanceOf(ManualContractPlanner.NotAllowedException.class).hasMessageContaining("另一方");

        // 2. 合法改绑：失败发放腿 buyer_receive 被 buyer_receive#2 取代且收件人为新角色 201，托管者仍记原 100
        var okReq = new ManualContractPlanner.Request(
                100L, 2L, ManualContractPlanner.Action.REDIRECT, "ops-alice", "TICKET-1",
                List.of(), "buyer", 201L, 0L, 0L, "", "");
        TradeCommerceRow.Builder r1 = row.clone();
        TradeCommerceWorkflow.Builder w1 = wf.clone();
        var res1 = ManualContractPlanner.planManualAction(r1, w1, new ManualContractPlanner.Context(
                okReq, buyerChanged, ManualContractPlanner.RoleFact.present(201L, "acc-b", 3L, false), null, null, null, null, null));
        assertThat(r1.getBuyerPlayerId()).isEqualTo(201L);
        assertThat(w1.getBuyerIdentity().getOwnerRevision()).isEqualTo(3L);
        assertThat(res1.identityBefore.getOwnerRevision()).isEqualTo(1L);
        assertThat(res1.newLegs).containsExactly("buyer_receive#2");
        assertThat(res1.supersededLegs).containsExactly("buyer_receive");
        assertThat(w1.getStage()).isEqualTo("deliver");
        assertThat(r1.getStatus()).isEqualTo(DELIVERING);

        // 3. 双方均变更时：先改绑卖家，未发腿 seller_receive 原地改投到 101，且合同维持 MANUAL 等买家改绑
        var bothChanged = List.of(
                new ManualContractPlanner.PartyRoleChange("seller", 100L, "account_changed"),
                new ManualContractPlanner.PartyRoleChange("buyer", 200L, "deleted"));
        var sellerReq = new ManualContractPlanner.Request(
                100L, 2L, ManualContractPlanner.Action.REDIRECT, "ops-alice", "TICKET-1",
                List.of(), "seller", 101L, 0L, 0L, "", "");
        TradeCommerceRow.Builder r2 = row.clone();
        TradeCommerceWorkflow.Builder w2 = wf.clone();
        var res2 = ManualContractPlanner.planManualAction(r2, w2, new ManualContractPlanner.Context(
                sellerReq, bothChanged, ManualContractPlanner.RoleFact.present(101L, "acc-s", 2L, false), null, null, null, null, null));
        assertThat(res2.retargetedLegs).containsExactly("seller_receive");
        assertThat(w2.getStage()).isEqualTo("manual");
        assertThat(r2.getStatus()).isEqualTo(MANUAL);
    }

    @Test
    void partial按scene应用顺序校验缺口_发放补缺口_托管扣款退实扣并拒退已扣实例或宝宝_须一次给齐全部部分腿() {
        AssetBundle creditBundle = AssetBundle.newBuilder()
                .addItemInstances(AssetItemInstance.newBuilder().setItemUuid(11L).build())
                .setPetInstance(AssetPetInstance.newBuilder().setPetId(22L).build())
                .addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(1).setAmount(100))
                .build();
        // 发放腿缺口不是后缀（缺 instance:11 但不缺 pet/currency）-> 拒绝
        assertThatThrownBy(() -> ManualContractPlanner.creditShortfall(
                creditBundle, ManualContractPlanner.parsePartialMissing("instance:11")))
                .isInstanceOf(ManualContractPlanner.NotAllowedException.class).hasMessageContaining("发放顺序");
        // 发放腿合法后缀缺口（pet + currency:1）-> 只补缺口
        AssetBundle shortfall = ManualContractPlanner.creditShortfall(
                creditBundle, ManualContractPlanner.parsePartialMissing("pet,currency:1"));
        assertThat(shortfall.getItemInstancesCount()).isZero();
        assertThat(shortfall.hasPetInstance()).isTrue();
        assertThat(shortfall.getCurrenciesCount()).isEqualTo(1);

        // 托管扣款腿：含可堆叠物品 + 货币1 + 货币2，缺 currency:2 -> 退物品与货币1
        AssetBundle debitBundle = AssetBundle.newBuilder()
                .addItems(ItemGrant.newBuilder().setConfigId(10).setCount(5))
                .addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(1).setAmount(50))
                .addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(2).setAmount(80))
                .build();
        var split = ManualContractPlanner.debitTaken(debitBundle, ManualContractPlanner.parsePartialMissing("currency:2"));
        assertThat(split.taken().getItemsCount()).isEqualTo(1);
        assertThat(split.taken().getCurrenciesList()).extracting(CurrencyAmount::getCurrencyType).containsExactly(1);
        assertThat(split.missing().getCurrenciesList()).extracting(CurrencyAmount::getCurrencyType).containsExactly(2);

        // 托管扣款腿若已扣到实例或宝宝 -> 拒绝自动退回（无 escrow_snapshot）
        AssetBundle debitWithPet = AssetBundle.newBuilder()
                .addItemUuids(99L).setPetId(77L)
                .addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(1).setAmount(10))
                .build();
        assertThatThrownBy(() -> ManualContractPlanner.debitTaken(
                debitWithPet, ManualContractPlanner.parsePartialMissing("currency:1")))
                .isInstanceOf(ManualContractPlanner.NotAllowedException.class).hasMessageContaining("实例或宝宝");

        // 存在两条部分发放腿时只给一条 -> 拒绝
        TradeCommerceWorkflow.Builder wf = TradeCommerceWorkflow.newBuilder()
                .setStage("manual")
                .setQuarantine(quarantine("deliver", DELIVERING, MANUAL_REASON_APPLIED_PARTIAL))
                .addLegs(TradeCommerceLeg.newBuilder().setRole("buyer_receive").setPlayerId(200L).setStream(4)
                        .setOpId(1L).setOutcome(OUTCOME_PARTIAL).setBundle(creditBundle).build())
                .addLegs(TradeCommerceLeg.newBuilder().setRole("seller_receive").setPlayerId(100L).setStream(4)
                        .setOpId(2L).setOutcome(OUTCOME_PARTIAL).setBundle(creditBundle).build());
        TradeCommerceRow.Builder row = TradeCommerceRow.newBuilder().setCommerceId(100L).setKind(3).setStatus(MANUAL).setVersion(2L);
        var onlyOne = new ManualContractPlanner.Request(
                100L, 2L, ManualContractPlanner.Action.PARTIAL, "ops-alice", "TICKET-2",
                List.of(ManualContractPlanner.parsePartialPart("buyer_receive=pet,currency:1")), "", 0L, 0L, 0L, "", "");
        assertThatThrownBy(() -> ManualContractPlanner.planManualAction(
                row, wf, new ManualContractPlanner.Context(onlyOne, List.of(), null, null, null, null, null, null)))
                .isInstanceOf(ManualContractPlanner.NotAllowedException.class).hasMessageContaining("一起给出缺口");
    }

    @Test
    void voidAuction同事务退拍品与联动出价_独立未引用出价允许单独refund_禁止refund跑两次() {
        AssetBundle itemBundle = AssetBundle.newBuilder()
                .addItems(ItemGrant.newBuilder().setConfigId(10).setCount(1)).build();
        AssetBundle bidGold = AssetBundle.newBuilder()
                .addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(1).setAmount(900)).build();

        // 1. void-auction：拍品退卖家，联动出价进 bid_refund / CANCEL_PENDING
        TradeCommerceRow.Builder lot = TradeCommerceRow.newBuilder()
                .setCommerceId(500L).setKind(5).setStatus(MANUAL).setVersion(4L).setSellerPlayerId(10L).setBuyerPlayerId(20L);
        TradeCommerceWorkflow.Builder lotW = TradeCommerceWorkflow.newBuilder()
                .setStage("manual")
                .setQuarantine(quarantine("deliver", DELIVERING, MANUAL_REASON_FINAL_CREDIT))
                .setHighestBidder(20L).setWinningBidId(501L)
                .addLegs(TradeCommerceLeg.newBuilder().setRole("seller_debit").setPlayerId(10L).setStream(3)
                        .setOpId(10L).setOutcome(2).setBundle(itemBundle).setSnapshot(AssetSnapshot.getDefaultInstance()).build())
                .addLegs(TradeCommerceLeg.newBuilder().setRole("buyer_receive").setPlayerId(20L).setStream(4)
                        .setOpId(11L).setOutcome(3).setBundle(itemBundle).build());
        TradeCommerceRow.Builder bid = TradeCommerceRow.newBuilder()
                .setCommerceId(501L).setKind(BID_KIND).setParentId(500L).setStatus(WAITING).setVersion(3L).setSellerPlayerId(20L);
        TradeCommerceWorkflow.Builder bidW = TradeCommerceWorkflow.newBuilder()
                .setStage("bid_held")
                .addLegs(TradeCommerceLeg.newBuilder().setRole("bid_debit").setPlayerId(20L).setStream(3)
                        .setOpId(12L).setOutcome(2).setBundle(bidGold).build());

        var voidReq = new ManualContractPlanner.Request(
                500L, 4L, ManualContractPlanner.Action.VOID_AUCTION, "ops-alice", "TICKET-3",
                List.of(), "", 0L, 501L, 3L, "", "");
        var res = ManualContractPlanner.planManualAction(lot, lotW, new ManualContractPlanner.Context(
                voidReq, List.of(), null, null, null, bid, bidW, List.of()));
        assertThat(lot.getStatus()).isEqualTo(CANCEL_PENDING);
        assertThat(lotW.getStage()).isEqualTo("return");
        assertThat(res.newLegs).containsExactly("seller_debit/refund");
        assertThat(bid.getStatus()).isEqualTo(CANCEL_PENDING);
        assertThat(bidW.getStage()).isEqualTo("bid_refund");

        // 2. 已整单退回过的合同再跑一次 refund -> 必须拒绝（防丢托管，引导用 reissue）
        lot.setStatus(MANUAL);
        lotW.setStage("manual");
        var secondRefund = new ManualContractPlanner.Request(
                500L, 5L, ManualContractPlanner.Action.REFUND, "ops-alice", "TICKET-3b",
                List.of(), "", 0L, 0L, 0L, "", "");
        // 模拟无出价以专门命中 refundCommon 的二次退回校验
        lotW.setHighestBidder(0L);
        assertThatThrownBy(() -> ManualContractPlanner.planManualAction(
                lot, lotW, new ManualContractPlanner.Context(secondRefund, List.of(), null, null, null, null, null, null)))
                .isInstanceOf(ManualContractPlanner.NotAllowedException.class).hasMessageContaining("已经整单退回过");

        // 3. 拍品不再引用的出价可以单独 refund
        TradeCommerceRow.Builder orphanBid = TradeCommerceRow.newBuilder()
                .setCommerceId(502L).setKind(BID_KIND).setParentId(500L).setStatus(MANUAL).setVersion(2L).setSellerPlayerId(30L);
        TradeCommerceWorkflow.Builder orphanBidW = TradeCommerceWorkflow.newBuilder()
                .setStage("manual").setQuarantine(quarantine("bid_refund", CANCEL_PENDING, MANUAL_REASON_FINAL_CREDIT))
                .addLegs(TradeCommerceLeg.newBuilder().setRole("bid_debit").setPlayerId(30L).setStream(3)
                        .setOpId(13L).setOutcome(2).setBundle(bidGold).build());
        TradeCommerceWorkflow otherWinningLot = TradeCommerceWorkflow.newBuilder().setWinningBidId(501L).build();
        var orphanRes = ManualContractPlanner.planManualAction(orphanBid, orphanBidW, new ManualContractPlanner.Context(
                baseReq(ManualContractPlanner.Action.REFUND), List.of(), null,
                TradeCommerceRow.newBuilder().setCommerceId(500L).build(), otherWinningLot, null, null, null));
        assertThat(orphanRes.newLegs).containsExactly("bid_debit/refund");
        assertThat(orphanBid.getStatus()).isEqualTo(CANCEL_PENDING);
    }

    @Test
    void 部分成交市场单refund仅退未成交剩余_成交子单转人工不再连带母单() throws Exception {
        // 买单总买 10 个，单价 100，已成交 4 个 -> 剩余 6 * 100 = 600
        AssetBundle origDebit = AssetBundle.newBuilder()
                .addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(1).setAmount(1000)).build();
        TradeCommerceRow.Builder marketOrder = TradeCommerceRow.newBuilder()
                .setCommerceId(700L).setKind(4).setSide(1).setCurrencyType(1).setUnitPrice(100L)
                .setQuantity(10).setFilledQuantity(4).setStatus(MANUAL).setVersion(2L).setSellerPlayerId(77L);
        TradeCommerceWorkflow.Builder wf = TradeCommerceWorkflow.newBuilder()
                .setStage("manual").setQuarantine(quarantine("market_open", ESCROWING, MANUAL_REASON_CORRUPT))
                .addLegs(TradeCommerceLeg.newBuilder().setRole("seller_debit").setPlayerId(77L).setStream(3)
                        .setOpId(1L).setOutcome(2).setBundle(origDebit).build());
        var res = ManualContractPlanner.planManualAction(marketOrder, wf, new ManualContractPlanner.Context(
                baseReq(ManualContractPlanner.Action.REFUND), List.of(), null, null, null, null, null, null));
        assertThat(res.newLegs).containsExactly("seller_debit/refund");
        var refundLeg = wf.getLegsList().stream().filter(l -> l.getRole().equals("seller_debit/refund")).findFirst().orElseThrow();
        assertThat(refundLeg.getBundle().getCurrencies(0).getAmount()).isEqualTo(600L);

        // 成交子单转人工时，母单 marketBarrier 不再被拖入 MANUAL（保持 CANCEL_PENDING / market_wait）
        CommerceStore store = mock(CommerceStore.class);
        CommerceEngine engine = new CommerceEngine(store, new AtomicLong(1)::incrementAndGet,
                new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(1), false, List.of()), () -> 5_000L);
        TradeCommerceRow parent = TradeCommerceRow.newBuilder()
                .setCommerceId(700L).setKind(4).setSide(1).setStatus(CANCEL_PENDING).setVersion(3L)
                .setSellerPlayerId(77L)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder().setStage("market_wait")
                        .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("a").setOwnerRevision(1).build())
                        .build().toByteString())
                .build();
        TradeCommerceRow manualChild = TradeCommerceRow.newBuilder()
                .setCommerceId(701L).setKind(4).setSide(0).setParentId(700L).setStatus(MANUAL).build();
        Connection conn = mockConnForBarrier(5_000L, "a", 1L);
        when(store.transaction(any(), any())).thenAnswer(inv -> ((CommerceStore.Transaction<?>) inv.getArgument(1)).run(conn));
        when(store.lock(conn, 700L)).thenReturn(parent);
        when(store.list(eq(conn), anyString(), eq(700L), eq(700L))).thenReturn(List.of(manualChild));

        TradeCommerceRow out = engine.advance(parent, Deadline.after(5_000));
        assertThat(out.getStatus()).isEqualTo(CANCEL_PENDING);
        verify(store, never()).save(any(), any());
    }

    @Test
    void 管理端口默认关闭_核对环境名与opsCommerce签名与双人复核_重复approvalId幂等且审计行带引号() throws Exception {
        CommerceStore store = mock(CommerceStore.class);
        ManualContractService.RoleFactReader roles = mock(ManualContractService.RoleFactReader.class);
        long now = 1_700_000_000_000L;
        ManualContractService svc = new ManualContractService(store, roles, () -> now, "prod-cn-east");

        String secret = "0123456789abcdef0123456789abcdef-manual-secret";
        ManualContractAdminController disabled = new ManualContractAdminController(svc, false, "prod-cn-east", secret, false, () -> now);
        ManualContractAdminController enabled = new ManualContractAdminController(svc, true, "prod-cn-east", secret, false, () -> now);

        TradeAdminAuthFilter filter = new TradeAdminAuthFilter("admin-tok-1234567890", new TradeMetrics(new SimpleMeterRegistry()));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", ManualContractAdminController.RESOLVE_PATH);
        req.setServletPath(ManualContractAdminController.RESOLVE_PATH);
        req.addHeader(TradeAdminAuthFilter.TOKEN_HEADER, "admin-tok-1234567890");
        req.addHeader(TradeAdminAuthFilter.OPERATOR_HEADER, "ops-alice");

        // 1. 默认关闭 -> 403
        ResolveManualContractRequest raw = ResolveManualContractRequest.newBuilder()
                .setCommerceId(100L).setMode(ManualContractMode.MANUAL_CONTRACT_MODE_DRY_RUN)
                .setAction("resume").setExpectVersion(2L).setExpectTarget("wrong-env")
                .setOperator("ops-alice").setReason("ticket \"quoted\" line").build();
        req.setContent(raw.toByteArray());
        var resHolder = new org.springframework.http.ResponseEntity<?>[1];
        filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {
            try {
                resHolder[0] = disabled.resolveManual((jakarta.servlet.http.HttpServletRequest) r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(resHolder[0].getStatusCode().value()).isEqualTo(403);

        // 2. 开启后错误环境名 -> VERDICT_REFUSED target_mismatch
        String sigHex = ManualContractAdminController.signHex(secret.getBytes(StandardCharsets.UTF_8),
                "trade.TradeAdmin/ResolveManualContract", ManualContractAdminController.ALLOWED_CALLER, now, raw);
        ResolveManualContractRequest signedWrongEnv = raw.toBuilder()
                .setAuth(ServiceCallAuth.newBuilder().setCaller("ops-commerce").setTimestampMs(now)
                        .setSignatureHex(sigHex).build())
                .build();
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", ManualContractAdminController.RESOLVE_PATH);
        req2.setServletPath(ManualContractAdminController.RESOLVE_PATH);
        req2.addHeader(TradeAdminAuthFilter.TOKEN_HEADER, "admin-tok-1234567890");
        req2.addHeader(TradeAdminAuthFilter.OPERATOR_HEADER, "ops-alice");
        req2.setContent(signedWrongEnv.toByteArray());
        filter.doFilter(req2, new MockHttpServletResponse(), (r, s) -> {
            try {
                resHolder[0] = enabled.resolveManual((jakarta.servlet.http.HttpServletRequest) r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(resHolder[0].getStatusCode().value()).isEqualTo(200);
        ResolveManualContractResponse parsed = ResolveManualContractResponse.parseFrom((byte[]) resHolder[0].getBody());
        assertThat(parsed.getVerdict()).isEqualTo(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_REFUSED);
        assertThat(parsed.getRefusal()).contains("target_mismatch");

        // 3. EXECUTE 缺少双人复核 -> REFUSED
        ResolveManualContractRequest noApprovalBase = ResolveManualContractRequest.newBuilder()
                .setCommerceId(100L).setMode(ManualContractMode.MANUAL_CONTRACT_MODE_EXECUTE)
                .setAction("resume").setExpectVersion(2L).setExpectTarget("prod-cn-east")
                .setOperator("ops-alice").setReason("ticket").build();
        String execSigHex = ManualContractAdminController.signHex(secret.getBytes(StandardCharsets.UTF_8),
                "trade.TradeAdmin/ResolveManualContract", "ops-commerce", now, noApprovalBase);
        ResolveManualContractRequest noApproval = noApprovalBase.toBuilder()
                .setAuth(ServiceCallAuth.newBuilder().setCaller("ops-commerce").setTimestampMs(now)
                        .setSignatureHex(execSigHex).build())
                .build();
        MockHttpServletRequest req3 = new MockHttpServletRequest("POST", ManualContractAdminController.RESOLVE_PATH);
        req3.setServletPath(ManualContractAdminController.RESOLVE_PATH);
        req3.addHeader(TradeAdminAuthFilter.TOKEN_HEADER, "admin-tok-1234567890");
        req3.addHeader(TradeAdminAuthFilter.OPERATOR_HEADER, "ops-alice");
        req3.setContent(noApproval.toByteArray());
        filter.doFilter(req3, new MockHttpServletResponse(), (r, s) -> {
            try {
                resHolder[0] = enabled.resolveManual((jakarta.servlet.http.HttpServletRequest) r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        ResolveManualContractResponse noAppResp = ResolveManualContractResponse.parseFrom((byte[]) resHolder[0].getBody());
        assertThat(noAppResp.getVerdict()).isEqualTo(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_REFUSED);
        assertThat(noAppResp.getRefusal()).contains("双人复核");

        // 4. 重复 approval_id 幂等直接返回原留痕，不再写库；且审计行中自由文本加双引号转义
        TradeCommerceWorkflow alreadyWf = TradeCommerceWorkflow.newBuilder()
                .setStage("deliver")
                .addManualActions(TradeCommerceManualAction.newBuilder()
                        .setAction("resume").setOperator("ops-alice").setReason("old")
                        .setVersionBefore(2L).setApprovalId("AP-99").setApprover("ops-bob").build())
                .build();
        when(store.get(100L)).thenReturn(TradeCommerceRow.newBuilder()
                .setCommerceId(100L).setStatus(DELIVERING).setVersion(3L)
                .setWorkflowProto(alreadyWf.toByteString()).build());
        ResolveManualContractRequest replay = noApproval.toBuilder()
                .setReason("ticket \"escape\" test")
                .setApproval(AdminApproval.newBuilder().setApprovalId("AP-99").setProposer("ops-alice")
                        .setApprover("ops-bob").setApprovedAtMs(now).build())
                .build();
        ResolveManualContractResponse replayResp = svc.resolve(replay, Deadline.after(5_000));
        assertThat(replayResp.getVerdict()).isEqualTo(ManualContractVerdict.MANUAL_CONTRACT_VERDICT_OK);
        assertThat(replayResp.getAlreadyExecuted()).isTrue();
        assertThat(replayResp.getAuditLine()).contains("reason=\"ticket \\\"escape\\\" test\"");
        verify(store, never()).transaction(any(), any());
    }

    private static Connection mockConnForBarrier(long sqlNow, String acc, long rev) throws Exception {
        Connection c = mock(Connection.class);
        when(c.unwrap(com.game.common.sql.AuthorityConnection.Fence.class))
                .thenReturn(mock(com.game.common.sql.AuthorityConnection.Fence.class));
        java.sql.Statement st = mock(java.sql.Statement.class);
        ResultSet nowRs = mock(ResultSet.class);
        when(c.createStatement()).thenReturn(st);
        when(st.executeQuery(anyString())).thenReturn(nowRs);
        when(nowRs.next()).thenReturn(true);
        when(nowRs.getLong(1)).thenReturn(sqlNow);

        PreparedStatement metaStmt = mock(PreparedStatement.class);
        ResultSet metaRs = mock(ResultSet.class);
        when(metaStmt.executeQuery()).thenReturn(metaRs);
        when(metaRs.next()).thenReturn(true);
        when(metaRs.getString(1)).thenReturn("11111111-1111-1111-1111-111111111111");
        when(metaRs.getString(2)).thenReturn("1");
        when(metaRs.getInt(3)).thenReturn(com.game.common.player.PlayerRouteAuthority.FENCE_SHARDS);

        PreparedStatement zoneStmt = mock(PreparedStatement.class);
        ResultSet zoneRs = mock(ResultSet.class);
        when(zoneStmt.executeQuery()).thenReturn(zoneRs);
        when(zoneRs.next()).thenReturn(false);

        PreparedStatement ownerStmt = mock(PreparedStatement.class);
        ResultSet ownerRs = mock(ResultSet.class);
        when(ownerStmt.executeQuery()).thenReturn(ownerRs);
        when(ownerRs.next()).thenReturn(true);
        when(ownerRs.getString(1)).thenReturn(acc);
        when(ownerRs.getString(2)).thenReturn(Long.toUnsignedString(rev));
        when(ownerRs.getString(3)).thenReturn("0");

        when(c.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            if (sql.contains("FROM route_meta")) {
                return metaStmt;
            }
            if (sql.contains("SELECT zone_id FROM player")) {
                return zoneStmt;
            }
            return ownerStmt;
        });
        return c;
    }
}
