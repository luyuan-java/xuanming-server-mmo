package com.game.scene.world;

import java.time.Duration;
import java.util.Objects;

/** 逻辑线程所有的节点疏散状态。运维疏散无截止，停服和身份冲突只能升级并缩短预算。 */
public record NodeEvacuation(Cause cause, long sinceNanos, long deadlineNanos) {
    public enum Cause { ADMIN, SHUTDOWN, CONFLICT }

    public NodeEvacuation {
        Objects.requireNonNull(cause, "cause");
    }

    public static NodeEvacuation begin(Cause cause, long now, Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("节点疏散预算须大于0且不超过1分钟");
        }
        return new NodeEvacuation(cause, now, cause == Cause.ADMIN ? 0 : now + budget.toNanos());
    }

    public NodeEvacuation upgrade(Cause next, long now, Duration budget) {
        NodeEvacuation proposed = begin(next, now, budget);
        if (next == Cause.ADMIN || cause == Cause.CONFLICT && next == Cause.SHUTDOWN) return this;
        long deadline = cause == Cause.ADMIN || proposed.deadlineNanos() - deadlineNanos < 0
                ? proposed.deadlineNanos() : deadlineNanos;
        return new NodeEvacuation(next, sinceNanos, deadline);
    }

    public boolean expired(long now) { return cause != Cause.ADMIN && now - deadlineNanos >= 0; }

    /** 自疏散时刻（停服/冲突计划排空预算过半时触发剩余副本或未赶上频道迁移的住户自疏散）。 */
    public boolean halfBudgetReached(long now) {
        if (cause == Cause.ADMIN) return false;
        long total = deadlineNanos - sinceNanos;
        return total > 0 && (now - sinceNanos) >= (total / 2L);
    }
}
