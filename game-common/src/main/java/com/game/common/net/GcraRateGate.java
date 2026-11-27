package com.game.common.net;

/**
 * 全局新建连接 GCRA 速率闸（与令牌桶数学等价，对齐主仓 {@code GcraRateGate} P1-3）：
 * 支持无副作用预检（{@link #canAdmit}）与全部闸门放行后的实际记账（{@link #consume}），
 * 避免满员或未验签拒绝白白消耗全局速率名额。
 */
public final class GcraRateGate {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final long emissionIntervalNanos;
    private final long delayToleranceNanos;
    private long tatNanos;
    private boolean initialized;

    public GcraRateGate(int ratePerSecond, int burst) {
        if (ratePerSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("ratePerSecond 与 burst 必须为正: rate=" + ratePerSecond + " burst=" + burst);
        }
        this.emissionIntervalNanos = Math.max(1L, NANOS_PER_SECOND / ratePerSecond);
        this.delayToleranceNanos = (long) (burst - 1) * this.emissionIntervalNanos;
    }

    /** 仅查询此刻是否允许通过，不消耗名额。 */
    public synchronized boolean canAdmit(long nowNanos) {
        if (!initialized) {
            return true;
        }
        long allowAt = tatNanos - delayToleranceNanos;
        return nowNanos >= allowAt;
    }

    /** 记账消耗 1 个名额（应由调用方在 {@link #canAdmit} 返回 true 后同锁或确定放行时调用）。 */
    public synchronized void consume(long nowNanos) {
        if (!initialized) {
            tatNanos = nowNanos + emissionIntervalNanos;
            initialized = true;
            return;
        }
        long base = Math.max(tatNanos, nowNanos);
        tatNanos = base + emissionIntervalNanos;
    }

    /** 原子判定并消费 1 个名额。 */
    public synchronized boolean tryAdmit(long nowNanos) {
        if (!canAdmit(nowNanos)) {
            return false;
        }
        consume(nowNanos);
        return true;
    }
}
