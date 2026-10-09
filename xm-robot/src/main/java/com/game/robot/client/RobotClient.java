package com.game.robot.client;

import com.game.contract.MessageIdRegistry;
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
    private final int redirectToGateId;
    private final int zoneId;
    private final Duration connectTimeout;
    private final Duration handshakeTimeout;

    /**
     * 124 的消息号按 classpath 上的契约注册表解析（批次 5.4 之前就有的签名，现有场景与测试照用）。入口已经加载了注册表时用
     * 带 {@code redirectToGateId} 的那个构造器，省一次加载。
     *
     * @param gatewayBaseUrl   xm-gateway 地址，不带结尾 {@code /}
     * @param zoneId           区号（assign-gate 的 {@code zone_id}）
     * @param connectTimeout   HTTP 与 TCP 建连超时
     * @param handshakeTimeout 等 {@code ClientTokenVerifyResponse} 的上限（Go robot 这里没有超时，探针必须有）
     */
    public RobotClient(String gatewayBaseUrl, int zoneId, MessageIds ids, Duration connectTimeout,
                       Duration handshakeTimeout) {
        this(gatewayBaseUrl, zoneId, ids, ContractRedirect.MESSAGE_ID, connectTimeout, handshakeTimeout);
    }

    /**
     * @param redirectToGateId 124 RedirectToGate 的消息号（{@link RedirectTarget#messageId}；{@link MessageIds} 不带它）：
     *                         登录流程靠它认出「进游戏之后来的不是 79 而是 124」，超时说明里也把它列出来
     */
    public RobotClient(String gatewayBaseUrl, int zoneId, MessageIds ids, int redirectToGateId, Duration connectTimeout,
                       Duration handshakeTimeout) {
        if (redirectToGateId <= 0) {
            throw new IllegalArgumentException("124 RedirectToGate 的消息号应为正数：" + redirectToGateId);
        }
        // 守护线程：场景抛异常没走到 close() 时也不挡 JVM 退出。
        this.group = new NioEventLoopGroup(2, new DefaultThreadFactory("xm-robot-io", true));
        this.assignGate = new AssignGateClient(gatewayBaseUrl, connectTimeout);
        this.ids = ids;
        this.redirectToGateId = redirectToGateId;
        this.zoneId = zoneId;
        this.connectTimeout = connectTimeout;
        this.handshakeTimeout = handshakeTimeout;
    }

    /** 旧构造器用的 124 消息号：整个 JVM 只从 classpath 解析一次。 */
    private static final class ContractRedirect {
        static final int MESSAGE_ID = RedirectTarget.messageId(MessageIdRegistry.loadFromClasspath());
    }

    public MessageIds ids() {
        return ids;
    }

    /** 124 RedirectToGate 的消息号。 */
    public int redirectToGateId() {
        return redirectToGateId;
    }

    /** 这个客户端 assign-gate 用的区号。 */
    public int zoneId() {
        return zoneId;
    }

    /** 等握手应答的上限（跟随 124 的握手用同一个）。 */
    public Duration handshakeTimeout() {
        return handshakeTimeout;
    }

    /** 分配 gate。 */
    public GateAssignment assignGate() throws RobotException {
        return assignGate.assign(zoneId);
    }

    /** TCP 连上 gate 并完成首帧握手（令牌为空时按 Go robot 的做法跳过握手）。 */
    public GameConnection connect(GateAssignment gate) throws RobotException {
        GameConnection connection = openRaw(gate.gateIp(), gate.gatePort());
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

    /**
     * 只建 TCP、<b>不握手</b>（批次 5.4）。握手由调用方用 {@link GameConnection#tryVerifyToken} 自己发：跟随 124 时要在
     * 「连上新 gate」与「验票」之间决定何时关旧连接；核对目标 gate 拒绝票据时要拿到 {@code success = false} 的应答并看到
     * 是服务端关的连接（{@link #connect} 失败时自己就把连接关了）。地址不看本客户端的区：任何区的 gate 都能连。连不上直接抛。
     * 调用方负责关闭。
     */
    public GameConnection openRaw(String host, int port) throws RobotException {
        return GameConnection.open(group, host, port, connectTimeout, ids.sendTip(), redirectToGateId);
    }

    /** TCP 连上 battle 直连面（不握手；地址取分配包 177 / 补签里的 host / port）。 */
    public BattleDirectConnection connectBattle(String host, int port) throws RobotException {
        return BattleDirectConnection.open(group, host, port, connectTimeout);
    }

    @Override
    public void close() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }
}
