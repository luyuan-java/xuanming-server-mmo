package com.game.scene.world;

import com.game.proto.SceneInfoComp;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个场景实例（主世界的一条频道）。只在场景逻辑线程上读写。
 *
 * <p>玩家按进场顺序保存（帧内外推与属性同步按这个顺序遍历，结果可复现）；视野由本场景的 {@link ViewIndex} 维护。
 * 玩家的进出与位置变化只能经本类（{@link #add} / {@link #remove} / {@link #relocate}），格子与兴趣列表才不会与位置脱节。
 */
public final class Scene {

    private final long sceneId;
    private final int configId;
    /** 进场时整体拷给客户端（79 的 scene_info）。主世界频道 mirror / dungeon 为 0、creators 为空。 */
    private final SceneInfoComp info;
    private final Map<Long, ScenePlayer> players = new LinkedHashMap<>();
    private final ViewIndex view = new ViewIndex();

    Scene(long sceneId, int configId) {
        this.sceneId = sceneId;
        this.configId = configId;
        this.info = SceneInfoComp.newBuilder()
                .setSceneConfigId(configId)
                .setSceneId(sceneId)
                .build();
    }

    public long sceneId() {
        return sceneId;
    }

    public int configId() {
        return configId;
    }

    public SceneInfoComp info() {
        return info;
    }

    public int playerCount() {
        return players.size();
    }

    Collection<ScenePlayer> players() {
        return Collections.unmodifiableCollection(players.values());
    }

    /** 玩家进入本场景（位置已定）：登记并按当前位置建立视野。返回双方各自新看见了谁。 */
    ViewIndex.Entered add(ScenePlayer player) {
        players.put(player.playerId(), player);
        return view.enter(player);
    }

    /** 玩家离开本场景：从视野里彻底清掉。返回离开前看得见它的玩家（51 的收件人）。不在本场景返回空列表。 */
    List<ScenePlayer> remove(ScenePlayer player) {
        if (!players.remove(player.playerId(), player)) {
            return List.of();
        }
        return view.leave(player);
    }

    /** 本场景内玩家的位置变化（移动输入、外推）：更新坐标与格子，下一帧的视野刷新重新判定它。 */
    void relocate(ScenePlayer player, Vec3 position) {
        player.setPosition(position);
        view.moved(player);
    }

    /** 本帧的视野刷新：处理上次刷新以来移动过的玩家，变化记进 {@code out}。 */
    void refreshViews(ViewChanges out) {
        view.refresh(out);
    }

    /** 看得见 {@code player} 的玩家（只读）。 */
    Set<ScenePlayer> watchers(ScenePlayer player) {
        return view.watchers(player);
    }

    /** {@code player} 看得见的玩家（只读）。 */
    Set<ScenePlayer> watching(ScenePlayer player) {
        return view.watching(player);
    }

    void clear() {
        players.clear();
        view.clear();
    }
}
