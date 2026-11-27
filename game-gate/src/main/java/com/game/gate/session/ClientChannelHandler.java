package com.game.gate.session;

import com.game.common.net.ConnectionAdmissionController;
import com.game.common.net.ConnectionAdmissionController.AdmitOutcome;
import com.game.common.net.ConnectionAdmissionController.PreAuthEntry;
import com.game.common.net.ConnectionAdmissionController.RejectReason;
import com.game.common.net.ConnectionAdmissionPolicy;
import com.game.common.net.SourcePrefixRateLimiter;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.DisconnectReason;
import com.game.proto.ClientHeartbeatRequest;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端连接上的会话 handler（每条连接一个实例）：
 * <ul>
 *   <li>P1 / P0-2 / P2-2：准入判定（关源 → 总量 → 未验签预算/淘汰/早拒 → 全局速率 → 公网来源限速）发生在分配会话之前，被拒发 RST（{@code SO_LINGER=0}）；</li>
 *   <li>P2-4 票据先行：验签成功之前只持有一条轻量 {@link PreAuthEntry}，不发会话号、不建 {@link ClientSession}；验签通过且占票成功才建会话并继承连接代次；</li>
 *   <li>未验签断开只删预认证记录，不通知 login / scene；dev 免验签模式仍建连即建会话。</li>
 * </ul>
 * 所有回调都在该连接的 EventLoop 上，满足会话状态的线程所有权。
 */
