package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.common.deadline.Deadline;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.player.store.asset.PersistedAssetLedgerReader;
import com.game.proto.AssetBundle;
import com.game.proto.CurrencyAmount;
import com.game.proto.ItemGrant;
import com.game.proto.trade.TradeCommerceLeg;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.trade.store.pb.TradeCommerceRow;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 成交交付先买家后卖家、退回带 TX_AUCTION_RETURN 且仅限托管者本人、拍卖由出价退款及反常隔离单测。 */
class DeliveryOrderAndTxTypeTest {

    private static final Policy POLICY = new Policy(Set.of(10), Set.of(), Set.of(0, 1), false, List.of());

    private static AssetBundle itemBundle(int configId, int count) {
        return AssetBundle.newBuilder().addItems(ItemGrant.newBuilder().setConfigId(configId).setCount(count)).build();
    }

    private static AssetBundle goldBundle(long amount) {
        return AssetBundle.newBuilder().addCurrencies(CurrencyAmount.newBuilder().setCurrencyType(0).setAmount(amount)).build();
    }

    @Test
    void 腿交易类型仅对付回托管者本人的退回腿给30_改绑他人与交付腿走4() {
        assertThat(legTxType(TradeCommerceLeg.newBuilder().setRole("seller_debit").setStream(3).setPlayerId(1).build()))
                .isEqualTo(TX_AUCTION_SELL);
        assertThat(legTxType(TradeCommerceLeg.newBuilder().setRole("official_reverse_1").setStream(3).setPlayerId(1).build()))
                .isEqualTo(TX_PAYMENT_REVERSAL);

        // 退回本人（含旧腿 escrow_owner_id=0、人工退款及补发、求购退书、人民币退回、撮合差价）-> 30
        for (String role : List.of("seller_return", "buyer_return", "bid_return", "buyer_surplus",
                "rmb_return_credit", "buyer_debit/refund", "buyer_debit/refund#2", "seller_return_1")) {
            assertThat(legTxType(TradeCommerceLeg.newBuilder()
                    .setRole(role).setStream(4).setPlayerId(10).setEscrowOwnerId(10).build()))
                    .as(role + " 付回托管者本人").isEqualTo(TX_AUCTION_RETURN);
            assertThat(legTxType(TradeCommerceLeg.newBuilder()
                    .setRole(role).setStream(4).setPlayerId(10).setEscrowOwnerId(0).build()))
                    .as(role + " 旧腿无 escrow_owner_id").isEqualTo(TX_AUCTION_RETURN);
            assertThat(legTxType(TradeCommerceLeg.newBuilder()
                    .setRole(role).setStream(4).setPlayerId(20).setEscrowOwnerId(10).build()))
                    .as(role + " 改绑他人").isEqualTo(TX_AUCTION_BUY);
        }

        // 成交交付腿即使 escrow_owner_id 与 player_id 相同也不给 30
        for (String role : List.of("buyer_receive", "seller_receive", "official_credit", "rmb_credit")) {
            assertThat(legTxType(TradeCommerceLeg.newBuilder()
                    .setRole(role).setStream(4).setPlayerId(10).setEscrowOwnerId(10).build()))
                    .as(role).isEqualTo(TX_AUCTION_BUY);
        }
    }

