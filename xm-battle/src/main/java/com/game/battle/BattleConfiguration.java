package com.game.battle;

import com.game.battle.BattleTables.RowReport;
import com.game.battle.admin.BattleAdminAuthFilter;
import com.game.battle.admin.DevBattleBackend;
import com.game.battle.admin.DevRoutingResolver;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.engine.TableBattleData;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.port.ActivityResultSink;
import com.game.battle.port.BattleResultSink;
import com.game.battle.port.LoggingBattleResultSink;
import com.game.battle.port.SceneBattleEvents;
import com.game.battle.port.SettlementSink;
import com.game.battle.port.scene.SceneTransport;
import com.game.battle.port.scene.SceneTransportProperties;
import com.game.battle.outbox.OutboxMetrics;
import com.game.discovery.RedisProperties;
import org.springframework.beans.factory.ObjectProvider;
import com.game.battle.protocol.BattleMessageIds;
import com.game.battle.push.LobbyAnnouncer;
import com.game.battle.push.PresenceLobbyAnnouncer;
import com.game.battle.room.BattleClock;
import com.game.common.RunMode;
import com.game.common.token.BattleSecretPolicy;
import com.game.common.token.BattleTickets;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.presence.PlayerPushes;
import com.game.net.limit.MessageLimits;
import com.game.net.limit.TableMessageLimits;
import com.game.table.ConfigTables;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.Locale;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * xm-battle 的 Spring 装配（battle-node-spec §7.1、§7.11 第 1–3 步）：启动门禁、启动时加载一次的只读数据、指标、准入闸、出站端口与
 * {@link BattleNode}。线程（单逻辑线程 = 直连面唯一的 I/O EventLoop、调度线程、回复执行器）、租约、Dubbo 导出与直连端口都由 {@link BattleNode}
 * 在生命周期里创建和释放，不是 bean。
 *
 * <p><b>启动门禁</b>（任一不过即拒绝启动，都发生在建 bean 阶段、任何端口打开之前）：
 * <ul>
 *   <li>票据密钥 {@value #TICKET_SECRET_ENV}：缺失 / 空白任何模式拒启；太短或与 {@value #GATE_SECRET_ENV}（进程能看到时）相同 → prod 拒启、
 *       dev / test 只 WARN（{@link BattleSecretPolicy}，§11 N5、Q11）；</li>
 *   <li>{@code XM_DUBBO_SECRET} 缺失拒启（控制面提供方要验调用方 MAC）；</li>
 *   <li>prod 下 {@code xm.battle.max-connections = 0} 拒启；{@code handshake-timeout} 越界、{@code table-fingerprint-mode} 写错由
 *       {@link BattleProperties} 绑定时拒启（§11 N18）；</li>
 *   <li>配表加载 / {@code TableBattleData} 构造失败拒启；四张关键战斗表为空只打 ERROR、Item 为空只打 WARN，<b>不拒启</b>（同基线）；
 *       11 个消息号缺任何一个拒启。</li>
 * </ul>
 * 运行模式读 {@code xm.run-mode}（{@code XM_RUN_MODE}），不认识的值按 prod 处理并 WARN（§11 N8）。
 *
 * <p>出站端口：{@link SceneBattleEvents} / {@link SettlementSink} / {@link ActivityResultSink} 自 6.3 起是真实传输（{@link SceneTransport}）；
 * {@link BattleResultSink} 仍是日志缺省实现（6.4 接 match）。测试提供同类型的 bean 时这里的缺省实现让位（{@link ConditionalOnMissingBean}）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BattleProperties.class, SceneTransportProperties.class})
