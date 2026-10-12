package com.game.match.rating;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RatingReader} 的生产实现（match-spec §5.2「读接口」、§9.3 的 {@code match-db} 线程）：评分在 MySQL，读在自己的有界平台线程池上跑，
 * 调用线程只在 future 上等——所以排队的工作线程与 gather 的<b>虚拟线程</b>都可以调（虚拟线程里直接跑 JDBC 会钉住载体线程）。
 *
 * <p>契约照接口：永不抛、永不拒绝。读失败（库故障、语句超时、等待超过 {@value #WAIT_MS} ms、线程池满、被中断）记 ERROR 并让受影响的人回落
 * {@link RatingReader#DEFAULT_CENTI}——评分是对局质量的软约束，读不到不该挡住排队（同基线 {@code loadRatingOrDefault}）。
 * 等待超时后还没开始跑的读会被取消，已经在跑的由语句超时（1 s）收尾，不会无限占着线程。
 *
 * <p>线程池 {@value #POOL_NAME}：{@value #THREADS} 条平台线程、队列 {@value #QUEUE}、AbortPolicy；标准指标 {@code executor_*{name="match-db"}}。
 */
public final class JdbcRatingReader implements RatingReader, MeterBinder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JdbcRatingReader.class);

    /** 线程名前缀与指标 name 标签。 */
    public static final String POOL_NAME = "match-db";
    static final int THREADS = 8;
    static final int QUEUE = 256;
    /** 调用线程最多等这么久（与语句超时同为 1 s）。 */
    static final long WAIT_MS = 1_000;

    /** 读评分行的窄口（生产为 {@link RatingStore#find}）：只返回有行的人，失败抛运行期异常。 */
    @FunctionalInterface
    interface Source {
        Map<Long, RatingStore.Rating> find(Collection<Long> playerIds);
    }

    private final Source source;
    private final ThreadPoolExecutor pool;
    private final long waitMs;

    public JdbcRatingReader(RatingStore store) {
        this(store::find, THREADS, QUEUE, WAIT_MS);
    }

    JdbcRatingReader(Source source, int threads, int queue, long waitMs) {
        this.source = Objects.requireNonNull(source, "source");
        this.waitMs = waitMs;
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
                Thread.ofPlatform().name(POOL_NAME + "-", 0).daemon(true).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public long loadCentiOrDefault(long playerId) {
        return loadAllCentiOrDefault(List.of(playerId)).get(playerId);
    }

    @Override
    public Map<Long, Long> loadAllCentiOrDefault(Collection<Long> playerIds) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Long playerId : playerIds) {
            if (playerId != null) {
                out.put(playerId, DEFAULT_CENTI);
            }
        }
        if (out.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = List.copyOf(out.keySet());
        Future<Map<Long, RatingStore.Rating>> pending;
        try {
            pending = pool.submit(() -> source.find(distinct));
        } catch (RejectedExecutionException e) {
            log.error("[rating] 读评分的线程池已满或已关闭，按默认 1500 处理 players={}", unsigned(distinct));
            return Collections.unmodifiableMap(out);
        }
        try {
            pending.get(waitMs, TimeUnit.MILLISECONDS).forEach((playerId, row) -> {
                if (out.containsKey(playerId)) {
                    out.put(playerId, row.ratingCenti());
                }
            });
        } catch (TimeoutException e) {
            pending.cancel(false);
            log.error("[rating] 读评分超过 {} ms，按默认 1500 处理 players={}", waitMs, unsigned(distinct));
        } catch (ExecutionException e) {
            log.error("[rating] 读评分失败，按默认 1500 处理 players={}: {}", unsigned(distinct), String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            pending.cancel(false);
            Thread.currentThread().interrupt();
            log.error("[rating] 读评分被中断，按默认 1500 处理 players={}", unsigned(distinct));
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        new ExecutorServiceMetrics(pool, POOL_NAME, Tags.empty()).bindTo(registry);
    }

    /** 排队等线程的读的个数（测试与排障用）。 */
    int queued() {
        return pool.getQueue().size();
    }

    /** 停机：不再接新的读，在跑的由语句超时收尾（至多约 1 s）。 */
    @Override
    public void close() {
        pool.shutdownNow();
    }

    private static List<String> unsigned(List<Long> ids) {
        return ids.stream().map(Long::toUnsignedString).toList();
    }
}
