package com.game.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.util.EnvironmentUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * nacos profile 下 Nacos namespace 的显式配置写法，钉住 Dubbo <b>实际</b>读到的注册中心配置（architecture.md §6.2「Nacos」）：Nacos namespace
 * 不随部署命名空间 {@code game.namespace} 自动推导，缺省落在 public；多个部署共用一个 Nacos 时由部署显式给。
 *
 * <p>照 Dubbo 的 Spring 集成（{@code DubboContextPostProcessor}：{@link EnvironmentUtils#filterDubboProperties} 把 Spring Environment 里<b>键名以
 * {@code dubbo.} 开头</b>的属性交给 Dubbo 的 {@code Environment}）建出 {@link RegistryConfig}，核对地址与 {@code parameters.namespace}。不连 Nacos、不起 Dubbo 服务。
 * 由此可见两种 Spring 认、Dubbo 不认的写法会静默落进 public：环境变量宽松名 {@code DUBBO_REGISTRY_PARAMETERS_NAMESPACE}（键名不以 {@code dubbo.} 开头，
 * 枚举时被滤掉；2026-10-12 用 game-chat 的 jar 实测也一样：注册中心与配置中心的 parameters 里都没有 namespace）与方括号写法
 * {@code dubbo.registry.parameters[namespace]}。哪天 Dubbo 认了，对应的用例会失败，届时同步改文档。
 */
class NacosRegistryNamespaceTest {

    @SpringBootConfiguration
    static class App {
    }

    /** 带 nacos profile 起最小上下文（只加载 game-chat 的 application*.yaml，不装配任何 bean），返回 Dubbo 建出的唯一一个注册中心配置。 */
    private static RegistryConfig dubboRegistry(Map<String, Object> env, String... args) {
        SpringApplication app = new SpringApplication(App.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        app.setAdditionalProfiles("nacos");
        if (env != null) {
            ConfigurableEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
            app.setEnvironment(environment);
        }
        String[] all = Stream.concat(Stream.of("--spring.main.web-application-type=none", "--spring.main.keep-alive=false"), Arrays.stream(args)).toArray(String[]::new);
        try (ConfigurableApplicationContext ctx = app.run(all)) {
            FrameworkModel framework = new FrameworkModel();
            try {
                ApplicationModel application = framework.newApplication();
                application.getModelEnvironment().getAppConfigMap().putAll(EnvironmentUtils.filterDubboProperties(ctx.getEnvironment()));
                application.getApplicationConfigManager().loadConfigsOfTypeFromProps(RegistryConfig.class);
                Collection<RegistryConfig> registries = application.getApplicationConfigManager().getRegistries();
                assertThat(registries).hasSize(1);
                return registries.iterator().next();
            } finally {
                framework.destroy();
            }
        }
    }

    private static Map<String, String> parameters(RegistryConfig registry) {
        return registry.getParameters() == null ? Map.of() : registry.getParameters();
    }

    @Test
    void 缺省不带namespace_落在public_与以前相同() {
        RegistryConfig registry = dubboRegistry(null);
        assertThat(registry.getAddress()).isEqualTo("nacos://127.0.0.1:8848");
        assertThat(parameters(registry)).doesNotContainKey("namespace");
    }

    @Test
    void 部署命名空间不自动推导Nacos_namespace() {
        assertThat(parameters(dubboRegistry(null, "--game.namespace=t1"))).doesNotContainKey("namespace");
    }

    @Test
    void Dubbo配置项dubbo_registry_parameters_namespace_经属性给出时生效() {
        // 命令行参数、-D 系统属性、SPRING_APPLICATION_JSON 都是键名原样的属性源
        assertThat(parameters(dubboRegistry(null, "--dubbo.registry.parameters.namespace=t1"))).containsEntry("namespace", "t1");
    }

    @Test
    void GAME_NACOS_ADDR里带namespace查询参数时_注册中心地址带上它() {
        Map<String, Object> env = new HashMap<>(System.getenv());
        env.put("GAME_NACOS_ADDR", "127.0.0.1:8848?namespace=t1");
        assertThat(dubboRegistry(env).getAddress()).isEqualTo("nacos://127.0.0.1:8848?namespace=t1");
    }

    @Test
    void 前提_环境变量宽松名DUBBO_REGISTRY_PARAMETERS_NAMESPACE不生效_Spring认而Dubbo不认() {
        Map<String, Object> env = new HashMap<>(System.getenv());
        env.put("DUBBO_REGISTRY_PARAMETERS_NAMESPACE", "t1");
        ConfigurableEnvironment spring = new StandardEnvironment();
        spring.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        assertThat(spring.getProperty("dubbo.registry.parameters.namespace")).as("Spring 的宽松查找认得").isEqualTo("t1");
        assertThat(parameters(dubboRegistry(env))).as("Dubbo 只枚举键名以 dubbo. 开头的属性：环境变量名对不上，静默落进 public")
                .doesNotContainKey("namespace");
    }

    @Test
    void 前提_方括号写法不生效() {
        assertThat(parameters(dubboRegistry(null, "--dubbo.registry.parameters[namespace]=t1"))).doesNotContainKey("namespace");
    }
}
