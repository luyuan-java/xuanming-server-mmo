package com.game.api;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 场景分配（Dubbo 服务，xm-scene-manager 提供）：玩家该进哪个 scene 节点的哪个场景。
 *
 * <p>数据来自 Redis 节点目录（scene 节点定期上报），服务本身无状态，可多实例。
 * 没有可用场景时返回带 tip 的应答，不以异常表达。
 *
 * <p><b>滚动升级顺序</b>（批次 5.3，dungeon-mirror-spec R7）：先升级 scene-manager，再升级 scene。新 scene + 旧 scene-manager 时
 * {@link #createInstance} 不存在 → 调用失败 → 建镜像推 23 {1003}（fail-closed）；但旧 scene-manager 选频道不看
 * {@code SceneEntry.kind}，会把登录分进新节点上的镜像，所以顺序不能反。
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

    /**
     * 为一个新的镜像 / 副本实例取全服 scene_id 并定放置（scene 调用，批次 5.3，dungeon-mirror-spec §6.5、§6.6）。
     * scene-manager 对实例零状态：只校验参数、发号、定放置（5.3 恒为发起节点），不读也不写 Redis 目录、不写预占、不调 scene；
     * 节点拿到号后在本地建实例并登记进节点目录。业务拒绝（参数错 3005；发起节点不接新实例 3000，5.5 钩子）走 {@code tip_id}；
     * 发号租约无效（Q12）等基础设施异常以 future 异常完成，表示「调用失败」（scene 推 23 {1003}）。不幂等，调用方 {@code retries = 0}。
     */
    CompletableFuture<CreateInstanceResponse> createInstance(CreateInstanceRequest request);
}
