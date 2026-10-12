package com.game.discovery.namespace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 上下文内的第二道核对：不经 SpringApplication（不跑 EnvironmentPostProcessor）的上下文里，Environment 的命名空间与进程里已定格的值
 * （缺省 JVM 钉在 game）不同或非法就拒启；相同或没配时正常。
 */
class GameNamespaceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class));

    @Test
    void 没配或与已定格值相同_正常() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(GameNamespaceAutoConfiguration.DeploymentNamespace.class).value()).isEqualTo("game");
        });
        runner.withPropertyValues("game.namespace=game").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 与已定格值不同_拒启() {
        runner.withPropertyValues("game.namespace=t1").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("\"game\"").hasMessageContaining("\"t1\"");
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void 非法值_拒启() {
        runner.withPropertyValues("game.namespace=Bad").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GAME_NAMESPACE");
        });
    }
}
