package com.game.data;

import com.game.audit.AuditProperties;
import com.game.audit.AuditTopics;
import com.game.common.KafkaNames;
import com.game.data.consume.AuditGroupProbe;
import com.game.data.consume.AuditOffsetSnapshot.ConsumerName;
import com.game.data.consume.AuditOffsetSnapshot.Target;
import com.game.data.consume.AuditProbeProperties;
import com.game.data.consume.KafkaGroupOffsetReader;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 与消费循环共用实际组名及 topic 代次；懒建只读 Admin，Spring 装配和 HTTP 读取均不连 broker。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditProbeProperties.class)
public class AuditProbeConfiguration {
    @Bean
    public AuditGroupProbe auditGroupProbe(DataNode data, AuditProperties audit, KafkaNames names,
                                           AuditProbeProperties properties, Clock clock, MeterRegistry meters) {
        var topics = new AuditTopics(names);
        int generation = audit.topicGeneration();
        List<Target> targets = List.of(
                new Target(ConsumerName.TRANSACTION_LOG, topics.transactionLog(generation).name(), generation,
                        data.transactionLogGroup(), AuditTopics.TRANSACTION_LOG_PARTITIONS),
                new Target(ConsumerName.PLAYER_SNAPSHOT, topics.playerSnapshot(generation).name(), generation,
                        data.playerSnapshotGroup(), AuditTopics.PLAYER_SNAPSHOT_PARTITIONS));
        return new AuditGroupProbe(targets, properties, () -> {
            Properties kafka = new Properties();
            kafka.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, audit.bootstrapServers());
            kafka.put(AdminClientConfig.CLIENT_ID_CONFIG, names.dataAuditProbeClientId());
            kafka.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, Long.toString(properties.timeout().toMillis()));
            kafka.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Long.toString(properties.timeout().toMillis()));
            return new KafkaGroupOffsetReader(Admin.create(kafka));
        }, clock, meters);
    }
}
