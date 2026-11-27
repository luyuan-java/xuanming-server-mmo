package com.game.scene.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerLeave;
import com.game.scene.afkpass.AfkPassService;
import com.game.scene.afkpass.AfkPassTables;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 验证 SceneWorld 逻辑线程归属守卫与 AfkPassService 异步回调防陈旧实例确认。 */
final class SceneActorIsolationTest {

    @Test
    void logicThreadGuardRejectsOffLoopAccess() {
        AtomicBoolean inLoop = new AtomicBoolean(true);
        SceneWorld world = new SceneWorld(
                new FakeSceneTables(),
                Contracts.IDS,
                new RecordingSink(),
                new FakePlayerRepository(),
                new AtomicLong(100)::incrementAndGet,
                new ManualClock(),
                SceneMetrics.noop()
        ).withLogicThreadGuard(inLoop::get);

        assertThat(world.playerById(1L)).isNull();
        world.step();

        inLoop.set(false);
        assertThatThrownBy(() -> world.playerById(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scene-logic");
        assertThatThrownBy(world::step)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scene-logic");
    }

    @Test
    void afkPassProjectionConfirmationDropsStalePlayerAfterLeave() {
        CompletableFuture<Boolean> pendingWrite = new CompletableFuture<>();
        AfkPassService.ProjectionSink sink = new AfkPassService.ProjectionSink() {
            @Override
            public CompletionStage<Boolean> set(long playerId, long expiresAtSec, Duration ttl) {
                return pendingWrite;
            }

            @Override
            public CompletionStage<Boolean> delete(long playerId) {
                return pendingWrite;
            }
        };

        ManualClock clock = new ManualClock();
        clock.setEpochMillis(1_700_000_000_000L);
        SceneMetrics metrics = SceneMetrics.noop();
        AfkPassTables tables = AfkPassTables.of(new AfkPassTables.Rule(30, 1, 300, 60, 72));
        CurrencyService currency = new CurrencyService(AssetAudit.log(), GainAnomalyDetector.off(), metrics, clock);
        AfkPassService afkPass = new AfkPassService(tables, currency, clock, sink, Runnable::run);

        FakePlayerRepository repo = new FakePlayerRepository();
        SceneWorld world = new SceneWorld(
                new FakeSceneTables(),
                Contracts.IDS,
                new RecordingSink(),
                repo,
                new AtomicLong(500)::incrementAndGet,
                clock,
                metrics
        ).withAfkPassService(afkPass);

        Scene scene = world.createScene(1);
        repo.put(new PlayerData(1001L, 1L, 1, 1, "00_default", 10, 1, new Vec3(10, 10, 0)));
        world.onPlayerEnter(1L, PlayerEnter.newBuilder()
                .setSessionId(101).setPlayerId(1001L).setSceneId(scene.sceneId()).setOwnerEpoch(1L).build());
        repo.completeAll();
        ScenePlayer loaded = world.playerById(1001L);
        assertThat(loaded).isNotNull();
        assertThat(loaded.hasUnconfirmedAfkPassProjection()).isTrue();

        // 模拟玩家在 Redis 投影异步完成前真实离场，随后迟到的异步回调才返回
        world.onPlayerLeave(1L, PlayerLeave.newBuilder()
                .setSessionId(101).setPlayerId(1001L).setVoluntary(true).build());
        repo.completeAll();
        assertThat(world.playerById(1001L)).isNull();
        pendingWrite.complete(true);

        // 旧实例不应被陈旧跨线程回调改写确认状态
        assertThat(loaded.hasUnconfirmedAfkPassProjection()).isTrue();
    }
}
