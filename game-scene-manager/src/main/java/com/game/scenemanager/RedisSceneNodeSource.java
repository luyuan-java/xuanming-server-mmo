package com.game.scenemanager;

import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import java.util.List;
import org.redisson.api.RedissonClient;

/**
 * 从 Redis 节点目录读 scene 节点（scene 节点每 5s 上报一次、条目 TTL 15s，见 architecture.md §6）；
 * 类型名 {@link NodeTypes#SCENE} 与 game-scene 上报时共用同一常量。
 * 单条解析失败由 {@link NodeDirectory} 跳过并告警；Redis 不可达时异常原样抛出（见 {@link SceneNodeSource} 契约）。
 */
public final class RedisSceneNodeSource implements SceneNodeSource {

    private final NodeDirectory<SceneNodeInfo> directory;

    public RedisSceneNodeSource(RedissonClient redis, RedisKeys redisKeys) {
        this.directory = new NodeDirectory<>(redis, redisKeys, NodeTypes.SCENE, SceneNodeInfo.parser());
    }

    @Override
    public List<SceneNodeInfo> list(int zoneId) {
        return directory.list(zoneId);
    }

    @Override
    public java.util.Optional<SceneNodeInfo> find(int zoneId, int nodeId) {
        // Dubbo 业务线程上的只读准入查询，独立有界；不是场景逻辑线程。
        return directory.findAsync(zoneId, nodeId).orTimeout(3, java.util.concurrent.TimeUnit.SECONDS).join();
    }
}
