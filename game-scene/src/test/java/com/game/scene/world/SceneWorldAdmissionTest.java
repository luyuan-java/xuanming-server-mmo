package com.game.scene.world;

import static org.assertj.core.api.Assertions.*;

import com.game.api.proto.PlayerEnter;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SceneWorldAdmissionTest {
    private final FakePlayerRepository repository = new FakePlayerRepository();
    private final RecordingSink sink = new RecordingSink();
    private final SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repository,
            new AtomicLong(9000)::incrementAndGet, new ManualClock(), SceneMetrics.noop(), PlayerInitializer.NONE,
            PlayerSnapshots.NONE, PlayerLocations.NONE, TeamFollow.NONE, CrossNodeSwitch.DISABLED,
            SceneInstances.DISABLED, BattleHooks.NONE, ZoneTravel.DISABLED, InboxHooks.NONE,
            new SceneAdmissionSource("9", "scene-original", 1));
    private final Scene scene = world.createScene(1);

    private PlayerEnter enter(int session) {
        return PlayerEnter.newBuilder().setPlayerId(7).setSessionId(session).setSceneId(scene.sceneId())
                .setOwnerEpoch(5).setAccount(" 原账号 ").setOwnerRevision(3).setLoginGeneration(4).build();
    }

    private void row() { repository.put(new PlayerData(7, 5, 1, 1, "", 1, 1, new Vec3(180, 200, 0))); }

    @Test
    void 加载与实体共用同一原件_原账号空白不被改写() {
        row(); world.onPlayerEnter(1, enter(11)); repository.completeAll();
        var request = repository.originalLoads().getFirst();
        assertThat(request.account()).isEqualTo(" 原账号 ");
        assertThat(world.playerById(7).admission()).isSameAs(request.admission());
        assertThat(request.admission().getLoginGeneration()).isEqualTo(4);
        assertThat(request.admission().getGeneration()).isEqualTo(5);
    }

    @Test
    void 同E不同session不替换原实体_不释放已活动原持有代() {
        row(); world.onPlayerEnter(1, enter(11)); repository.completeAll();
        var original = world.playerById(7);
        world.onPlayerEnter(2, enter(22)); repository.completeAll();
        assertThat(world.playerById(7)).isSameAs(original);
        assertThat(original.session().sessionId()).isEqualTo(11);
        assertThat(repository.releases()).isEmpty();
        assertThat(sink.results().getLast().tipId()).isNotZero();
    }

    @Test
    void 同E原字节重放沿用原准入_加载中取消按原件放弃() {
        row(); world.onPlayerEnter(1, enter(11)); repository.completeAll();
        var original = world.playerById(7).admission();
        world.onPlayerEnter(1, enter(11)); repository.completeAll();
        assertThat(world.playerById(7).admission()).isEqualTo(original);
        assertThat(repository.releases()).isEmpty();

        world.onPlayerEnter(2, enter(22).toBuilder().setPlayerId(8).build());
        world.onPlayerLeave(2, SceneWorldTest.leave(22, 8));
        assertThat(repository.releases()).containsExactly(new FakePlayerRepository.Release(8, 5));
    }

    @Test
    void 原字段缺失在存储调用前拒绝_不能凭裸E释放() {
        world.onPlayerEnter(1, enter(11).toBuilder().clearOwnerRevision().build());
        assertThat(repository.originalLoads()).isEmpty();
        assertThat(repository.releases()).isEmpty();
        assertThat(sink.results().getLast().tipId()).isNotZero();
    }

    @Test
    void 加载在途的同E冲突重放不能赶走原加载或提前释放() {
        row(); world.onPlayerEnter(1, enter(11));
        world.onPlayerEnter(1, enter(11).toBuilder().setOwnerRevision(4).build());
        assertThat(repository.originalLoads()).hasSize(1);
        assertThat(repository.releases()).isEmpty();
        repository.completeAll();
        assertThat(world.playerById(7).admission().getOwnerRevision()).isEqualTo(3);
        assertThat(repository.releases()).isEmpty();
    }
}
