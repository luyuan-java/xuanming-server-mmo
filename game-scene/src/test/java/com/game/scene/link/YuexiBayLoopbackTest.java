package com.game.scene.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientForward;
import com.game.api.proto.LinkHello;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.common.token.NodeLinkAuth;
import com.game.net.link.NodeLinkCodec;
import com.game.proto.ActorCreateS2C;
import com.game.proto.EnterSceneS2C;
import com.game.proto.Location;
import com.game.proto.MessageContent;
import com.game.proto.MoveAckS2C;
import com.game.proto.MoveSyncC2S;
import com.game.proto.Velocity;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.ManualClock;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.ConfigSceneTables;
import com.game.scene.world.PlayerData;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 用正式地图数据走真实 TCP 节点链路，测试玩家存档使用内存夹具。 */
class YuexiBayLoopbackTest {
    @Test
    void 场景3经真实TCP进场并在北码头水边收到权威纠偏() throws Exception {
        Path root = Path.of("../config-data");
        if (!root.resolve("tables/manifest.json").toFile().isFile()) root = Path.of("config-data");
        ConfigSceneTables tables = ConfigSceneTables.from(ConfigTables.load(root.resolve("tables")),
                root.resolve("navmesh/current_maps"));
        assertThat(tables.spawnPoint(3)).isEqualTo(new Vec3(36.73125, 20.19375, 0));
        Vec3 dock = new Vec3(44.4375, 59.34375, 0);
        var repository = new FakePlayerRepository();
        var server = new NodeLinkServer(1);
        var identity = new LinkIdentity(7, "yuexi-scene", 1);
        var auth = NodeLinkAuth.ofUtf8("yuexi-loopback-test-link-secret");
        long now = 1_800_000_000L;
        var meters = new SimpleMeterRegistry();
        try (var logic = Executors.newSingleThreadExecutor()) {
            SceneMetrics metrics = new SceneMetrics(meters);
            Executor dispatch = task -> logic.execute(() -> {
                task.run();
                repository.completeAll();
            });
            GateLinks links = new GateLinks(metrics, dispatch);
            SceneWorld world = new SceneWorld(tables, Contracts.IDS, links, repository,
                    new AtomicLong(9000)::incrementAndGet, new ManualClock(), metrics);
            long scene = logic.submit(() -> {
                repository.put(new PlayerData(1001, 1, 3, 1, "", 10, 3, dock));
                return world.createScene(3).sceneId();
            }).get(5, TimeUnit.SECONDS);
            var requests = new ClientRequestHandler(world, Contracts.REGISTRY, Contracts.IDS);
            var service = new SceneLinkService(identity, links, world, requests, metrics);
            var linkIds = new AtomicLong();
            int port = server.start("127.0.0.1", 0, () -> new NodeLinkHandler(identity, auth,
                    InstantSource.fixed(Instant.ofEpochSecond(now)), service, dispatch, linkIds,
                    Duration.ofSeconds(5), 100, metrics));
            try (var socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(5_000);
                var in = new DataInputStream(socket.getInputStream());
                var out = new DataOutputStream(socket.getOutputStream());
                write(out, NodeLinkFrame.newBuilder().setHello(LinkHello.newBuilder().setGateNodeId(5)
                        .setGateInstanceId("yuexi-gate").setZoneId(1).setAuthTimestamp(now).setLeaseEpoch(1)
                        .setAuthMac(auth.sign(5, "yuexi-gate", 1, 1, now))).build());
                assertThat(read(in).getHelloAck().getAccepted()).isTrue();
                write(out, NodeLinkFrame.newBuilder().setPlayerEnter(PlayerEnter.newBuilder()
                        .setSessionId(11).setPlayerId(1001).setSceneId(scene).setOwnerEpoch(1)).build());
                MessageContent entered = clientMessage(in, Contracts.IDS.notifyEnterScene());
                assertThat(EnterSceneS2C.parseFrom(entered.getSerializedMessage()).getSceneInfo().getSceneConfigId()).isEqualTo(3);
                MessageContent actor = clientMessage(in, Contracts.IDS.notifyActorCreate());
                assertThat(ActorCreateS2C.parseFrom(actor.getSerializedMessage()).getTransform().getLocation())
                        .isEqualTo(dock.toProto());
                assertThat(read(in).getPlayerEnterResult().getTipId()).isZero();

                var requested = MoveSyncC2S.newBuilder().setLocation(Location.newBuilder().setX(dock.x()).setY(62))
                        .setVelocity(Velocity.newBuilder().setY(8)).setInputSeq(42).build();
                write(out, NodeLinkFrame.newBuilder().setClientForward(ClientForward.newBuilder()
                        .setSessionId(11).setPlayerId(1001).setMessageId(Contracts.IDS.moveSync())
                        .setBody(requested.toByteString())).build());
                var frame = clientMessage(in, Contracts.IDS.notifyMoveAck());
                var ack = MoveAckS2C.parseFrom(frame.getSerializedMessage());
                assertThat(ack.getInputSeq()).isEqualTo(42);
                assertThat(ack.getServerLocation().getX()).isEqualTo(dock.x());
                assertThat(ack.getServerLocation().getY()).isBetween(59.4, 59.499);
                assertThat(ack.getServerVelocity()).isEqualTo(Velocity.getDefaultInstance());
            } finally {
                server.closeAndJoin(Duration.ofSeconds(6));
            }
        } finally {
            server.shutdown();
            meters.close();
        }
    }

    private static void write(DataOutputStream out, NodeLinkFrame frame) throws Exception {
        out.writeInt(frame.getSerializedSize());
        frame.writeTo(out);
        out.flush();
    }

    private static NodeLinkFrame read(DataInputStream in) throws Exception {
        int size = in.readInt();
        assertThat(size).isBetween(1, NodeLinkCodec.MAX_FRAME_LENGTH);
        return NodeLinkFrame.parseFrom(in.readNBytes(size));
    }

    private static MessageContent clientMessage(DataInputStream in, int expectedId) throws Exception {
        var frame = read(in);
        assertThat(frame.getBodyCase()).isEqualTo(NodeLinkFrame.BodyCase.TO_CLIENT);
        MessageContent content = MessageContent.parseFrom(frame.getToClient().getMessageContent());
        assertThat(content.getMessageId()).isEqualTo(expectedId);
        return content;
    }
}
