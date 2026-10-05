package com.game.battle.edge;

import com.game.battle.BattleProperties;
import com.game.battle.metrics.BattleMetrics.Disconnect;
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
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p><b>pipeline</b>（每连接）：{@link EdgeInboundGuard}（关闭中丢弃入站字节）→ xm-net {@link ClientFrameDecoder}（上行只收
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
            ClientRequest.getDescriptor().getFullName(), ClientRequest.getDefaultInstance());

    /** 停机时等强关完成、等 accept 线程退出的上限（只为不无限阻塞）。 */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(2);

    private final EdgeDependencies deps;
    private final LongSupplier nanoClock;
    private final AtomicInteger connections = new AtomicInteger();
    private final Set<DirectSession> sessions = ConcurrentHashMap.newKeySet();
    private final EdgeRejectLog rejectLog = new EdgeRejectLog();
    private final AtomicLong acceptedTotal = new AtomicLong();

    private final Object lifecycle = new Object();
    private EventLoopGroup bossGroup;
    private volatile Channel serverChannel;
    private boolean started;
    private boolean drained;

    public BattleEdgeServer(EdgeDependencies deps) {
        this(deps, System::nanoTime);
    }

    /** @param nanoClock 单调时钟（限频窗口；测试注入） */
    BattleEdgeServer(EdgeDependencies deps, LongSupplier nanoClock) {
        this.deps = Objects.requireNonNull(deps, "deps");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
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
            EventLoopGroup boss = new NioEventLoopGroup(1, new DefaultThreadFactory("battle-accept"));
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, deps.logicGroup())
                    .channel(NioServerSocketChannel.class)
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
            log.info("battle 直连面已监听 {} max_connections={} handshake_timeout={} illegal_packet_threshold={}",
                    bind.channel().localAddress(), p.effectiveMaxConnections(), p.handshakeTimeout(), p.illegalPacketThreshold());
        }
    }

    @Override
    public void stopAccepting() {
        Channel server = serverChannel;
        if (server == null) {
            return;
        }
        serverChannel = null;
        ChannelFuture closed = server.close();
        if (!inLogicThread()) {
            closed.awaitUninterruptibly(CLOSE_WAIT.toMillis());
        }
        log.info("battle 直连面停止接受新连接 {} 现有连接={}", server.localAddress(), connections.get());
    }

    @Override
    public void drainAndClose(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (inLogicThread()) {
            throw new IllegalStateException("drainAndClose 会阻塞等待逻辑线程写完输出，不得在逻辑线程上调用");
        }
        EventLoopGroup boss;
        synchronized (lifecycle) {
            if (drained) {
                return;
            }
            drained = true;
            boss = bossGroup;
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
        // ② 强关剩余的全部连接（未验证、空闲、排空超时的），在各自的 EventLoop（逻辑线程）上改状态
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
        long closeDeadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        for (Channel ch : closing) {
            long remaining = closeDeadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            ch.closeFuture().awaitUninterruptibly(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
        }
        // closeFuture 在 channelInactive 之前就完成（Netty 先置关闭、再排队触发 inactive），名额是在 inactive 里由 unregister 归还的：
        // 同一个截止时刻内再等会话真正摘掉，返回之后 connectionCount 才可信（慢机器上曾返回时还剩 2 条，GitHub Actions 偶发）。
        // 本方法不在逻辑线程上（开头已拒），短睡不会挡住要执行的 inactive
        while (!sessions.isEmpty() && closeDeadline - System.nanoTime() > 0) {
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
        log.info("battle 直连面已关闭 排空中={} 强关={} 剩余连接={}", draining, closing.size(), connections.get());
    }

    @Override
    public int connectionCount() {
        return connections.get();
    }

    // ---------------------------------------------------------------- 包内（handler 与测试）

    /** 装一条连接的 pipeline（服务端与 {@code EmbeddedChannel} 单测共用，线上字节一致）。 */
    void initChannel(Channel ch) {
        BattleEdgeHandler handler = new BattleEdgeHandler(this);
        ChannelPipeline p = ch.pipeline();
        p.addLast("edgeGuard", new EdgeInboundGuard(handler));
        p.addLast("clientFrameDecoder", new ClientFrameDecoder(ACCEPTED, ClientFrames.DEFAULT_MAX_LEN, handler::acceptsInbound,
                (ctx, e) -> handler.onInvalidFrame(ctx.channel(), e)));
        p.addLast("clientFrameEncoder", ClientFrameEncoder.INSTANCE);
        p.addLast("battleEdge", handler);
    }

    /** 闸 G1：连接数（含未握手）没到有效上限就占一个名额（配置 0 时取硬上限 65535）。 */
    boolean tryAdmit() {
        int max = deps.properties().effectiveMaxConnections();
        while (true) {
            int current = connections.get();
            if (current >= max) {
                return false;
            }
            if (connections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void register(DirectSession session) {
        sessions.add(session);
    }

    /** 连接断开：归还 {@link #tryAdmit()} 占的名额。 */
    void unregister(DirectSession session) {
        if (sessions.remove(session)) {
            connections.decrementAndGet();
        }
    }

    /** 接入的采样日志（首次 + 每 1024 次一行，同基线 {@code edge.cpp:202-209}）。 */
    void sampleAccepted(DirectSession session) {
        long n = acceptedTotal.getAndIncrement();
        if ((n & (EdgeRejectLog.SAMPLE_EVERY - 1)) == 0) {
            log.info("battle 直连接入（采样） latest_peer={} accepted_total={} current={}", session.peer(), n + 1, connections.get());
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
