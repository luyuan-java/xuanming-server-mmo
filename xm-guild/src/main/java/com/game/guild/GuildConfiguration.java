package com.game.guild;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.common.player.PlayerProfiles;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
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
import com.game.guild.rules.GuildTableRules;
import com.game.guild.service.GuildAccess;
import com.game.guild.service.GuildManageService;
import com.game.guild.service.GuildRankService;
import com.game.guild.service.GuildService;
import com.game.guild.service.GuildSnapshots;
import com.game.guild.service.GuildTableLookup;
import com.game.guild.service.GuildViews;
import com.game.guild.store.GuildStartupChecks;
import com.game.guild.store.GuildStore;
import com.game.guild.store.GuildTables;
import com.game.guild.store.GuildTx;
import com.game.guild.store.GuildTxHooks;
import com.game.guild.store.JdbcGuildStore;
import com.game.guild.zone.MergeFence;
import com.game.guild.zone.PlayerTableHomeZones;
import com.game.pbmysql.PbMysql;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * xm-guild 的装配（guild-spec §7.1、§7.11）。所有依赖显式经构造参数传入业务类；这里是唯一读配置、碰外部系统的地方。
 *
 * <p><b>启动顺序</b>（照基线 guild.go:85-375，每一步失败都拒启；顺序由 bean 依赖链钉住，Dubbo 在全部单例就绪之后才暴露）：
 * <ol>
 *   <li>配表校验（{@link GuildTableRules#validate}）——{@link #guildConfigTables}；</li>
 *   <li>xm-pbmysql 建表 / 只扩不缩地同步四张表——{@link #guildTables}；</li>
 *   <li>数据库版本检查（MySQL ≥ 8.0.29 或 TiDB，5 s）与全局插入守卫哨兵行 {@code guild_player_state(0)}（10 s）——{@link #guildSchemaReady}；</li>
 *   <li>guild_id 雪花租约（{@code NodeTypes.GUILD}，作用域 0）——{@link #guildIdLease}；</li>
 *   <li>从 MySQL 全量重建排行——{@link #guildRanks}；</li>
 *   <li>最后才暴露 Dubbo（{@code @DubboService} 在上下文刷新完成后导出）。</li>
 * </ol>
 *
 * <p><b>关闭顺序</b>：Dubbo 先注销并等在途调用；工作线程池 {@code @DependsOn} 后台调度器与租约、并以数据源为参数，所以 Spring 先排空工作池，
 * 再关调度器（缓存失效后台重试、排行锁续期）、租约、Redis 与连接池。
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
     * 第 1 步：加载并整体校验全部配置表，再按帮会规则校验 GuildRule[1] 与 GuildLevel（缺行或越界拒启，不降级成告警：
     * 成员上限、长老上限、申请上限全部「用时现查配表」，查不到会静默取到 0）。
     *
     * @param tableDir 配置表目录（导表器 .pb 产物）；相对进程工作目录，进程约定从仓库根目录启动，别处启动时用 {@code XM_TABLE_DIR} 覆盖
     */
    @Bean
    public ConfigTables guildConfigTables(@Value("${xm.table-dir:config-data/tables}") String tableDir) {
        ConfigTables tables = ConfigTables.load(Path.of(tableDir));
        GuildTableRules.validate(tables);
        log.info("帮会配表校验通过 dir={} GuildLevel={} 级", Path.of(tableDir).toAbsolutePath().normalize(),
                tables.guildLevel().all().size());
        return tables;
    }

    /**
     * 第 2 步：四张表经 xm-pbmysql 建表 / 只扩不缩地同步（一条自动提交的连接，整轮持咨询锁）；结构漂移即启动失败，由人工迁移。
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

    /** 第 3 步：数据库版本下限（低于 MySQL 8.0.29、MariaDB、解析不出一律拒启）与全局插入守卫哨兵行。 */
    @Bean
    public SchemaReady guildSchemaReady(GuildTx guildTx, PbMysql guildTables) {
        GuildStartupChecks checks = new GuildStartupChecks(guildTx);
        String version = checks.checkServerVersion(Deadline.after(VERSION_CHECK_BUDGET_MS));
        log.info("帮会数据库版本 {} 满足下限（TiDB 或 MySQL 8.0.29+）", version);
        checks.ensureGlobalInsertGuard(System.currentTimeMillis(), Deadline.after(INSERT_GUARD_BUDGET_MS));
        return new SchemaReady(version);
    }

    /** 4.4 的事务钩子是空操作（4.5 / 4.6 的表还没建，guild-spec §6.3）。 */
    @Bean
    public GuildStore guildStore(GuildTx guildTx, SchemaReady guildSchemaReady) {
        return new JdbcGuildStore(guildTx, GuildTxHooks.NONE);
    }

    // ================================================================ 第 4 步：guild_id 租约

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService guildLeaseScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("guild-lease").daemon(true).factory());
    }

    /**
     * guild_id 雪花 worker 租约（{@code NodeTypes.GUILD}，作用域 0，worker ∈ [1, {@value Snowflake#MAX_WORKER}]）。租约无效（丢失，或续期滞后
     * 超过 2/3 TTL）期间 {@link GuildIds} 拒绝发号，CreateGuild 回 14008（其余 RPC 不受影响）；续期滞后恢复后自动恢复，丢失则需重启。
     */
    @Bean(destroyMethod = "close")
    public NodeIdLease guildIdLease(RedissonClient redis,
                                    @Qualifier("guildLeaseScheduler") ScheduledExecutorService guildLeaseScheduler,
                                    SchemaReady guildSchemaReady) {
        return NodeIdLease.acquire(redis, guildLeaseScheduler, NodeTypes.GUILD, GUILD_ID_LEASE_SCOPE, 1, Snowflake.MAX_WORKER,
                UUID.randomUUID().toString(), LEASE_TTL,
                () -> log.error("guild_id 雪花 worker 租约丢失：停止建帮（新的 CreateGuild 一律 14008），需要重启本进程"));
    }

    @Bean
    public GuildIds guildIds(NodeIdLease guildIdLease) {
        log.info("guild_id 雪花 worker={}", guildIdLease.nodeId());
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
     * 第 5 步：从 MySQL 全量重建全服榜与全部区榜（每次启动都跑，失败拒启）。{@code guildIdLease} 参数只为钉住「租约之后才重建」的顺序。
     */
    @Bean
    public GuildRanks guildRanks(RedissonClient redis,
                                 @Qualifier("guildBackgroundScheduler") ScheduledExecutorService background,
                                 GuildMetrics metrics, GuildStore store, NodeIdLease guildIdLease) {
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

    // ================================================================ 服务

    @Bean
    public GuildTableLookup guildTableLookup(ConfigTables guildConfigTables) {
        return GuildTableLookup.of(() -> guildConfigTables);
    }

    /** 合服闸门：Java 首批不做合服（D4），恒放行；事务外与事务内的检查点照样保留。 */
    @Bean
    public GuildAccess guildAccess(GuildCache cache, PlayerProfiles profiles, GuildCacheInvalidator invalidator) {
        return new GuildAccess(cache, new PlayerTableHomeZones(profiles::loadStrict), MergeFence.NONE, invalidator);
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
                                           GuildRankService ranks, GuildWorkerPool guildWorkerPool, GuildMetrics metrics,
                                           GuildProperties props) {
        GuildDispatcher dispatcher = new GuildDispatcher(registry, guilds, manage, ranks, guildWorkerPool, metrics,
                props.requestBudget().toMillis());
        log.info("guild 接管的消息号: C2S={} 拒绝上行={} 4.5/4.6 占位(in-band 1006)={} 请求预算={} 缓存 TTL={} 推送上限={}",
                dispatcher.messageIdsOf(GuildMethods.CLIENT_REQUESTS), dispatcher.messageIdsOf(GuildMethods.FORBIDDEN),
                dispatcher.messageIdsOf(GuildMethods.PLACEHOLDERS), props.requestBudget(), props.cacheTtl(),
                props.pushTimeout());
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
