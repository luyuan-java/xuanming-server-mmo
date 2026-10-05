package com.game.trade;

import com.alibaba.druid.pool.DruidDataSource;
import com.game.common.RunMode;
import com.game.common.id.Snowflake;
import com.game.common.player.HomeZones;
import com.game.common.player.PlayerHomeZones;
import com.game.common.player.PlayerProfiles;
import com.game.common.token.DubboCallAuth;
import com.game.contract.MessageIdRegistry;
import com.game.discovery.NodeIdLease;
import com.game.discovery.NodeTypes;
import com.game.trade.admin.TradeAdminAuthFilter;
import com.game.trade.dispatch.TradeDispatcher;
import com.game.trade.dispatch.TradeWorkerPool;
import com.game.trade.id.ListingIds;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.rules.TradeLimits;
import com.game.trade.service.JubaozhaiService;
import com.game.trade.service.MarketSettings;
import com.game.trade.service.SeedListingService;
import com.game.trade.store.JdbcListingStore;
import com.game.trade.store.ListingStore;
import com.game.trade.store.TradeStartupChecks;
import com.game.trade.store.TradeTables;
import com.game.pbmysql.PbMysql;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * xm-trade 的装配（trade-spec §5.1、§5.10）。所有依赖显式经构造参数传入业务类；这里是唯一读配置、碰外部系统的地方。
 *
 * <p><b>启动顺序</b>（照基线 trade.go:127-291，每一步失败都拒启；顺序由 bean 依赖链钉住，Dubbo 在全部单例就绪、上下文刷新完成之后才暴露）：
 * <ol>
 *   <li>校验 {@code xm.trade.*}（{@link TradeProperties}：scope 必填且合法、页长、预算）与 {@code XM_DUBBO_SECRET}（{@link #dubboCallAuth}）；
 *       聚宝斋不读配置表，没有配表校验这一步；</li>
 *   <li>xm-pbmysql 建表 / 只扩不缩地同步 {@code trade_listing}、{@code trade_favorite}（{@link #tradeTables}；基线 ensureSchema，trade.go:166-170）；</li>
 *   <li>数据库版本与会话参数检查（{@link #tradeSchemaReady}；Java 增项：MySQL 8.0+ / TiDB、会话 RC、STRICT_TRANS_TABLES）；</li>
 *   <li>listing_id 雪花租约（{@code NodeTypes.TRADE}，作用域 0）——{@link #tradeIdLease}。<b>申领失败拒启</b>（Q5 / T13；基线首段号段领不到只告警，
 *       trade.go:175-177）；</li>
 *   <li>启动横幅（范围、播种是否开放、库与用户，不含口令；基线 trade.go:263-282）——{@link #tradeDispatcher}；</li>
 *   <li>暴露 Dubbo（{@code TradeClientMessageService}）。管理端口的播种接口随 Tomcat 起来（同一次上下文刷新里，同样在全部单例就绪之后）。</li>
 * </ol>
 *
 * <p><b>关闭顺序</b>（基线 lifecycle.Shutdown：注销 → 排空 → 关资源，trade.go:146-164）：Dubbo 在上下文关闭事件里先反导出并等在途调用；
 * 工作线程池以数据源为参数并 {@code @DependsOn} 租约，所以 Spring 先排空它，再释放租约（调度器随后）、关 Redis 与连接池。
 */
@Configuration(proxyBeanMethods = false)
public class TradeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TradeConfiguration.class);

    /**
     * listing_id 雪花 worker 的租约作用域。故意<b>不按 zone 分</b>（理由同 {@code NodeTypes.SCENE_GUID}）：listing_id 全服唯一，
     * 按 zone 各占 worker 号段会让不同 zone 的 xm-trade 拿到同一个 worker、发出逐位相同的号。0 表示「全服」。
     */
    static final int LISTING_ID_LEASE_SCOPE = 0;
    static final Duration LEASE_TTL = Duration.ofSeconds(15);
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);
    /** 结构同步连接的 socket 超时（咨询锁最多等 30 s，再加 DDL）。 */
    static final Duration SCHEMA_SYNC_NETWORK_TIMEOUT = Duration.ofMinutes(2);
    /** 启动期会话参数检查的语句超时。 */
    static final Duration STARTUP_CHECK_TIMEOUT = Duration.ofSeconds(5);

    /** 建表与会话检查通过的标记；依赖它的 bean 一定排在两者之后。 */
    public record SchemaReady(TradeStartupChecks.Session session) {
    }

    @Bean
    public MessageIdRegistry messageIdRegistry() {
        return MessageIdRegistry.loadFromClasspath();
    }

    @Bean
    public TradeMetrics tradeMetrics(MeterRegistry meterRegistry) {
        return new TradeMetrics(meterRegistry);
    }

    /** Dubbo 调用鉴权密钥的启动检查（fail-fast）；签名 / 校验在 xm-api 的 Dubbo 过滤器里。 */
    @Bean
    public DubboCallAuth dubboCallAuth() {
        return DubboCallAuth.requireFromEnvValue(System.getenv(DubboCallAuth.SECRET_ENV));
    }

    // ================================================================ 启动第 2–3 步：建表与会话检查

    /**
     * 第 2 步：两张表经 xm-pbmysql 建表 / 只扩不缩地同步（一条自动提交的连接，整轮持咨询锁）；结构漂移即启动失败，由人工迁移。
     * {@code props} 与 {@code dubboCallAuth} 参数只为保证「配置与密钥校验通过之后才碰库」。
     */
    @Bean
    public PbMysql tradeTables(DataSource dataSource, TradeProperties props, DubboCallAuth dubboCallAuth) throws SQLException {
        PbMysql db = TradeTables.sync(dataSource, SCHEMA_SYNC_NETWORK_TIMEOUT);
        log.info("聚宝斋表已同步: {}", TradeTables.NAMES);
        return db;
    }

    /** 第 3 步：数据库版本下限与连接会话参数（RC、STRICT_TRANS_TABLES；锁等待不是 2 s 只告警）。 */
    @Bean
    public SchemaReady tradeSchemaReady(DataSource dataSource, PbMysql tradeTables) throws SQLException {
        TradeStartupChecks.Session session = TradeStartupChecks.check(dataSource, STARTUP_CHECK_TIMEOUT);
        log.info("聚宝斋数据库 version={} isolation={} lock_wait={}s 满足要求", session.version(), session.isolation(),
                session.lockWaitTimeoutSeconds());
        return new SchemaReady(session);
    }

    @Bean
    public ListingStore listingStore(DataSource dataSource, TradeProperties props, TradeMetrics metrics, SchemaReady tradeSchemaReady) {
        return new JdbcListingStore(connections(dataSource), props.queryTimeoutCapSeconds(), metrics);
    }

    // ================================================================ 第 4 步：雪花租约

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService tradeLeaseScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("trade-lease").daemon(true).factory());
    }

    /**
     * 雪花 worker 租约（{@code NodeTypes.TRADE}，作用域 0，worker ∈ [1, {@value Snowflake#MAX_WORKER}]）。申领失败（号段全被占、Redis 不可达）抛异常 → 拒启
     * （Q5）。租约无效（丢失，或续期滞后超过 2/3 TTL）期间 {@link ListingIds} 拒绝发号，播种回 in-band 1003（四个只读方法不受影响）；续期滞后恢复后
     * 自动恢复，丢失则需重启。
     */
    @Bean(destroyMethod = "close")
    public NodeIdLease tradeIdLease(RedissonClient redis,
                                    @Qualifier("tradeLeaseScheduler") ScheduledExecutorService tradeLeaseScheduler,
                                    SchemaReady tradeSchemaReady) {
        return NodeIdLease.acquire(redis, tradeLeaseScheduler, NodeTypes.TRADE, LISTING_ID_LEASE_SCOPE, 1, Snowflake.MAX_WORKER,
                UUID.randomUUID().toString(), LEASE_TTL,
                () -> log.error("trade 雪花 worker 租约丢失：停止发 listing_id（播种一律 in-band 1003），需要重启本进程"));
    }

    @Bean
    public ListingIds listingIds(NodeIdLease tradeIdLease) {
        log.info("trade 雪花 worker={}（listing_id）", tradeIdLease.nodeId());
        return new ListingIds(new Snowflake(tradeIdLease.nodeId()), tradeIdLease::isValid);
    }

    // ================================================================ 归属区与服务

    /** 只读 {@code xm_java.player}（归属区 zone_id；这张表不归 xm-trade 所有）。 */
    @Bean
    public PlayerProfiles playerProfiles(DataSource dataSource, TradeProperties props) {
        return new PlayerProfiles(connections(dataSource), props.queryTimeoutCapSeconds());
    }

    /** 归属区 = player.zone_id（T3；xm-common 的 {@link PlayerHomeZones}，与组队、帮会共用，单次上限 1500 ms，Q6）。 */
    @Bean
    public HomeZones tradeHomeZones(PlayerProfiles profiles) {
        return new PlayerHomeZones(profiles::loadStrict, Duration.ofMillis(TradeLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS));
    }

    @Bean
    public JubaozhaiService jubaozhaiService(ListingStore store, HomeZones tradeHomeZones, TradeMetrics metrics,
                                             TradeProperties props) {
        return new JubaozhaiService(store, tradeHomeZones, metrics, props.market().settings(), System::currentTimeMillis);
    }

    /** dev 播种（T5 / T12：运行模式读 {@code xm.run-mode}，认 development / local / testing 等别名；不认识的值按 prod 并告警）。 */
    @Bean
    public SeedListingService seedListingService(ListingStore store, HomeZones tradeHomeZones, ListingIds listingIds,
                                                 TradeMetrics metrics, @Value("${xm.run-mode:prod}") String runMode) {
        if (!RunMode.isRecognized(runMode)) {
            log.warn("xm.run-mode（XM_RUN_MODE）取值不认识，按 prod 运行（播种接口回 403）: '{}'", runMode);
        }
        return new SeedListingService(store, tradeHomeZones, listingIds::nextId, metrics, RunMode.parse(runMode),
                System::currentTimeMillis);
    }

    // ================================================================ 派发

    /**
     * 依赖租约、并以数据源为参数（只为钉住销毁顺序）：Spring 按依赖逆序销毁，工作池先排空（排空中的请求还会用到 MySQL 连接池），之后租约与连接池才关。
     */
    @Bean(destroyMethod = "close")
    @DependsOn({"tradeIdLease"})
    public TradeWorkerPool tradeWorkerPool(TradeProperties props, DataSource dataSource) {
        return new TradeWorkerPool(props.workerThreads(), props.workerQueueCapacity(), DRAIN_TIMEOUT);
    }

    /** 第 5 步：派发器（启动校验契约）与启动横幅。 */
    @Bean
    public TradeDispatcher tradeDispatcher(MessageIdRegistry registry, JubaozhaiService service, TradeWorkerPool tradeWorkerPool,
                                           TradeMetrics metrics, TradeProperties props, SeedListingService seeds,
                                           SchemaReady tradeSchemaReady, DataSource dataSource) {
        TradeDispatcher dispatcher = new TradeDispatcher(registry, service, tradeWorkerPool, metrics, props.requestBudget().toMillis());
        MarketSettings market = props.market().settings();
        log.info("trade 接管的消息号={} 市场范围={} 页长={}/{} 页码上限={} 收藏上限={} 请求预算={} 播种={}（运行模式 {}） 库={} 用户={}",
                dispatcher.clientMessageIds(), props.market().scope(), market.defaultPageSize(), market.maxPageSize(),
                market.maxPage(), market.maxFavoritesPerPlayer(), props.requestBudget(), seeds.enabled() ? "开放" : "关闭（403）",
                seeds.runMode(), databaseOf(dataSource), userOf(dataSource));
        return dispatcher;
    }

    // ================================================================ 管理端口：播种接口鉴权

    /** 只注册在 {@code /admin/*}（容器按规范路径匹配）；令牌只从环境变量 {@code XM_ADMIN_TOKEN} 读。 */
    @Bean
    public FilterRegistrationBean<TradeAdminAuthFilter> tradeAdminAuthFilter(TradeMetrics metrics) {
        TradeAdminAuthFilter filter = new TradeAdminAuthFilter(System.getenv(TradeAdminAuthFilter.TOKEN_ENV), metrics);
        if (!filter.tokenConfigured()) {
            log.warn("运维令牌 {} 未配置：管理端口 /admin/**（含播种接口）一律 503", TradeAdminAuthFilter.TOKEN_ENV);
        }
        FilterRegistrationBean<TradeAdminAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    /** Druid 能按次限等连接：取连接不超过请求剩余预算（连接池的固定 max-wait 不认预算，见 PlayerProfiles.ConnectionSource）。 */
    private static PlayerProfiles.ConnectionSource connections(DataSource dataSource) {
        return dataSource instanceof DruidDataSource druid ? druid::getConnection : maxWait -> dataSource.getConnection();
    }

    /** 启动横幅里的库：连接串去掉查询参数（参数里可能带口令）。 */
    static String databaseOf(DataSource dataSource) {
        if (!(dataSource instanceof DruidDataSource druid) || druid.getUrl() == null) {
            return "<未知>";
        }
        String url = druid.getUrl();
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String userOf(DataSource dataSource) {
        return dataSource instanceof DruidDataSource druid && druid.getUsername() != null ? druid.getUsername() : "<未知>";
    }
}
