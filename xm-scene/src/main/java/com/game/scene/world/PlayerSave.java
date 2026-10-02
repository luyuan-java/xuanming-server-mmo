package com.game.scene.world;

import com.game.player.store.state.PlayerState;

/**
 * 写回的玩家状态快照（不可变，值相等：周期存盘据此做脏比对）。写回以 {@code ownerEpoch} 为围栏：
 * 只有持有最新 epoch 的写者能落库。
 *
 * @param state 各玩法系统的持久化组件（{@code player_state}）
 */
public record PlayerSave(long playerId, long ownerEpoch, int level, int sceneConfigId, Vec3 position, PlayerState state) {

    public PlayerSave {
        state = state == null ? PlayerState.getDefaultInstance() : state;
    }

    /** 没有状态组件（全部取初始状态）。 */
    public PlayerSave(long playerId, long ownerEpoch, int level, int sceneConfigId, Vec3 position) {
        this(playerId, ownerEpoch, level, sceneConfigId, position, null);
    }
}
