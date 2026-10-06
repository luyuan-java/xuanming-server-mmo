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

/**
 * 对局结果 topic 的规格（match-spec §5.4）与带代次环境变量名的核对重载（评审问题 7）：名字、分区数、保留期钉住；分区数不符时报错指向
 * {@code XM_BATTLE_RESULT_TOPIC_GENERATION} 而不是审计的变量；审计的旧签名文案一个字不变。
 */
class BattleResultTopicsTest {

    /** 内存里的假 broker（只做本测试用到的事）。 */
    private static final class FakeAdmin implements TopicAdmin {
        final Map<String, Integer> partitions = new HashMap<>();
        final Map<String, Map<String, String>> configs = new HashMap<>();
        final List<String> created = new ArrayList<>();
        final List<Short> replicationFactors = new ArrayList<>();
        final List<Map<String, String>> alters = new ArrayList<>();
        boolean unreachable;

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
                created.add(spec.name());
                replicationFactors.add(replicationFactor);
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
            configs.computeIfAbsent(topic, t -> new HashMap<>()).putAll(changes);
        }

        @Override
        public void close() {
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void 规格钉住_名字带代次_三分区_保留七天() {
        TopicSpec spec = BattleResultTopics.spec(1);

        assertThat(BattleResultTopics.BASE).isEqualTo("xm-battle-result");
        assertThat(BattleResultTopics.name(1)).isEqualTo("xm-battle-result-g1");
        assertThat(BattleResultTopics.name(7)).isEqualTo("xm-battle-result-g7");
        assertThat(spec.name()).isEqualTo("xm-battle-result-g1");
        assertThat(spec.partitions()).isEqualTo(3).isEqualTo(BattleResultTopics.PARTITIONS);
        assertThat(spec.configs()).containsOnly(
                Map.entry("retention.ms", "604800000"),
                Map.entry("retention.bytes", "-1"),
                Map.entry("cleanup.policy", "delete"));
        assertThat(Long.parseLong(spec.configs().get("retention.ms"))).isEqualTo(Duration.ofDays(7).toMillis());
        assertThat(BattleResultTopics.GENERATION_ENV).isEqualTo("XM_BATTLE_RESULT_TOPIC_GENERATION");
    }

    @Test
    void 代次小于1非法_与审计topic互不重名() {
        assertThatThrownBy(() -> BattleResultTopics.name(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BattleResultTopics.spec(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AuditTopics.all(1)).extracting(TopicSpec::name).doesNotContain(BattleResultTopics.name(1));
    }

    @Test
    void 消息key是battle_id的无符号十进制() {
        assertThat(BattleResultTopics.key(1001)).isEqualTo("1001");
        assertThat(BattleResultTopics.key(0)).isEqualTo("0");
        assertThat(BattleResultTopics.key(-1L)).isEqualTo("18446744073709551615");
        assertThat(BattleResultTopics.key(Long.MIN_VALUE)).isEqualTo("9223372036854775808");
    }

    @Test
    void 不存在就按规格创建_带上保留期与副本数() {
        FakeAdmin admin = new FakeAdmin();

        BattleResultTopics.ensure(admin, 2, Mode.CREATE_AND_VERIFY, (short) 3, TIMEOUT);

        assertThat(admin.created).containsExactly("xm-battle-result-g2");
        assertThat(admin.replicationFactors).containsExactly((short) 3);
        assertThat(admin.partitions).containsEntry("xm-battle-result-g2", 3);
        assertThat(admin.configs.get("xm-battle-result-g2")).containsEntry("retention.ms", "604800000");
        assertThat(admin.alters).as("刚按规格建的 topic 不需要校正").isEmpty();
    }

    @Test
    void 分区数不符_报错指向对局结果的代次变量_不提审计() {
        FakeAdmin admin = new FakeAdmin();
        admin.partitions.put("xm-battle-result-g1", 1);

        assertThatThrownBy(() -> BattleResultTopics.ensure(admin, 1, Mode.CREATE_AND_VERIFY, (short) 1, TIMEOUT))
                .isInstanceOf(AuditTopicContractException.class)
                .hasMessage("对局结果 topic xm-battle-result-g1 分区数是 1，契约是 3；不能原地改分区数，请升 topic 代次（XM_BATTLE_RESULT_TOPIC_GENERATION）")
                .hasMessageNotContaining("XM_AUDIT_TOPIC_GENERATION")
                .hasMessageNotContaining("审计");
        assertThat(admin.created).isEmpty();
    }

    @Test
    void 主人把保留期校正到七天并读回_生产方不改配置() {
        FakeAdmin produced = new FakeAdmin();
        produced.partitions.put("xm-battle-result-g1", 3);
        produced.configs.put("xm-battle-result-g1", new HashMap<>(Map.of("retention.ms", "1800000", "cleanup.policy", "delete")));
        BattleResultTopics.ensure(produced, 1, Mode.CREATE_AND_VERIFY, (short) 1, TIMEOUT);
        assertThat(produced.alters).as("xm-battle 只核对分区数").isEmpty();

        FakeAdmin owned = new FakeAdmin();
        owned.partitions.put("xm-battle-result-g1", 3);
        owned.configs.put("xm-battle-result-g1", new HashMap<>(Map.of("retention.ms", "1800000", "cleanup.policy", "delete")));
        BattleResultTopics.ensure(owned, 1, Mode.OWN, (short) 1, TIMEOUT);
        assertThat(owned.alters).containsExactly(Map.of("retention.ms", "604800000", "retention.bytes", "-1"));
        assertThat(owned.configs.get("xm-battle-result-g1")).containsAllEntriesOf(BattleResultTopics.spec(1).configs());
    }

    @Test
    void 连不上broker_抛可恢复异常_调用方后台重试() {
        FakeAdmin admin = new FakeAdmin();
        admin.unreachable = true;

        assertThatThrownBy(() -> BattleResultTopics.ensure(admin, 1, Mode.OWN, (short) 1, TIMEOUT))
                .isInstanceOf(AuditBrokerUnavailableException.class);
    }

    @Test
    void 带代次环境变量名的通用重载_报错里是调用方给的变量名() {
        FakeAdmin admin = new FakeAdmin();
        TopicSpec spec = new TopicSpec("xm-some-topic-g1", 4, Map.of("retention.ms", "1000"));
        admin.partitions.put(spec.name(), 2);

        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, List.of(spec), Mode.CREATE_AND_VERIFY, (short) 1, TIMEOUT, "XM_SOME_TOPIC_GENERATION"))
                .isInstanceOf(AuditTopicContractException.class)
                .hasMessage("Kafka topic xm-some-topic-g1 分区数是 2，契约是 4；不能原地改分区数，请升 topic 代次（XM_SOME_TOPIC_GENERATION）");

        admin.partitions.put(spec.name(), 4);
        AuditTopicInitializer.ensure(admin, List.of(spec), Mode.OWN, (short) 1, TIMEOUT, "XM_SOME_TOPIC_GENERATION");
        assertThat(admin.alters).as("重载与旧签名同一套逻辑：主人照样校正配置").containsExactly(Map.of("retention.ms", "1000"));
    }

    @Test
    void 通用重载的变量名不能为空() {
        FakeAdmin admin = new FakeAdmin();
        List<TopicSpec> specs = List.of(BattleResultTopics.spec(1));

        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, specs, Mode.CREATE_AND_VERIFY, (short) 1, TIMEOUT, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, specs, Mode.CREATE_AND_VERIFY, (short) 1, TIMEOUT, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(admin.created).as("参数不合法时什么都不建").isEmpty();
    }

    @Test
    void 审计的旧签名文案一个字不变() {
        FakeAdmin admin = new FakeAdmin();
        TopicSpec txlog = AuditTopics.transactionLog(1);
        admin.partitions.put(txlog.name(), 1);

        assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, List.of(txlog), Mode.CREATE_AND_VERIFY, (short) 1, TIMEOUT))
                .isInstanceOf(AuditTopicContractException.class)
                .hasMessage("审计 topic xm-transaction-log-g1 分区数是 1，契约是 6；不能原地改分区数，请升 topic 代次（XM_AUDIT_TOPIC_GENERATION）");
        assertThat(AuditTopicInitializer.AUDIT_GENERATION_ENV).isEqualTo("XM_AUDIT_TOPIC_GENERATION");
    }
}
