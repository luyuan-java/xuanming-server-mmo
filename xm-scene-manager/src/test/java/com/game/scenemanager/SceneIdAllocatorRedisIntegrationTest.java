package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.discovery.RedisKeys;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldChannels;
import com.game.scenemanager.world.NodeAvailability;
import com.game.scenemanager.world.WorldChannelControlPlane;
import com.game.scenemanager.world.WorldChannelMetrics;
import com.game.scenemanager.world.WorldChannelProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 全服 scene_id 发号器连真 Redis（批次 5.3 R5，dungeon-mirror-spec §12.4）：两个 scene-manager 副本（一个 {@code leader-eligible = false}）
 * 都占到发号租约、worker 不同、都能给镜像 / 副本实例取号且号不重复；不竞选的副本上控制面是空壳却照样拿得到 worker；
 * 租约被夺（键不再属于本实例）后续期判丢失 → 监听回调、实例取号转 NO_LEASE（调用失败，Q12）；关闭只删仍属于本实例的键。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 9（同 {@code WorldChannelRedisIntegrationTest}），结束时只删自己占的号。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class SceneIdAllocatorRedisIntegrationTest {

    private static final CreateInstanceRequest MIRROR = CreateInstanceRequest.newBuilder()
            .setZoneId(1).setRequesterSceneNodeId(7).setPlayerId(42).setKind(ChannelKind.CHANNEL_KIND_MIRROR)
            .setSourceSceneId(0x8000_0000_0000_0101L).setSceneConfigId(1).setMirrorConfigId(1).build();

    private static RedissonClient redis;
    private static final List<Integer> WORKERS = new ArrayList<>();

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(9);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void cleanup() {
        for (int worker : WORKERS) {
            redis.getBucket(RedisKeys.nodeIdEpoch(WorldChannels.ID_LEASE_NODE_TYPE, WorldChannels.ID_LEASE_ZONE, worker))
                    .delete();
        }
        redis.shutdown();
    }

    private static SceneIdAllocator acquire() {
        SceneIdAllocator ids = SceneIdAllocator.acquire(redis);
        synchronized (WORKERS) {
            WORKERS.add(ids.worker());
        }
        return ids;
    }

    private static boolean leaseKeyExists(int worker) {
        return redis.getBucket(RedisKeys.nodeId(WorldChannels.ID_LEASE_NODE_TYPE, WorldChannels.ID_LEASE_ZONE, worker))
                .isExists();
    }

    @Test
    void 两个副本都占到租约_不竞选的也能取号_worker不同_并发取号不重复() throws Exception {
        SceneIdAllocator leader = acquire();
        SceneIdAllocator canary = acquire();
        // 不竞选的副本：控制面是空壳（不起线程、不竞选），但发号器照样在（R5）
        WorldChannelProperties props = new Binder(new MapConfigurationPropertySource(
                Map.of("xm.scene-manager.world.leader-eligible", "false")))
                .bindOrCreate("xm.scene-manager.world", WorldChannelProperties.class);
        WorldChannelControlPlane shell = WorldChannelControlPlane.start(Mockito.mock(WorldChannelStore.class),
                zone -> List.of(), new WorldSceneConfigs(1, new LinkedHashSet<>(List.of(1))), props,
                new WorldChannelMetrics(new SimpleMeterRegistry(), List.of(1)), canary);
        try {
            assertThat(leader.worker()).isBetween(1, 1023).isNotEqualTo(canary.worker());
            assertThat(shell.coordinator()).isNull();
            assertThat(shell.idWorker()).isEqualTo(canary.worker());
            assertThat(leaseKeyExists(leader.worker())).isTrue();
            assertThat(leaseKeyExists(canary.worker())).isTrue();

            InstanceIdIssuer a = new InstanceIdIssuer(leader, NodeAvailability.ALL);
            InstanceIdIssuer b = new InstanceIdIssuer(canary, NodeAvailability.ALL);
            Set<Long> seen = ConcurrentHashMap.newKeySet();
            AtomicInteger duplicates = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (InstanceIdIssuer issuer : List.of(a, b, a, b)) {
                threads.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < 2000; i++) {
                        InstanceIdIssuer.Issue issue = issuer.issue(MIRROR);
                        assertThat(issue.result()).isEqualTo(InstanceIdIssuer.Result.OK);
                        CreateInstanceResponse response = issue.response();
                        assertThat(response.getSceneNodeId()).isEqualTo(7);
                        if (!seen.add(response.getSceneId())) {
                            duplicates.incrementAndGet();
                        }
                    }
                }));
            }
            for (Thread t : threads) {
                t.join();
            }
            assertThat(duplicates).hasValue(0);
            assertThat(seen).hasSize(8000).doesNotContain(0L);
        } finally {
            shell.close();
            leader.close();
            canary.close();
        }
        assertThat(leaseKeyExists(leader.worker())).as("关闭即还租约").isFalse();
        assertThat(leaseKeyExists(canary.worker())).isFalse();
    }

    @Test
    void 租约被夺后续期判丢失_监听回调_实例取号转NO_LEASE_关闭不删别人的键() throws Exception {
        SceneIdAllocator ids = acquire();
        int worker = ids.worker();
        AtomicInteger lost = new AtomicInteger();
        ids.onLost(lost::incrementAndGet);
        InstanceIdIssuer issuer = new InstanceIdIssuer(ids, NodeAvailability.ALL);
        assertThat(issuer.issue(MIRROR).result()).isEqualTo(InstanceIdIssuer.Result.OK);

        String key = RedisKeys.nodeId(WorldChannels.ID_LEASE_NODE_TYPE, WorldChannels.ID_LEASE_ZONE, worker);
        redis.<String>getBucket(key, StringCodec.INSTANCE).set("someone-else", Duration.ofSeconds(30));
        try {
            // 续期周期 = TTL/3 = 5 s：最多再等一轮多一点
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (!ids.isLost() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(ids.isLost()).isTrue();
            assertThat(lost).hasValue(1);
            assertThat(issuer.issue(MIRROR).result()).isEqualTo(InstanceIdIssuer.Result.NO_LEASE);

            ids.close();
            assertThat(redis.<String>getBucket(key, StringCodec.INSTANCE).get()).as("只删仍属于本实例的键")
                    .isEqualTo("someone-else");
        } finally {
            ids.close();
            redis.getBucket(key).delete();
        }
    }
}
