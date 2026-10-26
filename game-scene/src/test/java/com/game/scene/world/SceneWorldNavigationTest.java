package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.MoveAckS2C;
import com.game.proto.Rotation;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SceneWorldNavigationTest {
    private final CurrentMapNavigation grid = CurrentMapNavigationTest.wallMap();
    private FakePlayerRepository repository;
    private RecordingSink sink;
    private SceneWorld world;
    private Scene scene;

    @BeforeEach
    void setup() {
        repository = new FakePlayerRepository();
        sink = new RecordingSink();
        FakeSceneTables legacy = new FakeSceneTables();
        SceneTables tables = new SceneTables() {
            @Override public List<Integer> worldSceneConfigIds() { return List.of(1, 2); }
            @Override public Vec3 spawnPoint(int id) { return id == 1 ? grid.spawn() : legacy.spawnPoint(id); }
            @Override public SceneNavigation navigation(int id) { return id == 1 ? grid : SceneNavigation.NONE; }
            @Override public List<Integer> initialSkills() { return legacy.initialSkills(); }
            @Override public boolean skillExists(int id) { return legacy.skillExists(id); }
            @Override public boolean mirrorExists(int id) { return legacy.mirrorExists(id); }
            @Override public OptionalInt dungeonSceneConfigId(int id) { return legacy.dungeonSceneConfigId(id); }
        };
        world = new SceneWorld(tables, Contracts.IDS, sink, repository, new AtomicLong(1000)::incrementAndGet,
                new ManualClock(), new SceneMetrics(new SimpleMeterRegistry()));
        scene = world.createScene(1);
    }

    private ScenePlayer enter(int configId, Vec3 saved) {
        repository.put(new PlayerData(1001, 1, 3, 1, "", 10, configId, saved));
        world.onPlayerEnter(1, SceneWorldTest.enterFrame(11, 1001, scene.sceneId(), 1));
        repository.completeAll();
        return world.playerBySession(new SessionKey(1, 11));
    }

    @Test
    void 同图旧存档在墙内时回当前出生_有效旧坐标保留水平且贴地() {
        ScenePlayer player = enter(1, new Vec3(21, 71, 0));
        assertThat(player.position()).isEqualTo(grid.spawn());
        assertThat(player.moveGuard().anchor()).isEqualTo(grid.spawn());
    }

    @Test
    void 合法存档沿用位置且高度归零() {
        assertThat(enter(1, new Vec3(25, 65, 7)).position()).isEqualTo(new Vec3(25, 65, 0));
    }

    @Test
    void 真实移动流程不能穿墙_纠偏带零速度_锚点合法且不补额度() throws Exception {
        ScenePlayer player = enter(0, Vec3.ORIGIN);
        world.applyMove(player, new MoveInput(new Vec3(21, 73, 0), Rotation.getDefaultInstance(), new Vec3(0, 9, 0), 42));
        assertThat(player.position().y()).isBetween(69.9, 70.0);
        assertThat(player.velocity()).isEqualTo(Vec3.ORIGIN);
        assertThat(player.moveGuard().anchor()).isEqualTo(player.position());
        assertThat(player.moveGuard().allowance()).isEqualTo(MoveGuard.CAPACITY - 4);
        var frame = sink.to(1, 11).stream().filter(m -> m.getMessageId() == Contracts.IDS.notifyMoveAck()).findFirst().orElseThrow();
        MoveAckS2C ack = MoveAckS2C.parseFrom(frame.getSerializedMessage());
        assertThat(ack.getInputSeq()).isEqualTo(42);
        assertThat(ack.getServerLocation().getY()).isEqualTo(player.position().y());
        assertThat(ack.getServerVelocity().getY()).isZero();
    }

    @Test
    void 无新输入的服务器外推也在墙前停住() {
        ScenePlayer player = enter(0, Vec3.ORIGIN);
        world.applyMove(player, new MoveInput(player.position(), Rotation.getDefaultInstance(), new Vec3(0, 10, 0), 1));
        for (int i = 0; i < 10; i++) world.step();
        assertThat(player.position().y()).isBetween(69.9, 70.0);
        assertThat(grid.isWalkable(player.position())).isTrue();
        assertThat(player.velocity()).isEqualTo(Vec3.ORIGIN);
    }

    @Test
    void 换到旧无导航场景后仍可走过新图同位置的墙() {
        ScenePlayer player = enter(0, Vec3.ORIGIN);
        world.switchScene(player, world.createScene(2));
        Vec3 from = player.position();
        Vec3 to = new Vec3(from.x() + 1, from.y() + 1, 9);
        world.applyMove(player, new MoveInput(to, Rotation.getDefaultInstance(), Vec3.ORIGIN, 1));
        assertThat(player.position()).isEqualTo(to);
    }

    @Test
    void T6_外推撞墙清速当帧133_停步位置为墙前_后续不重复() throws Exception {
        ScenePlayer player = enter(0, Vec3.ORIGIN);
        repository.put(new PlayerData(1002, 1, 3, 1, "", 10, 1, player.position()));
        world.onPlayerEnter(1, SceneWorldTest.enterFrame(12, 1002, scene.sceneId(), 1));
        repository.completeAll();
        world.applyMove(player, new MoveInput(player.position(), Rotation.getDefaultInstance(), new Vec3(0, 10, 0), 1));
        sink.clear();
        int frames = 0;
        while (!player.velocity().isOrigin() && frames++ < 20) world.step();
        assertThat(frames).isLessThan(20);
        var moves = sink.to(1, 12).stream().filter(m -> m.getMessageId() == 133).toList();
        assertThat(moves).hasSize(2);
        var stopped = com.game.proto.ActorMoveS2C.parseFrom(moves.getLast().getSerializedMessage());
        assertThat(stopped.getEntity()).isEqualTo(player.entity());
        assertThat(stopped.getTransform().getLocation()).isEqualTo(player.position().toProto());
        assertThat(stopped.hasVelocity()).isTrue();
        assertThat(stopped.getVelocity()).isEqualTo(Vec3.ORIGIN.toVelocity());
        assertThat(stopped.getMoveState()).isZero();
        assertThat(moves.getLast().getId()).isZero();
        assertThat(player.moveBroadcastPending()).isFalse();
        for (int i = 0; i < 4; i++) world.step();
        assertThat(sink.to(1, 12).stream().filter(m -> m.getMessageId() == 133)).hasSize(2);
    }
}
