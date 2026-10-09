package com.game.scene.world;

import com.game.api.proto.ZoneRedirect;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 跨 zone 传送（226）的选目标（scene-manager {@code SceneDirectoryService.selectTravelTarget}，批次 5.4，zone-travel-spec §5.4）：
 * 在目标 zone 选一台 gate、签一张重定向票据。只做选择：不铸 epoch、不写位置记录、不碰归属。
 *
 * <p>契约同 {@link RemoteSwitchTargets}：{@link #selectTravel} 不阻塞调用线程（场景逻辑线程上调用）；结果回调<b>一定在场景逻辑线程上、
 * 不在 {@code selectTravel} 的调用栈内、恰好一次</b>（实现自带本地兜底超时，到时回 {@link TravelSelection.Failed}）。逻辑线程已停止时结果丢弃。
 */
public interface TravelTargets {

    /**
     * @param playerId          玩家（票据的持票者）
     * @param toZoneId          目标 zone（非 0、不等于本 zone，调用方已校验）
     * @param wantSceneConfigId 226 指定的地图（0 = 目标 zone 的默认主世界）
     */
    void selectTravel(long playerId, int toZoneId, int wantSceneConfigId, Consumer<TravelSelection> onDone);

    /** 选目标的结果。三种结果对客户端的表现都由调用方定（失败与拒绝一律 23 {3027}，zone-travel-spec §5.5）。 */
    sealed interface TravelSelection {

        /**
         * 选中了。
         *
         * @param sceneConfigId 解析后的目标地图（非 0：请求带 0 时是目标 zone 的默认主世界）；写进待落点记录
         * @param redirect      目标 gate 的通告地址与票据，原样进 {@code PlayerTransfer.redirect}（{@code token_payload} 不得重新序列化）
         */
        record Chosen(int sceneConfigId, ZoneRedirect redirect) implements TravelSelection {

            public Chosen {
                Objects.requireNonNull(redirect, "redirect");
            }
        }

        /** scene-manager 业务拒绝（目标 zone 没有这张图、没有可用的 gate、参数不合法…）。tip 只进日志与指标。 */
        record Refused(int tipId) implements TravelSelection {
        }

        /**
         * 调用失败、本地兜底超时，或应答残缺（地图为 0、gate 地址空、端口不在 1..65535、票据或签名为空、过期时刻 ≤ 0）。
         * 结局未知，但选目标没有副作用。
         */
        record Failed(String reason) implements TravelSelection {
        }
    }
}
