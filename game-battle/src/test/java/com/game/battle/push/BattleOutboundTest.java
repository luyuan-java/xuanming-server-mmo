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
import com.game.proto.SpectateEndS2C;
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

    /** 记下共享帧的产出与写出；{@link #share} 产出的共享帧记下被释放了几次。 */
    private static class SharingLink implements DirectLink {
        boolean live = true;
        int shares;
        RuntimeException failOnSend;
        final List<SharedFrame> sharedSent = new ArrayList<>();

        @Override
        public boolean isLive() {
            return live;
        }

        @Override
        public void send(MessageContent frame) {
            throw new AssertionError("扇出不该逐条写原帧");
        }

        @Override
        public SharedFrame share(MessageContent frame) {
            shares++;
            return new TrackedFrame(frame);
        }

        @Override
        public void send(SharedFrame frame) {
            if (failOnSend != null) {
                throw failOnSend;
            }
            sharedSent.add(frame);
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
            return "sharing";
        }
    }

    private static final class TrackedFrame implements DirectLink.SharedFrame {
        final MessageContent content;
        int closes;

        TrackedFrame(MessageContent content) {
            this.content = content;
        }

        @Override
        public MessageContent content() {
            return content;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private double pushes(String category, String route, String message) {
        return registry.get("game.battle.pushes").tag("category", category).tag("route", route).tag("message", message).counter().count();
    }

    // ================================================================ 观众帧扇出（编码一次）

    @Test
    void 观众帧扇出_借第一条活直连编码一次_每条活直连写同一份_扇出结束释放一次_无活直连的丢弃并计数() {
        SharingLink first = new SharingLink();
        SharingLink second = new SharingLink();
        SharingLink dead = new SharingLink();
        dead.live = false;
        MessageContent frame = outbound.frame(Notify.SPECTATE_TURN_RESULT, TurnResultS2C.getDefaultInstance());

        outbound.pushBattleFrameToAll(1, List.of(new BattleOutbound.Recipient(11, null), new BattleOutbound.Recipient(12, first),
                new BattleOutbound.Recipient(13, dead), new BattleOutbound.Recipient(14, second)), Notify.SPECTATE_TURN_RESULT, frame);

        assertThat(first.shares + second.shares + dead.shares).as("只编码一次").isEqualTo(1);
        assertThat(first.shares).as("借的是第一条活直连的分配器").isEqualTo(1);
        assertThat(first.sharedSent).hasSize(1);
        assertThat(second.sharedSent).as("写的是同一份共享帧").containsExactlyElementsOf(first.sharedSent);
        assertThat(dead.sharedSent).isEmpty();
        TrackedFrame shared = (TrackedFrame) first.sharedSent.get(0);
        assertThat(shared.content).isSameAs(frame);
        assertThat(shared.closes).as("扇出结束恰好释放一次原件").isEqualTo(1);
        assertThat(pushes("battle_frame", "direct", "NotifySpectateTurnResult")).as("每个收件人恰好计一次").isEqualTo(2);
        assertThat(pushes("battle_frame", "dropped", "NotifySpectateTurnResult")).isEqualTo(2);
        assertThat(announced).as("战斗帧绝不回落 gate").isEmpty();
    }

    @Test
    void 观众帧扇出_一条活直连都没有就不编码_写出途中抛异常也释放原件() {
        SharingLink dead = new SharingLink();
        dead.live = false;
        MessageContent end = outbound.frame(Notify.SPECTATE_END, SpectateEndS2C.getDefaultInstance());

        outbound.pushBattleFrameToAll(1, List.of(new BattleOutbound.Recipient(11, dead), new BattleOutbound.Recipient(12, null)),
                Notify.SPECTATE_END, end);

        assertThat(dead.shares).isZero();
        assertThat(pushes("battle_frame", "dropped", "NotifySpectateEnd")).isEqualTo(2);

        SharingLink first = new SharingLink();
        SharingLink broken = new SharingLink();
        broken.failOnSend = new IllegalStateException("测试：直连实现违约");
        assertThatThrownBy(() -> outbound.pushBattleFrameToAll(1, List.of(new BattleOutbound.Recipient(21, first),
                new BattleOutbound.Recipient(22, broken)), Notify.SPECTATE_END, end)).isInstanceOf(IllegalStateException.class);
        assertThat(((TrackedFrame) first.sharedSent.get(0)).closes).as("异常也不漏释放原件").isEqualTo(1);
    }

    @Test
    void 观众帧扇出_不支持共享编码的直连按原帧逐条写() {
        Link a = new Link();
        Link b = new Link();
        MessageContent frame = outbound.frame(Notify.SPECTATE_TURN_RESULT, TurnResultS2C.getDefaultInstance());

        outbound.pushBattleFrameToAll(1, List.of(new BattleOutbound.Recipient(11, a), new BattleOutbound.Recipient(12, b)),
                Notify.SPECTATE_TURN_RESULT, frame);

        assertThat(a.sent).containsExactly(frame);
        assertThat(b.sent).as("同一个 MessageContent 对象写给每条直连（§11 N15）").singleElement().isSameAs(frame);
        assertThat(pushes("battle_frame", "direct", "NotifySpectateTurnResult")).isEqualTo(2);
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
        assertThatThrownBy(() -> outbound.pushBattleFrameToAll(1, List.of(), Notify.BATTLE_START, frame))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
