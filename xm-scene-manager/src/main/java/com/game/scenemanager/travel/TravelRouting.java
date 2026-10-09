package com.game.scenemanager.travel;

import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 跨 zone 的两种选路（批次 5.4，zone-travel-spec §5.4）：在目标 zone 选一台 gate 并签一张重定向票据。
 * {@code SceneDirectoryProvider} 把 {@code SceneDirectoryService} 的两个新方法原样委托到这里，自己不做任何判断。
 *
 * <p>契约同 {@code SceneDirectoryService} 的其余方法：<b>业务拒绝走应答里的 {@code tip_id}</b>（参数不合法、目标 zone 没有这张图 /
 * 没有可用的 scene 节点、没有可用的 gate、围栏拒绝）；<b>基础设施失败以异常完成</b>（目录 / 频道计划读不到等），表示「调用失败」
 * 而不是「没有目标」——调用方对两者的处理不同（226 都推 23 {3027}，但登录期重定向只在调用失败时计回落告警）。
 * 实现自己计时、自己记指标、自己把读失败转成以异常完成的 future；<b>不得返回 null、不得同步抛出</b>。
 * 选路没有副作用：不铸 epoch、不写位置记录、不碰归属。
 */
public interface TravelRouting {

    /**
     * 226 跨 zone 传送的选目标（a 段）：校验参数 → 目标地图预检 → 选 gate → 签票据。
     * 成功时应答带解析后的 {@code scene_config_id}（请求给 0 时是目标 zone 的默认主世界）与 {@code redirect}。
     */
    CompletableFuture<SelectTravelTargetResponse> selectTravelTarget(SelectTravelTargetRequest request);

    /**
     * 登录期重定向（GO-5，b 段）：角色此刻在别的 zone，请客户端连过去。只选 gate、签票据——不查地图、不查围栏；
     * 目标 zone 没有可用的 scene 节点即拒。
     */
    CompletableFuture<RedirectToZoneResponse> redirectToZone(RedirectToZoneRequest request);
}
