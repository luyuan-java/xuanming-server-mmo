package com.game.scene.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.LinkHello;
import com.game.api.proto.LinkHelloAck;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerLeave;
import com.game.common.token.NodeLinkAuth;
import com.game.scene.metrics.SceneMetrics;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 链路握手（EmbeddedChannel，逻辑线程用同步执行器代替，时钟固定）。 */
class NodeLinkHandlerTest {

    private static final LinkIdentity IDENTITY = new LinkIdentity(7, "scene-instance", 1);
    private static final NodeLinkAuth AUTH = NodeLinkAuth.ofUtf8("link-secret");
    private static final long NOW = 1_800_000_000L;
    private static final String GATE_INSTANCE = "gate-instance";

    private RecordingInbound inbound;
    private SimpleMeterRegistry meters;
    private SceneMetrics metrics;

    @BeforeEach
    void setUp() {
        inbound = new RecordingInbound();
        meters = new SimpleMeterRegistry();
        metrics = new SceneMetrics(meters);
    }

    // ------------------------------------------------------------------ 鉴权

    @Test
    void MAC正确且时间戳在窗口内_握手通过() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));

        channel.writeInbound(hello(5, 1, NOW - NodeLinkAuth.MAX_CLOCK_SKEW_SECONDS));

        assertThat(channel.isOpen()).isTrue();
        assertThat(inbound.events).containsExactly("opened:1:5");
    }

    @Test
    void MAC错误_回拒绝并断开_不产生链路事件() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));

        channel.writeInbound(helloSignedWith(NodeLinkAuth.ofUtf8("wrong-secret"), 5, 1, NOW));

        assertRejectedForAuth(channel);
    }

    @Test
    void 没带MAC的旧版握手_拒绝() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));

        channel.writeInbound(NodeLinkFrame.newBuilder()
                .setHello(LinkHello.newBuilder().setGateNodeId(5).setGateInstanceId(GATE_INSTANCE).setZoneId(1))
                .build());

        assertRejectedForAuth(channel);
    }

    @Test
    void 签名后字段被改_拒绝() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));
        LinkHello signed = hello(5, 1, NOW).getHello();

        channel.writeInbound(NodeLinkFrame.newBuilder().setHello(signed.toBuilder().setGateNodeId(6)).build());

        assertRejectedForAuth(channel);
    }

    @Test
    void 时间戳过旧或超前超过60秒_拒绝() {
        EmbeddedChannel stale = newChannel(Duration.ofSeconds(10));
        stale.writeInbound(hello(5, 1, NOW - NodeLinkAuth.MAX_CLOCK_SKEW_SECONDS - 1));
        assertRejectedForAuth(stale);

        EmbeddedChannel future = newChannel(Duration.ofSeconds(10));
        future.writeInbound(hello(5, 1, NOW + NodeLinkAuth.MAX_CLOCK_SKEW_SECONDS + 1));
        assertRejectedForAuth(future);
    }

    // ------------------------------------------------------------------ 其他握手校验（鉴权已通过）

    @Test
    void zone不符_回拒绝并断开_不产生链路事件() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));

        channel.writeInbound(hello(5, 2, NOW));

        LinkHelloAck ack = readAck(channel);
        assertThat(ack.getAccepted()).isFalse();
        assertThat(ack.getReason()).contains("zone");
        assertThat(ack.getSceneNodeId()).isEqualTo(7);
        assertThat(ack.getSceneInstanceId()).isEqualTo("scene-instance");
        assertThat(ack.getZoneId()).isEqualTo(1);
        assertThat(channel.isOpen()).isFalse();
        assertThat(inbound.events).isEmpty();
        assertThat(frames("in", "hello")).isEqualTo(1);
        assertThat(frames("out", "hello_ack")).as("拒绝回包也算出站帧").isEqualTo(1);
    }

    @Test
    void 首帧不是Hello_拒绝并断开() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));

        channel.writeInbound(NodeLinkFrame.newBuilder()
                .setPlayerLeave(PlayerLeave.newBuilder().setSessionId(1).setPlayerId(2))
                .build());

        assertThat(readAck(channel).getAccepted()).isFalse();
        assertThat(channel.isOpen()).isFalse();
        assertThat(inbound.events).isEmpty();
    }

    @Test
    void gate节点号为0_拒绝() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));

        channel.writeInbound(hello(0, 1, NOW));

        assertThat(readAck(channel).getAccepted()).isFalse();
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void 握手通过_事件按序转给逻辑线程_断开时通知关闭() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));
        NodeLinkFrame leave = NodeLinkFrame.newBuilder()
                .setPlayerLeave(PlayerLeave.newBuilder().setSessionId(1).setPlayerId(2))
                .build();

        channel.writeInbound(hello(5, 1, NOW));
        channel.writeInbound(leave);
        channel.close();

        assertThat(inbound.events).containsExactly("opened:1:5", "frame:1:PLAYER_LEAVE", "closed:1");
        assertThat(inbound.lastOpenedChannel).isSameAs(channel);
        assertThat(frames("in", "hello")).isEqualTo(1);
        assertThat(frames("in", "player_leave")).isEqualTo(1);
        assertThat(frames("out", "hello_ack")).as("握手成功的回包由逻辑线程登记链路后发（SceneLinkService）").isZero();
    }

    @Test
    void 握手后重复Hello_断开() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));

        channel.writeInbound(hello(5, 1, NOW));
        channel.writeInbound(hello(5, 1, NOW));

        assertThat(channel.isOpen()).isFalse();
        assertThat(inbound.events).containsExactly("opened:1:5", "closed:1");
    }

    @Test
    void 超时未握手_断开() {
        EmbeddedChannel channel = newChannel(Duration.ZERO);

        channel.runScheduledPendingTasks();

        assertThat(channel.isOpen()).isFalse();
        assertThat(inbound.events).isEmpty();
    }

    @Test
    void 租约代次被篡改_MAC不对_拒绝() {
        EmbeddedChannel channel = newChannel(Duration.ofSeconds(10));
        LinkHello signed = hello(5, 1, NOW).getHello();

        channel.writeInbound(NodeLinkFrame.newBuilder().setHello(signed.toBuilder().setLeaseEpoch(99)).build());

        assertRejectedForAuth(channel);
    }

    // ------------------------------------------------------------------ 背压

    @Test
    void 逻辑线程积压到上限_暂停读取_消化到一半以下恢复() {
        List<Runnable> logicQueue = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel(new NodeLinkHandler(IDENTITY, AUTH,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), inbound, logicQueue::add, new AtomicLong(),
                Duration.ofSeconds(10), 4, metrics));
        channel.writeInbound(hello(5, 1, NOW));
        logicQueue.remove(0).run();
        NodeLinkFrame leave = NodeLinkFrame.newBuilder()
                .setPlayerLeave(PlayerLeave.newBuilder().setSessionId(1).setPlayerId(2))
                .build();

        for (int i = 0; i < 3; i++) {
            channel.writeInbound(leave);
        }
        assertThat(channel.config().isAutoRead()).as("3 帧积压，未到上限").isTrue();
        assertThat(pauses()).isZero();
        channel.writeInbound(leave);
        assertThat(channel.config().isAutoRead()).as("4 帧积压，到上限暂停读").isFalse();
        assertThat(pauses()).isEqualTo(1);

        logicQueue.remove(0).run();
        channel.runPendingTasks();
        assertThat(channel.config().isAutoRead()).as("剩 3 帧，仍在恢复线（一半 = 2）之上").isFalse();

        logicQueue.remove(0).run();
        channel.runPendingTasks();
        assertThat(channel.config().isAutoRead()).as("剩 2 帧，降到恢复线，恢复读").isTrue();

        logicQueue.forEach(Runnable::run);
        assertThat(inbound.events).containsExactly("opened:1:5", "frame:1:PLAYER_LEAVE", "frame:1:PLAYER_LEAVE",
                "frame:1:PLAYER_LEAVE", "frame:1:PLAYER_LEAVE");
        assertThat(pauses()).as("恢复不计，只计暂停").isEqualTo(1);
    }

    // ------------------------------------------------------------------ 工具

    private EmbeddedChannel newChannel(Duration handshakeTimeout) {
        return new EmbeddedChannel(new NodeLinkHandler(IDENTITY, AUTH, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
                inbound, Runnable::run, new AtomicLong(), handshakeTimeout, 100, metrics));
    }

    private double frames(String direction, String type) {
        return meters.get("xm.scene.link.frames").tag("direction", direction).tag("type", type).counter().count();
    }

    private double pauses() {
        return meters.get("xm.scene.link.backpressure.pauses").counter().count();
    }

    private static NodeLinkFrame hello(int gateNodeId, int zoneId, long authTimestamp) {
        return helloSignedWith(AUTH, gateNodeId, zoneId, authTimestamp);
    }

    private static NodeLinkFrame helloSignedWith(NodeLinkAuth auth, int gateNodeId, int zoneId, long authTimestamp) {
        long leaseEpoch = 3;
        ByteString mac = auth.sign(gateNodeId, GATE_INSTANCE, zoneId, leaseEpoch, authTimestamp);
        return NodeLinkFrame.newBuilder()
                .setHello(LinkHello.newBuilder()
                        .setGateNodeId(gateNodeId)
                        .setGateInstanceId(GATE_INSTANCE)
                        .setZoneId(zoneId)
                        .setAuthTimestamp(authTimestamp)
                        .setLeaseEpoch(leaseEpoch)
                        .setAuthMac(mac))
                .build();
    }

    private void assertRejectedForAuth(EmbeddedChannel channel) {
        LinkHelloAck ack = readAck(channel);
        assertThat(ack.getAccepted()).isFalse();
        assertThat(ack.getReason()).as("对外只给固定原因").isEqualTo(NodeLinkHandler.AUTH_FAILED_REASON);
        assertThat(channel.isOpen()).isFalse();
        assertThat(inbound.events).isEmpty();
    }

    private static LinkHelloAck readAck(EmbeddedChannel channel) {
        NodeLinkFrame frame = channel.readOutbound();
        assertThat(frame).isNotNull();
        assertThat(frame.hasHelloAck()).isTrue();
        return frame.getHelloAck();
    }

    private static final class RecordingInbound implements LinkInbound {

        final List<String> events = new ArrayList<>();
        Channel lastOpenedChannel;

        @Override
        public void linkOpened(long linkId, LinkHello hello, Channel channel) {
            events.add("opened:" + linkId + ":" + hello.getGateNodeId());
            lastOpenedChannel = channel;
        }

        @Override
        public void frameReceived(long linkId, NodeLinkFrame frame) {
            events.add("frame:" + linkId + ":" + frame.getBodyCase());
        }

        @Override
        public void linkClosed(long linkId) {
            events.add("closed:" + linkId);
        }
    }
}
