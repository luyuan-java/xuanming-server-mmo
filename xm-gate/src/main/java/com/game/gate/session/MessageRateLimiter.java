package com.game.gate.session;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 一个会话按消息号的滑动窗口限流（C++ {@code MessageLimiter::CanSend} 同义）：记录每个消息号最近被受理的时刻，
 * 窗口内已满 {@link MessageLimit#maxRequests()} 条就拒绝。被拒的请求不占额度。
 *
 * <p>只在会话所属的 EventLoop 上使用，不加锁。内存上界：本会话发过的消息号数 × 各自的上限条数。
 */
final class MessageRateLimiter {

    private final Map<Integer, ArrayDeque<Long>> accepted = new HashMap<>();

    /**
     * @param nowNanos 单调时钟读数（纳秒）
     * @return true = 受理并计入窗口；false = 超频
     */
    boolean tryAcquire(int messageId, MessageLimit limit, long nowNanos) {
        if (limit.unlimited()) {
            return true;
        }
        ArrayDeque<Long> times = accepted.computeIfAbsent(messageId, id -> new ArrayDeque<>(limit.maxRequests()));
        long windowNanos = limit.window().toNanos();
        while (!times.isEmpty() && nowNanos - times.peekFirst() >= windowNanos) {
            times.pollFirst();
        }
        if (times.size() >= limit.maxRequests()) {
            return false;
        }
        times.addLast(nowNanos);
        return true;
    }
}
