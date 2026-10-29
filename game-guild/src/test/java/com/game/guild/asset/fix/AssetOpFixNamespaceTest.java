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
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * assetopfix 的部署命名空间：与服务进程同口径绑定（GameNamespaceBinder：-D、环境变量宽松绑定，再到 -f 的 yaml；没配取 game），非法值（含显式空串）是前置条件不满足
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

    /** 去掉本机进程的系统属性与环境变量（结果不随跑测试的机器变）。 */
    private static StandardEnvironment withoutProcessSources(StandardEnvironment env) {
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return env;
    }

    /** 只有配置文件给出的命名空间。 */
    private static StandardEnvironment configOnly(String namespace) {
        StandardEnvironment env = withoutProcessSources(new StandardEnvironment());
        env.getPropertySources().addLast(new MapPropertySource("assetopfix-config", Map.of("game.namespace", namespace)));
        return env;
    }

    @Test
    void 缺省配置的库名与命名空间都是game() throws Exception {
        StandardEnvironment env = withoutProcessSources(AssetOpFixEnvironment.load(null));
        assertThat(env.getProperty("spring.datasource.url")).contains("/game?");
        assertThat(AssetOpFixEnvironment.resolveNamespace(env).value()).isEqualTo("game");
    }

    @Test
    void 没配命名空间取game_配了取配置值() {
        assertThat(AssetOpFixEnvironment.resolveNamespace(withoutProcessSources(new StandardEnvironment())).value()).isEqualTo("game");
        assertThat(AssetOpFixEnvironment.resolveNamespace(configOnly("nstest")).value()).isEqualTo("nstest");
    }

    @Test
    void 环境变量GAME_NAMESPACE经宽松绑定生效且优先于配置文件() {
        StandardEnvironment env = configOnly("fromyaml");
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("GAME_NAMESPACE", "t1")));
        assertThat(AssetOpFixEnvironment.resolveNamespace(env).value()).isEqualTo("t1");
    }

    @Test
    void 系统属性优先于环境变量_与服务进程同序() {
        StandardEnvironment env = configOnly("fromyaml");
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("GAME_NAMESPACE", "fromenv")));
        env.getPropertySources().addFirst(new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of("game.namespace", "fromprop")));
        assertThat(AssetOpFixEnvironment.resolveNamespace(env).value()).isEqualTo("fromprop");
    }

    @Test
    void 用法文案写的取值来源与实现一致() {
        // 实现：GameNamespaceBinder 按 StandardEnvironment 的次序绑定（-D、环境变量），-f 的 yaml 排在最后（load）
        assertThat(AssetOpFixMain.USAGE).contains("-Dgame.namespace").contains("环境变量 GAME_NAMESPACE").contains("-f 的 yaml")
                .doesNotContain("取环境变量 GAME_NAMESPACE（");
    }

    @Test
    void 配置给出的命名空间非法_前置条件不满足() {
        assertThatThrownBy(() -> AssetOpFixEnvironment.resolveNamespace(configOnly("Bad")))
                .isInstanceOf(AssetOpFixMain.PreconditionException.class).hasMessageContaining("GAME_NAMESPACE").hasMessageContaining("\"Bad\"")
                .as("报错带来源（属性源名）").hasMessageContaining("assetopfix-config");
        assertThatThrownBy(() -> AssetOpFixEnvironment.resolveNamespace(configOnly("")))
                .as("显式空串不当成没配").isInstanceOf(AssetOpFixMain.PreconditionException.class).hasMessageContaining("\"\"");
        assertThatThrownBy(() -> AssetOpFixEnvironment.resolveNamespace(configOnly(" game")))
                .isInstanceOf(AssetOpFixMain.PreconditionException.class).hasMessageContaining("\" game\"");
        assertThatThrownBy(() -> AssetOpFixEnvironment.resolveNamespace(configOnly("mysql")))
                .isInstanceOf(AssetOpFixMain.PreconditionException.class).hasMessageContaining("保留字");
    }

    @Test
    void 命名空间非法时打开即退出2_不连库(@TempDir Path dir) throws Exception {
        // 生产 OPENER 读 -f 给的 yaml：命名空间非法时在读 JDBC URL 之前就拒绝（URL 是假的，真去连会退出 1）
        Path yaml = dir.resolve("assetopfix.yaml");
        Files.writeString(yaml, "game:\n  namespace: Bad\nspring:\n  datasource:\n    url: jdbc:unknown-driver://secret-host/x\n");
        assertThat(run(AssetOpFixEnvironment.OPENER, "-f", yaml.toString(), "list")).isEqualTo(AssetOpFixMain.EXIT_REJECTED);
        assertThat(err()).contains("\"Bad\"").doesNotContain("secret-host");
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
        Files.writeString(yaml, "spring:\n  datasource:\n    url: jdbc:unknown-driver://secret-host/x?password=TOPSECRET\n    username: assetopfix_test_user\n    password: not-a-real-password\n");
        // 此例只核无效driver的库名报错与秘密不外泄，不证明NORMAL启动资格。
        // 仅替身外部boot消费；真实Druid、保护策略、Driver与SQL路径不作替身。
        var authority = org.mockito.Mockito.mock(com.game.common.sql.WriterAuthority.class);
        try (var externalBoot = org.mockito.Mockito.mockStatic(com.game.common.sql.WriterAuthority.class)) {
            externalBoot.when(com.game.common.sql.WriterAuthority::consumeEnvironment).thenReturn(authority);
            assertThat(run(AssetOpFixEnvironment.OPENER, "-f", yaml.toString(), "list")).isEqualTo(AssetOpFixMain.EXIT_ERROR);
            assertThat(err()).contains("库 game").doesNotContain("TOPSECRET").doesNotContain("secret-host");
            out.reset();
            err.reset();
            assertThat(run(AssetOpFixEnvironment.OPENER, "-f", yaml.toString(), "-expect-database", "legacy_db", "list"))
                    .isEqualTo(AssetOpFixMain.EXIT_ERROR);
            assertThat(err()).contains("库 legacy_db");
            externalBoot.verify(com.game.common.sql.WriterAuthority::consumeEnvironment, org.mockito.Mockito.times(2));
            org.mockito.Mockito.verifyNoInteractions(authority);
        }
    }
}
