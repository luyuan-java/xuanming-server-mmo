package com.game.robot.scenario;

import java.time.Duration;

/** 冷却验收的单调时钟判据；不给服务端猜一个精确起点，而保留首次成功切换的起算区间。 */
final class ChannelTiming {
    static final long WRITE_BOUND = Duration.ofSeconds(3).toNanos();
    static final long PROBE_GAP = Duration.ofSeconds(3).toNanos();
    static final long MIN_REMAINING = Duration.ofSeconds(2).toNanos();
    static final long EXPIRY_MARGIN = Duration.ofMillis(250).toNanos();
    private ChannelTiming() {}

    /**
     * 跨节点在目标79之前写冷却；同节点最迟在79之后3秒内完成首次写入。
     * probeAt比最迟写入再晚3秒，使“拒绝时续期”的最早过期晚于固定returnAt，留下可检验窗口。
     */
    static Window window(long firstSent, long firstArrival, long cooldownSeconds) {
        if (firstArrival - firstSent < 0 || cooldownSeconds < 1 || cooldownSeconds > 60)
            throw new IllegalArgumentException("冷却观测必须有按序的首次发送/到达及1–60秒TTL");
        long ttl = Duration.ofSeconds(cooldownSeconds).toNanos();
        long latestWrite = firstArrival + WRITE_BOUND;
        return new Window(ttl, firstSent + ttl, latestWrite, latestWrite + PROBE_GAP,
                latestWrite + ttl + EXPIRY_MARGIN);
    }

    record Window(long ttl, long earliestExpiry, long latestWrite, long probeAt, long returnAt) {
        boolean canProbe(long now) {
            return ttl >= Duration.ofSeconds(10).toNanos() && now - probeAt >= 0
                    && earliestExpiry - now >= MIN_REMAINING;
        }
        /** 到达或拒绝在最早可能过期之前可证明发生于冷却内；迟到结果不能当成冷却失效。 */
        boolean beforeExpiry(long observed) { return observed - earliestExpiry < 0; }

        /** 成功切回必须发生在原冷却最迟过期之后、任何“拒绝续期”最早过期之前，才能排除续期。 */
        boolean excludesRenewal(long rejectionSent, long returnObserved) {
            return canProbe(rejectionSent) && returnObserved - returnAt >= 0
                    && returnObserved - (rejectionSent + ttl) < 0;
        }
    }

    static long waitNanos(long deadline, long now) { return Math.max(0, deadline - now); }
}
