package com.game.scene.world;

import java.util.function.Consumer;

/**
 * 镜像 / 副本实例取号（scene-manager {@code SceneDirectoryService.createInstance}，批次 5.3，dungeon-mirror-spec §6.5、§6.6、Q8）：
 * scene-manager 只校验参数、从全服租约发号、定放置（5.3 恒为发起节点），不记任何状态；节点拿到号后在本地建实例。
 *
 * <p>契约（同 {@link RemoteSwitchTargets}）：{@link #create} 不阻塞调用线程（任意线程可调：63 在逻辑线程上，dev 管理口在 HTTP 线程上）；
 * 结果回调<b>一定在场景逻辑线程上、不在 {@code create} 的调用栈内、恰好一次</b>（实现自带本地兜底超时，到时回 {@link Result.Failed}）。
 * 逻辑线程已停止时结果丢弃。不幂等：每次调用一个新号（调用方 {@code retries = 0}，同一玩家同时只有一个在途）。
 */
public interface InstanceIds {

    void create(Request request, Consumer<Result> onDone);

    /**
     * 一次取号请求（zone 与发起节点由实现填）。
     *
     * @param playerId        发起玩家；dev 管理口建副本时为 0
     * @param sourceSceneId   镜像的源（发起玩家当前所在的主世界频道）；副本为 0
     * @param sceneConfigId   实例的地图：镜像 = 源的配置号，副本 = Dungeon.scene_id
     */
    record Request(long playerId, SceneKind kind, long sourceSceneId, int sceneConfigId, int mirrorConfigId,
                   int dungeonConfigId) {
    }

    /** 取号的结果。 */
    sealed interface Result {

        /** 发了号：实例应建在 {@code sceneNodeId}（5.3 恒为发起节点）。 */
        record Issued(int sceneNodeId, long sceneId) implements Result {
        }

        /** scene-manager 业务拒绝（参数错 3005、发起节点不接新实例 3000）：tip 只进日志与指标，客户端看到 23 {3023}（D5）。 */
        record Refused(int tipId) implements Result {
        }

        /** 调用失败、本地兜底超时、发号租约无效或应答残缺：客户端看到 23 {1003}（Q12）。 */
        record Failed(String reason) implements Result {
        }
    }
}
