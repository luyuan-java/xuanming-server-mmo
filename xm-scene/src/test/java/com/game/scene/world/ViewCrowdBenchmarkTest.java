package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.MessageContent;
import com.game.proto.Rotation;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 出生点人群基准：视野刷新（{@link ViewIndex#refresh}）与 66 扇出各占一帧多少。<b>默认跳过</b>，不拖慢 {@code clean install}；
 * 显式开启：{@code ./mvnw -o -pl xm-scene test -Dtest=ViewCrowdBenchmarkTest -Dxm.bench=true -Dsurefire.failIfNoSpecifiedTests=false}。
 *
 * <p><b>场景</b>：N 人（1000 / 2000）全部落在出生点周围半径 {@link #RADIUS} 内（新号与换地图的人都落在同一个出生点），
 * 速度随机、非零（1–9 m/s）；每人每 250 ms 上报一条 MoveSync（位置 = 当前外推位置，错开到不同帧），
 * 跑出半径的人在下一次上报时掉头朝出生点走。随机数定种，两次运行的人群一致。
 *
 * <p><b>输出</b>（只打印、不断言耗时，耗时与机器相关；标签用 ASCII，避免控制台编码把中文打成问号）：
 * <ul>
 *   <li>A. {@link SceneWorld#step()} 整帧耗时分布，拆出 66 扇出（偶数帧）与 47 / 64 下发；整帧减去这两段 ≈ 外推 + 视野刷新；</li>
 *   <li>B. 只跑「与外推同样的位置变化 + {@link Scene#refreshViews}」，单独得到视野刷新的耗时分布。</li>
 * </ul>
 * 断言只用来确认场景确实是「人群」：每人兴趣列表平均接近满。
 */
@EnabledIfSystemProperty(named = "xm.bench", matches = "true")
class ViewCrowdBenchmarkTest {

    private static final long LINK = 1;
    private static final Vec3 SPAWN = FakeSceneTables.SPAWN_1;
    private static final double RADIUS = 15.0;
    private static final Rotation FACING = Rotation.newBuilder().setZ(90).build();
    private static final int WARMUP_FRAMES = 100;
    private static final int MEASURE_FRAMES = 200;
    /** 每人每 5 帧（250 ms）上报一次 MoveSync。 */
    private static final int REPORT_EVERY_FRAMES = 5;

    private static final ClientSink NOOP_SINK = new ClientSink() {
        @Override
        public void send(long linkId, List<Integer> sessionIds, MessageContent content) {
        }

        @Override
        public void enterResult(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
        }

        @Override
        public void playerKicked(long linkId, int sessionId, long playerId, long ownerEpoch, int tipId) {
        }
    };

    @Test
    void 出生点人群_整帧与视野刷新耗时() {
        for (int players : new int[] {1000, 2000}) {
            worldLevel(players);
            indexLevel(players);
        }
    }

    // ------------------------------------------------------------------ A. 整帧

    private void worldLevel(int count) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakePlayerRepository repo = new FakePlayerRepository();
        AtomicLong ids = new AtomicLong(1_000_000);
        FrameClock clock = new FrameClock();
        SceneWorld world = new SceneWorld(new FakeSceneTables(), Contracts.IDS, NOOP_SINK, repo,
                ids::incrementAndGet, clock, new SceneMetrics(registry));
        Scene scene = world.createScene(1);
        Random random = new Random(42);
        List<ScenePlayer> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int session = i + 1;
            long playerId = 10_000L + i;
            repo.putSavedPlayer(playerId, 1, 1, randomInDisk(random));
            world.onPlayerEnter(LINK, enterFrame(session, playerId, scene.sceneId(), 1));
            repo.completeAll();
            ScenePlayer player = world.playerBySession(new SessionKey(LINK, session));
            world.applyMove(player, new MoveInput(player.position(), FACING, randomVelocity(random), 0));
            players.add(player);
        }
        Timer sync = registry.get("xm.scene.broadcast").tag("kind", "attribute_sync").timer();
        Timer views = registry.get("xm.scene.broadcast").tag("kind", "view_changes").timer();

        long[] frameNanos = new long[MEASURE_FRAMES];
        long[] syncNanos = new long[MEASURE_FRAMES];
        long[] viewNanos = new long[MEASURE_FRAMES];
        for (int f = 0; f < WARMUP_FRAMES + MEASURE_FRAMES; f++) {
            clock.nextFrame();
            report(world, players, random, f);
            double syncBefore = sync.totalTime(TimeUnit.NANOSECONDS);
            double viewBefore = views.totalTime(TimeUnit.NANOSECONDS);
            long start = System.nanoTime();
            world.step();
            long elapsed = System.nanoTime() - start;
            if (f >= WARMUP_FRAMES) {
                int m = f - WARMUP_FRAMES;
                frameNanos[m] = elapsed;
                syncNanos[m] = (long) (sync.totalTime(TimeUnit.NANOSECONDS) - syncBefore);
                viewNanos[m] = (long) (views.totalTime(TimeUnit.NANOSECONDS) - viewBefore);
            }
        }
        long[] rest = new long[MEASURE_FRAMES];
        for (int m = 0; m < MEASURE_FRAMES; m++) {
            rest[m] = frameNanos[m] - syncNanos[m] - viewNanos[m];
        }
        long[] syncEven = new long[MEASURE_FRAMES / 2];
        for (int m = 0, k = 0; m < MEASURE_FRAMES && k < syncEven.length; m++) {
            if ((WARMUP_FRAMES + m) % 2 == 0) {
                syncEven[k++] = syncNanos[m];
            }
        }
        System.out.printf(Locale.ROOT, "[bench] A world N=%d%n", count);
        print("  step()", frameNanos);
        print("  integrate+refresh (step - broadcasts)", rest);
        print("  47/64 emit", viewNanos);
        print("  66 fan-out (even frames)", syncEven);
        System.out.printf(Locale.ROOT, "  avg watching %.1f%n", averageWatching(scene, players));
        assertThat(averageWatching(scene, players)).as("确实是人群：兴趣列表接近满").isGreaterThan(80);
    }

    /** 每人每 {@link #REPORT_EVERY_FRAMES} 帧上报一次（按下标错开）；跑出半径的人掉头朝出生点。 */
    private static void report(SceneWorld world, List<ScenePlayer> players, Random random, int frame) {
        for (int k = 0; k < players.size(); k++) {
            if ((frame + k) % REPORT_EVERY_FRAMES != 0) {
                continue;
            }
            ScenePlayer player = players.get(k);
            Vec3 velocity = player.velocity();
            if (player.position().horizontalDistance(SPAWN) > RADIUS || velocity.isOrigin()) {
                velocity = towardSpawn(player.position(), random);
            }
            world.touch(player);
            world.applyMove(player, new MoveInput(player.position(), FACING, velocity, frame));
        }
    }

    // ------------------------------------------------------------------ B. 只测视野刷新

    private void indexLevel(int count) {
        Scene scene = new Scene(com.game.proto.SceneInfoComp.newBuilder().setSceneId(1).setSceneConfigId(1).build());
        Random random = new Random(42);
        List<ScenePlayer> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ScenePlayer player = new ScenePlayer(10_000L + i, 1_000_000L + i, new SessionKey(LINK, i + 1), 1, 1, 0,
                    "", 1, List.of(), randomInDisk(random), 0);
            player.setScene(scene);
            scene.add(player);
            player.setVelocity(randomVelocity(random));
            players.add(player);
        }
        ViewChanges changes = new ViewChanges();
        scene.refreshViews(changes);
        changes.clear();
        long[] refreshNanos = new long[MEASURE_FRAMES];
        for (int f = 0; f < WARMUP_FRAMES + MEASURE_FRAMES; f++) {
            for (int k = 0; k < players.size(); k++) {
                ScenePlayer player = players.get(k);
                if ((f + k) % REPORT_EVERY_FRAMES == 0 && player.position().horizontalDistance(SPAWN) > RADIUS) {
                    player.setVelocity(towardSpawn(player.position(), random));
                }
                scene.relocate(player, player.position().plusScaled(player.velocity(), MovementRules.STEP_SECONDS));
            }
            long start = System.nanoTime();
            scene.refreshViews(changes);
            long elapsed = System.nanoTime() - start;
            changes.clear();
            if (f >= WARMUP_FRAMES) {
                refreshNanos[f - WARMUP_FRAMES] = elapsed;
            }
        }
        System.out.printf(Locale.ROOT, "[bench] B refresh only N=%d%n", count);
        print("  refreshViews", refreshNanos);
        System.out.printf(Locale.ROOT, "  avg watching %.1f%n", averageWatching(scene, players));
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 真实单调时钟 + 每帧额外前进 50 ms：帧内各段的耗时照实测量，位移校验的额度按「每帧 50 ms」累积
     * （基准跑得比实时快时，不会因为额度攒不起来而把人群截停）。
     */
    private static final class FrameClock implements SceneClock {

        private long offset;

        void nextFrame() {
            offset += TimeUnit.MILLISECONDS.toNanos(50);
        }

        @Override
        public long nanoTime() {
            return System.nanoTime() + offset;
        }

        @Override
        public long epochMillis() {
            return System.currentTimeMillis();
        }
    }

    private static Vec3 randomInDisk(Random random) {
        double r = RADIUS * Math.sqrt(random.nextDouble());
        double a = random.nextDouble() * 2 * Math.PI;
        return new Vec3(SPAWN.x() + r * Math.cos(a), SPAWN.y() + r * Math.sin(a), 0);
    }

    private static Vec3 randomVelocity(Random random) {
        double speed = 1 + random.nextDouble() * 8;
        double a = random.nextDouble() * 2 * Math.PI;
        return new Vec3(speed * Math.cos(a), speed * Math.sin(a), 0);
    }

    private static Vec3 towardSpawn(Vec3 from, Random random) {
        double speed = 1 + random.nextDouble() * 8;
        double dx = SPAWN.x() - from.x();
        double dy = SPAWN.y() - from.y();
        double length = Math.hypot(dx, dy);
        if (length == 0) {
            return randomVelocity(random);
        }
        return new Vec3(dx / length * speed, dy / length * speed, 0);
    }

    private static double averageWatching(Scene scene, List<ScenePlayer> players) {
        long total = 0;
        for (ScenePlayer player : players) {
            total += scene.watching(player).size();
        }
        return (double) total / players.size();
    }

    private static void print(String label, long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0);
        System.out.printf(Locale.ROOT, "%s: mean=%.2fms p50=%.2fms p90=%.2fms p99=%.2fms max=%.2fms (n=%d)%n", label,
                ms(mean), ms(sorted[sorted.length / 2]), ms(sorted[(int) (sorted.length * 0.9)]),
                ms(sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))]), ms(sorted[sorted.length - 1]),
                sorted.length);
    }

    private static double ms(double nanos) {
        return nanos / 1_000_000.0;
    }
}
