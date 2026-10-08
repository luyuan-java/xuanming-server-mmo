package com.game.battle.port.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.BattleResultTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.audit.TopicSpec;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.port.BattleResultSink.Channel;
import com.game.battle.testing.ResultFallbackCapture;
import com.game.proto.BattleActivityContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 对局结果的 Kafka 生产方连真 Kafka（{@code -Dxm.it.kafka=127.0.0.1:9092}，缺省跳过；match-spec §5.4、§15.3）：真的幂等生产者、真的 topic 管理。
 * 本机 broker 不删 topic、又是多人共用，所以用两个<b>固定的测试代次</b>（幂等创建、反复使用，不碰任何进程在用的代次）：
 * {@value #GENERATION}（按契约 3 分区，由被测对象自己建）与 {@value #MISMATCH_GENERATION}（故意先建成 2 分区）。每次运行用唯一的 battle_id
 * 与消费组，只读自己发的消息；不删任何东西。
 *
 * <p>这两个代次是 xm-battle 的测试专用：xm-match 的 {@code BattleResultTopicIntegrationTest} 用的是 9641 / 9642 / 9644，它的消费者从头读自己的
 * 测试 topic 并真的入账——两个模块的测试不能共用一个 topic，新增测试代次时两边互相避开。
 */
@EnabledIfSystemProperty(named = "xm.it.kafka", matches = ".+")
class KafkaBattleResultSinkIntegrationTest {

    private static final int GENERATION = 9671;
    private static final int MISMATCH_GENERATION = 9672;
    private static final String BOOTSTRAP = System.getProperty("xm.it.kafka");
    private static final Duration WAIT = Duration.ofSeconds(60);

    /** 本次运行的标记：battle_id 的高位与消费组名都带它，别的运行（别的同事）留下的消息不会混进断言。 */
    private final long run = System.currentTimeMillis();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final ResultFallbackCapture fallback = ResultFallbackCapture.start();
    private KafkaBattleResultSink sink;

    @AfterEach
    void tearDown() {
        if (sink != null) {
            sink.close();
        }
        fallback.close();
    }

    private KafkaBattleResultSink sink(String bootstrap, int generation, Duration verifyTimeout) {
        sink = new KafkaBattleResultSink(KafkaBattleResultSink.Clients.kafka(bootstrap, "xm-it-battle-result-" + run), generation, (short) 1,
                verifyTimeout, metrics);
        return sink;
    }

    private BattleResultEvent event(int n) {
        long battleId = run * 1000 + n;
        return BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(1).setBattleConfigId(0)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN).setWinnerTeamIndex(1)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(run + 1))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(run + 2))
                .setTotalRounds(9).setFinishedAtMs(run).addDeadPlayerIds(run + 1).build();
    }

    private double events(String result) {
        return registry.get("xm.battle.result.events").tag("result", result).counter().count();
    }

    private double results(String channel, String result) {
        return registry.get("xm.battle.results").tag("channel", channel).tag("result", result).counter().count();
    }

    /** 从头读 {@code topic}，直到本次运行的 {@code expected} 条（按 key 认）都读到；别的 key 一律略过。 */
    private List<ConsumerRecord<String, byte[]>> consumeOwn(String topic, Set<String> ownKeys, int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "xm-it-battle-result-" + run);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, byte[]>> own = new ArrayList<>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            while (own.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (ownKeys.contains(record.key())) {
                        own.add(record);
                    }
                }
            }
        }
        return own;
    }

    @Test
    void 真Kafka_启动核对按契约建出三分区保留七天的topic_发布的事件落到topic_key与字节原样_同一局进同一分区且保序_重发照发() {
        String topic = BattleResultTopics.name(GENERATION);
        assertThat(topic).isEqualTo("xm-battle-result-g9671");
        sink(BOOTSTRAP, GENERATION, Duration.ofSeconds(20)).start();
        assertThat(sink.verified()).isTrue();
        assertThat(sink.topic()).isEqualTo(topic);
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-it-battle-result-admin")) {
            assertThat(admin.partitionCounts(List.of(topic), Duration.ofSeconds(10))).containsEntry(topic, 3);
            assertThat(admin.configs(topic, List.of("retention.ms", "retention.bytes", "cleanup.policy"), Duration.ofSeconds(10)))
                    .as("battle 先于 xm-match 启动时按规格建：保留期等配置随创建带上").containsEntry("retention.ms", "604800000")
                    .containsEntry("retention.bytes", "-1").containsEntry("cleanup.policy", "delete");
        }

        List<BattleResultEvent> plain = List.of(event(1), event(2), event(3), event(4), event(5));
        BattleResultEvent activity = event(6).toBuilder().setMatchMode(5).setActivityContext(BattleActivityContext.newBuilder()
                .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(66).setActivityId(3)).build();
        plain.forEach(sink::publish);
        // 活动结果通道的首发 + 两次重发：同一个事件对象
        sink.publish(activity, Channel.ACTIVITY);
        sink.publish(activity, Channel.ACTIVITY);
        sink.publish(activity, Channel.ACTIVITY);

        // 生产者回调里先计 xm_battle_result_events、再计 xm_battle_results：等后写的那个，先写的才一定已经到位
        await().atMost(WAIT).until(() -> results("plain", "sent") == 5 && results("activity", "sent") == 3);
        assertThat(events("sent")).isEqualTo(8);
        assertThat(events("fallback") + events("not_verified")).isZero();
        assertThat(fallback.size()).isZero();

        Map<String, BattleResultEvent> byKey = new HashMap<>();
        plain.forEach(e -> byKey.put(Long.toUnsignedString(e.getBattleId()), e));
        byKey.put(Long.toUnsignedString(activity.getBattleId()), activity);
        List<ConsumerRecord<String, byte[]>> records = consumeOwn(topic, byKey.keySet(), 8);

        assertThat(records).as("本次运行发的 8 条都读得到").hasSize(8);
        assertThat(records).allSatisfy(record -> {
            BattleResultEvent expected = byKey.get(record.key());
            assertThat(record.value()).as("value 是事件的完整字节 key=%s", record.key()).isEqualTo(expected.toByteArray());
            assertThat(record.partition()).isBetween(0, 2);
        });
        Map<String, Long> perKey = records.stream().collect(Collectors.groupingBy(ConsumerRecord::key, Collectors.counting()));
        plain.forEach(e -> assertThat(perKey).containsEntry(Long.toUnsignedString(e.getBattleId()), 1L));
        String activityKey = Long.toUnsignedString(activity.getBattleId());
        assertThat(perKey).as("同一 battle_id 的重发原样到达，由消费方按 battle_id 幂等").containsEntry(activityKey, 3L);
        List<ConsumerRecord<String, byte[]>> resent = records.stream().filter(record -> record.key().equals(activityKey)).toList();
        assertThat(resent.stream().map(ConsumerRecord::partition).distinct().toList()).as("key = battle_id：同一局进同一分区").hasSize(1);
        assertThat(resent.stream().map(ConsumerRecord::offset).toList()).as("同一局保序").isSorted();

        sink.close();
        sink.publish(event(7));
        assertThat(fallback.lines()).singleElement().satisfies(line -> {
            assertThat(line.reason()).isEqualTo("shutdown_dropped");
            assertThat(line.event()).isEqualTo(event(7));
        });
    }

    @Test
    void 真Kafka_topic分区数与契约不符_启动抛契约异常_报错指向结果topic的代次变量_之后的事件只写兜底() {
        String topic = BattleResultTopics.name(MISMATCH_GENERATION);
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-it-battle-result-admin")) {
            // 模拟 broker 自动建 topic 建出了别的分区数：先按 2 分区建好（幂等，反复运行不变）
            AuditTopicInitializer.ensure(admin, List.of(new TopicSpec(topic, 2, Map.of())), AuditTopicInitializer.Mode.CREATE_AND_VERIFY,
                    (short) 1, Duration.ofSeconds(20), BattleResultTopics.GENERATION_ENV);
            assertThat(admin.partitionCounts(List.of(topic), Duration.ofSeconds(10))).containsEntry(topic, 2);
        }
        KafkaBattleResultSink mismatched = sink(BOOTSTRAP, MISMATCH_GENERATION, Duration.ofSeconds(20));

        assertThatThrownBy(mismatched::start).isInstanceOf(AuditTopicContractException.class)
                .hasMessageContaining(topic).hasMessageContaining("分区数是 2").hasMessageContaining("契约是 3")
                .hasMessageContaining("XM_BATTLE_RESULT_TOPIC_GENERATION");
        assertThat(mismatched.verified()).isFalse();

        mismatched.publish(event(11));
        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        assertThat(fallback.lines()).singleElement().satisfies(line -> {
            assertThat(line.reason()).isEqualTo("not_verified");
            assertThat(line.topic()).isEqualTo(topic);
            assertThat(line.event()).isEqualTo(event(11));
        });
        assertThat(events("sent")).isZero();
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-it-battle-result-admin")) {
            assertThat(admin.partitionCounts(List.of(topic), Duration.ofSeconds(10))).as("不去改已有 topic 的分区数").containsEntry(topic, 2);
        }
    }

    @Test
    void 真Kafka客户端_broker连不上_启动在核对上限内返回不抛_事件写兜底_停机不卡() {
        // 本机 1 号端口没有服务：连接被拒，管理客户端一直重试到核对上限
        Duration verifyTimeout = Duration.ofSeconds(2);
        KafkaBattleResultSink unreachable = sink("127.0.0.1:1", GENERATION, verifyTimeout);

        long startedAt = System.nanoTime();
        assertThatCode(unreachable::start).doesNotThrowAnyException();
        long startMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(startMs).as("启动线程最多等一个核对上限（另加客户端收尾）").isBetween(1_500L, 10_000L);
        assertThat(unreachable.verified()).isFalse();

        unreachable.publish(event(21));
        // 发送线程依次写：兜底行 → xm_battle_result_events → xm_battle_results；等最后写的那个
        await().atMost(WAIT).until(() -> results("plain", "error") == 1);
        assertThat(events("not_verified")).isEqualTo(1);
        assertThat(fallback.lines()).singleElement().satisfies(line -> assertThat(line.event()).isEqualTo(event(21)));

        long closingAt = System.nanoTime();
        unreachable.close();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closingAt)).as("没有生产者要关、发送线程空闲").isLessThan(3_000L);
    }
}
