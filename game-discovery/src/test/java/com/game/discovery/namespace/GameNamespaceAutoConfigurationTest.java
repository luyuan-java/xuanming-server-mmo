package com.game.discovery.namespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.discovery.RedisKeys;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 部署命名空间的装配：{@code game.namespace} 经 {@link GameNamespaceProperties}（{@code @Validated}）绑定与校验，注册 {@link GameNamespace} /
 * {@link RedisKeys} / {@link KafkaNames} 三个 bean；非法值由 Spring Boot 的绑定校验失败拒启（根因 {@link BindValidationException}，标准的
 * {@code APPLICATION FAILED TO START} 报告），而且在任何 DataSource 被创建之前。
 */
@ExtendWith(OutputCaptureExtension.class)
class GameNamespaceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class));

    /** 原样给值（withPropertyValues 会去掉首尾空白，测不了 " game"）。 */
    private ApplicationContextRunner withExactValue(String value) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("exact", Map.of(GameNamespace.PROPERTY, value))));
    }

    private static boolean causedBy(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void 没配取缺省game_三个bean各一个() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(GameNamespace.class).hasSingleBean(RedisKeys.class).hasSingleBean(KafkaNames.class)
                    .hasSingleBean(GameNamespaceProperties.class).hasSingleBean(NamespaceDatabaseVerifier.class);
            assertThat(ctx.getBean(GameNamespace.class).value()).isEqualTo("game");
            assertThat(ctx.getBean(RedisKeys.class).prefix()).isEqualTo("game:");
            assertThat(ctx.getBean(RedisKeys.class).namespace()).isSameAs(ctx.getBean(GameNamespace.class));
            assertThat(ctx.getBean(KafkaNames.class).prefix()).isEqualTo("game-");
            assertThat(ctx.getBean(KafkaNames.class).namespace()).isSameAs(ctx.getBean(GameNamespace.class));
        });
        runner.withPropertyValues("game.namespace=game").run(ctx -> assertThat(ctx.getBean(GameNamespace.class).value()).isEqualTo("game"));
    }

    @Test
    void 属性给nstest_三个bean都跟着走() {
        runner.withPropertyValues("game.namespace=nstest").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(GameNamespace.class).value()).isEqualTo("nstest");
            assertThat(ctx.getBean(RedisKeys.class).presence(7)).isEqualTo("nstest:presence:7");
            assertThat(ctx.getBean(KafkaNames.class).transactionLogTopicBase()).isEqualTo("nstest-transaction-log");
        });
    }

    @Test
    void 环境变量GAME_NAMESPACE经宽松绑定生效() {
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("GAME_NAMESPACE", "t1"))))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(GameNamespace.class).value()).isEqualTo("t1");
                });
    }

    @Test
    void 显式空串必须拒启_不被DefaultValue换回game() {
        runner.withPropertyValues("game.namespace=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("namespace").hasMessageContaining(GameNamespace.RULE);
        });
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("GAME_NAMESPACE", ""))))
                .run(ctx -> {
                    assertThat(ctx).as("环境变量设成空串同样拒启").hasFailed();
                    assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void 非法值一律拒启_根因是绑定校验失败() {
        for (String bad : List.of("Bad", " game", "game ", "mysql", "sys", "public", "1a", "a-b", "a" + "0".repeat(28))) {
            withExactValue(bad).run(ctx -> {
                assertThat(ctx).as("[%s]", bad).hasFailed();
                assertThat(causedBy(ctx.getStartupFailure(), ConfigurationPropertiesBindException.class)).as("[%s] 经 Boot 的绑定失败", bad).isTrue();
                assertThat(ctx.getStartupFailure()).as("[%s]", bad)
                        .hasRootCauseInstanceOf(BindValidationException.class);
                assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(GameNamespace.RULE);
            });
        }
    }

    @Test
    void 真SpringApplication_非法值输出标准的绑定校验报告(CapturedOutput output) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(Minimal.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "game.namespace=Bad").run().close())
                .hasRootCauseInstanceOf(BindValidationException.class);
        assertThat(output).contains("APPLICATION FAILED TO START").contains("Property: game.namespace").contains("Value: \"Bad\"")
                .contains("Origin:").contains("Reason: " + GameNamespace.RULE);
    }

    @Test
    void 真SpringApplication_合法值打出命名空间行(CapturedOutput output) {
        new SpringApplicationBuilder(Minimal.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "game.namespace=t1").run().close();
        assertThat(output).contains("部署命名空间 game.namespace=t1（Redis 前缀 t1:，Kafka 前缀 t1-，MySQL 库 t1）");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(GameNamespaceAutoConfiguration.class)
    static class Minimal {
    }

    /** 记录 DataSource 的工厂方法有没有被调用过：命名空间非法时必须一次都没有（depends-on 生效）。 */
    @Configuration(proxyBeanMethods = false)
    static class FakeDataSourceConfig {

        static final AtomicInteger CREATED = new AtomicInteger();

        @Bean
        DataSource dataSource() throws SQLException {
            CREATED.incrementAndGet();
            DatabaseMetaData metaData = mock(DatabaseMetaData.class);
            when(metaData.getDatabaseProductName()).thenReturn("H2");
            Connection connection = mock(Connection.class);
            when(connection.getMetaData()).thenReturn(metaData);
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenReturn(connection);
            return dataSource;
        }
    }

    @Test
    void 命名空间非法时DataSource的工厂方法一次都没被调用() {
        FakeDataSourceConfig.CREATED.set(0);
        runner.withUserConfiguration(FakeDataSourceConfig.class).withPropertyValues("game.namespace=mysql").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class);
        });
        assertThat(FakeDataSourceConfig.CREATED).as("校验先于建连接池").hasValue(0);

        runner.withUserConfiguration(FakeDataSourceConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeanFactory().getBeanDefinition("dataSource").getDependsOn()).contains("gameNamespace");
        });
        assertThat(FakeDataSourceConfig.CREATED).hasValue(1);
    }

    @Test
    void 带方法校验的上下文里_record加Validated也能正常起来_不被代理() {
        runner.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class)).withPropertyValues("game.namespace=nstest").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasBean("methodValidationPostProcessor");
            GameNamespaceProperties properties = ctx.getBean(GameNamespaceProperties.class);
            assertThat(AopUtils.isAopProxy(properties)).isFalse();
            assertThat(properties.namespace()).isEqualTo("nstest");
        });
        runner.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class)).withPropertyValues("game.namespace=Bad")
                .run(ctx -> assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(BindValidationException.class));
    }
}
