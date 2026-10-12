package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.namespace.GameNamespaceAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;

/**
 * 非缺省部署命名空间（namespace 执行，{@code -Dgame.namespace=nstest}）下经 SpringApplication 读 game-login 自己的 application.yaml：
 * EPP 接受 nstest、上下文内核对通过；yaml 里 JDBC URL 的原值带 {@code ${game.namespace:game}} 占位符，解析后库名是 nstest。
 * 只起最小上下文（不开自动配置、不连 MySQL / Redis / Dubbo）。
 */
@Tag("namespace")
class LoginNamespaceTest {

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

    @Test
    void 进程的yaml里库名随命名空间_EPP与上下文核对都接受nstest() {
        SpringApplication app = new SpringApplication(App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        try (ConfigurableApplicationContext ctx = app.run("--spring.main.web-application-type=none", "--spring.main.keep-alive=false")) {
            assertThat(ctx.getBean(GameNamespaceAutoConfiguration.DeploymentNamespace.class).value()).isEqualTo("nstest");
            PropertySource<?> yaml = ctx.getEnvironment().getPropertySources().stream()
                    .filter(s -> s.getName().contains("application.yaml")).findFirst().orElseThrow();
            Object raw = yaml.getProperty("spring.datasource.url");
            assertThat(String.valueOf(raw)).contains("/${game.namespace:game}?");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url")).contains("3306/nstest?").doesNotContain("/game?");
            assertThat(ctx.getEnvironment().resolvePlaceholders(String.valueOf(raw))).contains("3306/nstest?");
        }
    }
}
