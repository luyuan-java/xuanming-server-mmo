package com.game.scene.world;

import java.time.Duration;
import java.util.Objects;

/** 节点改派的有界并发与重试政策；不包含外部 I/O 或线程调度。 */
public record EvacuationPolicy(int maxInFlight, Duration retryInitial, Duration retryMaximum) {
    public static final EvacuationPolicy DEFAULT = new EvacuationPolicy(32, Duration.ofSeconds(1), Duration.ofSeconds(30));

    public EvacuationPolicy {
        Objects.requireNonNull(retryInitial, "retryInitial");
        Objects.requireNonNull(retryMaximum, "retryMaximum");
        if (maxInFlight < 1 || maxInFlight > 256 || retryInitial.compareTo(Duration.ofMillis(100)) < 0
                || retryMaximum.compareTo(retryInitial) < 0 || retryMaximum.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("疏散并发须在1–256，退避须在100ms–1min且上限不小于初值");
        }
    }

    long retryNanos(int failures) {
        long delay = retryInitial.toNanos();
        long maximum = retryMaximum.toNanos();
        for (int i = 1; i < failures && delay < maximum; i++) delay = Math.min(maximum, delay * 2);
        return delay;
    }
}
