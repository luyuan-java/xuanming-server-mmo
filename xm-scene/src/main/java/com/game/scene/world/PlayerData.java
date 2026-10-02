package com.game.scene.world;

import com.game.player.store.state.PlayerState;

/**
 * 进场时从存储加载的玩家数据（不可变，可跨线程传递）。
 *
 * @param sceneConfigId 上次所在场景配置；0 表示从未进过场景
 * @param position      上次坐标（只在 {@code sceneConfigId} 与目标场景配置相同时沿用）
 * @param state         各玩法系统的持久化组件；从未写过为默认实例
 */
public record PlayerData(
        long playerId,
        long ownerEpoch,
        int classId,
        int gender,
        String appearanceId,
        int level,
        int sceneConfigId,
        Vec3 position,
        PlayerState state) {

    public PlayerData {
        appearanceId = appearanceId == null ? "" : appearanceId;
        position = position == null ? Vec3.ORIGIN : position;
        state = state == null ? PlayerState.getDefaultInstance() : state;
    }

    /** 没有状态组件（新号 / 测试）。 */
    public PlayerData(long playerId, long ownerEpoch, int classId, int gender, String appearanceId, int level,
                      int sceneConfigId, Vec3 position) {
        this(playerId, ownerEpoch, classId, gender, appearanceId, level, sceneConfigId, position, null);
    }

    /** 库里此刻的样子（周期存盘的脏比对基准）。 */
    PlayerSave asPersisted() {
        return new PlayerSave(playerId, ownerEpoch, level, sceneConfigId, position, state);
    }
}
