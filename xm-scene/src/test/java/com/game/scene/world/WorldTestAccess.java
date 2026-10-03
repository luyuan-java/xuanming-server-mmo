package com.game.scene.world;

/** 测试用：让 world 包外的测试拿到场景里的玩家实例与它的持久化快照（这些入口在生产代码里是包内可见）。 */
public final class WorldTestAccess {

    private WorldTestAccess() {
    }

    public static ScenePlayer player(SceneWorld world, long linkId, int sessionId) {
        return world.playerBySession(new SessionKey(linkId, sessionId));
    }

    public static com.game.player.store.state.PlayerState persistentState(ScenePlayer player) {
        return player.persistentState();
    }
}
