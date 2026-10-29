package com.game.scenemanager.world;

/**
 * 目录在场的节点能不能接新频道（scene-channels-spec §0.4、§4.6.2 P5 第 2 步；5.5 钩子）。5.5 的节点级排空标记接在这里：
 * 被标记的节点不再接新频道（也不算覆盖目标），其 ACTIVE 记录由 P2b 转疏散排空，仍保留直到收尾。
 * 调用发生在控制面 tick 线程上；批次 5.3 起实例取号（{@code InstanceIdIssuer}，被标记的发起节点不接新实例）也在 Dubbo 业务线程上调，
 * 实现须线程安全；控制面直接读本拍快照，实例准入可在 Dubbo 业务线程上进行有界目录读取。
 */
@FunctionalInterface
public interface NodeAvailability {

    /** 5.1：在场即可接新频道。 */
    NodeAvailability ALL = (zoneId, nodeId) -> true;

    boolean acceptsNewChannels(int zoneId, int nodeId);

    /** 实例调用原件；旧内存替身可沿用节点判断，生产 DirectoryNodeAvailability 必须核对实例与代次。 */
    default boolean acceptsNewInstance(int zoneId, int nodeId, String instanceId, long leaseEpoch) {
        return acceptsNewChannels(zoneId, nodeId);
    }
}
