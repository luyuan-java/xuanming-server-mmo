package com.game.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class FriendConnectionPolicyTest {
    @Test
    void 生产配置不得关闭TiDB门禁() {
        for (String profiles : List.of("prod", "nacos,prod")) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(PoolProbe.class)
                    .withPropertyValues("spring.profiles.active=" + profiles, "game.friend.require-tidb=false")
                    .run(context -> assertThat(context.getStartupFailure())
                            .isNotNull().hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("生产好友服务不得关闭 TiDB 权威门禁"));
        }
    }
    @Test
    void 仅RunMode生产_缺省或拼错模式均不得绕过强库门禁() {
        for(String mode:List.of("prod","production","release","live","typo","")) {
            var environment=new MockEnvironment().withProperty("game.run-mode",mode).withProperty("game.friend.require-tidb","false");
            assertThatThrownBy(()->FriendConfiguration.friendConnectionPolicy(environment)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(()->FriendConfiguration.friendConnectionPolicy(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 生产拒绝无法统一初始化的池_本地MySQL不注入TiDB语句() {
        FriendConnectionPolicy required = FriendConfiguration.friendConnectionPolicy(new MockEnvironment(), true);
        assertThatThrownBy(() -> required.postProcessBeforeInitialization(mock(DataSource.class), "dataSource"))
                .isInstanceOf(IllegalStateException.class);
        DruidDataSource initialized = mock(DruidDataSource.class);
        when(initialized.isInited()).thenReturn(true);
        assertThatThrownBy(() -> required.postProcessBeforeInitialization(initialized, "dataSource"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("提前初始化");
        try (DruidDataSource local = new DruidDataSource()) {
            assertThat(FriendConfiguration.friendConnectionPolicy(new MockEnvironment().withProperty("game.run-mode","dev"), false)
                    .postProcessBeforeInitialization(local, "dataSource"))
                    .isSameAs(local);
            assertThat(local.getConnectionInitSqls()).isNullOrEmpty();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "game.it.tidb", matches = ".+")
    void 真实Spring池覆盖弱读和历史URL_三个物理连接淘汰后仍遵守策略() throws Exception {
        String base = System.getProperty("game.it.tidb").split("\\?", 2)[0];
        if (!base.equals("jdbc:mysql://127.0.0.1:4000/test")) {
            throw new IllegalArgumentException("好友强读测试只允许本机隔离TiDB的test库");
        }
        String historical;
        try (Connection connection = DriverManager.getConnection(base + "?useSSL=false&allowPublicKeyRetrieval=true", user(), password());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT DATE_FORMAT(NOW(6)-INTERVAL 1 SECOND,'%Y-%m-%d %H:%i:%s.%f')")) {
            assertThat(rows.next()).isTrue();
            historical = rows.getString(1);
        }
        for (String weak : List.of(
                "tidb_read_consistency='weak',tidb_read_staleness='-5',tidb_low_resolution_tso=ON,tidb_replica_read='follower',"
                        + "tidb_txn_mode='optimistic',transaction_isolation='REPEATABLE-READ'",
                "tidb_snapshot='" + historical + "'")) {
            poolContext(base + "?useSSL=false&allowPublicKeyRetrieval=true&sessionVariables=" + weak, true)
                    .run(context -> {
                        assertThat(context.getStartupFailure()).isNull();
                        DruidDataSource pool = context.getBean(DruidDataSource.class);
                        try {
                            Set<Long> before = holdAndVerifyThree(pool);
                            pool.shrink(false, false);
                            assertThat(pool.getPoolingCount()).isZero();
                            Set<Long> after = holdAndVerifyThree(pool);
                            assertThat(after).doesNotContainAnyElementsOf(before);
                        } catch (SQLException e) {
                            throw new AssertionError("真实物理连接强读策略验证失败", e);
                        }
                    });
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "game.it.tidb", matches = ".+")
    void 真实门禁拒绝每项不合规的读会话_仅改本连接() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getProperty("game.it.tidb"), user(), password());
             var statement = connection.createStatement()) {
            for (String bad : List.of("tidb_read_consistency='weak'", "tidb_read_staleness='-5'",
                    "tidb_low_resolution_tso=ON", "tidb_replica_read='follower'")) {
                statement.execute(FriendConnectionPolicy.SESSION_SQL);
                statement.execute("SET SESSION " + bad);
                assertThatThrownBy(() -> FriendConfiguration.verifyFriendDatabase(connection, true))
                        .as(bad).isInstanceOf(SQLException.class).hasMessageContaining("强读");
            }
            statement.execute(FriendConnectionPolicy.SESSION_SQL);
            String historical;
            try (var rows = statement.executeQuery("SELECT DATE_FORMAT(NOW(6)-INTERVAL 1 SECOND,'%Y-%m-%d %H:%i:%s.%f')")) {
                assertThat(rows.next()).isTrue();
                historical = rows.getString(1);
            }
            try (var setSnapshot = connection.prepareStatement("SET SESSION tidb_snapshot=?")) {
                setSnapshot.setString(1, historical);
                setSnapshot.execute();
            }
            assertThatThrownBy(() -> FriendConfiguration.verifyFriendDatabase(connection, true))
                    .isInstanceOf(SQLException.class).hasMessageContaining("强读");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "game.it.mysql", matches = ".+")
    void 本地真实MySQL池保持兼容() {
        String base = System.getProperty("game.it.mysql").split("\\?", 2)[0];
        if (!base.equals("jdbc:mysql://127.0.0.1:13316")) {
            throw new IllegalArgumentException("好友兼容测试只允许本机隔离MySQL");
        }
        poolContext(base + "/mysql?useSSL=false&allowPublicKeyRetrieval=true", false)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    try (var connection = context.getBean(DruidDataSource.class).getConnection()) {
                        FriendConfiguration.verifyFriendDatabase(connection, false);
                    }
                });
    }

    private static Set<Long> holdAndVerifyThree(DruidDataSource pool) throws SQLException {
        Set<Long> ids = new HashSet<>();
        try (Connection first = pool.getConnection(); Connection second = pool.getConnection(); Connection third = pool.getConnection()) {
            for (Connection connection : List.of(first, second, third)) {
                FriendConfiguration.verifyFriendDatabase(connection, true);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(ids.add(rows.getLong(1))).isTrue();
                }
            }
        }
        return ids;
    }

    private static String user() { return System.getProperty("game.it.mysql.user", "root"); }
    private static String password() { return System.getenv().getOrDefault("GAME_MYSQL_PASSWORD", ""); }

    private ApplicationContextRunner poolContext(String url, boolean requireTiDb) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PropertyPlaceholderAutoConfiguration.class, DruidDataSourceAutoConfigure.class))
                .withUserConfiguration(PoolProbe.class)
                .withPropertyValues("game.run-mode=test","game.friend.require-tidb=" + requireTiDb,
                        "spring.datasource.url=" + url, "spring.datasource.username=" + user(),
                        "spring.datasource.password=" + password(), "spring.datasource.druid.initial-size=1",
                        "spring.datasource.druid.min-idle=0", "spring.datasource.druid.max-active=3",
                        "spring.datasource.druid.max-wait=3000");
        return requireTiDb ? runner.withPropertyValues(
                "spring.profiles.active=prod",
                "spring.datasource.druid.connection-init-sqls=SET SESSION tidb_read_consistency='weak'") : runner;
    }

    @Configuration(proxyBeanMethods = false)
    static class PoolProbe {
        @Bean
        static FriendConnectionPolicy policy(Environment environment) {
            return FriendConfiguration.friendConnectionPolicy(environment);
        }
    }
}
