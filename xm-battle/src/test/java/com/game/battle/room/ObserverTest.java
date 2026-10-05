package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.room.RoomHarness.FakeLink;
import com.game.common.token.BattleTickets;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleRouting;
import com.game.proto.MessageContent;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.SpectateEndS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

/** 观战的房间侧：AddObserver / RemoveObserver（基线 {@code room.cpp:774-929}；battle-node-spec §5.5、Q1、§13.2）。 */
class ObserverTest {

    private final RoomHarness h = new RoomHarness();

    private AddObserverResponse add(long battleId, long observerId, BattleRouting routing) {
        return h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerId)
                .setRouting(routing).setObserverName("观众" + observerId).build());
    }

    private AddObserverResponse add(long battleId, long observerId) {
        return add(battleId, observerId, RoomHarness.observerRouting(observerId, 900));
    }

    @Test
    void 判定顺序_房间不在1004_observer为0或gate实例为空1005_参战者1005() {
        h.create(h.pvp(9001, A, B));

        assertThat(add(404, C).getErrorMessage().getId()).as("match 据此懒剔除索引").isEqualTo(1004);
        assertThat(add(9001, 0).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(add(9001, C, RoomHarness.observerRouting(C, 900).toBuilder().clearGateInstanceId().build()).getErrorMessage().getId())
                .isEqualTo(1005);
        assertThat(add(9001, A).getErrorMessage().getId()).as("单槽互斥").isEqualTo(1005);
        assertThat(h.service().room(9001).routingByObserver).isEmpty();
    }

    @Test
    void 新观众签票登记推177_满20回1008且排在幂等之后() throws Exception {
        h.create(h.pvp(9002, A, B));
        h.clearLog();

        assertThat(add(9002, C).hasErrorMessage()).isFalse();
        assertThat(h.trace()).containsExactly("lobby:5003:177");
        BattleAssignedS2C assigned = RoomHarness.parse(BattleAssignedS2C.parser(), h.lobbyFrames(C).get(0));
        assertThat(assigned.getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        assertThat(assigned.getExpireAtMs()).isEqualTo(h.service().room(9002).deadlineMs);

        for (long observer = 6000; observer < 6019; observer++) {
            assertThat(add(9002, observer).hasErrorMessage()).isFalse();
        }
        assertThat(h.service().room(9002).routingByObserver).hasSize(20);
        assertThat(add(9002, 7000).getErrorMessage().getId()).isEqualTo(1008);
        assertThat(add(9002, C).hasErrorMessage()).as("已登记的观众幂等重试不受上限影响").isFalse();
        assertThat(h.counter("xm.battle.tickets", "path", "observer", "result", "ok")).isEqualTo(21);
    }

    @Test
    void 新观众签票失败回1003且不登记() {
        BattleTickets real = BattleTickets.ofUtf8(RoomHarness.SECRET);
        BattleTickets flaky = mock(BattleTickets.class);
        when(flaky.sign(any())).thenAnswer(invocation -> real.sign(invocation.getArgument(0, ByteString.class)));
        h.tickets = flaky;
        h.create(h.pvp(9003, A, B));
        doThrow(new IllegalStateException("测试")).when(flaky).sign(any());
        h.clearLog();

        assertThat(add(9003, C).getErrorMessage().getId()).isEqualTo(1003);
        assertThat(h.service().room(9003).isObserver(C)).isFalse();
        assertThat(h.log).isEmpty();
    }

    @Test
    void 幂等同会话_无直连只重推177_有活直连时177与161都走直连() {
        h.create(h.pvp(9004, A, B));
        add(9004, C);
        h.clearLog();

        assertThat(add(9004, C).hasErrorMessage()).isFalse();
        assertThat(h.trace()).as("161 是战斗帧，没直连就丢弃").containsExactly("lobby:5003:177");
        assertThat(h.counter("xm.battle.pushes", "category", "battle_frame", "route", "dropped", "message", "NotifySpectateState"))
                .isEqualTo(1);

        FakeLink c = h.connect(9004, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.clearLog();
        assertThat(add(9004, C).hasErrorMessage()).isFalse();
        assertThat(h.trace()).containsExactly("frame:5003:177", "frame:5003:161");
        assertThat(c.closedWith).isNull();
    }

    @Test
    void 幂等但会话变了_关旧直连_重推177经gate_不推161() {
        h.create(h.pvp(9005, A, B));
        add(9005, C);
        FakeLink old = h.connect(9005, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.clearLog();

        BattleRouting moved = RoomHarness.observerRouting(C, 901);
        assertThat(add(9005, C, moved).hasErrorMessage()).isFalse();

        assertThat(h.trace()).containsExactly("close:5003:battle_closed", "lobby:5003:177");
        assertThat(old.closedWith).isEqualTo(Disconnect.BATTLE_CLOSED);
        assertThat(h.service().room(9005).routingByObserver.get(C)).isEqualTo(moved);
    }

    @Test
    void 幂等路径签票失败_摘除观众并关直连_回1003() {
        BattleTickets real = BattleTickets.ofUtf8(RoomHarness.SECRET);
        BattleTickets flaky = mock(BattleTickets.class);
        when(flaky.sign(any())).thenAnswer(invocation -> real.sign(invocation.getArgument(0, ByteString.class)));
        h.tickets = flaky;
        h.create(h.pvp(9006, A, B));
        add(9006, C);
        FakeLink c = h.connect(9006, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        doThrow(new IllegalStateException("测试")).when(flaky).sign(any());
        h.clearLog();

        assertThat(add(9006, C).getErrorMessage().getId()).isEqualTo(1003);

        assertThat(h.service().room(9006).isObserver(C)).isFalse();
        assertThat(h.trace()).containsExactly("close:5003:battle_closed");
        assertThat(c.closedWith).isEqualTo(Disconnect.BATTLE_CLOSED);
    }

    @Test
    void 清退_推166为REMOVED加ONGOING后关闭_不在时什么也不做() {
        h.create(h.pvp(9007, A, B));
        add(9007, C);
        FakeLink c = h.connect(9007, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.clearLog();

        h.service().removeObserver(RemoveObserverRequest.newBuilder().setBattleId(9007).setObserverPlayerId(A).build());
        h.service().removeObserver(RemoveObserverRequest.newBuilder().setBattleId(404).setObserverPlayerId(C).build());
        assertThat(h.log).isEmpty();

        h.service().removeObserver(RemoveObserverRequest.newBuilder().setBattleId(9007).setObserverPlayerId(C).setReason("queue").build());

        assertThat(h.trace()).containsExactly("frame:5003:166", "close:5003:battle_closed");
        SpectateEndS2C end = c.parsed(166, SpectateEndS2C.parser()).get(0);
        assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_REMOVED);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        assertThat(h.service().room(9007).isObserver(C)).isFalse();
    }

    @Test
    void 观众每回合收158_全员冷却清空() {
        h.create(h.pvp(9008, A, B));
        add(9008, C);
        FakeLink c = h.connect(9008, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);

        h.scheduler.advance(6000);
        h.scheduler.advance(6000);

        assertThat(c.messageIds()).containsExactly(161, 158, 158);
        for (MessageContent frame : c.frames) {
            assertThat(frame.getId()).isZero();
        }
    }
}
