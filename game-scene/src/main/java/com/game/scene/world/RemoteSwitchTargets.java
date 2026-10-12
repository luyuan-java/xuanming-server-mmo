package com.game.scene.world;

import java.util.function.Consumer;

/**
 * 跨节点换图的选目标（scene-manager {@code SceneDirectoryService.selectSwitchTarget}，scene-handoff-spec §5.4、Q7）。
 * 只做选择：不铸 epoch、不写位置记录、不碰归属。
 *
 * <p>契约（同 {@link PlayerRepository} 的回调纪律）：{@link #select} 不阻塞调用线程（场景逻辑线程上调用）；结果回调<b>一定在场景逻辑线程上、
 * 不在 {@code select} 的调用栈内、恰好一次</b>（实现自带本地兜底超时，到时回 {@link Selection.Failed}）。逻辑线程已停止时结果丢弃。
 */
public interface RemoteSwitchTargets {

    /**
     * @param playerId          玩家
     * @param fromSceneId       玩家此刻所在的场景（只带地图时 scene-manager 不再选它）
     * @param wantSceneId       63 指定的场景号（0 = 只带地图）
     * @param wantSceneConfigId 63 带的地图（0 = 没带；与 wantSceneId 同时给时 scene-manager 校验二者一致）
     */
    void select(long playerId, long fromSceneId, long wantSceneId, int wantSceneConfigId, Consumer<Selection> onDone);

    /** 选目标的结果。 */
    sealed interface Selection {

        /** 选中了：节点号、场景号、场景的地图。节点可能就是本节点（目录过时，或选中本节点另一个频道）。 */
        record Chosen(int sceneNodeId, long sceneId, int sceneConfigId) implements Selection {
        }

        /** scene-manager 业务拒绝（tip 只进日志与指标；客户端一律看到 23 {3023}，同基线把非 0 非 18 映射成 3023）。 */
        record Refused(int tipId) implements Selection {
        }

        /** 调用失败或本地兜底超时（结局未知，但选目标没有副作用）：客户端看到 23 {1003}。 */
        record Failed(String reason) implements Selection {
        }
    }
}
