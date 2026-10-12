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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 真 Redis 上的命名空间隔离（namespace 执行 + {@code -Dgame.it.redis}，缺省跳过）：另一个部署（缺省命名空间 game）占着某个节点号时，
 * nstest 里照样能拿到同一个号；那个 {@code game:} 键的值与 TTL 原样不动。用 DB 13 与随机的测试专用节点类型，只删自己写的键，不碰真实节点类型。
 */
@Tag("namespace")
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class NamespaceRedisIsolationIntegrationTest {

    private static final String TYPE = "it-ns-" + UUID.randomUUID();
    private static RedissonClient redis;
    private static ScheduledExecutorService scheduler;

    @BeforeAll
    static void connect() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
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
        String otherTenantKey = "game:node-id:" + TYPE + ":1:1";
        RBucket<String> other = redis.getBucket(otherTenantKey, StringCodec.INSTANCE);
        other.set("other-tenant", Duration.ofSeconds(120));
        assertThat(RedisKeys.nodeId(TYPE, 1, 1)).isEqualTo("nstest:node-id:" + TYPE + ":1:1").isNotEqualTo(otherTenantKey);

        AtomicBoolean lost = new AtomicBoolean();
        try (NodeIdLease lease = NodeIdLease.acquire(redis, scheduler, TYPE, 1, 1, 1, "mine", Duration.ofSeconds(5), () -> lost.set(true))) {
            assertThat(lease.nodeId()).as("池里只有 1 号，被另一个命名空间占着也拿得到").isEqualTo(1);
            assertThat(redis.getBucket(RedisKeys.nodeId(TYPE, 1, 1), StringCodec.INSTANCE).get()).isEqualTo("mine");
        }
        assertThat(lost).isFalse();
        assertThat(other.get()).isEqualTo("other-tenant");
        assertThat(other.remainTimeToLive()).as("TTL 原样").isPositive();
    }
}
