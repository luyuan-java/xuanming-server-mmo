package com.game.scene.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.proto.PlayerLocation;
import com.game.scene.testing.CapturedLogs;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

/**
 * 跨 zone 传送的待落点写口（批次 5.4，zone-travel-spec §5.3、§5.9、§11.5「线程」）：记录的形状与 (epoch, 序号)、TTL 截到 [1 s, 300 s]、
 * 结果只经逻辑执行器投递（恰好一次、不在调用栈内、从 Redisson 的回调线程完成时也回到逻辑线程）、写失败 / 没生效 / 参数被拒都回 false、
 * 逻辑线程已停时丢弃（回调在哪条线程上都不执行）。位置目录是替身（真 Redis 的用例见 {@link AwaitingPlacementRedisIntegrationTest}）。
 */
class RedisPlayerLocationsAwaitingPlacementTest {

    private static final long PLAYER = 0x8000_0000_0000_1001L;   // ≥ 2^63：id 一律按无符号处理

    /** 一次待落点写：调用时的参数与测试控制的结果。 */
    private record Call(PlayerLocation location, long seq, Duration ttl, CompletableFuture<Boolean> reply) {
    }

    private final PlayerLocationDirectory directory = mock(PlayerLocationDirectory.class);
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    /** 假的「逻辑线程」：只收任务（任意线程可投），测试线程自己取出来跑。 */
    private final LinkedBlockingQueue<Runnable> logicQueue = new LinkedBlockingQueue<>();
    private final RedisPlayerLocations locations = new RedisPlayerLocations(directory, 1, 9, logicQueue::add);

