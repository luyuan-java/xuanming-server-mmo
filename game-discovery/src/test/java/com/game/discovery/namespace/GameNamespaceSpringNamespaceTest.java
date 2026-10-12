package com.game.discovery.namespace;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 非缺省部署命名空间（namespace 执行，{@code -Dgame.namespace=nstest}）下经 SpringApplication 启动：EPP 接受 nstest、上下文内核对通过；
 * nacos 注册中心地址下派生 {@code dubbo.registry.parameters.namespace=nstest}，显式配置优先、直连地址不派生。
 */
@Tag("namespace")
class GameNamespaceSpringNamespaceTest {

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration(GameNamespaceAutoConfiguration.class)
    static class App {
    }

    private static ConfigurableApplicationContext start(String... args) {
        SpringApplication app = new SpringApplication(App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        return app.run(args);
    }

    @Test
    void 接受nstest_上下文内核对通过_nacos地址派生同名namespace() {
        try (ConfigurableApplicationContext ctx = start("--dubbo.registry.address=nacos://127.0.0.1:8848")) {
            assertThat(ctx.getBean(GameNamespaceAutoConfiguration.DeploymentNamespace.class).value()).isEqualTo("nstest");
            assertThat(ctx.getEnvironment().getProperty("dubbo.registry.parameters.namespace")).isEqualTo("nstest");
        }
    }

    @Test
    void 显式Nacos_namespace优先_直连地址不派生() {
        try (ConfigurableApplicationContext ctx = start("--dubbo.registry.address=nacos://127.0.0.1:8848",
                "--dubbo.registry.parameters.namespace=4f1c-uuid")) {
            assertThat(ctx.getEnvironment().getProperty("dubbo.registry.parameters.namespace")).isEqualTo("4f1c-uuid");
        }
        try (ConfigurableApplicationContext ctx = start("--dubbo.registry.address=N/A")) {
            assertThat(ctx.getEnvironment().getProperty("dubbo.registry.parameters.namespace")).isNull();
        }
    }
}
