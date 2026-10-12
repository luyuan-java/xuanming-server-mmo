package com.game.guild.asset.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * assetopfix 的部署命名空间（缺省 JVM，命名空间钉在 game）：从同一份配置解析并显式定格；配置给出的值非法或与已定格的值冲突时是前置条件不满足
 * （退出 2，连库与连 Redis 之前就拒绝）；{@code -expect-database} 不许给空串；连库失败的报错只带库名（缺省 = 命名空间），不带连接串。
 */
class AssetOpFixNamespaceTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(AssetOpFixMain.StoreOpener opener, String... args) {
        return AssetOpFixMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                opener, () -> 0L);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    /** 只有配置文件给出的命名空间（去掉钉着 game 的系统属性与环境变量）。 */
    private static StandardEnvironment configOnly(String namespace) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(new MapPropertySource("assetopfix-config", Map.of("game.namespace", namespace)));
        return env;
    }

    @Test
    void 缺省配置的库名是命名空间() throws Exception {
        assertThat(AssetOpFixEnvironment.load(null).getProperty("spring.datasource.url")).contains("/game?");
    }

    @Test
    void 配置给出的命名空间与已定格的值冲突或非法_前置条件不满足() {
        assertThat(AssetOpFixEnvironment.defineNamespace(configOnly("game"))).isEqualTo("game");
        assertThatThrownBy(() -> AssetOpFixEnvironment.defineNamespace(configOnly("other")))
                .isInstanceOf(AssetOpFixMain.PreconditionException.class).hasMessageContaining("\"game\"").hasMessageContaining("\"other\"");
        assertThatThrownBy(() -> AssetOpFixEnvironment.defineNamespace(configOnly("Bad")))
                .isInstanceOf(AssetOpFixMain.PreconditionException.class).hasMessageContaining("GAME_NAMESPACE").hasMessageContaining("\"Bad\"");
    }

    @Test
    void 前置条件不满足退出2_其余打开失败退出1() {
        assertThat(run(path -> {
            throw new AssetOpFixMain.PreconditionException("库名核对失败：……");
        }, "list")).isEqualTo(AssetOpFixMain.EXIT_REJECTED);
        assertThat(err()).contains("库名核对失败");
        assertThat(run(path -> {
            throw new IllegalStateException("连接缓存 Redis 失败，拒绝执行");
        }, "list")).isEqualTo(AssetOpFixMain.EXIT_ERROR);
    }

    @Test
    void 期望库名不许为空() {
        assertThat(run(AssetOpFixEnvironment.OPENER, "-expect-database", " ", "list")).isEqualTo(AssetOpFixMain.EXIT_REJECTED);
        assertThat(err()).contains("-expect-database");
    }

    @Test
    void 连库失败的报错只带库名不带连接串(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("assetopfix.yaml");
        Files.writeString(yaml, "spring:\n  datasource:\n    url: jdbc:unknown-driver://secret-host/x?password=TOPSECRET\n");
        assertThat(run(AssetOpFixEnvironment.OPENER, "-f", yaml.toString(), "list")).isEqualTo(AssetOpFixMain.EXIT_ERROR);
        assertThat(err()).contains("库 game").doesNotContain("TOPSECRET").doesNotContain("secret-host");
        out.reset();
        err.reset();
        assertThat(run(AssetOpFixEnvironment.OPENER, "-f", yaml.toString(), "-expect-database", "legacy_db", "list"))
                .isEqualTo(AssetOpFixMain.EXIT_ERROR);
        assertThat(err()).contains("库 legacy_db");
    }
}
