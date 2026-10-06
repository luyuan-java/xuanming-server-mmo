package com.game.match.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@code match-worker} 工作池（match-spec §9.3）：任务不在投递线程上跑；线程与队列都有界，满了当场拒绝（过载快速失败）；
 * 关闭时把已排队的任务跑完再停，之后拒收；标准线程池指标按名字 {@code match-worker} 导出。
 */
class MatchWorkerPoolTest {

    @Test
    void 任务在名为match_worker的线程上执行_不占投递线程() throws Exception {
        MatchWorkerPool pool = new MatchWorkerPool(2, 8, Duration.ofSeconds(2));
        try {
            CompletableFuture<String> ran = new CompletableFuture<>();

            pool.execute(() -> ran.complete(Thread.currentThread().getName()));

            assertThat(ran.get(5, TimeUnit.SECONDS)).startsWith("match-worker-").isNotEqualTo(Thread.currentThread().getName());
        } finally {
            pool.close();
        }
    }

    @Test
    void 线程占满且队列已满_当场拒绝_腾出位置后又能投递() throws Exception {
        MatchWorkerPool pool = new MatchWorkerPool(1, 2, Duration.ofSeconds(2));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        try {
            pool.execute(() -> {
                started.countDown();
                await(release);
                ran.incrementAndGet();
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            pool.execute(ran::incrementAndGet);
            pool.execute(ran::incrementAndGet);

            assertThatThrownBy(() -> pool.execute(ran::incrementAndGet)).as("1 个在跑 + 2 个排队 = 满").isInstanceOf(RejectedExecutionException.class);

            release.countDown();
            CompletableFuture<Void> later = new CompletableFuture<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                try {
                    pool.execute(() -> later.complete(null));
                    break;
                } catch (RejectedExecutionException e) {
                    assertThat(System.nanoTime()).as("队列腾出位置之后应当能再投").isLessThan(deadline);
                    TimeUnit.MILLISECONDS.sleep(5);
                }
            }
            later.get(5, TimeUnit.SECONDS);
            assertThat(ran).hasValue(3);
        } finally {
            release.countDown();
            pool.close();
        }
    }

    @Test
    void 关闭时把已排队的任务跑完_之后拒收() throws Exception {
        MatchWorkerPool pool = new MatchWorkerPool(1, 16, Duration.ofSeconds(5));
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            pool.execute(() -> {
                sleep(10);
                ran.incrementAndGet();
            });
        }

        pool.close();

        assertThat(ran).as("排空").hasValue(10);
        assertThatThrownBy(() -> pool.execute(ran::incrementAndGet)).isInstanceOf(RejectedExecutionException.class);
        pool.close();
    }

    @Test
    void 排空超时_中断剩余任务_关闭不会一直等() {
        MatchWorkerPool pool = new MatchWorkerPool(1, 4, Duration.ofMillis(100));
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        pool.execute(() -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
        });

        long started = System.nanoTime();
        pool.close();

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (interrupted.get() == 0 && System.nanoTime() < deadline) {
            sleep(5);
        }
        assertThat(interrupted).as("卡住的任务被中断").hasValue(1);
    }

    @Test
    void 标准线程池指标按名字match_worker导出() {
        MatchWorkerPool pool = new MatchWorkerPool(3, 8, Duration.ofSeconds(1));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            pool.bindTo(meters);

            assertThat(MatchWorkerPool.NAME).isEqualTo("match-worker");
            assertThat(meters.get("executor.pool.core").tag("name", "match-worker").gauge().value()).isEqualTo(3);
            assertThat(meters.get("executor.queue.remaining").tag("name", "match-worker").gauge().value()).isEqualTo(8);
        } finally {
            pool.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
