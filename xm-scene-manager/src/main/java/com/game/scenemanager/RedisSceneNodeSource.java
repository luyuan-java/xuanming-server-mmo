package com.game.scenemanager;

import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import java.util.List;
import org.redisson.api.RedissonClient;

/**
 * 从 Redis 节点目录读 scene 节点（scene 节点每 5s 上报一次、条目 TTL 15s，见 architecture.md §6）；
 * 类型名 {@link NodeTypes#SCENE} 与 xm-scene 上报时共用同一常量。
 * 单条解析失败由 {@link NodeDirectory} 跳过并告警；Redis 不可达时异常原样抛出（见 {@link SceneNodeSource} 契约）。
 */
public final class RedisSceneNodeSource implements SceneNodeSource {

    private final NodeDirectory<SceneNodeInfo> directory;

    public RedisSceneNodeSource(RedissonClient redis) {
        this.directory = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
    }

    @Override
    public List<SceneNodeInfo> list(int zoneId) {
        return directory.list(zoneId);
    }
}
