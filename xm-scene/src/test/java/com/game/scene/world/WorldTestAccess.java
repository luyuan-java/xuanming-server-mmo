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

    /** 让玩家处于 63 镜像分支的取号中（同样占 RESOLVING 槽、永不冻结；不经 scene-manager，槽永不过期）。 */
    public static void startMirrorResolving(ScenePlayer player) {
        player.setSwitching(PlayerSwitch.mirrorCreate(1, player.scene() == null ? 0 : player.scene().sceneId(), 1, Long.MAX_VALUE));
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

    /** 直接给玩家一个速度（不经移动上行与位移校验；测「停步」用）。 */
    public static void setVelocity(ScenePlayer player, Vec3 velocity) {
        player.setVelocity(velocity);
    }

    /** 速度脏位是否已置（下一个同步帧会给看得见它的人发带 velocity 的 66）。 */
    public static boolean velocityDirty(ScenePlayer player) {
        return (player.syncDirty() & ScenePlayer.DIRTY_VELOCITY) != 0;
    }

    /**
     * 直接调 5.2 的交出发起入口（绕过 63 的处理器；生产里只有 63 的远端分支调它）。用来钉住入口自己的闸：
     * 「{@code begin} 拒绝战斗中的玩家」（scene-handoff-spec :106，scene-battle-spec §7.13 世界内部第 1 条）。
     */
    public static void beginRemoteSwitch(SceneWorld world, ScenePlayer player, long wantSceneId, int wantConfigId) {
        world.beginRemoteSwitch(player, wantSceneId, wantConfigId);
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

    /**
     * 身份字段（职业 / 性别 / 外观 / 等级 / 角色名）与技能表都由调用方给的玩家实例（不在任何场景里；进场规整由调用方自己跑）。
     * 组战斗快照这类纯函数测试用：经世界进场的玩家技能恒为配表的初始技能、等级被压回合法范围，造不出边界输入。
     */
    public static ScenePlayer player(long playerId, int classId, int gender, String appearanceId, int level,
                                     java.util.List<Integer> skills, String name,
                                     com.game.player.store.state.PlayerState state) {
        ScenePlayer player = new ScenePlayer(playerId, 10_000 + playerId, new SessionKey(1, (int) playerId), 1, classId, gender,
                appearanceId, level, skills, Vec3.ORIGIN, state, 0L);
        player.setName(name);
        return player;
    }
}
