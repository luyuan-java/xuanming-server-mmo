package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.token.BattleTickets;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.google.protobuf.ByteString;
import com.game.proto.eBattleTicketRole;
import org.junit.jupiter.api.Test;

/** IssueBattleTicket 补签（基线 {@code room.cpp:2033-2081}；battle-node-spec §2.6、§13.2）。 */
class IssueTicketTest {

    private final RoomHarness h = new RoomHarness();

    private IssueBattleTicketResponse issue(long battleId, long playerId) {
        return h.service().issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(battleId).setPlayerId(playerId).build());
    }

    @Test
    void player为0_房间不在_非成员都回1005且没有assignment() {
        h.create(h.pvp(8001, A, B));

        for (IssueBattleTicketResponse response : new IssueBattleTicketResponse[] {issue(8001, 0), issue(404, A), issue(8001, D)}) {
            assertThat(response.getErrorMessage().getId()).isEqualTo(1005);
            assertThat(response.hasAssignment()).isFalse();
        }
    }

    @Test
    void 成功_字段正确且与开局票逐字节相同() {
        h.create(h.pvp(8002, A, B));
        BattleAssignedS2C original = RoomHarness.parse(BattleAssignedS2C.parser(), h.lobbyFrames(A).get(0));

        IssueBattleTicketResponse response = issue(8002, A);

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getAssignment().getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(response.getAssignment().toByteString()).isEqualTo(original.toByteString());
        assertThat(h.counter("xm.battle.tickets", "path", "reissue", "result", "ok")).isEqualTo(1);
    }

    @Test
    void 观众补签得到观众票_两个名单都有时参战者优先() {
        h.create(h.pvp(8003, A, B));
        h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(8003).setObserverPlayerId(C)
                .setRouting(RoomHarness.observerRouting(C, 900)).build());

        assertThat(issue(8003, C).getAssignment().getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);

        // 正常路径到不了（AddObserver 拒绝参战者）；白盒放进去验证优先级
        h.service().room(8003).routingByObserver.put(A, RoomHarness.observerRouting(A, 901));
        assertThat(issue(8003, A).getAssignment().getRole()).isEqualTo(eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
    }

    @Test
    void 签票失败回1003且没有assignment() {
        BattleTickets real = BattleTickets.ofUtf8(RoomHarness.SECRET);
        BattleTickets flaky = mock(BattleTickets.class);
        when(flaky.sign(any())).thenAnswer(invocation -> real.sign(invocation.getArgument(0, ByteString.class)));
        h.tickets = flaky;
        h.create(h.pvp(8004, A, B));
        doThrow(new IllegalStateException("测试：JCA 故障")).when(flaky).sign(any());

        IssueBattleTicketResponse response = issue(8004, A);

        assertThat(response.getErrorMessage().getId()).isEqualTo(1003);
        assertThat(response.hasAssignment()).isFalse();
        assertThat(h.counter("xm.battle.tickets", "path", "reissue", "result", "failed")).isEqualTo(1);
    }
}
