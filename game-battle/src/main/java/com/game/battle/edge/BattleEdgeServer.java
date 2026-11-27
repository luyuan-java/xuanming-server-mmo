package com.game.battle.edge;

import com.game.battle.BattleProperties;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.common.net.ConnectionAdmissionController;
import com.game.common.net.ConnectionAdmissionController.AdmitOutcome;
import com.game.common.net.ConnectionAdmissionController.PreAuthEntry;
import com.game.common.net.ConnectionAdmissionPolicy;
import com.game.common.net.LoopStallDetector;
import com.game.common.net.SourcePrefixRateLimiter;
import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.net.client.ClientFrames;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.ClientRequest;
import com.google.protobuf.Message;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端直连面（基线 {@code BattleClientEdge}，{@code edge.cpp} / {@code edge.h}；battle-node-spec §3、§7.4）。
 *
 * <p><b>线程</b>（§7.3、Q16）：{@code ServerBootstrap} 的 child group 就是 battle 逻辑线程组（单线程，{@link EdgeDependencies#logicGroup()}），
 * 全部直连注册在它唯一的 EventLoop 上——同一条线程独占直连会话与全部房间，handler 里直接调 {@code BattleRoomService}。
 * boss（accept）线程由本类自己建、自己关；逻辑线程组归 {@code BattleNode}，本类不关它。
 *
 * <p><b>pipeline</b>（每连接）：{@link EdgeInboundGuard}（关闭中丢弃入站字节）→ game-net {@link ClientFrameDecoder}（上行只收
 * {@code BattleTokenVerifyRequest} / {@code ClientRequest}，帧长 ≤ 64 KiB，逐帧分发，非法帧计数 + 采样日志后立即关、不回包）→
 * {@link ClientFrameEncoder} → {@link BattleEdgeHandler}（持有 {@link DirectSession}）。子连接 {@code TCP_NODELAY}、写水位 1 / 2 MiB。
 *
 * <p>连接数（含未握手）是原子量，任何线程可读；会话集合是并发集合，停机排空从别的线程遍历。
 */
public final class BattleEdgeServer implements DirectEdge {

    private static final Logger log = LoggerFactory.getLogger(BattleEdgeServer.class);

    /** 直连写水位（基线 {@code kDirectConnHighWaterMark} 2 MiB，{@code edge.cpp:23}）：越过高水位即强关（客户端收不动时不能让广播把内存吃光）。 */
    static final WriteBufferWaterMark WATER_MARK = new WriteBufferWaterMark(1 << 20, 2 << 20);

    /** 上行只认两种类型（{@code edge.cpp:58-65}）：全名 → 默认实例。这批 proto 没有 package，全名就是裸名。 */
    static final Map<String, Message> ACCEPTED = Map.of(
            BattleTokenVerifyRequest.getDescriptor().getFullName(), BattleTokenVerifyRequest.getDefaultInstance(),
            ClientRequest.getDescriptor().getFullName(), ClientRequest.getDefaultInstance(),
            com.game.proto.ClientHeartbeatRequest.getDescriptor().getFullName(),
            com.game.proto.ClientHeartbeatRequest.getDefaultInstance(),
            com.game.proto.match.NetworkProbeStart.getDescriptor().getFullName(),com.game.proto.match.NetworkProbeStart.getDefaultInstance(),
            com.game.proto.match.NetworkProbeEcho.getDescriptor().getFullName(),com.game.proto.match.NetworkProbeEcho.getDefaultInstance());

    /** 停机时等强关完成、等 accept 线程退出的上限（只为不无限阻塞）。 */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final EdgeDependencies deps;
    private final LongSupplier nanoClock;
    private final LoopStallDetector stallDetector;
    private final Set<DirectSession> sessions = ConcurrentHashMap.newKeySet();
    private final EdgeRejectLog rejectLog = new EdgeRejectLog();
    private final AtomicLong acceptedTotal = new AtomicLong();
    private volatile ConnectionAdmissionController<Channel> admission;
    private volatile ScheduledFuture<?> preAuthSweepTask;
    private volatile boolean serverSweepActive;

    private final Object lifecycle = new Object();
    private EventLoopGroup bossGroup;
    private volatile Channel serverChannel;
    private boolean started;
    private boolean drained;

    public BattleEdgeServer(EdgeDependencies deps) {
        this(deps, System::nanoTime,
                ConnectionAdmissionPolicy.fromEnv("BATTLE_",
                        Objects.requireNonNull(deps, "deps").properties().effectiveMaxConnections(),
                        deps.properties().handshakeTimeout().toMillis()),
                () -> ThreadLocalRandom.current().nextDouble(),
                new LoopStallDetector());
    }

    /** @param nanoClock 单调时钟（限频窗口；测试注入） */
    BattleEdgeServer(EdgeDependencies deps, LongSupplier nanoClock) {
        this(deps, nanoClock,
                ConnectionAdmissionPolicy.defaults(
                        Objects.requireNonNull(deps, "deps").properties().effectiveMaxConnections(),
                        deps.properties().handshakeTimeout().toMillis()),
                () -> ThreadLocalRandom.current().nextDouble(),
                new LoopStallDetector());
    }

    /** 测试定制准入策略、早拒随机源与卡顿检测器。 */
    BattleEdgeServer(EdgeDependencies deps, LongSupplier nanoClock, ConnectionAdmissionPolicy policy,
                     DoubleSupplier random, LoopStallDetector stallDetector) {
        this.deps = Objects.requireNonNull(deps, "deps");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.stallDetector = Objects.requireNonNull(stallDetector, "stallDetector");
        this.admission = new ConnectionAdmissionController<>(policy, sessions::size, nanoClock, random, stallDetector);
        this.deps.metrics().bindAdmission(this.admission);
    }

    /** 装配仅公网来源前缀限速（承压时激活；默认关闭，开启后仅对公网 IPv4 /24、IPv6 /64 生效）。 */
    public BattleEdgeServer withSourcePrefixRateLimiter(SourcePrefixRateLimiter limiter, int preAuthBudget) {
        Objects.requireNonNull(limiter, "limiter");
        if (preAuthBudget <= 0) {
            throw new IllegalArgumentException("preAuthBudget 必须为正: " + preAuthBudget);
        }
        int maxConn = deps.properties().effectiveMaxConnections();
        long hsMs = deps.properties().handshakeTimeout().toMillis();
        long evictAge = Math.min(3_000L, Math.max(1L, hsMs - 1L));
        int budget = Math.max(preAuthBudget, maxConn);
        ConnectionAdmissionPolicy p = ConnectionAdmissionPolicy.of(
                maxConn, hsMs, Math.max(100, maxConn / 100), maxConn, budget, preAuthBudget, evictAge, null, null);
        this.admission = new ConnectionAdmissionController<>(p, sessions::size, nanoClock, () -> 1.0, stallDetector, limiter);
        this.deps.metrics().bindAdmission(this.admission);
        return this;
    }

    /** O(1) 未认证直连数：既未完成票据握手、也未通过探测授权验签的连接数。 */
    public int unauthenticatedConnectionCount() {
        return admission.preAuthCount();
    }

    /** 当前准入总控（单测核对指标与策略用）。 */
    ConnectionAdmissionController<Channel> admission() {
        return admission;
    }

    /** 构造时给的依赖（装配与测试核对用）。 */
    public EdgeDependencies dependencies() {
        return deps;
    }

    // ---------------------------------------------------------------- DirectEdge

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (started) {
                throw new IllegalStateException("battle 直连面已启动过");
            }
            started = true;
            requireSingleLogicLoop();
            BattleProperties p = deps.properties();
            boolean useEpoll = com.game.net.client.NettyServerTransports.isEpollGroup(deps.logicGroup());
            EventLoopGroup boss = com.game.net.client.NettyServerTransports.newEventLoopGroup(1, "battle-accept", useEpoll);
            ServerBootstrap bootstrap = com.game.net.client.NettyServerTransports.configureServer(
                    new ServerBootstrap().group(boss, deps.logicGroup()),
                    useEpoll, com.game.net.client.NettyServerTransports.DEFAULT_TCP_DEFER_ACCEPT_SECONDS)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WATER_MARK)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            BattleEdgeServer.this.initChannel(ch);
                        }
                    });
            ChannelFuture bind = bootstrap.bind(p.clientBindHost(), p.clientPort()).awaitUninterruptibly();
            if (!bind.isSuccess()) {
                boss.shutdownGracefully(0, 1, TimeUnit.SECONDS);
                throw new IllegalStateException("battle 直连端口绑定失败 " + p.clientBindHost() + ":" + p.clientPort(), bind.cause());
            }
            bossGroup = boss;
            serverChannel = bind.channel();
            // 1 秒节拍：更新事件循环卡顿判据并从未认证池表头清扫超时连接（P2-4：生产不按连接起握手定时器）
            stallDetector.recordBeat(nanoClock.getAsLong());
            serverSweepActive = true;
            preAuthSweepTask = deps.logicGroup().next().scheduleAtFixedRate(() -> {
                stallDetector.recordBeat(nanoClock.getAsLong());
                sweepPreAuthExpired();
            }, 1, 1, TimeUnit.SECONDS);
            log.info("battle 直连面已监听 {} max_connections={} handshake_timeout={} illegal_packet_threshold={}",
                    bind.channel().localAddress(), p.effectiveMaxConnections(), p.handshakeTimeout(), p.illegalPacketThreshold());
        }
    }

    @Override
    public void stopAccepting() {
        admission.setShuttingDown(true);
        Channel server = serverChannel;
        if (server == null) {
            return;
        }
        serverChannel = null;
        ChannelFuture closed = server.close();
        if (!inLogicThread()) {
            closed.awaitUninterruptibly(CLOSE_WAIT.toMillis());
        }
        log.info("battle 直连面停止接受新连接 {} 现有连接={}", server.localAddress(), connectionCount());
    }

    @Override
    public void drainAndClose(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (inLogicThread()) {
            throw new IllegalStateException("drainAndClose 会阻塞等待逻辑线程写完输出，不得在逻辑线程上调用");
        }
        EventLoopGroup boss;
        ScheduledFuture<?> sweep;
        synchronized (lifecycle) {
            if (drained) {
                return;
            }
            drained = true;
            boss = bossGroup;
            sweep = preAuthSweepTask;
            preAuthSweepTask = null;
            serverSweepActive = false;
        }
        if (sweep != null) {
            sweep.cancel(false);
        }
        stopAccepting();
        // ① 有界等待正在优雅关闭的连接排空（终局包、观众的 166 与 FIN 写完；修基线 F1 / §11 N16）
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        int draining = 0;
        for (DirectSession s : List.copyOf(sessions)) {
            Channel ch = s.channelOrNull();
            if (ch == null || !s.isClosing()) {
                continue;
            }
            draining++;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                ch.closeFuture().awaitUninterruptibly(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            }
        }
        // ② 强关剩余的全部连接（已认证 + 未认证池），在各自的 EventLoop（逻辑线程）上改状态
        List<Channel> closing = new ArrayList<>();
        for (DirectSession s : List.copyOf(sessions)) {
            Channel ch = s.channelOrNull();
            if (ch == null) {
                continue;
            }
            closing.add(ch);
            try {
                ch.eventLoop().execute(() -> s.forceClose(Disconnect.SHUTDOWN));
            } catch (RejectedExecutionException e) {
                ch.close();
            }
        }
        admission.closeAllPreAuth(entry -> {
            Channel ch = entry.attachment();
            if (ch == null) {
                return;
            }
            closing.add(ch);
            try {
                ch.eventLoop().execute(() -> {
                    BattleEdgeHandler handler = ch.pipeline().get(BattleEdgeHandler.class);
                    if (handler != null) {
                        handler.onShutdownClosePreAuth(ch);
                    } else {
                        deps.metrics().disconnect(Disconnect.SHUTDOWN);
                        ch.close();
                    }
                });
            } catch (RejectedExecutionException e) {
                deps.metrics().disconnect(Disconnect.SHUTDOWN);
                ch.close();
            }
        });
        long closeDeadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        for (Channel ch : closing) {
            long remaining = closeDeadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            ch.closeFuture().awaitUninterruptibly(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
        }
        while ((!sessions.isEmpty() || admission.preAuthCount() > 0) && closeDeadline - System.nanoTime() > 0) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // ③ 释放 accept 线程
        if (boss != null) {
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(CLOSE_WAIT.toMillis());
        }
        log.info("battle 直连面已关闭 排空中={} 强关={} 剩余连接={}", draining, closing.size(), connectionCount());
    }

    @Override
    public int connectionCount() {
        return admission.totalConnections();
    }

    // ---------------------------------------------------------------- 包内（handler 与测试）

    /** 装一条连接的 pipeline（服务端与 {@code EmbeddedChannel} 单测共用，线上字节一致）。 */
    void initChannel(Channel ch) {
        BattleEdgeHandler handler = new BattleEdgeHandler(this);
        ChannelPipeline p = ch.pipeline();
        p.addLast("edgeGuard", new EdgeInboundGuard(handler));
        p.addLast("wsMux", new com.game.net.client.WebSocketMuxHandler(
                () -> handler.isAuthenticated()
                        ? com.game.net.client.WebSocketMuxHandler.DEFAULT_MAX_PAYLOAD_BYTES
                        : com.game.net.client.WebSocketMuxHandler.PRE_AUTH_MAX_PAYLOAD_BYTES,
                deps.metrics().webSocketObserver()));
        p.addLast("clientFrameDecoder", new ClientFrameDecoder(ACCEPTED,
                () -> handler.isAuthenticated() ? ClientFrames.DEFAULT_MAX_LEN : ClientFrames.PRE_AUTH_MAX_LEN,
                handler::acceptsInbound,
                (ctx, e) -> handler.onInvalidFrame(ctx.channel(), e)));
        p.addLast("clientFrameEncoder", ClientFrameEncoder.INSTANCE);
        p.addLast("battleEdge", handler);
    }

    boolean isServerSweepActive() {
        return serverSweepActive;
    }

    /** 统一准入判定（关源 -> 总量 -> 未认证池淘汰/预算/早拒 -> 全局 GCRA 速率 -> 承压公网来源限速）。 */
    AdmitOutcome<Channel> tryAdmitChannel(Channel ch) {
        SocketAddress remote = ch.remoteAddress();
        InetAddress inet = remote instanceof InetSocketAddress isa ? isa.getAddress() : null;
        return admission.tryAdmit(inet, ch);
    }

    /** 握手或探测授权通过：登记会话并释放未认证记录（总连接数连续无抖动）。 */
    void registerAuthenticated(DirectSession session, PreAuthEntry<Channel> preAuthEntry) {
        sessions.add(session);
        if (preAuthEntry != null) {
            admission.releasePreAuth(preAuthEntry);
        }
    }

    void releasePreAuth(PreAuthEntry<Channel> entry) {
        admission.releasePreAuth(entry);
    }

    /** 已认证会话断开：移出会话集。 */
    void unregister(DirectSession session) {
        sessions.remove(session);
    }

    /** 仅用于准入拒绝：SO_LINGER=0 发 RST，不留 TIME_WAIT、不回业务包。 */
    static void rejectWithRst(Channel ch) {
        try {
            ch.config().setOption(ChannelOption.SO_LINGER, 0);
        } catch (RuntimeException ignored) {
            // EmbeddedChannel 等非 TCP 传输不支持 SO_LINGER，直接关闭
        }
        ch.close();
    }

    /** 从未认证池表头清扫超时连接（节拍回调与单测共用）。 */
    int sweepPreAuthExpired() {
        return admission.sweepExpired(entry -> {
            Channel ch = entry.attachment();
            if (ch == null) {
                return;
            }
            BattleEdgeHandler handler = ch.pipeline().get(BattleEdgeHandler.class);
            if (handler != null) {
                handler.onSweptHandshakeTimeout(ch, entry);
            } else {
                rejectLog.record(EdgeRejectReason.HANDSHAKE_TIMEOUT, DirectSession.describe(ch.remoteAddress()));
                deps.metrics().disconnect(Disconnect.HANDSHAKE_TIMEOUT);
                ch.close();
            }
        });
    }

    /** 接入的采样日志（首次 + 每 1024 次一行，同基线 {@code edge.cpp:202-209}）。 */
    void sampleAccepted(String peer) {
        long n = acceptedTotal.getAndIncrement();
        if ((n & (EdgeRejectLog.SAMPLE_EVERY - 1)) == 0) {
            log.info("battle 直连接入（采样） latest_peer={} accepted_total={} current={}", peer, n + 1, connectionCount());
        }
    }

    long nanoTime() {
        return nanoClock.getAsLong();
    }

    EdgeRejectLog rejectLog() {
        return rejectLog;
    }

    /** 实际绑定的监听地址（没启动或已停止接客时为 null；测试用）。 */
    InetSocketAddress localAddress() {
        Channel server = serverChannel;
        SocketAddress address = server == null ? null : server.localAddress();
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    private boolean inLogicThread() {
        for (EventExecutor executor : deps.logicGroup()) {
            if (executor.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    /** 直连 I/O 与房间必须是同一条线程（§7.3）：逻辑线程组只许有一个 EventLoop。 */
    private void requireSingleLogicLoop() {
        int loops = 0;
        for (EventExecutor ignored : deps.logicGroup()) {
            loops++;
        }
        if (loops != 1) {
            throw new IllegalStateException("battle 逻辑线程组必须恰好一个 EventLoop（直连 I/O 与房间同一条线程，§7.3），实际 " + loops);
        }
    }
}
