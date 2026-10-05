package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.C;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.room.RoomHarness.FakeLink;
import com.game.battle.testing.FakeBattleData;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleAction;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.SpectateEndS2C;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import org.junit.jupiter.api.Test;

/** 整场期限强制平局（基线 {@code room.cpp:1082-1105}；battle-node-spec §4.5、§13.2）。 */
class BattleDeadlineTest {

    private final RoomHarness h = new RoomHarness();

    private void observe(long battleId, long observerId) {
        h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerId)
                .setRouting(RoomHarness.observerRouting(observerId, 900)).build());
    }

    @Test
    void 期限到_没有139_参战者150为DRAW无奖励_消耗照常带出_观众166为ABORTED加DRAW_照常结算与结果事件() {
        h.create(h.pve(3001, A).setBattleConfigId(RoomHarness.DUNGEON_TANK).setDeadlineMs(h.now() + 8000));
        observe(3001, C);
        FakeLink a = h.connect(3001, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink c = h.connect(3001, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        // 第 1 回合：A 吃药（单人房，当场结算）；第 2 回合 6 s 到期结算；8 s 时期限到
        BattleAction potion = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_ITEM)
                .setItemTableId(FakeBattleData.ITEM_POTION).setTargetId(A).build();
        assertThat(h.service().submit(A, SubmitBattleActionRequest.newBuilder().setBattleId(3001).setAction(potion).build())
                .hasErrorMessage()).isFalse();
        h.scheduler.advance(6000);
        assertThat(a.messageIds()).containsExactly(139, 139);
        h.clearLog();

        h.scheduler.advance(2000);

        assertThat(h.trace()).as("没有最后一帧 139").containsExactly(
                "frame:5001:150", "settlement:5001", "frame:5003:166", "close:5003:battle_closed", "close:5001:battle_closed",
                "result");
        BattleEndS2C end = a.parsed(150, BattleEndS2C.parser()).get(0);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        BattleSettlementData settlement = end.getSettlement();
        assertThat(settlement.getOutcome()).as("引擎侧仍是 ONGOING，节点盖章").isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        assertThat(settlement.getExpGain()).isZero();
        assertThat(settlement.getGoldGain()).isZero();
        assertThat(settlement.getDefeatedMonstersList()).isEmpty();
        assertThat(settlement.getItemsConsumedList()).singleElement().satisfies(item -> {
            assertThat(item.getItemTableId()).isEqualTo(FakeBattleData.ITEM_POTION);
            assertThat(item.getCount()).isEqualTo(1);
        });
        assertThat(settlement.getTotalRounds()).as("已完成的回合数").isEqualTo(2);
        assertThat(h.settlementsOut()).containsExactly(settlement);
        SpectateEndS2C spectateEnd = c.parsed(166, SpectateEndS2C.parser()).get(0);
        assertThat(spectateEnd.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED);
        assertThat(spectateEnd.getOutcome()).as("B6：期限路径给观众的是 DRAW").isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        BattleResultEvent result = h.resultsOut().get(0);
        assertThat(result.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        assertThat(result.getWinnerTeamIndex()).isZero();
        assertThat(h.service().roomCount()).isZero();
        assertThat(h.counter("xm.battle.room.ends", "reason", "deadline")).isEqualTo(1);
    }

    @Test
    void 期限只剩1毫秒时立即收尾() {
        h.create(h.pve(3002, A).setDeadlineMs(h.now() + 1));

        h.scheduler.advance(1);

        assertThat(h.service().roomCount()).isZero();
        assertThat(h.settlementsOut()).singleElement()
                .satisfies(s -> assertThat(s.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW));
    }

    @Test
    void 期限与回合窗口同时到期_先执行者生效_另一个什么也不做() {
        h.create(h.pve(3003, A).setBattleConfigId(RoomHarness.DUNGEON_TANK).setDeadlineMs(h.now() + 6000));
        FakeLink a = h.connect(3003, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        h.scheduler.advance(6000);

        assertThat(a.messageIds()).as("期限计时器先登记、先执行：直接收尾，回合计时器随之取消").containsExactly(150);
        assertThat(h.settlementsOut()).hasSize(1);
        assertThat(h.resultsOut()).hasSize(1);
        h.scheduler.advance(60_000);
        assertThat(a.messageIds()).hasSize(1);
    }
}
