package com.game.battle.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.room.DirectLink;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleStartS2C;
import com.game.proto.MessageContent;
import com.game.proto.TurnResultS2C;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 房间下行出口（基线 {@code room.cpp:1378-1430}；battle-node-spec §5.7、§7.7）。 */
class BattleOutboundTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final List<List<MessageContent>> announced = new ArrayList<>();
    private final BattleOutbound outbound = new BattleOutbound(BattleMessageIds.loadFromClasspath(),
            (playerId, contents) -> announced.add(contents), metrics);

    /** 记录写出的帧；live 由用例控制。 */
    private static final class Link implements DirectLink {
        boolean live = true;
        final List<MessageContent> sent = new ArrayList<>();

        @Override
        public boolean isLive() {
            return live;
        }

        @Override
        public void send(MessageContent frame) {
            sent.add(frame);
        }

        @Override
        public void closeGracefully(Disconnect reason) {
            live = false;
        }

        @Override
        public void closeNow(Disconnect reason) {
            live = false;
        }

        @Override
        public String peer() {
            return "test";
        }
    }

    private double pushes(String category, String route, String message) {
        return registry.get("xm.battle.pushes").tag("category", category).tag("route", route).tag("message", message).counter().count();
    }

    @Test
    void 推送帧形状_id为0且带消息号() {
        MessageContent frame = outbound.frame(Notify.TURN_RESULT, TurnResultS2C.newBuilder().setBattleId(3).build());

        assertThat(frame.getId()).isZero();
        assertThat(frame.getMessageId()).isEqualTo(139);
        assertThat(frame.hasErrorMessage()).isFalse();
    }

    @Test
    void 战斗帧有活直连直写_无直连丢弃并计数() {
        Link link = new Link();
        MessageContent frame = outbound.frame(Notify.TURN_RESULT, TurnResultS2C.getDefaultInstance());

        assertThat(outbound.pushBattleFrame(1, 2, link, Notify.TURN_RESULT, frame)).isEqualTo(PushRoute.DIRECT);
        assertThat(link.sent).containsExactly(frame);

        link.live = false;
        assertThat(outbound.pushBattleFrame(1, 2, link, Notify.TURN_RESULT, frame)).isEqualTo(PushRoute.DROP);
        assertThat(outbound.pushBattleFrame(1, 3, null, Notify.TURN_RESULT, frame)).isEqualTo(PushRoute.DROP);
        assertThat(link.sent).hasSize(1);
        assertThat(announced).as("战斗帧绝不回落 gate").isEmpty();
        assertThat(pushes("battle_frame", "direct", "NotifyTurnResult")).isEqualTo(1);
        assertThat(pushes("battle_frame", "dropped", "NotifyTurnResult")).isEqualTo(2);
    }

    @Test
    void 大厅公告无直连时一批交给announcer_有活直连时按序直写() {
        List<BattleOutbound.Announcement> batch = List.of(
                outbound.announcement(Notify.BATTLE_ASSIGNED, BattleAssignedS2C.newBuilder().setBattleId(5).build()),
                outbound.announcement(Notify.BATTLE_START, BattleStartS2C.newBuilder().setBattleId(5).build()));

        assertThat(outbound.pushLobby(5, 9, null, batch)).isEqualTo(PushRoute.VIA_GATE);
        assertThat(announced).hasSize(1);
        assertThat(announced.get(0)).extracting(MessageContent::getMessageId).containsExactly(177, 143);

        Link link = new Link();
        assertThat(outbound.pushLobby(5, 9, link, batch)).isEqualTo(PushRoute.DIRECT);
        assertThat(link.sent).extracting(MessageContent::getMessageId).containsExactly(177, 143);
        assertThat(announced).hasSize(1);

        assertThat(pushes("lobby", "via_gate", "NotifyBattleAssigned")).isEqualTo(1);
        assertThat(pushes("lobby", "via_gate", "NotifyBattleStart")).isEqualTo(1);
        assertThat(pushes("lobby", "direct", "NotifyBattleAssigned")).isEqualTo(1);
        assertThat(pushes("lobby", "direct", "NotifyBattleStart")).isEqualTo(1);
    }

    @Test
    void announcer抛异常不外泄() {
        BattleOutbound throwing = new BattleOutbound(BattleMessageIds.loadFromClasspath(), (playerId, contents) -> {
            throw new IllegalStateException("测试");
        }, metrics);

        assertThat(throwing.pushLobby(1, 2, null,
                List.of(throwing.announcement(Notify.BATTLE_ASSIGNED, BattleAssignedS2C.getDefaultInstance()))))
                .isEqualTo(PushRoute.VIA_GATE);
    }

    @Test
    void 类别与出口不符是程序缺陷() {
        MessageContent frame = outbound.frame(Notify.BATTLE_START, BattleStartS2C.getDefaultInstance());

        assertThatThrownBy(() -> outbound.pushBattleFrame(1, 2, null, Notify.BATTLE_START, frame))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.announcement(Notify.TURN_RESULT, TurnResultS2C.getDefaultInstance()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbound.pushLobby(1, 2, null, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
