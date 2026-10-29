package com.game.data.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 配置缺失不能悄悄借用业务账号，也不能为了巡检启动已有的懒 Redis 连接。 */
class ConsistencyConfigurationTest {
    @Test
    void 未配置巡检凭据时保留业务数据源且不触发外部连接() {
        var namespace = GameNamespace.of("inspectiontest");
        var businessDataSource = mock(DataSource.class);
        var redisStarts = new AtomicInteger();
        new ApplicationContextRunner()
                .withUserConfiguration(ConsistencyConfiguration.class)
                .withPropertyValues("game.data.consistency.jdbc-url=",
                        "game.data.consistency.username=", "game.data.consistency.password=")
                .withBean(GameNamespace.class, () -> namespace)
                .withBean(RedisKeys.class, () -> new RedisKeys(namespace))
                .withBean(DataSource.class, () -> businessDataSource)
                .withBean(RedissonClient.class, () -> {
                    redisStarts.incrementAndGet();
                    throw new AssertionError("巡检不应初始化懒 Redis");
                }, definition -> definition.setLazyInit(true))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConsistencyService.class);
                    assertThat(context.getBean(DataSource.class)).isSameAs(businessDataSource);
                    var report = context.getBean(ConsistencyService.class).run(null);
                    assertThat(report.results()).hasSize(ConsistencyService.CORE.size())
                            .allSatisfy(result -> {
                                assertThat(result.status()).isEqualTo(ConsistencyReport.Status.UNKNOWN);
                                assertThat(result.reasons()).containsExactly("sql_not_configured");
                            });
                    assertThat(report.exitCode()).isEqualTo(3);
                    assertThat(redisStarts).hasValue(0);
                    verifyNoInteractions(businessDataSource);
                });
    }
}
