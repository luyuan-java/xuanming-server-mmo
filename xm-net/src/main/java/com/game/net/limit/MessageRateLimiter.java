package com.game.net.limit;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 一条连接按消息号的滑动窗口限流（C++ {@code MessageLimiter::CanSend} 同义）：记录每个消息号最近被受理的时刻，
 * 窗口内已满 {@link MessageLimit#maxRequests()} 条就拒绝。被拒的请求不占额度。窗口用单调时钟纳秒（基线是整秒，边界略宽松，
 * PARITY「gate 按消息号限频」行；battle-node-spec §11 N11）。
 *
 * <p>只在连接所属的 EventLoop 上使用，不加锁；每条连接一份。内存上界：本连接发过的消息号数 × 各自的上限条数
 * （白名单外的号也计，靠非法包阈值兜住，B4）。
 */
public final class MessageRateLimiter {

    private final Map<Integer, ArrayDeque<Long>> accepted = new HashMap<>();

    /**
     * @param nowNanos 单调时钟读数（纳秒，{@link System#nanoTime()}）
     * @return true = 受理并计入窗口；false = 超频
     */
    public boolean tryAcquire(int messageId, MessageLimit limit, long nowNanos) {
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
