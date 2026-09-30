package com.game.scene.world;

import com.game.proto.SceneInfoComp;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个场景实例（主世界的一条频道）。只在场景逻辑线程上读写。
 *
 * <p>玩家按进场顺序保存，AOI 名单（47 的 actor_list）因此有确定的顺序。
 */
public final class Scene {

    private final long sceneId;
    private final int configId;
    /** 进场时整体拷给客户端（79 的 scene_info）。主世界频道 mirror / dungeon 为 0、creators 为空。 */
    private final SceneInfoComp info;
    private final Map<Long, ScenePlayer> players = new LinkedHashMap<>();

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

    void add(ScenePlayer player) {
        players.put(player.playerId(), player);
    }

    void remove(ScenePlayer player) {
        players.remove(player.playerId(), player);
    }

    void clear() {
        players.clear();
    }
}