    @BeforeEach
    void setUp() {
        when(directory.awaitPlacementAsync(any(), anyLong(), any())).thenAnswer(inv -> {
            CompletableFuture<Boolean> reply = new CompletableFuture<>();
            calls.add(new Call(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), reply));
            return reply;
        });
    }

    /** 源节点上的实例：epoch 1（WorldTestAccess 的缺省），之前已写过 {@code writes} 次位置。 */
    private static ScenePlayer removed(int writes) {
        ScenePlayer player = WorldTestAccess.player(PLAYER);
        for (int i = 0; i < writes; i++) {
            player.nextLocationSeq();
        }
        return player;
    }

    /** 取出逻辑队列里唯一的任务在测试线程上执行；断言之后再没有别的任务（恰好一次）。 */
    private void runTheOnlyLogicTask() throws InterruptedException {
        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        assertThat(task).as("结果投递到了逻辑执行器").isNotNull();
        task.run();
        assertThat(logicQueue.poll(50, TimeUnit.MILLISECONDS)).as("恰好一次").isNull();
    }

    @Test
    void 写待落点_用E与续接的序号_目标zone_节点号与场景号为0_解析后的地图_写完经逻辑执行器回true恰好一次() throws Exception {
        ScenePlayer removed = removed(3);
        List<Boolean> results = new CopyOnWriteArrayList<>();

        locations.awaitingPlacement(removed, 2, 5, Duration.ofSeconds(287), results::add);

        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.location()).isEqualTo(PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(2)
                    .setSceneNodeId(0).setSceneId(0).setSceneConfigId(5).setOwnerEpoch(1).build());
            assertThat(call.seq()).as("(E, q+1)：续接这次进场的序号").isEqualTo(4);
            assertThat(call.ttl()).isEqualTo(Duration.ofSeconds(287));
        });
        assertThat(removed.locationSeq()).as("序号在逻辑线程上取").isEqualTo(4);
        assertThat(results).isEmpty();
        assertThat(logicQueue).as("还没写完").isEmpty();

        calls.get(0).reply().complete(true);

        assertThat(results).as("回调不在完成写的线程上直接跑").isEmpty();
        runTheOnlyLogicTask();
        assertThat(results).containsExactly(true);
    }

    @Test
    void 写已完成也不在调用栈内回调() throws Exception {
        doReturn(CompletableFuture.completedFuture(true)).when(directory).awaitPlacementAsync(any(), anyLong(), any());
        List<Boolean> results = new CopyOnWriteArrayList<>();

        locations.awaitingPlacement(removed(0), 2, 5, Duration.ofSeconds(60), results::add);

        assertThat(results).as("回调不在 awaitingPlacement 的调用栈内").isEmpty();
        runTheOnlyLogicTask();
        assertThat(results).containsExactly(true);
    }

    @Test
    void 没生效与Redis失败都回false() throws Exception {
        List<Boolean> results = new CopyOnWriteArrayList<>();

        locations.awaitingPlacement(removed(1), 2, 5, Duration.ofSeconds(60), results::add);
        calls.get(0).reply().complete(false);
        runTheOnlyLogicTask();

        locations.awaitingPlacement(removed(1), 2, 5, Duration.ofSeconds(60), results::add);
        calls.get(1).reply().completeExceptionally(new IllegalStateException("Redis 超时"));
        runTheOnlyLogicTask();

        assertThat(results).as("已有更新的写（别的登录已以更大 epoch 进场）/ Redis 故障").containsExactly(false, false);
    }

    /** TTL = 票据过期时刻 − 现在，由调用方算；这里只截到 [1 s, 300 s]（票据快过期、时钟偏差算出非正数时也至少留 1 s）。 */
    @Test
    void TTL截到1秒到300秒之间() {
        List<Duration> inputs = List.of(Duration.ofSeconds(-5), Duration.ZERO, Duration.ofMillis(999), Duration.ofSeconds(1),
                Duration.ofMillis(1_500), Duration.ofSeconds(300), Duration.ofMillis(300_001), Duration.ofHours(1));
        for (Duration ttl : inputs) {
            locations.awaitingPlacement(removed(0), 2, 5, ttl, written -> { });
        }

        assertThat(calls).extracting(Call::ttl).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(1_500), Duration.ofSeconds(300),
                Duration.ofSeconds(300), Duration.ofSeconds(300));
        assertThat(PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL).isEqualTo(Duration.ofSeconds(300));
    }

    /** 目录在发命令之前拒绝参数（目标 zone 为 0 是调用方的编程错误）：按没写上，回调仍恰好一次、仍经逻辑执行器，不抛给逻辑线程。 */
    @Test
    void 目录当场拒绝参数_按没写上经逻辑执行器回false() throws Exception {
        RedissonClient redis = mock(RedissonClient.class);
        RedisPlayerLocations real = new RedisPlayerLocations(new PlayerLocationDirectory(redis), 1, 9, logicQueue::add);
        List<Boolean> results = new CopyOnWriteArrayList<>();

        real.awaitingPlacement(removed(0), 0, 5, Duration.ofSeconds(60), results::add);

        assertThat(results).isEmpty();
        runTheOnlyLogicTask();
        assertThat(results).containsExactly(false);
        verifyNoInteractions(redis);
    }

    /**
     * 线程（§11.5）：Redis 的结果在另一条线程（Redisson 的回调线程）上出来——那条线程只投递，回调在逻辑线程上执行；
     * 那条线程上没有碰实例（实例的位置序号在调用时已取好，之后不变）。
     */
    @Test
    void 写在另一条线程上完成_回调回到逻辑线程上执行() throws Exception {
        ScenePlayer removed = removed(2);
        List<Thread> ranOn = new CopyOnWriteArrayList<>();
        List<Boolean> results = new CopyOnWriteArrayList<>();
        locations.awaitingPlacement(removed, 2, 5, Duration.ofSeconds(60), written -> {
            ranOn.add(Thread.currentThread());
            results.add(written);
        });
        long seqAfterCall = removed.locationSeq();

        Thread redisson = new Thread(() -> calls.get(0).reply().complete(true), "fake-redisson");
        redisson.start();
        redisson.join(5_000);

        assertThat(results).as("回调线程只做投递").isEmpty();
        runTheOnlyLogicTask();
        assertThat(results).containsExactly(true);
        assertThat(ranOn).containsExactly(Thread.currentThread());
        assertThat(removed.locationSeq()).isEqualTo(seqAfterCall).isEqualTo(3);
    }

    /**
     * 逻辑线程已停（§5.11）：投递被拒时结果丢弃、只记日志，回调不在完成写的线程（这里是测试线程与假的 Redisson 线程）上补跑。
     * 回调里不能拿「抛 AssertionError」当断言：它跑在 {@code whenComplete} 里，抛出的东西会被 CompletableFuture 吞掉、谁也看不到，
     * 所以改为记下调用，等完成写的线程跑完（{@code whenComplete} 的回调在完成 future 的那条线程上同步执行）再断言一次也没有；
     * 走的确实是丢弃分支（拒绝异常被接住），由日志确认。
     */
    @Test
    void 逻辑线程已停_投递被拒_结果丢弃只记日志_回调在哪条线程上都不执行() throws Exception {
        AtomicInteger offered = new AtomicInteger();
        Executor stopped = task -> {
            offered.incrementAndGet();
            throw new RejectedExecutionException("逻辑线程已停");
        };
        RedisPlayerLocations stoppedLocations = new RedisPlayerLocations(directory, 1, 9, stopped);
        List<Thread> ranOn = new CopyOnWriteArrayList<>();
        Consumer<Boolean> onDone = written -> ranOn.add(Thread.currentThread());

        try (CapturedLogs logs = new CapturedLogs(RedisPlayerLocations.class)) {
            stoppedLocations.awaitingPlacement(removed(0), 2, 5, Duration.ofSeconds(60), onDone);
            stoppedLocations.awaitingPlacement(removed(0), 2, 5, Duration.ofSeconds(60), onDone);
            calls.get(0).reply().complete(true);
            Thread redisson = new Thread(() -> calls.get(1).reply().completeExceptionally(new IllegalStateException("断开")),
                    "fake-redisson");
            redisson.start();
            redisson.join(5_000);
            assertThat(redisson.isAlive()).as("完成写的线程已跑完（回调随之跑完）").isFalse();

            assertThat(offered).as("两个结果各尝试投递一次").hasValue(2);
            assertThat(ranOn).as("投递被拒后回调没有在任何线程上补跑").isEmpty();
            assertThat(logs.count("丢弃待落点的写结果")).as("走的是丢弃分支（拒绝异常被接住）").isEqualTo(2);
        }
    }

    @Test
    void 参数为null是编程错误_在取序号与发命令之前抛() {
        ScenePlayer removed = removed(2);

        assertThatThrownBy(() -> locations.awaitingPlacement(removed, 2, 5, null, written -> { }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> locations.awaitingPlacement(removed, 2, 5, Duration.ofSeconds(60), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> locations.awaitingPlacement(null, 2, 5, Duration.ofSeconds(60), written -> { }))
                .isInstanceOf(NullPointerException.class);

        assertThat(removed.locationSeq()).isEqualTo(2);
        verify(directory, never()).awaitPlacementAsync(any(), anyLong(), any());
        assertThat(logicQueue).isEmpty();
    }
}
