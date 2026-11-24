package com.game.discovery.gate;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 本进程交出去、尚未反映到 gate 上报人数的在途票记账（对齐权威主仓 {@code loginqueue.GateInflight}）。
 *
 * <p>默认窗口为 20 秒（交票宽限），使用单调时钟避免墙钟回拨干扰，不跨进程同步。只影响挑台负载比较
 * （{@code reported + inflight}），不计入区容量记账与 90% 水位满载判定。线程安全。
 */
public final class GateInflight {

    /** 默认交票宽限窗口（20 秒）。 */
    public static final Duration DEFAULT_WINDOW = Duration.ofSeconds(20);

    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final Map<Integer, ArrayDeque<Long>> handedOut = new HashMap<>();

    public GateInflight() {
        this(DEFAULT_WINDOW, System::nanoTime);
    }

    public GateInflight(Duration window, LongSupplier nanoClock) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.windowNanos = window.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** 记录一张刚分配给 {@code nodeId} 的票。 */
    public void record(int nodeId) {
        record(nodeId, 1);
    }

    /** 记录 {@code count} 张刚分配给 {@code nodeId} 的票（排队放行按批记）。 */
    public synchronized void record(int nodeId, int count) {
        if (count <= 0) {
            return;
        }
        long now = nanoClock.getAsLong();
        ArrayDeque<Long> q = handedOut.computeIfAbsent(nodeId, k -> new ArrayDeque<>());
        prune(q, now);
        for (int i = 0; i < count; i++) {
            q.addLast(now);
        }
    }

    /** 查询 {@code nodeId} 在窗口内的在途票数，顺手淘汰所有过期条目以免下线节点残留。 */
    public synchronized int count(int nodeId) {
        long now = nanoClock.getAsLong();
        pruneAll(now);
        ArrayDeque<Long> q = handedOut.get(nodeId);
        return q == null ? 0 : q.size();
    }

    /** 拍一份各节点窗口内有效在途票数快照，供单次选台或整批放行复用。 */
    public synchronized Map<Integer, Integer> snapshot() {
        long now = nanoClock.getAsLong();
        pruneAll(now);
        if (handedOut.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> out = new HashMap<>(handedOut.size());
        for (Map.Entry<Integer, ArrayDeque<Long>> entry : handedOut.entrySet()) {
            out.put(entry.getKey(), entry.getValue().size());
        }
        return out;
    }

    private void pruneAll(long now) {
        Iterator<Map.Entry<Integer, ArrayDeque<Long>>> it = handedOut.entrySet().iterator();
        while (it.hasNext()) {
            ArrayDeque<Long> q = it.next().getValue();
            prune(q, now);
            if (q.isEmpty()) {
                it.remove();
            }
        }
    }

    private void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= windowNanos) {
            q.pollFirst();
        }
    }
}
