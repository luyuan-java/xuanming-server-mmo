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

class ExpiredFormationAuditConfigurationTest {
    private final GameNamespace namespace = GameNamespace.of("auditcase");
    private final DataSource business = mock(DataSource.class);
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ConsistencyConfiguration.class)
                .withBean(GameNamespace.class, () -> namespace)
                .withBean(RedisKeys.class, () -> new RedisKeys(namespace))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DataSource.class, () -> business)
                .withPropertyValues("game.data.consistency.jdbc-url=", "game.data.consistency.username=", "game.data.consistency.password=",
                        "game.data.consistency.terminal-backlog.enabled=false");
    }
    @Test void defaultScheduleHasReadOnlyBudgetAndMissingCredentialsNeverUseBusinessPool() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ExpiredFormationAudit.class);
            var properties = context.getBean(ExpiredFormationAuditProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.interval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.budget()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.maxRows()).isEqualTo(1024);
            assertThat(context.getBean(ExpiredFormationAudit.class).observation().badLowerBound()).isNaN();
            verifyNoInteractions(business);
        });
    }
    @Test void operatorCanDisableWithoutDisablingExistingOneShotChecks() {
        runner().withPropertyValues("game.data.consistency.expired-formations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ConsistencyService.class);
                    var audit = context.getBean(ExpiredFormationAudit.class);
                    audit.round();
                    assertThat(audit.observation().state()).isEqualTo(ExpiredFormationAudit.State.DISABLED);
                    assertThat(audit.observation().badLowerBound()).isNaN();
                    verifyNoInteractions(business);
                });
    }
    @Test void invalidBudgetAndFreshnessSettingsRejectStartup() {
        for (String property : new String[]{"budget=4s", "interval=0s", "page-size=0", "max-rows=4097", "stale-after=1s"}) {
            runner().withPropertyValues("game.data.consistency.expired-formations." + property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
