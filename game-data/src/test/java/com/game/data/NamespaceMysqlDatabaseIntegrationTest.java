package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.testing.AuthoritySqlFixture;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 真 MySQL / TiDB 上的命名空间库名（{@code -Dgame.it.mysql}，缺省跳过）：解析真实 application.yaml，
 * 未配置时库名为 game，随机合法命名空间解析成同名库；两者均不得开启连接时自动建库。
 * fixture 显式创建并发布本例专属库，再以独立 NORMAL 主体连接解析后的 YAML URL，验证实际 DATABASE()。
 * 结束时由 fixture 校验归属并精确清理，不连接或接管已有 nstest 库。
 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class NamespaceMysqlDatabaseIntegrationTest {

    private static final String YAML_BASE = "jdbc:mysql://127.0.0.1:3306";
    private static final String USER = System.getProperty("game.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
    private static final String HINT = "--expect-database <库名>";

    /** 按给定的 game.namespace 解析真实 YAML；不读取本机系统属性及环境变量。 */
    private static String yamlUrl(String namespace) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (namespace != null) {
            environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(GameNamespace.PROPERTY, namespace)));
        }
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment.getProperty("spring.datasource.url");
    }

    @Test
    void yaml解析命名空间且不自动建库_显式发布后实际库名正反核对() throws Exception {
        GameNamespace game = GameNamespace.of("game");
        GameNamespace namespace = GameNamespace.of("nsit" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        String defaultUrl = yamlUrl(null);
        assertThat(defaultUrl).startsWith(YAML_BASE + "/game?");
        assertThat(defaultUrl.toLowerCase(Locale.ROOT)).doesNotContain("createdatabaseifnotexist");
        String namespacedUrl = yamlUrl(namespace.value());
        assertThat(namespacedUrl).startsWith(YAML_BASE + "/" + namespace.value() + "?");
        assertThat(namespacedUrl.toLowerCase(Locale.ROOT)).doesNotContain("createdatabaseifnotexist");

        String root = System.getProperty("game.it.mysql");
        var options = AuthoritySqlFixture.Options.fromEnvironment(root, USER, PASSWORD, "namespace", true, List.of(1));
        // 保留 YAML 的库名与全部连接参数；只替换固定的主机端口段，不能由 fixture 重新拼一份被测 URL。
        String url = root.replaceAll("/$", "") + namespacedUrl.substring(YAML_BASE.length());
        // 正式 authority 发布需要 HOME 基表；沿用 fixture 的固定源装配，不复制一份临时 player DDL。
        try (var lease = AuthoritySqlFixture.beginUnpublishedWithPlayerBaseSchema(options, namespace);
             var fixture = lease.publish()) {
            assertThat(fixture.database()).isEqualTo(namespace.value());
            Properties credentials = new Properties();
            credentials.setProperty("user", fixture.normalUser());
            credentials.setProperty("password", fixture.normalPassword());
            var source = new AuthorityDataSource(url, credentials, fixture.writerBoot(), false, List.of());
            try (Connection conn = source.getConnection()) {
                assertThat(NamespaceDatabaseCheck.currentDatabase(conn)).isEqualTo(namespace.value());
                assertThat(NamespaceDatabaseCheck.verify(conn, null, namespace, HINT)).isNull();
                assertThat(NamespaceDatabaseCheck.verify(conn, null, game, HINT)).as("另一个命名空间的库名核对不过")
                        .contains("DATABASE()=" + namespace.value()).contains("期望 game");
            }
        }
    }
}
