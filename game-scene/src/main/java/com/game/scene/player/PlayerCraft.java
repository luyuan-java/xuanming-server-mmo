package com.game.scene.player;

import com.game.player.store.state.PlayerCraftState;
import java.util.Objects;

/**
 * 玩家轻量化手艺熟练度、未学技能书架、永久解锁技能与主城宗师原子代打运行态（只在场景逻辑线程读写）：
 * 直接承载不可变 {@link PlayerCraftState}，通过 {@code toBuilder()} 更新以自动保留未知字段。
 */
public final class PlayerCraft {

    private PlayerCraftState state;

    private PlayerCraft(PlayerCraftState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public static PlayerCraft empty() {
        return new PlayerCraft(PlayerCraftState.getDefaultInstance());
    }

    public static PlayerCraft restore(PlayerCraftState state) {
        return new PlayerCraft(state == null ? PlayerCraftState.getDefaultInstance() : state);
    }

    public PlayerCraftState state() {
        return state;
    }

    public void replace(PlayerCraftState next) {
        this.state = Objects.requireNonNull(next, "next");
    }

    public int forgeMasteryExp() {
        return state.getForgeMasteryExp();
    }

    public int scrollMasteryExp() {
        return state.getScrollMasteryExp();
    }

    public long scrollCraftRevision() {
        return state.getScrollCraftRevision();
    }

    public boolean isSkillBookUnlocked(int skillTableId) {
        if (skillTableId == 0) {
            return false;
        }
        for (int id : state.getBookUnlockedSkillIdsList()) {
            if (id == skillTableId) {
                return true;
            }
        }
        return false;
    }

    public boolean isPristine() {
        return state.equals(PlayerCraftState.getDefaultInstance());
    }

    public PlayerCraftState toState() {
        return state;
    }
}
