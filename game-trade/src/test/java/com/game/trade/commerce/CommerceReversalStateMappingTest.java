package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.proto.trade.CommerceOrderView;
import org.junit.jupiter.api.Test;

/** 商城新增 REVERSAL_PENDING / REVERSED 订单状态在 game-trade 映射至 proto 12 / 13，不抛未知状态异常。 */
class CommerceReversalStateMappingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void rmbConsignmentClient_mapsReversalPendingAndReversed() throws Exception {
        CommerceOrderView pending = RmbConsignmentClient.order(JSON.readTree("""
                {"orderId":"ord-1","state":"REVERSAL_PENDING","currency":"CNY","buyerId":"1001","amountMinor":500}
                """));
        assertThat(pending.getStateValue()).isEqualTo(12);

        CommerceOrderView reversed = RmbConsignmentClient.order(JSON.readTree("""
                {"orderId":"ord-2","state":"REVERSED","currency":"CNY","buyerId":"1001","amountMinor":500}
                """));
        assertThat(reversed.getStateValue()).isEqualTo(13);
    }
}
