package com.game.gate;

import com.game.api.ClientMessageService;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.common.token.GateTokens;
import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.gate.presence.GatePresence;
import com.game.gate.presence.GatePushSubscriber;
import com.game.gate.link.LinkHellos;
import com.game.gate.link.LinkSettings;
import com.game.gate.link.NettyLinkConnector;
import com.game.gate.link.SceneLinkManager;
import com.game.gate.link.SceneNodeResolver;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.session.ClientDispatcher;
import com.game.gate.session.ClientPipeline;
import com.game.gate.session.GateIdentity;
import com.game.gate.session.GateLimits;
import com.game.gate.session.MessageLimits;
import com.game.gate.session.MessageRoutes;
import com.game.gate.session.SceneEventRouter;
import com.game.gate.session.SessionIdAllocator;
import com.game.gate.session.SessionRegistry;
import com.game.gate.session.TableMessageLimits;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * gate 进程的运行时：节点号租约 → 会话层 / 链路层装配 → 客户端端口 → 节点目录上报；退出时逆序收尾。
 *
 * <p>启动时机是 {@link ApplicationReadyEvent}（Dubbo 在 ContextRefreshedEvent 上完成部署之后），
 * 保证第一个客户端请求到来时 login 引用已就绪。退出在 {@link ContextClosedEvent} 上以最高优先级执行，
 * 赶在 Dubbo 销毁引用之前把断线通知发出去。
 */
public final class GateNode {

    private static final Logger log = LoggerFactory.getLogger(GateNode.class);

    static final Duration LEASE_TTL = Duration.ofSeconds(15);
    static final Duration DIRECTORY_TTL = Duration.ofSeconds(15);
    static final Duration PUBLISH_PERIOD = Duration.ofSeconds(5);
    /** 客户端写缓冲高水位（C++ kClientHighWaterMark = 2MB），越过即断开。 */
    private static final WriteBufferWaterMark CLIENT_WATER_MARK = new WriteBufferWaterMark(1 << 20, 2 << 20);

    private final RedissonClient redis;
    private final MessageIdRegistry messageIdRegistry;
    private final GateTokens tokens;
    private final NodeLinkAuth linkAuth;
    private final ClientMessageService login;
    private final GateProperties properties;
    private final int zoneId;
    private final String advertiseHost;
    private final Path tableDir;
    private final GateMetrics metrics;
    private final String instanceId = UUID.randomUUID().toString();
    private final NodeDirectory<GateNodeInfo> gateDirectory;

    private boolean started;
    private boolean stopped;
    private volatile boolean acceptingStopped;
    private ScheduledExecutorService scheduler;
    private ExecutorService linkResolver;
    private NodeIdLease lease;
    /** 租约丢失回调在调度线程上读它，所以 volatile。 */
    private volatile SessionRegistry registry;
    private SceneLinkManager links;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventLoopGroup linkGroup;
    private volatile Channel serverChannel;
    private volatile ScheduledFuture<?> publishTask;
    /** 本 gate 在玩家在线目录里的条目（gate 是唯一写者）。 */
    private volatile GatePresence presence;
    /** 本 gate 的服务端推送频道订阅。 */
    private volatile GatePushSubscriber pushSubscriber;

