package com.game.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.audit.AuditProperties;
import com.game.audit.KafkaTopicAdmin;
import com.game.common.token.DubboCallAuth;
import com.game.data.admin.AdminAuthFilter;
import com.game.data.gainblock.GainBlockStore;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobRunner;
import com.game.data.ops.OpsJobService;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.OpsTables;
import com.game.data.ops.fence.AdminOwnership;
import com.game.data.ops.fence.RedisTakeoverRequests;
import com.game.data.ops.fence.StrictClock;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.recall.RecallPlanner;
import com.game.data.rollback.BattleLockGate;
import com.game.data.rollback.DubboGuildInternalClient;
import com.game.data.rollback.GuildDivergenceGate;
import com.game.data.rollback.RollbackJob;
import com.game.data.rollback.RollbackPlanner;
import com.game.data.rollback.RollbackService;
import com.game.data.rollback.RollbackWriter;
import com.game.data.snapshot.SnapshotAdminService;
import com.game.data.snapshot.SnapshotDiffService;
import com.game.data.snapshot.ZoneSnapshotService;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.gateway.store.GatewayStore;
import com.game.pbmysql.PbMysql;
import com.game.player.store.PlayerMapper;
import com.game.player.store.PlayerStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * xm-data 的装配。所有依赖显式经构造参数传入。
 *
 * <p>MyBatis：本服务的 Mapper（{@code com.game.data.store}）与 xm-player-store 的 {@link PlayerMapper}（批次 7.2b 起：夺权 / 续约 / 释放 /
 * 带围栏写 / 加锁读）共用同一个 {@code SqlSessionFactory} 与数据源（同事务的前提，data-ops-spec §7.5）。xm-player-store 的自动装配仍被
 * {@link DataApplication} 排除：{@link PlayerStore} 由这里定义（严格递增时钟，见 {@link StrictClock}）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DataProperties.class, AuditProperties.class})
