package com.game.battle.edge;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 直连面拒绝日志的采样（基线 {@code edge.cpp:32-49} {@code LogRejectionSampled}）：未验证连接的建立 / 拒绝次数完全由公网流量决定，
 * 不能逐条写盘。按原因<b>分别</b>计数——prod 里 handshake_timeout / at_capacity 是常态噪声，与 ticket_hmac_mismatch 共用一个计数器的话，
 * 一轮针对签名的伪造尝试可能一条日志都不留。每种原因第一次必打，之后每 {@value #SAMPLE_EVERY} 次打一行。
 *
 * <p>线程安全（原子计数）：主要在逻辑线程上调，停机排空可能在别的线程上调。
 */
final class EdgeRejectLog {

    private static final Logger log = LoggerFactory.getLogger(EdgeRejectLog.class);

    /** 采样间隔（基线 {@code (count++ & 0x3FF) == 0}）。 */
    static final int SAMPLE_EVERY = 1024;

    private final Map<EdgeRejectReason, AtomicLong> counts = new EnumMap<>(EdgeRejectReason.class);

    EdgeRejectLog() {
        for (EdgeRejectReason reason : EdgeRejectReason.values()) {
            counts.put(reason, new AtomicLong());
        }
    }

    /**
     * 记一次拒绝；第 1、1025、2049 … 次打一行 WARN。
     *
     * @param peer 最近一次的对端（{@code ip:port}，只进日志）
     * @return 这次是否打了日志（测试用）
     */
    boolean record(EdgeRejectReason reason, String peer) {
        long n = counts.get(reason).getAndIncrement();
        if ((n & (SAMPLE_EVERY - 1)) == 0) {
            log.warn("battle 直连拒绝（采样：每种原因首次必打，之后每 {} 次一行） reason={} latest_peer={} reason_total={}",
                    SAMPLE_EVERY, reason.wireName(), peer, n + 1);
            return true;
        }
        return false;
    }

    /** 某原因累计次数（测试与排障用）。 */
    long count(EdgeRejectReason reason) {
        return counts.get(reason).get();
    }
}
