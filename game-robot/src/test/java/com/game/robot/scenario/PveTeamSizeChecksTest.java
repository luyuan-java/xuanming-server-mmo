package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.*;

import com.game.proto.BattleActorState;
import com.game.proto.BattleStateS2C;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PveTeamSizeChecksTest {
    private BattleStateS2C state(List<Long> players, int monsters) {
        var state = BattleStateS2C.newBuilder();
        for (long player : players) state.addActors(BattleActorState.newBuilder().setActorId(player).setActorTypeValue(1).setTeamIndex(0));
        for (int i = 0; i < monsters; i++) state.addActors(BattleActorState.newBuilder().setActorId(Long.MIN_VALUE + i)
                .setActorTypeValue(2).setTeamIndex(1).setMonsterTableId(1));
        return state.build();
    }

    @Test void 人数档包括uint32高半区原样发送_每次操作独立UUID重试可复用原请求() {
        for (int size : new int[] {0, 1, 2, 5, 6, Integer.MIN_VALUE, -1}) {
            String original = UUID.randomUUID().toString();
            var request = PveTeamSizeChecks.request(7, size, original, "plan-1");
            assertThat(request.getTeamSize()).isEqualTo(size);
            assertThat(request.getMode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
            assertThat(request.getBattleConfigId()).isEqualTo(1);
            assertThat(request.getRequestId()).isEqualTo(original);
            assertThat(request.getNetworkPlanId()).isEqualTo("plan-1");
            assertThat(PveTeamSizeChecks.request(7, size, original, "plan-1")).isEqualTo(request);
        }
        assertThatThrownBy(() -> PveTeamSizeChecks.request(7, 2, "", "plan")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PveTeamSizeChecks.request(7, 2, "00000000-0000-0000-0000-000000000000", "plan"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PveTeamSizeChecks.request(7, 2, UUID.randomUUID().toString(), "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void 超限拒绝同时核错误码文案与无票_非法UUID或依赖失败不能冒充通过() {
        var rejected = JoinQueueResponse.newBuilder().setErrorCode(BattleSmokeChecks.TIP_MODE_NOT_OPEN)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(BattleSmokeChecks.TIP_MODE_NOT_OPEN)
                        .addParameters(PveTeamSizeChecks.OVER_LIMIT_TEXT)).build();
        assertThat(PveTeamSizeChecks.overLimitProblem(rejected)).isEmpty();
        for (var invalid : List.of(rejected.toBuilder().clearErrorCode().build(), rejected.toBuilder().setQueueTicket("ticket").build(),
                rejected.toBuilder().setErrorMessage(rejected.getErrorMessage().toBuilder().setId(1005)).build(),
                rejected.toBuilder().setErrorMessage(rejected.getErrorMessage().toBuilder().clearParameters()).build()))
            assertThat(PveTeamSizeChecks.overLimitProblem(invalid)).isNotEmpty();
    }

    @Test void NOT_QUEUED单独不是原意图撤销证明_有新票也不得误清() {
        var cancelled = GetQueueStatusResponse.newBuilder().setState(QueueState.QUEUE_STATE_NOT_QUEUED).setRequestCancelled(true).build();
        assertThat(PveTeamSizeChecks.cancelledProblem(cancelled)).isEmpty();
        for (var invalid : List.of(cancelled.toBuilder().clearRequestCancelled().build(), cancelled.toBuilder().setQueueTicket("new-ticket").build(),
                cancelled.toBuilder().setBattleId(99).build(), cancelled.toBuilder().setState(QueueState.QUEUE_STATE_QUEUED).build()))
            assertThat(PveTeamSizeChecks.cancelledProblem(invalid)).isNotEmpty();
    }

    @Test void 一二五人实际战场分别两四十怪_宝宝不充当玩家或怪物() {
        for (var players : List.of(List.of(7L), List.of(7L, 8L), List.of(7L, 8L, 9L, 10L, 11L))) {
            var state = state(players, players.size() * 2);
            assertThat(PveTeamSizeChecks.formationProblem(state, players, players.size() * 2)).isEmpty();
            var withPet = state.toBuilder().addActors(BattleActorState.newBuilder().setActorId(100).setActorTypeValue(3)
                    .setOwnerPlayerId(7).setTeamIndex(0)).build();
            assertThat(PveTeamSizeChecks.formationProblem(withPet, players, players.size() * 2)).isEmpty();
        }
    }

    @Test void 缺人串档错误怪物数与重复单位都不能通过() {
        var players = List.of(7L, 8L);
        var state = state(players, 4);
        for (var invalid : List.of(state(players, 2), state(players, 6), state(List.of(7L), 4), state(List.of(7L, 9L), 4),
                state.toBuilder().addActors(state.getActors(0)).build(),
                state.toBuilder().setActors(0, state.getActors(0).toBuilder().setTeamIndex(1)).build(),
                state.toBuilder().setActors(2, state.getActors(2).toBuilder().setTeamIndex(0)).build(),
                state.toBuilder().addActors(BattleActorState.newBuilder().setActorId(100).setActorTypeValue(3).setOwnerPlayerId(99)).build()))
            assertThat(PveTeamSizeChecks.formationProblem(invalid, players, 4)).isNotEmpty();
    }

    @Test void 五个账号后缀一位且长度固定() {
        for (int i = 0; i < PveTeamSizeScenario.PLAYERS; i++) {
            assertThat(PveTeamSizeScenario.accountName("robot_", "run", i)).isEqualTo("robot_psrun_" + i);
            assertThat(PveTeamSizeScenario.accountName("robot_", "run", Integer.toString(i)))
                    .isEqualTo(PveTeamSizeScenario.accountName("robot_", "run", i));
        }
        assertThatThrownBy(() -> PveTeamSizeScenario.accountName("p", "r", "a")).isInstanceOf(IllegalArgumentException.class);
    }
}
