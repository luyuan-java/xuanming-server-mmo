package com.game.gateway;

import com.game.api.AccountLoginService;
import com.game.api.proto.SceneNodeInfo;
import com.game.common.token.GateTokens;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.gateway.assign.AssignGateMetrics;
import com.game.gateway.assign.AssignGateService;
import com.game.gateway.gate.GateSource;
import com.game.gateway.gate.GateTokenIssuer;
import com.game.gateway.gate.RedisGateSource;
import com.game.gateway.login.LoginHttpMetrics;
import com.game.gateway.login.LoginHttpService;
import com.game.gateway.queue.LoginQueue;
import com.game.gateway.queue.QueueCapacity;
import com.game.gateway.queue.QueueDispatcher;
import com.game.gateway.queue.QueueDispatcherRunner;
import com.game.gateway.queue.QueueSettings;
import com.game.gateway.queue.QueueTokens;
import com.game.gateway.ratelimit.ClientIpResolver;
import com.game.gateway.ratelimit.RateLimitSettings;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.ratelimit.RedisRateLimitStore;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.zone.SeedZone;
import com.game.gateway.zone.ZoneDirectory;
import com.game.gateway.zone.ZoneHealthProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * xm-gateway 的装配（显式构造，不靠组件扫描）。{@link RedissonClient} 由 xm-discovery 的自动配置提供，
 * {@link GatewayStore}（区服目录 / 公告的库表）由 xm-gateway-store 的自动配置提供。
 *
 * <p>启动即校验（fail-fast）：缺 {@value #TOKEN_SECRET_ENV}、播种区服配置不合法都会让进程起不来。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfiguration.class);

    /** gate 令牌签名密钥的环境变量名；gateway 签、xm-gate 验，两边必须同值。只从环境变量注入，不进仓库。 */
    public static final String TOKEN_SECRET_ENV = "XM_GATE_TOKEN_SECRET";

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneDirectory zoneDirectory(GatewayStore store) {
        return new ZoneDirectory(store::zones, System::nanoTime);
    }

    /** 启动播种：配置里的区服库里没有才插入（同基线 schema.sql 播种默认区），已有的不动。 */
    @Bean
    public ApplicationRunner zoneSeeder(GatewayProperties properties, GatewayStore store) {
        return args -> {
            for (SeedZone zone : properties.seedZones()) {
                if (store.seedZone(zone.toRow())) {
                    log.info("播种区服 zone_id={} name={} status={}", zone.zoneId(), zone.name(), zone.status());
                }
            }
        };
    }

    @Bean
    public GateSource gateSource(RedissonClient redis) {
        return new RedisGateSource(redis);
    }

    @Bean
    public ZoneHealthProbe zoneHealthProbe(ZoneDirectory zones, GateSource gates, RedissonClient redis, Clock clock) {
        NodeDirectory<SceneNodeInfo> scenes = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        return new ZoneHealthProbe(zones::zones, gates::listGates, scenes::list, clock::millis,
                ZoneHealthProbe.STATUS_TTL);
    }

    /** 健康探测的调度线程（每 5 s 一轮，同基线 zone-probe.interval-ms）。 */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService zoneProbeScheduler(ZoneHealthProbe probe) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("gateway-zone-probe").daemon(true).factory());
        long period = ZoneHealthProbe.INTERVAL.toMillis();
        scheduler.scheduleWithFixedDelay(probe::probe, 1000, period, TimeUnit.MILLISECONDS);
        return scheduler;
    }

    @Bean
    public GateTokenIssuer gateTokenIssuer(Environment environment, Clock clock) {
        return new GateTokenIssuer(GateTokens.ofUtf8(gateSecret(environment)), clock, new SecureRandom());
    }

    private static String gateSecret(Environment environment) {
        String secret = environment.getProperty(TOKEN_SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + TOKEN_SECRET_ENV + "（gate 令牌签名密钥，须与 xm-gate 一致）");
        }
        return secret;
    }

    /** 登录排队的存储（构造不碰 Redis；排队关闭时也建，没有调用方）。 */
    @Bean
    public LoginQueue loginQueue(RedissonClient redis, GatewayProperties properties, Clock clock) {
        return new LoginQueue(redis, clock::millis, properties.queue().entryTtl(), properties.queue().admitTtl());
    }

    @Bean
    public QueueCapacity queueCapacity(GatewayProperties properties) {
        return new QueueCapacity(properties.queue().softCapMultiplier());
    }

    @Bean
    public AssignGateService assignGateService(ZoneDirectory zones, GateSource gates, GateTokenIssuer issuer,
                                               GatewayProperties properties, LoginQueue queue, QueueCapacity capacity,
                                               RateLimiter rateLimiter, Environment environment, Clock clock) {
        QueueSettings settings = properties.queue();
        AssignGateService.Queueing queueing = null;
        if (settings.enabled()) {
            queueing = new AssignGateService.Queueing(queue, capacity,
                    new QueueTokens(gateSecret(environment).getBytes(StandardCharsets.UTF_8)), settings.retryAfterMs());
            log.info("登录排队已打开 条目有效期={} 放行有效期={} 软上限倍数={}", settings.entryTtl(), settings.admitTtl(),
                    settings.softCapMultiplier());
        }
        return new AssignGateService(zones, gates, issuer, queueing, rateLimiter, clock);
    }

    /** 开服限流（关闭时 check 一律放行，不碰 Redis；存储出错 fail-open）。 */
    @Bean
    public RateLimiter rateLimiter(RedissonClient redis, GatewayProperties properties, Clock clock) {
        RateLimitSettings settings = properties.rateLimit();
        if (settings.enabled()) {
            log.info("开服限流已打开 区桶 {}/s 容量 {}（覆盖 {} 个区）IP 桶 {}/s 容量 {} 冷却 {} ms 分波={}", settings.zoneDefaultRps(),
                    settings.zoneDefaultBurst(), settings.zoneOverrides().size(), settings.ipRps(), settings.ipBurst(),
                    settings.accountCooldownMs(), settings.wave().enabled());
        }
        return new RateLimiter(settings, new RedisRateLimitStore(redis), clock::millis);
    }

    @Bean
    public ClientIpResolver clientIpResolver(GatewayProperties properties) {
        return new ClientIpResolver(properties.rateLimit().trustedProxies());
    }

    /**
     * 排队放行循环（全部 xm-gateway 里只有拿到选主锁的那个在放行）。与 {@link #assignGateService} 读同一个绑定好的开关，
     * 不用 {@code @ConditionalOnProperty}：后者按字面比较，{@code enabled: yes} 之类会出现「排队开了、放行循环没起」——全区卡死。
     */
    @Bean(destroyMethod = "close")
    public QueueDispatcherRunner queueDispatcher(LoginQueue queue, QueueCapacity capacity, ZoneDirectory zones,
                                                 GateSource gates, RedissonClient redis, GatewayProperties properties,
                                                 MeterRegistry meterRegistry) {
        if (!properties.queue().enabled()) {
            return QueueDispatcherRunner.idle();
        }
        Counter admits = Counter.builder("xm.gateway.queue.admits").description("排队放行的人数").register(meterRegistry);
        return new QueueDispatcherRunner(redis, properties.queue().dispatchInterval(),
                leadership -> new QueueDispatcher(queue, capacity, zones, gates, leadership, admits::increment));
    }

    @Bean
    public LoginHttpMetrics loginHttpMetrics(MeterRegistry meterRegistry) {
        return new LoginHttpMetrics(meterRegistry);
    }

    @Bean
    public LoginHttpService loginHttpService(AccountLoginService accountLoginService, ZoneDirectory zones,
                                             LoginHttpMetrics loginHttpMetrics, RateLimiter rateLimiter) {
        return new LoginHttpService(accountLoginService, zones, loginHttpMetrics, rateLimiter);
    }

    /** assign-gate 结局计数，注册到 actuator 提供的注册表（Prometheus 导出，见 architecture.md §11）。 */
    @Bean
    public AssignGateMetrics assignGateMetrics(MeterRegistry meterRegistry) {
        return new AssignGateMetrics(meterRegistry);
    }
}
