package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.GateNodeInfo;
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
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 真 Redis 集成测试。默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。
 * 用 DB 13 与随机节点类型名隔离，结束时删除自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisDiscoveryIntegrationTest {

    private static RedissonClient redis;
    private static ScheduledExecutorService scheduler;
    private static final String TYPE = "it-" + UUID.randomUUID();

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterAll
    static void cleanup() {
        redis.getKeys().deleteByPattern(RedisKeys.PREFIX + "*" + TYPE + "*");
        scheduler.shutdownNow();
        redis.shutdown();
    }

    @Test
    void 节点号互斥_释放后可复用() {
        AtomicBoolean lost = new AtomicBoolean();
        try (NodeIdLease a = NodeIdLease.acquire(redis, scheduler, TYPE, 1, 1, 2, "a", Duration.ofSeconds(5), () -> lost.set(true));
             NodeIdLease b = NodeIdLease.acquire(redis, scheduler, TYPE, 1, 1, 2, "b", Duration.ofSeconds(5), () -> lost.set(true))) {
            assertThat(a.nodeId()).isEqualTo(1);
            assertThat(b.nodeId()).isEqualTo(2);
            assertThatThrownBy(() -> NodeIdLease.acquire(redis, scheduler, TYPE, 1, 1, 2, "c", Duration.ofSeconds(5), () -> { }))
                    .hasMessageContaining("已占满");
        }
        try (NodeIdLease again = NodeIdLease.acquire(redis, scheduler, TYPE, 1, 1, 2, "d", Duration.ofSeconds(5), () -> { })) {
            assertThat(again.nodeId()).isEqualTo(1);
        }
        assertThat(lost).isFalse();
    }

    @Test
    void 号被他人夺走时回调丢失() throws Exception {
        AtomicBoolean lost = new AtomicBoolean();
        NodeIdLease lease = NodeIdLease.acquire(redis, scheduler, TYPE, 2, 1, 1, "mine", Duration.ofMillis(600), () -> lost.set(true));
        redis.getBucket(RedisKeys.nodeId(TYPE, 2, 1), org.redisson.client.codec.StringCodec.INSTANCE).set("thief");
        Thread.sleep(700);
        assertThat(lost).isTrue();
        assertThat(lease.isLost()).isTrue();
        lease.close();
        assertThat(redis.getBucket(RedisKeys.nodeId(TYPE, 2, 1), org.redisson.client.codec.StringCodec.INSTANCE).get())
                .isEqualTo("thief");
    }

    @Test
    void 目录条目过期后不可见() throws Exception {
        NodeDirectory<GateNodeInfo> dir = new NodeDirectory<>(redis, TYPE, GateNodeInfo.parser());
        dir.publish(3, 7, GateNodeInfo.newBuilder().setNodeId(7).setPlayerCount(2).build(), Duration.ofMillis(400));
        assertThat(dir.list(3)).extracting(GateNodeInfo::getNodeId).containsExactly(7);
        Thread.sleep(600);
        assertThat(dir.list(3)).isEmpty();
    }
}
