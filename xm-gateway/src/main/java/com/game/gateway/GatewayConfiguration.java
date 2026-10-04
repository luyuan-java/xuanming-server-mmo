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
import com.game.gateway.store.GatewayStore;
import com.game.gateway.zone.SeedZone;
import com.game.gateway.zone.ZoneDirectory;
import com.game.gateway.zone.ZoneHealthProbe;
import io.micrometer.core.instrument.MeterRegistry;
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
        String secret = environment.getProperty(TOKEN_SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + TOKEN_SECRET_ENV + "（gate 令牌签名密钥，须与 xm-gate 一致）");
        }
        return new GateTokenIssuer(GateTokens.ofUtf8(secret), clock, new SecureRandom());
    }

    @Bean
    public AssignGateService assignGateService(ZoneDirectory zones, GateSource gates, GateTokenIssuer issuer) {
        return new AssignGateService(zones, gates, issuer);
    }

    @Bean
    public LoginHttpMetrics loginHttpMetrics(MeterRegistry meterRegistry) {
        return new LoginHttpMetrics(meterRegistry);
    }

    @Bean
    public LoginHttpService loginHttpService(AccountLoginService accountLoginService, ZoneDirectory zones,
                                             LoginHttpMetrics loginHttpMetrics) {
        return new LoginHttpService(accountLoginService, zones, loginHttpMetrics);
    }

    /** assign-gate 结局计数，注册到 actuator 提供的注册表（Prometheus 导出，见 architecture.md §11）。 */
    @Bean
    public AssignGateMetrics assignGateMetrics(MeterRegistry meterRegistry) {
        return new AssignGateMetrics(meterRegistry);
    }
}
