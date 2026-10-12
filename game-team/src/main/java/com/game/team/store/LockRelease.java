package com.game.team.store;

/**
 * 按 token 清开战锁的<b>一轮</b>（读 → 判定 → 钉版本提交）的结果（基线 store.go:587-618 的 {@code (stop, pinned, err)}）。
 *
 * @param stop   非 null = 应当停止且没有写：记录不存在 / token 不符 / 锁已过期（只会是 {@link EndMatchStop#RECORD_MISSING}、
 *               {@link EndMatchStop#TOKEN_MISMATCH}、{@link EndMatchStop#LOCK_EXPIRED} 之一）；此时 {@code pinned} 为 null
 * @param pinned {@code stop == null} 时的钉版本提交结果：{@code commit != null} 已清锁；否则没清（冲突，或刚做过修复）
 */
public record LockRelease(EndMatchStop stop, PinnedResult pinned) {

    static LockRelease stopped(EndMatchStop stop) {
        return new LockRelease(stop, null);
    }

    static LockRelease attempted(PinnedResult pinned) {
        return new LockRelease(null, pinned);
    }
}
