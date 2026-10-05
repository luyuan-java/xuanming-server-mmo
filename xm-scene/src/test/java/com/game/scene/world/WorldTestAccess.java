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

    /** 让玩家处于跨节点换图的选目标中（RESOLVING，不经 scene-manager；槽永不过期）。 */
    public static void startResolving(ScenePlayer player) {
        player.setSwitching(new PlayerSwitch(1, 0, 0, Long.MAX_VALUE));
    }

    /**
     * 让玩家处于冻结中（FREEZING，不经 scene-manager、不提交交出；{@code player.frozen()} 为 true）。不在场景里的实例没有快照。
     * 给 world 包外的冻结闸单测用；要走完整交出流程的用 world 包里的 TransferFixture。
     */
    public static void startFreezing(ScenePlayer player) {
        PlayerSwitch sw = new PlayerSwitch(1, 0, 0, Long.MAX_VALUE);
        sw.freeze(4, 900_001, 1, player.scene() == null ? null : player.toSave(), 0);
        player.setSwitching(sw);
    }

    /** 摘掉换图状态（回到 NONE）。 */
    public static void clearSwitch(ScenePlayer player) {
        player.setSwitching(null);
    }

    /** 一个不在任何场景里的新玩家实例（全新钱包 / 窗口）。 */
    public static ScenePlayer player(long playerId) {
        return new ScenePlayer(playerId, 10_000 + playerId, new SessionKey(1, (int) playerId), 1, 1, 0, "", 1,
                java.util.List.of(), Vec3.ORIGIN, 0L);
    }

    /** 从存档恢复的玩家实例（不在任何场景里；进场规整由调用方自己跑）。 */
    public static ScenePlayer player(long playerId, int level, com.game.player.store.state.PlayerState state) {
        return new ScenePlayer(playerId, 10_000 + playerId, new SessionKey(1, (int) playerId), 1, 1, 0, "", level,
                java.util.List.of(), Vec3.ORIGIN, state, 0L);
    }
}
