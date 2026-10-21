package com.game.inbox;

import com.game.api.DubboGroups;
import com.game.api.SceneInboxService;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.token.DubboCallAuth;
import com.game.api.inbox.InboxCallContract;
import com.game.discovery.serviceauth.ServiceCallSignatures;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.sql.AuthorityJdbc;
import com.game.discovery.sql.StrongTiDbConnectionPolicy;
import com.game.discovery.world.RedissonWorldChannelStore;
import com.game.inbox.admin.InboxAdminAuthFilter;
import com.game.inbox.gate.ConsumerGate;
import com.game.inbox.gate.SceneDirectoryConsumerGate;
import com.game.inbox.maintenance.InboxMaintenance;
import com.game.inbox.maintenance.LeaderLease;
import com.game.inbox.metrics.InboxMetrics;
import com.game.inbox.notify.Notifier;
import com.game.inbox.notify.SceneNotifier;
import com.game.inbox.registry.InboxRegistry;
import com.game.inbox.service.GmSanctions;
import com.game.inbox.store.InboxAdminStore;
import com.game.inbox.store.InboxMaintenanceStore;
import com.game.inbox.store.InboxStore;
import com.game.inbox.store.InboxTables;
import com.game.inbox.store.JdbcPlayerTables;
import com.game.pbmysql.PbMysql;
import com.game.proto.player_inbox.PlayerInboxMessageType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * game-inbox 的装配。所有依赖显式经构造参数传入业务类；这里是唯一读配置、碰外部系统的地方。启动时依次：核对权威库（TiDB 强读门禁）→ 建表 /
 * 同步结构并整库核对 shard（不一致拒启）→ 读类型注册表并自检（不过拒启）→ 解析入箱白名单（v1 不开放的类型拒启）。
 */
