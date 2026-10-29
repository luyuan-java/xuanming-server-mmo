package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditDurabilityTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(1);
    private static final AuditTopics TOPICS = new AuditTopics(new KafkaNames(GameNamespace.of("auditcheck")));
    private static final List<TopicSpec> SPECS = TOPICS.all(2, 2);

    private static final class Broker implements TopicAdmin {
        final Map<String, Integer> partitions = new HashMap<>();
        final Map<String, Integer> replicas = new HashMap<>();
        final Map<String, Map<String, String>> configs = new HashMap<>();
        final List<Map<String, String>> changes = new ArrayList<>();
        int creates;
        int raceReplicas;
        boolean ignoreChanges;
        boolean unavailable;

        Broker existing(int replicaCount, String minIsr) {
            for (var spec : SPECS) {
                partitions.put(spec.name(), spec.partitions());
                replicas.put(spec.name(), replicaCount);
                var config = new HashMap<>(spec.configs());
                config.put(AuditTopicDurability.MIN_ISR, minIsr);
                configs.put(spec.name(), config);
            }
            return this;
        }
        @Override public Map<String, Integer> partitionCounts(Collection<String> topics, Duration timeout) {
            return Map.copyOf(partitions);
        }
        @Override public Map<String, Integer> minimumReplicaCounts(Collection<String> topics, Duration timeout) {
            if (unavailable) throw new AuditBrokerUnavailableException("副本元数据不可达", null);
            return Map.copyOf(replicas);
        }
        @Override public void create(Collection<TopicSpec> specs, short replicationFactor, Duration timeout) {
            creates++;
            for (var spec : specs) {
                partitions.put(spec.name(), spec.partitions());
                replicas.put(spec.name(), raceReplicas == 0 ? (int) replicationFactor : raceReplicas);
                configs.put(spec.name(), new HashMap<>(spec.configs()));
            }
        }
        @Override public Map<String, String> configs(String topic, Collection<String> keys, Duration timeout) {
            return Map.copyOf(configs.getOrDefault(topic, Map.of()));
        }
        @Override public void alterConfigs(String topic, Map<String, String> update, Duration timeout) {
            changes.add(Map.copyOf(update));
            if (!ignoreChanges) configs.get(topic).putAll(update);
        }
        @Override public void close() {}
    }

    private void ensure(Broker broker, AuditTopicInitializer.Mode mode) {
        AuditTopicInitializer.ensure(broker, SPECS, mode, (short) 3, TIMEOUT);
    }

    @Test void 新建时同时带副本数与显式持久配置() {
        var broker = new Broker();
        ensure(broker, AuditTopicInitializer.Mode.CREATE_AND_VERIFY);
        assertThat(broker.creates).isOne();
        assertThat(broker.replicas.values()).containsOnly(3);
        for (var spec : SPECS) assertThat(broker.configs.get(spec.name()))
                .containsEntry(AuditTopicDurability.MIN_ISR, "2")
                .containsEntry(AuditTopicDurability.UNCLEAN_ELECTION, "false");
        assertThat(broker.changes).isEmpty();
    }

    @Test void 任一topic存在弱分区都拒绝且主人不先改配置() {
        var broker = new Broker().existing(3, "1");
        broker.replicas.put(SPECS.getLast().name(), 1); // 管理端口返回全部分区里的最小值。
        assertThatThrownBy(() -> ensure(broker, AuditTopicInitializer.Mode.OWN))
                .isInstanceOf(AuditTopicContractException.class).hasMessageContaining("人工迁移");
        assertThat(broker.changes).isEmpty();
        assertThat(broker.creates).isZero();
    }

    @Test void 并发建成弱副本或元数据缺失均不从创建参数推断成功() {
        var race = new Broker();
        race.raceReplicas = 1;
        assertThatThrownBy(() -> ensure(race, AuditTopicInitializer.Mode.CREATE_AND_VERIFY))
                .isInstanceOf(AuditTopicContractException.class);
        var missing = new Broker().existing(3, "2");
        missing.replicas.remove(SPECS.getLast().name());
        assertThatThrownBy(() -> ensure(missing, AuditTopicInitializer.Mode.VERIFY_ONLY))
                .isInstanceOf(AuditTopicContractException.class);
    }

    @Test void 只读与生产方拒绝弱minISR不创建不修改而主人可提升并读回() {
        for (var mode : List.of(AuditTopicInitializer.Mode.VERIFY_ONLY, AuditTopicInitializer.Mode.CREATE_AND_VERIFY)) {
            var broker = new Broker().existing(3, "1");
            assertThatThrownBy(() -> ensure(broker, mode)).isInstanceOf(AuditTopicContractException.class);
            assertThat(broker.changes).isEmpty();
            assertThat(broker.creates).isZero();
        }
        var owner = new Broker().existing(3, "1");
        ensure(owner, AuditTopicInitializer.Mode.OWN);
        assertThat(owner.changes).hasSize(2).allSatisfy(change ->
                assertThat(change).containsExactlyEntriesOf(Map.of(AuditTopicDurability.MIN_ISR, "2")));
    }

    @Test void 已有更强minISR不降低且不能超过实际副本数() {
        var stronger = new Broker().existing(5, "4");
        ensure(stronger, AuditTopicInitializer.Mode.OWN);
        assertThat(stronger.changes).isEmpty();
        assertThat(stronger.configs.values()).allSatisfy(config -> assertThat(config)
                .containsEntry(AuditTopicDurability.MIN_ISR, "4"));
        var impossible = new Broker().existing(3, "4");
        assertThatThrownBy(() -> ensure(impossible, AuditTopicInitializer.Mode.OWN))
                .isInstanceOf(AuditTopicContractException.class).hasMessageContaining("超过实际");
        assertThat(impossible.changes).isEmpty();
    }

    @Test void 坏配置只允许主人修复且修不动不能通过() {
        for (String value : List.of("", "abc", "0", "-1")) {
            var broker = new Broker().existing(3, value);
            assertThatThrownBy(() -> ensure(broker, AuditTopicInitializer.Mode.VERIFY_ONLY))
                    .isInstanceOf(AuditTopicContractException.class);
            ensure(broker, AuditTopicInitializer.Mode.OWN);
        }
        var ignored = new Broker().existing(3, "1");
        ignored.ignoreChanges = true;
        assertThatThrownBy(() -> ensure(ignored, AuditTopicInitializer.Mode.OWN))
                .isInstanceOf(AuditTopicContractException.class);
    }

    @Test void 禁止unclean选主与只读缺topic均拒绝() {
        var broker = new Broker().existing(3, "2");
        broker.configs.get(SPECS.getFirst().name()).put(AuditTopicDurability.UNCLEAN_ELECTION, "true");
        assertThatThrownBy(() -> ensure(broker, AuditTopicInitializer.Mode.VERIFY_ONLY))
                .isInstanceOf(AuditTopicContractException.class);
        ensure(broker, AuditTopicInitializer.Mode.OWN);
        var missing = new Broker();
        assertThatThrownBy(() -> ensure(missing, AuditTopicInitializer.Mode.VERIFY_ONLY))
                .isInstanceOf(AuditTopicContractException.class);
        assertThat(missing.creates).isZero();
    }

    @Test void 元数据故障保留可恢复故障类型() {
        var broker = new Broker().existing(3, "2");
        broker.unavailable = true;
        assertThatThrownBy(() -> ensure(broker, AuditTopicInitializer.Mode.OWN))
                .isInstanceOf(AuditBrokerUnavailableException.class);
        assertThat(broker.changes).isEmpty();
    }
}
