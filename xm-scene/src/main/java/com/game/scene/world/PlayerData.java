package com.game.scene.world;

/**
 * 进场时从存储加载的玩家数据（不可变，可跨线程传递）。
 *
 * @param sceneConfigId 上次所在场景配置；0 表示从未进过场景
 * @param position      上次坐标（只在 {@code sceneConfigId} 与目标场景配置相同时沿用）
 */
public record PlayerData(
        long playerId,
        long ownerEpoch,
        int classId,
        int gender,
        String appearanceId,
        int level,
        int sceneConfigId,
        Vec3 position) {

    public PlayerData {
        appearanceId = appearanceId == null ? "" : appearanceId;
        position = position == null ? Vec3.ORIGIN : position;
    }
}
