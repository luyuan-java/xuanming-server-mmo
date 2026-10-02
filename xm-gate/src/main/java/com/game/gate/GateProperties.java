package com.game.gate;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gate 配置（{@code xm.gate.*}）。zone 与对外通告地址是全进程共用的 {@code xm.zone-id} / {@code xm.advertise-host}，不在这里。
 *
 * <p>gate 令牌密钥不在这里：它只从环境变量 {@code XM_GATE_TOKEN_SECRET} 读（见 {@link GateConfiguration}），
 * 不给任何可以写进仓库的配置入口。
 *
 * @param clientPort             客户端 TCP 端口
 * @param advertisePort          写进节点目录、由 gateway 下发给客户端的端口；缺省 = clientPort。经 NodePort / hostPort / 端口映射
 *                               对外时与监听端口不同（同 mmorpg `NodeInfo.client_endpoint` 的 port）
 * @param workerThreads          客户端 I/O 线程数；0 = Netty 默认（CPU 核数 × 2）
 * @param linkThreads            scene 链路 I/O 线程数
 * @param maxPendingRequests     单会话最多排队的上行请求（login 调用在途时后续请求排队），超出断开
 * @param illegalPacketThreshold 非法包（未知消息号 / 超长 / 超频 / 运行模式不放行的 GM 指令）累计到此数断开；0 = 不断开（C++ 默认 50）
 * @param handshakeTimeout       连上后必须在此时间内完成令牌握手；0 = 不限
 * @param linkConnectTimeout     连 scene 的 TCP 连接超时
 * @param linkHelloTimeout       连上 scene 后等握手应答的上限
 * @param linkMaxQueuedFrames    scene 链路未就绪时单链路最多排队帧数
 * @param shutdownDrainTimeout   退出时等待会话收尾（PlayerLeave / 断线通知发出）的上限
 */
@ConfigurationProperties("xm.gate")
public record GateProperties(
        Integer clientPort,
        Integer advertisePort,
        Integer workerThreads,
        Integer linkThreads,
        Integer maxPendingRequests,
        Integer illegalPacketThreshold,
        Duration handshakeTimeout,
        Duration linkConnectTimeout,
        Duration linkHelloTimeout,
        Integer linkMaxQueuedFrames,
        Duration shutdownDrainTimeout) {

    public GateProperties {
        clientPort = clientPort == null ? 11000 : clientPort;
        advertisePort = advertisePort == null || advertisePort == 0 ? clientPort : advertisePort;
        workerThreads = workerThreads == null ? 0 : workerThreads;
        linkThreads = linkThreads == null ? 2 : linkThreads;
        maxPendingRequests = maxPendingRequests == null ? 64 : maxPendingRequests;
        illegalPacketThreshold = illegalPacketThreshold == null ? 50 : illegalPacketThreshold;
        handshakeTimeout = handshakeTimeout == null ? Duration.ofSeconds(30) : handshakeTimeout;
        linkConnectTimeout = linkConnectTimeout == null ? Duration.ofSeconds(3) : linkConnectTimeout;
        linkHelloTimeout = linkHelloTimeout == null ? Duration.ofSeconds(5) : linkHelloTimeout;
        linkMaxQueuedFrames = linkMaxQueuedFrames == null ? 10_000 : linkMaxQueuedFrames;
        shutdownDrainTimeout = shutdownDrainTimeout == null ? Duration.ofSeconds(3) : shutdownDrainTimeout;
        if (clientPort <= 0 || clientPort > 65535) {
            throw new IllegalArgumentException("xm.gate.client-port 非法: " + clientPort);
        }
        if (advertisePort < 0 || advertisePort > 65535) {
            throw new IllegalArgumentException("xm.gate.advertise-port 非法: " + advertisePort);
        }
        if (linkThreads <= 0) {
            throw new IllegalArgumentException("xm.gate.link-threads 必须为正: " + linkThreads);
        }
    }
}
