package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 真 MySQL 上的缺省库名（{@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306}，缺省跳过）：同一个 JVM 里按两个命名空间解析 game-data 的
 * application.yaml——不设 {@code game.namespace} 时 JDBC URL 的库名是 game；设成 nstest 时解析成 nstest，{@code createDatabaseIfNotExist}
 * 建出 nstest 库，{@code SELECT DATABASE()} 为 nstest，按 nstest 核对通过、按 game 核对不通过。只换掉 URL 的主机端口段（取 {@code game.it.mysql}），
 * 只连 nstest 库；本测试建出的库在结束时删掉（之前就有的不删）。
 */
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class NamespaceMysqlDatabaseIntegrationTest {

    private static final String YAML_BASE = "jdbc:mysql://127.0.0.1:3306";
    private static final String USER = System.getProperty("game.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");
    private static final String HINT = "--expect-database <库名>";

    private static boolean databaseExists(Connection conn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 按给定的 {@code game.namespace}（null = 不设）解析 application.yaml 里的 JDBC URL；不看本机的系统属性与环境变量。 */
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
    void yaml的库名解析成命名空间_建库并核对通过() throws Exception {
        GameNamespace game = GameNamespace.of("game");
        GameNamespace nstest = GameNamespace.of("nstest");
        assertThat(yamlUrl(null)).startsWith(YAML_BASE + "/game?");
        String yamlUrl = yamlUrl("nstest");
        assertThat(yamlUrl).startsWith(YAML_BASE + "/nstest?").contains("createDatabaseIfNotExist=true");
        String url = System.getProperty("game.it.mysql") + yamlUrl.substring(YAML_BASE.length());

        boolean existedBefore;
        try (Connection admin = DriverManager.getConnection(System.getProperty("game.it.mysql") + "/?useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD)) {
            existedBefore = databaseExists(admin, "nstest");
        }
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD)) {
            assertThat(NamespaceDatabaseCheck.currentDatabase(conn)).isEqualTo("nstest");
            assertThat(NamespaceDatabaseCheck.verify(conn, null, nstest, HINT)).isNull();
            assertThat(NamespaceDatabaseCheck.verify(conn, null, game, HINT)).as("另一个命名空间的库名核对不过")
                    .contains("DATABASE()=nstest").contains("期望 game");
        } finally {
            if (!existedBefore) {
                try (Connection admin = DriverManager.getConnection(System.getProperty("game.it.mysql") + "/?useSSL=false&allowPublicKeyRetrieval=true",
                        USER, PASSWORD); Statement st = admin.createStatement()) {
                    st.execute("DROP DATABASE IF EXISTS `nstest`");
                }
            }
        }
    }
}
