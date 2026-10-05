package com.game.team;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.id.Snowflake;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerProfiles;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.team.dispatch.TeamDispatcher;
import com.game.team.dispatch.TeamWorkerPool;
import com.game.team.id.TeamIds;
import com.game.team.match.NoTeamBattle;
import com.game.team.metrics.TeamMetrics;
import com.game.team.presence.TeamDisplay;
import com.game.team.presence.TeamSessions;
import com.game.team.push.TeamPushes;
import com.game.team.rules.RuleConfig;
import com.game.team.service.TeamMethods;
import com.game.team.service.TeamService;
import com.game.team.store.RedissonTeamRedis;
import com.game.team.store.TeamStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.sql.DataSource;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * xm-team 的装配。所有依赖显式经构造参数传入业务类；这里是唯一读配置、碰外部系统的地方。
 */
@Configuration(proxyBeanMethods = false)
public class TeamConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TeamConfiguration.class);

    /**
     * team_id 雪花 worker 的租约作用域。故意<b>不按 zone 分</b>（理由同 {@code NodeTypes.SCENE_GUID}）：team_id 全服唯一，
     * 按 zone 各占 [0, 1023] 会让不同 zone 的 xm-team 拿到同一个 worker、发出逐位相同的号。0 表示「全服」。
     */
    static final int TEAM_ID_LEASE_SCOPE = 0;
    static final Duration LEASE_TTL = Duration.ofSeconds(15);
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public TeamMetrics teamMetrics(MeterRegistry meterRegistry, TeamProperties props) {
        return new TeamMetrics(meterRegistry, props.allowCrossZone());
    }

    @Bean
    public TeamStore teamStore(RedissonClient redis) {
        return new TeamStore(new RedissonTeamRedis(redis));
    }

    @Bean
    public PlayerPresenceDirectory playerPresenceDirectory(RedissonClient redis) {
        return new PlayerPresenceDirectory(redis);
    }

    @Bean
    public PlayerLocationDirectory playerLocationDirectory(RedissonClient redis) {
        return new PlayerLocationDirectory(redis);
    }

    @Bean
    public PlayerPushes playerPushes(RedissonClient redis, PlayerPresenceDirectory presence) {
        return new PlayerPushes(redis, presence);
    }

    /** 只读 {@code xm_java.player}（展示资料与 home zone；这张表不归 xm-team 所有）。 */
    @Bean
    public PlayerProfiles playerProfiles(DataSource dataSource, TeamProperties props) {
        // Druid 能按次限等连接：取连接不超过请求剩余预算（连接池的固定 max-wait 不认预算，见 PlayerProfiles.ConnectionSource）
        PlayerProfiles.ConnectionSource connections = dataSource instanceof DruidDataSource druid
                ? druid::getConnection : maxWait -> dataSource.getConnection();
        return new PlayerProfiles(connections, props.queryTimeoutCapSeconds());
    }

    @Bean
    public TeamSessions teamSessions(PlayerPresenceDirectory presence, PlayerLocationDirectory locations) {
        return new TeamSessions(presence::findEachStrictAsync, locations::statusesAsync, presence::findStrictAsync);
    }

    @Bean
    public TeamDisplay teamDisplay(PlayerProfiles profiles, PlayerPresenceDirectory presence) {
        return new TeamDisplay(profiles::load, presence::findAllAsync);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService teamLeaseScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("team-lease").daemon(true).factory());
    }

    /**
     * team_id 雪花 worker 租约（{@code NodeTypes.TEAM}，作用域 0）。租约无效（丢失，或续期滞后超过 2/3 TTL）期间 {@link TeamIds}
     * 拒绝发号，CreateTeam 回 4030 + 空视图（其余 RPC 不受影响）；续期滞后恢复后自动恢复，丢失则需重启。
     */
    @Bean(destroyMethod = "close")
    public NodeIdLease teamIdLease(RedissonClient redis, ScheduledExecutorService teamLeaseScheduler) {
        return NodeIdLease.acquire(redis, teamLeaseScheduler, NodeTypes.TEAM, TEAM_ID_LEASE_SCOPE, 0, Snowflake.MAX_WORKER,
                UUID.randomUUID().toString(), LEASE_TTL,
                () -> log.error("team_id 雪花 worker 租约丢失：停止建队（新的 CreateTeam 一律 4030），需要重启本进程"));
    }

    @Bean
    public TeamIds teamIds(NodeIdLease teamIdLease) {
        log.info("team_id 雪花 worker={}", teamIdLease.nodeId());
        return new TeamIds(new Snowflake(teamIdLease.nodeId()), teamIdLease::isValid);
    }

    /** 依赖推送池：Spring 按依赖逆序销毁，请求池先排空（排空中的请求还会往推送池投推送），推送池后关。 */
    @Bean(destroyMethod = "close")
    @DependsOn("teamPushPool")
    public TeamWorkerPool teamWorkerPool(TeamProperties props) {
        return new TeamWorkerPool(TeamWorkerPool.WORKER, props.workerThreads(), props.workerQueueCapacity(), DRAIN_TIMEOUT);
    }

    @Bean(destroyMethod = "close")
    public TeamWorkerPool teamPushPool(TeamProperties props) {
        return new TeamWorkerPool(TeamWorkerPool.PUSH, props.pushThreads(), props.pushQueueCapacity(), DRAIN_TIMEOUT);
    }

    @Bean
    public TeamPushes teamPushes(TeamStore store, TeamDisplay display, PlayerPushes pushes,
                                 @Qualifier("teamPushPool") TeamWorkerPool teamPushPool, TeamMetrics metrics,
                                 TeamProperties props, MessageIdRegistry registry) {
        return new TeamPushes(store, display, pushes::pushToPlayer, teamPushPool, metrics, props.pushBatchBudget(),
                new TeamPushes.MessageIds(
                        registry.requireId(TeamMethods.SERVICE, TeamMethods.NOTIFY_TEAM_SNAPSHOT),
                        registry.requireId(TeamMethods.SERVICE, TeamMethods.NOTIFY_TEAM_INVITE),
                        registry.requireId(TeamMethods.SERVICE, TeamMethods.NOTIFY_TEAM_EVENT)));
    }

    @Bean
    public TeamService teamService(TeamStore store, TeamSessions sessions, TeamDisplay display, PlayerProfiles profiles,
                                   TeamIds teamIds, TeamPushes pushes, TeamMetrics metrics, TeamProperties props) {
        PlayerHomeZones homeZones = new PlayerHomeZones(profiles::loadStrict, props.homeZoneTimeout());
        return new TeamService(store, sessions, display, homeZones, teamIds::nextId, NoTeamBattle.INSTANCE, pushes, metrics,
                new RuleConfig(props.allowCrossZone()));
    }

    @Bean
    public TeamDispatcher teamDispatcher(MessageIdRegistry registry, TeamService service,
                                         @Qualifier("teamWorkerPool") TeamWorkerPool teamWorkerPool, TeamMetrics metrics,
                                         TeamProperties props) {
        TeamDispatcher dispatcher = new TeamDispatcher(registry, service, teamWorkerPool, metrics,
                props.requestBudget().toMillis());
        log.info("team 接管的消息号={} 推送占位={} 跨区组队={} 请求预算={} 推送批预算={}", dispatcher.routedMessageIds(),
                dispatcher.pushMessageIds(), props.allowCrossZone(), props.requestBudget(), props.pushBatchBudget());
        return dispatcher;
    }

    /** Dubbo 调用鉴权密钥的启动检查（fail-fast）；签名 / 校验在 xm-api 的 Dubbo 过滤器里。 */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }
}
