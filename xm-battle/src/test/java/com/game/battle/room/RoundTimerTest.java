package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.D;
import static com.game.battle.room.RoomHarness.MONSTER;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.room.RoomHarness.FakeLink;
import com.game.battle.testing.FakeBattleData;
import com.game.proto.AddObserverRequest;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TurnResultS2C;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 回合计时、三种结算触发与正常收尾（基线 {@code room.cpp:1022-1080}、{@code :1130-1221}；battle-node-spec §4.4、§4.6、§13.2）。 */
class RoundTimerTest {

    private static final int TURN = 139;
    private static final int END = 150;
    private static final int SPECTATE_STATE = 161;
    private static final int SPECTATE_TURN = 158;
    private static final int SPECTATE_END = 166;

    private final RoomHarness h = new RoomHarness();

    private void addObserver(long battleId, long observerId) {
        assertThat(h.service().addObserver(AddObserverRequest.newBuilder()
                .setBattleId(battleId)
                .setObserverPlayerId(observerId)
                .setRouting(RoomHarness.observerRouting(observerId, 900))
                .setObserverName("观众")
                .build()).hasErrorMessage()).isFalse();
    }

    private SubmitBattleActionResponse submit(long battleId, long playerId, com.game.proto.BattleAction action) {
        return h.service().submit(playerId, SubmitBattleActionRequest.newBuilder().setBattleId(battleId).setAction(action).build());
    }

    private void setAuto(long battleId, long playerId, boolean enabled) {
        assertThat(h.service().setAuto(playerId, SetAutoBattleRequest.newBuilder().setBattleId(battleId).setEnabled(enabled).build())
                .hasErrorMessage()).isFalse();
    }

    private double rounds(String trigger) {
        return h.counter("xm.battle.rounds", "trigger", trigger);
    }

    private static BattleActorState actor(TurnResultS2C result, long actorId) {
        return result.getState().getActorsList().stream().filter(a -> a.getActorId() == actorId).findFirst().orElseThrow();
    }

