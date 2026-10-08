package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.rating.RatingStore.Rating;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcRatingReader}（match-spec §5.2「读接口」；{@link RatingReader} 的契约）：读在 {@code match-db} 平台线程上跑、调用线程只等 future；
 * 任何失败都回落 150000、永不抛；在虚拟线程上调不会把 JDBC 带到虚拟线程里。
 */
class JdbcRatingReaderTest {

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void closeAll() throws Exception {
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private JdbcRatingReader reader(JdbcRatingReader.Source source, int threads, int queue, long waitMs) {
        JdbcRatingReader reader = new JdbcRatingReader(source, threads, queue, waitMs);
        closeables.add(reader);
        return reader;
    }

    private static Map<Long, Rating> rows(long... idAndCenti) {
        Map<Long, Rating> out = new LinkedHashMap<>();
        for (int i = 0; i < idAndCenti.length; i += 2) {
            out.put(idAndCenti[i], new Rating(idAndCenti[i], idAndCenti[i + 1], 1));
        }
        return out;
    }

    @Test
    void 有行的取库里的分_没有行的是新号1500_结果覆盖每一个入参() {
        List<Collection<Long>> asked = new CopyOnWriteArrayList<>();
        JdbcRatingReader reader = reader(ids -> {
            asked.add(List.copyOf(ids));
            return rows(1001, 162_500, 1003, 0);
        }, 2, 4, 1_000);

        Map<Long, Long> all = reader.loadAllCentiOrDefault(List.of(1001L, 1002L, 1003L, 1001L));

        assertThat(all).containsExactly(Map.entry(1001L, 162_500L), Map.entry(1002L, 150_000L), Map.entry(1003L, 0L));
        assertThat(asked).as("重复的玩家号只读一次").containsExactly(List.of(1001L, 1002L, 1003L));
        assertThat(reader.loadCentiOrDefault(1001)).isEqualTo(162_500);
        assertThat(reader.loadCentiOrDefault(1002)).isEqualTo(RatingReader.DEFAULT_CENTI);
        assertThatThrownBy(() -> all.put(9L, 1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 空名单不发查询() {
        List<Collection<Long>> asked = new CopyOnWriteArrayList<>();
        JdbcRatingReader reader = reader(ids -> {
            asked.add(ids);
            return Map.of();
        }, 1, 1, 1_000);

        assertThat(reader.loadAllCentiOrDefault(List.of())).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void 读在match_db平台线程上跑_调用方在虚拟线程上也一样() throws Exception {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        JdbcRatingReader reader = reader(ids -> {
            ranOn.set(Thread.currentThread());
            return rows(7, 151_600);
        }, 2, 4, 1_000);

        long fromVirtual;
        try (var virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            fromVirtual = virtual.submit(() -> reader.loadCentiOrDefault(7)).get(10, TimeUnit.SECONDS);
        }

        assertThat(fromVirtual).isEqualTo(151_600);
        assertThat(ranOn.get().isVirtual()).as("JDBC 不上虚拟线程（JDK 21 下驱动内部的 synchronized 会钉住载体线程）").isFalse();
        assertThat(ranOn.get().getName()).startsWith("match-db-");
        assertThat(ranOn.get().isDaemon()).isTrue();
    }

    @Test
    void 库故障_每个人都回落1500_不抛() {
        JdbcRatingReader reader = reader(ids -> {
            throw new RatingStore.StoreException("读评分失败", new SQLException("Communications link failure", "08S01"));
        }, 1, 1, 1_000);

        assertThat(reader.loadAllCentiOrDefault(List.of(1L, Long.MIN_VALUE + 2))).containsExactly(Map.entry(1L, 150_000L),
                Map.entry(Long.MIN_VALUE + 2, 150_000L));
        assertThat(reader.loadCentiOrDefault(1)).isEqualTo(150_000);
    }

    @Test
    void 读得太慢_到时限就回落1500_不等它跑完() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JdbcRatingReader reader = reader(ids -> {
            await(release);
            return rows(1, 199_900);
        }, 1, 4, 150);

        long started = System.nanoTime();
        long rating = reader.loadCentiOrDefault(1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        release.countDown();

        assertThat(rating).as("迟到的结果不算").isEqualTo(150_000);
        assertThat(elapsedMs).isBetween(140L, 2_000L);
    }

    @Test
    void 等待超时的读如果还没开始跑_会被取消_不占着线程池() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Long> ran = new CopyOnWriteArrayList<>();
        JdbcRatingReader reader = reader(ids -> {
            ran.addAll(ids);
            await(release);
            return Map.of();
        }, 1, 4, 1_000);

        // 第一个读占住唯一的线程；第二个读排在队列里，等到超时后被取消。等待上限给 1 s 而不是一两百毫秒：线程池的线程是用到才建的，
        // 第一个读得在这个上限之内被接手，否则被取消的是它、随后跑起来的反而是第二个
        assertThat(reader.loadCentiOrDefault(1)).isEqualTo(150_000);
        assertThat(reader.loadCentiOrDefault(2)).isEqualTo(150_000);
        assertThat(reader.queued()).as("第二个读还在队列里（已取消，但要等线程来取才会被丢掉）").isEqualTo(1);
        release.countDown();
        // 等线程把队列里那一项取走，再留一点时间：它要是没被取消，这时已经查过库了
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (reader.queued() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(reader.queued()).isZero();
        Thread.sleep(200);

        assertThat(ran).as("排队中被取消的那次读没有真的去查库").containsExactly(1L);
    }

    @Test
    void 线程池满了_直接回落1500() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JdbcRatingReader reader = reader(ids -> {
            entered.countDown();
            await(release);
            return rows(1, 170_000, 2, 170_000, 3, 170_000);
        }, 1, 1, 5_000);
        try (var callers = Executors.newFixedThreadPool(2)) {
            Future<Long> running = callers.submit(() -> reader.loadCentiOrDefault(1));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Long> queued = callers.submit(() -> reader.loadCentiOrDefault(2));
            // 等第二个读进了队列（队列容量 1）
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (reader.queued() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(reader.queued()).isEqualTo(1);

            long started = System.nanoTime();
            long rejected = reader.loadCentiOrDefault(3);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            release.countDown();

            assertThat(rejected).isEqualTo(150_000);
            assertThat(elapsedMs).as("被拒绝的读不等").isLessThan(1_000);
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(170_000);
            assertThat(queued.get(10, TimeUnit.SECONDS)).isEqualTo(170_000);
        }
    }

    @Test
    void 调用线程被中断_回落1500并保留中断标记() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JdbcRatingReader reader = reader(ids -> {
            await(release);
            return rows(1, 170_000);
        }, 1, 1, 5_000);
        AtomicReference<Long> result = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            Thread.currentThread().interrupt();
            result.set(reader.loadCentiOrDefault(1));
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });

        caller.start();
        caller.join(10_000);
        release.countDown();

        assertThat(result.get()).isEqualTo(150_000);
        assertThat(interruptedAfter.get()).isTrue();
    }

    @Test
    void 关闭之后的读回落1500() {
        JdbcRatingReader reader = new JdbcRatingReader(ids -> rows(1, 170_000), 1, 1, 1_000);
        reader.close();

        assertThat(reader.loadCentiOrDefault(1)).isEqualTo(150_000);
    }

    @Test
    void 接真的存储_读到库里的评分_库坏了回落() {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            FaultyDataSource ds = new FaultyDataSource(db.dataSource);
            MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));
            RatingStore store = new RatingStore(RatingStore.connections(ds), configId -> 30, metrics, System::currentTimeMillis);
            JdbcRatingReader reader = new JdbcRatingReader(store);
            closeables.add(reader);
            db.putRating(1001, 162_500, 3);
            db.putRating(Long.MIN_VALUE + 7, 99_950, 1);

            assertThat(reader.loadAllCentiOrDefault(List.of(1001L, 1002L, Long.MIN_VALUE + 7)))
                    .containsExactly(Map.entry(1001L, 162_500L), Map.entry(1002L, 150_000L), Map.entry(Long.MIN_VALUE + 7, 99_950L));

            ds.failConnection(() -> new SQLException("Communications link failure", "08S01"));
            assertThat(reader.loadCentiOrDefault(1001)).as("读失败：按 1500，不抛").isEqualTo(150_000);
            assertThat(reader.loadCentiOrDefault(1001)).as("库恢复后读到真值").isEqualTo(162_500);
        }
    }

    @Test
    void 线程池的标准指标以match_db为名() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JdbcRatingReader reader = reader(ids -> Map.of(), JdbcRatingReader.THREADS, JdbcRatingReader.QUEUE, JdbcRatingReader.WAIT_MS);

        reader.bindTo(meters);

        assertThat(meters.get("executor.pool.core").tag("name", "match-db").gauge().value()).isEqualTo(8);
        assertThat(meters.get("executor.queue.remaining").tag("name", "match-db").gauge().value()).isEqualTo(256);
        assertThat(JdbcRatingReader.WAIT_MS).as("约 1 s 内返回").isEqualTo(1_000);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试没有放行");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
