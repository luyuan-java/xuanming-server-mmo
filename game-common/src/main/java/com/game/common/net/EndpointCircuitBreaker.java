package com.game.common.net;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 并发安全的按后端/实例隔离的三态熔断器（Closed / Open / HalfOpen，对齐 Go {@code shared/circuitbreaker}）。
 *
 * <p>语义：连续 {@code failureThreshold} 次基础设施故障打开 {@code openDuration}；到期后转 {@code HalfOpen}
 * 仅允许单条探测请求进入，探测成功立即关闭并清零计数，失败重新打开，调用方取消可 {@link #releaseProbe} 归还探测锁而不计失败。
 * 业务拒绝与客户端限频（1008）不应调用 {@link #recordFailure}。
 */
public final class EndpointCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    public static final int DEFAULT_FAILURE_THRESHOLD = 5;
    public static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(10);

    private static final EndpointCircuitBreaker DISABLED =
            new EndpointCircuitBreaker(false, DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION, System::nanoTime);

    private final boolean enabled;
    private final int failureThreshold;
    private final long openDurationNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public EndpointCircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoClock) {
        this(true, failureThreshold, openDuration, nanoClock);
    }

    private EndpointCircuitBreaker(boolean enabled, int failureThreshold, Duration openDuration, LongSupplier nanoClock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold 必须为正: " + failureThreshold);
        }
        Objects.requireNonNull(openDuration, "openDuration");
        if (openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration 必须为正: " + openDuration);
        }
        this.enabled = enabled;
        this.failureThreshold = failureThreshold;
        this.openDurationNanos = openDuration.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public static EndpointCircuitBreaker disabled() {
        return DISABLED;
    }

    public static EndpointCircuitBreaker defaults() {
        return new EndpointCircuitBreaker(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION, System::nanoTime);
    }

    public boolean enabled() {
        return enabled;
    }

    /** 请求前准入：允许发出返回 {@code true}；已熔断或已有探测在途返回 {@code false}。 */
    public boolean tryAcquire(String endpoint) {
        if (!enabled || endpoint == null || endpoint.isEmpty()) {
            return true;
        }
        Entry entry = entries.computeIfAbsent(endpoint, k -> new Entry());
        long now = nanoClock.getAsLong();
        synchronized (entry) {
            if (entry.state == State.CLOSED) {
                return true;
            }
            if (entry.state == State.OPEN) {
                if (now - entry.openedAtNanos >= openDurationNanos) {
                    entry.state = State.HALF_OPEN;
                    entry.probeInFlight = true;
                    return true;
                }
                return false;
            }
            if (!entry.probeInFlight) {
                entry.probeInFlight = true;
                return true;
            }
            return false;
        }
    }

    /** 只读快照：选点过滤健康实例用，不改变状态。 */
    public boolean isOpen(String endpoint) {
        if (!enabled || endpoint == null || endpoint.isEmpty()) {
            return false;
        }
        Entry entry = entries.get(endpoint);
        if (entry == null) {
            return false;
        }
        long now = nanoClock.getAsLong();
        synchronized (entry) {
            return entry.state == State.OPEN && (now - entry.openedAtNanos < openDurationNanos);
        }
    }

    public State stateOf(String endpoint) {
        if (!enabled || endpoint == null || endpoint.isEmpty()) {
            return State.CLOSED;
        }
        Entry entry = entries.get(endpoint);
        if (entry == null) {
            return State.CLOSED;
        }
        long now = nanoClock.getAsLong();
        synchronized (entry) {
            if (entry.state == State.OPEN && (now - entry.openedAtNanos >= openDurationNanos)) {
                return State.HALF_OPEN;
            }
            return entry.state;
        }
    }

    /** 调用收到有效响应（含业务拒绝或 1008）：归零失败计数并关闭熔断器。 */
    public void recordSuccess(String endpoint) {
        if (!enabled || endpoint == null || endpoint.isEmpty()) {
            return;
        }
        Entry entry = entries.get(endpoint);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.consecutiveFailures = 0;
            entry.state = State.CLOSED;
            entry.probeInFlight = false;
        }
    }

    /** 基础设施/超时/不可用故障：累计达到阈值或探测失败立即转 {@code OPEN}。 */
    public void recordFailure(String endpoint) {
        if (!enabled || endpoint == null || endpoint.isEmpty()) {
            return;
        }
        Entry entry = entries.computeIfAbsent(endpoint, k -> new Entry());
        long now = nanoClock.getAsLong();
        synchronized (entry) {
            if (entry.state == State.HALF_OPEN) {
                entry.state = State.OPEN;
                entry.openedAtNanos = now;
                entry.probeInFlight = false;
                return;
            }
            if (entry.state == State.OPEN) {
                entry.openedAtNanos = now;
                entry.probeInFlight = false;
                return;
            }
            if (++entry.consecutiveFailures >= failureThreshold) {
                entry.state = State.OPEN;
                entry.openedAtNanos = now;
                entry.probeInFlight = false;
            }
        }
    }

    /** 调用方主动取消时释放 {@code HalfOpen} 探测锁，不计入失败。 */
    public void releaseProbe(String endpoint) {
        if (!enabled || endpoint == null || endpoint.isEmpty()) {
            return;
        }
        Entry entry = entries.get(endpoint);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (entry.state == State.HALF_OPEN) {
                entry.probeInFlight = false;
            }
        }
    }

    /** 实例下线时清理状态，避免长跑内存增长。 */
    public void evict(String endpoint) {
        if (endpoint != null) {
            entries.remove(endpoint);
        }
    }

    public int trackedEndpoints() {
        return entries.size();
    }

    private static final class Entry {
        State state = State.CLOSED;
        int consecutiveFailures;
        long openedAtNanos;
        boolean probeInFlight;
    }
}
