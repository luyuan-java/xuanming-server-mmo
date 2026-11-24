package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.AssetBundle;
import com.game.proto.ItemGrant;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.trade.store.pb.TradeCommerceRow;
import org.junit.jupiter.api.Test;

/** 验证官方商城订单渠道撤单扣回、争议胜诉补发、余额不足转人工与扣回页游标校验。 */
class OfficialReversalTest {

    private static final CommerceRules.Policy POLICY = new CommerceRules.Policy(
            java.util.Set.of(0), java.util.Set.of(1001), java.util.Set.of(), true, java.util.List.of());

    private static AssetBundle bundle() {
        return AssetBundle.newBuilder()
                .addItems(ItemGrant.newBuilder().setConfigId(1001).setCount(2))
                .build();
    }

    @Test
    void 已发货订单撤单扣回成功收敛REVERSED_争议胜诉rev2补发收敛DELIVERED() {
        var r = TradeCommerceRow.newBuilder().setKind(1).setStatus(CommerceRules.DELIVERING).setBuyerPlayerId(42L);
        var w = TradeCommerceWorkflow.newBuilder()
                .setStage(CommerceRules.STAGE_OFFICIAL_DELIVER)
                .setOfficialBundle(bundle());

        // 首次发货
        CommerceRules.plan(r, w, 1000L, POLICY);
        assertThat(w.getLegsCount()).isEqualTo(1);
        assertThat(w.getLegs(0).getRole()).isEqualTo("official_credit");
        assertThat(w.getLegs(0).getStream()).isEqualTo(4);
        w.getLegsBuilder(0).setOutcome(2);
        CommerceRules.plan(r, w, 1001L, POLICY);
        assertThat(r.getStatus()).isEqualTo(CommerceRules.SETTLED);
        assertThat(w.getStage()).isEqualTo("done");

        // rev=1 渠道撤单 -> 建立扣回腿 official_reverse_1 (stream=3)
        w.setPaymentRevision(1).setPaymentRevoked(true).setStage(CommerceRules.STAGE_OFFICIAL_DELIVER);
        r.setStatus(CommerceRules.DELIVERING);
        CommerceRules.plan(r, w, 2000L, POLICY);
        assertThat(w.getLegsCount()).isEqualTo(2);
        assertThat(w.getLegs(1).getRole()).isEqualTo("official_reverse_1");
        assertThat(w.getLegs(1).getStream()).isEqualTo(3);
        assertThat(CommerceRules.isOfficialReverseLeg(w.getLegs(1).getRole())).isTrue();
        assertThat(CommerceRules.officialReversalState(r.build(), w.build())).isEmpty();

        // 扣回成功 -> CANCELLED + official_reversed -> 回执 REVERSED
        w.getLegsBuilder(1).setOutcome(2);
        CommerceRules.plan(r, w, 2001L, POLICY);
        assertThat(r.getStatus()).isEqualTo(CommerceRules.CANCELLED);
        assertThat(w.getStage()).isEqualTo(CommerceRules.STAGE_OFFICIAL_REVERSED);
        assertThat(CommerceRules.officialReversalState(r.build(), w.build())).isEqualTo("REVERSED");

        // rev=2 争议胜诉 -> 建立补发腿 official_regrant_2 (stream=4) -> 成功后 DELIVERED
        w.setPaymentRevision(2).setPaymentRevoked(false).setStage(CommerceRules.STAGE_OFFICIAL_DELIVER);
        r.setStatus(CommerceRules.DELIVERING);
        CommerceRules.plan(r, w, 3000L, POLICY);
        assertThat(w.getLegsCount()).isEqualTo(3);
        assertThat(w.getLegs(2).getRole()).isEqualTo("official_regrant_2");
        assertThat(w.getLegs(2).getStream()).isEqualTo(4);
        w.getLegsBuilder(2).setOutcome(2);
        CommerceRules.plan(r, w, 3001L, POLICY);
        assertThat(r.getStatus()).isEqualTo(CommerceRules.SETTLED);
        assertThat(CommerceRules.officialReversalState(r.build(), w.build())).isEqualTo("DELIVERED");
    }

    @Test
    void 道具已用掉扣回被拒转人工MANUAL_未发货前撤单零资产腿直接REVERSED() {
        // 1. 已发货后道具被消耗，扣回腿被拒 -> 转 MANUAL
        var r = TradeCommerceRow.newBuilder().setKind(1).setStatus(CommerceRules.DELIVERING).setBuyerPlayerId(42L);
        var w = TradeCommerceWorkflow.newBuilder()
                .setStage(CommerceRules.STAGE_OFFICIAL_DELIVER)
                .setOfficialBundle(bundle())
                .setPaymentRevision(1).setPaymentRevoked(true);
        w.addLegsBuilder().setRole("official_credit").setPlayerId(42L).setStream(4).setBundle(bundle()).setOutcome(2);
        CommerceRules.plan(r, w, 1000L, POLICY);
        w.getLegsBuilder(1).setOutcome(3); // REJECTED
        CommerceRules.plan(r, w, 1001L, POLICY);
        assertThat(r.getStatus()).isEqualTo(CommerceRules.MANUAL);
        assertThat(CommerceRules.officialReversalState(r.build(), w.build())).isEqualTo("MANUAL");

        // 2. 尚未发货就收到 rev=1 撤单 -> 零资产腿直接收敛 REVERSED
        var unissued = TradeCommerceRow.newBuilder().setKind(1).setStatus(CommerceRules.DELIVERING).setBuyerPlayerId(42L);
        var unissuedWf = TradeCommerceWorkflow.newBuilder()
                .setStage(CommerceRules.STAGE_OFFICIAL_DELIVER)
                .setOfficialBundle(bundle())
                .setPaymentRevision(1).setPaymentRevoked(true);
        CommerceRules.plan(unissued, unissuedWf, 2000L, POLICY);
        assertThat(unissuedWf.getLegsCount()).isZero();
        assertThat(unissued.getStatus()).isEqualTo(CommerceRules.CANCELLED);
        assertThat(CommerceRules.officialReversalState(unissued.build(), unissuedWf.build())).isEqualTo("REVERSED");
    }

    @Test
    void 扣回页校验拒绝修订号与entitled奇偶冲突() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var bad = json.readTree("""
                {"entries":[{"intent":{"orderId":"11111111-1111-1111-1111-111111111111"},"revision":1,"entitled":true,"cursor":""}]}
                """);
        assertThatThrownBy(() -> OfficialMallClient.reversalPage(bad, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("奇偶");

        var ok = json.readTree("""
                {"entries":[{"intent":{"orderId":"11111111-1111-1111-1111-111111111111"},"revision":1,"entitled":false,"cursor":""}]}
                """);
        var entries = OfficialMallClient.reversalPage(ok, "");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().revision()).isEqualTo(1);
        assertThat(entries.getFirst().entitled()).isFalse();
    }
}
