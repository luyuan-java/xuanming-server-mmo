package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.battle.room.RoomHarness.FakeLink;
import com.game.common.token.BattleTickets;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleTicketPayload;
import com.game.proto.CreateBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 未成年人防沉迷到点断开战斗直连（对齐 C++ {@code battle_minor_play_deadline_test.cpp} 与
 * {@code battle_observer_contract_test.cpp} {@code MinorPlayDeadline*}）。
 */
class BattleMinorPlayDeadlineTest {

    private final RoomHarness h = new RoomHarness();

    @Test
    void 纯函数_提前5秒封顶_不足提前量不回绕_零不限_与票据过期同刻翻转() {
        long deadline = 1_795_784_400_000L;
        long closeAt = deadline - BattleMinorPlayDeadline.CLOSE_LEAD_MS;

        assertThat(BattleMinorPlayDeadline.CLOSE_LEAD_MS).isEqualTo(5_000L);
        assertThat(BattleMinorPlayDeadline.directCloseAtMs(deadline)).isEqualTo(closeAt);
        assertThat(BattleMinorPlayDeadline.directCloseAtMs(BattleMinorPlayDeadline.CLOSE_LEAD_MS)).isZero();
        assertThat(BattleMinorPlayDeadline.directCloseAtMs(1L)).isZero();
        assertThat(BattleMinorPlayDeadline.reached(1L, 0L)).isTrue();

        assertThat(BattleMinorPlayDeadline.reached(0L, 0L)).isFalse();
        assertThat(BattleMinorPlayDeadline.reached(0L, -1L)).isFalse();
        assertThat(BattleMinorPlayDeadline.ticketExpireAtMs(deadline + 1L, 0L)).isEqualTo(deadline + 1L);

        assertThat(BattleMinorPlayDeadline.reached(deadline, closeAt - 1L)).isFalse();
        assertThat(BattleMinorPlayDeadline.reached(deadline, closeAt)).isTrue();
        assertThat(BattleMinorPlayDeadline.ticketExpireAtMs(deadline + 300_000L, deadline)).isEqualTo(closeAt);
        assertThat(BattleMinorPlayDeadline.ticketExpireAtMs(closeAt - 60_000L, deadline)).isEqualTo(closeAt - 60_000L);

        BattleTicketPayload payload = BattleTicketPayload.newBuilder()
                .setBattleId(65047318352658432L)
                .setPlayerId(9001L)
                .setBattleNodeId(RoomHarness.NODE_ID)
                .setBattleInstanceId(RoomHarness.INSTANCE)
                .setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT)
                .setExpireAtMs(BattleMinorPlayDeadline.ticketExpireAtMs(deadline + 300_000L, deadline))
                .build();
        assertThat(BattleTickets.classify(payload, RoomHarness.NODE_ID, RoomHarness.INSTANCE, closeAt - 1L))
                .isEqualTo(BattleTickets.Verdict.OK);
        assertThat(BattleTickets.classify(payload, RoomHarness.NODE_ID, RoomHarness.INSTANCE, closeAt))
                .isEqualTo(BattleTickets.Verdict.EXPIRED);
    }

    @Test
    void 参战者与观众票据均按直连截止时刻封顶_成年人及更晚截止不越过房间期限() throws Exception {
        long playDeadline = h.now() + 60_000L;
        long closeAt = playDeadline - BattleMinorPlayDeadline.CLOSE_LEAD_MS;
        CreateBattleRequest.Builder req = h.pvp(9101L, A, B);
        req.getPlayersBuilder(0).setMinorPlayDeadlineMs(playDeadline);
        h.create(req);
        long roomDeadline = h.service().room(9101L).deadlineMs;

        // 参战者首签 + 补签均按 closeAt 封顶，成年对手维持 roomDeadline
        BattleAssignedS2C minorAssigned = RoomHarness.parse(BattleAssignedS2C.parser(), h.lobbyFrames(A).get(0));
        BattleAssignedS2C adultAssigned = RoomHarness.parse(BattleAssignedS2C.parser(), h.lobbyFrames(B).get(0));
        assertThat(minorAssigned.getExpireAtMs()).isEqualTo(closeAt);
        assertThat(BattleTicketPayload.parseFrom(minorAssigned.getTokenPayload()).getExpireAtMs()).isEqualTo(closeAt);
        assertThat(adultAssigned.getExpireAtMs()).isEqualTo(roomDeadline);

        IssueBattleTicketResponse reissuedMinor = h.service().issueBattleTicket(
                IssueBattleTicketRequest.newBuilder().setBattleId(9101L).setPlayerId(A).build());
        assertThat(reissuedMinor.getAssignment().getExpireAtMs()).isEqualTo(closeAt);

        // 未成年观众封顶，成年观众不封顶
        String minorOp = UUID.randomUUID().toString();
        assertThat(h.service().addObserver(AddObserverRequest.newBuilder()
                .setBattleId(9101L).setObserverPlayerId(C).setRouting(RoomHarness.observerRouting(C, 901))
                .setObserverName("未成年观众").setSpectatorOperationId(minorOp).setAdmitBeforeMs(h.now() + 30_000L)
                .setTargetInstanceId(RoomHarness.INSTANCE).setMinorPlayDeadlineMs(playDeadline).build())
                .hasErrorMessage()).isFalse();
        IssueBattleTicketResponse minorObsTicket = h.service().issueBattleTicket(
                IssueBattleTicketRequest.newBuilder().setBattleId(9101L).setPlayerId(C)
                        .setTargetInstanceId(RoomHarness.INSTANCE).setSpectatorOperationId(minorOp).build());
        assertThat(minorObsTicket.getAssignment().getExpireAtMs()).isEqualTo(closeAt);

        String adultOp = UUID.randomUUID().toString();
        assertThat(h.service().addObserver(AddObserverRequest.newBuilder()
                .setBattleId(9101L).setObserverPlayerId(D).setRouting(RoomHarness.observerRouting(D, 902))
                .setObserverName("成年观众").setSpectatorOperationId(adultOp).setAdmitBeforeMs(h.now() + 30_000L)
                .setTargetInstanceId(RoomHarness.INSTANCE).build())
                .hasErrorMessage()).isFalse();
        IssueBattleTicketResponse adultObsTicket = h.service().issueBattleTicket(
                IssueBattleTicketRequest.newBuilder().setBattleId(9101L).setPlayerId(D)
                        .setTargetInstanceId(RoomHarness.INSTANCE).setSpectatorOperationId(adultOp).build());
        assertThat(adultObsTicket.getAssignment().getExpireAtMs()).isEqualTo(roomDeadline);

        // 截止晚于房间期限：不拉长票据期限
        CreateBattleRequest.Builder later = h.pvp(9102L, A, B);
        later.getPlayersBuilder(0).setMinorPlayDeadlineMs(roomDeadline + 3_600_000L);
        h.create(later);
        long laterRoomDeadline = h.service().room(9102L).deadlineMs;
        IssueBattleTicketResponse laterTicket = h.service().issueBattleTicket(
                IssueBattleTicketRequest.newBuilder().setBattleId(9102L).setPlayerId(A).build());
        assertThat(laterTicket.getAssignment().getExpireAtMs()).isEqualTo(laterRoomDeadline);
    }

    @Test
    void 到点仅关闭未成年参战者直连_此后拒绝重连含抗时钟回拨_对手与战斗照常() {
        long playDeadline = h.now() + 60_000L;
        long closeAt = playDeadline - BattleMinorPlayDeadline.CLOSE_LEAD_MS;
        CreateBattleRequest.Builder req = h.pvp(9103L, A, B);
        req.getPlayersBuilder(0).setMinorPlayDeadlineMs(playDeadline);
        h.create(req);

        FakeLink minorLink = h.connect(9103L, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink adultLink = h.connect(9103L, B, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        // 到点前一毫秒：双方均不断
        h.scheduler.advance(closeAt - h.now() - 1L);
        assertThat(minorLink.live).isTrue();
        assertThat(adultLink.live).isTrue();

        // 到达 closeAt：定时器精准强关未成年人直连，对手保持在线
        h.scheduler.advance(1L);
        assertThat(minorLink.live).isFalse();
        assertThat(minorLink.killed).isTrue();
        assertThat(adultLink.live).isTrue();

        // 模拟直连面断开回调解绑
        h.service().detachDirect(9103L, A, minorLink);

        // 之后换新连接握手被拒（即使时间未变或倒退也依赖单调 ended 标记拒接）
        FakeLink retry = h.new FakeLink(A);
        assertThat(h.service().attachDirect(9103L, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, retry)).isEmpty();

        // 成年对手重连与出招照常，房间仍存活
        FakeLink adultReconnect = h.connect(9103L, B, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(adultReconnect.live).isTrue();
        assertThat(h.service().setAuto(B, SetAutoBattleRequest.newBuilder().setBattleId(9103L).setEnabled(true).build())
                .hasErrorMessage()).isFalse();
        assertThat(h.service().containsBattle(9103L)).isTrue();
    }

    @Test
    void 建房时已到点仍正常开局_未成年人首连即拒_成年对手照常挂接() {
        CreateBattleRequest.Builder req = h.pvp(9104L, A, B);
        req.getPlayersBuilder(0).setMinorPlayDeadlineMs(h.now() + 1_000L);
        h.create(req);

        assertThat(h.service().attachDirect(9104L, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, h.new FakeLink(A)))
                .isEmpty();
        IssueBattleTicketResponse ticket = h.service().issueBattleTicket(
                IssueBattleTicketRequest.newBuilder().setBattleId(9104L).setPlayerId(A).build());
        assertThat(Long.compareUnsigned(ticket.getAssignment().getExpireAtMs(), h.now())).isLessThan(0);

        FakeLink adult = h.connect(9104L, B, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(adult.live).isTrue();
    }

    @Test
    void 未成年观众到点断直连_宽限期内拒重连_期满回收席位_成年观众不受影响() {
        h.create(h.pvp(9105L, A, B));
        long playDeadline = h.now() + 60_000L;
        long closeAt = playDeadline - BattleMinorPlayDeadline.CLOSE_LEAD_MS;
        String minorOp = UUID.randomUUID().toString();
        String adultOp = UUID.randomUUID().toString();

        assertThat(h.service().addObserver(AddObserverRequest.newBuilder()
                .setBattleId(9105L).setObserverPlayerId(C).setRouting(RoomHarness.observerRouting(C, 901))
                .setObserverName("未成年观众").setSpectatorOperationId(minorOp).setAdmitBeforeMs(h.now() + 30_000L)
                .setTargetInstanceId(RoomHarness.INSTANCE).setMinorPlayDeadlineMs(playDeadline).build())
                .hasErrorMessage()).isFalse();
        assertThat(h.service().addObserver(AddObserverRequest.newBuilder()
                .setBattleId(9105L).setObserverPlayerId(D).setRouting(RoomHarness.observerRouting(D, 902))
                .setObserverName("成年观众").setSpectatorOperationId(adultOp).setAdmitBeforeMs(h.now() + 30_000L)
                .setTargetInstanceId(RoomHarness.INSTANCE).build())
                .hasErrorMessage()).isFalse();

        FakeLink minorObs = h.connect(9105L, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        FakeLink adultObs = h.connect(9105L, D, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);

        h.scheduler.advance(closeAt - h.now());
        assertThat(minorObs.live).isFalse();
        assertThat(minorObs.killed).isTrue();
        assertThat(adultObs.live).isTrue();

        // 模拟断线进入重连宽限：宽限期内重连被拒
        h.service().detachDirect(9105L, C, minorObs);
        assertThat(h.service().room(9105L).isObserver(C)).isTrue();
        BattleTicketPayload minorPayload = BattleTicketPayload.newBuilder()
                .setBattleId(9105L).setPlayerId(C).setRole(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER)
                .setBattleInstanceId(RoomHarness.INSTANCE).setSpectatorOperationId(minorOp).build();
        assertThat(h.service().attachDirect(minorPayload, h.new FakeLink(C))).isEmpty();

        // 宽限期满后回收未成年观众席位；成年观众不受影响
        h.scheduler.advance(RoomConstants.OBSERVER_RECONNECT_GRACE_MS);
        assertThat(h.service().room(9105L).isObserver(C)).isFalse();
        assertThat(h.service().room(9105L).isObserver(D)).isTrue();
        assertThat(adultObs.live).isTrue();
    }

    @Test
    void 观众签票失败被拒不残留未成年截止限制() {
        BattleTickets real = BattleTickets.ofUtf8(RoomHarness.SECRET);
        BattleTickets flaky = mock(BattleTickets.class);
        when(flaky.sign(any())).thenAnswer(inv -> real.sign(inv.getArgument(0, ByteString.class)));
        h.tickets = flaky;
        h.create(h.pvp(9106L, A, B));
        long roomDeadline = h.service().room(9106L).deadlineMs;

        doThrow(new IllegalStateException("模拟签票失败")).when(flaky).sign(any());
        String firstOp = UUID.randomUUID().toString();
        assertThat(h.service().addObserver(AddObserverRequest.newBuilder()
                .setBattleId(9106L).setObserverPlayerId(C).setRouting(RoomHarness.observerRouting(C, 901))
                .setObserverName("观众").setSpectatorOperationId(firstOp).setAdmitBeforeMs(h.now() + 30_000L)
                .setTargetInstanceId(RoomHarness.INSTANCE).setMinorPlayDeadlineMs(h.now() + 60_000L).build())
                .getErrorMessage().getId()).isEqualTo(1003);

        // 恢复签票后换新 op 无截止时刻登记：票据期限应回到 roomDeadline，且到点清扫不误关
        doAnswer(inv -> real.sign(inv.getArgument(0, ByteString.class))).when(flaky).sign(any());
        String secondOp = UUID.randomUUID().toString();
        assertThat(h.service().addObserver(AddObserverRequest.newBuilder()
                .setBattleId(9106L).setObserverPlayerId(C).setRouting(RoomHarness.observerRouting(C, 901))
                .setObserverName("观众").setSpectatorOperationId(secondOp).setAdmitBeforeMs(h.now() + 30_000L)
                .setTargetInstanceId(RoomHarness.INSTANCE).build())
                .hasErrorMessage()).isFalse();
        IssueBattleTicketResponse ticket = h.service().issueBattleTicket(
                IssueBattleTicketRequest.newBuilder().setBattleId(9106L).setPlayerId(C)
                        .setTargetInstanceId(RoomHarness.INSTANCE).setSpectatorOperationId(secondOp).build());
        assertThat(ticket.getAssignment().getExpireAtMs()).isEqualTo(roomDeadline);
        FakeLink obs = h.connect(9106L, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.service().enforceMinorPlayDeadlines(9106L, Long.MAX_VALUE);
        assertThat(obs.live).isTrue();
    }
}
