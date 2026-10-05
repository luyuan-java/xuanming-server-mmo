package com.game.guild;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.SceneNodeInfo;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerProfiles;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.guild.asset.AssetOpCaller;
import com.game.guild.asset.AssetOpCleanup;
import com.game.guild.asset.AssetOpLoop;
import com.game.guild.asset.AssetOpSigner;
import com.game.guild.asset.GuildAssetRuntime;
import com.game.guild.asset.GuildAssetStore;
import com.game.guild.asset.JdbcGuildAssetStore;
import com.game.guild.asset.PersistedLedgerReader;
import com.game.guild.asset.SceneEndpointSweeper;
import com.game.guild.cache.ApplyPushCooldown;
import com.game.guild.cache.GuildCache;
import com.game.guild.cache.GuildCacheInvalidator;
import com.game.guild.cache.RedissonGuildCacheRedis;
import com.game.guild.dispatch.GuildDispatcher;
import com.game.guild.dispatch.GuildMethods;
import com.game.guild.dispatch.GuildWorkerPool;
import com.game.guild.id.GuildIds;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.presence.OnlineStatuses;
import com.game.guild.presence.PlayerNames;
import com.game.guild.push.GuildPushes;
import com.game.guild.rank.GuildRanks;
import com.game.guild.rank.RedissonGuildRankRedis;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildTableRules;
import com.game.guild.service.EconomyAssetEffects;
import com.game.guild.service.EconomyTables;
import com.game.guild.service.GuildAccess;
import com.game.guild.service.GuildEconomyService;
import com.game.guild.service.GuildInternalQueries;
import com.game.guild.service.GuildManageService;
import com.game.guild.service.GuildRankService;
import com.game.guild.service.GuildService;
import com.game.guild.service.GuildSnapshots;
import com.game.guild.service.GuildTableLookup;
import com.game.guild.service.GuildViews;
import com.game.guild.store.BackgroundTx;
import com.game.guild.store.EconomyStore;
import com.game.guild.store.EconomyTxHooks;
import com.game.guild.store.GuildStartupChecks;
import com.game.guild.store.GuildStore;
import com.game.guild.store.GuildTables;
import com.game.guild.store.GuildTx;
import com.game.guild.store.JdbcEconomyStore;
import com.game.guild.store.JdbcGuildStore;
import com.game.guild.zone.MergeFence;
import com.game.pbmysql.PbMysql;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * xm-guild 的装配（guild-spec §7.1、§7.11；guild-economy-spec §7.7）。所有依赖显式经构造参数传入业务类；这里是唯一读配置、碰外部系统的地方。
 *
 * <p><b>启动顺序</b>（照基线 guild.go:85-393，每一步失败都拒启；顺序由 bean 依赖链钉住，Dubbo 在全部单例就绪之后才暴露）：
 * <ol>
 *   <li>配表校验（{@link GuildTableRules#validate} + {@link EconomyTables#validate}：经济四表含 Item 存在与 MaxBuyCount）——{@link #guildConfigTables}；</li>
 *   <li>xm-pbmysql 建表 / 只扩不缩地同步七张表——{@link #guildTables}；</li>
 *   <li>数据库版本检查（MySQL ≥ 8.0.29 或 TiDB）、影响行数语义（{@code useAffectedRows=true}，带上限 upsert 依赖它）与全局插入守卫哨兵行——
 *       {@link #guildSchemaReady}；</li>
 *   <li>雪花租约（{@code NodeTypes.GUILD}，作用域 0；guild_id 与 op_id 共用，E6）——{@link #guildIdLease}；</li>
 *   <li>资产通道（{@code xm.guild.asset-op.enabled}）：交叉校验（租约 + 间隔 &lt; 捐献截止，退避基数 ≤ 封顶）→ 签名器（缺
 *       {@code XM_ASSET_OP_SECRET_GUILD} 或不足 32 字节即拒启）→ 定位 → 调用方 → 重投循环（未启动）→ 离线读账本（E8）；关闭时打一条 ERROR——
 *       {@link #guildAssetRuntime}；资产 Store 的终结回调（推送）在构造时绑定，先于循环与 Dubbo（{@link #guildAssetStore}）；</li>
 *   <li>从 MySQL 全量重建排行——{@link #guildRanks}；</li>
 *   <li>启动重投循环与清理（{@link GuildAssetRuntime}，SmartLifecycle），随后 Dubbo 在上下文刷新完成时暴露 GuildService 与 GuildInternalService。</li>
 * </ol>
 *
 * <p><b>关闭顺序</b>：Dubbo 先注销并等在途调用；然后停重投循环与清理（等 worker 落库）；工作线程池、落库执行器、循环 worker 池都以数据源与 Redis 为参数、
 * 并 {@code @DependsOn} 后台调度器与租约，所以 Spring 先排空它们，再关资产通道客户端、调度器（缓存失效后台重试、排行锁续期）、租约、Redis 与连接池。
 */
@Configuration(proxyBeanMethods = false)
public class GuildConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GuildConfiguration.class);

    /**
     * guild_id 雪花 worker 的租约作用域。故意<b>不按 zone 分</b>（理由同 {@code NodeTypes.SCENE_GUID}）：guild_id 全服唯一，
     * 按 zone 各占 worker 号段会让不同 zone 的 xm-guild 拿到同一个 worker、发出逐位相同的号。0 表示「全服」。
     */
    static final int GUILD_ID_LEASE_SCOPE = 0;
    static final Duration LEASE_TTL = Duration.ofSeconds(15);
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);
    /** 结构同步连接的 socket 超时（咨询锁最多等 30 s，再加 DDL）。 */
    static final Duration SCHEMA_SYNC_NETWORK_TIMEOUT = Duration.ofMinutes(2);
    /** 启动期版本检查的预算（基线 guild.go:124-130）。 */
    static final long VERSION_CHECK_BUDGET_MS = 5_000L;
    /** 启动期建哨兵行的预算（基线 guild.go:137-142；必须明显长于 GET_LOCK 的 5 s）。 */
    static final long INSERT_GUARD_BUDGET_MS = 10_000L;
    /** 启动期重建排行时 G10 全表扫的请求预算（语句超时仍受 xm.guild.query-timeout 封顶）。 */
    static final long RANK_REBUILD_SCAN_BUDGET_MS = 30_000L;
    /** 资产通道客户端缓存的 Dubbo 应用名（进 URL，便于在 scene 日志里认出调用方）。 */
    static final String ASSET_CLIENT_APPLICATION = "xm-guild-asset";
    /** 停服时等重投循环 / 清理做完手上那一行的上限（每行至多 op-budget 10 s 上限 + 余量）。 */
    static final Duration ASSET_STOP_TIMEOUT = Duration.ofSeconds(15);

    /** 启动期数据库检查通过的标记（版本串只进日志）；依赖它的 bean 一定排在版本检查与哨兵行之后。 */
    public record SchemaReady(String serverVersion) {
    }

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public GuildMetrics guildMetrics(MeterRegistry meterRegistry) {
        return new GuildMetrics(meterRegistry);
    }

    // ================================================================ 启动第 1–3 步

    /**
     * 第 1 步：加载并整体校验全部配置表，再按帮会规则校验 GuildRule[1] 与 GuildLevel、按经济规则校验 GuildDonate / GuildShop / Item 与资产参数列
     * （缺行或越界拒启，不降级成告警：捐献扣的是玩家的货币，表错了在运行期表现为不可逆的静默错误）。
     *
     * @param tableDir 配置表目录（导表器 .pb 产物）；相对进程工作目录，进程约定从仓库根目录启动，别处启动时用 {@code XM_TABLE_DIR} 覆盖
     */
    @Bean
    public ConfigTables guildConfigTables(@Value("${xm.table-dir:config-data/tables}") String tableDir) {
        ConfigTables tables = ConfigTables.load(Path.of(tableDir));
        GuildTableRules.validate(tables);
        EconomyTables.validate(tables);
        log.info("帮会配表校验通过 dir={} GuildLevel={} 级 GuildDonate={} 行 GuildShop={} 行",
                Path.of(tableDir).toAbsolutePath().normalize(), tables.guildLevel().all().size(),
                tables.guildDonate().all().size(), tables.guildShop().all().size());
        return tables;
    }

    /**
     * 第 2 步：七张表经 xm-pbmysql 建表 / 只扩不缩地同步（一条自动提交的连接，整轮持咨询锁）；结构漂移即启动失败，由人工迁移。
     * {@code guildConfigTables} 参数只为保证「配表校验通过之后才碰库」。
     */
    @Bean
    public PbMysql guildTables(DataSource dataSource, ConfigTables guildConfigTables) throws SQLException {
        PbMysql db = GuildTables.sync(dataSource, SCHEMA_SYNC_NETWORK_TIMEOUT);
        log.info("帮会表已同步: {}", GuildTables.NAMES);
        return db;
    }

    /** 帮会写事务的唯一基座：取连接按请求剩余预算限等（Druid {@code getConnection(long)}），指标钩子绑到 Micrometer。 */
    @Bean
    public GuildTx guildTx(DataSource dataSource, GuildProperties props, GuildMetrics metrics) {
        return new GuildTx(connections(dataSource), props.queryTimeoutCapSeconds(), metrics);
    }

    /**
     * 第 3 步：数据库版本下限（低于 MySQL 8.0.29、MariaDB、解析不出一律拒启）、影响行数语义（连接池漏了 {@code useAffectedRows=true} 时
     * 带上限的 upsert 「达上限」与「新插」都回 1，捐献次数与限购会被突破，拒启）与全局插入守卫哨兵行。
     */
    @Bean
    public SchemaReady guildSchemaReady(GuildTx guildTx, PbMysql guildTables) {
        GuildStartupChecks checks = new GuildStartupChecks(guildTx);
        String version = checks.checkServerVersion(Deadline.after(VERSION_CHECK_BUDGET_MS));
        log.info("帮会数据库版本 {} 满足下限（TiDB 或 MySQL 8.0.29+）", version);
        checks.checkAffectedRowsSemantics(Deadline.after(VERSION_CHECK_BUDGET_MS));
        checks.ensureGlobalInsertGuard(System.currentTimeMillis(), Deadline.after(INSERT_GUARD_BUDGET_MS));
        return new SchemaReady(version);
    }

    /** 4.5 填上的事务钩子：踢人 / 退帮 / 解散在同一事务里把本帮未决捐献的截止提前到 now（guild-economy-spec §2.9）。 */
    @Bean
    public GuildStore guildStore(GuildTx guildTx, SchemaReady guildSchemaReady) {
        return new JdbcGuildStore(guildTx, EconomyTxHooks.INSTANCE);
    }

    /** 经济请求路径存储（捐献 / 兑换预留、升级、读页；GuildTx 事务基座）。 */
    @Bean
    public EconomyStore economyStore(GuildTx guildTx, SchemaReady guildSchemaReady) {
        return new JdbcEconomyStore(guildTx);
    }

    // ================================================================ 第 4 步：雪花租约

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService guildLeaseScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("guild-lease").daemon(true).factory());
    }

    /**
     * 雪花 worker 租约（{@code NodeTypes.GUILD}，作用域 0，worker ∈ [1, {@value Snowflake#MAX_WORKER}]）：guild_id 与资产指令的 op_id 共用
     * （E6 / Q6；基线 op_id 只由号段发，Java 没有号段服务）。租约无效（丢失，或续期滞后超过 2/3 TTL）期间 {@link GuildIds} 拒绝发号，建帮 / 捐献 /
     * 兑换回 14008（其余 RPC 不受影响）；续期滞后恢复后自动恢复，丢失则需重启。
     */
    @Bean(destroyMethod = "close")
    public NodeIdLease guildIdLease(RedissonClient redis,
                                    @Qualifier("guildLeaseScheduler") ScheduledExecutorService guildLeaseScheduler,
                                    SchemaReady guildSchemaReady) {
        return NodeIdLease.acquire(redis, guildLeaseScheduler, NodeTypes.GUILD, GUILD_ID_LEASE_SCOPE, 1, Snowflake.MAX_WORKER,
                UUID.randomUUID().toString(), LEASE_TTL,
                () -> log.error("guild 雪花 worker 租约丢失：停止发号（新的 CreateGuild / DonateToGuild / BuyGuildShopGoods 一律 14008），"
                        + "需要重启本进程"));
    }

    @Bean
    public GuildIds guildIds(NodeIdLease guildIdLease) {
        log.info("guild 雪花 worker={}（guild_id 与 op_id 共用）", guildIdLease.nodeId());
        return new GuildIds(new Snowflake(guildIdLease.nodeId()), guildIdLease::isValid);
    }

    // ================================================================ Redis：缓存、排行、冷却

    /** 缓存失效的后台重试与排行维护锁续期共用的调度器（任务只发异步 Redis 命令，不阻塞）。 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService guildBackgroundScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("guild-background").daemon(true).factory());
    }

    @Bean
    public GuildCache.CacheRedis guildCacheRedis(RedissonClient redis) {
        return new RedissonGuildCacheRedis(redis);
    }

    @Bean
    public GuildCacheInvalidator guildCacheInvalidator(GuildCache.CacheRedis guildCacheRedis, GuildProperties props,
                                                       @Qualifier("guildBackgroundScheduler") ScheduledExecutorService background,
                                                       GuildMetrics metrics) {
        return new GuildCacheInvalidator(guildCacheRedis, props.cacheTtl(), background, metrics);
    }

    /** 帮会快照与玩家 → 帮会映射：回源读 MySQL（G9 + M11 / M5），快照转成 Java 自有 proto 存 Redis。 */
    @Bean
    public GuildCache guildCache(GuildCache.CacheRedis guildCacheRedis, GuildProperties props, GuildStore store,
                                 GuildCacheInvalidator invalidator, GuildMetrics metrics) {
        return new GuildCache(guildCacheRedis, props.cacheTtl(),
                (guildId, deadline) -> store.loadGuild(guildId, deadline).map(GuildSnapshots::toSnapshot).orElse(null),
                store::playerGuildId, invalidator, metrics);
    }

    /**
     * 第 6 步：从 MySQL 全量重建全服榜与全部区榜（每次启动都跑，失败拒启）。{@code guildIdLease} 与 {@code guildAssetRuntime} 参数只为钉住
     * 「租约与资产通道装配之后才重建」的顺序。
     */
    @Bean
    public GuildRanks guildRanks(RedissonClient redis,
                                 @Qualifier("guildBackgroundScheduler") ScheduledExecutorService background,
                                 GuildMetrics metrics, GuildStore store, NodeIdLease guildIdLease,
                                 GuildAssetRuntime guildAssetRuntime) {
        GuildRanks ranks = new GuildRanks(new RedissonGuildRankRedis(redis), background, metrics);
        GuildRanks.RebuildResult rebuilt = ranks.rebuild(sink -> store.scanGuildScores(
                Deadline.after(RANK_REBUILD_SCAN_BUDGET_MS),
                row -> sink.accept(new GuildRanks.RankRow(row.guildId(), row.zoneId(), row.score()))));
        log.info("帮会排行已从 MySQL 重建: {} 个帮会 / {} 个区榜", rebuilt.guilds(), rebuilt.zones());
        return ranks;
    }

    @Bean
    public ApplyPushCooldown guildApplyPushCooldown(RedissonClient redis) {
        return ApplyPushCooldown.redisson(redis);
    }

    // ================================================================ 在线、名字、归属区、推送

    @Bean
    public PlayerPresenceDirectory playerPresenceDirectory(RedissonClient redis) {
        return new PlayerPresenceDirectory(redis);
    }

    @Bean
    public PlayerPushes playerPushes(RedissonClient redis, PlayerPresenceDirectory presence) {
        return new PlayerPushes(redis, presence);
    }

    /** 只读 {@code xm_java.player}（展示名与归属区；这张表不归 xm-guild 所有）。 */
    @Bean
    public PlayerProfiles playerProfiles(DataSource dataSource, GuildProperties props) {
        return new PlayerProfiles(connections(dataSource), props.queryTimeoutCapSeconds());
    }

    @Bean
    public GuildPushes guildPushes(PlayerPushes pushes, GuildMetrics metrics, GuildProperties props,
                                   MessageIdRegistry registry) {
        return new GuildPushes(pushes::pushToPlayers, metrics,
                registry.requireId(GuildMethods.SERVICE, GuildMethods.NOTIFY_GUILD_CHANGED), props.pushTimeout());
    }

    // ================================================================ 第 5 步：资产指令账本与通道

    /** 资产指令后台写的事务基座（RC；1213 / 9007 / 1205 都重跑；子预算取调用方给的 settle 截止）。 */
    @Bean
    public BackgroundTx guildBackgroundTx(DataSource dataSource, GuildProperties props) {
        return new BackgroundTx(connections(dataSource), props.queryTimeoutCapSeconds(), BackgroundTx.Listener.NONE);
    }

    /** 资产 Store 提交后的副作用：失效缓存、orphan / 清理计数、后台终结推送（基线 OnFinalized，必须先于循环与 Dubbo 绑定）。 */
    @Bean
    public EconomyAssetEffects economyAssetEffects(GuildCacheInvalidator invalidator, GuildPushes pushes, GuildMetrics metrics) {
        return new EconomyAssetEffects(invalidator, pushes, metrics);
    }

    /** 资产指令账本的后台 Store：无条件建（升级、读页、清理、内部查询都不依赖通道开关，guild.go:235-245）。 */
    @Bean
    public GuildAssetStore guildAssetStore(BackgroundTx guildBackgroundTx, EconomyAssetEffects economyAssetEffects,
                                           SchemaReady guildSchemaReady) {
        return new JdbcGuildAssetStore(guildBackgroundTx, economyAssetEffects);
    }

    @Bean
    public EconomyTables.Lookup economyTables(ConfigTables guildConfigTables) {
        return EconomyTables.Lookup.of(() -> guildConfigTables);
    }

    /**
     * 同步投递的落库执行器（{@code guild-asset-settle}，有界；700 ms 自有预算的阻塞 JDBC）：满了 → 那一行不落库、留在 PENDING，插行租约到期后由循环
     * 用同一 seq 重投（scene 只读答复）。以数据源与 Redis 为参数、依赖后台调度器：销毁时先排空它，再关这些。
     */
    @Bean(destroyMethod = "close")
    @DependsOn({"guildBackgroundScheduler"})
    public GuildWorkerPool guildAssetSettlePool(GuildProperties props, DataSource dataSource, RedissonClient redis) {
        return new GuildWorkerPool(GuildWorkerPool.ASSET_SETTLE_NAME, props.workerThreads(), props.workerQueueCapacity(),
                DRAIN_TIMEOUT);
    }

    /** 重投循环的 worker 池（{@code guild-asset-worker}，线程数 = workers：每副本同时在途的资产 RPC 上限）。 */
    @Bean(destroyMethod = "close")
    @DependsOn({"guildBackgroundScheduler"})
    public GuildWorkerPool guildAssetWorkerPool(GuildProperties props, DataSource dataSource, RedissonClient redis) {
        GuildProperties.AssetOp assetOp = props.assetOp();
        return new GuildWorkerPool(GuildWorkerPool.ASSET_WORKER_NAME, assetOp.workers(), assetOp.workers(), DRAIN_TIMEOUT);
    }

    /** 资产调用 durable 重查的定时器（只做「到点完成一个 future」，不阻塞）。 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService guildAssetTimer() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("guild-asset-timer").daemon(true).factory());
    }

    /**
     * 资产通道的 Dubbo 客户端缓存（按 scene 节点直连、{@code retries = 0}、单次 800 ms）：Dubbo 模型在第一次调用时才建（那时 Spring 的 Dubbo 早已初始化，
     * 不会抢走进程的缺省框架模型）。节点从目录消失 / 换实例时由定位结果驱动重建，进程退出时整体销毁。
     */
    @Bean(destroyMethod = "close")
    public SceneAssetOpClients sceneAssetOpClients() {
        return new SceneAssetOpClients(ASSET_CLIENT_APPLICATION);
    }

    /**
     * 资产通道的运行件（第 5 步）。{@code enabled = false}：不建签名器 / 定位 / 循环，捐献 / 兑换回 14026，打一条 ERROR（基线 guild.go:256-281）。
     * 开启时任何一步失败都拒启：交叉校验、签名器（缺密钥）、循环参数（含退避基数取自配表 GuildRule.asset_op_retry_base_ms）。
     * 清理按自己的开关（与通道互相独立）。
     */
    @Bean
    public GuildAssetRuntime guildAssetRuntime(GuildProperties props, ConfigTables guildConfigTables, GuildAssetStore store,
                                               DataSource dataSource, RedissonClient redis, GuildMetrics metrics,
                                               @Qualifier("guildAssetSettlePool") GuildWorkerPool settlePool,
                                               @Qualifier("guildAssetWorkerPool") GuildWorkerPool workerPool,
                                               @Qualifier("guildAssetTimer") ScheduledExecutorService timer,
                                               SceneAssetOpClients clients, NodeIdLease guildIdLease) {
        GuildProperties.AssetOp cfg = props.assetOp();
        AssetOpLoop loop = null;
        SceneEndpointSweeper sweeper = null;
        if (cfg.enabled()) {
            EconomyTables.validateAssetOpTiming(guildConfigTables, cfg.lease(), cfg.reconcileInterval(), cfg.maxBackoff());
            AssetOpSigner signer = AssetOpSigner.fromEnvironment();
            NodeDirectory<SceneNodeInfo> scenes = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
            SceneAssetLocator locator = new SceneAssetLocator(new PlayerLocationDirectory(redis), scenes, metrics::resolved);
            SceneEndpointSweeper tracker = new SceneEndpointSweeper(scenes::list, clients);
            sweeper = tracker;
            // 定位每找到一个节点就登记：节点从目录消失 / 换实例后由清扫销毁它的客户端（基线 watcher 删连接，svc/asset_op.go:166-170）
            AssetOpCaller.Locator tracked = playerId -> locator.resolveAsync(playerId).thenApply(resolution -> {
                if (resolution instanceof SceneAssetLocator.Found found) {
                    tracker.track(found.endpoint());
                }
                return resolution;
            });
            AssetOpCaller caller = new AssetOpCaller(tracked, clients::call, signer, timer, System::currentTimeMillis, metrics);
            AssetOpLoop.Config loopConfig = new AssetOpLoop.Config(cfg.reconcileInterval(), cfg.reconcileBatch(), cfg.workers(),
                    cfg.lease(), cfg.opBudget(), EconomyTables.assetOpRetryBaseMs(guildConfigTables), cfg.maxBackoff(),
                    cfg.poisonDelay(), cfg.ledgerReadMinAttempts());
            loop = new AssetOpLoop(loopConfig, store, caller, PersistedLedgerReader.of(dataSource), metrics,
                    System::currentTimeMillis, workerPool, settlePool, new SecureRandom(),
                    () -> ThreadLocalRandom.current().nextDouble());
            log.info("帮会资产通道已装配（caller=guild，签名密钥来自 XM_ASSET_OP_SECRET_GUILD；定位 = xm:location + scene 节点目录直连；"
                    + "离线读已落盘账本已接线）");
        } else {
            log.error("帮会资产通道关闭（xm.guild.asset-op.enabled=false）：捐献 / 兑换一律回 14026，不写任何行；升级、两个读页、清理与内部查询照常");
        }
        AssetOpCleanup cleanup = null;
        if (cfg.cleanupEnabled()) {
            cleanup = new AssetOpCleanup(store, cfg.cleanupInterval(), cfg.terminalRetention(), cfg.counterRetention(),
                    System::currentTimeMillis);
        } else {
            log.info("帮会资产账本清理关闭（xm.guild.asset-op.cleanup-enabled=false）");
        }
        return new GuildAssetRuntime(loop, cleanup, sweeper, ASSET_STOP_TIMEOUT);
    }

    // ================================================================ 服务

    @Bean
    public GuildTableLookup guildTableLookup(ConfigTables guildConfigTables) {
        return GuildTableLookup.of(() -> guildConfigTables);
    }

    /**
     * 归属区读 player.zone_id（D3；xm-common 的 {@link PlayerHomeZones}，与组队、聚宝斋共用，单次上限 1500 ms）。
     * 合服闸门：Java 首批不做合服（D4），恒放行；事务外与事务内的检查点照样保留。
     */
    @Bean
    public GuildAccess guildAccess(GuildCache cache, PlayerProfiles profiles, GuildCacheInvalidator invalidator) {
        return new GuildAccess(cache, new PlayerHomeZones(profiles::loadStrict,
                Duration.ofMillis(GuildLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS)), MergeFence.NONE, invalidator);
    }

    @Bean
    public GuildViews guildViews(PlayerPresenceDirectory presence, PlayerProfiles profiles, GuildMetrics metrics,
                                 GuildProperties props, GuildTableLookup tables, GuildStore store) {
        return new GuildViews(new OnlineStatuses(presence::findAllAsync, props.onlineLookupTimeout(), metrics),
                new PlayerNames(profiles::loadStrictOnce, metrics), tables, store::countLiveApplications,
                System::currentTimeMillis);
    }

    @Bean
    public GuildService guildService(GuildStore store, GuildCache cache, GuildAccess access, GuildViews views,
                                     GuildRanks ranks, GuildPushes pushes, GuildIds guildIds, GuildTableLookup tables) {
        return new GuildService(store, cache, access, views, ranks, pushes, guildIds::nextId, tables,
                System::currentTimeMillis);
    }

    @Bean
    public GuildManageService guildManageService(GuildStore store, GuildCache cache, GuildAccess access, GuildViews views,
                                                 GuildPushes pushes, ApplyPushCooldown cooldown, GuildTableLookup tables) {
        return new GuildManageService(store, cache, access, views, pushes, cooldown::tryMark, tables,
                System::currentTimeMillis);
    }

    @Bean
    public GuildRankService guildRankService(GuildRanks ranks, GuildCache cache, GuildAccess access, GuildViews views) {
        return new GuildRankService(ranks, cache, access, views);
    }

    /** 帮会经济五个 RPC；op_id 与 guild_id 同一个雪花（E6），插行租约 = {@code xm.guild.asset-op.lease}。 */
    @Bean
    public GuildEconomyService guildEconomyService(EconomyStore economyStore, GuildCache cache, GuildAccess access,
                                                   GuildViews views, EconomyTables.Lookup economyTables, GuildPushes pushes,
                                                   GuildMetrics metrics, GuildIds guildIds, GuildAssetRuntime guildAssetRuntime,
                                                   GuildProperties props,
                                                   @Qualifier("guildWorkerPool") GuildWorkerPool guildWorkerPool) {
        return new GuildEconomyService(economyStore, cache, access, views, economyTables, pushes, metrics, guildIds::nextId,
                new SecureRandom(), guildAssetRuntime.loop().orElse(null), props.assetOp().lease(), guildWorkerPool,
                System::currentTimeMillis);
    }

    /** 内部查询（回档闸用；与通道开关无关，保留期取 {@code xm.guild.asset-op.terminal-retention}）。SQL 在 guild-worker 上跑。 */
    @Bean
    public GuildInternalQueries guildInternalQueries(GuildAssetStore store, GuildProperties props,
                                                     @Qualifier("guildWorkerPool") GuildWorkerPool guildWorkerPool,
                                                     GuildMetrics metrics) {
        return new GuildInternalQueries(store, props.assetOp().terminalRetention(), guildWorkerPool, metrics,
                System::currentTimeMillis, props.requestBudget().toMillis());
    }

    // ================================================================ 派发

    /**
     * 依赖后台调度器与租约、并以数据源为参数（只为钉住销毁顺序）：Spring 按依赖逆序销毁，工作池先排空（排空中的请求还会用到失效重试调度器、
     * 发号租约、MySQL 连接池），之后它们才关。
     */
    @Bean(destroyMethod = "close")
    @DependsOn({"guildBackgroundScheduler", "guildIdLease"})
    public GuildWorkerPool guildWorkerPool(GuildProperties props, DataSource dataSource) {
        return new GuildWorkerPool(props.workerThreads(), props.workerQueueCapacity(), DRAIN_TIMEOUT);
    }

    @Bean
    public GuildDispatcher guildDispatcher(MessageIdRegistry registry, GuildService guilds, GuildManageService manage,
                                           GuildRankService ranks, GuildEconomyService economy,
                                           @Qualifier("guildWorkerPool") GuildWorkerPool guildWorkerPool, GuildMetrics metrics,
                                           GuildProperties props) {
        GuildDispatcher dispatcher = new GuildDispatcher(registry, guilds, manage, ranks, economy, guildWorkerPool, metrics,
                props.requestBudget().toMillis());
        log.info("guild 接管的消息号: C2S={} 经济={} 拒绝上行={} 4.6 占位(in-band 1006)={} 请求预算={} 缓存 TTL={} 推送上限={} 资产通道={}",
                dispatcher.messageIdsOf(GuildMethods.CLIENT_REQUESTS), dispatcher.messageIdsOf(GuildMethods.ECONOMY_REQUESTS),
                dispatcher.messageIdsOf(GuildMethods.FORBIDDEN), dispatcher.messageIdsOf(GuildMethods.PLACEHOLDERS),
                props.requestBudget(), props.cacheTtl(), props.pushTimeout(), economy.assetChannelEnabled() ? "开启" : "关闭");
        return dispatcher;
    }

    /** Dubbo 调用鉴权密钥的启动检查（fail-fast）；签名 / 校验在 xm-api 的 Dubbo 过滤器里。 */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    /** Druid 能按次限等连接：取连接不超过请求剩余预算（连接池的固定 max-wait 不认预算，见 PlayerProfiles.ConnectionSource）。 */
    private static PlayerProfiles.ConnectionSource connections(DataSource dataSource) {
        return dataSource instanceof DruidDataSource druid ? druid::getConnection : maxWait -> dataSource.getConnection();
    }
}
