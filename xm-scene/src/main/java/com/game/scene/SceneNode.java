package com.game.scene;

import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.DestroyInstanceResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.proto.BattleRouting;
import com.game.audit.AuditProperties;
import com.game.audit.AuditTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.common.RunMode;
import com.game.common.id.LeaseGatedSnowflake;
import com.game.common.id.Snowflake;
import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.team.TeamMembershipReader;
import com.game.discovery.world.RedissonWorldChannelStore;
import com.game.player.store.PlayerStore;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpEndpoint;
import com.game.scene.asset.AssetOpService;
import com.game.scene.asset.SceneAssetOpProvider;
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
import com.game.scene.battle.BattleLocks;
import com.game.scene.battle.BattleSettlementService;
import com.game.scene.battle.PlayerBattleService;
import com.game.scene.battle.SceneBattleProvider;
import com.game.scene.battle.SceneBattleTables;
import com.game.scene.channel.ChannelPlanFollower;
import com.game.scene.currency.CurrencyFeature;
import com.game.scene.currency.CurrencyService;
import com.game.scene.discovery.SceneDirectoryPublisher;
import com.game.scene.gainblock.GainBlockSync;
import com.game.scene.gainblock.RedisGainBlockSource;
import com.game.scene.link.GateLinks;
import com.game.scene.link.LinkIdentity;
import com.game.scene.link.NodeLinkHandler;
import com.game.scene.link.NodeLinkServer;
import com.game.scene.link.SceneLinkService;
import com.game.scene.location.RedisPlayerLocations;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.mission.ActivityFeature;
import com.game.scene.mission.MissionFeature;
import com.game.scene.mission.MissionService;
import com.game.scene.mission.MissionTables;
import com.game.scene.pet.PetFeature;
import com.game.scene.pet.PetService;
import com.game.scene.pet.PetTables;
import com.game.scene.skill.SkillFeature;
import com.game.scene.skill.SkillService;
import com.game.scene.skill.SkillTables;
import com.game.scene.ownership.OwnerLeaseRenewer;
import com.game.scene.ownership.OwnerTakeoverSubscriber;
import com.game.scene.player.ItemGuids;
import com.game.scene.rpc.SceneRpcServer;
import com.game.scene.storage.StoragePlayerRepository;
import com.game.scene.team.TeamFollowService;
import com.game.scene.team.TravelTeamChecks;
import com.game.scene.transfer.SceneManagerSwitchTargets;
import com.game.scene.world.ClientRequestHandler;
import com.game.scene.world.CrossNodeSwitch;
import com.game.scene.world.PlayerSnapshots;
import com.game.scene.world.SceneClock;
import com.game.scene.world.SceneInstances;
import com.game.scene.world.SceneMessageIds;
import com.game.scene.world.SceneTables;
import com.game.scene.world.SceneTicker;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.ZoneTravel;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
 *   <li>{@code scene-sched}：节点号续租、节点目录发布、归属续约、主世界频道计划拉取（{@link ChannelPlanFollower}，批次 5.1）的调度
 *       （Redis I/O；MySQL 交给存储线程池；拉到的计划投递逻辑线程应用）；</li>
 *   <li>资产通道（architecture.md §4.12）：Dubbo Triple 的 I/O 与业务线程只把请求投递到逻辑线程；逻辑线程上完成的结局由
 *       {@code scene-asset-reply}（2 条，队列由在途上限 {@code xm.scene.asset-op-max-inflight} 兜住）切出来回写，Dubbo 的序列化不占逻辑线程；</li>
 *   <li>{@code scene-switch-rpc}（1 条，{@link SceneManagerSwitchTargets}）：跨节点换图向 scene-manager 选目标的建引用与发起调用，
 *       结果投递回逻辑线程（批次 5.2）。</li>
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
    /** 其他线程同步等待逻辑线程执行一个任务的上限（目录快照、归属快照、应用频道计划）。停服写回不用它，用整个停服预算。 */
    private static final long LOGIC_CALL_TIMEOUT_MS = 5_000;
    /** 资产通道回写线程数（只做 future 完成与 Dubbo 回写，不做业务）。 */
    static final int ASSET_REPLY_THREADS = 2;
    /** {@code scene-sched} 的线程数。 */
    static final int SCHED_THREADS = 3;

    private final SceneNodeProperties props;
    private final RedissonClient redis;
    private final PlayerStore playerStore;
    private final MessageIdRegistry registry;
    private final SceneTables tables;
    private final AttributeTables attributeTables;
    private final BagTables bagTables;
    private final MissionTables missionTables;
    private final SkillTables skillTables;
    private final PetTables petTables;
    private final NodeLinkAuth linkAuth;
    private final SceneMetrics metrics;
    private final AuditProperties audit;
    private final SceneBattleTables battleTables;
    private final SceneBattleMetrics battleMetrics;
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
    /** 玩家存储（停服时关存储线程池之前，等在途交出 / 探测的结局处理完）。 */
    private volatile StoragePlayerRepository playerRepository;
    private volatile GateLinks links;
    private volatile SceneWorld world;
    /** 资产通道的进程内入口（跨进程传输 {@link #assetRpc} 接在它外面）。 */
    private volatile AssetOpEndpoint assetOps;
    /** 资产通道的跨进程提供方（Dubbo Triple，按节点直连）与它的回写线程池。 */
    private volatile SceneAssetOpProvider assetProvider;
    private volatile ThreadPoolExecutor assetReplyExecutor;
    private volatile SceneRpcServer assetRpc;
    /** 回合制战斗（批次 6.3）：逻辑线程上的业务与它的 Dubbo 提供方（与资产通道同一个端口、同一个回写池）。 */
    private volatile PlayerBattleService battleService;
    private volatile SceneBattleProvider battleProvider;
    private volatile ScheduledFuture<?> battleReaperTask;
    private volatile ScheduledFuture<?> frameTask;
    private volatile ScheduledFuture<?> saveTask;
    private volatile ScheduledFuture<?> locationTask;
    private volatile ScheduledFuture<?> drainTask;
    private volatile ChannelPlanFollower channelFollower;
    private volatile NodeLinkServer linkServer;
    private volatile SceneDirectoryPublisher publisher;
    private volatile OwnerLeaseRenewer leaseRenewer;
    /** 跨节点换图选目标的 scene-manager 客户端（逻辑线程停了之后才关）。 */
    private volatile SceneManagerSwitchTargets switchTargets;
    private volatile OwnerTakeoverSubscriber takeoverSubscriber;
    private volatile GainBlockSync gainBlockSync;
    private volatile int gainBlockListener = -1;
    private volatile boolean running;

    /**
     * @param attributeTables 属性加点配表视图（与 {@code tables} 来自同一份配表快照）
     * @param bagTables       背包配表视图（同上）
     * @param missionTables   任务配表视图（同上）
     * @param skillTables     技能配表视图（同上）
     * @param petTables       宝宝配表视图（同上）
     * @param audit    资产审计管线配置（Kafka）
     * @param linkAuth gate 链路握手鉴权（密钥来自环境变量 {@code XM_NODE_LINK_SECRET}，须与 gate 一致）
     * @param metrics  scene 指标（各组件共用一份）
     * @param battleTables  回合制战斗的配表视图（指纹、技能可施放、道具 battle_usable、职业初值）
     * @param battleMetrics 回合制战斗的指标
     */
    public SceneNode(SceneNodeProperties props, RedissonClient redis, PlayerStore playerStore,
                     MessageIdRegistry registry, SceneTables tables, AttributeTables attributeTables, BagTables bagTables,
                     MissionTables missionTables, SkillTables skillTables, PetTables petTables, NodeLinkAuth linkAuth,
                     SceneMetrics metrics, AuditProperties audit, SceneBattleTables battleTables,
                     SceneBattleMetrics battleMetrics) {
        this.props = props;
        this.redis = redis;
        this.playerStore = playerStore;
        this.registry = registry;
        this.tables = tables;
        this.attributeTables = attributeTables;
        this.bagTables = bagTables;
        this.missionTables = missionTables;
        this.skillTables = skillTables;
        this.petTables = petTables;
        this.linkAuth = linkAuth;
        this.metrics = metrics;
        this.audit = audit;
        this.battleTables = battleTables;
        this.battleMetrics = battleMetrics;
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

        // 3 条：频道计划拉取与目录发布都可能同步等逻辑线程（各至多 5 s），留一条给节点号续期，续期不被它们拖到有效期之外
        scheduler = Executors.newScheduledThreadPool(SCHED_THREADS, new DefaultThreadFactory("scene-sched", true));
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
        StoragePlayerRepository repository = new StoragePlayerRepository(playerStore, storageExecutor, logic, metrics,
                settings.handOff());
        playerRepository = repository;

        GateLinks gateLinks = new GateLinks(metrics, logic);
        GainAnomalyDetector anomalies = new GainAnomalyDetector(settings.anomaly().defaults(),
                settings.anomaly().currencyThresholds(), settings.anomaly().itemThresholds(), SceneClock.SYSTEM, metrics);
        LeaseGatedSnowflake sceneGuids = acquireSceneGuids();
        AssetAudit assetAudit = startAudit(zoneId, nodeId, settings, sceneGuids);
        CurrencyService currency = new CurrencyService(assetAudit, anomalies, metrics, SceneClock.SYSTEM);
        BagService bags = new BagService(bagTables, itemGuids(sceneGuids), assetAudit, anomalies, metrics);
        startGainBlockSync(currency, bags, settings);
        AttributeService attributes = new AttributeService(attributeTables, SceneClock.SYSTEM, currency, metrics);
        MissionService missions = new MissionService(missionTables, bags, SceneClock.SYSTEM);
        SkillService skills = new SkillService(skillTables, SceneClock.SYSTEM, metrics, ids);
        PetService petService = new PetService(petTables, currency, itemGuids(sceneGuids), SceneClock.SYSTEM,
                new SplittableRandom(), metrics);
        PetFeature pets = new PetFeature(petService, registry);
        AuditPipeline pipeline = auditPipeline;
        PlayerSnapshots snapshots = pipeline == null ? PlayerSnapshots.NONE
                : new KafkaPlayerSnapshots(pipeline, SceneClock.SYSTEM, zoneId);
        // 组队跟随：进场 / 换场景后异步读成员关系（Redis I/O 不在逻辑线程上等），结果投递回逻辑线程（team-spec §6.10）
        TeamMembershipReader teamMemberships = new TeamMembershipReader(redis);
        // 6.3：与成员关系那次读并行发战斗锁 EXISTS（存在或读失败都按在途、不跟随，scene-battle-spec §7.13）
        BattleLockReader battleLocks = new BattleLockReader(redis);
        TeamFollowService teamFollow = new TeamFollowService(teamMemberships::readAsync, battleLocks::exists, logic, metrics);
        // 跨节点换图（批次 5.2，scene-handoff-spec §5.4–§5.8）：选目标经 Dubbo 直连 scene-manager（引用在第一次调用时才建，不阻塞启动）；
        // 交出进场成功后的立即续约经 leaseRenewer（它在下面才建，所以经 volatile 字段转一手，还没建出来 / 已停时跳过，周期续约兜底）
        switchTargets = SceneManagerSwitchTargets.dubbo(settings.sceneManagerUrl(), zoneId, nodeId, logic,
                settings.switchResolveTimeout());
        CrossNodeSwitch crossNode = new CrossNodeSwitch(nodeId, switchTargets, owned -> {
            OwnerLeaseRenewer renewer = leaseRenewer;
            if (renewer != null) {
                renewer.renewSoon(owned);
            }
        }, settings.switchResolveTimeout(), settings.transferTombstoneTtl());
        // 跨 zone 传送（批次 5.4，zone-travel-spec §5.5）：选目标复用同一个 scene-manager 客户端；受理前的在队检查与组队跟随共用成员关系的读口。
        // 两个时限与 63 共用配置键，但装配记录各带各的——226 不依赖跨节点换图是否启用
        ZoneTravel zoneTravel = new ZoneTravel(zoneId, switchTargets, new TravelTeamChecks(teamMemberships::readAsync, logic),
                settings.travel().teamCheckTimeout(), settings.switchResolveTimeout(), settings.transferTombstoneTtl());
        // 镜像 / 副本实例（批次 5.3，dungeon-mirror-spec §6）：取号复用同一个 scene-manager 客户端（同一个 Dubbo 引用，retries = 0）；
        // 实例建立 / 回收 / 级联 / 销毁后立即补发目录（目录是实例的唯一登记，D1）
        SceneNodeProperties.InstanceSettings instance = settings.instance();
        SceneInstances instances = new SceneInstances(nodeId, switchTargets, instance.mirrorIdleTimeout(),
                instance.idleTimeout(), instance.reclaimGrace(), instance.maxPerNode(), instance.maxPerCreator(),
                settings.switchResolveTimeout(), this::requestDirectoryPublish);
        // 回合制战斗（批次 6.3，scene-battle-spec §7）：冻结 / 确认 / 进场恢复 / reaper / 结算应用 / 销账都在逻辑线程上，Redis 脚本异步；
        // 世界的战斗钩子就是它（进场恢复、落盘后销账、原地解冻后重跑进场恢复），世界建好后再绑定
        BattleSettlementService settlements = new BattleSettlementService(currency, bags, petService, missions, battleTables,
                battleMetrics, SceneClock.SYSTEM, registry.requireId("ScenePetClientPlayer", "NotifyPetListChanged"));
        PlayerBattleService battle = new PlayerBattleService(BattleLocks.redis(new BattleRedis(redis)), settlements, battleTables,
                petService, player -> battleRouting(gateLinks, player, zoneId, nodeId), teamFollow, battleMetrics, SceneClock.SYSTEM,
                logic, registry.requireId("BattleClientPlayer", "NotifyBattleReconnect"),
                registry.requireId("BattleClientPlayer", "NotifyBattleEnd"));
        // 进场景前的规整：先背包（坏档拒绝进场），再属性、宝宝（同基线加载顺序），最后重建任务索引
        SceneWorld sceneWorld = new SceneWorld(tables, ids, gateLinks, repository, snowflake::nextId,
                SceneClock.SYSTEM, metrics, player -> {
                    bags.initializeOnLoad(player);
                    attributes.initializeOnLoad(player);
                    petService.initializeOnLoad(player);
                    missions.initializeOnLoad(player);
                    AssetOpService.checkLedgerOnLoad(player);
                    // 战斗结算账本损坏时大声报一次（结算一律延后、备战一律 1006，原样保留；scene-battle-spec §7.12，D24）
                    PlayerBattleService.checkLedgerOnLoad(player);
                }, snapshots, new RedisPlayerLocations(new PlayerLocationDirectory(redis), zoneId, nodeId, logic), teamFollow,
                crossNode, instances, battle, zoneTravel);
        battle.attach(sceneWorld);
        battleService = battle;
        links = gateLinks;
        world = sceneWorld;
        assetOps = new AssetOpEndpoint(logic, new AssetOpService(sceneWorld, currency, bags,
                AssetOpAuth.fromEnvironment(), SceneClock.SYSTEM, metrics));
        // 资产通道的跨进程提供方：先建好（导出在接受链路之后、发布目录之前）。回写池队列无界，但同时排队的至多是在途上限条
        assetReplyExecutor = new ThreadPoolExecutor(ASSET_REPLY_THREADS, ASSET_REPLY_THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), new DefaultThreadFactory("scene-asset-reply", true));
        metrics.bindAssetReplyExecutor(assetReplyExecutor);
        assetProvider = new SceneAssetOpProvider(() -> assetOps, settings.assetOpMaxInflight(), assetReplyExecutor,
                metrics);
        metrics.bindAssetOpsInFlight(() -> {
            SceneAssetOpProvider p = assetProvider;
            return p == null ? 0 : p.inFlight();
        });
        // 回合制战斗入口的跨进程提供方：同一端口、同一个回写池，在途上限各自独立（scene-battle-spec §7.3）
        battleProvider = new SceneBattleProvider(() -> battleService, instanceId, logic, settings.battleRpcMaxInflight(),
                assetReplyExecutor, battleMetrics);
        RunMode runMode = RunMode.parse(props.runMode());
        if (!RunMode.isRecognized(props.runMode())) {
            log.warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（GM 指令拒绝）: '{}'", props.runMode());
        }
        ClientRequestHandler requests = new ClientRequestHandler(sceneWorld, registry, ids, runMode,
                List.of(new CurrencyFeature(currency), new AttributeFeature(attributes, registry, call -> {
                    pets.onOwnerLevelChanged(call);
                    missions.onLevelChanged(call.player());
                }), new BagFeature(bags), new MissionFeature(missions), new ActivityFeature(missions),
                        new SkillFeature(skills), pets));
        log.info("场景请求分发就绪 运行模式={}（GM 指令{}）", runMode, runMode.allowsGmCommands() ? "放行" : "拒绝");
        // 主世界频道（批次 5.1，scene-channels-spec §4.10.5）：节点不再自己发号建场景（D18），按 scene-manager 写在 Redis 的频道计划建。
        // 先登记 zone、同步拉一次计划并在逻辑线程上应用（启动时本地没有场景，只会建出 ACTIVE 记录），再起周期拉取。
        // 拉不到只告警、不带频道启动：领导者下一拍按覆盖规则给本节点铺频道，拉取每周期重试。
        NodeIdLease nodeLease = lease;
        ChannelPlanFollower follower = new ChannelPlanFollower(new RedissonWorldChannelStore(redis), zoneId, nodeId,
                nodeLease::isValid, (version, mine) -> callOnLogic(() -> sceneWorld.applyChannelPlan(version, mine)),
                this::requestDirectoryPublish, metrics);
        channelFollower = follower;
        follower.syncOnce();
        follower.start(scheduler, settings.channelPlanPollInterval());
        // 场景维护：每秒一次（应用计划后另会立即推进一次排空）。排空频道里的人同节点改派，空了即销毁（§4.10.3）；
        // 批次 5.3 起同一个任务里做实例的空闲回收、回收宽限、级联兜底（dungeon-mirror-spec §6.10、§6.11，1 s 粒度）
        drainTask = logicLoop.scheduleAtFixedRate(() -> {
            try {
                sceneWorld.maintainScenes();
            } catch (RuntimeException e) {
                log.error("场景维护（排空推进 / 实例回收）这一秒出错，下一秒照常", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
        // 回合制战斗 reaper（scene-battle-spec §7.9）：与帧同一个执行器，启动注册、停服注销；缺省 30 s（本机切片调小）
        long reaperMillis = settings.battle().reaperInterval().toMillis();
        battleReaperTask = logicLoop.scheduleAtFixedRate(() -> {
            try {
                battle.reap();
            } catch (RuntimeException e) {
                log.error("回合制战斗 reaper 这一轮出错，下一轮照常", e);
            }
        }, reaperMillis, reaperMillis, TimeUnit.MILLISECONDS);
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

        // 玩家位置续期：每秒一个槽，每人每 20 s 一次（异步发出，不阻塞逻辑线程）。
        locationTask = logicLoop.scheduleAtFixedRate(() -> {
            try {
                sceneWorld.refreshDueLocations();
            } catch (RuntimeException e) {
                log.error("玩家位置续期这一秒出错，下一秒照常", e);
            }
        }, 1, 1, TimeUnit.SECONDS);

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

        // 资产通道导出：成功之后才把 rpc 地址写进目录（调用方只从目录找它，guild-economy-spec §4.6 第 6 条）；端口被占 / 缺 XM_DUBBO_SECRET 拒绝启动
        SceneRpcServer rpc = SceneRpcServer.export(assetProvider, battleProvider, props.advertiseHost(), settings.assetRpcPort());
        assetRpc = rpc;

        SceneNodeInfo info = SceneNodeInfo.newBuilder()
                .setZoneId(zoneId)
                .setNodeId(nodeId)
                .setInstanceId(instanceId)
                .setLinkHost(props.advertiseHost())
                .setLinkPort(linkPort)
                .setRpcHost(props.advertiseHost())
                .setRpcPort(rpc.port())
                .build();
        publisher = new SceneDirectoryPublisher(new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser()), info,
                () -> callOnLogic(() -> {
                    List<SceneEntry> entries = sceneWorld.sceneEntries();
                    approxPlayers.set(entries.stream().mapToInt(SceneEntry::getPlayerCount).sum());
                    return new SceneDirectoryPublisher.Snapshot(entries, sceneWorld.appliedPlanVersion());
                }));
        publisher.start(scheduler);

        log.info("场景节点已启动 zone={} node_id={} instance={} link={}:{} asset_rpc={}:{} 频道计划版本={}", zoneId, nodeId,
                instanceId, props.advertiseHost(), linkPort, props.advertiseHost(), rpc.port(),
                follower.appliedVersion() < 0 ? "未拉到" : Long.toUnsignedString(follower.appliedVersion()));
    }

    /**
     * 立即补发节点目录：应用了新的频道计划之后（拉取线程）、实例建立 / 回收 / 复活 / 级联 / 销毁之后（场景逻辑线程，批次 5.3）。
     * 任意线程可调，不加锁、不等 Redis（{@link SceneDirectoryPublisher#requestPublishNow} 只标脏，连发合并成至多一个调度任务）；
     * 目录发布者还没建出来或已停时什么也不做。
     */
    private void requestDirectoryPublish() {
        SceneDirectoryPublisher p = publisher;
        if (p != null) {
            p.requestPublishNow();
        }
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

    /** 本场景节点的节点号；没在运行（没启动、正在停）为 0。任意线程可调。 */
    public int nodeId() {
        NodeIdLease l = lease;
        return running && l != null ? l.nodeId() : 0;
    }

    public int zoneId() {
        return props.zoneId();
    }

    /** 本进程的实例 id（启动时随机生成，写进节点目录条目）。 */
    public String instanceId() {
        return instanceId;
    }

    /** 在线玩家数（到逻辑线程上数）；没在运行或逻辑线程没及时应答为 -1。任意线程可调（阻塞至多一个逻辑调用超时）。 */
    public int onlinePlayerCount() {
        SceneWorld w = world;
        if (!running || w == null) {
            return -1;
        }
        try {
            return callOnLogic(w::playerCount);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    /** 资产通道的进程内入口（任意线程可调）；节点没启动过为 null。 */
    public AssetOpEndpoint assetOps() {
        return assetOps;
    }

    /**
     * dev 管理口建副本（批次 5.3 §6.13；任意线程可调，不阻塞）：业务在逻辑线程上（{@link SceneWorld#createDungeon}，取号经 scene-manager，
     * 结果回到逻辑线程建实例）。节点没在运行、逻辑线程已停或处理出错时异常完成。调用方不再等结果时 {@code cancel} 返回的 future：
     * 还没取号就不取、号回来时不建、刚建好交不回去就当场销毁（不留没人知道号的副本）。
     */
    public CompletableFuture<CreateDungeonInstanceResponse> createDungeonInstance(int dungeonConfigId) {
        CompletableFuture<CreateDungeonInstanceResponse> result = new CompletableFuture<>();
        SceneWorld w = world;
        if (!running || w == null) {
            result.completeExceptionally(new IllegalStateException("场景节点没在运行"));
            return result;
        }
        // result 同时是放弃信号：管理口等超时会 cancel 它，SceneWorld 据此不取号 / 不建 / 建好交不回去就当场销毁
        submitAdmin(result, () -> w.createDungeon(dungeonConfigId, result));
        return result;
    }

    /** dev 管理口显式销毁实例（批次 5.3 §6.11；任意线程可调，不阻塞）：{@link SceneWorld#destroyInstance} 在逻辑线程上执行。 */
    public CompletableFuture<DestroyInstanceResponse> destroyInstance(long sceneId) {
        CompletableFuture<DestroyInstanceResponse> result = new CompletableFuture<>();
        SceneWorld w = world;
        if (!running || w == null) {
            result.completeExceptionally(new IllegalStateException("场景节点没在运行"));
            return result;
        }
        submitAdmin(result, () -> result.complete(
                DestroyInstanceResponse.newBuilder().setTipId(w.destroyInstance(sceneId)).build()));
        return result;
    }

    /** 管理口的逻辑任务：抛异常或逻辑线程已停时让等着的 HTTP 线程立刻拿到失败，而不是等到超时。 */
    private void submitAdmin(CompletableFuture<?> result, Runnable task) {
        try {
            runOnLogic(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                    throw e;
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
    }

    /**
     * 按启动的逆序释放，每一步都容忍前面没建出来（启动失败时也走这里）：
     * 停频道计划拉取与排空推进 → 摘目录（资产通道的调用方随之找不到本节点）→ 停监听 → 停封禁名单同步、接管订阅与续约 → 断开全部 gate 链路（不再有新帧进来，逻辑线程的积压只减不增）
     * → 逻辑线程上写回全部玩家并关链路，写回提交之后先等在途交出 / 探测的结局在逻辑线程上处理完（其中的「释放 E+1」赶在关池前提交），
     * 再关存储线程池并等写回落库（{@link SceneShutdown}，共用一个停服预算）
     * → 资产通道反导出 → 关线程（逻辑线程停之后才关选目标客户端与资产回写池）→ 最后才释放节点号（写回期间号仍归本实例，别的实例拿不到同一个号）。
     */
    private void release() {
        // 第一步停频道计划拉取（§4.10.5）：停服期间不再按计划建 / 排空场景；正在跑的一次最多再应用完这一版
        ChannelPlanFollower follower = channelFollower;
        if (follower != null) {
            follower.stop();
        }
        ScheduledFuture<?> drains = drainTask;
        if (drains != null) {
            drains.cancel(false);
        }
        ScheduledFuture<?> reaper = battleReaperTask;
        if (reaper != null) {
            reaper.cancel(false);
        }
        ScheduledFuture<?> locationRefresh = locationTask;
        if (locationRefresh != null) {
            locationRefresh.cancel(false);
        }
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
        StoragePlayerRepository repo = playerRepository;
        if (loop != null && w != null && g != null && storage != null) {
            // 写回之后、关存储线程池之前等在途交出 / 探测的结局在逻辑线程上处理完：其中「释放 E+1」要赶在关池之前提交
            SceneShutdown.TransferSettlement transfers =
                    repo == null ? SceneShutdown.TransferSettlement.NONE : repo::awaitTransfersSettled;
            SceneShutdown.Result result = SceneShutdown.writeBackThenDrainStorage(loop, () -> {
                int count = w.shutdown();
                g.closeAll();
                return count;
            }, transfers, storage, props.scene().shutdownSaveTimeout(), approxPlayers::get, System::nanoTime);
            log.info("停服写回 已执行={} 人数={} 丢弃存储任务={}", result.writeBackRan(), result.playersSubmitted(),
                    result.droppedTasks());
        } else if (storage != null) {
            storage.shutdownNow();
        }
        // 资产通道反导出：在摘目录（第一步）之后、逻辑线程停之前（guild-economy-spec §4.6 第 6 条）。到这里写回都已交出去，
        // 期间到的请求只会是 NOT_HERE；反导出之后调用方连不上 = 传输失败、重投，此前已记账已落库的结局可经离线读已落盘账本终结。
        SceneRpcServer rpc = assetRpc;
        if (rpc != null) {
            rpc.close();
            assetRpc = null;
        }
        if (server != null) {
            server.shutdown();
        }
        if (loop != null) {
            loop.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        // 选目标客户端在逻辑线程停之后才关：之后不会再有新的选目标请求（迟到的结果投递被拒、丢弃）
        SceneManagerSwitchTargets targets = switchTargets;
        if (targets != null) {
            targets.close();
            switchTargets = null;
        }
        // 回写池在逻辑线程停之后才关：之后不会再有逻辑线程上完成的结局（关闭后迟到的完成由提供方退回到完成线程上直接回写）
        ThreadPoolExecutor replies = assetReplyExecutor;
        if (replies != null) {
            replies.shutdown();
            try {
                if (!replies.awaitTermination(2, TimeUnit.SECONDS)) {
                    replies.shutdownNow();
                }
            } catch (InterruptedException e) {
                replies.shutdownNow();
                Thread.currentThread().interrupt();
            }
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
     * 全服发号：占全服范围的号段租约（{@code NodeTypes.SCENE_GUID}），只建一个 {@link LeaseGatedSnowflake}，物品 uuid、资产流水号、快照号
     * 共用（两个同 worker 的雪花实例会发出相同的号）。不论审计开不开都要：物品入包离不开它。
     */
    private LeaseGatedSnowflake acquireSceneGuids() {
        NodeIdLease guids = NodeIdLease.acquire(redis, scheduler, NodeTypes.SCENE_GUID, GUID_LEASE_SCOPE, 1,
                Snowflake.MAX_WORKER, instanceId, LEASE_TTL,
                () -> log.error("全服发号租约丢失：物品入包发不出号（回 6004）、资产流水改写兜底日志，请尽快重启本节点"));
        guidLease = guids;
        log.info("全服发号就绪 worker={}", guids.nodeId());
        return new LeaseGatedSnowflake(new Snowflake(guids.nodeId()), guids::isValid);
    }

    /**
     * 回合制战斗快照的路由（scene-battle-spec §7.11 快照表）：会话号；gate 节点号与实例 id 取链路登记表（恒有值）；scene 节点号 / 实例 / zone 取本节点。
     * 会话所在链路不在登记表里（不该发生）为 null。逻辑线程上调用。
     */
    private BattleRouting battleRouting(GateLinks gateLinks, com.game.scene.world.ScenePlayer player, int zoneId, int nodeId) {
        GateLinks.Link link = gateLinks.link(player.session().linkId());
        if (link == null) {
            return null;
        }
        return BattleRouting.newBuilder()
                .setSessionId(player.session().sessionId())
                .setGateNodeId(link.gateNodeId())
                .setGateInstanceId(link.gateInstanceId())
                .setSceneNodeId(nodeId)
                .setSceneInstanceId(instanceId)
                .setZoneId(zoneId)
                .build();
    }

    /** 物品 guid：一次铸齐一批，任何一个发不出来就整批放弃（调用方零写入地回 6004）。 */
    private static ItemGuids itemGuids(LeaseGatedSnowflake guids) {
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
    private AssetAudit startAudit(int zoneId, int nodeId, SceneNodeProperties.SceneSettings settings,
                                  LeaseGatedSnowflake guids) {
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
        log.error("场景节点号租约丢失：停止刷新节点目录与拉取频道计划、关闭链路监听、拒绝新进场；在场玩家继续服务至离开，请尽快重启本节点");
        // 号可能已归别的实例：不再按这个号名下的计划建 / 排空场景（已有场景照常服务）
        ChannelPlanFollower follower = channelFollower;
        if (follower != null) {
            follower.stop();
        }
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
