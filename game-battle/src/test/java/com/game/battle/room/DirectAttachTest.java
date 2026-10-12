package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static com.game.battle.room.RoomHarness.C;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.room.RoomHarness.FakeLink;
import com.game.proto.AddObserverRequest;
import com.game.proto.SpectateStateS2C;
import com.game.proto.eBattleTicketRole;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 直连挂接、重连顶替、迟到的断开与观众首帧（基线 {@code room.cpp:1500-1593}；battle-node-spec §3.4、R1、R7、§13.2）。 */
class DirectAttachTest {

    private static final eBattleTicketRole PARTICIPANT = eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT;
    private static final eBattleTicketRole OBSERVER = eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER;

    private final RoomHarness h = new RoomHarness();

    private void observe(long battleId, long observerId) {
        h.service().addObserver(AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerId)
                .setRouting(RoomHarness.observerRouting(observerId, 900)).build());
    }

    @Test
    void 角色与名单不符被拒_挂接时不写任何帧() {
        h.create(h.pvp(7001, A, B));
        observe(7001, C);
        FakeLink link = h.new FakeLink(A);
        link.live = true;

        assertThat(h.service().attachDirect(7001, A, OBSERVER, link)).as("参战者拿观众票").isEmpty();
        assertThat(h.service().attachDirect(7001, C, PARTICIPANT, link)).as("观众拿参战票").isEmpty();
        assertThat(h.service().attachDirect(7001, A, eBattleTicketRole.BATTLE_TICKET_ROLE_NONE, link)).isEmpty();
        assertThat(h.service().attachDirect(404, A, PARTICIPANT, link)).as("房间不在").isEmpty();
        assertThat(h.service().attachDirect(7001, RoomHarness.D, PARTICIPANT, link)).isEmpty();
        assertThat(link.frames).isEmpty();

        OptionalInt session = h.service().attachDirect(7001, A, PARTICIPANT, link);
        assertThat(session).hasValue(RoomHarness.routing(A).getSessionId());
        assertThat(link.frames).as("握手应答必须是直连上的第一帧（R1）").isEmpty();
    }

    @Test
    void 重连顶替旧连接_旧的被立即强关且没收到帧_迟到的断开不摘新连接() {
        h.create(h.pvp(7002, A, B));
        FakeLink old = h.connect(7002, A, PARTICIPANT);
        h.clearLog();

        FakeLink fresh = h.connect(7002, A, PARTICIPANT);

        assertThat(h.trace()).containsExactly("kill:5001:replaced");
        assertThat(old.killed).isTrue();
        assertThat(old.closedWith).isEqualTo(Disconnect.REPLACED);
        assertThat(old.frames).isEmpty();

        h.service().detachDirect(7002, A, old);
        assertThat(h.service().room(7002).directOf(A)).isSameAs(fresh);
        h.scheduler.advance(6000);
        assertThat(fresh.messageIds()).containsExactly(139);
        assertThat(old.frames).isEmpty();

        h.service().detachDirect(7002, A, fresh);
        assertThat(h.service().room(7002).directOf(A)).isNull();
    }

    @Test
    void 同一条连接重复挂接不自关() {
        h.create(h.pvp(7003, A, B));
        FakeLink link = h.connect(7003, A, PARTICIPANT);

        assertThat(h.service().attachDirect(7003, A, PARTICIPANT, link)).isPresent();

        assertThat(link.closedWith).isNull();
    }

    @Test
    void 观众挂接后推161_观众数正确_参战者不推() {
        h.create(h.pvp(7004, A, B));
        observe(7004, C);
        observe(7004, RoomHarness.D);
        h.clearLog();

        FakeLink a = h.connect(7004, A, PARTICIPANT);
        FakeLink c = h.connect(7004, C, OBSERVER);

        assertThat(a.frames).as("参战者由客户端自己发 140 补拉").isEmpty();
        assertThat(c.messageIds()).containsExactly(161);
        SpectateStateS2C first = c.parsed(161, SpectateStateS2C.parser()).get(0);
        assertThat(first.getObserverCount()).isEqualTo(2);
        assertThat(first.getState().getBattleId()).isEqualTo(7004);
        assertThat(first.getState().getActionDeadlineMs()).isEqualTo(h.service().room(7004).actionDeadlineMs);
        assertThat(first.getState().getActorsList()).allSatisfy(x -> assertThat(x.getSkillCooldownRoundsMap()).isEmpty());
        assertThat(first.getState().getSelfItemsList()).isEmpty();

        h.service().onDirectVerified(7004, C, PARTICIPANT);
        h.service().onDirectVerified(404, C, OBSERVER);
        assertThat(c.messageIds()).hasSize(1);
    }

    @Test
    void 房间结束后旧连接的断开回调什么也不做() {
        h.create(h.pvp(7005, A, B));
        FakeLink a = h.connect(7005, A, PARTICIPANT);
        h.service().destroyBattle(com.game.proto.DestroyBattleRequest.newBuilder().setBattleId(7005).build());

        h.service().detachDirect(7005, A, a);

        assertThat(h.service().roomCount()).isZero();
    }
}
