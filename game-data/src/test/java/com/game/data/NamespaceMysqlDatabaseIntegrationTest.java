package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.NamespaceDatabaseCheck;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 真 MySQL 上的缺省库名（namespace 执行 + {@code -Dgame.it.mysql=jdbc:mysql://127.0.0.1:3306}，缺省跳过）：game-data 的 application.yaml
 * 里 JDBC URL 的库名占位符解析成 nstest，{@code createDatabaseIfNotExist} 建出 nstest 库，{@code SELECT DATABASE()} 为 nstest，
 * 命令行工具的库名核对通过。只换掉 URL 的主机端口段（取 {@code game.it.mysql}）；本测试建出的库在结束时删掉（之前就有的不删）。
 */
@Tag("namespace")
@EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
class NamespaceMysqlDatabaseIntegrationTest {

    private static final String YAML_BASE = "jdbc:mysql://127.0.0.1:3306";
    private static final String USER = System.getProperty("game.it.mysql.user", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", "");

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    private static boolean databaseExists(Connection conn, String name) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void yaml的库名解析成命名空间_建库并核对通过() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        String yamlUrl = environment.getProperty("spring.datasource.url");
        assertThat(yamlUrl).startsWith(YAML_BASE + "/nstest?").contains("createDatabaseIfNotExist=true");
        String url = System.getProperty("game.it.mysql") + yamlUrl.substring(YAML_BASE.length());

        boolean existedBefore;
        try (Connection admin = DriverManager.getConnection(System.getProperty("game.it.mysql") + "/?useSSL=false&allowPublicKeyRetrieval=true",
                USER, PASSWORD)) {
            existedBefore = databaseExists(admin, "nstest");
        }
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD)) {
            assertThat(NamespaceDatabaseCheck.currentDatabase(conn)).isEqualTo("nstest");
            assertThat(NamespaceDatabaseCheck.verify(conn, null, GameNamespace.get())).isNull();
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
