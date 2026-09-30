package com.game.scene.world;

/**
 * 离场时写回的玩家状态（不可变）。写回以 {@code ownerEpoch} 为围栏：只有持有最新 epoch 的写者能落库。
 */
public record PlayerSave(long playerId, long ownerEpoch, int level, int sceneConfigId, Vec3 position) {
}
