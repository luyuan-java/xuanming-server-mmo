package com.game.api;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.SelectSwitchTargetResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
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
 * 批次 5.4 沿用同一顺序：先升级 scene-manager，再升级 scene / login。新 scene / login + 旧 scene-manager 时
 * {@link #selectTravelTarget}、{@link #redirectToZone} 不存在 → 调用失败（fail-closed）：226 在受理之后得到 23 {3027}、玩家留在原地可再试；
 * 登录期重定向（GO-5）退化为「在入口 zone 按首登进游戏」。两种退化都不动归属，数据安全。
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

    /**
     * 跨 zone 传送选目标（scene 调用，批次 5.4，zone-travel-spec §5.4）：玩家发 226 并被受理后，源 scene 请 scene-manager 一次做完
     * 「目标 zone 开没开这张地图（只读预检，不解析实例、不预占）→ 合服围栏检查点 → 在目标 zone 选一台 gate → 为这名玩家签重定向票据」。
     *
     * <p>契约（同 {@link #selectSwitchTarget}）：
     * <ul>
     *   <li>只做选择与签名：不铸 epoch、不写位置记录、不碰归属；唯一的副作用是签名。归属交接由源节点随后的「交出并释放」事务完成，
     *       待落点位置记录由源节点写。</li>
     *   <li>业务拒绝走 {@code tip_id}（参数错 3005、目标 zone 没开这张图 3000、没有可用 gate 等；是 scene-manager 的内部码，只进日志与指标，
     *       scene 对任何非 0 一律推 23 {3027}）；{@code tip_id = 0} 时 {@code scene_config_id}（解析后的地图）非 0 且 {@code redirect} 必填，
     *       调用方读到残缺的应答按调用失败处理。</li>
     *   <li>目标 zone 的频道计划或 gate 目录读不到等基础设施异常以 future 异常完成，表示「调用失败」（scene 同样推 23 {3027}），
     *       不伪装成「没有这张图」。</li>
     *   <li>应答里的 {@code redirect.token_payload} 是签名时的原字节：调用方及其下游一律原样拷贝，不得解析后重新序列化。</li>
     *   <li>每次调用签一张新票据（有效期 300 s），不幂等也无须幂等；调用方 {@code retries = 0}。</li>
     * </ul>
     */
    CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request);

    /**
     * 登录期重定向选目标（login 调用，批次 5.4 的 GO-5，zone-travel-spec §5.4、§5.8）：请求进游戏的角色此刻正在别的 zone
     * （位置记录在线或断线重连租约内），在那个 zone 选一台 gate 并签重定向票据，login 据此把这条连接送过去。
     *
     * <p>契约：只送连接——不查地图、不查合服围栏、不夺权、不分配场景；目标 zone 的节点目录里没有任何可用的 scene 节点时拒绝
     * （{@code tip_id} = 3000，否则会把人送到一个落不了地的 zone）。业务拒绝走 {@code tip_id}，{@code tip_id = 0} 时 {@code redirect} 必填；
     * 节点目录读不到等基础设施异常以 future 异常完成。调用失败、超时或 {@code tip_id != 0} 时 login 回落为「在入口 zone 按首登进游戏」。
     * {@code redirect.token_payload} 同样原样拷贝；调用方 {@code retries = 0}。
     */
    CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request);
}