    @Test
    void 窗口6秒到期结算_每个参战者一条裁剪过的139_观众一条158_新截止为结算时刻加6秒() {
        h.create(h.pvp(2001, A, B));
        addObserver(2001, C);
        FakeLink a = h.connect(2001, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink b = h.connect(2001, B, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink c = h.connect(2001, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        // A 放带冷却的技能，B 不提交：窗口到期才结算
        assertThat(submit(2001, A, RoomHarness.skill(FakeBattleData.SKILL_DAMAGE, B)).hasErrorMessage()).isFalse();
        h.clearLog();

        h.scheduler.advance(5999);
        assertThat(h.log).as("窗口没到").isEmpty();
        h.scheduler.advance(1);

        assertThat(h.trace()).containsExactly("frame:5001:139", "frame:5002:139", "frame:5003:158");
        TurnResultS2C forA = a.parsed(TURN, TurnResultS2C.parser()).get(0);
        TurnResultS2C forB = b.parsed(TURN, TurnResultS2C.parser()).get(0);
        TurnResultS2C forC = c.parsed(SPECTATE_TURN, TurnResultS2C.parser()).get(0);
        assertThat(forA.getRoundIndex()).isEqualTo(1);
        assertThat(forA.getState().getRoundIndex()).isEqualTo(2);
        assertThat(forA.getState().getActionDeadlineMs()).isEqualTo(h.now() + 6000);
        assertThat(forA.getActionOrderList()).isNotEmpty().isEqualTo(forB.getActionOrderList()).isEqualTo(forC.getActionOrderList());
        assertThat(actor(forA, A).getSkillCooldownRoundsMap()).as("本人的冷却保留").containsKey(FakeBattleData.SKILL_DAMAGE);
        assertThat(actor(forB, A).getSkillCooldownRoundsMap()).as("对手的冷却被清掉").isEmpty();
        assertThat(forC.getState().getActorsList()).allSatisfy(x -> assertThat(x.getSkillCooldownRoundsMap()).isEmpty());
        assertThat(forA.getState().getSelfItemsList()).hasSize(1);
        assertThat(forC.getState().getSelfItemsList()).isEmpty();
        assertThat(forA.getEventsList()).isEqualTo(forC.getEventsList());
        assertThat(rounds("timer")).isEqualTo(1);
    }

    @Test
    void 全员提交当场结算_旧窗口原到期时刻不再结算一次() {
        h.create(h.pvp(2002, A, B));
        FakeLink a = h.connect(2002, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        h.scheduler.advance(1000);

        submit(2002, A, RoomHarness.action(eBattleActionType.BATTLE_ACTION_ATTACK, B));
        assertThat(a.messageIds()).as("只有一人提交，不结算").isEmpty();
        submit(2002, B, RoomHarness.action(eBattleActionType.BATTLE_ACTION_ATTACK, A));

        assertThat(a.messageIds()).containsExactly(TURN);
        assertThat(a.parsed(TURN, TurnResultS2C.parser()).get(0).getState().getActionDeadlineMs()).isEqualTo(h.now() + 6000);
        h.scheduler.advanceTo(RoomHarness.T0 + 6000);
        assertThat(a.messageIds()).as("旧窗口已取消").hasSize(1);
        h.scheduler.advanceTo(RoomHarness.T0 + 7000);
        assertThat(a.parsed(TURN, TurnResultS2C.parser())).extracting(TurnResultS2C::getRoundIndex).containsExactly(1, 2);
        assertThat(rounds("all_ready")).isEqualTo(1);
        assertThat(rounds("timer")).isEqualTo(1);
    }

    @Test
    void 开挂机造成翻转当场结算_之后每回合2秒() {
        h.create(h.pvp(2003, A, B));
        FakeLink a = h.connect(2003, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        h.scheduler.advance(500);

        setAuto(2003, A, true);
        assertThat(a.messageIds()).as("B 还是手动，没有翻转").isEmpty();
        setAuto(2003, B, true);

        assertThat(a.messageIds()).containsExactly(TURN);
        assertThat(a.parsed(TURN, TurnResultS2C.parser()).get(0).getState().getActionDeadlineMs()).isEqualTo(h.now() + 2000);
        assertThat(rounds("auto_flip")).isEqualTo(1);
        h.scheduler.advance(2000);
        assertThat(a.messageIds()).hasSize(2);
        assertThat(rounds("timer")).isEqualTo(1);
    }

    @Test
    void 全自动时重复开挂机不结算_关挂机不结算且当前窗口不变_下一窗口6秒() {
        h.create(h.pvp(2004, A, B));
        FakeLink a = h.connect(2004, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        setAuto(2004, A, true);
        setAuto(2004, B, true);
        long deadline = h.service().room(2004).actionDeadlineMs;
        assertThat(deadline).isEqualTo(h.now() + 2000);

        h.scheduler.advance(500);
        setAuto(2004, A, true);
        assertThat(a.messageIds()).as("已全员就绪的房间重复开启不加速").hasSize(1);
        setAuto(2004, A, false);
        assertThat(a.messageIds()).as("关闭永不触发结算").hasSize(1);
        assertThat(h.service().room(2004).actionDeadlineMs).as("当前 2 s 窗口不变").isEqualTo(deadline);

        h.scheduler.advanceTo(deadline);
        assertThat(a.messageIds()).hasSize(2);
        assertThat(h.service().room(2004).actionDeadlineMs).as("A 关了挂机且没提交：下一窗口 6 s").isEqualTo(h.now() + 6000);
    }

    @Test
    void 全自动房间里挂机玩家合法提交当场结算_钉住B2() {
        h.create(h.pvp(2005, A, B));
        FakeLink a = h.connect(2005, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        setAuto(2005, A, true);
        setAuto(2005, B, true);
        h.scheduler.advance(100);

        submit(2005, A, RoomHarness.action(eBattleActionType.BATTLE_ACTION_ATTACK, B));

        assertThat(a.messageIds()).containsExactly(TURN, TURN);
        assertThat(rounds("all_ready")).isEqualTo(1);
    }

    @Test
    void 只剩宝宝时空真_按2秒节奏推进() {
        CreateBattleRequest.Builder request = h.pve(2006, A).setBattleConfigId(RoomHarness.DUNGEON_BRUTE);
        request.getPlayersBuilder(0).setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(1).setSpeed(100)).setMaxHealth(1)
                .addPets(BattlePetSnapshot.newBuilder()
                        .setPetId(700001)
                        .setOwnerPlayerId(A)
                        .setLevel(10)
                        .setMaxHealth(1_000_000)
                        .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(1_000_000).setSpeed(50)));
        h.create(request);
        BattleRoom room = h.service().room(2006);

        for (int round = 0; round < 20 && !dead(room, A); round++) {
            assertThat(room.actionDeadlineMs - h.now()).as("A 还活着且手动：6 s").isEqualTo(6000);
            h.scheduler.advanceTo(room.actionDeadlineMs);
        }

        assertThat(dead(room, A)).as("A 应当已被怪物打死").isTrue();
        assertThat(h.service().room(2006)).as("宝宝还在打，房间没结束").isSameAs(room);
        assertThat(room.actionDeadlineMs - h.now()).as("没有存活的手动玩家 = 全员就绪（空真）").isEqualTo(2000);
    }

    private static boolean dead(BattleRoom room, long playerId) {
        return room.engine.buildStateSnapshot().getActorsList().stream()
                .anyMatch(actor -> actor.getActorId() == playerId && actor.getIsDead());
    }

    @Test
    void 结束回合_139截止为0_然后150与结算逐人交替_观众166_关闭_结果事件_之后再无回调() {
        h.create(h.pveTeam(2007, A, D).setBattleConfigId(0));
        addObserver(2007, C);
        FakeLink a = h.connect(2007, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink d = h.connect(2007, D, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink c = h.connect(2007, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        assertThat(c.messageIds()).containsExactly(SPECTATE_STATE);
        // 两只兜底怪各吃一发必杀
        submit(2007, A, RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER));
        h.clearLog();

        SubmitBattleActionResponse response = submit(2007, D, RoomHarness.skill(FakeBattleData.SKILL_NUKE, MONSTER + 1));

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getSerializedSize()).as("成功时应答体 0 字节").isZero();
        assertThat(h.trace()).containsExactly(
                "frame:5001:139", "frame:5004:139", "frame:5003:158",
                "frame:5001:150", "settlement:5001", "frame:5004:150", "settlement:5004",
                "frame:5003:166", "close:5003:battle_closed",
                "close:5001:battle_closed", "close:5004:battle_closed",
                "result");

        TurnResultS2C last = a.parsed(TURN, TurnResultS2C.parser()).get(0);
        assertThat(last.getState().getActionDeadlineMs()).isZero();
        assertThat(last.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(last.getActionOrderList()).isNotEmpty();
        BattleEndS2C end = a.parsed(END, BattleEndS2C.parser()).get(0);
        assertThat(end.getBattleId()).isEqualTo(2007);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(end.getSettlement().getPlayerId()).isEqualTo(A);
        assertThat(end.getSettlement().getBattleId()).isEqualTo(2007);
        List<BattleSettlementData> settlements = h.settlementsOut();
        assertThat(settlements.get(0)).isEqualTo(end.getSettlement());
        assertThat(settlements.get(1).getPlayerId()).isEqualTo(D);
        SpectateEndS2C spectateEnd = c.parsed(SPECTATE_END, SpectateEndS2C.parser()).get(0);
        assertThat(spectateEnd.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED);
        assertThat(spectateEnd.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleResultEvent result = h.resultsOut().get(0);
        assertThat(result.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(result.getTeamsList()).singleElement().satisfies(team -> assertThat(team.getPlayerIdsList()).containsExactly(A, D));
        assertThat(result.getTotalRounds()).isEqualTo(1);
        assertThat(result.getFinishedAtMs()).isEqualTo(h.now());

        assertThat(h.service().roomCount()).as("onRemoved 恰好一次").isZero();
        assertThat(h.counter("xm.battle.room.ends", "reason", "finished")).isEqualTo(1);
        assertThat(h.scheduler.scheduledCount()).as("三个计时器都已取消").isZero();
        h.clearLog();
        h.scheduler.advance(600_000);
        assertThat(h.log).isEmpty();
    }

    @Test
    void 观众版回合帧每回合只构造一次_同一帧写给全部观众() {
        h.create(h.pvp(2008, A, B));
        addObserver(2008, C);
        addObserver(2008, D);
        FakeLink c = h.connect(2008, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        FakeLink d = h.connect(2008, D, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);

        h.scheduler.advance(6000);

        assertThat(c.frames.get(c.frames.size() - 1)).as("§11 N15").isSameAs(d.frames.get(d.frames.size() - 1));
        assertThat(c.frames.get(c.frames.size() - 1).getMessageId()).isEqualTo(SPECTATE_TURN);
    }
}
