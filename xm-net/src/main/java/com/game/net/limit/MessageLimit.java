package com.game.net.limit;

import java.time.Duration;

/**
 * 一个消息号的发送频率上限：任意 {@code window} 时长内最多 {@code maxRequests} 条（C++ MessageLimiter 同义）。
 *
 * <p>gate 与 battle 直连面两个客户端面共用（基线两边查同一张 MessageLimiter 表，{@code edge.h:22-24}；battle-node-spec §7.2、Q9）。
 *
 * @param maxRequests 窗口内最多几条；0 表示不限
 * @param window      窗口长度
 */
public record MessageLimit(int maxRequests, Duration window) {

    /** C++ {@code MessageLimiter} 的缺省值：MessageLimiter 表里没有这个消息号时，每秒最多 3 条。 */
    public static final MessageLimit DEFAULT = new MessageLimit(3, Duration.ofSeconds(1));
    public static final MessageLimit UNLIMITED = new MessageLimit(0, Duration.ZERO);

    public MessageLimit {
        if (maxRequests < 0 || window == null || window.isNegative()) {
            throw new IllegalArgumentException("频率上限非法: " + maxRequests + "/" + window);
        }
        if (maxRequests > 0 && window.isZero()) {
            throw new IllegalArgumentException("有上限时窗口必须为正: " + maxRequests + "/" + window);
        }
    }

    public boolean unlimited() {
        return maxRequests == 0;
    }
}
