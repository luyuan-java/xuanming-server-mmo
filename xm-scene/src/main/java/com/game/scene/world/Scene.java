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
 *
 * <p><b>频道状态</b>（批次 5.1，scene-channels-spec §4.10.2）：承载中 / 排空中，由 {@link SceneWorld#applyChannelPlan} 按频道计划切换。
 * 排空中的场景不接受显式进入（63 指定它回 3023，D11）、不被组队跟随跟进（§4.13）、进场加载完成时改进兄弟频道（§4.10.4），
 * 在场玩家由 {@link SceneWorld#drainStep} 同节点改派，空了即销毁（对应基线 C++ DestroyScene 的「先排空再销毁」，
 * cpp/nodes/scene/handler/grpc/scene_node_service.cpp:68-125）。
 */
public final class Scene {

    private final long sceneId;
    private final int configId;
    /**
     * 进场时整体拷给客户端（79 的 scene_info）。主世界频道 mirror / dungeon 为 0、creators 为空；构造收完整的 {@link SceneInfoComp}
     * （同基线 HandleCreateScene 挂的 SceneInfoComp，scene_node_service.cpp:39-52），5.3 的副本 / 镜像从这里填。
     */
    private final SceneInfoComp info;
    private final Map<Long, ScenePlayer> players = new LinkedHashMap<>();
    /** 只读视图建一次复用：帧内每帧要遍历两遍（外推、属性同步），不为每次遍历新建包装对象。 */
    private final Collection<ScenePlayer> playersView = Collections.unmodifiableCollection(players.values());
    private final ViewIndex view = new ViewIndex();
    private boolean draining;
    /** 排空推进找不到改派目标时只告警一次（恢复改派或改回承载中时清掉）。 */
    private boolean relocationBlockedWarned;

    /** @param info scene_id 与 scene_config_id 都非 0（调用方校验） */
    Scene(SceneInfoComp info) {
        this.sceneId = info.getSceneId();
        this.configId = info.getSceneConfigId();
        this.info = info;
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

    /** 是否在排空中（逻辑线程）。 */
    public boolean draining() {
        return draining;
    }

    void setDraining(boolean draining) {
        this.draining = draining;
        if (!draining) {
            relocationBlockedWarned = false;
        }
    }

    /** 第一次找不到改派目标时为 true（之后同一段阻塞期内为 false），用来只告警一次。 */
    boolean firstRelocationBlocked() {
        if (relocationBlockedWarned) {
            return false;
        }
        relocationBlockedWarned = true;
        return true;
    }

    void relocationUnblocked() {
        relocationBlockedWarned = false;
    }

    /** 本场景的玩家（只读、按进场顺序）。遍历期间不得进出场景（外推与同步都不会）。 */
    Collection<ScenePlayer> players() {
        return playersView;
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

    /**
     * 本场景内玩家的位置变化（移动输入、外推）：更新坐标与格子，之后的视野刷新重新判定它。
     * 非有限坐标是上游校验的漏洞（移动输入在裁决前已丢弃）：抛 {@link IllegalArgumentException}，位置保持原样——
     * 先检查再写，NaN / ±Inf 不会进 Transform、66、137 与写回。
     */
    void relocate(ScenePlayer player, Vec3 position) {
        if (!position.isFinite()) {
            throw new IllegalArgumentException("位置必须有限 player=" + player.playerId() + " position=" + position);
        }
        player.setPosition(position);
        view.moved(player);
    }

    /** 本帧的视野刷新：重判位置变过的玩家（节奏见 {@link ViewIndex}），变化记进 {@code out}。 */
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
