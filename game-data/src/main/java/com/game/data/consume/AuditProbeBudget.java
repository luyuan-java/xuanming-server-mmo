package com.game.data.consume;

import static com.game.data.consume.AuditProbeFailure.Reason.BUDGET;
import static com.game.data.consume.AuditProbeFailure.Reason.INTERRUPTED;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 同一轮所有管理请求共享单调时钟预算；不能每个请求重新获得完整 timeout。 */
public final class AuditProbeBudget {
    private final LongSupplier nanos;
    private final long started;
    private final long limit;

    public AuditProbeBudget(Duration timeout, LongSupplier nanos) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("探针预算须在 (0,30s] 内");
        }
        this.nanos = nanos;
        started = nanos.getAsLong();
        limit = timeout.toNanos();
    }

    public long startedNanos() { return started; }

    public long remainingNanos() {
        if (Thread.currentThread().isInterrupted()) throw new AuditProbeFailure(INTERRUPTED);
        long elapsed = nanos.getAsLong() - started;
        if (elapsed < 0 || elapsed >= limit) throw new AuditProbeFailure(BUDGET);
        return limit - elapsed;
    }

    public int timeoutMillis() {
        long left = remainingNanos();
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(left - 1) + 1);
    }
}