    @Test
    void 成交交付压住卖家所得直到买家到账_撮合差价不压_买家被拒自动整单退回且带托管者() {
        var r = TradeCommerceRow.newBuilder().setCommerceId(100).setKind(3).setStatus(DELIVERING)
                .setSellerPlayerId(1).setBuyerPlayerId(2);
        var w = TradeCommerceWorkflow.newBuilder().setStage("deliver");
        addEscrowLeg(w, "buyer_receive", 2L, 1L, itemBundle(10, 2));
        addEscrowLeg(w, "seller_receive", 1L, 2L, goldBundle(500));
        addEscrowLeg(w, "buyer_surplus", 2L, 2L, goldBundle(50));

        // 买家腿未到账：压住 seller_receive，放行 buyer_receive 与 buyer_surplus
        assertThat(legReadyToSend(w, leg(w, "buyer_receive"))).isTrue();
        assertThat(legReadyToSend(w, leg(w, "buyer_surplus"))).isTrue();
        assertThat(legReadyToSend(w, leg(w, "seller_receive"))).isFalse();

        // 买家腿发出且被终局拒绝（outcome=3），卖家腿从未发出 -> 自动整单退回，按撤单走 return / CANCEL_PENDING
        leg(w, "buyer_receive").setOpId(1001L).setOutcome(3);
        leg(w, "buyer_surplus").setOpId(1002L).setOutcome(2);
        plan(r, w, 1_000_000L, POLICY);

        assertThat(w.getStage()).isEqualTo("return");
        assertThat(r.getStatus()).isEqualTo(CANCEL_PENDING);
        assertThat(leg(w, "buyer_receive").getSupersededBy()).isEqualTo(UNWOUND_BY);
        assertThat(leg(w, "seller_receive").getSupersededBy()).isEqualTo(UNWOUND_BY);
        assertThat(legReadyToSend(w, leg(w, "seller_receive"))).isFalse();

        var sellerRet = leg(w, "seller_return");
        var buyerRet = leg(w, "buyer_return");
        assertThat(sellerRet).isNotNull();
        assertThat(sellerRet.getPlayerId()).isEqualTo(1L);
        assertThat(sellerRet.getEscrowOwnerId()).isEqualTo(1L);
        assertThat(legTxType(sellerRet)).isEqualTo(TX_AUCTION_RETURN);
        assertThat(buyerRet).isNotNull();
        assertThat(buyerRet.getPlayerId()).isEqualTo(2L);
        assertThat(buyerRet.getEscrowOwnerId()).isEqualTo(2L);
        assertThat(legTxType(buyerRet)).isEqualTo(TX_AUCTION_RETURN);

        // 两条退回腿都到账后按 CANCELLED 结案（跳过被 UNWOUND_BY 取代的原交付腿）
        sellerRet.setOpId(1003L).setOutcome(2);
        buyerRet.setOpId(1004L).setOutcome(2);
        plan(r, w, 1_000_000L, POLICY);
        assertThat(w.getStage()).isEqualTo("done");
        assertThat(r.getStatus()).isEqualTo(CANCELLED);
    }

    @Test
    void 买家到账后放行卖家所得_补发链与部分到账转人工并压住卖家所得() {
        // 1. 买家腿经人工补发 #1 到账后，自动放行被压住的卖家所得
        var w = TradeCommerceWorkflow.newBuilder().setStage("deliver");
        addEscrowLeg(w, "buyer_receive", 2L, 1L, itemBundle(10, 1));
        leg(w, "buyer_receive").setOpId(2001L).setOutcome(3).setSupersededBy("buyer_receive#1");
        addEscrowLeg(w, "buyer_receive#1", 2L, 1L, itemBundle(10, 1));
        addEscrowLeg(w, "seller_receive", 1L, 2L, goldBundle(300));

        assertThat(legReadyToSend(w, leg(w, "seller_receive"))).isFalse();
        assertThat(isHeldUnissuedSellerProceeds(leg(w, "seller_receive"))).isTrue();

        // 补发腿 #1 若失败 -> 非原腿，不可自动退回，转 MANUAL 继续压住卖家所得
        var rManual = TradeCommerceRow.newBuilder().setCommerceId(101).setKind(3).setStatus(DELIVERING);
        var wFail = w.clone();
        leg(wFail, "buyer_receive#1").setOpId(2002L).setOutcome(3);
        plan(rManual, wFail, 1_000_000L, POLICY);
        assertThat(wFail.getStage()).isEqualTo("manual");
        assertThat(rManual.getStatus()).isEqualTo(MANUAL);
        assertThat(leg(wFail, "seller_receive").getOpId()).isZero();
        assertThat(leg(wFail, "seller_return")).isNull();

        // 补发腿 #1 成功到账 -> 卖家所得自动解锁可发
        leg(w, "buyer_receive#1").setOpId(2002L).setOutcome(2);
        assertThat(legReadyToSend(w, leg(w, "seller_receive"))).isTrue();

        // 2. 部分到账合同不允许人工整单退回
        var wPartial = TradeCommerceWorkflow.newBuilder().setStage("manual");
        addEscrowLeg(wPartial, "buyer_receive", 2L, 1L, itemBundle(10, 1));
        leg(wPartial, "buyer_receive").setOpId(2003L).setOutcome(OUTCOME_PARTIAL);
        assertThat(canManualFullRefund(wPartial)).isFalse();
    }

