package com.game.guild.asset.fix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 非缺省部署命名空间下 assetopfix 读到的配置：jar 里同一份 application.yaml，JDBC URL 的库名段与解析出的命名空间都随同一个值走
 * （环境变量 {@code GAME_NAMESPACE} 或 {@code -D game.namespace}），与服务进程同口径。不连库、不连 Redis。
 */
class AssetOpFixNamespaceIsolationTest {

    /** jar 里的 application.yaml，换掉本机进程的系统属性与环境变量，只留给定的覆盖源（放在最前面，同 StandardEnvironment 的优先级）。 */
    private static StandardEnvironment bundledConfigWith(PropertySource<?> override) throws Exception {
        StandardEnvironment env = AssetOpFixEnvironment.load(null);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(override);
        return env;
    }

    @Test
    void 环境变量GAME_NAMESPACE同时决定库名与命名空间() throws Exception {
        StandardEnvironment env = bundledConfigWith(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("GAME_NAMESPACE", "nstest")));
        assertThat(env.getProperty("spring.datasource.url")).contains("3306/nstest?").doesNotContain("/game?");
        assertThat(AssetOpFixEnvironment.resolveNamespace(env).value()).isEqualTo("nstest");
    }

    @Test
    void 系统属性game_namespace同时决定库名与命名空间() throws Exception {
        StandardEnvironment env = bundledConfigWith(new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of("game.namespace", "nstest")));
        assertThat(env.getProperty("spring.datasource.url")).contains("3306/nstest?").doesNotContain("/game?");
        assertThat(AssetOpFixEnvironment.resolveNamespace(env).value()).isEqualTo("nstest");
    }
}
