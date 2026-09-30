package com.game.gate.link;

import com.game.api.proto.SceneNodeInfo;
import java.util.Optional;

/** scene 节点号 → 链路地址。实现可以阻塞（查 Redis 节点目录），只在建链线程上调用。 */
@FunctionalInterface
public interface SceneNodeResolver {

    Optional<SceneNodeInfo> resolve(int sceneNodeId);
}
