package com.game.common.net;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 边缘连接准入与未验签连接池总控（gate 与 battle 共用；对齐主仓 P1-1..P1-4、P0-2、P2-2、P2-4）。
 *
 * <p>判定严格在创建每连接业务会话之前执行，且按固定顺序：
 * {@code shutting_down -> capacity -> preauth(先淘汰最老 >= evictableAge 且未卡顿，否则 budget / 线性 early_drop) -> accept_rate(GCRA) -> source_rate(仅承压期)}。
 * 所有副作用（消耗全局速率、来源记账、淘汰旧未验签连接、登记新未验签记录）仅在全部闸门通过后才原子提交。
 *
 * @param <C> 连接句柄类型（如 Netty {@code Channel}）
 */
public final class ConnectionAdmissionController<C> {

    public enum RejectReason {
        SHUTTING_DOWN("shutting_down"),
        CAPACITY("capacity"),
        PREAUTH_BUDGET("preauth_budget"),
        PREAUTH_EARLY_DROP("preauth_early_drop"),
        ACCEPT_RATE("accept_rate"),
        SOURCE_RATE("source_rate");

        private final String tag;

        RejectReason(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /** 验签前轻量记录：建连时刻、严格单调代次与连接句柄；不占业务会话号与会话对象。 */
    public static final class PreAuthEntry<C> {
        private final long generation;
        private final long acceptedNanos;
        private final C attachment;
        private volatile boolean closing;

        PreAuthEntry(long generation, long acceptedNanos, C attachment) {
            this.generation = generation;
            this.acceptedNanos = acceptedNanos;
            this.attachment = attachment;
        }

        public long generation() {
            return generation;
        }

        public long acceptedNanos() {
            return acceptedNanos;
        }

        public C attachment() {
            return attachment;
        }

        public boolean isClosing() {
            return closing;
        }

        /** 标记本未验签连接已进入关闭（例如验签失败正回发拒绝包），淘汰与超时巡检会跳过它。 */
        public void markClosing() {
            this.closing = true;
        }
    }

    public record AdmitOutcome<C>(boolean admitted, RejectReason rejectReason, PreAuthEntry<C> entry,
                                  PreAuthEntry<C> evicted) {
        static <C> AdmitOutcome<C> ok(PreAuthEntry<C> entry, PreAuthEntry<C> evicted) {
            return new AdmitOutcome<>(true, null, entry, evicted);
        }

        static <C> AdmitOutcome<C> reject(RejectReason reason) {
            return new AdmitOutcome<>(false, Objects.requireNonNull(reason), null, null);
        }
    }

    private final ConnectionAdmissionPolicy policy;
    private final IntSupplier verifiedCountSupplier;
    private final LongSupplier nanoClock;
    private final DoubleSupplier random;
    private final GcraRateGate globalRateGate;
    private final SourcePrefixRateLimiter sourceRateLimiter;
    private final LoopStallDetector stallDetector;
    private final long handshakeTimeoutNanos;
    private final long evictableAgeNanos;

    private final LinkedHashMap<Long, PreAuthEntry<C>> preAuthPool = new LinkedHashMap<>();
    private final AtomicLong generationSeq = new AtomicLong();
    private final AtomicLong acceptedTotal = new AtomicLong();
    private final AtomicLong preAuthEvictedTotal = new AtomicLong();
    private final AtomicLong preAuthReapedTotal = new AtomicLong();
    private volatile boolean shuttingDown;
    private volatile long lastGlobalRateRejectNanos;
    private volatile boolean hasGlobalRateReject;

    public ConnectionAdmissionController(ConnectionAdmissionPolicy policy, IntSupplier verifiedCountSupplier) {
        this(policy, verifiedCountSupplier, System::nanoTime, () -> ThreadLocalRandom.current().nextDouble(),
                new LoopStallDetector());
    }

    public ConnectionAdmissionController(ConnectionAdmissionPolicy policy,
                                         IntSupplier verifiedCountSupplier,
                                         LongSupplier nanoClock,
                                         DoubleSupplier random,
                                         LoopStallDetector stallDetector) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.verifiedCountSupplier = Objects.requireNonNull(verifiedCountSupplier, "verifiedCountSupplier");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.random = Objects.requireNonNull(random, "random");
        this.stallDetector = Objects.requireNonNull(stallDetector, "stallDetector");
        this.handshakeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(policy.handshakeTimeoutMs());
        this.evictableAgeNanos = TimeUnit.MILLISECONDS.toNanos(policy.preAuthEvictableAgeMs());
        this.globalRateGate = policy.enabled()
                ? new GcraRateGate(policy.acceptRatePerSecond(), policy.acceptBurst())
                : null;
        this.sourceRateLimiter = new SourcePrefixRateLimiter(
                policy.sourceRatePerSecond(),
                policy.sourceBurst(),
                SourcePrefixRateLimiter.DEFAULT_MAX_PREFIXES,
                SourcePrefixRateLimiter.DEFAULT_IDLE_EXPIRY,
                nanoClock);
    }

