package com.game.gate.session;

import com.game.net.limit.MessageLimits;
import java.time.Duration;

/**
 * 会话层的保护阈值。
 *
 * @param maxPendingRequests     一个会话最多排队的上行请求数（login 调用在途时后续请求排队）；超出即断开
 * @param illegalPacketThreshold 非法包（未知消息号、超长、超频、运行模式不放行的 GM 指令）累计到此数断开；0 表示不断开（C++ GATE_ILLEGAL_PACKET_THRESHOLD，默认 50）
 * @param handshakeTimeout       连上后多久内必须完成令牌握手，超时断开；0 表示不限
 * @param messageLimits          按消息号的发送频率上限（C++ MessageLimiter；生产取 MessageLimiter 表，缺省每秒 3 条）
 * @param gmCommandsAllowed      放行 GM 类客户端指令（运行模式 dev / test）；否则推 23 {1006}、计非法包、不转发（C++ GATE_RUN_MODE）
 */
public record GateLimits(int maxPendingRequests, int illegalPacketThreshold, Duration handshakeTimeout,
                         MessageLimits messageLimits, boolean gmCommandsAllowed) {

    public GateLimits {
        if (maxPendingRequests <= 0) {
            throw new IllegalArgumentException("maxPendingRequests 必须为正: " + maxPendingRequests);
        }
        if (illegalPacketThreshold < 0) {
            throw new IllegalArgumentException("illegalPacketThreshold 不能为负: " + illegalPacketThreshold);
        }
        if (handshakeTimeout == null || handshakeTimeout.isNegative()) {
            throw new IllegalArgumentException("handshakeTimeout 非法: " + handshakeTimeout);
        }
        if (messageLimits == null) {
            throw new IllegalArgumentException("messageLimits 不能为空");
        }
    }

    /** 拒绝 GM 指令（生产默认）。 */
    public GateLimits(int maxPendingRequests, int illegalPacketThreshold, Duration handshakeTimeout,
                      MessageLimits messageLimits) {
        this(maxPendingRequests, illegalPacketThreshold, handshakeTimeout, messageLimits, false);
    }

    /** 不限频的阈值组合（只给测试等不关心限流的装配用；生产装配必须显式给 {@link MessageLimits}）。 */
    public GateLimits(int maxPendingRequests, int illegalPacketThreshold, Duration handshakeTimeout) {
        this(maxPendingRequests, illegalPacketThreshold, handshakeTimeout, MessageLimits.UNLIMITED, false);
    }
}
