package com.game.discovery.namespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 连库服务的库名核对（库用 Mockito 模拟 MySQL 的连接）：DataSource 交给任何使用者之前核对 {@code DATABASE()} 等于命名空间，不一致拒启且使用者没有被创建
 * （{@code spring.sql.init} 建表、pbmysql 同步都是这样的使用者）；应急开关 {@code game.mysql.expect-database}（{@link GameMysqlProperties}，环境变量
 * {@code GAME_MYSQL_EXPECT_DATABASE} / {@code GAME_MYSQL_EXPECTDATABASE}）放行偏离规格的库名，给了空值由标准的绑定校验拒启；连不上库也拒启，报错不带连接串；
 * 不是 MySQL 系（测试用的 H2）跳过。
 */
class NamespaceDatabaseVerifierTest {

    /** 用 DataSource 建表的使用者（相当于 spring.sql.init / pbmysql）：记录自己有没有被创建。 */
    static final class SchemaUser {
        static final AtomicBoolean CREATED = new AtomicBoolean();

        SchemaUser(DataSource dataSource) {
            CREATED.set(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserConfig {
        @Bean
        SchemaUser schemaUser(DataSource dataSource) {
            return new SchemaUser(dataSource);
        }
    }

    /** 产品名为 {@code product}、{@code SELECT DATABASE()} 回 {@code database} 的模拟库。 */
    private static DataSource db(String product, String database) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(database);
        Statement st = mock(Statement.class);
        when(st.executeQuery("SELECT DATABASE()")).thenReturn(rs);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn(product);
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.createStatement()).thenReturn(st);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private static DataSource mysql(String database) throws SQLException {
        return db("MySQL", database);
    }

    private static ApplicationContextRunner runner(DataSource dataSource) {
        SchemaUser.CREATED.set(false);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class))
                .withBean("dataSource", DataSource.class, () -> dataSource)
                .withUserConfiguration(UserConfig.class);
    }

    @Test
    void 库名等于命名空间_放行() throws SQLException {
        runner(mysql("game")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(SchemaUser.CREATED).isTrue();
        });
        runner(mysql("t1")).withPropertyValues("game.namespace=t1").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 库名不等于命名空间_拒启_使用者没有被创建() throws SQLException {
        runner(mysql("game")).withPropertyValues("game.namespace=t1").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("DataSource dataSource")
                    .hasMessageContaining("DATABASE()=game").hasMessageContaining("期望 t1").hasMessageContaining("规格：库名 = 命名空间")
                    .hasMessageContaining("game.mysql.expect-database").hasMessageNotContaining("jdbc:");
            assertThat(SchemaUser.CREATED).as("核对在任何使用者拿到 DataSource 之前").isFalse();
        });
    }

    @Test
    void 应急开关放行偏离规格的库名_给错或给空仍拒启() throws SQLException {
        DataSource legacy = mysql("legacy_db");
        runner(legacy).withPropertyValues("game.mysql.expect-database=legacy_db").run(ctx -> assertThat(ctx).hasNotFailed());
        runner(legacy).withPropertyValues("game.mysql.expect-database=game").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("期望 game（应急开关 game.mysql.expect-database");
        });
        // 空值：由 GameMysqlProperties 的 @Pattern 在绑定时拒绝（Spring Boot 的标准绑定校验报告），核对器拿不到它，使用者也没被创建
        for (String blank : new String[] {"", " "}) {
            runner(legacy).withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("exact", Map.of("game.mysql.expect-database", blank)))).run(ctx -> {
                        assertThat(ctx).as("[%s]", blank).hasFailed();
                        assertThat(ctx.getStartupFailure()).as("[%s]", blank).hasRootCauseInstanceOf(BindValidationException.class);
                        assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("expectDatabase").hasMessageContaining("不用应急开关就不要设它");
                        assertThat(SchemaUser.CREATED).isFalse();
                    });
        }
    }

    private static ApplicationContextRunner withEnv(ApplicationContextRunner runner, Map<String, Object> env) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env)));
    }

    @Test
    void 应急开关的环境变量两种写法都认_文档写法GAME_MYSQL_EXPECT_DATABASE与规范写法GAME_MYSQL_EXPECTDATABASE() throws SQLException {
        withEnv(runner(mysql("legacy_db")), Map.of("GAME_MYSQL_EXPECT_DATABASE", "legacy_db")).run(ctx -> assertThat(ctx).hasNotFailed());
        withEnv(runner(mysql("legacy_db")), Map.of("GAME_MYSQL_EXPECTDATABASE", "legacy_db")).run(ctx -> assertThat(ctx).hasNotFailed());
        withEnv(runner(mysql("legacy_db")), Map.of("GAME_MYSQL_EXPECT_DATABASE", "")).run(ctx -> {
            assertThat(ctx).as("环境变量给空值同样拒启").hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
        withEnv(runner(mysql("game")), Map.of("GAME_MYSQL_PASSWORD", "x", "GAME_MYSQL_USER", "u")).run(ctx -> {
            assertThat(ctx).as("同一前缀下的其它变量（口令、账号）不绑定、不影响").hasNotFailed();
            assertThat(ctx.getBean(GameMysqlProperties.class).expectDatabase()).isNull();
        });
    }

    @Test
    void 连不上库也拒启_报错只带SQLState() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("Access denied jdbc:mysql://h/game?password=TOPSECRET", "28000", 1045));
        runner(broken).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SQLState=28000").hasMessageContaining("code=1045").hasMessageNotContaining("TOPSECRET");
            assertThat(SchemaUser.CREATED).isFalse();
        });
    }

    @Test
    void 不是MySQL系的库跳过_H2() throws SQLException {
        DataSource h2 = db("H2", "whatever");
        runner(h2).withPropertyValues("game.namespace=t1").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(SchemaUser.CREATED).isTrue();
        });
        verify(h2.getConnection(), never()).createStatement();
    }

    @Test
    void MariaDB也核() throws SQLException {
        runner(db("MariaDB", "game")).withPropertyValues("game.namespace=t1").run(ctx -> assertThat(ctx).hasFailed());
        assertThat(NamespaceDatabaseVerifier.isMysqlFamily("MySQL")).isTrue();
        assertThat(NamespaceDatabaseVerifier.isMysqlFamily("H2")).isFalse();
        assertThat(NamespaceDatabaseVerifier.isMysqlFamily(null)).isFalse();
    }

    @Test
    void 没有DataSource的进程不受影响() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(NamespaceDatabaseVerifier.class));
    }
}