    @Test
    void 拍卖买家被拒只退货不退钱_结案唤醒出价并由出价退钱_自相矛盾拍品隔离出价() throws Exception {
        var lotBefore = TradeCommerceRow.newBuilder().setCommerceId(500).setKind(5).setStatus(DELIVERING)
                .setSellerPlayerId(1).setBuyerPlayerId(2).build();
        var r = lotBefore.toBuilder();
        var w = TradeCommerceWorkflow.newBuilder().setStage("deliver").setWinningBidId(42L).setHighestBidder(2L);
        addEscrowLeg(w, "buyer_receive", 2L, 1L, itemBundle(10, 1));
        addEscrowLeg(w, "seller_receive", 1L, 2L, goldBundle(800));
        leg(w, "buyer_receive").setOpId(3001L).setOutcome(3);

        plan(r, w, 1_000_000L, POLICY);
        assertThat(w.getStage()).isEqualTo("return");
        assertThat(r.getStatus()).isEqualTo(CANCEL_PENDING);
        assertThat(leg(w, "seller_return")).isNotNull();
        assertThat(leg(w, "buyer_return")).isNull();
        assertThat(finishedLotBid(lotBefore, r.build(), w)).isZero();

        leg(w, "seller_return").setOpId(3002L).setOutcome(2);
        var inReturn = r.build();
        plan(r, w, 1_000_000L, POLICY);
        assertThat(r.getStatus()).isEqualTo(CANCELLED);
        assertThat(finishedLotBid(inReturn, r.build(), w)).isEqualTo(42L);
        assertThat(heldBidKeepsFunds(w, 42L)).isTrue();

        // 拍品按撤单结案后，出价 settleHeldBid 转为 bid_refund / CANCEL_PENDING
        CommerceStore store = mock(CommerceStore.class);
        CommerceEngine engine = new CommerceEngine(store, new AtomicLong(4000)::incrementAndGet, POLICY, () -> 1_000_000L);
        TradeCommerceRow bidRow = TradeCommerceRow.newBuilder().setCommerceId(42L).setKind(BID_KIND).setStatus(ESCROWING)
                .setParentId(500L).setSellerPlayerId(2L).setVersion(1)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder().setStage("bid_held").build().toByteString()).build();
        TradeCommerceRow cancelledLot = r.setWorkflowProto(w.build().toByteString()).build();
        when(store.due(1_000_000L, 100)).thenReturn(List.of(bidRow));
        when(store.ops(anyString(), any(), any())).thenReturn(List.of());
        when(store.ackDue(anyLong(), anyInt())).thenReturn(List.of());
        when(store.get(500L)).thenReturn(cancelledLot);
        when(store.transaction(any(), any())).thenAnswer(inv -> {
            CommerceStore.Transaction<?> tx = inv.getArgument(1);
            return tx.run(mock(Connection.class));
        });
        when(store.lock(any(), eq(42L))).thenReturn(bidRow);

        try (var runtime = new CommerceRuntime(engine, mock(ReliableAssetDelivery.class), mock(PersistedAssetLedgerReader.class))) {
            runtime.tick(Deadline.after(5000));
        }
        var savedCaptor = ArgumentCaptor.forClass(TradeCommerceRow.Builder.class);
        verify(store).save(any(), savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(CANCEL_PENDING);
        assertThat(TradeCommerceWorkflow.parseFrom(savedCaptor.getValue().getWorkflowProto()).getStage()).isEqualTo("bid_refund");

        // 自相矛盾拍品：按撤单结案但买家腿到过账或卖家发过款 -> heldBidKeepsFunds=false，出价被隔离成 MANUAL
        var badLotWorkflow = w.clone();
        leg(badLotWorkflow, "buyer_receive").setOutcome(2);
        assertThat(heldBidKeepsFunds(badLotWorkflow, 42L)).isFalse();
        var badLotWorkflow2 = w.clone();
        leg(badLotWorkflow2, "seller_receive").setOpId(9999L);
        assertThat(heldBidKeepsFunds(badLotWorkflow2, 42L)).isFalse();

        when(store.get(500L)).thenReturn(cancelledLot.toBuilder().setWorkflowProto(badLotWorkflow.build().toByteString()).build());
        when(store.quarantine(eq(bidRow), any())).thenReturn(true);
        try (var runtime = new CommerceRuntime(engine, mock(ReliableAssetDelivery.class), mock(PersistedAssetLedgerReader.class))) {
            runtime.tick(Deadline.after(5000));
        }
        verify(store).quarantine(eq(bidRow), any());
    }
}
