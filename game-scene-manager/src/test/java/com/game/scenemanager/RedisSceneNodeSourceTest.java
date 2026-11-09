package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.common.GameNamespace;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 真 Redis 集成测试：scene 节点按 game-scene 的方式写目录，scene-manager 读出来并完成分配。
 * 默认跳过；显式开启：{@code -Dgame.it.redis=redis://127.0.0.1:6379}。
 * 用 DB 13、每次用例独占的随机命名空间与测试 zone 隔离，结束时只清自己的精确键。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class RedisSceneNodeSourceTest {

    private RedisKeys redisKeys;
    /** 摘除必须复用实际发布对象的原代次和序号，不能另建空的发布器。 */
    private NodeDirectory<SceneNodeInfo> directory;

    /** 远离真实 zone 的测试 zone 号。 */
    private static final int ZONE = 900_001;

    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @BeforeEach
    void ownDirectory() {
        // v2永久保存发布水位；重复测试不能复用旧实例的sequence=1和过期TTL。
        redisKeys = new RedisKeys(GameNamespace.of("smtest" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)));
        directory = new NodeDirectory<>(redis, redisKeys, NodeTypes.SCENE, SceneNodeInfo.parser());
    }

    @AfterEach
    void cleanup() {
        if (directory == null) return;
        try {
            directory.remove(ZONE, 1);
            directory.remove(ZONE, 2);
        } finally {
            // 只销毁本次随机命名空间内实际创建的五个键，不扫描或清空共享DB。
            redis.getKeys().delete(redisKeys.zoneNodeDirectory(NodeTypes.SCENE, ZONE),
                    redisKeys.zoneNodeExpiry(NodeTypes.SCENE, ZONE), redisKeys.zoneNodeSeen(NodeTypes.SCENE, ZONE),
                    redisKeys.zoneDirectoryFormat(ZONE), redisKeys.zoneNodeTypes(ZONE));
        }
    }

    @AfterAll
    static void disconnect() {
        if (redis != null) redis.shutdown();
    }

    @Test
    void 读到scene节点上报的目录并选人数最少的场景() {
        directory.publish(ZONE, 1, node(1, 1001, 20), Duration.ofSeconds(15));
        directory.publish(ZONE, 2, node(2, 2001, 5), Duration.ofSeconds(15));

        RedisSceneNodeSource source = new RedisSceneNodeSource(redis, redisKeys);
        assertThat(source.list(ZONE)).extracting(SceneNodeInfo::getNodeId).containsExactlyInAnyOrder(1, 2);

        AssignSceneResponse resp = new SceneAssigner(source, new WorldSceneConfigs(1, Set.of(1)))
                .assign(AssignSceneRequest.newBuilder().setZoneId(ZONE).setPlayerId(1).build());
        assertThat(resp.getTipId()).isZero();
        assertThat(resp.getSceneNodeId()).isEqualTo(2);
        assertThat(resp.getSceneId()).isEqualTo(2001);
    }

    private static SceneNodeInfo node(int nodeId, long sceneId, int playerCount) {
        return SceneNodeInfo.newBuilder()
                .setZoneId(ZONE)
                .setNodeId(nodeId)
                .setInstanceId("it-" + nodeId)
                .setLinkHost("127.0.0.1")
                .setLinkPort(21000)
                .addScenes(SceneEntry.newBuilder().setSceneId(sceneId).setSceneConfigId(1).setPlayerCount(playerCount))
                .build();
    }
}
