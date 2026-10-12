package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 真 Redis 上的命名空间隔离（{@code -Dgame.it.redis}，缺省跳过）：同一个 JVM 里直接构造 {@code game} 与 {@code nstest} 两个实例；
 * 另一个部署（缺省命名空间 game）占着某个节点号时，nstest 里照样能拿到同一个号，那个 {@code game:} 键的值与 TTL 原样不动。只验证名字层（键与租约），
 * 不是两套部署同时运行。用 DB 13 与随机的测试专用节点类型，只删自己写的键，不碰真实节点类型。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class NamespaceRedisIsolationIntegrationTest {

    private static final String TYPE = "it-ns-" + UUID.randomUUID();
    private static final RedisKeys NSTEST_KEYS = new RedisKeys(GameNamespace.of("nstest"));
    private static final RedisKeys GAME_KEYS = new RedisKeys(GameNamespace.of("game"));
    private static RedissonClient redis;
    private static ScheduledExecutorService scheduler;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterAll
    static void cleanup() {
        if (redis != null) {
            redis.getKeys().deleteByPattern("nstest:*" + TYPE + "*");
            redis.getKeys().deleteByPattern("game:*" + TYPE + "*");
            redis.shutdown();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void 别的命名空间占着同号_本命名空间照样拿到_对方的键不动() {
        String otherTenantKey = GAME_KEYS.nodeId(TYPE, 1, 1);
        assertThat(otherTenantKey).isEqualTo("game:node-id:" + TYPE + ":1:1");
        RBucket<String> other = redis.getBucket(otherTenantKey, StringCodec.INSTANCE);
        other.set("other-tenant", Duration.ofSeconds(120));
        assertThat(NSTEST_KEYS.nodeId(TYPE, 1, 1)).isEqualTo("nstest:node-id:" + TYPE + ":1:1").isNotEqualTo(otherTenantKey);

        AtomicBoolean lost = new AtomicBoolean();
        try (NodeIdLease lease = NodeIdLease.acquire(redis, NSTEST_KEYS, scheduler, TYPE, 1, 1, 1, "mine", Duration.ofSeconds(5),
                () -> lost.set(true))) {
            assertThat(lease.nodeId()).as("池里只有 1 号，被另一个命名空间占着也拿得到").isEqualTo(1);
            assertThat(redis.getBucket(NSTEST_KEYS.nodeId(TYPE, 1, 1), StringCodec.INSTANCE).get()).isEqualTo("mine");
        }
        assertThat(lost).isFalse();
        assertThat(other.get()).isEqualTo("other-tenant");
        assertThat(other.remainTimeToLive()).as("TTL 原样").isPositive();
    }
}
