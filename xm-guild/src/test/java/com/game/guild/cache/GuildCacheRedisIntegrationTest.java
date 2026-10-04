package com.game.guild.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.guild.cache.GuildCacheMetrics.CacheKind;
import com.game.guild.cache.GuildCacheMetrics.CacheResult;
import com.game.guild.cache.pb.GuildMemberSnapshot;
import com.game.guild.cache.pb.GuildSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 缓存脚本、批量读与申请推送冷却在真 Redis 上的行为（guild-spec §11.4；缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}）。
 * 用 DB 10；帮会号 / 玩家号取 ≥ 2^63 的随机区间，只删自己写过的键，不 FLUSHDB。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class GuildCacheRedisIntegrationTest {

    private static final int DB = 10;
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final long BASE = Long.MIN_VALUE + (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 40) * 64;
    private static final Set<Long> USED = ConcurrentHashMap.newKeySet();
    private static RedissonClient redis;

    private final RecordingCacheMetrics metrics = new RecordingCacheMetrics();
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, GuildSnapshot> guildRows = new ConcurrentHashMap<>();
    private final Map<Long, Long> memberRows = new ConcurrentHashMap<>();
    private final AtomicInteger snapshotLoads = new AtomicInteger();

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void cleanup() {
        List<String> keys = new ArrayList<>();
        for (long id : USED) {
            for (String key : List.of(RedisKeys.guildSnapshot(id), RedisKeys.guildOfPlayer(id))) {
                keys.add(key);
                keys.add(RedisKeys.cacheGeneration(key));
            }
            for (long other : USED) {
                keys.add(RedisKeys.guildApplyPush(id, other));
            }
        }
        if (!keys.isEmpty()) {
            redis.getKeys().delete(keys.toArray(String[]::new));
        }
        redis.shutdown();
    }

    @AfterEach
    void stopBackground() {
        background.shutdownNow();
    }

    private static long id(int n) {
        long id = BASE + n;
        USED.add(id);
        return id;
    }

    private GuildCache cache(GuildCache.CacheRedis cacheRedis) {
        GuildCacheInvalidator invalidator = new GuildCacheInvalidator(cacheRedis, TTL, background, metrics, List.of(5L, 5L, 5L),
                1_000);
        return new GuildCache(cacheRedis, TTL, (guildId, deadline) -> {
            snapshotLoads.incrementAndGet();
            return guildRows.get(guildId);
        }, (playerId, deadline) -> memberRows.getOrDefault(playerId, 0L), invalidator, metrics);
    }

    private static GuildSnapshot snapshot(long guildId, String name, long... members) {
        GuildSnapshot.Builder b = GuildSnapshot.newBuilder().setGuildId(guildId).setName(name).setLevel(2).setZoneId(-1)
                .setScore(Long.MIN_VALUE).setFunds(-1L).setAnnouncement("公告\u0000尾");
        for (long member : members) {
            b.addMembers(GuildMemberSnapshot.newBuilder().setPlayerId(member).setRole(0).setContributionTotal(-1L));
        }
        return b.build();
    }

    private byte[] raw(String key) {
        return redis.<byte[]>getBucket(key, ByteArrayCodec.INSTANCE).get();
    }

    private long ttl(String key) {
        return redis.getBucket(key, ByteArrayCodec.INSTANCE).remainTimeToLive();
    }

    @Test
    void 回填与失效脚本_字节原样往返_TTL与代次() throws Exception {
        long g = id(1);
        long p = id(2);
        GuildSnapshot row = snapshot(g, "帮会 ∑", p);
        guildRows.put(g, row);
        GuildCache cache = cache(new RedissonGuildCacheRedis(redis));
        assertThat(cache.guild(g, Deadline.after(3_000))).contains(row);
        String key = RedisKeys.guildSnapshot(g);
        assertThat(GuildSnapshot.parseFrom(raw(key))).isEqualTo(row);
        assertThat(ttl(key)).isBetween(TimeUnit.MINUTES.toMillis(29), TimeUnit.MINUTES.toMillis(30));
        String gen = RedisKeys.cacheGeneration(key);
        assertThat(ttl(gen)).isBetween(TimeUnit.MINUTES.toMillis(59), TimeUnit.MINUTES.toMillis(60));
        assertThat(cache.guild(g, Deadline.after(3_000))).contains(row);
        assertThat(snapshotLoads).hasValue(1);

        // 失效：代次换成「UUID:服务器时间」并删数据键
        String before = redis.<String>getBucket(gen, StringCodec.INSTANCE).get();
        new GuildCacheInvalidator(new RedissonGuildCacheRedis(redis), TTL, background, metrics)
                .afterCommit(InvalidationOp.ANNOUNCEMENT, g, List.of(), Deadline.after(3_000));
        assertThat(redis.getBucket(key).isExists()).isFalse();
        String after = redis.<String>getBucket(gen, StringCodec.INSTANCE).get();
        assertThat(after).isNotEqualTo(before).matches("[0-9a-f-]{36}:\\d+\\.\\d+");
        assertThat(ttl(gen)).isBetween(TimeUnit.MINUTES.toMillis(59), TimeUnit.MINUTES.toMillis(60));

        // 代次不符 / 代次缺失的回填被拒
        RedissonGuildCacheRedis scripts = new RedissonGuildCacheRedis(redis);
        assertThat(scripts.fill(gen, key, before.getBytes(), row.toByteArray(), 1_000).toCompletableFuture().join())
                .isFalse();
        assertThat(scripts.fill(gen, key, after.getBytes(), row.toByteArray(), 1_000).toCompletableFuture().join()).isTrue();
        redis.getKeys().delete(gen);
        assertThat(scripts.fill(gen, key, after.getBytes(), row.toByteArray(), 1_000).toCompletableFuture().join())
                .isFalse();
        // 读者遇到代次缺失自己建一个（带 TTL）
        byte[] created = scripts.generation(gen, "mine".getBytes(), 5_000).toCompletableFuture().join();
        assertThat(new String(created)).isEqualTo("mine");
        assertThat(ttl(gen)).isBetween(1L, 5_000L);
        assertThat(new String(scripts.generation(gen, "other".getBytes(), 5_000).toCompletableFuture().join()))
                .isEqualTo("mine");
        // 回填成功把代次续到 2 × 数据 TTL
        assertThat(scripts.fill(gen, key, "mine".getBytes(), row.toByteArray(), 100_000).toCompletableFuture().join()).isTrue();
        assertThat(ttl(gen)).isBetween(190_000L, 200_000L);
    }

    @Test
    void 回源发现帮会不存在_读者建的候选代次几秒就过期_不留一小时的孤儿键() throws Exception {
        long missing = id(9);
        GuildCache cache = cache(new RedissonGuildCacheRedis(redis));
        assertThat(cache.guild(missing, Deadline.after(3_000))).isEmpty();
        String gen = RedisKeys.cacheGeneration(RedisKeys.guildSnapshot(missing));
        assertThat(ttl(gen)).isBetween(1L, GuildCache.CANDIDATE_GENERATION_TTL_MS);
        redis.getKeys().delete(gen);
    }

    @Test
    void 写者在读者快照之后提交并失效_持旧快照的回填被拒() {
        long g = id(3);
        guildRows.put(g, snapshot(g, "旧"));
        RedissonGuildCacheRedis real = new RedissonGuildCacheRedis(redis);
        GuildCache.CacheRedis racing = new Delegating(real) {
            @Override
            public CompletionStage<Boolean> fill(String generationKey, String dataKey, byte[] expectedGeneration,
                                                 byte[] payload, long ttlMillis) {
                guildRows.put(g, snapshot(g, "新"));
                new GuildCacheInvalidator(real, TTL, background, metrics)
                        .afterCommit(InvalidationOp.SET_ROLE, g, List.of(), Deadline.after(3_000));
                return super.fill(generationKey, dataKey, expectedGeneration, payload, ttlMillis);
            }
        };
        assertThat(cache(racing).guild(g, Deadline.after(3_000)).orElseThrow().getName()).isEqualTo("旧");
        assertThat(raw(RedisKeys.guildSnapshot(g))).isNull();
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.FILL_SKIPPED)).isEqualTo(1);
        assertThat(cache(real).guild(g, Deadline.after(3_000)).orElseThrow().getName()).isEqualTo("新");
    }

    @Test
    void 映射0也缓存_回填失败不让读失败() {
        long p = id(4);
        GuildCache cache = cache(new RedissonGuildCacheRedis(redis));
        assertThat(cache.guildIdOf(p, Deadline.after(3_000))).isZero();
        assertThat(redis.<String>getBucket(RedisKeys.guildOfPlayer(p), StringCodec.INSTANCE).get()).isEqualTo("0");

        long q = id(5);
        long g = id(6);
        memberRows.put(q, g);
        GuildCache.CacheRedis failingFill = new Delegating(new RedissonGuildCacheRedis(redis)) {
            @Override
            public CompletionStage<Boolean> fill(String generationKey, String dataKey, byte[] expectedGeneration,
                                                 byte[] payload, long ttlMillis) {
                return CompletableFuture.failedFuture(new IllegalStateException("fill down"));
            }
        };
        assertThat(cache(failingFill).guildIdOf(q, Deadline.after(3_000))).isEqualTo(g);
        assertThat(metrics.count(CacheKind.MAPPING, CacheResult.FILL_FAILED)).isEqualTo(1);
        assertThat(cache.guildIdOf(q, Deadline.after(3_000))).isEqualTo(g);
        assertThat(redis.<String>getBucket(RedisKeys.guildOfPlayer(q), StringCodec.INSTANCE).get())
                .isEqualTo(Long.toUnsignedString(g));
    }

    @Test
    void 按键单飞_同键只回源一次_不同键互不阻塞() throws Exception {
        long g = id(7);
        long h = id(8);
        guildRows.put(g, snapshot(g, "g"));
        guildRows.put(h, snapshot(h, "h"));
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loadsOfG = new AtomicInteger();
        GuildCacheInvalidator invalidator = new GuildCacheInvalidator(new RedissonGuildCacheRedis(redis), TTL, background,
                metrics);
        GuildCache cache = new GuildCache(new RedissonGuildCacheRedis(redis), TTL, (guildId, deadline) -> {
            if (guildId == g) {
                loadsOfG.incrementAndGet();
                inLoader.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return guildRows.get(guildId);
        }, (playerId, deadline) -> 0L, invalidator, metrics);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Optional<GuildSnapshot>>> waiters = new ArrayList<>();
            waiters.add(pool.submit(() -> cache.guild(g, Deadline.after(5_000))));
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 4; i++) {
                waiters.add(pool.submit(() -> cache.guild(g, Deadline.after(5_000))));
            }
            assertThat(pool.submit(() -> cache.guild(h, Deadline.after(5_000))).get(2, TimeUnit.SECONDS)).isPresent();
            release.countDown();
            for (Future<Optional<GuildSnapshot>> waiter : waiters) {
                assertThat(waiter.get(5, TimeUnit.SECONDS)).isPresent();
            }
            assertThat(loadsOfG).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 批量展示读_一次MGET_缺失的回源回填() {
        long g1 = id(9);
        long g2 = id(10);
        long ghost = id(11);
        guildRows.put(g1, snapshot(g1, "一"));
        guildRows.put(g2, snapshot(g2, "二"));
        GuildCache cache = cache(new RedissonGuildCacheRedis(redis));
        cache.guild(g1, Deadline.after(3_000));
        snapshotLoads.set(0);
        Map<Long, GuildSnapshot> found = cache.guildsForDisplay(List.of(g1, g2, ghost), Deadline.after(3_000));
        assertThat(found).containsOnlyKeys(g1, g2);
        assertThat(snapshotLoads).hasValue(2);
        assertThat(raw(RedisKeys.guildSnapshot(g2))).isNotNull();
        assertThat(raw(RedisKeys.guildSnapshot(ghost))).isNull(); // 不做负缓存
    }

    @Test
    void 申请推送冷却_60秒内只放行一次() {
        long g = id(12);
        long p = id(13);
        ApplyPushCooldown cooldown = ApplyPushCooldown.redisson(redis);
        assertThat(cooldown.tryMark(g, p, Deadline.after(3_000))).isTrue();
        assertThat(cooldown.tryMark(g, p, Deadline.after(3_000))).isFalse();
        String key = RedisKeys.guildApplyPush(g, p);
        assertThat(redis.<String>getBucket(key, StringCodec.INSTANCE).get()).isEqualTo("1");
        assertThat(redis.getBucket(key).remainTimeToLive()).isBetween(1L, 60_000L);
        redis.getKeys().delete(key); // 冷却过期
        assertThat(cooldown.tryMark(g, p, Deadline.after(3_000))).isTrue();
    }

    /** 转发给真实现，子类覆写个别方法插入竞态或故障。 */
    private static class Delegating implements GuildCache.CacheRedis {

        private final GuildCache.CacheRedis delegate;

        Delegating(GuildCache.CacheRedis delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<byte[]> get(String key) {
            return delegate.get(key);
        }

        @Override
        public CompletionStage<Map<String, byte[]>> getAll(List<String> keys) {
            return delegate.getAll(keys);
        }

        @Override
        public CompletionStage<byte[]> generation(String generationKey, byte[] candidate, long ttlMillis) {
            return delegate.generation(generationKey, candidate, ttlMillis);
        }

        @Override
        public CompletionStage<Boolean> fill(String generationKey, String dataKey, byte[] expectedGeneration,
                                             byte[] payload, long ttlMillis) {
            return delegate.fill(generationKey, dataKey, expectedGeneration, payload, ttlMillis);
        }

        @Override
        public CompletionStage<Void> invalidate(String generationKey, String dataKey, byte[] newGeneration,
                                                long generationTtlMillis) {
            return delegate.invalidate(generationKey, dataKey, newGeneration, generationTtlMillis);
        }
    }
}
