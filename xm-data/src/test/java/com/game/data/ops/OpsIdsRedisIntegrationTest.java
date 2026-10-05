package com.game.data.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.game.common.id.Snowflake;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * T-N1 / §12.4 的 Redis 部分（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13，与其他 Redis 集成测试同一个测试库）：
 * xm-data 的号源与 scene 共池占 {@code NodeTypes.SCENE_GUID}（作用域 0）——两边 worker 不重叠、键就在 scene-guid 池里；停机交还。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class OpsIdsRedisIntegrationTest {

    private RedissonClient redis;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void close() {
        scheduler.shutdownNow();
        redis.shutdown();
    }

    @Test
    void 与scene共池占SCENE_GUID_worker不重叠_停机交还() {
        String sceneInstance = "scene-it-" + UUID.randomUUID();
        NodeIdLease scene = NodeIdLease.acquire(redis, scheduler, NodeTypes.SCENE_GUID, OpsIds.SCOPE, 1, Snowflake.MAX_WORKER,
                sceneInstance, OpsIds.LEASE_TTL, () -> { });
        String dataInstance = "xm-data-it-" + UUID.randomUUID();
        OpsIds ids = new OpsIds(OpsIds.sceneGuidPool(() -> redis, scheduler, dataInstance), scheduler,
                System::currentTimeMillis, Duration.ofMillis(200));
        try {
            ids.start();
            await().atMost(Duration.ofSeconds(10)).until(() -> ids.workerId().isPresent());
            int worker = ids.workerId().getAsInt();

            assertThat(worker).as("与 scene 的 worker 不重叠").isNotEqualTo(scene.nodeId());
            assertThat(redis.<String>getBucket(RedisKeys.nodeId(NodeTypes.SCENE_GUID, OpsIds.SCOPE, worker),
                    StringCodec.INSTANCE).get()).as("占的就是 scene-guid 全服池里的号").isEqualTo(dataInstance);
            long id = ids.tryNext().orElseThrow();
            assertThat((id >>> Snowflake.SEQUENCE_BITS) & Snowflake.MAX_WORKER).isEqualTo(worker);

            ids.stop();
            assertThat(redis.getBucket(RedisKeys.nodeId(NodeTypes.SCENE_GUID, OpsIds.SCOPE, worker), StringCodec.INSTANCE)
                    .isExists()).as("停机交还").isFalse();
        } finally {
            ids.close();
            scene.close();
        }
    }
}
