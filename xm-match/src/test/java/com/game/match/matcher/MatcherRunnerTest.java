package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.lifecycle.MatcherControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.MatcherRound;
import com.game.match.metrics.MetricLabels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.Lifecycle;

/**
 * 凑单循环的调度（match-spec §2.5、§9.3、§9.8、§12.1 坑 14）：单线程、轮与轮不重叠、上一轮结束后再等一个间隔；<b>单轮抛任何异常下一轮照常</b>
 * （JDK 的调度器遇到一次未捕获的异常就永久停转）；停机等当前这一轮结束、超时才中断；启停幂等。用真线程驱动，所有等待都有上限。
 */
class MatcherRunnerTest {

    private static final Duration INTERVAL = Duration.ofMillis(5);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private MatcherRunner runner;

    @AfterEach
    void stopRunner() {
        if (runner != null) {
            runner.stop();
        }
    }

    private double rounds(String result) {
        return meters.get("xm.match.matcher.rounds").tags("result", result).counter().count();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("10 s 内应该等到").isTrue();
    }

    @Test
    void 单轮抛异常甚至Error_下一轮照常_出错的轮次计error() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch thirdOk = new CountDownLatch(3);
        runner = new MatcherRunner(stop -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                throw new IllegalStateException("第一轮炸了");
            }
            if (n == 2) {
                throw new AssertionError("第二轮抛的是 Error");
            }
            thirdOk.countDown();
            return MatcherRound.OK;
        }, INTERVAL, STOP_TIMEOUT, metrics);

        runner.start();
        await(thirdOk);
        runner.stop();

        assertThat(rounds("error")).isEqualTo(2.0);
        assertThat(rounds("ok")).as("异常之后的每一轮都照常跑了").isEqualTo(calls.get() - 2.0).isGreaterThanOrEqualTo(3.0);
    }

    @Test
    void 每一轮的结局原样计数_没给结局的按error() throws Exception {
        MatcherRound[] script = {MatcherRound.PAUSED_NO_BATTLE, MatcherRound.PAUSED_NO_LEASE, MatcherRound.PAUSED_SATURATED, MatcherRound.ERROR, null,
                MatcherRound.OK};
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch scriptDone = new CountDownLatch(script.length);
        runner = new MatcherRunner(stop -> {
            int n = calls.getAndIncrement();
            scriptDone.countDown();
            return n < script.length ? script[n] : MatcherRound.OK;
        }, INTERVAL, STOP_TIMEOUT, metrics);

        runner.start();
        await(scriptDone);
        runner.stop();

        assertThat(rounds("paused_no_battle")).isEqualTo(1.0);
        assertThat(rounds("paused_no_lease")).isEqualTo(1.0);
        assertThat(rounds("paused_saturated")).isEqualTo(1.0);
        assertThat(rounds("error")).as("一轮自己报的 error + 一轮没给结局").isEqualTo(2.0);
        assertThat(rounds("ok")).isEqualTo(calls.get() - 5.0);
    }

    @Test
    void 轮与轮不重叠_都在match_matcher线程上_上一轮结束后再等一个间隔() throws Exception {
        Duration interval = Duration.ofMillis(30);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        List<Long> startedAtNanos = new CopyOnWriteArrayList<>();
        CountDownLatch four = new CountDownLatch(4);
        runner = new MatcherRunner(stop -> {
            startedAtNanos.add(System.nanoTime());
            threads.add(Thread.currentThread().getName());
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            sleep(20);
            inFlight.decrementAndGet();
            four.countDown();
            return MatcherRound.OK;
        }, interval, STOP_TIMEOUT, metrics);

        runner.start();
        await(four);
        runner.stop();

        assertThat(maxInFlight.get()).isEqualTo(1);
        assertThat(threads).containsExactly("match-matcher");
        for (int i = 1; i < 4; i++) {
            long gapMs = TimeUnit.NANOSECONDS.toMillis(startedAtNanos.get(i) - startedAtNanos.get(i - 1));
            assertThat(gapMs).as("第 %d 轮与上一轮的开始时刻至少隔「一轮的耗时 20 ms + 间隔 30 ms」（留 10 ms 计时误差）", i + 1).isGreaterThanOrEqualTo(40);
        }
    }

    @Test
    void 停机等当前这一轮结束_并把停机信号递给它_之后不再跑() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        runner = new MatcherRunner(stop -> {
            calls.incrementAndGet();
            started.countDown();
            while (!stop.getAsBoolean()) {
                sleep(2);
            }
            sleep(30);
            finished.set(true);
            return MatcherRound.OK;
        }, INTERVAL, STOP_TIMEOUT, metrics);

        runner.start();
        await(started);
        assertThat(runner.isRunning()).isTrue();
        runner.stop();

        assertThat(finished.get()).as("stop 返回时这一轮已经收尾完").isTrue();
        assertThat(runner.isRunning()).isFalse();
        int after = calls.get();
        sleep(60);
        assertThat(calls.get()).as("停了之后不再有新的一轮").isEqualTo(after);
        assertThat(rounds("ok")).isEqualTo((double) after);
    }

    @Test
    void 当前这一轮迟迟不结束_等到上限就中断它() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        runner = new MatcherRunner(stop -> {
            started.countDown();
            try {
                new CountDownLatch(1).await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return MatcherRound.OK;
        }, INTERVAL, Duration.ofMillis(80), metrics);

        runner.start();
        await(started);
        long begin = System.nanoTime();
        runner.stop();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertThat(tookMs).as("至少等满上限，但不会等那一轮自己结束（60 s）").isBetween(70L, 10_000L);
        await(interrupted);
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    void 启停都幂等_停了可以再起() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Set<Thread> threads = ConcurrentHashMap.newKeySet();
        runner = new MatcherRunner(stop -> {
            threads.add(Thread.currentThread());
            calls.incrementAndGet();
            return MatcherRound.OK;
        }, INTERVAL, STOP_TIMEOUT, metrics);

        runner.stop();
        assertThat(runner.isRunning()).as("没起过就停：什么都不做").isFalse();
        runner.start();
        runner.start();
        awaitCalls(calls, 3);
        assertThat(threads).as("重复 start 没有起第二个线程").hasSize(1);
        runner.stop();
        runner.stop();
        assertThat(runner.isRunning()).isFalse();

        int before = calls.get();
        runner.start();
        awaitCalls(calls, before + 3);
        assertThat(runner.isRunning()).isTrue();
        assertThat(threads).as("再起是一个新线程").hasSize(2);
    }

    @Test
    void 它是进程的凑单启停口_自己不带生命周期_间隔与等待上限必须为正_一轮不能缺() {
        runner = new MatcherRunner(stop -> MatcherRound.OK, INTERVAL, STOP_TIMEOUT, metrics);

        assertThat(runner).as("MatchLifecycle 经这个接口启停它").isInstanceOf(MatcherControl.class);
        assertThat(runner).as("不实现 Spring 的生命周期接口：容器不会自己启停它（启动第 8 步 / 停机第 1 步归 MatchLifecycle）")
                .isNotInstanceOf(Lifecycle.class).isNotInstanceOf(AutoCloseable.class);
        assertThat(runner.isRunning()).as("建出来不自己启动").isFalse();
        assertThatThrownBy(() -> new MatcherRunner(null, INTERVAL, STOP_TIMEOUT, metrics)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MatcherRunner(stop -> MatcherRound.OK, Duration.ZERO, STOP_TIMEOUT, metrics))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatcherRunner(stop -> MatcherRound.OK, INTERVAL, Duration.ZERO, metrics))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void awaitCalls(AtomicInteger calls, int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (calls.get() < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertThat(calls.get()).as("10 s 内应该跑到第 %d 轮", atLeast).isGreaterThanOrEqualTo(atLeast);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
