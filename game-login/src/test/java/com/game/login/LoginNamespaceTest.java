package com.game.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.discovery.namespace.GameNamespaceAutoConfiguration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 经 SpringApplication 读 game-login 自己的 application.yaml：yaml 里 JDBC URL 的原值带 {@code ${game.namespace:game}} 占位符，
 * 解析后的库名与注入的 {@link GameNamespace} / {@link RedisKeys} bean 随同一个 {@code game.namespace}（命令行属性或环境变量 {@code GAME_NAMESPACE}）走；
 * 非法值由绑定校验拒启。只起最小上下文（只导入命名空间的自动配置，不连 MySQL / Redis / Dubbo）；进程的环境变量换成给定的值，结果不随机器变。
 */
class LoginNamespaceTest {

    @SpringBootConfiguration
    @ImportAutoConfiguration(GameNamespaceAutoConfiguration.class)
    static class App {
    }

    /** 起最小上下文；{@code env} 代替进程的环境变量。 */
    private static ConfigurableApplicationContext start(Map<String, Object> env, String... args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        SpringApplication app = new SpringApplication(App.class);
        app.setEnvironment(environment);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        String[] all = Stream.concat(Stream.of("--spring.main.web-application-type=none", "--spring.main.keep-alive=false"),
                Stream.of(args)).toArray(String[]::new);
        return app.run(all);
    }

    private static Object rawYamlUrl(ConfigurableApplicationContext ctx) {
        PropertySource<?> yaml = ctx.getEnvironment().getPropertySources().stream()
                .filter(s -> s.getName().contains("application.yaml")).findFirst().orElseThrow();
        return yaml.getProperty("spring.datasource.url");
    }

    @Test
    void 缺省命名空间_库名与键前缀都是game() {
        try (ConfigurableApplicationContext ctx = start(Map.of())) {
            assertThat(ctx.getBean(GameNamespace.class).value()).isEqualTo("game");
            assertThat(ctx.getBean(RedisKeys.class).prefix()).isEqualTo("game:");
            assertThat(String.valueOf(rawYamlUrl(ctx))).contains("/${game.namespace:game}?");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url")).contains("3306/game?");
        }
    }

    @Test
    void 命令行属性nstest_库名与注入的命名空间一致() {
        try (ConfigurableApplicationContext ctx = start(Map.of(), "--game.namespace=nstest")) {
            assertThat(ctx.getBean(GameNamespace.class).value()).isEqualTo("nstest");
            assertThat(ctx.getBean(RedisKeys.class).ownerTakeoverTopic()).startsWith("nstest:");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url")).contains("3306/nstest?").doesNotContain("/game?");
            assertThat(ctx.getEnvironment().resolvePlaceholders(String.valueOf(rawYamlUrl(ctx)))).contains("3306/nstest?");
        }
    }

    @Test
    void 环境变量GAME_NAMESPACE_库名与注入的命名空间一致() {
        try (ConfigurableApplicationContext ctx = start(Map.of("GAME_NAMESPACE", "t1"))) {
            assertThat(ctx.getBean(GameNamespace.class).value()).isEqualTo("t1");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.url")).contains("3306/t1?");
        }
    }

    @Test
    void 非法命名空间由绑定校验拒启() {
        assertThatThrownBy(() -> start(Map.of("GAME_NAMESPACE", "Bad"))).hasRootCauseInstanceOf(BindValidationException.class);
        assertThatThrownBy(() -> start(Map.of(), "--game.namespace=")).as("显式空串不换回 game")
                .hasRootCauseInstanceOf(BindValidationException.class);
    }
}
