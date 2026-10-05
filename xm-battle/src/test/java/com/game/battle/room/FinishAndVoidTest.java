package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static com.game.battle.room.RoomHarness.D;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.room.RoomHarness.FakeLink;
import com.game.proto.AddObserverRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.SpectateEndS2C;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import org.junit.jupiter.api.Test;

/** 作废路径：DestroyBattle、停机 abortAll、同 id 重建（基线 {@code room.cpp:644-670}、{@code :1327-1353}；battle-node-spec §4.7、§13.2）。 */
class FinishAndVoidTest {

    private final RoomHarness h = new RoomHarness();

    private void observe(long battleId, long observerId) {
        h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerId)
                .setRouting(RoomHarness.observerRouting(observerId, 900)).build());
    }

    private void destroy(long battleId) {
        h.service().destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(battleId).setReason("gather_rollback").build());
    }

    @Test
    void 销毁不存在的房间是空操作() {
        destroy(404);

        assertThat(h.log).isEmpty();
        assertThat(h.counter("xm.battle.room.ends", "reason", "destroyed")).isZero();
    }

    @Test
    void 销毁_观众166为ABORTED加ONGOING_参战者没有150只看到FIN_不结算不发结果_计时器全部失效() {
        h.create(h.pvp(5001, A, B));
        observe(5001, C);
        FakeLink a = h.connect(5001, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink b = h.connect(5001, B, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink c = h.connect(5001, C, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        h.clearLog();

        destroy(5001);

        assertThat(h.trace()).containsExactly("frame:5003:166", "close:5003:battle_closed", "close:5001:battle_closed",
                "close:5002:battle_closed");
        SpectateEndS2C end = c.parsed(166, SpectateEndS2C.parser()).get(0);
        assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        assertThat(a.messageIds()).isEmpty();
        assertThat(b.messageIds()).isEmpty();
        assertThat(h.service().roomCount()).isZero();
        assertThat(h.counter("xm.battle.room.ends", "reason", "destroyed")).isEqualTo(1);
        assertThat(h.scheduler.scheduledCount()).isZero();

        h.scheduler.advance(400_000);
        assertThat(h.trace()).as("没有任何迟到的回调").hasSize(4);
        destroy(5001);
        assertThat(h.counter("xm.battle.room.ends", "reason", "destroyed")).as("幂等").isEqualTo(1);
    }

    @Test
    void 停机作废全部房间_每间一次onRemoved_直连按停机原因关闭() {
        h.create(h.pve(5002, A));
        h.create(h.pve(5003, B));
        h.create(h.pve(5004, C));
        observe(5004, D);
        FakeLink a = h.connect(5002, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        FakeLink d = h.connect(5004, D, eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER);
        assertThat(h.service().roomCount()).isEqualTo(3);
        h.clearLog();

        h.service().abortAll("node_shutdown");

        assertThat(h.service().roomCount()).isZero();
        assertThat(h.counter("xm.battle.room.ends", "reason", "aborted")).isEqualTo(3);
        assertThat(a.closedWith).isEqualTo(com.game.battle.metrics.BattleMetrics.Disconnect.SHUTDOWN);
        assertThat(a.messageIds()).as("参战者不收帧").isEmpty();
        SpectateEndS2C end = d.parsed(166, SpectateEndS2C.parser()).get(0);
        assertThat(end.getReason()).isEqualTo(eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED);
        assertThat(end.getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_ONGOING);
        assertThat(d.closedWith).isEqualTo(com.game.battle.metrics.BattleMetrics.Disconnect.SHUTDOWN);
        assertThat(h.settlementsOut()).isEmpty();
        assertThat(h.resultsOut()).isEmpty();
        assertThat(h.scheduler.scheduledCount()).isZero();

        h.service().abortAll("node_shutdown");
        assertThat(h.counter("xm.battle.room.ends", "reason", "aborted")).isEqualTo(3);
    }

    @Test
    void 销毁后同id重建_旧计时器回调打不到新房间() {
        h.create(h.pve(5005, A).setBattleConfigId(RoomHarness.DUNGEON_TANK));
        h.scheduler.advance(3000);
        destroy(5005);
        h.create(h.pve(5005, A).setBattleConfigId(RoomHarness.DUNGEON_TANK));
        FakeLink a = h.connect(5005, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        h.scheduler.advanceTo(RoomHarness.T0 + 6000);
        assertThat(a.messageIds()).as("旧房间的回合窗口不结算新房间").isEmpty();
        h.scheduler.advanceTo(RoomHarness.T0 + 9000);
        assertThat(a.messageIds()).containsExactly(139);
    }
}
