package com.game.discovery.drain;

import static com.game.discovery.drain.SceneEvacuationMarks.MarkResult.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.discovery.NodeTypes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 真 Redis Lua 并发验收；默认跳过，独立命名空间，只清自己写的三把键。 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class SceneEvacuationMarksRedisTest {
    private static RedissonClient redis;
    private final RedisKeys keys = new RedisKeys(GameNamespace.of("evac" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)));
    private SceneEvacuationMarks marks() { return new SceneEvacuationMarks(redis, keys); }
    private RBucket<String> bucket(int node) { return redis.getBucket(keys.sceneEvacuating(1, node), StringCodec.INSTANCE); }
    private RBucket<String> lease(int node) { return redis.getBucket(keys.nodeId(NodeTypes.SCENE, 1, node), StringCodec.INSTANCE); }

    @BeforeAll static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }
    @AfterAll static void close() { if (redis != null) redis.shutdown(); }
    @BeforeEach void leases() {
        lease(1).set("a", Duration.ofMinutes(5));
        lease(2).set("b", Duration.ofMinutes(5));
        lease(3).set("c", Duration.ofMinutes(5));
    }
    @AfterEach void cleanup() {
        for (int node = 1; node <= 3; node++) { marks().clear(1, node); lease(node).delete(); }
        redis.getKeys().delete(keys.worldRetirement(1));
    }

    @Test
    void 并发标两节点至多一方成功_管理副本共享同一最后节点闸() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { ready.countDown(); start.await(); return marks().mark(1, 1, "a", Duration.ofMinutes(5), Map.of(2, "b")); });
            var two = pool.submit(() -> { ready.countDown(); start.await(); return marks().mark(1, 2, "b", Duration.ofMinutes(5), Map.of(1, "a")); });
            boolean bothReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            assertThat(bothReady).isTrue();
            assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(MARKED, LAST_NODE);
            assertThat(marks().evacuating(1, List.of(1, 2))).hasSize(1);
        }
    }

    @Test
    void 起点来自Redis_重复不续TTL_同实例无TTL冲突_撤销后可重打() {
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), null)).isEqualTo(MARKED);
        var first = marks().read(1, 1);
        assertThat(first.markedAtSec()).isPositive();
        assertThat(first.raw()).isEqualTo(first.markedAtSec() + ":a");
        assertThat(bucket(1).remainTimeToLive()).isBetween(1L, 300_000L);
        bucket(1).expire(Duration.ofSeconds(60));
        long before = bucket(1).remainTimeToLive();
        assertThat(marks().mark(1, 1, "a", Duration.ofHours(1), Map.of())).isEqualTo(ALREADY_EVACUATING);
        assertThat(marks().read(1, 1)).isEqualTo(first);
        assertThat(bucket(1).remainTimeToLive()).isBetween(1L, before);
        bucket(1).clearExpire();
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), null)).isEqualTo(EXISTING_INVALID);
        assertThat(bucket(1).remainTimeToLive()).isEqualTo(-1);
        marks().clear(1, 1);
        marks().clear(1, 1);
        assertThat(marks().read(1, 1)).isNull();
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), Map.of())).isEqualTo(LAST_NODE);
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), null)).isEqualTo(MARKED);
    }

    @Test
    void 旧实例标记不阻新实例接客_同实例标记阻止_目标换代覆盖旧值() {
        bucket(2).set("1:old", Duration.ofMinutes(5));
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), Map.of(2, "b"))).isEqualTo(MARKED);
        assertThat(marks().mark(1, 2, "b", Duration.ofMinutes(5), Map.of(1, "a"))).isEqualTo(LAST_NODE);
        assertThat(marks().read(1, 2).appliesTo("b")).isFalse();
        assertThat(marks().mark(1, 2, "b", Duration.ofMinutes(5), null)).isEqualTo(MARKED);
        assertThat(marks().read(1, 2).appliesTo("b")).isTrue();
        assertThat(marks().mark(1, 3, "c", Duration.ofMinutes(5), Map.of(1, "a", 2, "b"))).isEqualTo(LAST_NODE);
        bucket(2).set("x:b", Duration.ofMinutes(5));
        assertThat(marks().mark(1, 2, "b", Duration.ofMinutes(5), null)).isEqualTo(EXISTING_INVALID);
    }

    @Test
    void 新旧目录快照交错不能同时标满_已丢租约目标即使force也拒绝() {
        lease(2).set("b-new", Duration.ofMinutes(5));
        assertThat(marks().mark(1, 2, "b-new", Duration.ofMinutes(5), Map.of(1, "a"))).isEqualTo(MARKED);
        // 旧请求把新实例标记误认作 stale，但同拍读取租约发现旧身份已不是后备容量。
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), Map.of(2, "b"))).isEqualTo(LAST_NODE);
        assertThat(marks().read(1, 1)).isNull();
        assertThat(marks().mark(1, 2, "b", Duration.ofMinutes(5), null)).isEqualTo(TARGET_CHANGED);
        assertThat(marks().read(1, 2).instanceId()).isEqualTo("b-new");
        lease(1).delete();
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), null)).isEqualTo(TARGET_CHANGED);
        assertThat(marks().read(1, 1)).isNull();
    }

    @Test
    void 永久退休优先于force与重复请求_不复活或刷新标记() {
        assertThat(marks().mark(1, 1, "a", Duration.ofMinutes(5), null)).isEqualTo(MARKED);
        var before = marks().read(1, 1);
        redis.<String>getBucket(keys.worldRetirement(1), StringCodec.INSTANCE).set("retired");
        assertThat(marks().mark(1, 1, "a", Duration.ofHours(1), null)).isEqualTo(ZONE_RETIRED);
        assertThat(marks().mark(1, 2, "b", Duration.ofHours(1), null)).isEqualTo(ZONE_RETIRED);
        assertThat(marks().read(1, 1)).isEqualTo(before);
        assertThat(marks().read(1, 2)).isNull();
        assertThat(redis.getBucket(keys.worldRetirement(1)).remainTimeToLive()).isEqualTo(-1);
    }
}
