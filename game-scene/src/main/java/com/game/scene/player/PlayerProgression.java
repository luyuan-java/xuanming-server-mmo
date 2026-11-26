package com.game.scene.player;

import com.game.player.store.state.PlayerProgressionState;
import com.google.protobuf.UnknownFieldSet;

/**
 * 玩家累计总经验持久化状态（逻辑线程上读写；与 {@code player.level} 列同一次玩家存档围栏提交）。
 * 保留未知字段，确保滚动升级 / 回滚往返无损。
 */
public final class PlayerProgression {

    private long totalExp;
    private UnknownFieldSet unknownFields = UnknownFieldSet.getDefaultInstance();

    private PlayerProgression() {
    }

    public static PlayerProgression empty() {
        return new PlayerProgression();
    }

    public static PlayerProgression restore(PlayerProgressionState state) {
        PlayerProgression out = new PlayerProgression();
        if (state == null) {
            return out;
        }
        out.totalExp = state.getTotalExp();
        out.unknownFields = state.getUnknownFields();
        return out;
    }

    public long totalExp() {
        return totalExp;
    }

    public void setTotalExp(long totalExp) {
        this.totalExp = totalExp;
    }

    public boolean isPristine() {
        return totalExp == 0L && unknownFields.asMap().isEmpty();
    }

    public PlayerProgressionState toState() {
        return PlayerProgressionState.newBuilder()
                .setUnknownFields(unknownFields)
                .setTotalExp(totalExp)
                .build();
    }
}
