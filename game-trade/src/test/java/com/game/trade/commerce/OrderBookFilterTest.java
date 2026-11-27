package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.proto.trade.GetMarketDepthRequest;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeMarketView;
import com.game.trade.store.TradeTables;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.ByteString;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 聚宝斋材料盘口只收已托管订单（on_order_book 列、索引、撮合入口守卫、选单与深度 SQL 过滤）。 */
class OrderBookFilterTest {

    @Test
    void 合同表结构包含在盘标记列与扩展盘口索引() {
        String ddl = TradeTables.registry().createTableSql(TradeCommerceRow.class);
        assertThat(ddl).contains("`on_order_book`");
        assertThat(ddl).contains(
                "INDEX `idx_trade_commerce_3` (`kind`,`market_id`,`item_config_id`,`side`,`status`,`on_order_book`,`unit_price`,`commerce_id`)");
    }

    @Test
    void 在盘标记仅对kind4买卖单且market_open为真_其余阶段与损坏原件为假() {
        TradeCommerceRow openBuy = TradeCommerceRow.newBuilder()
                .setKind(4).setSide(1).setStatus(CommerceRules.ESCROWING)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder().setStage("market_open").build().toByteString())
                .build();
        TradeCommerceRow escrowingSell = openBuy.toBuilder()
                .setSide(2)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder().setStage("escrow").build().toByteString())
                .build();
        TradeCommerceRow matchChild = openBuy.toBuilder().setSide(0).build();
        TradeCommerceRow consignment = openBuy.toBuilder().setKind(3).build();
        TradeCommerceRow corrupt = openBuy.toBuilder()
                .setWorkflowProto(ByteString.copyFrom(new byte[] {(byte) 0xff})).build();

        assertThat(CommerceStore.computeOnOrderBook(openBuy)).isTrue();
        assertThat(CommerceStore.computeOnOrderBook(escrowingSell)).isFalse();
        assertThat(CommerceStore.computeOnOrderBook(matchChild)).isFalse();
        assertThat(CommerceStore.computeOnOrderBook(consignment)).isFalse();
        assertThat(CommerceStore.computeOnOrderBook(corrupt)).isFalse();
    }

    @Test
    void 托管中材料单不发起撮合_不发成交号也不抢盘口守卫锁() throws Exception {
        CommerceStore store = mock(CommerceStore.class);
        AtomicInteger idCalls = new AtomicInteger();
        CommerceEngine engine = new CommerceEngine(store, () -> {
            idCalls.incrementAndGet();
            return 9001L;
        }, new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(1), false, List.of()), () -> 1_000_000L);
        CommerceMatching matching = new CommerceMatching(engine);

        TradeCommerceRow escrowingOrder = TradeCommerceRow.newBuilder()
                .setCommerceId(501L).setKind(4).setSide(1).setStatus(CommerceRules.ESCROWING)
                .setMarketId(1).setItemConfigId(10).setCurrencyType(1).setUnitPrice(100).setQuantity(5)
                .setOnOrderBook(false)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder().setStage("escrow").build().toByteString())
                .build();

        matching.match(escrowingOrder, Deadline.after(5_000));

        assertThat(idCalls.get()).isZero();
        verify(store, never()).transaction(any(), any());
    }

    @Test
    void 撮合选对手单与深度查询均按on_order_book过滤() throws Exception {
        CommerceStore store = mock(CommerceStore.class);
        Connection conn = mock(Connection.class);
        PreparedStatement guardStmt = mock(PreparedStatement.class);
        ResultSet guardRs = mock(ResultSet.class);
        when(guardRs.next()).thenReturn(true);
        when(guardStmt.executeQuery()).thenReturn(guardRs);
        when(conn.prepareStatement(anyString())).thenReturn(guardStmt);
        when(store.transaction(any(), any())).thenAnswer(inv -> {
            CommerceStore.Transaction<?> tx = inv.getArgument(1);
            return tx.run(conn);
        });
        when(store.list(any(Connection.class), anyString(), any(Object[].class))).thenReturn(List.of());

        TradeMarketView market = TradeMarketView.newBuilder()
                .setMarketId(1).setItemConfigId(10).setCurrencyType(1).build();
        CommerceEngine engine = new CommerceEngine(store, new AtomicLong(9000)::incrementAndGet,
                new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(1), false, List.of(market)), () -> 1_000_000L);

        // 1. 撮合候选查询必须带 on_order_book=1
        TradeCommerceRow openBuy = TradeCommerceRow.newBuilder()
                .setCommerceId(601L).setKind(4).setSide(1).setStatus(CommerceRules.ESCROWING)
                .setMarketId(1).setItemConfigId(10).setCurrencyType(1).setUnitPrice(100).setQuantity(5)
                .setOnOrderBook(true)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder().setStage("market_open").build().toByteString())
                .build();
        new CommerceMatching(engine).match(openBuy, Deadline.after(5_000));

        ArgumentCaptor<String> matchWhere = ArgumentCaptor.forClass(String.class);
        verify(store).list(any(Connection.class), matchWhere.capture(), any(Object[].class));
        assertThat(matchWhere.getValue()).contains("status=3 AND on_order_book=1");

        // 2. 深度查询买卖两面都必须带 on_order_book=1
        List<String> depthSqls = new ArrayList<>();
        PreparedStatement depthStmt = mock(PreparedStatement.class);
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.next()).thenReturn(false);
        when(depthStmt.executeQuery()).thenReturn(emptyRs);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            depthSqls.add(inv.getArgument(0));
            return depthStmt;
        });

        CommerceClientService client = new CommerceClientService(engine, null);
        client.depth(GetMarketDepthRequest.newBuilder().setMarketId(1).setItemConfigId(10).setLimit(10).build(),
                Deadline.after(5_000));

        assertThat(depthSqls).hasSize(2);
        assertThat(depthSqls).allSatisfy(sql -> assertThat(sql).contains("status=3 AND on_order_book=1"));
    }
}
