package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.audit.AuditTopicInitializer.Mode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditTopicInitializerTest {

    /** 内存里的假 broker。 */
    private static final class FakeAdmin implements TopicAdmin {
        final Map<String, Integer> partitions = new HashMap<>();
        final Map<String, Map<String, String>> configs = new HashMap<>();
        final List<String> created = new ArrayList<>();
        final List<Map<String, String>> alters = new ArrayList<>();
        boolean unreachable;
        /** 并发建好：create 时发现已存在（模拟另一进程抢先）。 */
        Map<String, Integer> raceCreatesWith;
        boolean ignoreAlter;

        @Override
        public Map<String, Integer> partitionCounts(Collection<String> topics, Duration timeout) {
            if (unreachable) {
                throw new AuditBrokerUnavailableException("连不上", null);
            }
            Map<String, Integer> out = new HashMap<>();
            topics.forEach(t -> {
                if (partitions.containsKey(t)) {
                    out.put(t, partitions.get(t));
                }
            });
            return out;
        }

        @Override
        public void create(Collection<TopicSpec> specs, short replicationFactor, Duration timeout) {
            for (TopicSpec spec : specs) {
                if (raceCreatesWith != null && raceCreatesWith.containsKey(spec.name())) {
                    partitions.put(spec.name(), raceCreatesWith.get(spec.name()));
                    continue;
                }
                created.add(spec.name());
                partitions.put(spec.name(), spec.partitions());
                configs.put(spec.name(), new HashMap<>(spec.configs()));
            }
        }

        @Override
        public Map<String, String> configs(String topic, Collection<String> keys, Duration timeout) {
            Map<String, String> out = new HashMap<>();
            Map<String, String> all = configs.getOrDefault(topic, Map.of());
            keys.forEach(k -> {
                if (all.containsKey(k)) {
                    out.put(k, all.get(k));
                }
            });
            return out;
        }

        @Override
        public void alterConfigs(String topic, Map<String, String> changes, Duration timeout) {
            alters.add(changes);
            if (!ignoreAlter) {
                configs.computeIfAbsent(topic, t -> new HashMap<>()).putAll(changes);
            }
        }

        @Override
        public void close() {
        }
    }

    private static final TopicSpec TXLOG = AuditTopics.transactionLog(1);

    @Test
    void 不存在就按规格创建() {
        FakeAdmin admin = new FakeAdmin();

        AuditTopicInitializer.ensure(admin, List.of(TXLOG), Mode.CREATE_AND_VERIFY, (short) 1, Duration.ofSeconds(1));

        assertThat(admin.created).containsExactly("xm-transaction-log-g1");
        assertThat(admin.partitions).containsEntry("xm-transaction-log-g1", 6);
        assertThat(admin.configs.get("xm-transaction-log-g1")).containsEntry("retention.ms", "2592000000")
                .containsEntry("retention.bytes", "-1");
    }

    @Test
    void 已存在且分区数一致_不建不改() {
        FakeAdmin admin = new FakeAdmin();
        admin.partitions.put(TXLOG.name(), 6);

        AuditTopicInitializer.ensure(admin, List.of(TXLOG), Mode.CREATE_AND_VERIFY, (short) 1, Duration.ofSeconds(1));

        assertThat(admin.created).isEmpty();
        assertThat(admin.alters).as("生产方不改配置").isEmpty();
    }

    @Test
    void 分区数不符_拒绝() {
        FakeAdmin admin = new FakeAdmin();
        admin.partitions.put(TXLOG.name(), 1);

        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, List.of(TXLOG), Mode.CREATE_AND_VERIFY, (short) 1,
                Duration.ofSeconds(1))).isInstanceOf(AuditTopicContractException.class).hasMessageContaining("代次");
    }

    @Test
    void 并发被别人建成错误分区数_同样拒绝() {
        FakeAdmin admin = new FakeAdmin();
        admin.raceCreatesWith = Map.of(TXLOG.name(), 1);

        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, List.of(TXLOG), Mode.CREATE_AND_VERIFY, (short) 1,
                Duration.ofSeconds(1))).isInstanceOf(AuditTopicContractException.class);
    }

    @Test
    void 消费方把配置校正到规格并读回() {
        FakeAdmin admin = new FakeAdmin();
        admin.partitions.put(TXLOG.name(), 6);
        admin.configs.put(TXLOG.name(), new HashMap<>(Map.of("retention.ms", "1800000", "cleanup.policy", "delete")));

        AuditTopicInitializer.ensure(admin, List.of(TXLOG), Mode.OWN, (short) 1, Duration.ofSeconds(1));

        assertThat(admin.alters).containsExactly(Map.of("retention.ms", "2592000000", "retention.bytes", "-1"));
        assertThat(admin.configs.get(TXLOG.name())).containsAllEntriesOf(TXLOG.configs());
    }

    @Test
    void 配置改不过来_拒绝() {
        FakeAdmin admin = new FakeAdmin();
        admin.partitions.put(TXLOG.name(), 6);
        admin.ignoreAlter = true;

        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, List.of(TXLOG), Mode.OWN, (short) 1,
                Duration.ofSeconds(1))).isInstanceOf(AuditTopicContractException.class);
    }

    @Test
    void 连不上broker_抛可恢复异常() {
        FakeAdmin admin = new FakeAdmin();
        admin.unreachable = true;

        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, List.of(TXLOG), Mode.CREATE_AND_VERIFY, (short) 1,
                Duration.ofSeconds(1))).isInstanceOf(AuditBrokerUnavailableException.class);
    }

    @Test
    void topic名带代次_代次小于1非法_分区键无符号() {
        assertThat(AuditTopics.name("xm-transaction-log", 3)).isEqualTo("xm-transaction-log-g3");
        assertThatThrownBy(() -> AuditTopics.name("xm-transaction-log", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AuditKeys.transactionKey(0, 1001)).isEqualTo("1001");
        assertThat(AuditKeys.transactionKey(7, 1001)).as("扣减方优先").isEqualTo("7");
        assertThat(AuditKeys.transactionKey(-1L, 0)).isEqualTo("18446744073709551615");
        assertThat(AuditKeys.transactionKey(0, 0)).isEqualTo("0");
    }
}
