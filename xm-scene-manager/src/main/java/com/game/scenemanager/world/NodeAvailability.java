package com.game.scenemanager.world;

/**
 * 目录在场的节点能不能接新频道（scene-channels-spec §0.4、§4.6.2 P5 第 2 步；5.5 钩子）。5.5 的节点级排空标记接在这里：
 * 被标记的节点不再接新频道（也不算覆盖目标），但它名下已有的记录照常计数、照常推进。5.1 实现为 {@link #ALL}。
 * 调用发生在控制面 tick 线程上。
 */
@FunctionalInterface
public interface NodeAvailability {

    /** 5.1：在场即可接新频道。 */
    NodeAvailability ALL = (zoneId, nodeId) -> true;

    boolean acceptsNewChannels(int zoneId, int nodeId);
}