@Configuration(proxyBeanMethods = false)
public class InboxConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InboxConfiguration.class);

    /** scene 提醒客户端的 Dubbo 应用名（隔离模型、按节点直连；进对端 URL 便于认出调用方）。 */
    static final String SCENE_CLIENT_APPLICATION = "game-inbox-scene";
    /** 提醒的单次 Dubbo 超时上限（调用时按剩余预算传更小的值）。 */
    static final Duration SCENE_CALL_TIMEOUT = Duration.ofSeconds(1);

    /** 静态工厂：在 Druid 池初始化之前装好强读会话策略。prod profile 不得关闭 TiDB 门禁（同 game-friend）。 */
    @Bean
    static StrongTiDbConnectionPolicy inboxConnectionPolicy(Environment environment,
                                                            @Value("${game.inbox.require-tidb:true}") boolean requireTiDb) {
        if (environment.acceptsProfiles(Profiles.of("prod")) && !requireTiDb) {
            throw new IllegalStateException("生产收件箱不得关闭 TiDB 权威门禁（game.inbox.require-tidb）");
        }
        return new StrongTiDbConnectionPolicy("inbox", requireTiDb);
    }

    /** 调用方鉴权密钥：缺失即拒启（Dubbo 提供方与 scene 提醒客户端都要）。 */
    @Bean
    DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    /** 收件箱三张表：核对权威库 → 建表 / 只扩不缩地同步 → 整库核对 shard。 */
    @Bean
    PbMysql inboxTables(DataSource dataSource, @Value("${game.inbox.require-tidb:true}") boolean requireTiDb) throws Exception {
        try (var connection = dataSource.getConnection()) {
            StrongTiDbConnectionPolicy.verify(connection, requireTiDb);
        }
        return InboxTables.sync(dataSource);
    }

    @Bean
    AuthorityJdbc inboxJdbc(DataSource dataSource, PbMysql inboxTables) {
        return new AuthorityJdbc(dataSource);
    }

    @Bean
    InboxRegistry inboxRegistry() {
        return InboxRegistry.load();
    }

    @Bean
    InboxMetrics inboxMetrics(MeterRegistry registry) {
        return new InboxMetrics(registry);
    }

    @Bean
    JdbcPlayerTables playerTables(AuthorityJdbc jdbc) {
        return new JdbcPlayerTables(jdbc);
    }

    @Bean
    NodeDirectory<SceneNodeInfo> sceneDirectory(RedissonClient redis, RedisKeys keys) {
        return new NodeDirectory<>(redis, keys, NodeTypes.SCENE, SceneNodeInfo.parser());
    }

    @Bean(destroyMethod = "close")
    NodeRpcClients<SceneInboxService> sceneInboxClients() {
        return new NodeRpcClients<>(SCENE_CLIENT_APPLICATION, SceneInboxService.class, DubboGroups.SCENE_INBOX, SCENE_CALL_TIMEOUT,
                "inbox-scene-connect");
    }

    @Bean
    Notifier sceneNotifier(RedissonClient redis, RedisKeys keys, NodeDirectory<SceneNodeInfo> sceneDirectory,
                           NodeRpcClients<SceneInboxService> clients, InboxMetrics metrics) {
        PlayerLocationDirectory locations = new PlayerLocationDirectory(redis, keys);
        return new SceneNotifier(locations::findHolderAsync, sceneDirectory::findAsync,
                (target, request, timeout) -> clients.call(target, timeout, service -> service.notifyInbox(request)), metrics);
    }

    @Bean
    ConsumerGate consumerGate(RedissonClient redis, RedisKeys keys, NodeDirectory<SceneNodeInfo> sceneDirectory) {
        RedissonWorldChannelStore world = new RedissonWorldChannelStore(redis, keys);
        return new SceneDirectoryConsumerGate(world::zones, sceneDirectory::list);
    }

    @Bean
    InboxStore inboxStore(AuthorityJdbc jdbc, InboxRegistry registry, InboxProperties properties, JdbcPlayerTables players,
                          ConsumerGate gate, Notifier notifier, InboxMetrics metrics) {
        Set<PlayerInboxMessageType> allowed = InboxRegistry.parseEnqueueTypes(properties.enqueueTypes());
        if (allowed.isEmpty()) {
            log.info("[PlayerInbox] 入箱白名单为空（game.inbox.enqueue-types）：GM 处置一律回 Disabled；Fetch / Ack 照常服务");
        } else {
            log.info("[PlayerInbox] 入箱白名单 {}；每次入箱前核对线上 scene 节点都已开消费（写前门禁）", allowed);
        }
        String secret = System.getenv(InboxCallContract.SECRET_ENV);
        ServiceCallSignatures signatures = new ServiceCallSignatures(java.util.Map.of(InboxCallContract.CALLER, secret == null ? "" : secret), System::currentTimeMillis);
        return new InboxStore(jdbc, registry, allowed, players, gate, notifier, metrics, System::currentTimeMillis, signatures);
    }

    @Bean
    InboxAdminStore inboxAdminStore(AuthorityJdbc jdbc, InboxRegistry registry, JdbcPlayerTables players, InboxMetrics metrics) {
        return new InboxAdminStore(jdbc, registry, players, metrics, System::currentTimeMillis);
    }

    @Bean
    InboxMaintenanceStore inboxMaintenanceStore(AuthorityJdbc jdbc, Notifier notifier, InboxMetrics metrics) {
        return new InboxMaintenanceStore(jdbc, notifier, metrics, System::currentTimeMillis);
    }

    @Bean
    GmSanctions gmSanctions(InboxStore store) {
        return new GmSanctions(store);
    }

    @Bean
    InboxMaintenance inboxMaintenance(RedissonClient redis, RedisKeys keys, InboxMaintenanceStore store, JdbcPlayerTables players,
                                      InboxMetrics metrics, InboxProperties properties) {
        LeaderLease lease = new LeaderLease(redis, keys, "inbox-" + UUID.randomUUID(), properties.leaderTtl());
        InboxMaintenance.Settings settings = new InboxMaintenance.Settings(properties.terminalRetention(), properties.sweepInterval(),
                properties.notifyResendInterval(), properties.gaugeInterval(), properties.reconcileInterval(), properties.reconcileWindow());
        return new InboxMaintenance(lease, store, players, metrics, settings, System::currentTimeMillis, System::nanoTime,
                bound -> bound <= 0 ? 0 : ThreadLocalRandom.current().nextLong(bound));
    }

    /** 运维接口 /admin/inbox/** 的鉴权（独立口令 GAME_INBOX_ADMIN_TOKEN；没配置一律 503）。 */
    @Bean
    FilterRegistrationBean<InboxAdminAuthFilter> inboxAdminAuthFilter() {
        InboxAdminAuthFilter filter = new InboxAdminAuthFilter(System.getenv(InboxAdminAuthFilter.TOKEN_ENV));
        if (!filter.tokenConfigured()) {
            log.warn("[PlayerInbox] 运维令牌 {} 未配置：/admin/inbox/** 一律 503（GM 处置、作废、对账都停用）", InboxAdminAuthFilter.TOKEN_ENV);
        }
        FilterRegistrationBean<InboxAdminAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/admin/inbox/*");
        return registration;
    }
}
