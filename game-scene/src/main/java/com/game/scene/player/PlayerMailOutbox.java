package com.game.scene.player;

import com.game.player.store.state.MailOutboxEntryState;
import com.game.player.store.state.PlayerMailOutboxState;
import java.util.List;
import java.util.Objects;

/**
 * 玩家待寄个人邮件出箱运行态（仅在场景逻辑线程读写）：
 * 与货币/背包同属 {@code PlayerState} 单事务围栏提交，确保跨节点/离线名匠代打 95% 净银两分成
 * 落盘确认后再经 {@code game-mail} 幂等投递，进程崩溃不丢钱也不重发。
 */
public final class PlayerMailOutbox {

    public static final int MAX_ENTRIES = 64;
    public static final int SOURCE_BATTLE_OVERFLOW = 1;
    public static final int SOURCE_CRAFT_PROXY_FEE = 2;

    private PlayerMailOutboxState state;

    private PlayerMailOutbox(PlayerMailOutboxState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public static PlayerMailOutbox empty() {
        return new PlayerMailOutbox(PlayerMailOutboxState.getDefaultInstance());
    }

    public static PlayerMailOutbox restore(PlayerMailOutboxState state) {
        return new PlayerMailOutbox(state == null ? PlayerMailOutboxState.getDefaultInstance() : state);
    }

    public int size() {
        return state.getEntriesCount();
    }

    public boolean isEmpty() {
        return state.getEntriesCount() == 0;
    }

    public boolean isPristine() {
        return state.equals(PlayerMailOutboxState.getDefaultInstance());
    }

    public List<MailOutboxEntryState> entries() {
        return state.getEntriesList();
    }

    public MailOutboxEntryState head() {
        return state.getEntriesCount() == 0 ? null : state.getEntries(0);
    }

    public boolean contains(long requestId) {
        for (int i = 0; i < state.getEntriesCount(); i++) {
            if (state.getEntries(i).getRequestId() == requestId) {
                return true;
            }
        }
        return false;
    }

    public boolean append(MailOutboxEntryState entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.getRequestId() == 0L || state.getEntriesCount() >= MAX_ENTRIES || contains(entry.getRequestId())) {
            return false;
        }
        state = state.toBuilder().addEntries(entry).build();
        return true;
    }

    public boolean remove(long requestId) {
        for (int i = 0; i < state.getEntriesCount(); i++) {
            if (state.getEntries(i).getRequestId() == requestId) {
                state = state.toBuilder().removeEntries(i).build();
                return true;
            }
        }
        return false;
    }

    public PlayerMailOutboxState toState() {
        return state;
    }
}
