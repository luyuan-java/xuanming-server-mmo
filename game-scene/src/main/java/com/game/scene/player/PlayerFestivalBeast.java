package com.game.scene.player;

import com.game.player.store.state.PlayerFestivalBeastState;
import java.util.Objects;

/**
 * 玩家瑞兽迎春限时活动运行态（只在场景逻辑线程读写）：直接承载不可变 {@link PlayerFestivalBeastState}，
 * 通过 {@code toBuilder()} 更新以自动保留未知字段。
 */
public final class PlayerFestivalBeast {

    private PlayerFestivalBeastState state;

    private PlayerFestivalBeast(PlayerFestivalBeastState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public static PlayerFestivalBeast empty() {
        return new PlayerFestivalBeast(PlayerFestivalBeastState.getDefaultInstance());
    }

    public static PlayerFestivalBeast restore(PlayerFestivalBeastState state) {
        return new PlayerFestivalBeast(state == null ? PlayerFestivalBeastState.getDefaultInstance() : state);
    }

    public PlayerFestivalBeastState state() {
        return state;
    }

    public void replace(PlayerFestivalBeastState next) {
        this.state = Objects.requireNonNull(next, "next");
    }

    public boolean isPristine() {
        return state.equals(PlayerFestivalBeastState.getDefaultInstance());
    }

    public PlayerFestivalBeastState toState() {
        return state;
    }
}
