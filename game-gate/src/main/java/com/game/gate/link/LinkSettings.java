package com.game.gate.link;

import java.time.Duration;

/**
 * 链路参数。
 *
 * @param helloTimeout    连上之后等 {@code LinkHelloAck} 的上限；超时判建链失败
 * @param maxQueuedFrames 链路未就绪时每条链路最多排队的帧数；溢出的 PlayerEnter 按建链失败回报，其余丢弃
 */
public record LinkSettings(Duration helloTimeout, int maxQueuedFrames, Duration pingInterval, Duration idleTimeout,
                           Duration supersededGrace) {

    /** 兼容已有调用；旧握手未声明心跳时这些设置不会启用探活。 */
    public LinkSettings(Duration helloTimeout, int maxQueuedFrames) {
        this(helloTimeout, maxQueuedFrames, Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    public LinkSettings {
        if (helloTimeout == null || helloTimeout.isNegative()) {
            throw new IllegalArgumentException("helloTimeout 非法: " + helloTimeout);
        }
        if (maxQueuedFrames <= 0) {
            throw new IllegalArgumentException("maxQueuedFrames 必须为正: " + maxQueuedFrames);
        }
        if (pingInterval == null || pingInterval.compareTo(Duration.ofSeconds(1)) < 0 || pingInterval.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("pingInterval 必须在 [1s,30s]");
        if (idleTimeout == null || idleTimeout.compareTo(pingInterval.multipliedBy(3)) < 0)
            throw new IllegalArgumentException("idleTimeout 必须至少为 pingInterval 的三倍");
        if (supersededGrace == null || supersededGrace.compareTo(Duration.ofSeconds(20)) < 0)
            throw new IllegalArgumentException("supersededGrace 不得短于 20s");
    }
}
