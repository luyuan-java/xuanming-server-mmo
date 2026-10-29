package com.game.data.consume;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 单个固定消费目标的线程安全观测；失败不把旧值包装成新鲜零，缺口证据在本进程内保留。 */
public final class AuditProbeState {
    public enum Status { OK, NO_COMMIT_HISTORY, RETENTION_GAP, UNKNOWN, STALE, STOPPED }

    public record View(AuditOffsetSnapshot.Target target, Status status, String reason, double ageSeconds,
                       long lastAttemptMillis, long lastSuccessMillis, AuditOffsetSnapshot lastSample,
                       AuditOffsetSnapshot lastGapEvidence, long gapHighWater) {
        public boolean fresh() {
            return status == Status.OK || status == Status.NO_COMMIT_HISTORY || status == Status.RETENTION_GAP;
        }
        public double lag() { return fresh() ? lastSample.lag() : Double.NaN; }
        public double maxPartitionLag() { return fresh() ? lastSample.maxPartitionLag() : Double.NaN; }
        public double retentionGap() {
            return fresh() && lastSample.uncommittedPartitions() == 0 ? lastSample.retentionGap() : Double.NaN;
        }
        /** 已有提交历史的分区上确认的下界；其他分区缺历史时也不能隐藏这个风险。 */
        public double confirmedGapLowerBound() { return fresh() ? lastSample.retentionGap() : Double.NaN; }
        public double uncommittedPartitions() { return fresh() ? lastSample.uncommittedPartitions() : Double.NaN; }
    }

    private final AuditOffsetSnapshot.Target target;
    private final long staleNanos;
    private final LongSupplier nanos;
    private AuditOffsetSnapshot lastSample;
    private AuditOffsetSnapshot lastGap;
    private String pinnedTopicId;
    private String reason = "NOT_OBSERVED";
    private boolean stopped;
    private long attemptedMillis;
    private long succeededMillis;
    private long succeededNanos;
    private long highWater;

    public AuditProbeState(AuditOffsetSnapshot.Target target, Duration staleAfter, LongSupplier nanos) {
        this.target = Objects.requireNonNull(target, "target");
        this.staleNanos = staleAfter.toNanos();
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    public synchronized void started() { stopped = false; reason = "NOT_OBSERVED"; }
    public synchronized void stopped() { stopped = true; }

    /** 返回 false 表示同代 topic 身份已改变，本进程不自动接受重建的 topic。 */
    public synchronized boolean succeeded(AuditOffsetSnapshot sample, long startedNanos, long nowMillis) {
        if (!target.equals(sample.target())) throw new IllegalArgumentException("探针返回了其他目标");
        attemptedMillis = nowMillis;
        if (pinnedTopicId != null && !pinnedTopicId.equals(sample.topicId())) {
            reason = AuditProbeFailure.Reason.TOPIC_CHANGED.name();
            return false;
        }
        pinnedTopicId = sample.topicId();
        lastSample = sample;
        succeededMillis = nowMillis;
        // 新鲜度从读取开始算；网络慢不能让前面读到的旧位点获得一个新的完整 freshness 窗口。
        succeededNanos = startedNanos;
        reason = null;
        if (sample.retentionGap() > 0) {
            lastGap = sample;
            highWater = Math.max(highWater, sample.retentionGap());
        }
        return true;
    }

    public synchronized void failed(AuditProbeFailure.Reason failure, long nowMillis) {
        attemptedMillis = nowMillis;
        reason = failure.name();
    }

    public synchronized View view() {
        double age = Double.NaN;
        boolean stale = false;
        if (lastSample != null) {
            long elapsed = nanos.getAsLong() - succeededNanos;
            stale = elapsed < 0 || elapsed >= staleNanos;
            age = elapsed < 0 ? Double.POSITIVE_INFINITY : elapsed / 1_000_000_000.0;
        }
        Status status;
        if (stopped) status = Status.STOPPED;
        else if (reason != null) status = Status.UNKNOWN;
        else if (stale) status = Status.STALE;
        else if (lastSample.retentionGap() > 0) status = Status.RETENTION_GAP;
        else if (lastSample.uncommittedPartitions() > 0) status = Status.NO_COMMIT_HISTORY;
        else status = Status.OK;
        return new View(target, status, reason, age, attemptedMillis, succeededMillis, lastSample, lastGap, highWater);
    }
}
