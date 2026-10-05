package com.game.battle.room;

import static com.game.battle.room.RoomHarness.A;
import static com.game.battle.room.RoomHarness.B;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.room.RoomHarness.FakeLink;
import com.game.proto.MessageContent;
import com.game.proto.eBattleTicketRole;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 推送类别与出口（基线 {@code room.cpp:1378-1430}；battle-node-spec §5.7、§7.7、§13.2）。 */
class LobbyAnnouncementTest {

    private final RoomHarness h = new RoomHarness();

    private double pushes(String category, String route, String message) {
        return h.counter("xm.battle.pushes", "category", category, "route", route, "message", message);
    }

    @Test
    void 没有直连时177与143作为同一批交给announcer() {
        h.create(h.pvp(10001, A, B));

        List<RoomHarness.Out> lobby = h.outs(RoomHarness.Kind.LOBBY);
        assertThat(lobby).hasSize(2);
        for (RoomHarness.Out out : lobby) {
            @SuppressWarnings("unchecked")
            List<MessageContent> batch = (List<MessageContent>) out.payload();
            assertThat(batch).extracting(MessageContent::getMessageId).containsExactly(177, 143);
        }
        assertThat(pushes("lobby", "via_gate", "NotifyBattleAssigned")).isEqualTo(2);
        assertThat(pushes("lobby", "via_gate", "NotifyBattleStart")).isEqualTo(2);
    }

    @Test
    void 战斗帧没有直连时丢弃并计数_有直连的照常直写() {
        h.create(h.pvp(10002, A, B));
        FakeLink a = h.connect(10002, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);

        h.scheduler.advance(6000);

        assertThat(a.messageIds()).containsExactly(139);
        assertThat(pushes("battle_frame", "direct", "NotifyTurnResult")).isEqualTo(1);
        assertThat(pushes("battle_frame", "dropped", "NotifyTurnResult")).as("B 没有直连").isEqualTo(1);
        assertThat(h.outs(RoomHarness.Kind.LOBBY)).as("战斗帧绝不回落 gate").hasSize(2);
    }

    @Test
    void 直连进入关闭流程后不再算活直连() {
        h.create(h.pvp(10003, A, B));
        FakeLink a = h.connect(10003, A, eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT);
        a.live = false;

        h.scheduler.advance(6000);

        assertThat(a.frames).isEmpty();
        assertThat(pushes("battle_frame", "dropped", "NotifyTurnResult")).isEqualTo(2);
    }
}
