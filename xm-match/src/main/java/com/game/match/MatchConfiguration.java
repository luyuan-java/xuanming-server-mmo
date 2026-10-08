package com.game.match;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.SceneBattleService;
import com.game.api.match.MatchBudgets;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.RunMode;
import com.game.common.id.Snowflake;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisProperties;
import com.game.discovery.battle.BattleLockReader;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.match.admin.MatchAdminAuthFilter;
import com.game.match.dispatch.MatchWorkerPool;
import com.game.match.gather.GatherHooks;
import com.game.match.gather.GatherLauncher;
import com.game.match.id.MatchIds;
import com.game.match.lifecycle.MatchLeaseHealthIndicator;
import com.game.match.lifecycle.MatchLifecycle;
import com.game.match.lifecycle.MatchStartupChecks;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.lifecycle.ResultConsumerControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.NodeCalls;
import com.game.match.port.PlayerPusher;
import com.game.match.port.PlayerStatusReader;
import com.game.match.port.RedisClock;
import com.game.match.port.RedisPlayerStatusReader;
import com.game.match.port.RedissonRedisClock;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * xm-match 的<b>基础设施</b>装配（match-spec §9.1、§9.7、§9.8）：启动门禁（密钥、配置表）、发号租约、指标、对别的进程的只读目录与出站口。
 * 业务 bean 不放这里——各包自带自己的 {@code XxxConfiguration}，经下面的接口互相往来，谁也不直接 new 别的包的实现：
 *
 * <table>
 *   <caption>包与包之间的接口（并行开发的协调面；契约见各接口的注释）</caption>
 *   <tr><th>接口</th><th>实现在</th><th>谁用</th></tr>
 *   <tr><td>{@code ticket.TicketStore} / {@code TicketReader} / {@code TicketHealing}</td><td>ticket 包</td><td>排队、凑单、gather、预检、整队、活动</td></tr>
 *   <tr><td>{@code rating.RatingReader}</td><td>rating 包</td><td>排队（入队读分）、gather（5V5 分队）</td></tr>
 *   <tr><td>{@code gather.GatherLauncher}</td><td>gather 包</td><td>凑单、PVE_SOLO、切磋、整队、活动</td></tr>
 *   <tr><td>{@code gather.BattleNodes}</td><td>gather 包</td><td>gather、凑单（暂停判定）、落点直拨（判死）</td></tr>
 *   <tr><td>{@code gather.GatherHooks}</td><td>本类给空实现；6.5 换成观战的</td><td>gather</td></tr>
 *   <tr><td>{@code placement.PlacementStore} / {@code PlacementDialer}</td><td>placement 包</td><td>gather、补签 179、6.5</td></tr>
 *   <tr><td>{@code precheck.MemberPrecheck}</td><td>precheck 包（{@code PrecheckConfiguration}）</td><td>整队、活动</td></tr>
 *   <tr><td>{@code challenge.ChallengeStore} / {@code MatchPushExecutor}（{@code match-push}）</td><td>challenge 包</td><td>切磋</td></tr>
 *   <tr><td>{@code activity.ActivityBattleService}</td><td>activity 包（{@code ActivityConfiguration}）</td>
 *       <td>活动开战的 Dubbo 提供方与 dev 管理口共用</td></tr>
 *   <tr><td>{@code dispatch.MatchMethodHandler}</td><td>排队、切磋、补签各包提供处理器 bean</td><td>派发器</td></tr>
 *   <tr><td>{@code dispatch.MatchWorkers}</td><td>dispatch 包（{@code match-worker} 工作池）</td><td>派发器、整队 / 活动两个内部接口的提供方</td></tr>
 *   <tr><td>{@code port.PlayerStatusReader} / {@code RedisClock} / {@code NodeCalls} / {@code PlayerPusher}</td><td>本类（包 xm-discovery / xm-api）</td>
 *       <td>各包按需注入</td></tr>
 *   <tr><td>{@code lifecycle.MatcherControl} / {@code ResultConsumerControl}</td><td>凑单包的 {@code matcher.MatcherRunner}、评分包的
 *       {@code rating.BattleResultIngest}（都不得自带启停）</td><td>{@link MatchLifecycle}（启动第 8、9 步与停机）</td></tr>
 *   <tr><td>{@code admin.MatchAdminAuthFilter}</td><td>本类登记在 {@code /admin/*}</td><td>dev 管理口的控制器（令牌、操作人、运行模式都已在过滤器里判过）</td></tr>
 * </table>
 *
 * <p><b>启动门禁</b>（match-spec §9.8 的九步；前六步由 bean 的参数依赖钉住次序，任一步失败即拒绝启动）：
 * <ol>
 *   <li>运行模式（{@link #matchRunMode}；不认识的值按 prod 并告警，不拒启）；{@code xm.match.*} 的绑定与自身校验（{@link MatchProperties}，
 *       含指纹模式的枚举绑定）；</li>
 *   <li>{@code XM_DUBBO_SECRET}（{@link #dubboCallAuth}）。{@code XM_MYSQL_PASSWORD} 允许为空，与其它模块一致，不单独拒启；</li>
 *   <li>配置表（{@link #matchConfigTables}）；PVE 组队人数表的副本 id 都在 Dungeon 表里；</li>
 *   <li>预算断言：Redis 单条命令最坏耗时 ≤ 6100 ms、各跳超时不超上限、matched TTL 表仍是基线值（第 3、4 步在 {@link #matchStartupChecks}）；</li>
 *   <li>发号租约（{@link #matchIdLease}）；</li>
 *   <li>评分两张表的建表（评分包的 bean）；其余单例；</li>
 *   <li>Dubbo 导出（上下文刷新完成时）；</li>
 *   <li>起凑单；</li>
 *   <li>起评分消费（对局结果 topic 首次核对同步做、至多等 init-timeout：与契约不符拒启，Kafka 不可达只告警）。第 8、9 步与停机次序见
 *       {@link MatchLifecycle}。</li>
 * </ol>
 * 销毁时 Spring 按依赖逆序：先停用到租约与直连客户端的业务 bean，再还租约、关直连客户端。
 *
 * <p><b>别的包的 bean 都是硬依赖</b>：凑单 / 评分消费的启停口、开局管线、十个号的处理器，缺任何一个都拒绝启动（{@link #matchLifecycle} 直接注入；
 * 处理器不全由 {@code MatchDispatchConfiguration} 拒）——少一包的进程会照收请求而永不成局 / 不入账，不如不起。包与包之间不用
 * {@code @ConditionalOnMissingBean} 给缺省 bean：组件扫描到的配置类之间，它的判定取决于类的扫描次序（随平台而变），不可靠。
 */
@Configuration(proxyBeanMethods = false)
public class MatchConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MatchConfiguration.class);

    /** battle_id / challenge_id 雪花 worker 的租约作用域：0 = 全服（号要全服唯一，不按 zone 分；理由同 {@code NodeTypes.SCENE_GUID}）。 */
    static final int ID_LEASE_SCOPE = 0;
    static final Duration LEASE_TTL = Duration.ofSeconds(15);

    // ================================================================ 启动门禁与只读数据

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    /**
     * 第 2 步：Dubbo 调用鉴权密钥的启动检查（fail-fast）。签名 / 校验在 xm-api 的 Dubbo 过滤器里（过滤器自己读环境变量，缺密钥时导出也会失败）；
     * 这个 bean 只为在建 bean 阶段就给出明确的原因。
     */
    @Bean
    public DubboCallAuth dubboCallAuth(Environment environment) {
        return DubboCallAuth.requireFromEnvValue(environment.getProperty(DubboCallAuth.SECRET_ENV));
    }

    /** 运行模式（{@code xm.run-mode}）：只有 dev / test 开放 dev 管理口；不认识的值按 prod 并告警。 */
    @Bean
    public RunMode matchRunMode(@Value("${xm.run-mode:prod}") String value) {
        if (!RunMode.isRecognized(value)) {
            log.warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（dev 管理口回 403）: '{}'", value);
        }
        return RunMode.parse(value);
    }

    /** 配置表快照（加载失败拒启）：PVE 组队人数的副本 id 校验、指标的 config 标签净化读 Dungeon 表。 */
    @Bean
    public ConfigTables matchConfigTables(@Value("${xm.table-dir:config-data/tables}") String tableDir, DubboCallAuth dubboCallAuth) {
        Path dir = Path.of(tableDir);
        ConfigTables tables = ConfigTables.load(dir);
        log.info("配置表已加载 dir={} dungeon={}", dir.toAbsolutePath().normalize(), tables.dungeon().size());
        return tables;
    }

    /**
     * 第 3、4 步：PVE 组队人数表的副本 id 都在 Dungeon 表里；预算断言（Redis 单条命令最坏耗时、各跳超时、matched TTL 表）。不过即拒启。
     * 返回的凭据给后面的步骤当参数，钉住「先查完再申领租约」。
     */
    @Bean
    public MatchStartupChecks.Passed matchStartupChecks(MatchProperties props, ConfigTables matchConfigTables, RedisProperties redisProperties) {
        MatchStartupChecks.Passed passed = MatchStartupChecks.verify(props.pveTeamSizeByConfigId(), matchConfigTables.dungeon()::contains,
                redisProperties.worstCaseCommandMillis());
        log.info("启动门禁通过：PVE 组队人数表 {}（副本 id 都在 Dungeon 表里） Redis 单条命令最坏≈{} ms（≤ {} ms） 指纹模式={}",
                props.pveTeamSizeByConfigId(), passed.redisWorstCaseMillis(), MatchBudgets.PLACEMENT_WRITE_WORST_MS,
                props.tableFingerprintMode());
        return passed;
    }

    @Bean
    public MetricLabels matchMetricLabels(ConfigTables matchConfigTables) {
        return new MetricLabels(matchConfigTables.dungeon()::contains);
    }

    @Bean
    public MatchMetrics matchMetrics(MeterRegistry meterRegistry, MetricLabels matchMetricLabels) {
        return new MatchMetrics(meterRegistry, matchMetricLabels);
    }

    // ================================================================ 发号

    @Bean
    public MatchInstance matchInstance() {
        return new MatchInstance(UUID.randomUUID().toString());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService matchLeaseScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("match-lease").daemon(true).factory());
    }

    /**
     * 第 5 步：雪花 worker 租约（{@code NodeTypes.MATCH}，作用域 0）。申领失败（号段全被占、Redis 不可达）抛异常 → 拒启。
     * 续期滞后（超过 2/3 TTL）期间停止发号、恢复后自动恢复；<b>真正丢失不会自愈</b>（lead 裁决 2）：回调里记 ERROR、置 {@code xm_match_lease_lost = 1}；
     * 之后派发器拒收 157 / 152（{@code MatchDispatcher}），整队 / 活动 / PVE_SOLO 这些要发号的入口本来就按 {@link MatchIds#leaseValid()} 拒，
     * 凑单暂停，健康检查 DOWN（{@link #matchLeaseHealthIndicator}）。{@code matchStartupChecks} 参数只为钉住次序：门禁没过不去占号。
     */
    @Bean(destroyMethod = "close")
    public NodeIdLease matchIdLease(RedissonClient redis, @Qualifier("matchLeaseScheduler") ScheduledExecutorService matchLeaseScheduler,
                                    MatchInstance matchInstance, MatchMetrics matchMetrics, MatchStartupChecks.Passed matchStartupChecks) {
        return NodeIdLease.acquire(redis, matchLeaseScheduler, NodeTypes.MATCH, ID_LEASE_SCOPE, 0, Snowflake.MAX_WORKER,
                matchInstance.id(), LEASE_TTL, () -> {
                    matchMetrics.leaseLost(true);
                    log.error("match 发号租约丢失：停止发 battle_id / challenge_id，排队与一切开局入口一律拒绝（取消排队、查状态、补签不受影响）；"
                            + "不会自愈，需要重启本进程");
                });
    }

    @Bean
    public MatchIds matchIds(NodeIdLease matchIdLease) {
        log.info("match 雪花 worker={}（battle_id / challenge_id）", matchIdLease.nodeId());
        return new MatchIds(new Snowflake(matchIdLease.nodeId()), matchIdLease::isValid, matchIdLease::isLost);
    }

    /** 健康组件 {@code matchLease}：租约真正丢失时 DOWN（{@code /actuator/health} 回 503，等编排层重启）；续期滞后仍 UP。 */
    @Bean
    public MatchLeaseHealthIndicator matchLeaseHealthIndicator(MatchIds matchIds) {
        return new MatchLeaseHealthIndicator(matchIds);
    }

    // ================================================================ 启停次序（第 8、9 步与停机）

    /**
     * 凑单 / 评分消费的启动与整个停机序列（{@link MatchLifecycle}）。三样东西都来自别的包（凑单包的 {@code MatcherRunner}、评分包的
     * {@code BattleResultIngest}、gather 包的 {@code VirtualThreadGatherLauncher}），都是硬依赖：缺任何一个、或同一个接口出现两个 bean，
     * 上下文起不来（测试里要换掉其中一个时把替身标 {@code @Primary}）。
     */
    @Bean
    public MatchLifecycle matchLifecycle(MatcherControl matcherControl, ResultConsumerControl resultConsumerControl,
                                         GatherLauncher gatherLauncher, MatchWorkerPool matchWorkerPool) {
        return new MatchLifecycle(matcherControl, resultConsumerControl, gatherLauncher, matchWorkerPool::close,
                MatchLifecycle.GATHER_DRAIN_TIMEOUT);
    }

    // ================================================================ 管理端口：dev / test 管理口的鉴权

    /**
     * 只注册在 {@code /admin/*}（容器按规范路径匹配）；令牌只从环境变量 {@code XM_ADMIN_TOKEN} 读。运行模式不是 dev / test 时
     * {@code /admin/match/dev/*} 一律 403（在令牌与操作人都过了之后）。
     */
    @Bean
    public FilterRegistrationBean<MatchAdminAuthFilter> matchAdminAuthFilter(Environment environment, RunMode matchRunMode, MatchMetrics matchMetrics) {
        MatchAdminAuthFilter filter = new MatchAdminAuthFilter(environment.getProperty(MatchAdminAuthFilter.TOKEN_ENV), matchRunMode, matchMetrics);
        if (!filter.tokenConfigured()) {
            log.warn("运维令牌 {} 未配置：管理端口 /admin/** 一律 503", MatchAdminAuthFilter.TOKEN_ENV);
        } else if (!filter.devEndpointsOpen()) {
            log.info("运行模式 {}：dev 管理口 {}* 一律 403", matchRunMode, MatchAdminAuthFilter.DEV_PREFIX);
        }
        FilterRegistrationBean<MatchAdminAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    // ================================================================ 只读目录（都是别的进程写的键）

    /** battle 节点目录 {@code xm:nodes:battle:0}（全服一个池）。 */
    @Bean
    public NodeDirectory<BattleNodeInfo> battleNodeDirectory(RedissonClient redis) {
        return new NodeDirectory<>(redis, NodeTypes.BATTLE, BattleNodeInfo.parser());
    }

    /** scene 节点目录 {@code xm:nodes:scene:<zone>}（按玩家位置记录里的 zone 读）。 */
    @Bean
    public NodeDirectory<SceneNodeInfo> sceneNodeDirectory(RedissonClient redis) {
        return new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
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

    /** 战斗锁的只读工具（咨询性读，权威在 scene 的备战写锁）。 */
    @Bean
    public BattleLockReader battleLockReader(RedissonClient redis) {
        return new BattleLockReader(redis);
    }

    /**
     * 玩家 → 持有他的 scene 节点的直连地址（位置记录 → 按 (zone, 节点号) 读 scene 目录；判定顺序见 {@link SceneAssetLocator}）。
     * scene 的战斗入口与资产通道在同一个端口上，所以 gather 的备战定位直接复用它。
     */
    @Bean
    public SceneAssetLocator sceneLocator(PlayerLocationDirectory locations, NodeDirectory<SceneNodeInfo> sceneNodeDirectory) {
        return new SceneAssetLocator(locations, sceneNodeDirectory, result -> { });
    }

    // ================================================================ 出站口

    /** scene 的回合制战斗入口（备战 / 取消）的直连客户端缓存；引用上的缺省超时是备战的 3 s，每次调用再按次给。 */
    @Bean(destroyMethod = "close")
    public NodeRpcClients<SceneBattleService> sceneBattleClients() {
        return new NodeRpcClients<>("xm-match-scene-battle", SceneBattleService.class, DubboGroups.SCENE_BATTLE,
                Duration.ofMillis(MatchBudgets.PREPARE_BATTLE_TIMEOUT_MS), "match-scene-connect");
    }

    /** battle 节点控制面（建房 / 销毁 / 补签）的直连客户端缓存。 */
    @Bean(destroyMethod = "close")
    public NodeRpcClients<BattleNodeService> battleNodeClients() {
        return new NodeRpcClients<>("xm-match-battle-node", BattleNodeService.class, DubboGroups.BATTLE_NODE,
                Duration.ofMillis(MatchBudgets.CREATE_BATTLE_TIMEOUT_MS), "match-battle-connect");
    }

    @Bean
    public NodeCalls<SceneBattleService> sceneBattleCalls(NodeRpcClients<SceneBattleService> sceneBattleClients) {
        return sceneBattleClients::call;
    }

    @Bean
    public NodeCalls<BattleNodeService> battleNodeCalls(NodeRpcClients<BattleNodeService> battleNodeClients) {
        return battleNodeClients::call;
    }

    @Bean
    public RedisClock redisClock(RedissonClient redis) {
        return new RedissonRedisClock(redis);
    }

    @Bean
    public PlayerStatusReader playerStatusReader(BattleLockReader locks, PlayerPresenceDirectory presence, PlayerLocationDirectory locations) {
        return new RedisPlayerStatusReader(locks, presence, locations);
    }

    @Bean
    public PlayerPusher playerPusher(PlayerPushes pushes) {
        return pushes::pushToPlayer;
    }

    /**
     * 开局管线留给观战的接缝：6.4 是空实现。<b>不带条件装配</b>（{@code @ConditionalOnMissingBean} 在组件扫描到的配置类之间不可靠，见类注释）：
     * 6.5 提供自己的 {@link GatherHooks} bean 时把这个 bean 方法删掉；测试里要换钩子就把替身标 {@code @Primary}。
     */
    @Bean
    public GatherHooks gatherHooks() {
        return GatherHooks.NOOP;
    }
}
