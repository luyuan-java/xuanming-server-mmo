package com.game.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.gateway.assign.AssignGateService;
import com.game.gateway.zone.ZoneCatalog;
import com.game.gateway.zone.ZoneStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 启动期 fail-fast：缺密钥、缺区服配置时进程起不来；齐全时装配完整。不连 Redis（RedissonClient 用替身）。 */
class GatewayConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayConfiguration.class)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    private static final String[] ONE_ZONE = {
            "xm.gateway.zones[0].zone-id=1",
            "xm.gateway.zones[0].name=一区",
            "xm.gateway.zones[0].status=open",
    };

    @Test
    void 缺少令牌密钥时启动失败() {
        // 显式置空，盖住开发机上可能已设置的同名环境变量。
        runner.withPropertyValues(ONE_ZONE)
                .withPropertyValues(GatewayConfiguration.TOKEN_SECRET_ENV + "=")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .hasMessageContaining(GatewayConfiguration.TOKEN_SECRET_ENV);
                });
    }

    @Test
    void 缺少区服配置时启动失败() {
        runner.withPropertyValues(GatewayConfiguration.TOKEN_SECRET_ENV + "=s")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("xm.gateway.zones");
                });
    }

    @Test
    void 配置齐全时装配完整_状态大小写不敏感() {
        runner.withPropertyValues(ONE_ZONE)
                .withPropertyValues(GatewayConfiguration.TOKEN_SECRET_ENV + "=s")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(AssignGateService.class);
                    assertThat(ctx.getBean(ZoneCatalog.class).find(1).orElseThrow().status()).isEqualTo(ZoneStatus.OPEN);
                });
    }
}
