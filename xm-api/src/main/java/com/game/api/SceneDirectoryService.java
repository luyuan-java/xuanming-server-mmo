package com.game.api;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 场景分配（Dubbo 服务，xm-scene-manager 提供）：玩家该进哪个 scene 节点的哪个场景。
 *
 * <p>数据来自 Redis 节点目录（scene 节点定期上报），服务本身无状态，可多实例。
 * 没有可用场景时返回带 tip 的应答，不以异常表达。
 */
public interface SceneDirectoryService {

    /** 进游戏时选场景（login 调用）。 */
    CompletableFuture<AssignSceneResponse> assign(AssignSceneRequest request);

    /**
     * 在线换图选跨节点目标（scene 调用，批次 5.2）：本节点解析不了 63 的目标时，由 scene-manager 在节点目录里选。
     * 只做选择，不铸 epoch、不写位置记录、不碰归属。业务拒绝走 {@code tip_id}；
     * 节点目录读不到等基础设施异常以 future 异常完成，表示「调用失败」。
     */
    CompletableFuture<SelectSwitchTargetResponse> selectSwitchTarget(SelectSwitchTargetRequest request);
}
