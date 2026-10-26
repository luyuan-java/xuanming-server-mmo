package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;

import com.game.api.proto.ClientForward;
import com.game.proto.MoveStartC2S;
import com.game.proto.MoveStopC2S;
import com.game.proto.MoveSyncC2S;
import com.game.proto.Rotation;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;

/** 移动广播与瞬移共用的真实派发夹具，保留原 MovementSyncTest 的进场、时钟与上行路径。 */
abstract class MovementFixture {

    static final long LINK = 1;
    static final SceneMessageIds IDS = Contracts.IDS;
    static final Vec3 SPAWN = FakeSceneTables.SPAWN_1;
    static final Rotation FACING = Rotation.newBuilder().setZ(90).build();

    RecordingSink sink;
    FakePlayerRepository repo;
    ManualClock clock;
    SceneWorld world;
    ClientRequestHandler handler;
    Scene scene;
    SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        clock = new ManualClock();
        meters = new SimpleMeterRegistry();
        AtomicLong ids = new AtomicLong(7000);
        FakeSceneTables tables = new FakeSceneTables();
        world = new SceneWorld(tables, IDS, sink, repo, ids::incrementAndGet, clock, new SceneMetrics(meters));
        handler = new ClientRequestHandler(world, Contracts.REGISTRY, IDS);
        scene = world.createScene(1);
    }

    ScenePlayer enterNew(int sessionId, long playerId) {
        repo.putNewPlayer(playerId, 1);
        return enter(sessionId, playerId);
    }

    ScenePlayer enterSaved(int sessionId, long playerId, Vec3 position) {
        repo.putSavedPlayer(playerId, 1, 1, position);
        return enter(sessionId, playerId);
    }

    ScenePlayer enter(int sessionId, long playerId) {
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, scene.sceneId(), 1));
        repo.completeAll();
        return world.playerBySession(new SessionKey(LINK, sessionId));
    }

    void steps(long frames) {
        for (long i = 0; i < frames; i++) {
            world.step();
        }
    }

    void moveStart(int sessionId, long playerId, Vec3 at, Rotation rotation, Vec3 velocity, int seq) {
        MoveStartC2S.Builder move = MoveStartC2S.newBuilder()
                .setStartLocation(at.toLocation()).setVelocity(velocity.toVelocity()).setInputSeq(seq);
        if (rotation != null) {
            move.setRotation(rotation);
        }
        forward(sessionId, playerId, IDS.moveStart(), move.build(), 0);
    }

    void moveSync(int sessionId, long playerId, Vec3 at, Rotation rotation, Vec3 velocity, int seq) {
        MoveSyncC2S.Builder move = MoveSyncC2S.newBuilder()
                .setLocation(at.toLocation()).setVelocity(velocity.toVelocity()).setInputSeq(seq);
        if (rotation != null) {
            move.setRotation(rotation);
        }
        forward(sessionId, playerId, IDS.moveSync(), move.build(), 0);
    }

    void moveStop(int sessionId, long playerId, Vec3 at, Rotation rotation, int seq) {
        MoveStopC2S.Builder move = MoveStopC2S.newBuilder().setEndLocation(at.toLocation()).setInputSeq(seq);
        if (rotation != null) {
            move.setRotation(rotation);
        }
        forward(sessionId, playerId, IDS.moveStop(), move.build(), 0);
    }

    void forward(int sessionId, long playerId, int messageId, Message body, long requestId) {
        forward(sessionId, playerId, messageId, body.toByteString(), requestId);
    }

    void forward(int sessionId, long playerId, int messageId, ByteString body, long requestId) {
        handler.onClientForward(LINK, ClientForward.newBuilder()
                .setSessionId(sessionId)
                .setPlayerId(playerId)
                .setMessageId(messageId)
                .setBody(body)
                .setRequestId(requestId)
                .build());
    }

}
