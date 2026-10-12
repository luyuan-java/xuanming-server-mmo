package com.game.discovery.namespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.mock.env.MockEnvironment;

/**
 * 部署命名空间的 EnvironmentPostProcessor（缺省 JVM：进程级命名空间钉在 game，纯逻辑用 MockEnvironment，不改全局）：
 * 解析（宽松绑定、缺省 game、原值不 trim）、登记与顺序（排在 ConfigData 之后）、与已定格值冲突拒启、Nacos 派生规则。
 */
class GameNamespaceEnvironmentPostProcessorTest {

    private static final DeferredLogFactory LOGS = Supplier::get;

    private static MockEnvironment env(String... keyValues) {
        MockEnvironment env = new MockEnvironment();
        for (int i = 0; i < keyValues.length; i += 2) {
            env.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return env;
    }

    @Test
    void resolve_没配为缺省_原值返回_不trim不校验() {
        assertThat(GameNamespaceEnvironmentPostProcessor.resolve(new MockEnvironment())).isEqualTo("game");
        assertThat(GameNamespaceEnvironmentPostProcessor.resolveWithOrigin(new MockEnvironment()).origin()).isEqualTo("缺省");
        assertThat(GameNamespaceEnvironmentPostProcessor.resolve(env("game.namespace", "t1"))).isEqualTo("t1");
        assertThat(GameNamespaceEnvironmentPostProcessor.resolve(env("game.namespace", " Bad "))).isEqualTo(" Bad ");
        assertThat(GameNamespaceEnvironmentPostProcessor.resolve(env("game.namespace", ""))).as("空串是配了空串，不是没配").isEqualTo("");
        assertThat(GameNamespaceEnvironmentPostProcessor.resolveWithOrigin(env("game.namespace", "t1")).origin()).isEqualTo("mockProperties");
    }

    @Test
    void resolve_认环境变量GAME_NAMESPACE_宽松绑定() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("GAME_NAMESPACE", "e1")));
        assertThat(GameNamespaceEnvironmentPostProcessor.resolveWithOrigin(env))
                .isEqualTo(new GameNamespaceEnvironmentPostProcessor.Resolved("e1", "systemEnvironment"));
    }

    @Test
    void 登记在spring_factories_顺序排在ConfigData之后() {
        List<EnvironmentPostProcessor> processors = SpringFactoriesLoader.forDefaultResourceLocation()
                .load(EnvironmentPostProcessor.class, ArgumentResolver.of(DeferredLogFactory.class, LOGS), (type, name, failure) -> {
                });
        assertThat(processors).hasAtLeastOneElementOfType(GameNamespaceEnvironmentPostProcessor.class);
        assertThat(new GameNamespaceEnvironmentPostProcessor(LOGS).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(FailureAnalyzer.class, (type, name, failure) -> {
        })).hasAtLeastOneElementOfType(GameNamespaceFailureAnalyzer.class);
    }

    @Test
    void 与已定格的值相同时幂等_不同或非法时拒启() {
        assertThat(GameNamespace.get()).as("缺省 JVM 钉在 game；不要给 mvn 传 -Dgame.namespace").isEqualTo("game");
        GameNamespaceEnvironmentPostProcessor epp = new GameNamespaceEnvironmentPostProcessor(LOGS);

        epp.postProcessEnvironment(new MockEnvironment(), new SpringApplication());
        epp.postProcessEnvironment(env("game.namespace", "game"), new SpringApplication());

        assertThatThrownBy(() -> epp.postProcessEnvironment(env("game.namespace", "t1"), new SpringApplication()))
                .isInstanceOf(InvalidGameNamespaceException.class).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"game\"").hasMessageContaining("\"t1\"")
                .satisfies(e -> assertThat(((InvalidGameNamespaceException) e).isConflict()).isTrue());
        for (String bad : List.of("Bad", "", " game", "mysql", "public", "a-b")) {
            assertThatThrownBy(() -> epp.postProcessEnvironment(env("game.namespace", bad), new SpringApplication())).as("[%s]", bad)
                    .isInstanceOf(InvalidGameNamespaceException.class).hasMessageContaining("game.namespace").hasMessageContaining("GAME_NAMESPACE")
                    .satisfies(e -> {
                        InvalidGameNamespaceException ex = (InvalidGameNamespaceException) e;
                        assertThat(ex.isConflict()).isFalse();
                        assertThat(ex.value()).isEqualTo(bad);
                        assertThat(ex.origin()).isEqualTo("mockProperties");
                    });
        }
        assertThat(GameNamespace.get()).as("拒启不改已定格的值").isEqualTo("game");
    }

    @Test
    void 缺省命名空间不派生Nacos参数_现状不变() {
        MockEnvironment env = env("dubbo.registry.address", "nacos://127.0.0.1:8848");
        new GameNamespaceEnvironmentPostProcessor(LOGS).postProcessEnvironment(env, new SpringApplication());
        assertThat(env.getPropertySources().contains(GameNamespaceEnvironmentPostProcessor.NACOS_PROPERTY_SOURCE)).isFalse();
        assertThat(env.getProperty("dubbo.registry.parameters.namespace")).isNull();
    }

    @Test
    void Nacos规则_非缺省且nacos地址且没有显式配置时才派生() {
        String addr = "dubbo.registry.address";
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(env(addr, "nacos://127.0.0.1:8848"), "game")).isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(env(addr, "nacos://127.0.0.1:8848"), "t1")).contains("t1");
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(env(addr, "nacos://${GAME_NACOS_ADDR:127.0.0.1:8848}"), "t1"))
                .as("地址里的占位符照常解析").contains("t1");
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(env(addr, "N/A"), "t1")).as("直连（local profile）").isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(new MockEnvironment(), "t1")).isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(env(addr, "zookeeper://127.0.0.1:2181"), "t1")).isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(
                env(addr, "nacos://127.0.0.1:8848", "dubbo.registry.parameters.namespace", "uuid-1"), "t1")).as("显式参数优先").isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(
                env(addr, "nacos://127.0.0.1:8848", "dubbo.registry.parameters[namespace]", "uuid-1"), "t1")).as("方括号写法").isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(env(addr, "nacos://h:8848?namespace=abc"), "t1"))
                .as("地址里自带 namespace=").isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(
                env(addr, "nacos://${GAME_NACOS_ADDR:127.0.0.1:8848}", "GAME_NACOS_ADDR", "h:8848?username=u&namespace=zz"), "t1"))
                .as("GAME_NACOS_ADDR 里自带 ?namespace= 也算显式").isEmpty();
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(env(addr, "nacos://h:8848?username=u"), "t1")).contains("t1");

        MockEnvironment viaEnvVar = env(addr, "nacos://127.0.0.1:8848");
        viaEnvVar.getPropertySources().addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("DUBBO_REGISTRY_PARAMETERS_NAMESPACE", "uuid-2")));
        assertThat(GameNamespaceEnvironmentPostProcessor.nacosNamespace(viaEnvVar, "t1")).as("环境变量形式的显式参数").isEmpty();

        assertThat(GameNamespaceEnvironmentPostProcessor.queryHasNamespace("nacos://h:8848?namespacex=1")).isFalse();
        assertThat(GameNamespaceEnvironmentPostProcessor.queryHasNamespace("nacos://h:8848?a=1&namespace")).isTrue();
    }

    /** 最小的应用：不开自动配置、不起 Web。 */
    @SpringBootConfiguration
    static class Empty {
    }

    @Test
    void 经SpringApplication时读得到ConfigData里的值_说明顺序排在yaml之后() {
        // 去掉系统属性（钉着 game）与环境变量，只留命令行与 ConfigData：yaml 里写 other，与进程里已定格的 game 冲突而拒启
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        SpringApplication app = new SpringApplication(Empty.class);
        app.setEnvironment(env);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        List<Throwable> failures = new ArrayList<>();
        try {
            app.run("--spring.config.location=classpath:namespace/epp-config-data.yaml").close();
        } catch (RuntimeException e) {
            failures.add(e);
        }
        assertThat(failures).singleElement().isInstanceOf(InvalidGameNamespaceException.class)
                .satisfies(e -> {
                    InvalidGameNamespaceException ex = (InvalidGameNamespaceException) e;
                    assertThat(ex.value()).isEqualTo("other");
                    assertThat(ex.origin()).contains("epp-config-data.yaml");
                    assertThat(ex.isConflict()).isTrue();
                });
    }
}
