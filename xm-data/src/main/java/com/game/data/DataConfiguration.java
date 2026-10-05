package com.game.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.audit.AuditProperties;
import com.game.audit.KafkaTopicAdmin;
import com.game.data.admin.AdminAuthFilter;
import com.game.data.gainblock.GainBlockStore;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobStore;
import com.game.data.ops.OpsTables;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.recall.RecallPlanner;
import com.game.data.snapshot.SnapshotAdminService;
import com.game.data.snapshot.SnapshotDiffService;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import com.game.pbmysql.PbMysql;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** xm-data 的装配。所有依赖显式经构造参数传入。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DataProperties.class, AuditProperties.class})
@MapperScan("com.game.data.store")
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

    /** 全服产出封禁名单（Redis；客户端第一次用到时才创建，Redis 不可用不挡住启动与审计消费）。 */
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
}
