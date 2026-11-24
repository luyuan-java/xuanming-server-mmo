package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 断线保留同代重连接回：复用会话时必须驱逐旧会话上的其他角色，避免跨玩家串线。 */
class ConnectionLostHoldReconnectTest {

    @Test
    void resumeConnectionLostHold_evictsExistingOccupantOnReusedSession() {
        RecordingSink sink = new RecordingSink();
        FakePlayerRepository repo = new FakePlayerRepository();
        ManualClock clock = new ManualClock();
        clock.advanceMillis(1_700_000_000_000L);
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo,
                new AtomicLong(5000)::incrementAndGet, clock, new SceneMetrics(new SimpleMeterRegistry()),
                p -> {}, PlayerSnapshots.NONE).withConnectionLostHold(true);
        Scene scene = world.createScene(1);

        long p1 = 1001L;
        long p2 = 1002L;
        repo.put(new PlayerData(p1, 5L, 1, 1, "", 10, 0, Vec3.ORIGIN, null, "角色甲", 0L));
        repo.put(new PlayerData(p2, 6L, 1, 1, "", 10, 0, Vec3.ORIGIN, null, "角色乙", 0L));

        // 玩家 1 进场，再非主动断线进入 ConnectionLostHold
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(11).setPlayerId(p1).setSceneId(scene.sceneId()).setOwnerEpoch(5L).build());
        repo.completeAll();
        world.onPlayerLeave(1L, PlayerLeave.newBuilder().setSessionId(11).setPlayerId(p1).setVoluntary(false).build());
        assertThat(world.playerById(p1)).isNotNull();
        assertThat(world.playerById(p1).connectionLostHold()).isNotNull();

        // 会话 22 当前由玩家 2 占用
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(22).setPlayerId(p2).setSceneId(scene.sceneId()).setOwnerEpoch(6L).build());
        repo.completeAll();
        assertThat(world.playerById(p2)).isNotNull();

        // 玩家 1 复用会话 22 重连接回：必须驱逐旧占用者玩家 2，防止双角色共享同一 session 串线
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(22).setPlayerId(p1).setSceneId(scene.sceneId()).setOwnerEpoch(5L).build());

        assertThat(world.playerById(p2)).as("被复用会话上的旧角色必须被移除").isNull();
        assertThat(world.playerById(p1).connectionLostHold()).isNull();
        assertThat(world.playerById(p1).session()).isEqualTo(new SessionKey(1L, 22));
    }
}
