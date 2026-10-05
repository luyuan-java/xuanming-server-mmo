package com.game.gate.session;

import com.game.gate.metrics.GateMetrics.DisconnectReason;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端连接上的会话 handler（每条连接一个实例）：建连分配会话、上行交给 {@link ClientDispatcher}、断线收尾。
 * 所有回调都在该连接的 EventLoop 上，满足会话状态的线程所有权。
 */
public final class ClientChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClientChannelHandler.class);

    private final SessionRegistry registry;
    private final ClientDispatcher dispatcher;
    private ClientSession session;

    public ClientChannelHandler(SessionRegistry registry, ClientDispatcher dispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        session = registry.open(ctx.channel(), peerIp(ctx.channel().remoteAddress()));
        if (session == null) {
            log.warn("会话号已耗尽，拒绝新连接 peer={}", ctx.channel().remoteAddress());
            dispatcher.metrics().disconnected(DisconnectReason.SESSION_ID_EXHAUSTED);
            ctx.close();
            return;
        }
        dispatcher.onConnected(session);
        ctx.fireChannelActive();
    }

    /**
     * 解码器是否还解下一帧（{@link ClientPipeline} 接到 xm-net {@code ClientFrameDecoder} 的 keepDecoding）：会话存在、没决定关闭、
     * 没断开。会话一旦决定关闭（握手被拒、握手前发请求、非法包达阈值、服务端指令关闭……），同一次读里剩下的帧不再解析——不分发、
     * 不计非法帧、坏帧也不当场强关（不会截断已排队的拒绝应答 / tip），同基线 codec 的 {@code conn->connected()} 判据。
     * 只在本连接的 EventLoop 上调用。
     */
    boolean acceptsFrames() {
        return session != null && !session.closing && !session.closed;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (session == null) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof ClientRequest request) {
            dispatcher.onRequest(session, request);
        } else if (msg instanceof ClientTokenVerifyRequest verify) {
            dispatcher.onTokenVerify(session, verify);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (session != null) {
            dispatcher.onDisconnected(session);
        }
        ctx.fireChannelInactive();
    }

    /** 写缓冲越过高水位 = 客户端不读（断线 / 作弊 / 过慢）：直接断开（C++ kClientHighWaterMark 同义）。 */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (!ctx.channel().isWritable()) {
            log.warn("客户端写缓冲超过高水位，断开 session={} peer={}",
                    session == null ? "-" : Integer.toUnsignedString(session.sessionId()), ctx.channel().remoteAddress());
            if (ctx.channel().isActive()) {
                dispatcher.metrics().disconnected(DisconnectReason.WRITE_BUFFER_FULL);
            }
            ctx.close();
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("客户端连接异常，断开 peer={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    static String peerIp(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString();
        }
        return address == null ? "" : address.toString();
    }
}
