package com.game.scenemanager;

import com.game.api.proto.SceneNodeInfo;
import java.util.List;

/**
 * 场景节点在线列表的来源。分配规则（{@link SceneAssigner}）只依赖这个接口，
 * 生产实现读 Redis 节点目录（{@link RedisSceneNodeSource}），单测用内存假实现。
 *
 * <p>契约：
 * <ul>
 *   <li>返回某 zone 下当前可见的 scene 节点快照；条目可能已过时最多一个目录 TTL（节点刚死、人数滞后一个上报周期），
 *       调用方不能把它当作强一致视图。</li>
 *   <li>没有节点时返回空列表，不返回 null。</li>
 *   <li>来源本身不可用（如 Redis 不可达）时抛 {@link RuntimeException}，<b>不</b>返回空列表冒充「没有节点」：
 *       两者对调用方的含义不同（前者是基础设施故障，后者是业务上无场景可进）。</li>
 *   <li>实现必须线程安全：Dubbo 业务线程会并发调用。</li>
 * </ul>
 */
public interface SceneNodeSource {

    List<SceneNodeInfo> list(int zoneId);
}
