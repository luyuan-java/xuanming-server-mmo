package com.game.trade.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerSessionAuthority;
import com.game.contract.MessageIdRegistry;
import com.game.proto.trade.BrowseListingsRequest;
import com.game.proto.trade.BrowseListingsResponse;
import com.game.proto.trade.GetMyShelfRequest;
import com.game.proto.trade.GetMyShelfResponse;
import com.game.proto.trade.ListCommerceOrdersRequest;
import com.game.proto.trade.ListCommerceOrdersResponse;
import com.game.proto.trade.TradeCommerceWorkflow;
import com.game.proto.trade.TradeRoleIdentity;
import com.game.trade.commerce.CommerceClientService;
import com.game.trade.commerce.CommerceEngine;
import com.game.trade.commerce.CommerceRules;
import com.game.trade.commerce.CommerceStore;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.rules.TradeTips;
import com.game.trade.service.JubaozhaiService;
import com.game.trade.store.pb.TradeCommerceRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-12 交易只读方法核归属（我的货架/收藏/订单）及私有历史按合同原账号与修订过滤。 */
class TradeReadOnlyOwnershipTest {

    @Test
    void 私有只读接口核归属_公开浏览不核_交易历史新主看不到旧主挂单() throws Exception {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        JubaozhaiService jubaozhai = mock(JubaozhaiService.class);
        when(jubaozhai.browseListings(anyLong(), any(), any())).thenReturn(BrowseListingsResponse.getDefaultInstance());
        when(jubaozhai.getMyShelf(anyLong(), any(), any())).thenReturn(GetMyShelfResponse.getDefaultInstance());

        CommerceStore store = mock(CommerceStore.class);
        TradeCommerceRow oldOwnerOrder = TradeCommerceRow.newBuilder()
                .setCommerceId(5001L).setKind(3).setStatus(CommerceRules.WAITING).setSellerPlayerId(1001L)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder()
                        .setStage("open")
                        .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("acc-old").setOwnerRevision(1L))
                        .build().toByteString())
                .build();
        TradeCommerceRow newOwnerOrder = TradeCommerceRow.newBuilder()
                .setCommerceId(5002L).setKind(3).setStatus(CommerceRules.WAITING).setSellerPlayerId(1001L)
                .setWorkflowProto(TradeCommerceWorkflow.newBuilder()
                        .setStage("open")
                        .setSellerIdentity(TradeRoleIdentity.newBuilder().setAccount("acc-new").setOwnerRevision(2L))
                        .build().toByteString())
                .build();
        when(store.list(org.mockito.ArgumentMatchers.anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(oldOwnerOrder, newOwnerOrder));

        CommerceEngine engine = new CommerceEngine(store, () -> 1L,
                new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(1), false, List.of()), () -> 1_700_000_000_000L);
        CommerceClientService commerceClient = new CommerceClientService(engine, null, null, null, null);

        PlayerSessionAuthority.ReadOnlyOwnerCheck check = (pid, acc, rev) ->
                pid == 1001L && "acc-new".equals(acc) && rev == 2L;

        TradeDispatcher dispatcher = new TradeDispatcher(
                registry, jubaozhai, Runnable::run, new TradeMetrics(new SimpleMeterRegistry()), 3500L, commerceClient)
                .withReadOnlyOwnerCheck(check);

        SessionContext staleSession = SessionContext.newBuilder()
                .setPlayerId(1001L).setAccount("acc-old").setOwnerRevision(1L).build();
        SessionContext newOwnerSession = SessionContext.newBuilder()
                .setPlayerId(1001L).setAccount("acc-new").setOwnerRevision(2L).build();

        // 1. 公开 BrowseListings(favorites_only=false) 不核归属，旧会话仍可看公开市场
        int browseId = registry.requireId(TradeMethods.SERVICE, TradeMethods.BROWSE_LISTINGS);
        ClientReply publicBrowse = dispatcher.dispatch(ClientCall.newBuilder()
                .setSession(staleSession).setMessageId(browseId)
                .setBody(BrowseListingsRequest.newBuilder().setFavoritesOnly(false).build().toByteString())
                .build()).join();
        assertThat(publicBrowse.getTipId()).isZero();

        // 2. 私有 BrowseListings(favorites_only=true) 与 GetMyShelf 拒绝已过户旧会话
        ClientReply staleFav = dispatcher.dispatch(ClientCall.newBuilder()
                .setSession(staleSession).setMessageId(browseId)
                .setBody(BrowseListingsRequest.newBuilder().setFavoritesOnly(true).build().toByteString())
                .build()).join();
        assertThat(staleFav.getTipId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);

        int myShelfId = registry.requireId(TradeMethods.SERVICE, TradeMethods.GET_MY_SHELF);
        ClientReply staleShelf = dispatcher.dispatch(ClientCall.newBuilder()
                .setSession(staleSession).setMessageId(myShelfId)
                .setBody(GetMyShelfRequest.getDefaultInstance().toByteString())
                .build()).join();
        assertThat(staleShelf.getTipId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        verify(jubaozhai, never()).getMyShelf(anyLong(), any(), any());

        // 3. 新主调用 ListCommerceOrders：通过归属核对，且只能看到自己 (acc-new, rev=2) 的订单，看不到旧主 (acc-old, rev=1) 的历史挂单
        int listOrdersId = registry.requireId(TradeMethods.SERVICE, "ListCommerceOrders");
        ClientReply newOrdersReply = dispatcher.dispatch(ClientCall.newBuilder()
                .setSession(newOwnerSession).setMessageId(listOrdersId)
                .setBody(ListCommerceOrdersRequest.newBuilder().setLimit(20).build().toByteString())
                .build()).join();
        assertThat(newOrdersReply.getTipId()).isZero();
        ListCommerceOrdersResponse orders = ListCommerceOrdersResponse.parseFrom(newOrdersReply.getBody());
        assertThat(orders.getOrdersList()).extracting(o -> o.getOrderId()).containsExactly("5002");
    }
}
