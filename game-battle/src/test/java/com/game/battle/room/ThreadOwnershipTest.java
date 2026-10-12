package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.AddObserverRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.eBattleTicketRole;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** 线程所有权：房间服务只许在逻辑线程上调用，roomCount 任意线程可读（battle-node-spec §7.3、§13.2）。 */
class ThreadOwnershipTest {

    @Test
    void 从别的线程调用房间服务一律断言失败() {
        RoomHarness h = new RoomHarness();
        BattleRoomService service = h.service();
        h.create(h.pvp(11001, A, B));
        h.scheduler.setInLoop(false);

        List<ThrowingCallable> calls = List.of(
                () -> service.createBattle(h.pve(11002, A).build(), RoomOrigin.MATCH),
                () -> service.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(11001).build()),
                () -> service.abortAll("test"),
                () -> service.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(11001).setPlayerId(A).build()),
                () -> service.addObserver(AddObserverRequest.newBuilder().setBattleId(11001).build()),
                () -> service.removeObserver(RemoveObserverRequest.newBuilder().setBattleId(11001).build()),
                () -> service.attachDirect(11001, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, null),
                () -> service.onDirectVerified(11001, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT),
                () -> service.detachDirect(11001, A, null),
                () -> service.submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(11001).build()),
                () -> service.getState(A, GetBattleStateRequest.newBuilder().setBattleId(11001).build()),
                () -> service.setAuto(A, SetAutoBattleRequest.newBuilder().setBattleId(11001).build()),
                () -> service.stopWatch(A, StopWatchBattleRequest.newBuilder().setBattleId(11001).build()));
        for (ThrowingCallable call : calls) {
            assertThatThrownBy(call).isInstanceOf(IllegalStateException.class).hasMessageContaining("逻辑线程");
        }

        assertThat(service.roomCount()).as("任意线程可读").isEqualTo(1);
    }
}
