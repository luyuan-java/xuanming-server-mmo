package com.game.scenemanager.world;

import com.game.scenemanager.SceneAssigner;
import com.game.scenemanager.SceneNodeSource;
import java.util.Objects;

/** 实例准入的严格单条目录读侧；控制面直接使用本拍 DirectoryView，不重复读取。 */
public final class DirectoryNodeAvailability implements NodeAvailability {
    private final SceneNodeSource source;
    public DirectoryNodeAvailability(SceneNodeSource source) { this.source = Objects.requireNonNull(source); }
    @Override public boolean acceptsNewChannels(int zoneId, int nodeId) {
        return source.find(zoneId, nodeId).filter(node -> node.getNodeId() == nodeId
                && SceneAssigner.isUsable(node, zoneId) && !node.getEvacuating()).isPresent();
    }

    @Override public boolean acceptsNewInstance(int zoneId, int nodeId, String instanceId, long leaseEpoch) {
        if (instanceId == null || instanceId.isBlank() || leaseEpoch == 0) return false;
        return source.find(zoneId, nodeId).filter(node -> node.getNodeId() == nodeId
                && SceneAssigner.isUsable(node, zoneId) && !node.getEvacuating()
                && node.getInstanceId().equals(instanceId) && node.getLeaseEpoch() == leaseEpoch).isPresent();
    }
}
