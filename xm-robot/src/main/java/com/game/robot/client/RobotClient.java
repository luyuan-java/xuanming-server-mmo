package com.game.robot.client;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 一次探针运行共享的客户端资源：Netty I/O 线程、HTTP 客户端、消息号。{@link #connect} 走完「分配 gate → TCP → 握手」。
 * 线程安全，多个账号并发使用同一个实例；用完 {@link #close()}。
 */
public final class RobotClient implements AutoCloseable {

    private final EventLoopGroup group;
    private final AssignGateClient assignGate;
    private final MessageIds ids;
    private final int zoneId;
    private final Duration connectTimeout;
    private final Duration handshakeTimeout;

    /**
     * @param gatewayBaseUrl   xm-gateway 地址，不带结尾 {@code /}
     * @param zoneId           区号（assign-gate 的 {@code zone_id}）
     * @param connectTimeout   HTTP 与 TCP 建连超时
     * @param handshakeTimeout 等 {@code ClientTokenVerifyResponse} 的上限（Go robot 这里没有超时，探针必须有）
     */
    public RobotClient(String gatewayBaseUrl, int zoneId, MessageIds ids, Duration connectTimeout,
                       Duration handshakeTimeout) {
        // 守护线程：场景抛异常没走到 close() 时也不挡 JVM 退出。
        this.group = new NioEventLoopGroup(2, new DefaultThreadFactory("xm-robot-io", true));
        this.assignGate = new AssignGateClient(gatewayBaseUrl, connectTimeout);
        this.ids = ids;
        this.zoneId = zoneId;
        this.connectTimeout = connectTimeout;
        this.handshakeTimeout = handshakeTimeout;
    }

    public MessageIds ids() {
        return ids;
    }

    /** 分配 gate。 */
    public GateAssignment assignGate() throws RobotException {
        return assignGate.assign(zoneId);
    }

    /** TCP 连上 gate 并完成首帧握手（令牌为空时按 Go robot 的做法跳过握手）。 */
    public GameConnection connect(GateAssignment gate) throws RobotException {
        GameConnection connection = GameConnection.open(group, gate.gateIp(), gate.gatePort(), connectTimeout,
                ids.sendTip());
        try {
            if (gate.tokenPayload().length > 0) {
                connection.verifyToken(gate.tokenPayload(), gate.tokenSignature(), handshakeTimeout);
            }
            return connection;
        } catch (RobotException e) {
            connection.close();
            throw e;
        }
    }

    @Override
    public void close() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }
}
