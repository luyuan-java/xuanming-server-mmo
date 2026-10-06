package com.game.scene;

import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.DestroyInstanceResponse;
import com.game.audit.AuditProperties;
import com.game.common.RunMode;
import com.game.common.token.DubboCallAuth;
import com.game.common.token.GmRequestAuth;
import com.game.common.token.GmShutdownHandler;
import com.game.common.token.NodeLinkAuth;
import com.game.contract.MessageIdRegistry;
import com.game.player.store.PlayerStore;
import com.game.scene.admin.GmShutdownController;
import com.game.scene.admin.SceneAdminAuthFilter;
import com.game.scene.admin.SceneAdminController;
import com.game.scene.attribute.AttributeTables;
import com.game.scene.bag.BagTables;
import com.game.scene.mission.MissionTables;
import com.game.scene.pet.PetTables;
import com.game.scene.skill.SkillTables;
import com.game.scene.battle.SceneBattleTables;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.ConfigSceneTables;
import com.game.scene.world.SceneTables;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Spring 装配：只提供不可变的共享件（消息号注册表、配置表）与 {@link SceneNode}；
 * 线程与连接全部由 SceneNode 在生命周期里创建和释放。
 * {@link RedissonClient} 来自 xm-discovery，{@link PlayerStore} 来自 xm-player-store 的自动装配。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SceneNodeProperties.class, AuditProperties.class})
