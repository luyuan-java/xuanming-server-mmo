package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * 163 的在途执行器（spectate-spec §4.9、§10.3「在途许可在正常 / 超时 / 异常 / 未预期异常四条出口都归还」；lead 裁决 2）：
 * 每个受理的任务一条虚拟线程、不在调用线程上跑；在途满了当场拒收（不阻塞、不排队）；任务从哪一条出口结束许可都归还；关闭后拒收；
 * {@code awaitIdle} 有界、只等不打断。等待一律带上限，不依赖窄的墙钟窗口。
 */
class SpectateExecutorTest {

    private static final long WAIT_SECONDS = 20;

    private static void await(CountDownLatch latch, String what) throws InterruptedException {
        assertThat(latch.await(WAIT_SECONDS, TimeUnit.SECONDS)).as(what).isTrue();
    }

    /** 等到在途数变成期望值（任务的 finally 在它的闩之后才跑，所以要等一下）。 */
    private static void awaitInflight(SpectateExecutor executor, int expected) {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (executor.inflight() != expected && System.nanoTime() < giveUp) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertThat(executor.inflight()).as("在途数").isEqualTo(expected);
    }

    @Test
    void 受理的任务跑在自己的虚拟线程上_名字带前缀_不在调用线程上_跑完许可归还() throws Exception {
        SpectateExecutor executor = new SpectateExecutor(4);
        AtomicReference<Thread> ran = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            ran.set(Thread.currentThread());
            done.countDown();
        });

        await(done, "任务被执行");
        assertThat(ran.get()).isNotSameAs(Thread.currentThread());
        assertThat(ran.get().isVirtual()).as("每个 163 一条虚拟线程").isTrue();
        assertThat(ran.get().getName()).startsWith("match-spectate-");
        awaitInflight(executor, 0);
        assertThat(executor.maxInflight()).isEqualTo(4);
    }

    @Test
    void 在途满了_当场拒收_不阻塞也不排队_被拒的任务一行都不跑_归还一个之后又能受理() throws Exception {
        SpectateExecutor executor = new SpectateExecutor(2);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch bothRunning = new CountDownLatch(2);
        AtomicInteger rejectedRan = new AtomicInteger();
        Runnable blocker = () -> {
            bothRunning.countDown();
            try {
                hold.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        executor.execute(blocker);
        executor.execute(blocker);
        await(bothRunning, "两个任务都在跑");
        assertThat(executor.inflight()).isEqualTo(2);

        long startedNanos = System.nanoTime();
        assertThatThrownBy(() -> executor.execute(rejectedRan::incrementAndGet)).isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("在途已满").hasMessageContaining("2");
        long rejectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(rejectMillis).as("拒收是当场的：不等许可（两个占着许可的任务要 %d s 才会自己放手）", WAIT_SECONDS).isLessThan(5_000);
        assertThat(executor.inflight()).as("被拒的那一次不占许可").isEqualTo(2);

        hold.countDown();
        awaitInflight(executor, 0);
        CountDownLatch again = new CountDownLatch(1);
        executor.execute(again::countDown);
        await(again, "归还之后重新受理");
        assertThat(rejectedRan).as("被拒的任务从未执行").hasValue(0);
    }

    @Test
    void 四条出口都归还许可_正常返回_跑到预算到点才返回_抛运行时异常_抛Error() throws Exception {
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        ThreadFactory threads = Thread.ofVirtual().name("match-spectate-test-", 0).uncaughtExceptionHandler((thread, error) -> uncaught.add(error))
                .factory();
        SpectateExecutor executor = new SpectateExecutor(1, threads);

        // 1 正常返回
        CountDownLatch normal = new CountDownLatch(1);
        executor.execute(normal::countDown);
        await(normal, "正常返回的任务");
        awaitInflight(executor, 0);

        // 2 超时：任务自己等到它的预算到点才返回（163 的每一跳都这样收场）
        CountDownLatch timedOut = new CountDownLatch(1);
        executor.execute(() -> {
            Deadline budget = Deadline.after(50);
            while (!budget.expired()) {
                LockSupport.parkNanos(Math.max(1, budget.remainingNanos()));
            }
            timedOut.countDown();
        });
        await(timedOut, "跑到预算到点的任务");
        awaitInflight(executor, 0);

        // 3 抛 RuntimeException（派发器交进来的任务本不该抛；抛了许可也得还，而且不该变成未捕获异常）
        CountDownLatch threw = new CountDownLatch(1);
        executor.execute(() -> {
            threw.countDown();
            throw new IllegalStateException("任务抛出的运行时异常");
        });
        await(threw, "抛运行时异常的任务");
        awaitInflight(executor, 0);

        // 4 抛 Error：先还许可，再原样上抛
        CountDownLatch died = new CountDownLatch(1);
        executor.execute(() -> {
            died.countDown();
            throw new StackOverflowError("任务抛出的 Error");
        });
        await(died, "抛 Error 的任务");
        awaitInflight(executor, 0);

        // 上限是 1：前四个只要有一个没还许可，这一个就会被拒
        CountDownLatch after = new CountDownLatch(1);
        executor.execute(after::countDown);
        await(after, "四条出口之后仍能受理");
        awaitInflight(executor, 0);
        assertThat(executor.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).isTrue();
        assertThat(uncaught).as("只有 Error 原样上抛给线程的未捕获处理器；RuntimeException 在执行器里收住").singleElement()
                .isInstanceOf(StackOverflowError.class);
    }

    @Test
    void 并发提交_同时在跑的任务数从不超过上限_受理数加拒收数等于提交数_最后在途归零() throws Exception {
        int max = 3;
        int submitters = 8;
        int perSubmitter = 40;
        SpectateExecutor executor = new SpectateExecutor(max);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(submitters);
        for (int i = 0; i < submitters; i++) {
            Thread.ofPlatform().daemon(true).name("test-submit-" + i).start(() -> {
                try {
                    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    for (int n = 0; n < perSubmitter; n++) {
                        try {
                            executor.execute(() -> {
                                int now = running.incrementAndGet();
                                peak.accumulateAndGet(now, Math::max);
                                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                                running.decrementAndGet();
                                executed.incrementAndGet();
                            });
                            accepted.incrementAndGet();
                        } catch (RejectedExecutionException e) {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    submitted.countDown();
                }
            });
        }
        start.countDown();
        await(submitted, "全部提交完");

        assertThat(executor.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).as("受理的任务都跑完了").isTrue();
        assertThat(peak.get()).as("同时在跑的任务数的峰值").isBetween(1, max);
        assertThat(accepted.get() + rejected.get()).isEqualTo(submitters * perSubmitter);
        assertThat(executed).as("受理的任务恰好各执行一次").hasValue(accepted.get());
        assertThat(executor.inflight()).isZero();
    }

    @Test
    void 关闭之后拒收_在途的任务不受影响_照样跑完并归还许可_关闭可以重复调() throws Exception {
        SpectateExecutor executor = new SpectateExecutor(2);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        executor.execute(() -> {
            running.countDown();
            try {
                hold.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            finished.countDown();
        });
        await(running, "在途的任务已经开始");

        executor.close();
        executor.close();

        assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(RejectedExecutionException.class).hasMessageContaining("已关闭");
        assertThat(executor.inflight()).as("关闭不动在途的任务，被拒的那一次也不占许可").isEqualTo(1);
        assertThat(executor.toString()).contains("closed");
        hold.countDown();
        await(finished, "在途的任务跑完");
        assertThat(interrupted).as("关闭不打断在途的任务").isFalse();
        awaitInflight(executor, 0);
    }

    @Test
    void awaitIdle_没有在途立即回true_有在途时等到它结束_到点回false且不打断任务() throws Exception {
        SpectateExecutor executor = new SpectateExecutor(2);
        assertThat(executor.awaitIdle(Duration.ofSeconds(WAIT_SECONDS))).as("一次 163 都没受理过").isTrue();
        assertThat(executor.awaitIdle(Duration.ZERO)).as("零等待也不抛").isTrue();

        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        executor.execute(() -> {
            running.countDown();
            try {
                hold.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        await(running, "在途的任务已经开始");

        long startedNanos = System.nanoTime();
        boolean idle = executor.awaitIdle(Duration.ofMillis(150));
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(idle).as("到点仍有在途：放弃等待").isFalse();
        assertThat(waitedMillis).as("不早于给的上限就放弃（上界只防无限等）").isBetween(100L, 10_000L);
        assertThat(executor.inflight()).as("只是等：在途的请求没有被取消").isEqualTo(1);
        assertThat(interrupted).isFalse();
        assertThat(executor.awaitIdle(Duration.ZERO)).as("零等待：有在途就是 false").isFalse();

        // 另一条线程在等的时候任务结束：等待者被唤醒，回 true
        AtomicReference<Boolean> woke = new AtomicReference<>();
        CountDownLatch waiterDone = new CountDownLatch(1);
        Thread.ofPlatform().daemon(true).name("test-await-idle").start(() -> {
            woke.set(executor.awaitIdle(Duration.ofSeconds(WAIT_SECONDS)));
            waiterDone.countDown();
        });
        hold.countDown();
        await(waiterDone, "等待者返回");
        assertThat(woke.get()).isTrue();
        assertThat(executor.inflight()).isZero();
        assertThat(interrupted).isFalse();
    }

    @Test
    void awaitIdle_被中断时尽快回false_保留中断标志_在途的任务照旧() throws Exception {
        SpectateExecutor executor = new SpectateExecutor(1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        executor.execute(() -> {
            running.countDown();
            try {
                hold.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        await(running, "在途的任务已经开始");
        AtomicReference<Boolean> result = new AtomicReference<>();
        AtomicBoolean flagKept = new AtomicBoolean();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().daemon(true).name("test-await-interrupted").start(() -> {
            waiting.countDown();
            result.set(executor.awaitIdle(Duration.ofSeconds(WAIT_SECONDS)));
            flagKept.set(Thread.currentThread().isInterrupted());
            waiterDone.countDown();
        });
        await(waiting, "等待者已经开始");

        waiter.interrupt();

        await(waiterDone, "被中断的等待者返回");
        assertThat(result.get()).isFalse();
        assertThat(flagKept).as("保留中断标志").isTrue();
        assertThat(executor.inflight()).isEqualTo(1);
        hold.countDown();
        awaitInflight(executor, 0);
    }

    @Test
    void 起不了线程_按拒收处理_任务没有执行_许可当场归还_之后照常受理() throws Exception {
        AtomicBoolean broken = new AtomicBoolean(true);
        ThreadFactory real = Thread.ofVirtual().name("match-spectate-test-", 0).factory();
        ThreadFactory flaky = task -> {
            if (broken.get()) {
                throw new IllegalStateException("注入的故障: 起不了线程");
            }
            return real.newThread(task);
        };
        SpectateExecutor executor = new SpectateExecutor(1, flaky);
        AtomicInteger ran = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(ran::incrementAndGet)).isInstanceOf(RejectedExecutionException.class)
                .hasMessageContaining("起不了").hasCauseInstanceOf(IllegalStateException.class);

        assertThat(executor.inflight()).as("没执行的任务不占许可").isZero();
        assertThat(ran).hasValue(0);
        broken.set(false);
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(done::countDown);
        await(done, "线程工厂恢复之后照常受理（上限 1：许可没还的话这里会被拒）");
        awaitInflight(executor, 0);
    }

    @Test
    void 在途上限必须至少为1_任务不能为空() {
        assertThatThrownBy(() -> new SpectateExecutor(0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("≥ 1");
        assertThatThrownBy(() -> new SpectateExecutor(-3)).isInstanceOf(IllegalArgumentException.class);
        SpectateExecutor executor = new SpectateExecutor(1);
        assertThatThrownBy(() -> executor.execute(null)).isInstanceOf(NullPointerException.class);
        assertThat(executor.inflight()).isZero();
    }
}
