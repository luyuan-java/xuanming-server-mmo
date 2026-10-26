package com.game.scene.team;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.SceneTeamRefresh;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.scene.world.PlayerInitializer;
import com.game.scene.world.PlayerLocations;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SceneTeamProviderTest {
    private static SceneTeamRefresh request(long playerId, String instance, boolean follow) {
        return SceneTeamRefresh.newBuilder().setPlayerId(playerId).setTargetInstanceId(instance).setFollow(follow).build();
    }

    @Test
    void 拒绝空请求零玩家与不匹配实例_不排队也不访问世界() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicInteger reads = new AtomicInteger();
        SceneTeamProvider provider = new SceneTeamProvider(() -> { reads.incrementAndGet(); return null; }, "instance", queue::add);
        for (SceneTeamRefresh bad : List.of(request(0, "instance", false), request(1, "", false), request(1, "old", true))) {
            assertThat(provider.refresh(bad).join().getAccepted()).isFalse();
        }
        assertThat(provider.refresh(null).join().getAccepted()).isFalse();
        assertThat(queue).isEmpty();
        assertThat(reads).hasValue(0);
    }

    @Test
    void 排队上限1024_只在逻辑线程访问世界_执行后释放名额() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicInteger reads = new AtomicInteger();
        SceneTeamProvider provider = new SceneTeamProvider(() -> { reads.incrementAndGet(); return null; }, "instance", queue::add);
        for (int i = 0; i < SceneTeamProvider.MAX_QUEUED; i++) assertThat(provider.refresh(request(1, "instance", true)).join().getAccepted()).isTrue();
        assertThat(provider.refresh(request(1, "instance", true)).join().getAccepted()).isFalse();
        assertThat(reads).hasValue(0);
        queue.remove().run();
        assertThat(reads).hasValue(1);
        assertThat(provider.refresh(request(1, "instance", false)).join().getAccepted()).isTrue();
        assertThat(queue).hasSize(SceneTeamProvider.MAX_QUEUED);
    }

    @Test
    void 执行器拒绝异常完成_名额不会泄漏() {
        AtomicInteger submissions = new AtomicInteger();
        SceneTeamProvider provider = new SceneTeamProvider(() -> null, "instance", task -> {
            submissions.incrementAndGet();
            throw new RejectedExecutionException("stopped");
        });
        for (int i = 0; i < SceneTeamProvider.MAX_QUEUED + 1; i++) assertThat(provider.refresh(request(1, "instance", true))).isCompletedExceptionally();
        assertThat(submissions).hasValue(SceneTeamProvider.MAX_QUEUED + 1);
    }

    @Test
    void 在线玩家仅逻辑线程收到follow标志_离线玩家提示丢弃() {
        Queue<Runnable> queue = new ArrayDeque<>();
        List<String> calls = new ArrayList<>();
        TeamFollow follow = new TeamFollow() {
            @Override public void onEnteredScene(SceneWorld world, ScenePlayer player) { }
            @Override public void onRefresh(SceneWorld world, ScenePlayer player, boolean shouldFollow) {
                calls.add(player.playerId() + ":" + shouldFollow);
            }
        };
        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, new RecordingSink(), repo,
                new AtomicLong(1000)::incrementAndGet, new ManualClock(), new SceneMetrics(new SimpleMeterRegistry()),
                PlayerInitializer.NONE, PlayerSnapshots.NONE, PlayerLocations.NONE, follow);
        var scene = world.createScene(1);
        repo.putNewPlayer(1, 1);
        world.onPlayerEnter(1, PlayerEnter.newBuilder().setPlayerId(1).setSessionId(1).setSceneId(scene.sceneId()).setOwnerEpoch(1).build());
        repo.completeAll();
        SceneTeamProvider provider = new SceneTeamProvider(() -> world, "instance", queue::add);
        assertThat(provider.refresh(request(1, "instance", true)).join().getAccepted()).isTrue();
        assertThat(provider.refresh(request(1, "instance", false)).join().getAccepted()).isTrue();
        assertThat(provider.refresh(request(2, "instance", true)).join().getAccepted()).isTrue();
        assertThat(calls).isEmpty();
        while (!queue.isEmpty()) queue.remove().run();
        assertThat(calls).containsExactly("1:true", "1:false");
    }
}
