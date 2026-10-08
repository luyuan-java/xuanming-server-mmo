package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.match.lifecycle.SweeperControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.Lifecycle;

/**
 * 观战清扫（spectate-spec §4.7、§10.3 的 {@code SpectateSweeperTest} 一段）：一轮只摘过期成员、不动落点（对照基线
 * {@code spectate_test.go:192} CleanupExpiredSpectateIndexKeepsFresh）；摘掉的条数与索引大小进指标；某一轮抛异常（连同 {@code Error}）下一轮照常；
 * 启停幂等、构造时不起线程、停机等当前一轮结束且有界。
 *
 * <p>「一轮做了什么」的用例直接调 {@code runRoundSafely()}（确定性）；调度与启停的用例用真线程，所有等待都有上限。
 */
class SpectateSweeperTest {

    private static final long T0 = ManualRedisClock.DEFAULT_START_MS;
    private static final long STALE_MS = 360_000;
    private static final Duration INTERVAL = Duration.ofMillis(5);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ManualRedisClock clock = new ManualRedisClock();
    private final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    private final InMemorySpectateStore store = new InMemorySpectateStore(clock, new InMemoryTicketStore(clock), placements, events);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private SpectateSweeper sweeper;

    @AfterEach
    void stopSweeper() {
        if (sweeper != null) {
            sweeper.stop();
        }
    }

    private void register(long battleId, long createdAtMs) {
        placements.put(BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(7).setBattleInstanceId("inst-7").setRpcHost("127.0.0.1")
                .setRpcPort(21207).setAttempt(1).setMode(3).setCreatedAtMs(createdAtMs).setDeadlineMs(createdAtMs + 300_000).build());
        store.putWatchable(battleId, createdAtMs);
    }

    private double swept() {
        return meters.get("xm.match.watchable.index.evictions").tags("reason", "sweep").counter().count();
    }

    private double gauge() {
        return meters.get("xm.match.watchable.battles").gauge().value();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("10 s 内应该等到").isTrue();
    }

