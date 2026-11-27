package com.game.common.net;

import java.util.concurrent.TimeUnit;

/**
 * 本端事件循环卡顿判定器（对齐主仓 1 秒节拍判卡顿规则）：
 * 还没打过拍、上一拍本身迟到（间隔 &gt; 2 个节拍）、或距上一拍已超过 2 个节拍，均视为刚卡过；
 * 卡过时承压淘汰与握手超时收割推迟，避免把本端暂停误算成客户端超时/空占。
 */
public final class LoopStallDetector {

    public static final long DEFAULT_BEAT_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final long beatPeriodNanos;
    private final long stallThresholdNanos;
    private boolean hasBeat;
    private long lastBeatNanos;
    private boolean lastBeatLate;

    public LoopStallDetector() {
        this(DEFAULT_BEAT_PERIOD_NANOS);
    }

    public LoopStallDetector(long beatPeriodNanos) {
        if (beatPeriodNanos <= 0L) {
            throw new IllegalArgumentException("beatPeriodNanos 必须为正: " + beatPeriodNanos);
        }
        this.beatPeriodNanos = beatPeriodNanos;
        this.stallThresholdNanos = beatPeriodNanos * 2L;
    }

    /** 每隔一个节拍在事件循环线程上调用一次。 */
    public synchronized void recordBeat(long nowNanos) {
        if (hasBeat) {
            lastBeatLate = (nowNanos - lastBeatNanos) > stallThresholdNanos;
        } else {
            lastBeatLate = false;
            hasBeat = true;
        }
        lastBeatNanos = nowNanos;
    }

    /** 当前是否处于「刚卡过 / 尚未打拍」状态。 */
    public synchronized boolean isStalled(long nowNanos) {
        if (!hasBeat || lastBeatLate) {
            return true;
        }
        return (nowNanos - lastBeatNanos) > stallThresholdNanos;
    }

    public long beatPeriodNanos() {
        return beatPeriodNanos;
    }
}
