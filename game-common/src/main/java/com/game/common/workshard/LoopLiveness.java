package com.game.common.workshard;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台定时循环活性跟踪器（对齐主仓 {@code go/shared/looplive}）：
 * <ul>
 *   <li>启动时记录一次当前时刻；每轮正常返回后更新 {@code lastCompletedEpochSeconds}（单轮业务错误照常计入跑完，未捕获异常/Error 不计入）；</li>
 *   <li>{@link #runGuarded(Runnable)} 每轮独立兜住 {@link Throwable}，防止 {@code ScheduledExecutorService} 因未捕获异常静默停摆，同时递增 {@code panicCount}；</li>
 *   <li>仅在有意关闭（{@link #close()}）时触发移除回调，绝不在异常退栈时删除活性序列（保留循环死锁/停摆证据供告警发现）。</li>
 * </ul>
 */
public final class LoopLiveness implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LoopLiveness.class);

    /** 默认停摆告警阈值（秒）：覆盖最慢 1 分钟一拍 + 30 秒预算的 5 倍余量。 */
    public static final long DEFAULT_STALL_THRESHOLD_SECONDS = 300L;

    private final String service;
    private final String loop;
    private final long stallThresholdSeconds;
    private final LongSupplier clockMs;
    private final AtomicLong lastCompletedEpochSeconds = new AtomicLong();
    private final AtomicLong panicCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Runnable onClose;
    private volatile Runnable onPanic;

    public LoopLiveness(String service, String loop) {
        this(service, loop, DEFAULT_STALL_THRESHOLD_SECONDS, System::currentTimeMillis);
    }

    public LoopLiveness(String service, String loop, long stallThresholdSeconds, LongSupplier clockMs) {
        this.service = Objects.requireNonNull(service, "service");
        this.loop = Objects.requireNonNull(loop, "loop");
        if (stallThresholdSeconds <= 0) {
            throw new IllegalArgumentException("stallThresholdSeconds must be positive: " + stallThresholdSeconds);
        }
        this.stallThresholdSeconds = stallThresholdSeconds;
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.lastCompletedEpochSeconds.set(clockMs.getAsLong() / 1000L);
    }

    public String service() {
        return service;
    }

    public String loop() {
        return loop;
    }

    public long stallThresholdSeconds() {
        return stallThresholdSeconds;
    }

    public long lastCompletedEpochSeconds() {
        return lastCompletedEpochSeconds.get();
    }

    public long panicCount() {
        return panicCount.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 绑定 Micrometer 计数器与有意关闭摘除回调（由 {@code LoopLivenessMeters} 调用）。 */
    public void bindCallbacks(Runnable onPanic, Runnable onClose) {
        this.onPanic = onPanic;
        this.onClose = onClose;
    }

    /** 记录一次正常轮次完成。 */
    public void markCompleted() {
        if (!closed.get()) {
            lastCompletedEpochSeconds.set(clockMs.getAsLong() / 1000L);
        }
    }

    /**
     * 兜底执行一轮后台任务：正常返回时刷新活性时间；抛出未捕获 {@link Throwable} 时不刷新活性、计数并打 ERROR 日志，
     * 返回 {@code false} 让调度器继续下一拍而非静默终止。
     */
    public boolean runGuarded(Runnable round) {
        if (closed.get()) {
            return false;
        }
        try {
            round.run();
            markCompleted();
            return true;
        } catch (Throwable panic) {
            panicCount.incrementAndGet();
            Runnable cb = this.onPanic;
            if (cb != null) {
                try {
                    cb.run();
                } catch (Throwable ignored) {
                }
            }
            log.error("[LoopLiveness] background loop panic recovered service={} loop={}", service, loop, panic);
            return false;
        }
    }

    /** 有意退出时调用：摘除活性序列，避免正常下线误报停摆。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Runnable cb = this.onClose;
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