public class BattleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BattleConfiguration.class);

    /** 票据签名密钥（只从环境变量读，必填）。 */
    public static final String TICKET_SECRET_ENV = "XM_BATTLE_TOKEN_SECRET";
    /** gate 令牌密钥（可选：进程能看到时用于「battle 密钥不得与 gate 相同」检查，Q11）。 */
    public static final String GATE_SECRET_ENV = "XM_GATE_TOKEN_SECRET";

    /** 运行模式（{@code xm.run-mode}）；不认识的值按 prod 并告警。 */
    @Bean
    public RunMode battleRunMode(@Value("${xm.run-mode:prod}") String value) {
        if (!RunMode.isRecognized(value)) {
            log.warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（dev 管理接口回 403、票据密钥门禁按 prod 判定）: '{}'", value);
        }
        return RunMode.parse(value);
    }

    /**
     * 票据签名器（房间签票与直连面验签共用一个实例）。密钥只从环境变量读；门禁见类注释。签名用密钥原始字节（不去空白）。
     */
    @Bean
    public BattleTickets battleTickets(Environment environment, RunMode battleRunMode) {
        String secret = environment.getProperty(TICKET_SECRET_ENV);
        String gateSecret = environment.getProperty(GATE_SECRET_ENV);
        BattleSecretPolicy.Verdict verdict = BattleSecretPolicy.check(secret, gateSecret, battleRunMode);
        switch (verdict.action()) {
            case REFUSE -> throw new IllegalStateException("拒绝启动：" + verdict.describe() + "（run_mode="
                    + battleRunMode.name().toLowerCase(Locale.ROOT) + "）");
            case WARN -> log.warn("安全告警：{}；只因 run_mode={} 才放行，生产必须修正", verdict.describe(),
                    battleRunMode.name().toLowerCase(Locale.ROOT));
            case ACCEPT -> log.info("battle 直连票据验签已启用 run_mode={} gate_secret_visible={}",
                    battleRunMode.name().toLowerCase(Locale.ROOT), gateSecret != null && !gateSecret.isEmpty());
        }
        return BattleTickets.ofUtf8(secret);
    }

    /**
     * 控制面提供方要求调用方鉴权：密钥只从环境变量 {@code XM_DUBBO_SECRET} 读，缺失即拒启（xm-api 的提供方过滤器缺密钥时导出也会失败，
     * 这里在建 bean 阶段就给出明确原因）。过滤器自己读环境变量，这个 bean 只作启动校验。
     */
    @Bean
    public DubboCallAuth battleDubboCallAuth(Environment environment) {
        return DubboCallAuth.requireFromEnvValue(environment.getProperty(DubboCallAuth.SECRET_ENV));
    }

    /** 配置表快照（加载失败拒启）。 */
    @Bean
    public ConfigTables battleConfigTables(@Value("${xm.table-dir:config-data/tables}") String tableDir) {
        Path dir = Path.of(tableDir);
        ConfigTables tables = ConfigTables.load(dir);
        log.info("配置表已加载 dir={}", dir.toAbsolutePath().normalize());
        return tables;
    }

    /**
     * 启动时加载一次的只读数据（§6.4、§7.11 第 3 步）：{@code TableBattleData}（构造失败拒启）与指纹、限频表、消息号（缺号拒启）；
     * 打出七张战斗表的行数与指纹。
     */
    @Bean
    public BattleTables battleTables(ConfigTables battleConfigTables) {
        RowReport report = RowReport.of(battleConfigTables.rowCounts());
        log.info("battle 战斗表加载完成: {}", report.summary());
        if (report.itemEmpty()) {
            log.warn("battle Item 表为空：战斗内道具一律不可用（ITEM 行动会被拒）");
        }
        if (!report.emptyCritical().isEmpty()) {
            log.error("battle 关键战斗表为空 {}：回合引擎将无法开局，请检查表数据目录（不拒绝启动，同基线）", report.emptyCritical());
        }
        TableBattleData data;
        try {
            data = new TableBattleData(battleConfigTables);
        } catch (RuntimeException e) {
            throw new IllegalStateException("拒绝启动：战斗配表不可用（TableBattleData 构造失败）", e);
        }
        log.info("battle 战斗配表指纹: table_fingerprint={}", data.fingerprint());
        MessageLimits limits = TableMessageLimits.from(battleConfigTables.messageLimiter());
        BattleMessageIds ids = BattleMessageIds.resolve(MessageIdRegistry.loadFromClasspath());
        return new BattleTables(data, data.fingerprint(), limits, ids);
    }

    /** battle 指标，注册到 actuator 提供的注册表（Prometheus 导出，§9）。 */
    @Bean
    public BattleMetrics battleMetrics(MeterRegistry meterRegistry) {
        return new BattleMetrics(meterRegistry);
    }

    /** 建房准入闸（进程唯一；{@link BattleNode} 开 / 关，控制面读）。 */
    @Bean
    public AdmissionGate battleAdmissionGate() {
        return new AdmissionGate();
    }

    /** 大厅公告 177 / 143 的 gate 回落：查在线目录取当前会话，一条 {@code GatePush{message_batch}} 保序（§7.7、Q3）。 */
    @Bean
    @ConditionalOnMissingBean(LobbyAnnouncer.class)
    public LobbyAnnouncer battleLobbyAnnouncer(RedissonClient redis, BattleMetrics battleMetrics) {
        return new PresenceLobbyAnnouncer(new PlayerPushes(redis, new PlayerPresenceDirectory(redis)), battleMetrics);
    }

    /** battle 侧发件箱与结算投递的指标（scene-battle-spec §9）。 */
    @Bean
    public OutboxMetrics battleOutboxMetrics(MeterRegistry meterRegistry) {
        return new OutboxMetrics(meterRegistry);
    }

    /**
     * battle → scene 的真实传输（批次 6.3，scene-battle-spec §7.15–§7.17）：{@code battle-outbox} 线程、按节点直连的 {@code SceneBattleService} 客户端、
     * 定位器与 scene 目录。启动门禁：{@code xm.battle.scene-rpc-timeout} 必须大于 Redis 单条命令最坏耗时（§7.3、§10.4）。
     * Spring 销毁它时（{@link BattleNode} 停机之后）有界排空结算发件箱。
     */
    @Bean(destroyMethod = "close")
    public SceneTransport battleSceneTransport(RedissonClient redis, BattleMetrics battleMetrics, OutboxMetrics battleOutboxMetrics,
                                               SceneTransportProperties transportProps, ObjectProvider<RedisProperties> redisProps,
                                               BattleResultSink results) {
        RedisProperties redisSettings = redisProps.getIfAvailable(() -> new RedisProperties(null, null, null, null, null, null, null));
        transportProps.requireAbove(redisSettings.worstCaseCommandMillis());
        return new SceneTransport(redis, battleMetrics, battleOutboxMetrics, transportProps, results);
    }

    /** 确认事件 → scene：Dubbo {@code SceneBattleService.confirmBattle}（6.3，Q13；实例不符时回落定位器，D28）。 */
    @Bean
    @ConditionalOnMissingBean(SceneBattleEvents.class)
    public SceneBattleEvents battleSceneEvents(SceneTransport battleSceneTransport) {
        return battleSceneTransport.sceneEvents();
    }

    /** 结算 → scene：先落 Redis、后投递、未销账就有界重投（6.3 的结算发件箱）。 */
    @Bean
    @ConditionalOnMissingBean(SettlementSink.class)
    public SettlementSink battleSettlementSink(SceneTransport battleSceneTransport) {
        return battleSceneTransport.settlementSink();
    }

    /** 活动局结果：持久副本 + 发布 + 重发（6.3 的 battle 侧，Q11；消费方随 4.6）。 */
    @Bean
    @ConditionalOnMissingBean(ActivityResultSink.class)
    public ActivityResultSink battleActivityResultSink(SceneTransport battleSceneTransport) {
        return battleSceneTransport.activityResults();
    }

    /** 普通局结果 → match：6.2 只记日志（6.4，Q12）。 */
    @Bean
    @ConditionalOnMissingBean(BattleResultSink.class)
    public BattleResultSink battleResultSink(BattleMetrics battleMetrics) {
        return new LoggingBattleResultSink(battleMetrics);
    }

    /** 要 Redis / 端口 / 节点身份的部件的工厂（测试换成假实现）。 */
    @Bean
    @ConditionalOnMissingBean(BattleInfrastructure.class)
    public BattleInfrastructure battleInfrastructure(RedissonClient redis) {
        return BattleInfrastructure.production(redis);
    }

    /**
     * battle 节点（{@code SmartLifecycle}：Spring 在全部 bean 建好之后启动它）。{@code battleDubboCallAuth} 只为保证 Dubbo 密钥门禁先过。
     */
    @Bean
    public BattleNode battleNode(BattleProperties props, RunMode battleRunMode, @Value("${xm.advertise-host}") String advertiseHost,
                                 BattleTables battleTables, BattleTickets battleTickets, LobbyAnnouncer lobby,
                                 SceneBattleEvents sceneEvents, SettlementSink settlements, ActivityResultSink activityResults,
                                 BattleResultSink results, AdmissionGate battleAdmissionGate, BattleMetrics battleMetrics,
                                 BattleInfrastructure battleInfrastructure, DubboCallAuth battleDubboCallAuth) {
        props.requireAllowedIn(battleRunMode);
        return new BattleNode(props, battleRunMode, advertiseHost, battleTables, battleTickets,
                new OutboundPorts(lobby, sceneEvents, settlements, activityResults, results), battleAdmissionGate, battleMetrics,
                BattleClock.SYSTEM, battleInfrastructure);
    }

    // ---------------------------------------------------------------- dev / test 管理接口（§7.12）

    /** dev 管理接口经它取控制面的进程内入口（节点没在运行时为空）。 */
    @Bean
    public DevBattleBackend devBattleBackend(BattleNode battleNode) {
        return battleNode::controlPlane;
    }

    /** dev 建房 / 登记观众时补全快照路由（读在线目录、位置记录与 scene 目录；只在 Tomcat 线程上用）。 */
    @Bean
    public DevRoutingResolver devRoutingResolver(RedissonClient redis) {
        return DevRoutingResolver.redis(redis);
    }

    /** 只注册在 {@code /admin/*}（容器按规范路径匹配）；令牌只从环境变量 {@code XM_ADMIN_TOKEN} 读。 */
    @Bean
    public FilterRegistrationBean<BattleAdminAuthFilter> battleAdminAuthFilter(Environment environment) {
        BattleAdminAuthFilter filter = new BattleAdminAuthFilter(environment.getProperty(BattleAdminAuthFilter.TOKEN_ENV));
        if (!filter.tokenConfigured()) {
            log.warn("运维令牌 {} 未配置：管理端口 /admin/**（含 dev 建房接口）一律 503", BattleAdminAuthFilter.TOKEN_ENV);
        }
        FilterRegistrationBean<BattleAdminAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }
}
