package com.game.scene.world;

import static com.game.scene.metrics.SceneMetrics.TeleportReason.ADMIN;
import static com.game.scene.metrics.SceneMetrics.TeleportReason.TEAM_REGROUP;
import static com.game.scene.metrics.SceneMetrics.TeleportResult.INVALID_TARGET;
import static com.game.scene.metrics.SceneMetrics.TeleportResult.TELEPORTED;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;

/** 从真实进场走统一瞬移入口；失败不能改坐标、移动锚点或发送成功瞬移消息。 */
class FestivalTeleportTest {
    @Test
    void 归队与管理瞬移不能跨10000双域或35004三域() throws Exception {
        int[] ids = {10000, 35004, 35004};
        // 冻结位图中各独立平台的安全格中心，均位于出生点以外的连通域。
        Vec3[] targets = {new Vec3(87, 339, 0), new Vec3(173, 221, 0), new Vec3(163, 51, 0)};
        for (int i = 0; i < ids.length; i++) {
            CurrentMapNavigation grid = FestivalMapNavigation.load(FestivalMapNavigationTest.root(), String.valueOf(ids[i]));
            Fixture fixture = enter(ids[i], grid);
            assertThat(grid.isWalkable(targets[i])).isTrue();
            for (var reason : List.of(ADMIN, TEAM_REGROUP)) {
                assertThat(fixture.world.teleportWithinScene(fixture.player, targets[i], null, reason)).isEqualTo(INVALID_TARGET);
                assertThat(fixture.player.position()).isEqualTo(grid.spawn());
                assertThat(fixture.player.moveGuard().anchor()).isEqualTo(grid.spawn());
                assertThat(fixture.player.teleportSettle()).isNull();
                assertThat(fixture.sink.to(1, 11)).isEmpty();
            }
        }
    }

    @Test
    void 同域可归队且贴地_阻挡和有效边界外仍拒绝() throws Exception {
        CurrentMapNavigation grid = FestivalMapNavigation.load(FestivalMapNavigationTest.root(), "10000");
        Fixture fixture = enter(10000, grid);
        Vec3 target = new Vec3(183, 63, -0.05);
        assertThat(fixture.world.teleportWithinScene(fixture.player, target, null, TEAM_REGROUP)).isEqualTo(TELEPORTED);
        Vec3 landed = new Vec3(183, 63, 0);
        assertThat(fixture.player.position()).isEqualTo(landed);
        assertThat(fixture.player.moveGuard().anchor()).isEqualTo(landed);
        assertThat(fixture.sink.messageIdsTo(1, 11)).contains(Contracts.IDS.notifyTeleport());
        fixture.sink.clear();
        assertThat(grid.isWalkable(Vec3.ORIGIN)).isFalse();
        for (Vec3 blocked : List.of(Vec3.ORIGIN, new Vec3(10000, 10000, 0), new Vec3(landed.x(), landed.y(), 0.21))) {
            assertThat(fixture.world.teleportWithinScene(fixture.player, blocked, null, ADMIN)).isEqualTo(INVALID_TARGET);
            assertThat(fixture.player.position()).isEqualTo(landed);
            assertThat(fixture.sink.to(1, 11)).isEmpty();
        }
    }

    @Test
    void 节庆导航缺失拒绝瞬移_旧图既有管理落点规则保持() {
        Fixture unavailable = enter(19004, SceneNavigation.NONE);
        Vec3 oldPosition = unavailable.player.position();
        assertThat(unavailable.world.teleportWithinScene(unavailable.player, oldPosition, null, TEAM_REGROUP)).isEqualTo(INVALID_TARGET);
        assertThat(unavailable.sink.to(1, 11)).isEmpty();

        CurrentMapNavigation oldGrid = CurrentMapNavigationTest.wallMap();
        Fixture legacy = enter(1, oldGrid);
        Vec3 oldBlockedTarget = new Vec3(21, 71, 7);
        assertThat(oldGrid.isWalkable(oldBlockedTarget)).isFalse();
        assertThat(legacy.world.teleportWithinScene(legacy.player, oldBlockedTarget, null, ADMIN)).isEqualTo(TELEPORTED);
        assertThat(legacy.player.position()).isEqualTo(oldBlockedTarget);
    }

    private record Fixture(SceneWorld world, ScenePlayer player, RecordingSink sink) { }

    private Fixture enter(int sceneId, SceneNavigation navigation) {
        FakeSceneTables legacy = new FakeSceneTables();
        SceneTables tables = new SceneTables() {
            @Override public List<Integer> worldSceneConfigIds() { return List.of(sceneId); }
            @Override public Vec3 spawnPoint(int id) {
                return navigation instanceof CurrentMapNavigation grid ? grid.spawn() : legacy.spawnPoint(id);
            }
            @Override public SceneNavigation navigation(int id) { return navigation; }
            @Override public List<Integer> initialSkills() { return legacy.initialSkills(); }
            @Override public boolean skillExists(int id) { return legacy.skillExists(id); }
            @Override public boolean mirrorExists(int id) { return legacy.mirrorExists(id); }
            @Override public OptionalInt dungeonSceneConfigId(int id) { return legacy.dungeonSceneConfigId(id); }
        };
        FakePlayerRepository repository = new FakePlayerRepository();
        RecordingSink sink = new RecordingSink();
        SceneWorld world = new SceneWorld(tables, Contracts.IDS, sink, repository, new AtomicLong(1000)::incrementAndGet,
                new ManualClock(), new SceneMetrics(new SimpleMeterRegistry()));
        Scene scene = world.createScene(sceneId);
        repository.put(new PlayerData(1001, 1, 3, 1, "", 10, sceneId, tables.spawnPoint(sceneId)));
        world.onPlayerEnter(1, SceneWorldTest.enterFrame(11, 1001, scene.sceneId(), 1));
        repository.completeAll();
        ScenePlayer player = world.playerBySession(new SessionKey(1, 11));
        assertThat(player).isNotNull();
        sink.clear();
        return new Fixture(world, player, sink);
    }
}
