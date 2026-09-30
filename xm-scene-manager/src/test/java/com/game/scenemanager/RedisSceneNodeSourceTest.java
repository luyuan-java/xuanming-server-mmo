package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 真 Redis 集成测试：scene 节点按 xm-scene 的方式写目录，scene-manager 读出来并完成分配。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。
 * 用 DB 13 与一个不会被真实节点使用的 zone 号隔离，结束时删除自己写的条目。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisSceneNodeSourceTest {

    /** 远离真实 zone 的测试 zone 号。 */
    private static final int ZONE = 900_001;

    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void cleanup() {
        NodeDirectory<SceneNodeInfo> dir = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        dir.remove(ZONE, 1);
        dir.remove(ZONE, 2);
        redis.shutdown();
    }

    @Test
    void 读到scene节点上报的目录并选人数最少的场景() {
        NodeDirectory<SceneNodeInfo> dir = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        dir.publish(ZONE, 1, node(1, 1001, 20), Duration.ofSeconds(15));
        dir.publish(ZONE, 2, node(2, 2001, 5), Duration.ofSeconds(15));

        RedisSceneNodeSource source = new RedisSceneNodeSource(redis);
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
