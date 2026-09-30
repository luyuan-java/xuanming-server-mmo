package com.game.gate.link;

import java.time.Duration;

/**
 * 链路参数。
 *
 * @param helloTimeout    连上之后等 {@code LinkHelloAck} 的上限；超时判建链失败
 * @param maxQueuedFrames 链路未就绪时每条链路最多排队的帧数；溢出的 PlayerEnter 按建链失败回报，其余丢弃
 */
public record LinkSettings(Duration helloTimeout, int maxQueuedFrames) {

    public LinkSettings {
        if (helloTimeout == null || helloTimeout.isNegative()) {
            throw new IllegalArgumentException("helloTimeout 非法: " + helloTimeout);
        }
        if (maxQueuedFrames <= 0) {
            throw new IllegalArgumentException("maxQueuedFrames 必须为正: " + maxQueuedFrames);
        }
    }
}
