package com.game.trade.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.PlayerTradeCommand;
import com.game.api.proto.PlayerTradeReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.proto.trade.CreateRmbConsignmentRequest;
import com.game.proto.trade.TradeAssetSelection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 人民币寄售标题内容安全审核校验：敏感词拒 20016、审核不可用拒 20017（fail-closed）、空标题先由参数校验拦截。 */
class RmbConsignmentTitleGuardTest {

    private static SessionContext session() {
        return SessionContext.newBuilder()
                .setPlayerId(1001L).setAccount("seller-a").setOwnerRevision(1L).build();
    }

    private static CreateRmbConsignmentRequest request(String title) {
        return CreateRmbConsignmentRequest.newBuilder()
                .setClientRequestId("req-rmb-12345678")
                .setTitle(title)
                .setPriceFen(1000L)
                .setAssets(TradeAssetSelection.newBuilder().putItems(10, 1))
                .build();
    }

    private static RmbConsignmentService service(PlayerTradeClient players, RmbConsignmentService.TextGuard guard) {
        CommerceEngine engine = new CommerceEngine(
                mock(CommerceStore.class), () -> 1L,
                new CommerceRules.Policy(Set.of(10), Set.of(), Set.of(0), false, List.of()),
                () -> 1_000_000L);
        return new RmbConsignmentService(engine, mock(RmbConsignmentClient.class), players, Set.of(0), guard);
    }

    @Test
    void 敏感标题拒20016_服务异常failClosed拒20017_空白标题不调审核() throws Exception {
        PlayerTradeClient players = mock(PlayerTradeClient.class);
        when(players.execute(any(PlayerTradeCommand.Builder.class), any()))
                .thenReturn(PlayerTradeReply.newBuilder().setCode("OK").setOwnerRevision(1L).setHomeZone(1).build());

        AtomicInteger guardCalls = new AtomicInteger();
        // 1) 空白标题直接参数拒绝（INVALID），不触发内容安全 RPC
        RmbConsignmentService blankSvc = service(players, (pid, acc, title, d) -> {
            guardCalls.incrementAndGet();
            return 0;
        });
        assertThatThrownBy(() -> blankSvc.client("CreateRmbConsignment", session(), request("   "), Deadline.after(3000)))
                .isInstanceOf(CommerceRules.Refusal.class)
                .extracting(e -> ((CommerceRules.Refusal) e).tip())
                .isEqualTo(CommerceRules.INVALID);
        assertThat(guardCalls.get()).isZero();

        // 2) 敏感标题拒绝 20016（kTradeTitleSensitive）
        RmbConsignmentService sensitiveSvc = service(players, (pid, acc, title, d) -> {
            guardCalls.incrementAndGet();
            assertThat(pid).isEqualTo(1001L);
            assertThat(title).isEqualTo("违规标题");
            return CommerceRules.TITLE_SENSITIVE;
        });
        assertThatThrownBy(() -> sensitiveSvc.client("CreateRmbConsignment", session(), request("违规标题"), Deadline.after(3000)))
                .isInstanceOf(CommerceRules.Refusal.class)
                .extracting(e -> ((CommerceRules.Refusal) e).tip())
                .isEqualTo(CommerceRules.TITLE_SENSITIVE);
        assertThat(guardCalls.get()).isEqualTo(1);

        // 3) 审核抛出运行时异常 -> fail-closed 拒 20017（kTradeTextCheckUnavailable）
        RmbConsignmentService brokenSvc = service(players, (pid, acc, title, d) -> {
            throw new IllegalStateException("content guard timeout");
        });
        assertThatThrownBy(() -> brokenSvc.client("CreateRmbConsignment", session(), request("正常标题"), Deadline.after(3000)))
                .isInstanceOf(CommerceRules.Refusal.class)
                .extracting(e -> ((CommerceRules.Refusal) e).tip())
                .isEqualTo(CommerceRules.TEXT_CHECK_UNAVAILABLE);
    }
}
