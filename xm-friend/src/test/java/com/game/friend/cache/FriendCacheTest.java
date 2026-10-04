package com.game.friend.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.CacheKind;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.friend.store.FriendStore.FriendEdge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FriendCacheTest {

    private static final String KEY = RedisKeys.friendList(42);
    private static final String GEN = RedisKeys.friendCacheGeneration(KEY);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InMemoryCacheRedis redis = new InMemoryCacheRedis();
    private final FriendCache cache = new FriendCache(redis, Duration.ofMinutes(30), new FriendMetrics(registry));

    private List<FriendEdge> load(List<FriendEdge> rows, AtomicInteger loads) {
        return cache.load(CacheKind.LIST, KEY, FriendEdge.class, Deadline.after(2000), () -> {
            loads.incrementAndGet();
            return rows;
        });
    }

    @Test
    void 未命中回源并回填_再读命中_值是带TTL的JSON() {
        AtomicInteger loads = new AtomicInteger();
        List<FriendEdge> rows = List.of(new FriendEdge(7, 100), new FriendEdge(-5L, 200));
        assertThat(load(rows, loads)).isEqualTo(rows);
        assertThat(load(List.of(), loads)).isEqualTo(rows);
        assertThat(loads).hasValue(1);
        assertThat(redis.values.get(KEY)).contains("\"friend_player_id\":7").contains("\"since_ms\":100");
        assertThat(redis.ttls.get(KEY)).isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(registry.get("xm.friend.cache").tag("cache", "list").tag("result", "hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("xm.friend.cache").tag("cache", "list").tag("result", "miss").counter().count()).isEqualTo(1);
    }

    @Test
    void 空列表写成方括号_命中空列表不再回源() {
        AtomicInteger loads = new AtomicInteger();
        assertThat(load(List.of(), loads)).isEmpty();
        assertThat(redis.values.get(KEY)).isEqualTo("[]");
        assertThat(load(List.of(new FriendEdge(1, 1)), loads)).isEmpty();
        assertThat(loads).hasValue(1);
    }

    @Test
    void 写者在读者快照之后提交_回填被拒_下一次读拿到新值() {
        AtomicInteger loads = new AtomicInteger();
        redis.beforeFill = () -> cache.invalidateAfterCommit(List.of(KEY), Deadline.after(1000));
        assertThat(load(List.of(new FriendEdge(1, 1)), loads)).hasSize(1); // 本次返回的是 MySQL 权威结果
        assertThat(redis.values).doesNotContainKey(KEY);                   // 但旧快照没有落地
        assertThat(load(List.of(new FriendEdge(1, 1), new FriendEdge(2, 2)), loads)).hasSize(2);
        assertThat(registry.get("xm.friend.cache").tag("cache", "list").tag("result", "fill_skipped").counter().count()).isEqualTo(1);
    }

    @Test
    void 失效_代次写成唯一值带两倍TTL_并删除数据键() {
        redis.values.put(KEY, "[]");
        cache.invalidateAfterCommit(List.of(KEY), Deadline.after(1000));
        String first = redis.values.get(GEN);
        cache.invalidateAfterCommit(List.of(KEY), Deadline.after(1000));
        assertThat(redis.values.get(GEN)).isNotEqualTo(first).isNotEqualTo("0");
        assertThat(redis.ttls.get(GEN)).isEqualTo(Duration.ofMinutes(60).toMillis());
        assertThat(redis.values).doesNotContainKey(KEY);
    }

    @Test
    void 失效被重发_代次不会变回读者手里的旧值() {
        FriendCache.CacheRedis raw = redis;
        raw.invalidate(GEN, KEY, "writer-1", 1000);
        String readerSaw = raw.generation(GEN, "unused", 1000).toCompletableFuture().join();
        raw.invalidate(GEN, KEY, "writer-2", 1000);
        raw.invalidate(GEN, KEY, "writer-1", 1000); // writer-1 的 EVAL 被 Redisson 重发
        assertThat(raw.fill(GEN, KEY, readerSaw, "[]", 1000).toCompletableFuture().join()).isFalse();
    }

    @Test
    void 读者遇到代次缺失自己建一个_代次被淘汰后回填落空() {
        AtomicInteger loads = new AtomicInteger();
        assertThat(load(List.of(new FriendEdge(1, 1)), loads)).hasSize(1);
        assertThat(redis.values.get(GEN)).isNotNull();                       // 读者建的代次
        assertThat(redis.ttls.get(GEN)).isEqualTo(Duration.ofMinutes(60).toMillis());
        redis.values.remove(KEY);
        redis.beforeFill = () -> redis.values.remove(GEN);                   // 回填之前代次键被淘汰
        assertThat(load(List.of(new FriendEdge(2, 2)), loads)).hasSize(1);
        assertThat(redis.values).doesNotContainKey(KEY);
    }

    @Test
    void 读失败与坏值都是依赖故障_回填失败与失效失败只记指标() {
        redis.failGet = true;
        assertThatThrownBy(() -> load(List.of(), new AtomicInteger())).isInstanceOf(DependencyException.class);
        redis.failGet = false;
        redis.values.put(KEY, "{not json");
        assertThatThrownBy(() -> load(List.of(), new AtomicInteger())).isInstanceOf(DependencyException.class);
        redis.values.remove(KEY);

        redis.failFill = true;
        assertThat(load(List.of(new FriendEdge(3, 3)), new AtomicInteger())).hasSize(1);
        assertThat(registry.get("xm.friend.cache").tag("cache", "list").tag("result", "fill_failed").counter().count()).isEqualTo(1);

        redis.failInvalidate = true;
        cache.invalidateAfterCommit(List.of(KEY, RedisKeys.friendPending(42)), Deadline.after(1000));
        assertThat(registry.get("xm.friend.cache.invalidation.failures").counter().count()).isEqualTo(2);
    }

    @Test
    void 回源异常原样上抛() {
        assertThatThrownBy(() -> cache.load(CacheKind.LIST, KEY, FriendEdge.class, Deadline.after(1000), () -> {
            throw new IllegalStateException("mysql down");
        })).isInstanceOf(IllegalStateException.class).hasMessage("mysql down");
        // 单飞已回收：下一次读照常回源
        assertThat(load(List.of(new FriendEdge(9, 9)), new AtomicInteger())).hasSize(1);
    }

    @Test
    void 单飞_并发未命中只回源一次() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<FriendEdge>>> results = new ArrayList<>();
            results.add(pool.submit(() -> cache.load(CacheKind.LIST, KEY, FriendEdge.class, Deadline.after(5000), () -> {
                loads.incrementAndGet();
                inLoader.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(new FriendEdge(1, 1));
            })));
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 7; i++) {
                results.add(pool.submit(() -> cache.load(CacheKind.LIST, KEY, FriendEdge.class, Deadline.after(5000), () -> {
                    loads.incrementAndGet();
                    return List.of(new FriendEdge(1, 1));
                })));
            }
            Thread.sleep(100);
            release.countDown();
            for (Future<List<FriendEdge>> f : results) {
                assertThat(f.get(5, TimeUnit.SECONDS)).hasSize(1);
            }
            assertThat(loads).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 等单飞超过预算是依赖故障() throws Exception {
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread leader = Thread.ofPlatform().start(() -> cache.load(CacheKind.LIST, KEY, FriendEdge.class,
                Deadline.after(5000), () -> {
                    inLoader.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                }));
        assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> cache.load(CacheKind.LIST, KEY, FriendEdge.class, Deadline.after(50), List::of))
                .isInstanceOf(DependencyException.class);
        release.countDown();
        leader.join(5000);
    }
}
