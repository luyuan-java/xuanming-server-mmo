package com.game.battle.room;

import static com.game.battle.room.RoomHarness.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 真房间与真引擎的五个下发出口，避免只测裁剪 helper 却漏掉一条生产调用。 */
class EquipmentVisibilityTest {
    @Test
    void 开战回合补拉观战首帧观战回合均无combat_引擎内部仍保留() {
        var h = new RoomHarness();
        long battleId = 97001;
        var request = h.pvp(battleId, A, B);
        for (var player : request.getPlayersBuilderList()) {
            player.getCombatBuilder().setPhysicalCritRate(100).setComboRate(100).setResistAllAilment(87);
        }
        h.create(request);
        for (long player : List.of(A, B)) {
            var starts = h.lobbyFrames(player).stream().filter(frame -> frame.getMessageId() == 143).toList();
            assertThat(starts).singleElement().satisfies(frame -> clean(parse(BattleStartS2C.parser(), frame).getState()));
        }
        var a = h.connect(battleId, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        h.connect(battleId, B, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        assertThat(h.addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(C)
                .setRouting(observerRouting(C, 900)).build()).hasErrorMessage()).isFalse();
        var observer = h.connect(battleId, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        assertThat(observer.parsed(161, SpectateStateS2C.parser())).singleElement().satisfies(frame -> clean(frame.getState()));
        for (long player : List.of(A, C)) clean(h.service().getState(player, GetBattleStateRequest.newBuilder().setBattleId(battleId).build()));
        assertThat(h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(action(eBattleActionType.BATTLE_ACTION_ATTACK, B)).build()).hasErrorMessage()).isFalse();
        assertThat(h.service().submit(B, SubmitBattleActionRequest.newBuilder().setBattleId(battleId)
                .setAction(action(eBattleActionType.BATTLE_ACTION_DEFEND, A)).build()).hasErrorMessage()).isFalse();
        assertThat(a.parsed(139, TurnResultS2C.parser())).singleElement().satisfies(frame -> {
            clean(frame.getState());
            assertThat(frame.getEventsList()).anyMatch(e -> e.getHitKind() == eBattleHitKind.BATTLE_HIT_COMBO);
        });
        assertThat(observer.parsed(158, TurnResultS2C.parser())).singleElement().satisfies(frame -> clean(frame.getState()));
        assertThat(h.service().room(battleId).engine.buildStateSnapshot().getActorsList()).allMatch(BattleActorState::hasCombat);
    }

    private static void clean(BattleStateS2C state) {
        assertThat(state.getActorsList()).hasSize(2).noneMatch(BattleActorState::hasCombat);
    }
}