public final class ClientChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClientChannelHandler.class);

    private final SessionRegistry registry;
    private final ClientDispatcher dispatcher;
    private final ConnectionAdmissionController<Channel> admission;
    private final boolean devSkipVerify;
    private final boolean externalSweepDriven;
    private PreAuthEntry<Channel> preAuth;
    private ScheduledFuture<?> preAuthHandshakeTimer;
    private String remoteIp = "";
    private ClientSession session;

    public ClientChannelHandler(SessionRegistry registry, ClientDispatcher dispatcher) {
        this(registry, dispatcher, unlimitedAdmission(registry, dispatcher), false, false);
    }

    public ClientChannelHandler(SessionRegistry registry, ClientDispatcher dispatcher,
                                int maxConnections, SourcePrefixRateLimiter sourceRateLimiter) {
        this(registry, dispatcher, legacyAdmission(registry, dispatcher, maxConnections, sourceRateLimiter), false, false);
    }

    public ClientChannelHandler(SessionRegistry registry, ClientDispatcher dispatcher,
                                ConnectionAdmissionController<Channel> admission,
                                boolean devSkipVerify, boolean externalSweepDriven) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.admission = admission != null ? admission : unlimitedAdmission(registry, dispatcher);
        this.devSkipVerify = devSkipVerify;
        this.externalSweepDriven = externalSweepDriven;
    }

    private static ConnectionAdmissionController<Channel> unlimitedAdmission(SessionRegistry registry,
                                                                             ClientDispatcher dispatcher) {
        return new ConnectionAdmissionController<>(
                ConnectionAdmissionPolicy.defaults(0, dispatcher.limits().handshakeTimeout().toMillis()),
                registry::size, dispatcher.nanoClock(), () -> 0.5, new com.game.common.net.LoopStallDetector());
    }

    private static ConnectionAdmissionController<Channel> legacyAdmission(SessionRegistry registry,
                                                                          ClientDispatcher dispatcher,
                                                                          int maxConnections,
                                                                          SourcePrefixRateLimiter sourceRateLimiter) {
        double srcRate = sourceRateLimiter != null && sourceRateLimiter.enabled() ? 20.0 : 0.0;
        double srcBurst = srcRate > 0 ? srcRate * 10.0 : 0.0;
        var policy = ConnectionAdmissionPolicy.of(Math.max(0, maxConnections),
                dispatcher.limits().handshakeTimeout().toMillis(),
                null, null, null, null, null, srcRate, srcBurst);
        return new ConnectionAdmissionController<>(policy, registry::size, dispatcher.nanoClock(),
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble(),
                new com.game.common.net.LoopStallDetector());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        Channel ch = ctx.channel();
        SocketAddress remote = ch.remoteAddress();
        remoteIp = peerIp(remote);
        InetAddress inet = remote instanceof InetSocketAddress isa ? isa.getAddress() : null;

        if (devSkipVerify) {
            RejectReason rejected = admission.tryAdmitBypassPreAuth(inet);
            if (rejected != null) {
                recordRejectMetrics(rejected, remote);
                rejectWithRst(ch);
                return;
            }
            session = registry.open(ch, remoteIp, admission.nextGeneration());
            if (session == null) {
                log.warn("会话号已耗尽，拒绝新连接 peer={}", remote);
                dispatcher.metrics().disconnected(DisconnectReason.SESSION_ID_EXHAUSTED);
                ch.close();
                return;
            }
            session.verified = true;
            dispatcher.onConnected(session);
            ctx.fireChannelActive();
            return;
        }

        AdmitOutcome<Channel> outcome = admission.tryAdmit(inet, ch);
        if (!outcome.admitted()) {
            recordRejectMetrics(outcome.rejectReason(), remote);
            rejectWithRst(ch);
            return;
        }
        if (outcome.evicted() != null) {
            Channel evictedCh = outcome.evicted().attachment();
            if (evictedCh != null && evictedCh.isOpen()) {
                // 被淘汰的是已建出来的未验签连接：走正常关闭（非 RST），在其自身 EventLoop 上关掉
                evictedCh.eventLoop().execute(evictedCh::close);
            }
        }
        this.preAuth = outcome.entry();
        if (!externalSweepDriven) {
            Duration timeout = dispatcher.limits().handshakeTimeout();
            if (!timeout.isZero()) {
                schedulePreAuthHandshakeCheck(ch, this.preAuth, timeout);
            }
        }
        ctx.fireChannelActive();
    }

    private void schedulePreAuthHandshakeCheck(Channel ch, PreAuthEntry<Channel> entry, Duration delay) {
        long due = dispatcher.nanoClock().getAsLong() + delay.toNanos();
        preAuthHandshakeTimer = ch.eventLoop().schedule(() -> {
            if (session != null || preAuth != entry || !ch.isActive()) {
                return;
            }
            if (entry.isClosing()) {
                admission.releasePreAuth(entry);
                return;
            }
            if (dispatcher.nanoClock().getAsLong() - due > TimeUnit.SECONDS.toNanos(2)) {
                dispatcher.metrics().heartbeat(GateMetrics.HeartbeatResult.DEFERRED);
                schedulePreAuthHandshakeCheck(ch, entry, Duration.ofSeconds(1));
                return;
            }
            // 先删记录再关连接，确保同一条连接不会被重复计次
            entry.markClosing();
            admission.releasePreAuth(entry);
            dispatcher.onPreAuthHandshakeTimeout(ch, remoteIp);
        }, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void cancelPreAuthTimer() {
        ScheduledFuture<?> t = preAuthHandshakeTimer;
        preAuthHandshakeTimer = null;
        if (t != null) {
            t.cancel(false);
        }
    }

    private void recordRejectMetrics(RejectReason reason, SocketAddress remote) {
        dispatcher.metrics().admissionRejected(reason);
        switch (reason) {
            case CAPACITY -> {
                log.warn("客户端连接数已达上限，RST 拒绝新连接 peer={}", remote);
                dispatcher.metrics().disconnected(DisconnectReason.MAX_CONNECTIONS);
            }
            case SOURCE_RATE -> {
                log.warn("承压期公网来源前缀限速拒绝新连接 peer={}", remote);
                dispatcher.metrics().disconnected(DisconnectReason.SOURCE_RATE_LIMITED);
            }
            default -> log.debug("连接准入拒绝 reason={} peer={}", reason.tag(), remote);
        }
    }

    /** 仅用于准入拒绝：设置 SO_LINGER=0 立即发 RST，不留 TIME_WAIT、不回业务包。 */
    public static void rejectWithRst(Channel ch) {
        try {
            ch.config().setOption(ChannelOption.SO_LINGER, 0);
        } catch (RuntimeException ignored) {
            // EmbeddedChannel 或非 TCP 传输不支持 SO_LINGER，照常立即关闭
        }
        ch.close();
    }

    /**
     * 解码器是否还解下一帧（{@link ClientPipeline} 接到 game-net {@code ClientFrameDecoder} 的 keepDecoding）。
     * 只在本连接的 EventLoop 上调用。
     */
    boolean acceptsFrames() {
        if (session != null) {
            return !session.closing && !session.closed;
        }
        return preAuth != null && !preAuth.isClosing();
    }

    /** 连接是否已完成 gate 票据验签（供解码器与 WS 桥在验签前收紧至 4 KiB、验签后放宽至 64 KiB）。 */
    boolean isVerified() {
        return session != null && session.verified;
    }

    /** 仅供单测核对：当前是否已创建会话（验签前必为 null）。 */
    ClientSession session() {
        return session;
    }

    /** 仅供单测核对：当前未验签记录（验签后归零）。 */
    PreAuthEntry<Channel> preAuthEntry() {
        return preAuth;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (session != null) {
            if (msg instanceof ClientRequest request) {
                dispatcher.onRequest(session, request);
            } else if (msg instanceof ClientTokenVerifyRequest verify) {
                dispatcher.onTokenVerify(session, verify);
            } else if (msg instanceof ClientHeartbeatRequest heartbeat) {
                dispatcher.onHeartbeat(session, heartbeat);
            } else {
                ReferenceCountUtil.release(msg);
            }
            return;
        }
        PreAuthEntry<Channel> entry = preAuth;
        if (entry == null || entry.isClosing()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        if (msg instanceof ClientTokenVerifyRequest verify) {
            cancelPreAuthTimer();
            ClientSession opened = dispatcher.verifyPreAuth(ctx.channel(), remoteIp,
                    entry.generation(), entry.acceptedNanos(), verify);
            if (opened != null) {
                this.session = opened;
                admission.releasePreAuth(entry);
                this.preAuth = null;
            } else {
                entry.markClosing();
                admission.releasePreAuth(entry);
            }
        } else if (msg instanceof ClientHeartbeatRequest) {
            cancelPreAuthTimer();
            entry.markClosing();
            admission.releasePreAuth(entry);
            dispatcher.onPreAuthHeartbeat(ctx.channel(), remoteIp);
        } else if (msg instanceof ClientRequest) {
            cancelPreAuthTimer();
            entry.markClosing();
            admission.releasePreAuth(entry);
            dispatcher.onPreAuthRequest(ctx.channel(), remoteIp);
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelPreAuthTimer();
        PreAuthEntry<Channel> entry = preAuth;
        if (entry != null) {
            entry.markClosing();
            admission.releasePreAuth(entry);
            preAuth = null;
        }
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
