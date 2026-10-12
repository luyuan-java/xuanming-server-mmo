package com.game.discovery.namespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 命令行工具取部署命名空间的唯一入口：与服务进程同一个绑定类型、同一套宽松绑定（系统属性优先于环境变量，没配取 game），按同一套规则校验，
 * 返回值带来源。用例只给映射，不读本机进程的系统属性与环境变量。
 */
class GameNamespaceBinderTest {

    @Test
    void 没配取game_来源是缺省() {
        GameNamespaceBinder.Bound bound = GameNamespaceBinder.bind(Map.of(), Map.of());
        assertThat(bound.namespace()).isEqualTo(GameNamespace.of("game"));
        assertThat(bound.origin()).isEqualTo(GameNamespaceBinder.DEFAULT_ORIGIN).isEqualTo("缺省");
    }

    @Test
    void 环境变量GAME_NAMESPACE与系统属性game_namespace都认_系统属性优先_与服务进程同序() {
        GameNamespaceBinder.Bound env = GameNamespaceBinder.bind(Map.of(), Map.of("GAME_NAMESPACE", "t1"));
        assertThat(env.namespace().value()).isEqualTo("t1");
        assertThat(env.origin()).isEqualTo("\"GAME_NAMESPACE\" from property source \"systemEnvironment\"");

        Properties props = new Properties();
        props.setProperty("game.namespace", "t2");
        GameNamespaceBinder.Bound both = GameNamespaceBinder.bind(props, Map.of("GAME_NAMESPACE", "t1"));
        assertThat(both.namespace().value()).isEqualTo("t2");
        assertThat(both.origin()).isEqualTo("\"game.namespace\" from property source \"systemProperties\"");
    }

    @Test
    void 非法值一律拒绝_显式空串不当成没配_消息带原值与来源() {
        for (String bad : List.of("Bad", "", " game", "game ", "mysql", "sys", "public", "a-b", "a" + "0".repeat(28))) {
            assertThatThrownBy(() -> GameNamespaceBinder.bind(Map.of(), Map.of("GAME_NAMESPACE", bad))).as("[%s]", bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GAME_NAMESPACE")
                    .hasMessageContaining("\"GAME_NAMESPACE\" from property source \"systemEnvironment\"");
        }
        assertThatThrownBy(() -> GameNamespaceBinder.bind(Map.of("game.namespace", "Bad"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("\"Bad\"").hasMessageContaining("systemProperties");
    }

    @Test
    void 给定的Environment按它的属性源次序绑定_assetopfix的yaml排在最后() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));
        environment.getPropertySources().addLast(new MapPropertySource("tool-config", Map.of("game.namespace", "fromyaml")));
        assertThat(GameNamespaceBinder.bind(environment).namespace().value()).isEqualTo("fromyaml");

        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("GAME_NAMESPACE", "fromenv")));
        assertThat(GameNamespaceBinder.bind(environment).namespace().value()).isEqualTo("fromenv");
    }

    @Test
    void 同一份系统属性与环境变量_工具与服务进程绑定出相同的命名空间_拒绝的也相同() {
        // 服务进程：GameNamespaceAutoConfiguration 经 GameNamespaceProperties 绑定；工具：本类。以前工具只认精确的 GAME_NAMESPACE，
        // 服务进程认的 -Dgame.namespace 在工具那里被静默忽略
        ApplicationContextRunner service = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class));
        List<Map<String, String>> props = List.of(Map.of(), Map.of(), Map.of(), Map.of("game.namespace", "t2"), Map.of("game.namespace", "t2"),
                Map.of(), Map.of(), Map.of("game.namespace", "Bad"));
        List<Map<String, String>> envs = List.of(Map.of(), Map.of("GAME_NAMESPACE", "t1"), Map.of("game.namespace", "t3"), Map.of(),
                Map.of("GAME_NAMESPACE", "t1"), Map.of("GAME_NAMESPACE_X", "t4"), Map.of("GAME_NAMESPACE", ""), Map.of());
        for (int i = 0; i < envs.size(); i++) {
            Map<String, String> p = props.get(i);
            Map<String, String> e = envs.get(i);
            GameNamespace tool;
            try {
                tool = GameNamespaceBinder.bind(p, e).namespace();
            } catch (IllegalArgumentException rejected) {
                tool = null;
            }
            GameNamespace expected = tool;
            service.withInitializer(ctx -> {
                ctx.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                        new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.<String, Object>copyOf(p)));
                ctx.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>copyOf(e)));
            }).run(ctx -> {
                if (expected == null) {
                    assertThat(ctx).as("系统属性 %s、环境变量 %s：工具拒绝，服务进程也拒启", p, e).hasFailed();
                } else {
                    assertThat(ctx).as("系统属性 %s、环境变量 %s", p, e).hasNotFailed();
                    assertThat(ctx.getBean(GameNamespace.class)).as("系统属性 %s、环境变量 %s", p, e).isEqualTo(expected);
                }
            });
        }
    }
}
