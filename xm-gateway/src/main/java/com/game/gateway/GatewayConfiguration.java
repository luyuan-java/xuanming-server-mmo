package com.game.gateway;

import com.game.api.AccountLoginService;
import com.game.common.token.GateTokens;
import com.game.gateway.assign.AssignGateMetrics;
import com.game.gateway.assign.AssignGateService;
import com.game.gateway.gate.GateSource;
import com.game.gateway.gate.GateTokenIssuer;
import com.game.gateway.gate.RedisGateSource;
import com.game.gateway.login.LoginHttpMetrics;
import com.game.gateway.login.LoginHttpService;
import com.game.gateway.zone.ZoneCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * xm-gateway 的装配（显式构造，不靠组件扫描）。{@link RedissonClient} 由 xm-discovery 的自动配置提供。
 *
 * <p>启动即校验（fail-fast）：缺 {@value #TOKEN_SECRET_ENV}、区服配置不合法都会让进程起不来。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    /** gate 令牌签名密钥的环境变量名；gateway 签、xm-gate 验，两边必须同值。只从环境变量注入，不进仓库。 */
    public static final String TOKEN_SECRET_ENV = "XM_GATE_TOKEN_SECRET";

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneCatalog zoneCatalog(GatewayProperties properties) {
        return new ZoneCatalog(properties.zones());
    }

    @Bean
    public GateSource gateSource(RedissonClient redis) {
        return new RedisGateSource(redis);
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
    public AssignGateService assignGateService(ZoneCatalog zones, GateSource gates, GateTokenIssuer issuer) {
        return new AssignGateService(zones, gates, issuer);
    }

    @Bean
    public LoginHttpMetrics loginHttpMetrics(MeterRegistry meterRegistry) {
        return new LoginHttpMetrics(meterRegistry);
    }

    @Bean
    public LoginHttpService loginHttpService(AccountLoginService accountLoginService, ZoneCatalog zones,
                                             LoginHttpMetrics loginHttpMetrics) {
        return new LoginHttpService(accountLoginService, zones, loginHttpMetrics);
    }

    /** assign-gate 结局计数，注册到 actuator 提供的注册表（Prometheus 导出，见 architecture.md §11）。 */
    @Bean
    public AssignGateMetrics assignGateMetrics(MeterRegistry meterRegistry) {
        return new AssignGateMetrics(meterRegistry);
    }
}
