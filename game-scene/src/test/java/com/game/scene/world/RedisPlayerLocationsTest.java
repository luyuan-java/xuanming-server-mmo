package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static com.game.scene.world.SceneWorldTest.leave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.PlayerLocationDirectory.Refresh;
import com.game.discovery.proto.PlayerLocation;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.location.RedisPlayerLocations;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 写位置记录的序号与内容：同一次进场内单调递增、断线带最后所在的位置、续期用当前序号不递增。 */
class RedisPlayerLocationsTest {

    private static final long LINK = 7;

    private record Write(String action, long seq, long sceneId) {
    }

    private final List<Write> writes = new ArrayList<>();
    private final List<Refresh> refreshes = new ArrayList<>();
    private final PlayerLocationDirectory directory = mock(PlayerLocationDirectory.class);
    private FakePlayerRepository repo;
    private SceneWorld world;
    private Scene map1;
    private Scene map2;

    @BeforeEach
    void setUp() {
        when(directory.putAsync(any(), anyLong())).thenAnswer(call -> {
            PlayerLocation at = call.getArgument(0);
            writes.add(new Write("put", call.getArgument(1), at.getSceneId()));
            assertThat(at.getZoneId()).isEqualTo(3);
            assertThat(at.getSceneNodeId()).isEqualTo(9);
            return CompletableFuture.completedFuture(true);
        });
        when(directory.leaseAsync(any(), anyLong())).thenAnswer(call -> {
            PlayerLocation at = call.getArgument(0);
            writes.add(new Write("lease", call.getArgument(1), at.getSceneId()));
            return CompletableFuture.completedFuture(true);
        });
        when(directory.removeAsync(anyLong(), anyLong(), anyLong())).thenAnswer(call -> {
            writes.add(new Write("remove", call.getArgument(2), 0));
            return CompletableFuture.completedFuture(true);
        });
        when(directory.refreshAsync(anyCollection())).thenAnswer(call -> {
            Collection<Refresh> batch = call.getArgument(0);
            refreshes.addAll(batch);
            return CompletableFuture.completedFuture(0);
        });
        repo = new FakePlayerRepository();
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repo,
                new AtomicLong(1000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(), PlayerInitializer.NONE,
                PlayerSnapshots.NONE, new RedisPlayerLocations(directory, 3, 9, Runnable::run));
        map1 = world.createScene(1);
        map2 = world.createScene(2);
    }

    private void enter(int sessionId, long playerId, long sceneId, long epoch) {
        repo.putNewPlayer(playerId, epoch);
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, sceneId, epoch));
        repo.completeAll();
    }

    @Test
    void 进场_换场景_续期_断线_序号递增_断线带最后所在的位置() {
        enter(11, 1000, map1.sceneId(), 3);
        world.switchScene(world.playerBySession(new SessionKey(LINK, 11)), map2);
        for (int i = 0; i < 20; i++) {
            world.refreshDueLocations();
        }
        world.onPlayerLeave(LINK, leave(11, 1000).toBuilder().setVoluntary(false).build());

        assertThat(writes).containsExactly(
                new Write("put", 1, map1.sceneId()),
                new Write("put", 2, map2.sceneId()),
                new Write("lease", 3, map2.sceneId()));
        assertThat(refreshes).singleElement().satisfies(r -> {
            assertThat(r.seq()).as("续期用当前序号，不递增").isEqualTo(2);
            assertThat(r.location().getSceneId()).isEqualTo(map2.sceneId());
        });
    }

    @Test
    void 主动离开写墓碑_序号接着数() {
        enter(11, 1000, map1.sceneId(), 3);
        world.onPlayerLeave(LINK, leave(11, 1000));

        assertThat(writes).containsExactly(new Write("put", 1, map1.sceneId()), new Write("remove", 2, 0));
    }

    @Test
    void 同一epoch的重复进场接替旧实例_序号接着旧实例往下数() {
        enter(11, 1000, map1.sceneId(), 3);
        world.switchScene(world.playerBySession(new SessionKey(LINK, 11)), map2);
        enter(12, 1000, map2.sceneId(), 3);

        assertThat(writes).extracting(Write::seq).containsExactly(1L, 2L, 3L);
    }
}