    public ConnectionAdmissionPolicy policy() {
        return policy;
    }

    public LoopStallDetector stallDetector() {
        return stallDetector;
    }

    public SourcePrefixRateLimiter sourceRateLimiter() {
        return sourceRateLimiter;
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** 分配下一个严格单调连接代次（dev 免验签直建会话时共用同一代次序列）。 */
    public long nextGeneration() {
        return generationSeq.incrementAndGet();
    }

    /** 当前未验签连接数（O(1)）。 */
    public synchronized int preAuthCount() {
        return preAuthPool.size();
    }

    /** 总连接数 = 已验签会话数 + 未验签连接数。 */
    public int totalConnections() {
        return verifiedCountSupplier.getAsInt() + preAuthCount();
    }

    public long acceptedTotal() {
        return acceptedTotal.get();
    }

    public long preAuthEvictedTotal() {
        return preAuthEvictedTotal.get();
    }

    public long preAuthReapedTotal() {
        return preAuthReapedTotal.get();
    }

    /** 来源前缀限速当前是否处于承压激活态。 */
    public boolean isSourceRateActive() {
        if (!sourceRateLimiter.enabled()) {
            return false;
        }
        return isUnderPressure(preAuthCount(), nanoClock.getAsLong());
    }

    private boolean isUnderPressure(int preAuthSize, long nowNanos) {
        if (policy.enabled() && preAuthSize >= policy.preAuthEarlyDropStart()) {
            return true;
        }
        if (hasGlobalRateReject) {
            long window = handshakeTimeoutNanos > 0L ? handshakeTimeoutNanos : TimeUnit.SECONDS.toNanos(10);
            if ((nowNanos - lastGlobalRateRejectNanos) < window) {
                return true;
            }
        }
        return false;
    }

    /**
     * 准入判定并登记一条未验签记录；返回中的 {@link AdmitOutcome#evicted()} 非空时，调用方应在对应连接的事件循环上
     * 正常关闭被淘汰连接（非 RST），该记录已从本池原子摘除，不会被重复计入或二次淘汰。
     */
    public AdmitOutcome<C> tryAdmit(InetAddress remoteAddress, C attachment) {
        long now = nanoClock.getAsLong();
        synchronized (this) {
            if (shuttingDown) {
                return AdmitOutcome.reject(RejectReason.SHUTTING_DOWN);
            }
            int preAuthSize = preAuthPool.size();
            if (policy.enabled()) {
                int total = verifiedCountSupplier.getAsInt() + preAuthSize;
                if (total >= policy.maxConnections()) {
                    return AdmitOutcome.reject(RejectReason.CAPACITY);
                }
            }
            PreAuthEntry<C> evictCandidate = null;
            if (policy.enabled() && preAuthSize >= policy.preAuthEarlyDropStart()) {
                if (!stallDetector.isStalled(now)) {
                    evictCandidate = findOldestEvictableLocked(now);
                }
                if (evictCandidate == null) {
                    if (preAuthSize >= policy.preAuthBudget()) {
                        return AdmitOutcome.reject(RejectReason.PREAUTH_BUDGET);
                    }
                    int span = policy.preAuthBudget() - policy.preAuthEarlyDropStart();
                    if (span > 0) {
                        double dropProb = (double) (preAuthSize - policy.preAuthEarlyDropStart()) / (double) span;
                        if (random.getAsDouble() < dropProb) {
                            return AdmitOutcome.reject(RejectReason.PREAUTH_EARLY_DROP);
                        }
                    }
                }
            }
            if (globalRateGate != null && !globalRateGate.canAdmit(now)) {
                lastGlobalRateRejectNanos = now;
                hasGlobalRateReject = true;
                return AdmitOutcome.reject(RejectReason.ACCEPT_RATE);
            }
            boolean underPressure = isUnderPressure(preAuthSize, now);
            if (sourceRateLimiter.enabled() && underPressure && !sourceRateLimiter.canAdmit(remoteAddress, true)) {
                return AdmitOutcome.reject(RejectReason.SOURCE_RATE);
            }
            // 所有闸门全部放行：原子提交记账、淘汰与建档
            if (globalRateGate != null) {
                globalRateGate.consume(now);
            }
            if (sourceRateLimiter.enabled() && underPressure) {
                sourceRateLimiter.recordAdmitted(remoteAddress, true);
            }
            if (evictCandidate != null) {
                preAuthPool.remove(evictCandidate.generation);
                evictCandidate.markClosing();
                preAuthEvictedTotal.incrementAndGet();
            }
            PreAuthEntry<C> entry = new PreAuthEntry<>(generationSeq.incrementAndGet(), now, attachment);
            preAuthPool.put(entry.generation, entry);
            acceptedTotal.incrementAndGet();
            return AdmitOutcome.ok(entry, evictCandidate);
        }
    }

    /** 仅在放行但不建未验签池条目的路径（如 dev 免验签）走关源/总量/速率闸。 */
    public RejectReason tryAdmitBypassPreAuth(InetAddress remoteAddress) {
        long now = nanoClock.getAsLong();
        synchronized (this) {
            if (shuttingDown) {
                return RejectReason.SHUTTING_DOWN;
            }
            int preAuthSize = preAuthPool.size();
            if (policy.enabled()) {
                int total = verifiedCountSupplier.getAsInt() + preAuthSize;
                if (total >= policy.maxConnections()) {
                    return RejectReason.CAPACITY;
                }
            }
            if (globalRateGate != null && !globalRateGate.canAdmit(now)) {
                lastGlobalRateRejectNanos = now;
                hasGlobalRateReject = true;
                return RejectReason.ACCEPT_RATE;
            }
            boolean underPressure = isUnderPressure(preAuthSize, now);
            if (sourceRateLimiter.enabled() && underPressure && !sourceRateLimiter.canAdmit(remoteAddress, true)) {
                return RejectReason.SOURCE_RATE;
            }
            if (globalRateGate != null) {
                globalRateGate.consume(now);
            }
            if (sourceRateLimiter.enabled() && underPressure) {
                sourceRateLimiter.recordAdmitted(remoteAddress, true);
            }
            acceptedTotal.incrementAndGet();
            return null;
        }
    }

    private PreAuthEntry<C> findOldestEvictableLocked(long nowNanos) {
        for (PreAuthEntry<C> e : preAuthPool.values()) {
            if (nowNanos - e.acceptedNanos < evictableAgeNanos) {
                break; // 按建连先后有序，后续一定更年轻
            }
            if (!e.closing) {
                return e;
            }
        }
        return null;
    }

    /** 幂等归还未验签占位（验签成功、被拒或连接断开时调用，最多生效一次）。 */
    public synchronized boolean releasePreAuth(PreAuthEntry<C> entry) {
        if (entry == null) {
            return false;
        }
        return preAuthPool.remove(entry.generation, entry);
    }

    /**
     * 从有序池头收割超过握手期限的未验签连接（P2-4）：
     * <ul>
     *   <li>若本端事件循环刚卡过（{@link LoopStallDetector#isStalled}），本拍推迟不收割；</li>
     *   <li>遇到已因别的原因进入关闭的连接（{@link PreAuthEntry#isClosing()}），只删记录、不计握手超时；</li>
     *   <li>否则先删记录并标记关闭、累计 {@link #preAuthReapedTotal()}，最后回调关闭连接。</li>
     * </ul>
     *
     * @return 本轮因握手超时被收割并关闭的条数
     */
    public int sweepExpired(Consumer<PreAuthEntry<C>> onTimeout) {
        if (handshakeTimeoutNanos <= 0L) {
            return 0;
        }
        long now = nanoClock.getAsLong();
        List<PreAuthEntry<C>> timedOut = null;
        synchronized (this) {
            if (stallDetector.isStalled(now)) {
                return 0;
            }
            Iterator<PreAuthEntry<C>> it = preAuthPool.values().iterator();
            while (it.hasNext()) {
                PreAuthEntry<C> e = it.next();
                if (now - e.acceptedNanos < handshakeTimeoutNanos) {
                    break;
                }
                it.remove();
                if (e.closing) {
                    continue; // 已因别的原因正在关闭：只删记录，不计握手超时
                }
                e.markClosing();
                preAuthReapedTotal.incrementAndGet();
                if (timedOut == null) {
                    timedOut = new ArrayList<>();
                }
                timedOut.add(e);
            }
        }
        if (timedOut == null) {
            return 0;
        }
        for (PreAuthEntry<C> e : timedOut) {
            if (onTimeout != null) {
                onTimeout.accept(e);
            }
        }
        return timedOut.size();
    }

    /** 停机 / 关源时清空并关闭全部未验签连接。 */
    public void closeAllPreAuth(Consumer<PreAuthEntry<C>> closer) {
        List<PreAuthEntry<C>> snapshot;
        synchronized (this) {
            if (preAuthPool.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(preAuthPool.values());
            preAuthPool.clear();
            for (PreAuthEntry<C> e : snapshot) {
                e.markClosing();
            }
        }
        if (closer != null) {
            for (PreAuthEntry<C> e : snapshot) {
                closer.accept(e);
            }
        }
    }
}
