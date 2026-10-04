package com.game.scene.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.LinkHello;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.ToClient;
import com.game.common.token.NodeLinkAuth;
import com.game.proto.MessageContent;
import com.game.proto.ListSkillsRequest;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.Scene;
import com.game.scene.world.SceneWorld;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 握手 → 进场 → 下行帧 → 链路顶替，走真实的 GateLinks / SceneWorld（逻辑线程用同步执行器代替）。 */
class SceneLinkServiceTest {

    private static final LinkIdentity IDENTITY = new LinkIdentity(7, "scene-instance", 1);
    private static final NodeLinkAuth AUTH = NodeLinkAuth.ofUtf8("link-secret");
    private static final long NOW = 1_800_000_000L;

    private FakePlayerRepository repo;
    private SceneWorld world;
    private Scene scene;
    private SceneLinkService service;
    private final AtomicLong linkIds = new AtomicLong();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SceneMetrics metrics = new SceneMetrics(meters);

    @BeforeEach
    void setUp() {
        repo = new FakePlayerRepository();
        GateLinks links = new GateLinks(metrics);
        FakeSceneTables tables = new FakeSceneTables();
        AtomicLong ids = new AtomicLong(9000);
        world = new SceneWorld(tables, Contracts.IDS, links, repo, ids::incrementAndGet, new ManualClock(), metrics);
        scene = world.createScene(1);
        ClientRequestHandler requests = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS);
        service = new SceneLinkService(IDENTITY, links, world, requests, metrics);
    }

    @Test
    void 握手通过回accepted_进场后按序下发79_21_再回进场结果() throws Exception {
        EmbeddedChannel gate = connect(5, "gate-a");
        repo.putNewPlayer(1001, 3);

        gate.writeInbound(NodeLinkFrame.newBuilder().setPlayerEnter(PlayerEnter.newBuilder()
                .setSessionId(11).setPlayerId(1001).setSceneId(scene.sceneId()).setOwnerEpoch(3)).build());
        repo.completeAll();

        List<NodeLinkFrame> out = drain(gate);
        assertThat(out).hasSize(3);
        assertThat(toClientIds(out.subList(0, 2))).containsExactly(79, 21);
        assertThat(out.get(0).getToClient().getSessionIdsList()).containsExactly(11);
        PlayerEnterResult result = out.get(2).getPlayerEnterResult();
        assertThat(result.getSessionId()).isEqualTo(11);
        assertThat(result.getPlayerId()).isEqualTo(1001L);
        assertThat(result.getTipId()).isZero();
        assertThat(result.getOwnerEpoch()).as("回显进场的 epoch").isEqualTo(3);

        gate.writeInbound(NodeLinkFrame.newBuilder().setClientForward(ClientForward.newBuilder()
                .setSessionId(11).setPlayerId(1001).setMessageId(Contracts.IDS.listSkills())
                .setBody(ListSkillsRequest.getDefaultInstance().toByteString()).setRequestId(5)).build());
        List<NodeLinkFrame> replies = drain(gate);
        assertThat(toClientIds(replies)).containsExactly(77);
        assertThat(MessageContent.parseFrom(replies.get(0).getToClient().getMessageContent()).getId()).isEqualTo(5L);

        // 链路帧计数与实际收发一致：收 hello / player_enter / client_forward，发 hello_ack / 3 个 to_client / player_enter_result。
        assertThat(frames("in", "hello")).isEqualTo(1);
        assertThat(frames("in", "player_enter")).isEqualTo(1);
        assertThat(frames("in", "client_forward")).isEqualTo(1);
        assertThat(frames("out", "hello_ack")).isEqualTo(1);
        assertThat(frames("out", "to_client")).isEqualTo(3);
        assertThat(frames("out", "player_enter_result")).isEqualTo(1);
    }

    @Test
    void 同gate节点的新链路顶替旧链路_旧链路断开且其上玩家按断线移除写回() {
        EmbeddedChannel oldLink = connect(5, "gate-a");
        repo.putNewPlayer(1001, 1);
        oldLink.writeInbound(NodeLinkFrame.newBuilder().setPlayerEnter(PlayerEnter.newBuilder()
                .setSessionId(11).setPlayerId(1001).setSceneId(scene.sceneId()).setOwnerEpoch(1)).build());
        repo.completeAll();
        assertThat(world.playerCount()).isEqualTo(1);

        EmbeddedChannel newLink = connect(5, "gate-a-restarted");

        assertThat(oldLink.isOpen()).isFalse();
        assertThat(world.playerCount()).isZero();
        assertThat(repo.saves()).extracting(save -> save.playerId()).containsExactly(1001L);
        assertThat(newLink.isOpen()).isTrue();
    }

    @Test
    void 租约代次更低的旧gate进程_顶不掉新持有者的链路() {
        EmbeddedChannel current = connect(5, "gate-new", 8);
        repo.putNewPlayer(1001, 1);
        current.writeInbound(NodeLinkFrame.newBuilder().setPlayerEnter(PlayerEnter.newBuilder()
                .setSessionId(11).setPlayerId(1001).setSceneId(scene.sceneId()).setOwnerEpoch(1)).build());
        repo.completeAll();
        drain(current);

        EmbeddedChannel zombie = open(5, "gate-old", 7);
        NodeLinkFrame ack = zombie.readOutbound();

        assertThat(ack.getHelloAck().getAccepted()).isFalse();
        assertThat(ack.getHelloAck().getReason()).isEqualTo(SceneLinkService.STALE_LEASE_REASON);
        assertThat(frames("out", "hello_ack")).as("接受一次 + 拒绝一次").isEqualTo(2);
        assertThat(zombie.isOpen()).isFalse();
        assertThat(current.isOpen()).as("新持有者的链路不受影响").isTrue();
        assertThat(world.playerCount()).isEqualTo(1);
        assertThat(repo.saves()).isEmpty();

        // 代次相同（同一持有者重连）或更高（新持有者）照常顶替。
        EmbeddedChannel reconnect = connect(5, "gate-new", 8);
        assertThat(current.isOpen()).isFalse();
        assertThat(reconnect.isOpen()).isTrue();
    }

    @Test
    void 进场失败的结果回显epoch() {
        EmbeddedChannel gate = connect(5, "gate-a");

        gate.writeInbound(NodeLinkFrame.newBuilder().setPlayerEnter(PlayerEnter.newBuilder()
                .setSessionId(11).setPlayerId(1001).setSceneId(424242).setOwnerEpoch(6)).build());

        PlayerEnterResult result = ((NodeLinkFrame) gate.readOutbound()).getPlayerEnterResult();
        assertThat(result.getTipId()).isEqualTo(3023);
        assertThat(result.getOwnerEpoch()).isEqualTo(6);
    }

    @Test
    void 链路断开_其上玩家移除并写回() {
        EmbeddedChannel gate = connect(5, "gate-a");
        repo.putNewPlayer(1001, 1);
        gate.writeInbound(NodeLinkFrame.newBuilder().setPlayerEnter(PlayerEnter.newBuilder()
                .setSessionId(11).setPlayerId(1001).setSceneId(scene.sceneId()).setOwnerEpoch(1)).build());
        repo.completeAll();

        gate.close();

        assertThat(world.playerCount()).isZero();
        assertThat(repo.saves()).hasSize(1);
    }

    // ------------------------------------------------------------------ 工具

    private EmbeddedChannel connect(int gateNodeId, String gateInstanceId) {
        return connect(gateNodeId, gateInstanceId, 1);
    }

    private EmbeddedChannel connect(int gateNodeId, String gateInstanceId, long leaseEpoch) {
        EmbeddedChannel channel = open(gateNodeId, gateInstanceId, leaseEpoch);
        NodeLinkFrame ack = channel.readOutbound();
        assertThat(ack).isNotNull();
        assertThat(ack.getHelloAck().getAccepted()).isTrue();
        assertThat(ack.getHelloAck().getSceneNodeId()).isEqualTo(7);
        return channel;
    }

    /** 建链并发出握手，不读 ack。 */
    private EmbeddedChannel open(int gateNodeId, String gateInstanceId, long leaseEpoch) {
        EmbeddedChannel channel = new EmbeddedChannel(new NodeLinkHandler(IDENTITY, AUTH,
                InstantSource.fixed(Instant.ofEpochSecond(NOW)), service, Runnable::run, linkIds, Duration.ofSeconds(10),
                100, metrics));
        channel.writeInbound(NodeLinkFrame.newBuilder()
                .setHello(LinkHello.newBuilder()
                        .setGateNodeId(gateNodeId)
                        .setGateInstanceId(gateInstanceId)
                        .setZoneId(1)
                        .setAuthTimestamp(NOW)
                        .setLeaseEpoch(leaseEpoch)
                        .setAuthMac(AUTH.sign(gateNodeId, gateInstanceId, 1, leaseEpoch, NOW)))
                .build());
        return channel;
    }

    private double frames(String direction, String type) {
        return meters.get("xm.scene.link.frames").tag("direction", direction).tag("type", type).counter().count();
    }

    private static List<NodeLinkFrame> drain(EmbeddedChannel channel) {
        List<NodeLinkFrame> frames = new ArrayList<>();
        for (NodeLinkFrame frame = channel.readOutbound(); frame != null; frame = channel.readOutbound()) {
            frames.add(frame);
        }
        return frames;
    }

    private static List<Integer> toClientIds(List<NodeLinkFrame> frames) throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (NodeLinkFrame frame : frames) {
            assertThat(frame.getBodyCase()).isEqualTo(NodeLinkFrame.BodyCase.TO_CLIENT);
            ToClient toClient = frame.getToClient();
            ids.add(MessageContent.parseFrom(toClient.getMessageContent()).getMessageId());
        }
        return ids;
    }
}
