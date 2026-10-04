package com.game.scene;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.audit.AuditProperties;
import com.game.audit.AuditTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.common.RunMode;
import com.game.common.id.Snowflake;
import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.player.store.PlayerStore;
import com.game.scene.attribute.AttributeFeature;
import com.game.scene.attribute.AttributeService;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.AuditPipeline;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.audit.KafkaAssetAudit;
import com.game.scene.audit.KafkaPlayerSnapshots;
import com.game.scene.currency.CurrencyFeature;
import com.game.scene.currency.CurrencyService;
import com.game.scene.discovery.SceneDirectoryPublisher;
import com.game.scene.gainblock.GainBlockSync;
import com.game.scene.gainblock.RedisGainBlockSource;
import com.game.scene.id.SceneGuids;
import com.game.scene.link.GateLinks;
import com.game.scene.link.LinkIdentity;
import com.game.scene.link.NodeLinkHandler;
import com.game.scene.link.NodeLinkServer;
import com.game.scene.link.SceneLinkService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.mission.ActivityFeature;
import com.game.scene.mission.MissionFeature;
import com.game.scene.mission.MissionService;
import com.game.scene.mission.MissionTables;
import com.game.scene.skill.SkillFeature;
import com.game.scene.skill.SkillService;
import com.game.scene.skill.SkillTables;
import com.game.scene.ownership.OwnerLeaseRenewer;
import com.game.scene.ownership.OwnerTakeoverSubscriber;
import com.game.scene.player.ItemGuids;
import com.game.scene.storage.StoragePlayerRepository;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.SceneClock;
import com.game.scene.world.SceneMessageIds;
import com.game.scene.world.SceneTables;
import com.game.scene.world.SceneTicker;
import com.game.scene.world.SceneWorld;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 场景节点的装配与生命周期（组合根）。启动、停止的顺序都写在这里，别处不隐式创建线程。
 *
 * <p>线程：
 * <ul>
 *   <li>{@code scene-logic}（1 个 {@link DefaultEventLoop}）：唯一拥有场景 / 玩家 / 链路登记表的线程；
 *       场景帧（20 FPS，{@link SceneTicker}）也以定时任务跑在它上面，与客户端消息串行，不需要锁；</li>
 *   <li>{@code scene-link-*}：Netty 链路 I/O，只做编解码与握手，事件投递到逻辑线程（每条链路有积压上限，见 NodeLinkHandler）；</li>
 *   <li>{@code scene-storage}：有界线程池，执行 MySQL 阻塞调用（加载、写回、释放、续约），结果投递回逻辑线程；</li>
 *   <li>{@code scene-sched}：节点号续租、节点目录发布、归属续约的调度（Redis I/O；MySQL 交给存储线程池）。</li>
 * </ul>
 *
 * <p>节点号租约丢失时（号可能已被别的实例占用，雪花号与目录条目都会撞）：停止刷新目录、关闭监听、
 * 拒绝新进场；已在场玩家继续服务直到离开，由运维决定何时重启。
 *
 * <p>指标（{@link SceneMetrics}，architecture.md §11）：这里绑定逻辑线程队列长度、gate 链路连接数与存储线程池的状态量，
 * 并给经 {@link #runOnLogic} 投递的每个逻辑任务计排队与执行耗时；其余指标由各组件自己记。
 */
public class SceneNode implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SceneNode.class);

    /** 节点号即雪花 worker（10 位），0 保留不用。 */
    static final int MIN_NODE_ID = 1;
    static final int MAX_NODE_ID = Snowflake.MAX_WORKER;
    static final Duration LEASE_TTL = Duration.ofSeconds(15);
    /** 全服发号租约的作用域：0 = 全服（不按 zone 分，见 NodeTypes.SCENE_GUID）。 */
    static final int GUID_LEASE_SCOPE = 0;
    /** Kafka 不可达时重试核对审计 topic 的间隔。 */
    static final long AUDIT_REVERIFY_SECONDS = 30;
    /** 其他线程同步等待逻辑线程执行一个任务的上限（目录快照、归属快照、启动建场景）。停服写回不用它，用整个停服预算。 */
    private static final long LOGIC_CALL_TIMEOUT_MS = 5_000;

    private final SceneNodeProperties props;
    private final RedissonClient redis;
    private final PlayerStore playerStore;
    private final MessageIdRegistry registry;
    private final SceneTables tables;
    private final AttributeTables attributeTables;
    private final BagTables bagTables;
    private final MissionTables missionTables;
    private final SkillTables skillTables;
    private final NodeLinkAuth linkAuth;
    private final SceneMetrics metrics;
    private final AuditProperties audit;
    private final String instanceId = UUID.randomUUID().toString();
    /** 最近一次目录快照里的在线人数（停服写回没能执行时报告用，任意线程可读）。 */
    private final AtomicInteger approxPlayers = new AtomicInteger();

    // 以下在 start() 里依序创建、release() 里逆序释放；租约丢失回调在调度线程上读取，所以都是 volatile。
    private volatile ScheduledExecutorService scheduler;
    private volatile NodeIdLease lease;
    private volatile NodeIdLease guidLease;
    private volatile AuditPipeline auditPipeline;
    private volatile DefaultEventLoop logicLoop;
    private volatile ThreadPoolExecutor storageExecutor;
    private volatile GateLinks links;
    private volatile SceneWorld world;
    private volatile ScheduledFuture<?> frameTask;
    private volatile ScheduledFuture<?> saveTask;
    private volatile NodeLinkServer linkServer;
    private volatile SceneDirectoryPublisher publisher;
    private volatile OwnerLeaseRenewer leaseRenewer;
    private volatile OwnerTakeoverSubscriber takeoverSubscriber;
    private volatile GainBlockSync gainBlockSync;
    private volatile int gainBlockListener = -1;
    private volatile boolean running;

    /**
     * @param attributeTables 属性加点配表视图（与 {@code tables} 来自同一份配表快照）
     * @param bagTables       背包配表视图（同上）
     * @param missionTables   任务配表视图（同上）
     * @param skillTables     技能配表视图（同上）
     * @param audit    资产审计管线配置（Kafka）
     * @param linkAuth gate 链路握手鉴权（密钥来自环境变量 {@code XM_NODE_LINK_SECRET}，须与 gate 一致）
     * @param metrics  scene 指标（各组件共用一份）
     */
    public SceneNode(SceneNodeProperties props, RedissonClient redis, PlayerStore playerStore,
                     MessageIdRegistry registry, SceneTables tables, AttributeTables attributeTables, BagTables bagTables,
                     MissionTables missionTables, SkillTables skillTables, NodeLinkAuth linkAuth, SceneMetrics metrics,
                     AuditProperties audit) {
        this.props = props;
        this.redis = redis;
        this.playerStore = playerStore;
        this.registry = registry;
        this.tables = tables;
        this.attributeTables = attributeTables;
        this.bagTables = bagTables;
        this.missionTables = missionTables;
        this.skillTables = skillTables;
        this.linkAuth = linkAuth;
        this.metrics = metrics;
        this.audit = audit;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            doStart();
            running = true;
        } catch (Exception e) {
            release();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("场景节点启动失败", e);
        }
    }

    private void doStart() throws Exception {
        SceneNodeProperties.SceneSettings settings = props.scene();
        int zoneId = props.zoneId();
        SceneMessageIds ids = SceneMessageIds.resolve(registry);

        scheduler = Executors.newScheduledThreadPool(2, new DefaultThreadFactory("scene-sched", true));
        lease = NodeIdLease.acquire(redis, scheduler, NodeTypes.SCENE, zoneId, MIN_NODE_ID, MAX_NODE_ID, instanceId,
                LEASE_TTL, this::onLeaseLost);
        int nodeId = lease.nodeId();
        Snowflake snowflake = new Snowflake(nodeId);

        logicLoop = new DefaultEventLoop(new DefaultThreadFactory("scene-logic"));
        // 状态量回调读 volatile 字段（抓取线程上调用，线程安全、不阻塞），组件还没建出来或已释放时报 0。
        metrics.bindLogicQueue(() -> {
            DefaultEventLoop loop = logicLoop;
            return loop == null ? 0 : loop.pendingTasks();
        });
        Executor logic = this::runOnLogic;
        storageExecutor = new ThreadPoolExecutor(settings.storageThreads(), settings.storageThreads(),
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(settings.storageQueueCapacity()),
                new DefaultThreadFactory("scene-storage"), new ThreadPoolExecutor.AbortPolicy());
        metrics.bindStorageExecutor(storageExecutor);
        StoragePlayerRepository repository = new StoragePlayerRepository(playerStore, storageExecutor, logic, metrics);

        GateLinks gateLinks = new GateLinks(metrics);
        GainAnomalyDetector anomalies = new GainAnomalyDetector(settings.anomaly().defaults(),
                settings.anomaly().currencyThresholds(), settings.anomaly().itemThresholds(), SceneClock.SYSTEM, metrics);
        SceneGuids sceneGuids = acquireSceneGuids();
        AssetAudit assetAudit = startAudit(zoneId, nodeId, settings, sceneGuids);
        CurrencyService currency = new CurrencyService(assetAudit, anomalies, metrics);
        BagService bags = new BagService(bagTables, itemGuids(sceneGuids), assetAudit, anomalies, metrics);
        startGainBlockSync(currency, bags, settings);
        AttributeService attributes = new AttributeService(attributeTables, SceneClock.SYSTEM, currency);
        MissionService missions = new MissionService(missionTables, bags, SceneClock.SYSTEM);
        SkillService skills = new SkillService(skillTables, SceneClock.SYSTEM, metrics, ids);
        AuditPipeline pipeline = auditPipeline;
        PlayerSnapshots snapshots = pipeline == null ? PlayerSnapshots.NONE
                : new KafkaPlayerSnapshots(pipeline, SceneClock.SYSTEM, zoneId);
        // 进场景前的规整：先背包（坏档拒绝进场），再属性，最后重建任务索引
        SceneWorld sceneWorld = new SceneWorld(tables, ids, gateLinks, repository, snowflake::nextId,
                SceneClock.SYSTEM, metrics, player -> {
                    bags.initializeOnLoad(player);
                    attributes.initializeOnLoad(player);
                    missions.initializeOnLoad(player);
                }, snapshots);
        links = gateLinks;
        world = sceneWorld;
        RunMode runMode = RunMode.parse(props.runMode());
        if (!RunMode.isRecognized(props.runMode())) {
            log.warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（GM 指令拒绝）: '{}'", props.runMode());
        }
        ClientRequestHandler requests = new ClientRequestHandler(sceneWorld, registry, ids, runMode,
                List.of(new CurrencyFeature(currency), new AttributeFeature(attributes, registry, missions::onLevelChanged),
                        new BagFeature(bags), new MissionFeature(missions), new ActivityFeature(missions),
                        new SkillFeature(skills)));
        log.info("场景请求分发就绪 运行模式={}（GM 指令{}）", runMode, runMode.allowsGmCommands() ? "放行" : "拒绝");
        callOnLogic(() -> {
            tables.worldSceneConfigIds().forEach(sceneWorld::createScene);
            return null;
        });
        // 场景帧：固定周期触发，SceneTicker 按单调时钟补帧（每次最多 5 帧），帧内异常只记日志、不让定时任务停掉。
        SceneTicker ticker = new SceneTicker(sceneWorld::step, SceneClock.SYSTEM::nanoTime);
        long periodNanos = SceneTicker.STEP_NANOS;
        frameTask = logicLoop.scheduleAtFixedRate(ticker, periodNanos, periodNanos, TimeUnit.NANOSECONDS);
        // 周期存盘：每秒一个槽（同在逻辑线程上，与帧和客户端消息串行）；周期为 0 = 关闭（只在离场时写回）。
        int saveIntervalSeconds = (int) settings.saveInterval().toSeconds();
        if (saveIntervalSeconds > 0) {
            saveTask = logicLoop.scheduleAtFixedRate(() -> {
                try {
                    sceneWorld.saveDuePlayers(saveIntervalSeconds);
                } catch (RuntimeException e) {
                    log.error("周期存盘这一秒出错，下一秒照常", e);
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        // 归属：接管请求（login → 全部 scene）与续约。都要在接受链路之前就绪：进场的玩家一上来就需要续约、可被接管。
        takeoverSubscriber = new OwnerTakeoverSubscriber(
                redis.getTopic(RedisKeys.ownerTakeoverTopic(), ByteArrayCodec.INSTANCE),
                (playerId, epoch) -> postToLogic(() -> sceneWorld.onTakeoverRequested(playerId, epoch)));
        takeoverSubscriber.start();
        leaseRenewer = new OwnerLeaseRenewer(() -> callOnLogic(sceneWorld::ownedPlayers), playerStore, storageExecutor,
                lost -> postToLogic(() -> sceneWorld.onOwnershipLost(lost)));
        leaseRenewer.start(scheduler);

        LinkIdentity identity = new LinkIdentity(nodeId, instanceId, zoneId);
        SceneLinkService linkService = new SceneLinkService(identity, gateLinks, sceneWorld, requests, metrics);
        AtomicLong linkIds = new AtomicLong();
        Duration handshakeTimeout = settings.linkHandshakeTimeout();
        int maxPendingFrames = settings.linkMaxPendingFrames();
        linkServer = new NodeLinkServer(settings.linkIoThreads());
        metrics.bindGateLinkCount(() -> {
            NodeLinkServer server = linkServer;
            return server == null ? 0 : server.connectionCount();
        });
        int linkPort = linkServer.start(settings.linkBindHost(), settings.linkPort(),
                () -> new NodeLinkHandler(identity, linkAuth, InstantSource.system(), linkService, logic, linkIds,
                        handshakeTimeout, maxPendingFrames, metrics));

        SceneNodeInfo info = SceneNodeInfo.newBuilder()
                .setZoneId(zoneId)
                .setNodeId(nodeId)
                .setInstanceId(instanceId)
                .setLinkHost(props.advertiseHost())
                .setLinkPort(linkPort)
                .build();
        publisher = new SceneDirectoryPublisher(new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser()), info,
                () -> callOnLogic(() -> {
                    List<SceneEntry> entries = sceneWorld.sceneEntries();
                    approxPlayers.set(entries.stream().mapToInt(SceneEntry::getPlayerCount).sum());
                    return entries;
                }));
        publisher.start(scheduler);

        log.info("场景节点已启动 zone={} node_id={} instance={} link={}:{} 场景数={}", zoneId, nodeId, instanceId,
                props.advertiseHost(), linkPort, tables.worldSceneConfigIds().size());
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("场景节点停止中");
        release();
        log.info("场景节点已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 按启动的逆序释放，每一步都容忍前面没建出来（启动失败时也走这里）：
     * 摘目录 → 停监听 → 停封禁名单同步、接管订阅与续约 → 断开全部 gate 链路（不再有新帧进来，逻辑线程的积压只减不增）
     * → 逻辑线程上写回全部玩家并关链路，写回提交之后才关存储线程池并等写回落库（{@link SceneShutdown}，共用一个停服预算）
     * → 关线程 → 最后才释放节点号（写回期间号仍归本实例，别的实例拿不到同一个号）。
     */
    private void release() {
        ScheduledFuture<?> saves = saveTask;
        if (saves != null) {
            // 第一步就停周期存盘：最终写回由下面的停服写回统一做，不让新提交的在线存盘排在停服写回前面占停服预算
            // （SceneWorld.shutdown 也会挡掉之后到期的槽）。
            saves.cancel(false);
        }
        SceneDirectoryPublisher p = publisher;
        NodeIdLease l = lease;
        if (p != null) {
            p.stop(l != null && !l.isLost());
        }
        NodeLinkServer server = linkServer;
        if (server != null) {
            server.stopAccepting();
        }
        stopGainBlockSync();
        OwnerTakeoverSubscriber subscriber = takeoverSubscriber;
        if (subscriber != null) {
            subscriber.stop();
        }
        OwnerLeaseRenewer renewer = leaseRenewer;
        if (renewer != null) {
            renewer.stop();
        }
        if (server != null) {
            server.closeLinks();
        }
        ScheduledFuture<?> frames = frameTask;
        if (frames != null) {
            // 停帧：写回之后不再外推（写回任务与帧同在逻辑线程上串行，取消只是省掉之后的空帧）。
            frames.cancel(false);
        }
        SceneWorld w = world;
        GateLinks g = links;
        ThreadPoolExecutor storage = storageExecutor;
        DefaultEventLoop loop = logicLoop;
        if (loop != null && w != null && g != null && storage != null) {
            SceneShutdown.Result result = SceneShutdown.writeBackThenDrainStorage(loop, () -> {
                int count = w.shutdown();
                g.closeAll();
                return count;
            }, storage, props.scene().shutdownSaveTimeout(), approxPlayers::get, System::nanoTime);
            log.info("停服写回 已执行={} 人数={} 丢弃存储任务={}", result.writeBackRan(), result.playersSubmitted(),
                    result.droppedTasks());
        } else if (storage != null) {
            storage.shutdownNow();
        }
        if (server != null) {
            server.shutdown();
        }
        if (loop != null) {
            loop.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        // 写回之后（逻辑线程已停、不再产生审计记录）才发完审计队列，最后交还发号租约：反过来别的实例可能拿到同一个 worker、发重号
        AuditPipeline pipeline = auditPipeline;
        if (pipeline != null) {
            pipeline.close(props.scene().auditFlushTimeout());
        }
        NodeIdLease guids = guidLease;
        if (guids != null) {
            guids.close();
        }
        if (l != null) {
            l.close();
        }
        ScheduledExecutorService sched = scheduler;
        if (sched != null) {
            sched.shutdownNow();
        }
    }

    /**
     * 全服发号：占全服范围的号段租约（{@code NodeTypes.SCENE_GUID}），只建一个 {@link SceneGuids}，物品 uuid、资产流水号、快照号
     * 共用（两个同 worker 的雪花实例会发出相同的号）。不论审计开不开都要：物品入包离不开它。
     */
    private SceneGuids acquireSceneGuids() {
        NodeIdLease guids = NodeIdLease.acquire(redis, scheduler, NodeTypes.SCENE_GUID, GUID_LEASE_SCOPE, 1,
                Snowflake.MAX_WORKER, instanceId, LEASE_TTL,
                () -> log.error("全服发号租约丢失：物品入包发不出号（回 6004）、资产流水改写兜底日志，请尽快重启本节点"));
        guidLease = guids;
        log.info("全服发号就绪 worker={}", guids.nodeId());
        return new SceneGuids(new Snowflake(guids.nodeId()), guids::isValid);
    }

    /** 物品 guid：一次铸齐一批，任何一个发不出来就整批放弃（调用方零写入地回 6004）。 */
    private static ItemGuids itemGuids(SceneGuids guids) {
        return count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                OptionalLong id = guids.tryNext();
                if (id.isEmpty()) {
                    return null;
                }
                out[i] = id.getAsLong();
            }
            return out;
        };
    }

    /**
     * 资产审计：建审计管线并核对 topic（号用全服发号）。分区契约不符抛出（拒绝启动）；Kafka 不可达（含地址解析不了）
     * 时启动线程最多等 {@code xm.audit.init-timeout} 后告警并照常启动，后台每 30 秒重试，期间流水完整写兜底日志。关闭审计（{@code xm.audit.enabled=false}）时只写本地审计日志。
     */
    private AssetAudit startAudit(int zoneId, int nodeId, SceneNodeProperties.SceneSettings settings, SceneGuids guids) {
        if (!audit.enabled()) {
            log.warn("资产流水未接 Kafka（xm.audit.enabled=false），只写本地日志 {}", AssetAudit.LOGGER);
            return AssetAudit.log();
        }
        String clientId = "xm-scene-audit-z" + zoneId + "-n" + nodeId;
        int generation = audit.topicGeneration();
        AuditPipeline pipeline = new AuditPipeline(
                () -> AuditPipeline.kafkaProducer(audit.bootstrapServers(), clientId, settings.auditMaxBlock()),
                () -> new KafkaTopicAdmin(audit.bootstrapServers(), clientId + "-admin"),
                AuditTopics.all(generation), AuditTopics.transactionLog(generation).name(),
                AuditTopics.playerSnapshot(generation).name(), settings.snapshotMaxBytes(), audit.replicationFactor(),
                audit.initTimeout(), guids,
                settings.auditQueueCapacity(), metrics);
        auditPipeline = pipeline;
        // 第一次核对在启动线程上同步做（分区契约不符才能拒绝启动）；Kafka 不可达时最多等 init-timeout 后照常启动。
        // 之后每 30 秒一次：没核对通过（或生产者进入致命状态被丢弃）就在审计线程上重试，已通过时立即返回
        pipeline.verifyNow();
        scheduler.scheduleWithFixedDelay(pipeline::requestVerify, AUDIT_REVERIFY_SECONDS, AUDIT_REVERIFY_SECONDS,
                TimeUnit.SECONDS);
        log.info("资产审计就绪 kafka={} topic 代次={} topic 已核对={}", audit.bootstrapServers(), generation,
                pipeline.verified());
        return new KafkaAssetAudit(pipeline, SceneClock.SYSTEM, zoneId);
    }

    /** 租约丢失回调（调度线程上，只调一次）。 */
    private void onLeaseLost() {
        log.error("场景节点号租约丢失：停止刷新节点目录、关闭链路监听、拒绝新进场；在场玩家继续服务至离开，请尽快重启本节点");
        SceneDirectoryPublisher p = publisher;
        if (p != null) {
            p.stop(false);
        }
        NodeLinkServer server = linkServer;
        if (server != null) {
            server.stopAccepting();
        }
        SceneWorld w = world;
        if (w != null) {
            postToLogic(w::stopAcceptingEnters);
        }
    }

    /**
     * 全服产出封禁名单：先起同步线程并订阅变更通知，再同步读第一份（读不到就拒绝启动）——这样读的同时来的变更不会漏。
     * 名单投递到逻辑线程换上；第一份在接受 gate 链路之前就排进逻辑线程，所以第一个玩家请求到来时名单已生效。
     */
    private void startGainBlockSync(CurrencyService currency, BagService bags, SceneNodeProperties.SceneSettings settings) {
        GainBlockSync sync = new GainBlockSync(new RedisGainBlockSource(redis), blocks -> postToLogic(() -> {
            currency.applyGlobalBlocks(blocks);
            bags.applyGlobalBlocks(blocks);
        }), metrics, SceneClock.SYSTEM::nanoTime);
        gainBlockSync = sync;
        // 状态量经 volatile 字段读当前实例（同 bindLogicQueue）：节点停了再起也不会读到旧实例
        metrics.bindGainBlocks(() -> {
            GainBlockSync s = gainBlockSync;
            return s == null ? 0 : s.entries();
        }, () -> {
            GainBlockSync s = gainBlockSync;
            return s == null ? Double.NaN : s.syncAgeSeconds();
        });
        sync.start(settings.gainBlockRefresh());
        gainBlockListener = redis.getTopic(RedisKeys.gainBlockChangedTopic(), StringCodec.INSTANCE)
                .addListener(String.class, (channel, category) -> sync.requestRefresh());
        sync.loadNow();
    }

    /** 先停同步线程（中断可能卡在 Redis 上的重读），再取消订阅；之后迟到的通知遇到已停的同步直接忽略。 */
    private void stopGainBlockSync() {
        GainBlockSync sync = gainBlockSync;
        if (sync != null) {
            sync.stop();
        }
        int listener = gainBlockListener;
        if (listener >= 0) {
            try {
                redis.getTopic(RedisKeys.gainBlockChangedTopic(), StringCodec.INSTANCE).removeListener(listener);
            } catch (RuntimeException e) {
                log.warn("取消订阅全服产出封禁变更通知失败", e);
            }
            gainBlockListener = -1;
        }
    }

    /** 投递到逻辑线程；逻辑线程已停（停服中）时丢弃并记 DEBUG。 */
    private void postToLogic(Runnable task) {
        try {
            runOnLogic(task);
        } catch (RejectedExecutionException e) {
            log.debug("逻辑线程已停止，丢弃任务");
        }
    }

    /**
     * 投递到逻辑线程；任务里的异常记错误日志，不让它悄悄吞掉。逻辑线程已停时抛 {@link RejectedExecutionException}。
     * 每个任务计排队等待与执行耗时（{@code xm.scene.logic.task.wait} / {@code .run}）：链路帧、存储回调、接管与失去归属
     * 都经这里；同步调用（{@link #callOnLogic}：目录 / 归属快照）与定时的帧任务不经过这里（帧耗时另记）。
     */
    private void runOnLogic(Runnable task) {
        logicLoop.execute(metrics.timeLogicTask(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("场景逻辑任务异常", e);
            }
        }));
    }

    private <T> T callOnLogic(Callable<T> task) throws Exception {
        return logicLoop.submit(task).get(LOGIC_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
