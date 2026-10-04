package com.game.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.api.AccountLoginService;
import com.game.gateway.assign.AssignGateService;
import com.game.gateway.login.LoginHttpService;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.zone.ZoneDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 启动期 fail-fast：缺密钥、播种区服配置不合法时进程起不来；齐全时装配完整。不连 Redis / MySQL（替身）。 */
class GatewayConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayConfiguration.class)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(GatewayStore.class, () -> mock(GatewayStore.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(AccountLoginService.class, () -> mock(AccountLoginService.class));

    private static final String[] ONE_ZONE = {
            "xm.gateway.seed-zones[0].zone-id=1",
            "xm.gateway.seed-zones[0].name=一区",
            "xm.gateway.seed-zones[0].status=open",
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
    void 播种区服缺状态时启动失败() {
        runner.withPropertyValues(GatewayConfiguration.TOKEN_SECRET_ENV + "=s",
                        "xm.gateway.seed-zones[0].zone-id=1", "xm.gateway.seed-zones[0].name=一区")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("xm.gateway.seed-zones");
                });
    }

    @Test
    void 配置齐全时装配完整_状态大小写不敏感_不配播种区服也能起() {
        runner.withPropertyValues(ONE_ZONE)
                .withPropertyValues(GatewayConfiguration.TOKEN_SECRET_ENV + "=s")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(AssignGateService.class).hasSingleBean(LoginHttpService.class)
                            .hasSingleBean(ZoneDirectory.class);
                    assertThat(ctx.getBean(GatewayProperties.class).seedZones().get(0).status())
                            .isEqualTo(ZoneManualStatus.OPEN);
                });
        runner.withPropertyValues(GatewayConfiguration.TOKEN_SECRET_ENV + "=s")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
