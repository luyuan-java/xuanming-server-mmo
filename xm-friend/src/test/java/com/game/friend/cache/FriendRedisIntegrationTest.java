package com.game.friend.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.CacheKind;
import com.game.friend.quota.FriendRequestQuota;
import com.game.friend.support.Deadline;
import com.game.friend.store.FriendStore.FriendEdge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 两段缓存 Lua 与配额 Lua 在真 Redis 上的行为（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，用 DB 13）。 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class FriendRedisIntegrationTest {

    private static final long BASE = (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void cleanup() {
        for (long i = 0; i < 4; i++) {
            String list = RedisKeys.friendList(BASE + i);
            redis.getKeys().delete(list, RedisKeys.friendCacheGeneration(list), RedisKeys.friendRequestQuota(BASE + i));
        }
        redis.shutdown();
    }

    @Test
    void 回填与失效脚本() {
        String key = RedisKeys.friendList(BASE);
        FriendCache cache = new FriendCache(new RedissonFriendCacheRedis(redis), Duration.ofMinutes(30),
                new FriendMetrics(new SimpleMeterRegistry()));
        List<FriendEdge> rows = List.of(new FriendEdge(-2L, 5));
        assertThat(cache.load(CacheKind.LIST, key, FriendEdge.class, Deadline.after(3000), () -> rows)).isEqualTo(rows);
        assertThat(redis.getBucket(key, StringCodec.INSTANCE).get()).isNotNull();
        long ttl = redis.getBucket(key, StringCodec.INSTANCE).remainTimeToLive();
        assertThat(ttl).isBetween(TimeUnit.MINUTES.toMillis(29), TimeUnit.MINUTES.toMillis(30));
        assertThat(cache.load(CacheKind.LIST, key, FriendEdge.class, Deadline.after(3000), List::of)).isEqualTo(rows);

        cache.invalidateAfterCommit(List.of(key), Deadline.after(3000));
        assertThat(redis.getBucket(key, StringCodec.INSTANCE).isExists()).isFalse();
        String generationKey = RedisKeys.friendCacheGeneration(key);
        assertThat(redis.getBucket(generationKey, StringCodec.INSTANCE).remainTimeToLive())
                .isBetween(TimeUnit.MINUTES.toMillis(59), TimeUnit.MINUTES.toMillis(60));

        // 代次不符的回填被拒
        RedissonFriendCacheRedis raw = new RedissonFriendCacheRedis(redis);
        assertThat(raw.fill(generationKey, key, "0", "[]", 1000).toCompletableFuture().join()).isFalse();
        String current = raw.get(generationKey).toCompletableFuture().join();
        assertThat(raw.fill(generationKey, key, current, "[]", 1000).toCompletableFuture().join()).isTrue();
    }

    @Test
    void 配额脚本_计数带60秒过期_自愈无TTL的存量计数器() {
        FriendRequestQuota quota = FriendRequestQuota.redisson(redis, 2, new FriendMetrics(new SimpleMeterRegistry()));
        long player = BASE + 1;
        assertThat(quota.tryAcquire(player, Deadline.after(3000))).isTrue();
        assertThat(quota.tryAcquire(player, Deadline.after(3000))).isTrue();
        assertThat(quota.tryAcquire(player, Deadline.after(3000))).isFalse();
        String key = RedisKeys.friendRequestQuota(player);
        assertThat(redis.getBucket(key, StringCodec.INSTANCE).remainTimeToLive()).isBetween(1L, 60_000L);

        long legacy = BASE + 2;
        String legacyKey = RedisKeys.friendRequestQuota(legacy);
        redis.getBucket(legacyKey, StringCodec.INSTANCE).set("5"); // 没有 TTL 的存量计数器
        assertThat(quota.tryAcquire(legacy, Deadline.after(3000))).isFalse();
        assertThat(redis.getBucket(legacyKey, StringCodec.INSTANCE).remainTimeToLive()).isBetween(1L, 60_000L);
    }
}
