package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static com.game.scene.world.SceneWorldTest.leave;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 玩家位置记录的写时机（批次 3.3）：进场 / 换场景写当前实例、断线缩到重连租约、主动离开删、被接管 / 失去归属 / 停服不动、
 * 在线续期每秒一个槽。
 */
class PlayerLocationHooksTest {

    private static final long LINK = 7;

    /** 记下每次调用：动作、玩家、epoch、场景号。 */
    private record Call(String action, long playerId, long epoch, long sceneId) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final List<List<Long>> refreshes = new ArrayList<>();
    private final PlayerLocations recording = new PlayerLocations() {
        @Override
        public void entered(ScenePlayer player) {
            calls.add(new Call("entered", player.playerId(), player.ownerEpoch(), player.scene().sceneId()));
        }

        @Override
        public void disconnected(ScenePlayer player) {
            calls.add(new Call("disconnected", player.playerId(), player.ownerEpoch(), 0));
        }

        @Override
        public void loggedOut(ScenePlayer player) {
            calls.add(new Call("loggedOut", player.playerId(), player.ownerEpoch(), 0));
        }

        @Override
        public void loggedOutWhileLoading(long playerId, long ownerEpoch) {
            calls.add(new Call("loggedOutWhileLoading", playerId, ownerEpoch, 0));
        }

        @Override
        public void refresh(Collection<ScenePlayer> players) {
            refreshes.add(players.stream().map(ScenePlayer::playerId).sorted().toList());
        }
    };

    private FakePlayerRepository repo;
    private SceneWorld world;
    private Scene map1;
    private Scene map2;

    @BeforeEach
    void setUp() {
        repo = new FakePlayerRepository();
        world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repo,
                new AtomicLong(1000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(), PlayerInitializer.NONE,
                PlayerSnapshots.NONE, recording);
        map1 = world.createScene(1);
        map2 = world.createScene(2);
    }

    private void enter(int sessionId, long playerId, long sceneId, long epoch) {
        repo.putNewPlayer(playerId, epoch);
        world.onPlayerEnter(LINK, enterFrame(sessionId, playerId, sceneId, epoch));
        repo.completeAll();
    }

    @Test
    void 进场写当前实例_换场景再写_主动离开删() {
        enter(11, 1001, map1.sceneId(), 3);
        world.switchScene(world.playerBySession(new SessionKey(LINK, 11)), map2);
        world.onPlayerLeave(LINK, leave(11, 1001));

        assertThat(calls).containsExactly(
                new Call("entered", 1001, 3, map1.sceneId()),
                new Call("entered", 1001, 3, map2.sceneId()),
                new Call("loggedOut", 1001, 3, 0));
    }

    @Test
    void 断线与gate链路断开都缩到重连租约() {
        enter(11, 1001, map1.sceneId(), 3);
        enter(12, 1002, map1.sceneId(), 4);
        enter(13, 1003, map1.sceneId(), 5);
        calls.clear();

        world.onPlayerLeave(LINK, leave(11, 1001).toBuilder().setVoluntary(false).build());
        world.onLinkClosed(LINK);

        assertThat(calls).containsExactlyInAnyOrder(
                new Call("disconnected", 1001, 3, 0),
                new Call("disconnected", 1002, 4, 0),
                new Call("disconnected", 1003, 5, 0));
    }

    @Test
    void 被接管_失去归属_停服_加载中离开都不动位置记录() {
        enter(11, 1001, map1.sceneId(), 3);
        enter(12, 1002, map1.sceneId(), 4);
        enter(13, 1003, map1.sceneId(), 5);
        repo.putNewPlayer(1004, 6);
        world.onPlayerEnter(LINK, enterFrame(14, 1004, map1.sceneId(), 6));
        calls.clear();

        world.onTakeoverRequested(1001, 3);
        world.onOwnershipLost(List.of(new OwnedPlayer(1002, 4)));
        world.onPlayerLeave(LINK, leave(14, 1004).toBuilder().setVoluntary(false).build());
        world.shutdown();

        assertThat(calls).isEmpty();
    }

    @Test
    void 加载中就LeaveGame_用这次进场的epoch写登出墓碑_加载中断线不动() {
        repo.putNewPlayer(1001, 6);
        world.onPlayerEnter(LINK, enterFrame(11, 1001, map1.sceneId(), 6));
        repo.putNewPlayer(1002, 7);
        world.onPlayerEnter(LINK, enterFrame(12, 1002, map1.sceneId(), 7));

        world.onPlayerLeave(LINK, leave(11, 1001));
        world.onPlayerLeave(LINK, leave(12, 1002).toBuilder().setVoluntary(false).build());
        repo.completeAll();

        assertThat(calls).containsExactly(new Call("loggedOutWhileLoading", 1001, 6, 0));
    }

    @Test
    void 同一会话换角色进场_旧角色按主动离开删记录() {
        enter(11, 1001, map1.sceneId(), 3);
        calls.clear();

        enter(11, 1002, map1.sceneId(), 4);

        assertThat(calls).containsExactly(
                new Call("loggedOut", 1001, 3, 0),
                new Call("entered", 1002, 4, map1.sceneId()));
    }

    @Test
    void 在线续期每秒一个槽_每人每个周期恰好一次() {
        int slots = SceneWorld.LOCATION_REFRESH_SLOTS;
        assertThat(slots).isEqualTo(20);
        enter(11, 1000, map1.sceneId(), 1);
        enter(12, 1001, map1.sceneId(), 1);
        enter(13, 1020, map1.sceneId(), 1);

        List<Long> seen = new ArrayList<>();
        int total = 0;
        for (int second = 0; second < slots; second++) {
            total += world.refreshDueLocations();
        }
        refreshes.forEach(seen::addAll);

        assertThat(total).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrder(1000L, 1001L, 1020L);
        assertThat(refreshes).as("1000 与 1020 同槽，一批续").contains(List.of(1000L, 1020L));
    }
}
