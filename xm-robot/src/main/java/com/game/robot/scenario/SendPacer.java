package com.game.robot.scenario;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * gate 按连接、按消息号滑动窗口限频（{@code MessageRateLimiter}；63 不在 MessageLimiter 表里，取缺省每秒 3 条，超了回信封 1008、不转发）。
 * 探针一个场景里要发十来条 63，在发之前按同样的窗口等够，免得被 gate 挡下而到不了 scene。窗口多留 200 ms 余量。
 * 只在场景线程上用（不加锁）；连接按身份区分（重连后是新连接、gate 上也是新窗口）。
 */
final class SendPacer {

    /** gate 缺省：每秒 3 条。 */
    static final int MAX_IN_WINDOW = 3;
    /** 1 s 窗口 + 200 ms 余量（与 cross-node 的 {@code GATE_RATE_WINDOW} 同口径）。 */
    static final Duration WINDOW = Duration.ofMillis(1200);

    private final Map<Object, ArrayDeque<Long>> sent = new IdentityHashMap<>();

    /** 发 {@code count} 条之前调用：必要时睡到窗口里放得下这几条。 */
    void await(Object connection, int count) throws InterruptedException {
        ArrayDeque<Long> times = sent.computeIfAbsent(connection, c -> new ArrayDeque<>());
        long waitNanos = waitNanos(List.copyOf(times), System.nanoTime(), count, MAX_IN_WINDOW, WINDOW.toNanos());
        if (waitNanos > 0) {
            Thread.sleep(Duration.ofNanos(waitNanos));
        }
    }

    /** 刚发出一条（发完立即调用）。 */
    void record(Object connection) {
        ArrayDeque<Long> times = sent.computeIfAbsent(connection, c -> new ArrayDeque<>());
        times.addLast(System.nanoTime());
        while (times.size() > MAX_IN_WINDOW) {
            times.pollFirst();
        }
    }

    /**
     * 还要等多久才能在同一时刻再发 {@code count} 条而不超过「任意 {@code windowNanos} 内至多 {@code max} 条」。
     *
     * @param sentNanos 之前各条的发送时刻（升序）
     * @return 需要等待的纳秒数（0 = 立即可发）
     */
    static long waitNanos(List<Long> sentNanos, long nowNanos, int count, int max, long windowNanos) {
        if (count < 1 || count > max) {
            throw new IllegalArgumentException("一次要发 " + count + " 条，窗口上限 " + max);
        }
        List<Long> inWindow = new ArrayList<>();
        for (long t : sentNanos) {
            if (nowNanos - t < windowNanos) {
                inWindow.add(t);
            }
        }
        int excess = inWindow.size() + count - max;
        if (excess <= 0) {
            return 0;
        }
        // 最老的 excess 条滑出窗口之后才放得下
        return Math.max(0, inWindow.get(excess - 1) + windowNanos - nowNanos);
    }
}
