package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.D;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.room.RoomHarness.FakeLink;
import com.game.battle.testing.FakeBattleData;
import com.game.proto.AddObserverRequest;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleTicketRole;
import org.junit.jupiter.api.Test;

/** 直连上的四条客户端 RPC（基线 {@code room.cpp:672-772}、{@code :931-1020}；battle-node-spec §5.2–§5.5、§13.2）。 */
class ClientActionsTest {

    private final RoomHarness h = new RoomHarness();

    private void observe(long battleId, long observerId) {
        h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerId)
                .setRouting(RoomHarness.observerRouting(observerId, 900)).build());
    }

    private SubmitBattleActionResponse submit(long playerId, long battleId, BattleAction action) {
        return h.service().submit(playerId, SubmitBattleActionRequest.newBuilder().setBattleId(battleId).setAction(action).build());
    }

    private BattleStateS2C state(long playerId, long battleId) {
        return h.service().getState(playerId, GetBattleStateRequest.newBuilder().setBattleId(battleId).build());
    }

    private SetAutoBattleResponse setAuto(long playerId, long battleId, boolean enabled) {
        return h.service().setAuto(playerId, SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(enabled).build());
    }

    private StopWatchBattleResponse stopWatch(long playerId, long battleId) {
        return h.service().stopWatch(playerId, StopWatchBattleRequest.newBuilder().setBattleId(battleId).build());
    }

    private static BattleActorState actor(BattleStateS2C state, long actorId) {
        return state.getActorsList().stream().filter(a -> a.getActorId() == actorId).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- 149

    @Test
    void 提交_1012_房间不存在1005_非参战者与观众1005() {
        h.create(h.pvp(6001, A, B));
        observe(6001, C);
        BattleAction attack = RoomHarness.action(eBattleActionType.BATTLE_ACTION_ATTACK, B);

        assertThat(submit(0, 6001, attack).getErrorMessage().getId()).isEqualTo(1012);
        assertThat(submit(A, 404, attack).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(submit(C, 6001, attack).getErrorMessage().getId()).as("观众也不是参战者").isEqualTo(1005);
        assertThat(submit(D, 6001, attack).getErrorMessage().getId()).isEqualTo(1005);
    }

    @Test
    void 提交_引擎tip原样回_同回合此前收下的合法行动仍有效() {
        h.create(h.pvp(6002, A, B));
        BattleRoom room = h.service().room(6002);
        BattleAction none = RoomHarness.action(eBattleActionType.BATTLE_ACTION_NONE, 0);
        BattleAction unknownSkill = RoomHarness.skill(999, B);
        int expected = room.engine.validateAction(A, unknownSkill);

        assertThat(submit(A, 6002, none).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(expected).isNotEqualTo(1000);
        assertThat(submit(A, 6002, unknownSkill).getErrorMessage().getId()).isEqualTo(expected);

        assertThat(submit(A, 6002, RoomHarness.action(eBattleActionType.BATTLE_ACTION_ATTACK, B)).hasErrorMessage()).isFalse();
        assertThat(submit(A, 6002, none).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(state(A, 6002).getPendingActorIdsList()).as("B3：A 的合法行动仍在").containsExactly(B);
    }

    @Test
    void 提交成功时应答体为空() {
        h.create(h.pvp(6003, A, B));

        SubmitBattleActionResponse response = submit(A, 6003, RoomHarness.action(eBattleActionType.BATTLE_ACTION_DEFEND, 0));

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.toByteArray()).isEmpty();
    }

    // ---------------------------------------------------------------- 140

    @Test
    void 补拉_非成员与房间不存在回空状态() {
        h.create(h.pvp(6004, A, B));

        assertThat(state(D, 6004)).isEqualTo(BattleStateS2C.getDefaultInstance());
        assertThat(state(A, 404).toByteArray()).isEmpty();
        assertThat(state(0, 6004).getBattleId()).isZero();
    }

    @Test
    void 补拉_参战者只见本人冷却且带本人道具_观众全清() {
        h.create(h.pvp(6005, A, B));
        observe(6005, C);
        submit(A, 6005, RoomHarness.skill(FakeBattleData.SKILL_DAMAGE, B));
        submit(B, 6005, RoomHarness.skill(FakeBattleData.SKILL_DAMAGE, A));
        long deadline = h.service().room(6005).actionDeadlineMs;

        BattleStateS2C forA = state(A, 6005);
        BattleStateS2C forC = state(C, 6005);

        assertThat(forA.getBattleId()).isEqualTo(6005);
        assertThat(forA.getActionDeadlineMs()).isEqualTo(deadline);
        assertThat(actor(forA, A).getSkillCooldownRoundsMap()).containsKey(FakeBattleData.SKILL_DAMAGE);
        assertThat(actor(forA, B).getSkillCooldownRoundsMap()).isEmpty();
        assertThat(forA.getSelfItemsList()).hasSize(1);
        assertThat(forC.getActorsList()).allSatisfy(x -> assertThat(x.getSkillCooldownRoundsMap()).isEmpty());
        assertThat(forC.getSelfItemsList()).isEmpty();
        assertThat(forC.getActionDeadlineMs()).isEqualTo(deadline);
    }

    // ---------------------------------------------------------------- 162

    @Test
    void 自动_1012_1005_观众1005_已死1009() {
        CreateBattleRequest.Builder request = h.pvp(6006, A, B).addPlayers(RoomHarness.player(D, 0, 1_000_000, 80));
        request.getPlayersBuilder(0).setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(10).setSpeed(100)).setMaxHealth(10);
        h.create(request);
        observe(6006, C);

        assertThat(setAuto(0, 6006, true).getErrorMessage().getId()).isEqualTo(1012);
        assertThat(setAuto(A, 404, true).getErrorMessage().getId()).isEqualTo(1005);
        assertThat(setAuto(C, 6006, true).getErrorMessage().getId()).as("观众不能切自动").isEqualTo(1005);

        // B 一发必杀打死 A（2V1，A 死后 D 还在，房间继续）
        submit(B, 6006, RoomHarness.skill(FakeBattleData.SKILL_NUKE, A));
        h.scheduler.advance(6000);
        assertThat(h.service().room(6006)).isNotNull();
        assertThat(actor(state(A, 6006), A).getIsDead()).isTrue();
        assertThat(setAuto(A, 6006, true).getErrorMessage().getId()).isEqualTo(1009);
    }

    @Test
    void 自动_成功时应答体为空_翻转立即结算() {
        h.create(h.pve(6007, A).setBattleConfigId(RoomHarness.DUNGEON_TANK));
        FakeLink a = h.connect(6007, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        SetAutoBattleResponse response = setAuto(A, 6007, true);

        assertThat(response.toByteArray()).isEmpty();
        assertThat(a.messageIds()).containsExactly(139);
        assertThat(setAuto(A, 6007, true).hasErrorMessage()).isFalse();
        assertThat(a.messageIds()).as("全自动时重复开启不结算").hasSize(1);
    }

    // ---------------------------------------------------------------- 165

    @Test
    void 退出观战_房间不在与非观众都回成功_观众被移除并关闭_不推166() {
        h.create(h.pvp(6008, A, B));
        observe(6008, C);
        FakeLink c = h.connect(6008, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.clearLog();

        assertThat(stopWatch(C, 404).hasErrorMessage()).isFalse();
        assertThat(stopWatch(A, 6008).hasErrorMessage()).isFalse();
        assertThat(stopWatch(0, 6008).getErrorMessage().getId()).isEqualTo(1012);
        assertThat(h.log).isEmpty();

        StopWatchBattleResponse response = stopWatch(C, 6008);

        assertThat(response.toByteArray()).isEmpty();
        assertThat(h.trace()).as("O8：只有 FIN，没有 166").containsExactly("close:5003:battle_closed");
        assertThat(h.service().room(6008).isObserver(C)).isFalse();
        assertThat(state(C, 6008).getBattleId()).as("已不是成员").isZero();
        assertThat(c.closedWith).isNotNull();
    }
}
