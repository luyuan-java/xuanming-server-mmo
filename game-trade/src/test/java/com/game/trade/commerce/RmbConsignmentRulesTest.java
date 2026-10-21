package com.game.trade.commerce;

import static org.junit.jupiter.api.Assertions.*;
import com.game.proto.trade.*;
import com.game.trade.store.pb.TradeCommerceRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RmbConsignmentRulesTest {
    private final CommerceRules.Policy policy = new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(0), false, List.of());
    private TradeCommerceRow.Builder row() { return TradeCommerceRow.newBuilder().setKind(6).setSellerPlayerId(1).setBuyerPlayerId(2).setStatus(3); }
    private TradeCommerceWorkflow.Builder escrow() {
        return TradeCommerceWorkflow.newBuilder().setStage("rmb_escrow")
            .setSellerAssets(TradeAssetSelection.newBuilder().putItems(10, 1));
    }
    @Test void 只有扣出真实完成才登记商城_失败不登记_撤单原托管只返一次() {
        var r = row(); var w = escrow(); CommerceRules.plan(r, w, 1000, policy);
        assertEquals("rmb_escrow", w.getStage()); assertEquals(1, w.getLegsCount());
        w.getLegsBuilder(0).setOutcome(2); CommerceRules.plan(r, w, 1001, policy);
        assertEquals("rmb_register", w.getStage()); assertEquals(1, r.getStatus());
        var denied = escrow(); var deniedRow = row(); CommerceRules.plan(deniedRow, denied, 1000, policy);
        denied.getLegsBuilder(0).setOutcome(3); CommerceRules.plan(deniedRow, denied, 1001, policy);
        assertEquals("done", denied.getStage()); assertEquals(7, deniedRow.getStatus());
        var cancelled = escrow().setCancelRequested(true); var cancelledRow = row();
        CommerceRules.plan(cancelledRow, cancelled, 1000, policy); cancelled.getLegsBuilder(0).setOutcome(2);
        CommerceRules.plan(cancelledRow, cancelled, 1001, policy); CommerceRules.plan(cancelledRow, cancelled, 1002, policy);
        assertEquals("rmb_return", cancelled.getStage()); assertEquals(2, cancelled.getLegsCount());
        assertEquals(1, cancelled.getLegs(1).getPlayerId()); assertEquals(4, cancelled.getLegs(1).getStream());
    }
    @Test void 发放失败只走退款_完成发放只请求交付回执_角色流程不造资产腿() {
        var w = escrow(); var r = row(); CommerceRules.plan(r, w, 1000, policy);
        w.getLegsBuilder(0).setOutcome(2); w.setStage("rmb_deliver"); CommerceRules.plan(r, w, 1001, policy);
        assertEquals(2, w.getLegs(1).getPlayerId()); w.getLegsBuilder(1).setOutcome(3);
        CommerceRules.plan(r, w, 1002, policy); assertEquals("rmb_refund", w.getStage()); assertEquals(8, r.getStatus());
        var character = TradeCommerceWorkflow.newBuilder().setStage("rmb_confirm");
        CommerceRules.plan(row(), character, 1000, policy); assertEquals(0, character.getLegsCount());
    }
    @Test void 未付款订单和提现DTO可含未设置provider字段_数值冒充字符串拒绝() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var value = RmbConsignmentClient.withdrawal(json.readTree("{\"withdrawalId\":\"a\",\"amountFen\":1,\"state\":\"REQUESTED\",\"channel\":null,\"transactionId\":null}"));
        assertEquals("", value.getChannel()); assertEquals("", value.getTransactionId());
        assertThrows(IllegalStateException.class, () -> RmbConsignmentClient.text(json.readTree("{\"orderId\":42}"), "orderId"));
    }
}