@MapperScan(basePackages = "com.game.data.store", basePackageClasses = PlayerMapper.class)
public class DataConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DataConfiguration.class);

    /** 运维表结构同步那条连接的 socket 超时（咨询锁最多等 30 s，再加 DDL；同 xm-trade）。 */
    static final Duration SCHEMA_SYNC_NETWORK_TIMEOUT = Duration.ofMinutes(2);

    /** 列名下划线转驼峰（与 xm-player-store 同口径）。 */
    @Bean
    public ConfigurationCustomizer mapUnderscoreToCamelCase() {
        return configuration -> configuration.setMapUnderscoreToCamelCase(true);
    }

    @Bean
    public DataMetrics dataMetrics(MeterRegistry registry) {
        return new DataMetrics(registry);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 全服产出封禁名单（Redis）。客户端是懒加载 bean，经 {@code ObjectProvider} 在用到时取——装配期不连接，Redis 不可用不挡住启动与审计消费；
     * 批次 7.2a 起它实际由发号器 {@link OpsIds} 在启动后的后台线程上先连上（见 {@link #opsIds}）。
     */
    @Bean
    public GainBlockStore gainBlockStore(ObjectProvider<RedissonClient> redis, ObjectMapper json) {
        return new GainBlockStore(redis, json);
    }

    @Bean
    public DataNode dataNode(AuditProperties audit, DataProperties props, TransactionLogMapper transactionLog,
                             PlayerSnapshotMapper playerSnapshot, PlatformTransactionManager transactionManager,
                             DataMetrics metrics, Clock clock) {
        return new DataNode(audit, props, transactionLog, playerSnapshot, new TransactionTemplate(transactionManager),
                metrics, () -> new KafkaTopicAdmin(audit.bootstrapServers(), "xm-data-admin"), clock);
    }

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(DataProperties props, DataMetrics metrics) {
        FilterRegistrationBean<AdminAuthFilter> registration =
                new FilterRegistrationBean<>(new AdminAuthFilter(props.adminToken(), metrics));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    // ================================================================ 运维面（批次 7.2a）

    /** 本实例的标识（作业行的 runner 列；排障用）：pid@主机名。 */
    static String runnerId() {
        return "xm-data/" + ManagementFactory.getRuntimeMXBean().getName();
    }

    /** 发号租约的申领、重试与续期线程（阻塞 Redis 调用，不是 I/O 线程）。 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService opsIdsScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("data-ops-ids").daemon(true).factory());
    }

    /**
     * 直写快照号 / 作业号的号源：{@code NodeTypes.SCENE_GUID} 全服池（与 scene 同池，data-ops-spec §2.3 硬约束）。后台申领、不挡启动；
     * 生命周期阶段低于 Web 服务器，停机时最后交还。
     */
    @Bean
    public OpsIds opsIds(ObjectProvider<RedissonClient> redis,
                         @Qualifier("opsIdsScheduler") ScheduledExecutorService opsIdsScheduler, Clock clock) {
        String instanceId = "xm-data-" + UUID.randomUUID();
        return new OpsIds(OpsIds.sceneGuidPool(redis::getObject, opsIdsScheduler, instanceId), opsIdsScheduler,
                clock::millis, OpsIds.RETRY);
    }

    /** 运维作业表经 xm-pbmysql 建表 / 只扩不缩地同步（结构漂移拒绝启动，由人工迁移）。 */
    @Bean
    public PbMysql opsTables(DataSource dataSource) throws SQLException {
        PbMysql db = OpsTables.sync(dataSource, SCHEMA_SYNC_NETWORK_TIMEOUT);
        log.info("运维作业表已同步: {}", OpsTables.NAMES);
        return db;
    }

    @Bean
    public OpsJobStore opsJobStore(DataSource dataSource, @Qualifier("opsTables") PbMysql opsTables) {
        return new OpsJobStore(dataSource, opsTables);
    }

    @Bean
    public TransactionLogQueryService transactionLogQueryService(TransactionLogMapper mapper, DataProperties props) {
        return new TransactionLogQueryService(mapper, props.ops().maxWindow());
    }

    @Bean
    public SnapshotAdminService snapshotAdminService(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots,
                                                     OpsJobStore jobs, OpsIds opsIds,
                                                     PlatformTransactionManager transactionManager, DataMetrics metrics,
                                                     ObjectMapper json, Clock clock) {
        return new SnapshotAdminService(players, snapshots, jobs, opsIds, new TransactionTemplate(transactionManager),
                metrics, json, clock, runnerId());
    }

    /** 带资产流水保留期：快照早于「现在 − 保留期」时转移证据可能已被清理，差异里标成不完整而不是「没转移」。 */
    @Bean
    public SnapshotDiffService snapshotDiffService(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots,
                                                   TransactionLogQueryService txlog, Clock clock, DataProperties props) {
        return new SnapshotDiffService(players, snapshots, txlog, clock, props.retention().transactionLog());
    }

    @Bean
    public RecallPlanner recallPlanner(TransactionLogQueryService txlog, PersistedPlayerMapper players, OpsJobStore jobs,
                                       OpsIds opsIds, PlatformTransactionManager transactionManager, ObjectMapper json,
                                       Clock clock, DataProperties props) {
        return new RecallPlanner(txlog, players, jobs, opsIds, new TransactionTemplate(transactionManager), json, clock,
                props.recall().maxRows(), runnerId());
    }

    // ================================================================ 作业框架、栅栏与回档（批次 7.2b）

    /**
     * 写操作开关打开时 {@code XM_DUBBO_SECRET} 必填（帮会检查是 Dubbo 调用方；缺了拒绝启动，data-ops-spec §4.6.1）。关闭时不要求：
     * 只读与 dry-run 不调 Dubbo。
     */
    static void checkOpsWriteGate(DataProperties props, String dubboSecret) {
        if (props.ops().enabled()) {
            DubboCallAuth.requireFromEnvValue(dubboSecret);
            log.info("运维写操作已开启（xm.data.ops.enabled=true）：回档 / 取消作业可用");
        } else {
            log.info("运维写操作未开启（xm.data.ops.enabled=false）：回档执行回 503 ops_disabled，只读与 dry-run 照常");
        }
    }

    /**
     * xm-data 自己的 {@link PlayerStore}（xm-player-store 的自动装配被排除）：时钟严格递增（{@link StrictClock}，
     * 防 {@code useAffectedRows=true} 下同毫秒无变化写被误判为失去围栏）；与本服务同一数据源、同一事务管理器。
     */
    @Bean
    public PlayerStore playerStore(PlayerMapper mapper, PlatformTransactionManager transactionManager) {
        return new PlayerStore(mapper, new StrictClock(System::currentTimeMillis), transactionManager);
    }

    /** 作业心跳与栅栏续约线程（{@code data-ops-fence}；阻塞 JDBC，不是 I/O 线程）。 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService opsFenceScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("data-ops-fence").daemon(true).factory());
    }

    @Bean
    public OpsJobRunner opsJobRunner(OpsJobStore jobs, PlatformTransactionManager transactionManager, ObjectMapper json,
                                     DataMetrics metrics, Clock clock, DataProperties props,
                                     @Qualifier("opsFenceScheduler") ScheduledExecutorService fence) {
        checkOpsWriteGate(props, System.getenv(DubboCallAuth.SECRET_ENV));
        return new OpsJobRunner(jobs, new TransactionTemplate(transactionManager), json, metrics, clock, runnerId(),
                props.ops().heartbeat(), props.ops().staleAfter(), props.ops().jobTimeout(), fence);
    }

    @Bean
    public OpsJobService opsJobService(OpsJobStore jobs, OpsIds opsIds, OpsJobRunner runner,
                                       PlatformTransactionManager transactionManager, ObjectMapper json, Clock clock) {
        return new OpsJobService(jobs, opsIds, runner, new TransactionTemplate(transactionManager), json, clock);
    }

    /**
     * 离线栅栏（归属夺权）。让出请求走 {@code xm:owner-takeover}，墓碑写 {@code xm:location}。Redis 客户端是懒加载 bean，这里只经
     * {@code ObjectProvider} 在用到时取、装配期不连接；实际的连接时机是启动之后——发号器 {@link OpsIds} 在 {@code data-ops-ids}
     * 线程上后台申领租约时就连上了（连不上每 10 s 重试，与写开关无关），并不是等到第一次夺权才连。
     * 续约在 {@code data-ops-fence} 线程上每 {@code OWNER_LEASE / 3}（10 s）一次，与 scene 续约同口径。
     */
    @Bean
    public AdminOwnership adminOwnership(PlayerStore playerStore, PlatformTransactionManager transactionManager,
                                         ObjectProvider<RedissonClient> redis, DataMetrics metrics,
                                         @Qualifier("opsFenceScheduler") ScheduledExecutorService fence) {
        AdminOwnership ownership = new AdminOwnership(playerStore, new TransactionTemplate(transactionManager),
                new RedisTakeoverRequests(redis::getObject),
                (playerId, epoch) -> new PlayerLocationDirectory(redis.getObject()).removeAsync(playerId, epoch,
                        AdminOwnership.TOMBSTONE_SEQ),
                metrics);
        long renewMs = PlayerStore.OWNER_LEASE.toMillis() / 3;
        fence.scheduleWithFixedDelay(ownership::renew, renewMs, renewMs, TimeUnit.MILLISECONDS);
        return ownership;
    }

    /** 帮会内部查询的 Dubbo 调用方：{@code xm.dubbo.guild-url} 为空 = 没有装配（帮会检查一律 check_failed）。 */
    @Bean(destroyMethod = "close")
    public DubboGuildInternalClient guildInternalClient(@Value("${xm.dubbo.guild-url:}") String guildUrl,
                                                        DataProperties props) {
        return new DubboGuildInternalClient(guildUrl, props.rollback().guild().callTimeout());
    }

    @Bean
    public GuildDivergenceGate guildDivergenceGate(DubboGuildInternalClient client,
                                                   @Value("${xm.dubbo.guild-url:}") String guildUrl, DataProperties props) {
        return new GuildDivergenceGate(guildUrl.isBlank() ? null : client, props.rollback().guild().callTimeout());
    }

    /**
     * 回档前的战斗锁闸（批次 6.3，data-ops-spec §13.3）：夺权之后、账本差集之前批量读 {@code xm:battle:{pid}:lock}（xm-discovery 的
     * {@link BattleLockReader#existsAll}，与 xm-scene 同一个 Redis 库）。Redis 客户端每次读锁时才经 {@code ObjectProvider} 取
     * （同 {@link RedisTakeoverRequests} 的接法），装配期不连接；取不到 / 读失败 / 超时都折成 {@code battle_lock_unknown}（不写）。
     */
    @Bean
    public BattleLockGate battleLockGate(ObjectProvider<RedissonClient> redis, DataProperties props) {
        return new BattleLockGate(playerIds -> new BattleLockReader(redis.getObject()).existsAll(playerIds),
                props.ops().battleLockWait());
    }

    @Bean
    public RollbackJob.Deps rollbackDeps(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots,
                                         TransactionLogMapper txlog, TransactionLogQueryService txlogQuery,
                                         PlayerStore playerStore, PlayerMapper playerMapper, OpsJobStore jobs, OpsIds opsIds,
                                         AdminOwnership ownership, GuildDivergenceGate guild, BattleLockGate battleLocks,
                                         PlatformTransactionManager transactionManager, ObjectMapper json, Clock clock,
                                         DataProperties props, DataMetrics metrics) {
        RollbackWriter writer = new RollbackWriter(playerStore, playerMapper, players, snapshots, txlog, jobs, opsIds,
                new TransactionTemplate(transactionManager), json, clock);
        return new RollbackJob.Deps(new RollbackPlanner(players, snapshots), writer, ownership, guild, battleLocks, players,
                snapshots, txlogQuery, jobs, props, metrics, json);
    }

    @Bean
    public RollbackService rollbackService(RollbackJob.Deps deps, OpsJobService jobs, GatewayStore gatewayStore,
                                           DataProperties props, Clock clock) {
        return new RollbackService(deps, jobs, gatewayStore, props, clock);
    }

    @Bean
    public ZoneSnapshotService zoneSnapshotService(PersistedPlayerMapper players, PlayerSnapshotMapper snapshots,
                                                   OpsIds opsIds, OpsJobService jobs, GatewayStore gatewayStore,
                                                   PlatformTransactionManager transactionManager, Clock clock) {
        return new ZoneSnapshotService(players, snapshots, opsIds, jobs, gatewayStore,
                new TransactionTemplate(transactionManager), clock);
    }
}
