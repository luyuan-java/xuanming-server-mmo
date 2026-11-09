package com.game.data.consistency;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TerminalOutboxBacklogConfigurationTest {
    private final GameNamespace namespace = GameNamespace.of("backlogcase");
    private final DataSource business = mock(DataSource.class);
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ConsistencyConfiguration.class)
                .withBean(GameNamespace.class, () -> namespace)
                .withBean(RedisKeys.class, () -> new RedisKeys(namespace))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DataSource.class, () -> business)
                .withPropertyValues("game.data.consistency.jdbc-url=", "game.data.consistency.username=",
                        "game.data.consistency.password=", "game.data.consistency.expired-formations.enabled=false");
    }

    @Test void defaultScheduleUsesIndependentBudgetAndMissingSqlNeverBorrowsBusinessPool() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TerminalOutboxBacklogAudit.class);
            var properties = context.getBean(TerminalOutboxBacklogProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.budget()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.staleAfter()).isEqualTo(Duration.ofSeconds(150));
            var audit = context.getBean(TerminalOutboxBacklogAudit.class);
            assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.RESULT)).isNaN();
            assertThat(context.getBean(DataSource.class)).isSameAs(business);
            verifyNoInteractions(business);
        });
    }

    @Test void disabledBacklogDoesNotDisableExistingChecksOrRegisterAnotherDataSource() {
        runner().withPropertyValues("game.data.consistency.terminal-backlog.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConsistencyService.class).hasSingleBean(DataSource.class);
                    var audit = context.getBean(TerminalOutboxBacklogAudit.class);
                    audit.round();
                    assertThat(audit.observation().state()).isEqualTo(TerminalOutboxBacklogAudit.State.DISABLED);
                    assertThat(audit.observation().rows(TerminalOutboxBacklogAudit.Kind.WAITING)).isNaN();
                    verifyNoInteractions(business);
                });
    }

    @Test void configuredDedicatedReaderIsSharedAndDisabledAuditsDoNotOpenConnections() {
        runner().withPropertyValues("game.data.consistency.jdbc-url=jdbc:mysql://127.0.0.1:1/backlogcase",
                        "game.data.consistency.username=inspection", "game.run-mode=dev",
                        "game.data.consistency.terminal-backlog.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JdbcConsistencyReader.class).hasSingleBean(DataSource.class);
                    assertThat(context.getBean(DataSource.class)).isSameAs(business);
                    assertThat(context.getBean(TerminalOutboxBacklogAudit.class).observation().state())
                            .isEqualTo(TerminalOutboxBacklogAudit.State.DISABLED);
                    verifyNoInteractions(business);
                });
    }

    @Test void invalidBudgetIntervalAndFreshnessRejectStartup() {
        for (String property : new String[]{"budget=4s", "budget=99ms", "interval=0s", "interval=301s", "stale-after=1s", "stale-after=901s"}) {
            runner().withPropertyValues("game.data.consistency.terminal-backlog." + property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
