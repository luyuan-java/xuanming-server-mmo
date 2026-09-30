package com.game.scene.link;

import com.game.api.proto.LinkHello;
import com.game.api.proto.NodeLinkFrame;
import com.game.common.token.NodeLinkAuth;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一条 gate → scene 链路的入站处理（每条连接一个实例，不可共享）。
 *
 * <p>只做三件事，都在该连接的 I/O 线程上：
 * <ol>
 *   <li>握手：首帧必须是 {@code LinkHello}，且依次通过：鉴权（{@link NodeLinkAuth}：MAC 常数时间比较、
 *       时间戳与本地时钟相差不超过 60s）→ zone 与本节点一致 → gate_node_id 非 0；否则回
 *       {@code LinkHelloAck{accepted=false, reason}} 并断开；超时未握手也断开。鉴权失败对外只给一句固定原因，
 *       不说是 MAC 错还是时间戳过期；</li>
 *   <li>握手之后把每一帧原样投递到场景逻辑线程（{@link LinkInbound}），I/O 线程不碰场景状态；</li>
 *   <li>背压：已投递、逻辑线程还没执行完的帧达到 {@code maxPendingFrames} 时停止读这条连接（autoRead=false），
 *       逻辑线程消化到一半以下再恢复。积压因此有上界（每条链路一个上界，链路数 = gate 数），压力沿 TCP 传回 gate，
 *       而不是在逻辑线程的任务队列里无限增长直到 OOM。</li>
 * </ol>
 * 握手成功的回包由逻辑线程在登记链路后发出（见 {@link LinkInbound#linkOpened}），保证 gate 收到 ack 时链路已可用。
 */
public final class NodeLinkHandler extends SimpleChannelInboundHandler<NodeLinkFrame> {

    private static final Logger log = LoggerFactory.getLogger(NodeLinkHandler.class);

    /** 鉴权失败的对外原因只给这一句，不区分 MAC 错还是时间戳过期（细节只进本地日志）。 */
    static final String AUTH_FAILED_REASON = "链路鉴权失败";

    private enum State { HANDSHAKING, OPEN, REJECTED }

    private final LinkIdentity identity;
    private final NodeLinkAuth auth;
    private final InstantSource clock;
    private final LinkInbound inbound;
    private final Executor logicExecutor;
    private final AtomicLong linkIds;
    private final Duration handshakeTimeout;
    private final int maxPendingFrames;
    private final int resumePendingFrames;

    // 以下字段只在本连接的 EventLoop 上读写。
    private State state = State.HANDSHAKING;
    private long linkId;
    private ScheduledFuture<?> handshakeTimer;
    private boolean readPaused;

    // 背压计数：I/O 线程加、逻辑线程减。
    private final AtomicInteger pendingFrames = new AtomicInteger();
    /** 读已暂停（I/O 线程写，逻辑线程读）：逻辑线程只在暂停时才安排恢复，平时不给 I/O 线程加任务。 */
    private volatile boolean pausedFlag;
    private final AtomicBoolean resumeScheduled = new AtomicBoolean();

    /**
     * @param auth             链路握手鉴权（密钥来自环境变量 {@code XM_NODE_LINK_SECRET}，须与 gate 一致）
     * @param clock            校验握手时间戳用的时钟
     * @param linkIds          进程内共享的链路号计数器（从 1 起），保证链路号不复用
     * @param maxPendingFrames 这条链路已投递未执行的帧数上限，达到即暂停读；降到一半以下恢复
     */
    public NodeLinkHandler(LinkIdentity identity, NodeLinkAuth auth, InstantSource clock, LinkInbound inbound,
                           Executor logicExecutor, AtomicLong linkIds, Duration handshakeTimeout, int maxPendingFrames) {
        if (maxPendingFrames < 2) {
            throw new IllegalArgumentException("maxPendingFrames 至少为 2: " + maxPendingFrames);
        }
        this.identity = identity;
        this.auth = auth;
        this.clock = clock;
        this.inbound = inbound;
        this.logicExecutor = logicExecutor;
        this.linkIds = linkIds;
        this.handshakeTimeout = handshakeTimeout;
        this.maxPendingFrames = maxPendingFrames;
        this.resumePendingFrames = maxPendingFrames / 2;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        handshakeTimer = ctx.executor().schedule(() -> {
            if (state == State.HANDSHAKING) {
                log.warn("gate 链路握手超时，断开 remote={}", ctx.channel().remoteAddress());
                ctx.close();
            }
        }, handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        super.channelActive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, NodeLinkFrame frame) {
        switch (state) {
            case HANDSHAKING -> handshake(ctx, frame);
            case OPEN -> {
                if (frame.hasHello()) {
                    log.warn("gate 链路重复握手，断开 link={} remote={}", linkId, ctx.channel().remoteAddress());
                    ctx.close();
                    return;
                }
                long id = linkId;
                postFrame(ctx, () -> inbound.frameReceived(id, frame));
            }
            case REJECTED -> {
                // 已回拒绝、正在断开，后续帧一律丢弃。
            }
        }
    }

    private void handshake(ChannelHandlerContext ctx, NodeLinkFrame frame) {
        if (!frame.hasHello()) {
            reject(ctx, "首帧必须是 LinkHello，实际 " + frame.getBodyCase());
            return;
        }
        LinkHello hello = frame.getHello();
        NodeLinkAuth.Verdict verdict = auth.verify(hello.getGateNodeId(), hello.getGateInstanceId(), hello.getZoneId(),
                hello.getLeaseEpoch(), hello.getAuthTimestamp(), hello.getAuthMac(), clock.instant().getEpochSecond());
        if (verdict != NodeLinkAuth.Verdict.OK) {
            log.warn("gate 链路鉴权失败 remote={} 结果={} gate_node={} auth_timestamp={}", ctx.channel().remoteAddress(),
                    verdict, Integer.toUnsignedLong(hello.getGateNodeId()), Long.toUnsignedString(hello.getAuthTimestamp()));
            reject(ctx, AUTH_FAILED_REASON);
            return;
        }
        if (hello.getZoneId() != identity.zoneId()) {
            reject(ctx, "zone 不符：本节点 " + identity.zoneId() + "，gate " + hello.getZoneId());
            return;
        }
        if (hello.getGateNodeId() == 0) {
            reject(ctx, "gate_node_id 为 0");
            return;
        }
        cancelHandshakeTimer();
        state = State.OPEN;
        linkId = linkIds.incrementAndGet();
        long id = linkId;
        Channel channel = ctx.channel();
        log.info("gate 链路握手通过 link={} gate_node={} gate_instance={} lease_epoch={} remote={}", id,
                hello.getGateNodeId(), hello.getGateInstanceId(), Long.toUnsignedString(hello.getLeaseEpoch()),
                channel.remoteAddress());
        post(() -> inbound.linkOpened(id, hello, channel));
    }

    private void reject(ChannelHandlerContext ctx, String reason) {
        log.warn("拒绝 gate 链路 remote={} 原因={}", ctx.channel().remoteAddress(), reason);
        cancelHandshakeTimer();
        state = State.REJECTED;
        ctx.writeAndFlush(identity.ack(false, reason)).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cancelHandshakeTimer();
        if (state == State.OPEN) {
            long id = linkId;
            log.info("gate 链路断开 link={} remote={}", id, ctx.channel().remoteAddress());
            post(() -> inbound.linkClosed(id));
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("gate 链路异常，断开 link={} remote={}", linkId, ctx.channel().remoteAddress(), cause);
        ctx.close();
    }

    /** 当前已投递、逻辑线程还没执行完的业务帧数（测试与诊断用）。 */
    int pendingFrames() {
        return pendingFrames.get();
    }

    /** I/O 线程：计数、必要时暂停读，再把帧投递给逻辑线程；逻辑线程执行完回调 {@link #frameDone}。 */
    private void postFrame(ChannelHandlerContext ctx, Runnable task) {
        int pending = pendingFrames.incrementAndGet();
        if (!readPaused && pending >= maxPendingFrames) {
            readPaused = true;
            pausedFlag = true;
            ctx.channel().config().setAutoRead(false);
            log.warn("场景逻辑线程积压，暂停读取 gate 链路 link={} 积压帧={}", linkId, pending);
            // 置暂停标记之前逻辑线程可能已经消化到恢复线以下（那时它看不到标记、不会安排恢复）：这里补查一次。
            if (pendingFrames.get() <= resumePendingFrames) {
                resumeReading(ctx);
            }
        }
        post(() -> {
            try {
                task.run();
            } finally {
                frameDone(ctx);
            }
        });
    }

    /** 逻辑线程：计数减一；读已暂停且积压降到恢复线以下时，请 I/O 线程恢复读（只安排一次）。 */
    private void frameDone(ChannelHandlerContext ctx) {
        int pending = pendingFrames.decrementAndGet();
        if (pausedFlag && pending <= resumePendingFrames && resumeScheduled.compareAndSet(false, true)) {
            try {
                ctx.executor().execute(() -> resumeReading(ctx));
            } catch (RejectedExecutionException e) {
                resumeScheduled.set(false);
            }
        }
    }

    /** I/O 线程：再确认一次积压确实在恢复线以下才恢复读（期间可能又涨上去了）。 */
    private void resumeReading(ChannelHandlerContext ctx) {
        resumeScheduled.set(false);
        if (readPaused && pendingFrames.get() <= resumePendingFrames) {
            readPaused = false;
            pausedFlag = false;
            ctx.channel().config().setAutoRead(true);
            log.info("场景逻辑线程积压回落，恢复读取 gate 链路 link={} 积压帧={}", linkId, pendingFrames.get());
        }
    }

    private void cancelHandshakeTimer() {
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
            handshakeTimer = null;
        }
    }

    private void post(Runnable task) {
        try {
            logicExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            // 停服时逻辑线程先停，迟到的链路事件没有接收方了。
            log.debug("场景逻辑线程已停止，丢弃链路事件 link={}", linkId);
        }
    }
}
