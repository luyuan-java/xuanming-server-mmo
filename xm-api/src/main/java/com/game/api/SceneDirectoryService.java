package com.game.api;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 场景分配（Dubbo 服务，xm-scene-manager 提供）：玩家该进哪个 scene 节点的哪个场景。
 *
 * <p>数据来自 Redis 节点目录（scene 节点定期上报），服务本身无状态，可多实例。
 * 没有可用场景时返回带 tip 的应答，不以异常表达。
 */
public interface SceneDirectoryService {

    CompletableFuture<AssignSceneResponse> assign(AssignSceneRequest request);
}
