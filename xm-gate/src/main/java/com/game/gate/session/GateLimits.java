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
 * @param redirectLinger         已重定向的会话（推过 124 RedirectToGateNotify，批次 5.4）最多再留多久：客户端在此之内没有自己断开，
 *                               gate 到点直接关（不推 tip）。不得短于 {@link #MIN_REDIRECT_LINGER}——客户端要先连上目标 gate、验完票才关旧连接
 *                               （探测 5 s + 验票 10 s），留得太短会把还在路上的客户端提前踢掉（{@code xm.gate.redirect-linger}，缺省 60 s）
 */
public record GateLimits(int maxPendingRequests, int illegalPacketThreshold, Duration handshakeTimeout,
                         MessageLimits messageLimits, boolean gmCommandsAllowed, Duration redirectLinger) {

    /** {@link #redirectLinger} 的缺省值（{@code xm.gate.redirect-linger} 的缺省）。 */
    public static final Duration DEFAULT_REDIRECT_LINGER = Duration.ofSeconds(60);
    /** {@link #redirectLinger} 的下限：客户端探测目标 gate 5 s + 验票 10 s。 */
    public static final Duration MIN_REDIRECT_LINGER = Duration.ofSeconds(15);

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
        if (redirectLinger == null || redirectLinger.compareTo(MIN_REDIRECT_LINGER) < 0) {
            throw new IllegalArgumentException("xm.gate.redirect-linger 不得短于 " + MIN_REDIRECT_LINGER.toSeconds() + "s: "
                    + redirectLinger);
        }
    }

    /** 重定向收口时限取缺省 60 s（不关心重定向的装配用）。 */
    public GateLimits(int maxPendingRequests, int illegalPacketThreshold, Duration handshakeTimeout,
                      MessageLimits messageLimits, boolean gmCommandsAllowed) {
        this(maxPendingRequests, illegalPacketThreshold, handshakeTimeout, messageLimits, gmCommandsAllowed,
                DEFAULT_REDIRECT_LINGER);
    }

    /** 拒绝 GM 指令（生产默认）；重定向收口时限取缺省 60 s。 */
    public GateLimits(int maxPendingRequests, int illegalPacketThreshold, Duration handshakeTimeout,
                      MessageLimits messageLimits) {
        this(maxPendingRequests, illegalPacketThreshold, handshakeTimeout, messageLimits, false);
    }

    /** 不限频的阈值组合（只给测试等不关心限流的装配用；生产装配必须显式给 {@link MessageLimits}）。 */
    public GateLimits(int maxPendingRequests, int illegalPacketThreshold, Duration handshakeTimeout) {
        this(maxPendingRequests, illegalPacketThreshold, handshakeTimeout, MessageLimits.UNLIMITED, false);
    }
}