public class SceneNodeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SceneNodeConfiguration.class);

    /** GM 停机受理到开始停机之间留给应答写出的时间。 */
    static final Duration GM_EXIT_DELAY = Duration.ofMillis(300);

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    /** 配置表快照（不可变）；各玩法的视图都从这一份构建，保证同一次加载。 */
    @Bean
    public ConfigTables configTables(SceneNodeProperties props) {
        ConfigTables tables = ConfigTables.load(Path.of(props.tableDir()));
        log.info("配置表已加载 dir={} 行数={}", Path.of(props.tableDir()).toAbsolutePath().normalize(), tables.rowCounts());
        return tables;
    }

    @Bean
    public SceneTables sceneTables(ConfigTables tables) {
        return ConfigSceneTables.from(tables);
    }

    @Bean
    public AttributeTables attributeTables(ConfigTables tables) {
        return AttributeTables.from(tables);
    }

    @Bean
    public BagTables bagTables(ConfigTables tables) {
        return BagTables.from(tables);
    }

    @Bean
    public MissionTables missionTables(ConfigTables tables) {
        return MissionTables.from(tables);
    }

    @Bean
    public SkillTables skillTables(ConfigTables tables) {
        return SkillTables.from(tables);
    }

    @Bean
    public PetTables petTables(ConfigTables tables) {
        return PetTables.from(tables);
    }

    /** gate 链路握手鉴权。密钥只从环境变量 {@code XM_NODE_LINK_SECRET} 读，缺失即启动失败（不允许无鉴权的链路）。 */
    @Bean
    public NodeLinkAuth nodeLinkAuth(Environment environment) {
        return NodeLinkAuth.requireFromEnvValue(environment.getProperty(NodeLinkAuth.SECRET_ENV));
    }

    /**
     * 资产通道的 Dubbo 提供方（{@code SceneAssetOpService}）要求调用方鉴权：密钥只从环境变量 {@code XM_DUBBO_SECRET} 读，缺失即启动失败
     * （xm-api 的提供方过滤器缺密钥时导出也会失败，这里在单例创建阶段就给出明确原因，不等到 SceneNode 启动一半）。
     * 过滤器自己从环境变量读密钥，这个 bean 只作启动校验。
     */
    @Bean
    public DubboCallAuth sceneDubboCallAuth(Environment environment) {
        return DubboCallAuth.requireFromEnvValue(environment.getProperty(DubboCallAuth.SECRET_ENV));
    }

    /** scene 指标，注册到 actuator 提供的注册表（Prometheus 导出，见 architecture.md §11）。 */
    @Bean
    public SceneMetrics sceneMetrics(MeterRegistry meterRegistry) {
        return new SceneMetrics(meterRegistry);
    }

    /** 回合制战斗的指标（scene-battle-spec §9）。 */
    @Bean
    public SceneBattleMetrics sceneBattleMetrics(MeterRegistry meterRegistry) {
        return new SceneBattleMetrics(meterRegistry);
    }

    /** 回合制战斗的配表视图（指纹在启动时算一次，scene 没有表热更）。 */
    @Bean
    public SceneBattleTables sceneBattleTables(ConfigTables tables) {
        SceneBattleTables battleTables = SceneBattleTables.from(tables);
        log.info("战斗配表指纹 table_fingerprint={}", battleTables.fingerprint());
        return battleTables;
    }

    @Bean
    public SceneNode sceneNode(SceneNodeProperties props, RedissonClient redis, PlayerStore playerStore,
                               MessageIdRegistry registry, SceneTables tables, AttributeTables attributeTables,
                               BagTables bagTables, MissionTables missionTables, SkillTables skillTables,
                               PetTables petTables, NodeLinkAuth nodeLinkAuth,
                               SceneMetrics sceneMetrics, AuditProperties audit, SceneBattleTables sceneBattleTables,
                               SceneBattleMetrics sceneBattleMetrics) {
        return new SceneNode(props, redis, playerStore, registry, tables, attributeTables, bagTables, missionTables,
                skillTables, petTables, nodeLinkAuth, sceneMetrics, audit, sceneBattleTables, sceneBattleMetrics);
    }

    /** GM 签名停机的校验（密钥只从环境变量 XM_GM_ADMIN_SECRET 读，没配一律拒）。 */
    @Bean
    public GmRequestAuth gmRequestAuth(Environment environment) {
        GmRequestAuth auth = GmRequestAuth.fromEnvValues(environment.getProperty(GmRequestAuth.SECRET_ENV),
                environment.getProperty(GmRequestAuth.SKEW_ENV), () -> System.currentTimeMillis() / 1000);
        if (!auth.configured()) {
            log.info("没配 {}：GM 签名停机一律拒绝", GmRequestAuth.SECRET_ENV);
        }
        return auth;
    }

    /**
     * GM 签名停机的受理（只收本机来的请求；确需远程调用时设 XM_GM_ALLOW_REMOTE=true——管理端口为了 Prometheus 跨机抓取
     * 绑到内网地址时，停机接口不该跟着暴露）。
     */
    @Bean
    public GmShutdownHandler gmShutdownHandler(GmRequestAuth gmRequestAuth, SceneNode sceneNode,
                                               @Value("${XM_GM_ALLOW_REMOTE:false}") boolean allowRemote) {
        return new GmShutdownHandler(GmShutdownController.METHOD, gmRequestAuth,
                () -> new GmShutdownHandler.Identity(sceneNode.zoneId(), sceneNode.nodeId(), sceneNode.instanceId()),
                sceneNode::onlinePlayerCount, allowRemote);
    }

    /** dev 实例管理口（批次 5.3 §6.13）的业务入口：投到场景逻辑线程执行（{@link SceneNode#createDungeonInstance} / {@link SceneNode#destroyInstance}）。 */
    @Bean
    public SceneAdminController.InstanceAdmin sceneInstanceAdmin(SceneNode sceneNode) {
        return new SceneAdminController.InstanceAdmin() {
            @Override
            public CompletableFuture<CreateDungeonInstanceResponse> createDungeon(int dungeonConfigId) {
                return sceneNode.createDungeonInstance(dungeonConfigId);
            }

            @Override
            public CompletableFuture<DestroyInstanceResponse> destroyInstance(long sceneId) {
                return sceneNode.destroyInstance(sceneId);
            }
        };
    }

    /**
     * 管理端口 {@code /admin/*} 的运维鉴权（容器按规范路径匹配；令牌只从环境变量 {@code XM_ADMIN_TOKEN} 读，没配一律 503）。
     * 只覆盖 dev 实例管理口；actuator 与 GM 签名停机 {@code /gm/**} 不在这个前缀下。
     */
    @Bean
    public FilterRegistrationBean<SceneAdminAuthFilter> sceneAdminAuthFilter(SceneMetrics sceneMetrics,
                                                                             SceneNodeProperties props) {
        SceneAdminAuthFilter filter = new SceneAdminAuthFilter(System.getenv(SceneAdminAuthFilter.TOKEN_ENV), sceneMetrics);
        RunMode runMode = RunMode.parse(props.runMode());
        if (!filter.tokenConfigured()) {
            log.info("运维令牌 {} 未配置：管理端口 /admin/**（dev 实例管理口）一律 503", SceneAdminAuthFilter.TOKEN_ENV);
        } else {
            log.info("dev 实例管理口 {}（运行模式 {}）", runMode.allowsGmCommands() ? "开放" : "关闭（403）", runMode);
        }
        FilterRegistrationBean<SceneAdminAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    /** GM 停机受理后：等应答写出，再按正常流程关 Spring 上下文（触发 {@link SceneNode#stop()} 写回全部玩家）并退出进程。 */
    @Bean
    public GmShutdownController.ProcessExit gmProcessExit(ConfigurableApplicationContext context) {
        return () -> Thread.ofPlatform().name("gm-shutdown").start(() -> {
            LockSupport.parkNanos(GM_EXIT_DELAY.toNanos());
            System.exit(SpringApplication.exit(context, () -> 0));
        });
    }
}
