package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.AuditProperties;
import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.data.consume.AuditProbeProperties;
import com.game.data.consume.AuditProbeState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuditProbeConfigurationTest {
    @Test void 实际组名命名空间代次分区接线一致_装配不连接broker() {
        KafkaNames names = new KafkaNames(GameNamespace.of("nstest"));
        var props = new Binder(new MapConfigurationPropertySource(Map.of("game.data.transaction-log.group", "explicit-group")))
                .bindOrCreate("game.data", DataProperties.class);
        DataNode data = new DataNode(null, props, names, null, null, null, null, null, null);
        var audit = new AuditProperties(true, "not-a-reachable-host.invalid:9092", 9, (short) 1, Duration.ofSeconds(1));
        var meters = new SimpleMeterRegistry();
        try {
            var probe = new AuditProbeConfiguration().auditGroupProbe(data, audit, names,
                    new AuditProbeProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(90)),
                    Clock.systemUTC(), meters);
            assertThat(probe.isRunning()).isFalse();
            assertThat(probe.views()).allSatisfy(view -> assertThat(view.status()).isEqualTo(AuditProbeState.Status.UNKNOWN));
            assertThat(probe.views().get(0).target().topic()).isEqualTo("nstest-transaction-log-g9");
            assertThat(probe.views().get(0).target().group()).isEqualTo("explicit-group");
            assertThat(probe.views().get(0).target().partitionCount()).isEqualTo(6);
            assertThat(probe.views().get(1).target().topic()).isEqualTo("nstest-player-snapshot-g9");
            assertThat(probe.views().get(1).target().group()).isEqualTo("nstest-data-player-snapshot");
            assertThat(probe.views().get(1).target().partitionCount()).isEqualTo(3);
            assertThat(names.dataAuditProbeClientId()).isEqualTo("nstest-data-audit-probe");
        } finally {
            meters.close();
        }
    }
}
