package com.game.scene.player;

import com.game.player.store.state.PlayerTrialJournalState;
import java.util.Objects;

/**
 * 玩家五行试炼志 V2 运行态（只在场景逻辑线程读写）：直接承载不可变 {@link PlayerTrialJournalState}，
 * 通过 {@code toBuilder()} 更新以自动保留所有未知字段与高位未识别位图字节。
 */
public final class PlayerTrialJournal {

    private PlayerTrialJournalState state;

    private PlayerTrialJournal(PlayerTrialJournalState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public static PlayerTrialJournal empty() {
        return new PlayerTrialJournal(PlayerTrialJournalState.getDefaultInstance());
    }

    public static PlayerTrialJournal restore(PlayerTrialJournalState state) {
        return new PlayerTrialJournal(state == null ? PlayerTrialJournalState.getDefaultInstance() : state);
    }

    public PlayerTrialJournalState state() {
        return state;
    }

    public void replace(PlayerTrialJournalState next) {
        this.state = Objects.requireNonNull(next, "next");
    }

    /** true = 至少通关过任意一只普通难度首领试炼，解锁全部困难首领试炼准入。 */
    public boolean normalBossTrialCleared() {
        return state.getNormalBossTrialCleared();
    }

    /** 标记已通关任意普通首领试炼（幂等；仅首次变更返回 true）。 */
    public boolean markNormalBossTrialCleared() {
        if (state.getNormalBossTrialCleared()) {
            return false;
        }
        state = state.toBuilder().setNormalBossTrialCleared(true).build();
        return true;
    }

    public boolean isPristine() {
        return state.equals(PlayerTrialJournalState.getDefaultInstance());
    }

    public PlayerTrialJournalState toState() {
        return state;
    }
}
