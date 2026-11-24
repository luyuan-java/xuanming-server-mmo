package com.game.scene.world;

import com.game.player.store.state.PlayerState;

/**
 * 写回的玩家状态快照（不可变，值相等：周期存盘据此做脏比对）。写回以 {@code ownerEpoch} 为围栏：
 * 只有持有最新 epoch 的写者能落库。
 *
 * @param state 各玩法的持久化数据（{@code player_state}）
 */
public record PlayerSave(long playerId, long ownerEpoch, int level, int sceneConfigId, Vec3 position,
                         PlayerState state, String appearanceId, long afkPassExpiresAt, long afkPassLastReqId) {

    public PlayerSave {
        state = state == null ? PlayerState.getDefaultInstance() : state;
        appearanceId = appearanceId == null ? "" : appearanceId;
    }

    /** 未改挂机月卡（旧调用方与测试）。 */
    public PlayerSave(long playerId, long ownerEpoch, int level, int sceneConfigId, Vec3 position,
                      PlayerState state, String appearanceId) {
        this(playerId, ownerEpoch, level, sceneConfigId, position, state, appearanceId, 0L, 0L);
    }

    /** 未改永久外观（旧调用方与测试）。 */
    public PlayerSave(long playerId, long ownerEpoch, int level, int sceneConfigId, Vec3 position, PlayerState state) {
        this(playerId, ownerEpoch, level, sceneConfigId, position, state, "", 0L, 0L);
    }

    /** 没有玩法数据（全部取初始状态）。 */
    public PlayerSave(long playerId, long ownerEpoch, int level, int sceneConfigId, Vec3 position) {
        this(playerId, ownerEpoch, level, sceneConfigId, position, null, "", 0L, 0L);
    }
}