    /**
     * @param linkAuth gate → scene 链路握手鉴权（密钥来自环境变量 {@code XM_NODE_LINK_SECRET}，须与 scene 一致）
     * @param tableDir 配置表目录（只读 MessageLimiter 表做按消息号限频）
     * @param metrics  gate 指标（会话层与链路层共用一份）
     */
    public GateNode(RedissonClient redis, MessageIdRegistry messageIdRegistry, GateTokens tokens, NodeLinkAuth linkAuth,
                    ClientMessageService login, GateProperties properties, int zoneId, String advertiseHost, Path tableDir,
                    GateMetrics metrics) {
        this.redis = redis;
        this.messageIdRegistry = messageIdRegistry;
        this.tokens = tokens;
        this.linkAuth = linkAuth;
        this.login = login;
        this.properties = properties;
        this.zoneId = zoneId;
        this.advertiseHost = advertiseHost;
        this.tableDir = tableDir;
        this.metrics = metrics;
        this.gateDirectory = new NodeDirectory<>(redis, NodeTypes.GATE, GateNodeInfo.parser());
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            doStart();
        } catch (RuntimeException e) {
            log.error("gate 启动失败，释放已占用的资源", e);
            stop();
            throw e;
        }
    }

    private void doStart() {
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("gate-bg").daemon(true).factory());
        lease = NodeIdLease.acquire(redis, scheduler, NodeTypes.GATE, zoneId,
                SessionIdAllocator.MIN_NODE_ID, SessionIdAllocator.MAX_NODE_ID, instanceId, LEASE_TTL, this::onLeaseLost);
        GateIdentity identity = new GateIdentity(lease.nodeId(), instanceId, zoneId);
        registry = new SessionRegistry(new SessionIdAllocator(identity.nodeId()));
        presence = new GatePresence(new PlayerPresenceDirectory(redis), zoneId, identity.nodeId(), instanceId,
                System::currentTimeMillis);

        // scene 链路：寻址查 Redis（阻塞）放在单独线程，连接放在链路 I/O 线程。
        linkGroup = new NioEventLoopGroup(properties.linkThreads(), new DefaultThreadFactory("gate-link"));
        linkResolver = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("gate-link-resolve").daemon(true).factory());
        NodeDirectory<SceneNodeInfo> sceneDirectory = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        SceneNodeResolver resolver = nodeId -> sceneDirectory.list(zoneId).stream()
                .filter(info -> info.getNodeId() == nodeId)
                .findFirst();
        // 节点号租约无效（丢失或续期滞后）时不新建 scene 链路：scene 按 gate 节点号登记链路，一个可能已归别人的号
        // 建链会顶掉新持有者的链路。握手帧带上本次占号的防护代次，scene 拒绝代次更低的旧持有者。
        NodeIdLease heldLease = lease;
        links = new SceneLinkManager(
                new LinkHellos(identity.nodeId(), instanceId, zoneId, heldLease.leaseEpoch(), linkAuth, InstantSource.system()),
                new NettyLinkConnector(resolver, linkResolver, linkGroup, properties.linkConnectTimeout()),
                new LinkSettings(properties.linkHelloTimeout(), properties.linkMaxQueuedFrames()),
                heldLease::isValid, metrics);

        int tipMessageId = messageIdRegistry.requireId("SceneClientPlayerCommon", "SendTipToClient");
        MessageLimits messageLimits = TableMessageLimits.load(tableDir);
        ClientDispatcher dispatcher = new ClientDispatcher(identity, tokens, InstantSource.system(),
                MessageRoutes.of(messageIdRegistry), tipMessageId, login, links, registry,
                new GateLimits(properties.maxPendingRequests(), properties.illegalPacketThreshold(), properties.handshakeTimeout(),
                        messageLimits), metrics, presence);
        links.bindListener(new SceneEventRouter(registry, dispatcher));
        // 服务端 → 玩家推送：订阅本 gate 的频道（任何服务按在线目录找到本 gate 后发布到这里）。
        pushSubscriber = new GatePushSubscriber(
                redis.getTopic(RedisKeys.gatePushTopic(zoneId, identity.nodeId()), ByteArrayCodec.INSTANCE),
                instanceId, registry, dispatcher, metrics);
        pushSubscriber.start();
        presence.start(scheduler);
        // 状态量由抓取线程读：会话表与链路表都是并发容器，size() 线程安全、不阻塞。
        metrics.bindSessionCount(registry::size);
        metrics.bindSceneLinkCount(links::linkCount);

        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("gate-accept"));
        workerGroup = new NioEventLoopGroup(properties.workerThreads(), new DefaultThreadFactory("gate-io"));
        SessionRegistry sessions = registry;
        serverChannel = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, CLIENT_WATER_MARK)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ClientPipeline.install(ch.pipeline(), sessions, dispatcher);
                    }
                })
                .bind(properties.clientPort())
                .syncUninterruptibly()
                .channel();
        log.info("gate 已启动 zone={} node_id={} instance={} 客户端端口={} 通告地址={}:{}",
                zoneId, identity.nodeId(), instanceId, properties.clientPort(), advertiseHost, properties.advertisePort());

        publishTask = scheduler.scheduleAtFixedRate(this::publish, 0, PUBLISH_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 每 5s 刷新一次节点目录条目（TTL 15s）。在后台线程上执行，阻塞 Redis 调用不碰 I/O 线程。 */
    private void publish() {
        if (acceptingStopped) {
            return;
        }
        GateNodeInfo info = GateNodeInfo.newBuilder()
                .setZoneId(zoneId)
                .setNodeId(lease.nodeId())
                .setInstanceId(instanceId)
                .setClientHost(advertiseHost)
                .setClientPort(properties.advertisePort())
                .setPlayerCount(registry.size())
                .setDraining(false)
                .build();
        try {
            gateDirectory.publish(zoneId, lease.nodeId(), info, DIRECTORY_TTL);
        } catch (RuntimeException e) {
            log.warn("刷新 gate 节点目录失败，下一轮重试 node_id={}", lease.nodeId(), e);
        }
    }

    /**
     * 节点号被夺或续期失败超过 TTL（fail-closed）：停止接客（关监听、停上报），不再新建 scene 链路（SceneLinkManager 按租约
     * 有效性拒绝），并关闭全部现有会话——它们的会话号带着这个节点号作高位，新持有者会发出同样的会话号。
     * 各会话照常走断线流程：经仍然就绪的旧链路让 scene 放掉玩家（写回），并通知 login。
     * 不主动摘目录条目：这个号可能已归新持有者，条目键相同，删了会误删对方刚上报的条目；旧条目最多一个 TTL 后自然过期。
     */
    private void onLeaseLost() {
        SessionRegistry sessions = registry;
        log.error("gate 节点号租约丢失：停止接受新连接、不再新建 scene 链路、关闭全部会话 node_id={} instance={} 会话数={}",
                lease.nodeId(), instanceId, sessions == null ? 0 : sessions.size());
        stopAccepting(false);
        stopPush();
        if (sessions != null) {
            sessions.closeAll();
        }
        stopPresence();
    }

    private void stopAccepting(boolean removeDirectoryEntry) {
        acceptingStopped = true;
        ScheduledFuture<?> task = publishTask;
        if (task != null) {
            task.cancel(false);
        }
        Channel server = serverChannel;
        if (server != null) {
            server.close().syncUninterruptibly();
        }
        if (removeDirectoryEntry && lease != null && !lease.isLost()) {
            try {
                gateDirectory.remove(zoneId, lease.nodeId());
            } catch (RuntimeException e) {
                log.warn("从节点目录摘除 gate 失败（TTL 到期后自动消失） node_id={}", lease.nodeId(), e);
            }
        }
    }

    /** 赶在 Dubbo 销毁 login 引用之前收尾，断线通知才发得出去。 */
    @EventListener(ContextClosedEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onContextClosed() {
        stop();
    }

    /** 逆序收尾（幂等）：停接客 → 关会话并等收尾 → 关链路 → 关线程 → 释放节点号。 */
    public synchronized void stop() {
        if (!started || stopped) {
            return;
        }
        stopped = true;
        log.info("gate 正在退出");
        stopAccepting(true);
        stopPush();
        if (registry != null) {
            registry.closeAll();
            awaitSessionsDrained(properties.shutdownDrainTimeout());
        }
        stopPresence();
        if (links != null) {
            links.close();
        }
        shutdownGroup(workerGroup);
        shutdownGroup(bossGroup);
        shutdownGroup(linkGroup);
        if (lease != null) {
            lease.close();
        }
        if (linkResolver != null) {
            linkResolver.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.info("gate 已退出");
    }

    private void stopPush() {
        GatePushSubscriber subscriber = pushSubscriber;
        if (subscriber != null) {
            subscriber.stop();
        }
    }

    /** 停续期并撤销剩下的在线目录条目（正常收尾的会话已在断线流程里撤销自己的）。 */
    private void stopPresence() {
        GatePresence p = presence;
        if (p != null) {
            p.stop();
        }
    }

    private void awaitSessionsDrained(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (registry.size() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
        if (registry.size() > 0) {
            log.warn("退出时仍有 {} 个会话未收尾完（在途 login 调用未返回），放弃等待", registry.size());
        }
    }

    private static void shutdownGroup(EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