    private static void awaitAtLeast(AtomicInteger counter, int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (counter.get() < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertThat(counter.get()).as("10 s 内应该跑到第 %d 次", atLeast).isGreaterThanOrEqualTo(atLeast);
    }

    /**
     * 包一层存储：每次调用先交给 {@code before}（入参是方法名；可以抛任何东西、可以阻塞），再原样转给内存替身。
     * 用来表达替身的故障注入表达不了的两件事：抛 {@code Error}、在一轮中间卡住。
     */
    private SpectateStore intercepted(Consumer<String> before) {
        return (SpectateStore) Proxy.newProxyInstance(SpectateStore.class.getClassLoader(), new Class<?>[] {SpectateStore.class},
                (proxy, method, args) -> {
                    before.accept(method.getName());
                    try {
                        return method.invoke(store, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // ================================================================ 一轮做了什么

    @Test
    void 一轮只摘过期成员_活着的保留_落点记录不动_摘掉的条数与索引大小进指标() {
        register(880020, T0); // 活着
        register(880021, T0 - STALE_MS - 1); // 过期
        register(880022, T0 - STALE_MS - 60_000); // 过期
        register(880023, T0 - STALE_MS); // 恰在分界上：不算过期
        sweeper = new SpectateSweeper(store, metrics, INTERVAL);

        sweeper.runRoundSafely();

        assertThat(store.watchable()).containsExactly("880020", "880023");
        assertThat(placements.stored(880021)).as("只摘成员：落点记录靠自己的 TTL 过期，清扫不删").isPresent();
        assertThat(placements.stored(880022)).isPresent();
        assertThat(events).as("没有任何落点被删").containsExactly("spectate.sweep:2");
        assertThat(store.calls).as("一轮两条命令：先摘、后采样").containsExactly("sweep()", "watchableCount()");
        assertThat(swept()).isEqualTo(2.0);
        assertThat(gauge()).as("采样的是摘完之后的大小").isEqualTo(2.0);
    }

    @Test
    void 没有过期成员的一轮_什么都不摘_照样采样_重复执行幂等() {
        register(880030, T0);
        sweeper = new SpectateSweeper(store, metrics, INTERVAL);

        sweeper.runRoundSafely();
        sweeper.runRoundSafely();

        assertThat(store.watchable()).containsExactly("880030");
        assertThat(swept()).isZero();
        assertThat(gauge()).isEqualTo(1.0);

        clock.advanceMs(STALE_MS + 1); // 时间走到它过期
        sweeper.runRoundSafely();
        sweeper.runRoundSafely();

        assertThat(store.watchable()).isEmpty();
        assertThat(swept()).as("第二轮没有可摘的：不重复计").isEqualTo(1.0);
        assertThat(gauge()).isZero();
    }

    @Test
    void 摘失败的一轮_不计数_但照样采样() {
        register(880040, T0);
        register(880041, T0 - STALE_MS - 1);
        sweeper = new SpectateSweeper(store, metrics, INTERVAL);
        store.faults.failNext("sweep");

        assertThatCode(() -> sweeper.runRoundSafely()).doesNotThrowAnyException();

        assertThat(store.watchable()).as("这一轮没摘成").containsExactly("880040", "880041");
        assertThat(swept()).isZero();
        assertThat(gauge()).as("两条命令互不依赖：采样照做（含还没被清扫的过期成员）").isEqualTo(2.0);

        sweeper.runRoundSafely();

        assertThat(store.watchable()).as("下一轮补上").containsExactly("880040");
        assertThat(swept()).isEqualTo(1.0);
        assertThat(gauge()).isEqualTo(1.0);
    }

    @Test
    void 采样失败的一轮_gauge停在上一次的读数_不归零() {
        register(880050, T0);
        register(880051, T0 - 1);
        sweeper = new SpectateSweeper(store, metrics, INTERVAL);
        sweeper.runRoundSafely();
        assertThat(gauge()).isEqualTo(2.0);
        register(880052, T0 - 2);
        store.faults.failNext("watchableCount");

        assertThatCode(() -> sweeper.runRoundSafely()).doesNotThrowAnyException();

        assertThat(gauge()).as("读失败不等于索引空了").isEqualTo(2.0);

        sweeper.runRoundSafely();
        assertThat(gauge()).isEqualTo(3.0);
    }

    @Test
    void 一轮里存储抛出任何东西_连同Error_都不逃出这一轮() {
        AtomicInteger calls = new AtomicInteger();
        sweeper = new SpectateSweeper(intercepted(method -> {
            switch (calls.incrementAndGet()) {
                case 1 -> throw new IllegalStateException("存储的 bug");
                case 2 -> throw new AssertionError("存储抛的是 Error");
                default -> {
                }
            }
        }), metrics, INTERVAL);
        register(880060, T0 - STALE_MS - 1);

        assertThatCode(() -> sweeper.runRoundSafely()).as("RuntimeException").doesNotThrowAnyException();
        assertThatCode(() -> sweeper.runRoundSafely()).as("Error").doesNotThrowAnyException();
        sweeper.runRoundSafely();

        assertThat(store.watchable()).as("第三轮照常").isEmpty();
        assertThat(swept()).isEqualTo(1.0);
    }

    // ================================================================ 调度

    @Test
    void 某一轮抛异常甚至Error_调度不停转_下一轮照常清扫() throws Exception {
        AtomicInteger sweeps = new AtomicInteger();
        CountDownLatch fourth = new CountDownLatch(1);
        sweeper = new SpectateSweeper(intercepted(method -> {
            if (!method.equals("sweep")) {
                return;
            }
            int n = sweeps.incrementAndGet();
            if (n == 1) {
                throw new IllegalStateException("第一轮炸了");
            }
            if (n == 2) {
                throw new AssertionError("第二轮抛的是 Error");
            }
            if (n >= 4) {
                fourth.countDown();
            }
        }), metrics, INTERVAL, STOP_TIMEOUT);
        register(880070, T0 - STALE_MS - 1);
        register(880071, T0);

        sweeper.start();
        await(fourth);
        sweeper.stop();

        assertThat(store.watchable()).as("JDK 的调度器遇到一次未捕获的异常就永久停转：每轮自己兜住，后面的轮次才摘得掉").containsExactly("880071");
        assertThat(swept()).isEqualTo(1.0);
        assertThat(gauge()).isEqualTo(1.0);
    }

    @Test
    void 轮与轮不重叠_都在match_spectate_sweeper线程上_第一轮在一个间隔之后() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        List<Long> sweepAtNanos = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        sweeper = new SpectateSweeper(intercepted(method -> {
            threads.add(Thread.currentThread().getName());
            if (method.equals("sweep")) {
                sweepAtNanos.add(System.nanoTime());
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                sleep(20);
            } else if (method.equals("watchableCount")) {
                inFlight.decrementAndGet();
                three.countDown();
            }
        }), metrics, Duration.ofMillis(30), STOP_TIMEOUT);
        long startedNanos = System.nanoTime();

        sweeper.start();
        await(three);
        sweeper.stop();

        assertThat(maxInFlight.get()).isEqualTo(1);
        assertThat(threads).containsExactly("match-spectate-sweeper");
        assertThat(TimeUnit.NANOSECONDS.toMillis(sweepAtNanos.get(0) - startedNanos)).as("start 不当场跑一轮：第一轮在一个间隔（30 ms）之后（留 10 ms 计时误差）")
                .isGreaterThanOrEqualTo(20);
        for (int i = 1; i < 3; i++) {
            long gapMs = TimeUnit.NANOSECONDS.toMillis(sweepAtNanos.get(i) - sweepAtNanos.get(i - 1));
            assertThat(gapMs).as("第 %d 轮与上一轮的开始时刻至少隔「一轮的耗时 20 ms + 间隔 30 ms」（留 10 ms 计时误差）", i + 1).isGreaterThanOrEqualTo(40);
        }
    }

    // ================================================================ 启停

    @Test
    void 构造时不起线程不碰存储_start之后才开始_stop之后不再清扫() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        sweeper = new SpectateSweeper(intercepted(method -> calls.incrementAndGet()), metrics, INTERVAL, STOP_TIMEOUT);

        assertThat(sweeper.isRunning()).as("建出来不自己启动：启停归 MatchLifecycle").isFalse();
        Thread.sleep(40);
        assertThat(calls.get()).as("没 start 就没有任何一轮").isZero();

        sweeper.start();
        assertThat(sweeper.isRunning()).isTrue();
        awaitAtLeast(calls, 4);
        sweeper.stop();

        assertThat(sweeper.isRunning()).isFalse();
        int after = calls.get();
        Thread.sleep(60);
        assertThat(calls.get()).as("停了之后不再碰存储").isEqualTo(after);
    }

    @Test
    void 启停都幂等_没起过也能停_停了可以再起() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        sweeper = new SpectateSweeper(intercepted(method -> {
            threads.add(Thread.currentThread());
            calls.incrementAndGet();
        }), metrics, INTERVAL, STOP_TIMEOUT);

        assertThatCode(() -> {
            sweeper.stop();
            sweeper.stop();
        }).as("没起过就停：什么都不做").doesNotThrowAnyException();
        sweeper.start();
        sweeper.start();
        awaitAtLeast(calls, 4);
        assertThat(threads).as("重复 start 没有起第二个线程").hasSize(1);
        sweeper.stop();
        sweeper.stop();
        assertThat(sweeper.isRunning()).isFalse();

        int before = calls.get();
        sweeper.start();
        awaitAtLeast(calls, before + 4);
        assertThat(sweeper.isRunning()).isTrue();
        assertThat(threads).as("再起是一个新线程").hasSize(2);
    }

    @Test
    void 停机等当前这一轮结束_这一轮在两条命令之间看到停机信号就收手() throws Exception {
        CountDownLatch inSweep = new CountDownLatch(1);
        AtomicReference<Thread> stopperRef = new AtomicReference<>();
        List<String> seen = new CopyOnWriteArrayList<>();
        sweeper = new SpectateSweeper(intercepted(method -> {
            seen.add(method);
            if (method.equals("sweep")) {
                inSweep.countDown();
                // 卡在第一条命令上，直到停机线程已经在 stop() 里限时等这一轮结束（那时停机信号一定已经置上）——看线程状态，不靠睡一会儿
                long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < giveUp) {
                    Thread stopper = stopperRef.get();
                    if (stopper != null && stopper.getState() == Thread.State.TIMED_WAITING) {
                        break;
                    }
                    sleep(2);
                }
            }
        }), metrics, INTERVAL, STOP_TIMEOUT);
        register(880080, T0 - STALE_MS - 1);

        sweeper.start();
        await(inSweep);
        Thread stopper = Thread.ofPlatform().unstarted(() -> sweeper.stop());
        stopperRef.set(stopper);
        stopper.start();
        stopper.join(10_000);

        assertThat(stopper.isAlive()).as("stop 在上限之内返回").isFalse();
        assertThat(sweeper.isRunning()).isFalse();
        assertThat(store.watchable()).as("stop 返回时手上这一轮的第一条命令已经做完").isEmpty();
        assertThat(seen).as("看到停机信号：不再发第二条命令，也没有下一轮").containsExactly("sweep");
    }

    @Test
    void 当前这一轮迟迟不结束_等到上限就中断它_stop照常返回() throws Exception {
        CountDownLatch inSweep = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        sweeper = new SpectateSweeper(intercepted(method -> {
            inSweep.countDown();
            try {
                new CountDownLatch(1).await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new Deadline.DependencyException("sweep 被中断", e);
            }
        }), metrics, INTERVAL, Duration.ofMillis(80));

        sweeper.start();
        await(inSweep);
        long begin = System.nanoTime();
        assertThatCode(() -> sweeper.stop()).doesNotThrowAnyException();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertThat(tookMs).as("至少等满上限（80 ms），但不会等那一轮自己结束（60 s）").isBetween(70L, 10_000L);
        await(interrupted);
        assertThat(sweeper.isRunning()).isFalse();
    }

    // ================================================================ 形状

    @Test
    void 它是进程的观战清扫启停口_自己不带生命周期_生产的等待上限_间隔与上限必须为正() {
        sweeper = new SpectateSweeper(store, metrics, INTERVAL);

        assertThat(sweeper).as("MatchLifecycle 经这个接口启停它").isInstanceOf(SweeperControl.class);
        assertThat(sweeper).as("不实现 Spring 的生命周期接口：容器不会自己启停它（启动第 8 步 / 停机第 4 步归 MatchLifecycle）")
                .isNotInstanceOf(Lifecycle.class).isNotInstanceOf(AutoCloseable.class);
        assertThat(sweeper.isRunning()).isFalse();
        assertThat(SpectateSweeper.THREAD_NAME).isEqualTo("match-spectate-sweeper");
        assertThat(SpectateSweeper.STOP_TIMEOUT).as("Redis 卡住时清扫不拖住停机").isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(SpectateSweeper.OP_BUDGET_MS).as("一条命令的等待不超过一个缺省的清扫间隔").isLessThanOrEqualTo(10_000);
        assertThatThrownBy(() -> new SpectateSweeper(null, metrics, INTERVAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SpectateSweeper(store, null, INTERVAL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SpectateSweeper(store, metrics, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SpectateSweeper(store, metrics, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpectateSweeper(store, metrics, Duration.ofNanos(999_999))).as("不足 1 ms").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpectateSweeper(store, metrics, INTERVAL, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
