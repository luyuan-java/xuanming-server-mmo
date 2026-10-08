package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.audit.AuditBrokerUnavailableException;
import com.game.audit.AuditTopicContractException;
import com.game.audit.TopicAdmin;
import com.game.audit.TopicSpec;
import com.game.match.lifecycle.MatchLifecycle;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.lifecycle.ResultConsumerControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchModes;
import com.game.match.testing.FakeGatherLauncher;
import com.game.proto.contracts.kafka.BattleResultEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.Lifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 评分回流的运行时（match-spec §5.3、§5.4、§9.8 第 9 步）：topic 由 match 以主人身份核对；分区数不符拒启；Kafka 不可达照常启动、后台重试；
 * 消费循环意外退出后换新的消费者重来；开关关闭时什么都不做；它自己不带生命周期，是进程的评分消费启停口，由 {@code MatchLifecycle} 启停。Kafka 用假的管理口与
 * {@code MockConsumer}，真 broker 的用例在 {@code BattleResultTopicIntegrationTest}。
 */
class BattleResultIngestTest {

    private static final int GENERATION = 7;
    private static final String TOPIC = "xm-battle-result-g7";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));
    private final FakeAdmin admin = new FakeAdmin();
    private final List<MockConsumer<String, byte[]>> consumers = new CopyOnWriteArrayList<>();
    private final List<BattleResultEvent> handled = new CopyOnWriteArrayList<>();
    private BattleResultIngest ingest;

    /** 内存里的 topic 管理口：记下建了什么、改了什么配置；可以让接下来的若干次调用报「连不上」。 */
    private static final class FakeAdmin implements TopicAdmin {
        final Map<String, Integer> partitions = new ConcurrentHashMap<>();
        final Map<String, Map<String, String>> configs = new ConcurrentHashMap<>();
        final List<String> created = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> altered = new CopyOnWriteArrayList<>();
        final AtomicInteger sessions = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger unreachable = new AtomicInteger();

        @Override
        public Map<String, Integer> partitionCounts(Collection<String> topics, Duration timeout) {
            if (unreachable.get() > 0 && unreachable.getAndDecrement() > 0) {
                throw new AuditBrokerUnavailableException("连不上 broker", null);
            }
            Map<String, Integer> out = new HashMap<>();
            for (String topic : topics) {
                if (partitions.containsKey(topic)) {
                    out.put(topic, partitions.get(topic));
                }
            }
            return out;
        }

        @Override
        public void create(Collection<TopicSpec> specs, short replicationFactor, Duration timeout) {
            for (TopicSpec spec : specs) {
                created.add(spec.name() + "/" + spec.partitions() + "/rf" + replicationFactor);
                partitions.put(spec.name(), spec.partitions());
                configs.put(spec.name(), new HashMap<>(spec.configs()));
            }
        }

        @Override
        public Map<String, String> configs(String topic, Collection<String> keys, Duration timeout) {
            Map<String, String> out = new HashMap<>();
            Map<String, String> current = configs.getOrDefault(topic, Map.of());
            for (String key : keys) {
                if (current.containsKey(key)) {
                    out.put(key, current.get(key));
                }
            }
            return out;
        }

        @Override
        public void alterConfigs(String topic, Map<String, String> changes, Duration timeout) {
            altered.add(Map.copyOf(changes));
            configs.computeIfAbsent(topic, t -> new HashMap<>()).putAll(changes);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    @AfterEach
    void stopIngest() {
        if (ingest != null) {
            ingest.stop();
        }
    }

    private Supplier<TopicAdmin> adminFactory() {
        return () -> {
            admin.sessions.incrementAndGet();
            return admin;
        };
    }

    private Supplier<Consumer<String, byte[]>> consumerFactory() {
        return () -> {
            MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
            consumers.add(consumer);
            return consumer;
        };
    }

    private BattleResultIngest ingest(boolean enabled, Supplier<Consumer<String, byte[]>> consumerFactory) {
        ingest = new BattleResultIngest(new BattleResultIngest.Settings(enabled, GENERATION, (short) 2, Duration.ofSeconds(5)), adminFactory(),
                consumerFactory, handled::add, metrics, Duration.ofMillis(30), Duration.ofMillis(30), Duration.ofMillis(10));
        return ingest;
    }

    private static byte[] result(long battleId) {
        return BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(MatchModes.ONE_V_ONE).build().toByteArray();
    }

    @Test
    void topic名带代次() {
        assertThat(ingest(true, consumerFactory()).topic()).isEqualTo(TOPIC);
    }

    @Test
    void 开关关闭_不核对topic_不建消费者() {
        BattleResultIngest disabled = ingest(false, consumerFactory());

        disabled.start();

        assertThat(disabled.isRunning()).isFalse();
        assertThat(disabled.consuming()).isFalse();
        assertThat(admin.sessions.get()).isZero();
        assertThat(consumers).isEmpty();
        disabled.stop();
    }

    @Test
    void topic不存在_按规格创建三分区七天保留_然后起消费线程订阅它() {
        BattleResultIngest started = ingest(true, consumerFactory());

        started.start();

        assertThat(admin.created).as("第一次核对在启动线程上同步完成").containsExactly(TOPIC + "/3/rf2");
        assertThat(admin.configs.get(TOPIC)).containsEntry("retention.ms", "604800000").containsEntry("cleanup.policy", "delete")
                .containsEntry("retention.bytes", "-1");
        assertThat(admin.closes.get()).as("管理客户端用完即关").isEqualTo(admin.sessions.get());
        assertThat(started.isRunning()).isTrue();
        await().atMost(WAIT).until(() -> consumers.size() == 1 && consumers.get(0).subscription().contains(TOPIC));
        assertThat(started.consuming()).isTrue();

        started.stop();

        assertThat(started.isRunning()).isFalse();
        assertThat(started.consuming()).isFalse();
        assertThat(consumers.get(0).closed()).isTrue();
        assertThat(consumers).as("停止后不再重建消费者").hasSize(1);
    }

    @Test
    void 已有的topic保留期不对_作为主人校正回规格() {
        admin.partitions.put(TOPIC, 3);
        admin.configs.put(TOPIC, new HashMap<>(Map.of("retention.ms", "86400000", "retention.bytes", "-1", "cleanup.policy", "delete")));

        ingest(true, consumerFactory()).start();

        assertThat(admin.created).isEmpty();
        assertThat(admin.altered).containsExactly(Map.of("retention.ms", "604800000"));
        assertThat(admin.configs.get(TOPIC)).containsEntry("retention.ms", "604800000");
    }

    @Test
    void 分区数与契约不符_拒绝启动_报错指向结果topic的代次变量_不建消费者() {
        admin.partitions.put(TOPIC, 2);
        BattleResultIngest mismatched = ingest(true, consumerFactory());

        assertThatThrownBy(mismatched::start).isInstanceOf(AuditTopicContractException.class)
                .hasMessageContaining(TOPIC).hasMessageContaining("分区数是 2").hasMessageContaining("契约是 3")
                .hasMessageContaining("XM_BATTLE_RESULT_TOPIC_GENERATION");

        assertThat(mismatched.isRunning()).isFalse();
        assertThat(mismatched.consuming()).isFalse();
        assertThat(consumers).isEmpty();
        assertThat(admin.closes.get()).isEqualTo(1);
    }

    @Test
    void Kafka不可达_照常启动不抛_后台重试核对_通过之前不建消费者_通过后才开始消费() {
        admin.unreachable.set(3);
        BattleResultIngest started = ingest(true, consumerFactory());

        started.start();

        assertThat(started.isRunning()).as("评分是软数据：Kafka 不可达不拒启").isTrue();
        assertThat(consumers).as("没核对通过之前不消费").isEmpty();
        assertThat(admin.created).isEmpty();

        await().atMost(WAIT).until(() -> consumers.size() == 1);
        assertThat(admin.sessions.get()).as("启动时一次 + 后台三次").isEqualTo(4);
        assertThat(admin.created).containsExactly(TOPIC + "/3/rf2");
        assertThat(started.consuming()).isTrue();
    }

    @Test
    void 后台重试时发现分区数不符_不建消费者_进程不受影响() throws Exception {
        admin.unreachable.set(1);
        BattleResultIngest started = ingest(true, consumerFactory());
        started.start();
        admin.partitions.put(TOPIC, 6);

        await().atMost(WAIT).until(() -> admin.sessions.get() >= 2);
        Thread.sleep(150);

        assertThat(admin.sessions.get()).as("契约不符之后不再重试").isEqualTo(2);
        assertThat(consumers).isEmpty();
        assertThat(started.consuming()).isFalse();
        assertThat(started.isRunning()).isTrue();
    }

    @Test
    void 还在等重试时停止_立即结束_之后也不会起消费者() throws Exception {
        admin.unreachable.set(Integer.MAX_VALUE / 2);
        BattleResultIngest slowRetry = new BattleResultIngest(new BattleResultIngest.Settings(true, GENERATION, (short) 1, Duration.ofSeconds(5)),
                adminFactory(), consumerFactory(), handled::add, metrics, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMillis(10));
        ingest = slowRetry;
        slowRetry.start();

        long started = System.nanoTime();
        slowRetry.stop();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        admin.unreachable.set(0);
        Thread.sleep(100);

        assertThat(elapsedMs).as("不等满 30 s 的重试间隔").isLessThan(5_000);
        assertThat(consumers).isEmpty();
        assertThat(admin.sessions.get()).isEqualTo(1);
    }

    @Test
    void 收到的结果逐条交给入账() {
        TopicPartition tp = new TopicPartition(TOPIC, 0);
        BattleResultIngest started = ingest(true, () -> {
            MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
            consumer.schedulePollTask(() -> {
                consumer.rebalance(List.of(tp));
                consumer.updateBeginningOffsets(Map.of(tp, 0L));
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0, "9001", result(9001)));
                consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 1, "9002", result(9002)));
            });
            consumers.add(consumer);
            return consumer;
        });

        started.start();

        await().atMost(WAIT).until(() -> handled.size() == 2);
        assertThat(handled).extracting(BattleResultEvent::getBattleId).containsExactly(9001L, 9002L);
        await().atMost(WAIT).untilAsserted(() -> assertThat(consumers.get(0).committed(java.util.Set.of(tp)).get(tp).offset()).isEqualTo(2));
    }

    @Test
    void 消费循环意外退出_换一个新的消费者重来() {
        AtomicInteger built = new AtomicInteger();
        BattleResultIngest started = ingest(true, () -> {
            MockConsumer<String, byte[]> consumer;
            if (built.incrementAndGet() == 1) {
                // 第一个消费者一 poll 就炸（协调者故障之类）
                consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
                    @Override
                    public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
                        throw new KafkaException("协调者故障");
                    }
                };
            } else {
                consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
            }
            consumers.add(consumer);
            return consumer;
        });

        started.start();

        await().atMost(WAIT).until(() -> consumers.size() == 2 && consumers.get(1).subscription().contains(TOPIC));
        assertThat(consumers.get(0).closed()).as("坏掉的那个已关闭").isTrue();
        assertThat(started.consuming()).isTrue();
        assertThat(consumers).as("新的消费者正常工作，不再重建").hasSize(2);
    }

    @Test
    void 建消费者失败_稍后重试() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> failures = new ArrayList<>();
        BattleResultIngest started = ingest(true, () -> {
            if (attempts.incrementAndGet() <= 2) {
                failures.add("第 " + attempts.get() + " 次");
                throw new KafkaException("地址解析不了");
            }
            MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
            consumers.add(consumer);
            return consumer;
        });

        started.start();

        await().atMost(WAIT).until(() -> consumers.size() == 1 && consumers.get(0).subscription().contains(TOPIC));
        assertThat(failures).hasSize(2);
    }

    @Test
    void 重复start与stop是幂等的() {
        BattleResultIngest started = ingest(true, consumerFactory());

        started.start();
        started.start();
        await().atMost(WAIT).until(() -> consumers.size() == 1);
        started.stop();
        started.stop();

        assertThat(admin.sessions.get()).isEqualTo(1);
        assertThat(consumers).hasSize(1);
        assertThat(consumers.get(0).closed()).isTrue();
    }

    @Test
    void 没启动过就stop_什么都不做_不抛() {
        BattleResultIngest never = ingest(true, consumerFactory());

        never.stop();

        assertThat(never.isRunning()).isFalse();
        assertThat(admin.sessions.get()).isZero();
        assertThat(consumers).isEmpty();
    }

    @Test
    void 停止之后可以再启动_换新的消费者() {
        BattleResultIngest restarted = ingest(true, consumerFactory());
        restarted.start();
        await().atMost(WAIT).until(() -> consumers.size() == 1 && consumers.get(0).subscription().contains(TOPIC));
        restarted.stop();

        restarted.start();

        await().atMost(WAIT).until(() -> consumers.size() == 2 && consumers.get(1).subscription().contains(TOPIC));
        assertThat(consumers.get(0).closed()).isTrue();
        assertThat(consumers.get(1).closed()).isFalse();
        assertThat(restarted.consuming()).isTrue();
        assertThat(admin.sessions.get()).as("每次启动各核对一次").isEqualTo(2);
    }

    // ================================================================ 启停口：进程的 ResultConsumerControl，由 MatchLifecycle 启停

    /** 只记次序的凑单启停口。 */
    private static MatcherControl recordingMatcher(List<String> events) {
        return new MatcherControl() {
            @Override
            public void start() {
                events.add("matcher.start");
            }

            @Override
            public void stop() {
                events.add("matcher.stop");
            }
        };
    }

    @Test
    void 它是进程的评分消费启停口_自己不带生命周期_放进容器既不被启动也不被停止() {
        BattleResultIngest managed = ingest(true, consumerFactory());
        assertThat(managed).isInstanceOf(ResultConsumerControl.class);
        assertThat(managed).as("容器不会自己启停它：启动第 9 步与停机都归 MatchLifecycle")
                .isNotInstanceOf(Lifecycle.class).isNotInstanceOf(AutoCloseable.class);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(BattleResultIngest.class, () -> managed);
            context.refresh();

            assertThat(context.getBean(ResultConsumerControl.class)).isSameAs(managed);
            assertThat(managed.isRunning()).as("上下文刷新完：没人调启动第 9 步").isFalse();
            assertThat(admin.sessions.get()).as("没有碰 Kafka").isZero();

            managed.start();
            await().atMost(WAIT).until(() -> consumers.size() == 1 && consumers.get(0).subscription().contains(TOPIC));
        }

        assertThat(managed.isRunning()).as("上下文关闭不停它（没有销毁方法）：留给 @AfterEach 停").isTrue();
        assertThat(consumers.get(0).closed()).isFalse();
    }

    @Test
    void 经MatchLifecycle启停_应用已启动时在凑单之后核对topic并消费_停机时在等完gather之后停掉并关消费者() {
        BattleResultIngest managed = ingest(true, consumerFactory());
        List<String> events = new CopyOnWriteArrayList<>();
        FakeGatherLauncher gathers = new FakeGatherLauncher();
        MatchLifecycle lifecycle = new MatchLifecycle(recordingMatcher(events), managed, gathers, () -> events.add("workers.drain"),
                Duration.ofSeconds(1));

        lifecycle.start();
        assertThat(managed.isRunning()).as("SmartLifecycle.start 只登记，后台件等应用已启动").isFalse();

        lifecycle.startBackground();

        assertThat(events).containsExactly("matcher.start");
        assertThat(managed.isRunning()).isTrue();
        assertThat(admin.created).as("第一次核对在启动线程上同步做完").containsExactly(TOPIC + "/3/rf2");
        await().atMost(WAIT).until(() -> consumers.size() == 1 && consumers.get(0).subscription().contains(TOPIC));

        lifecycle.stop();

        assertThat(events).containsExactly("matcher.start", "matcher.stop", "workers.drain");
        assertThat(managed.isRunning()).isFalse();
        assertThat(managed.consuming()).isFalse();
        assertThat(consumers.get(0).closed()).isTrue();
    }

    @Test
    void 经MatchLifecycle启动时分区数不符_启动步骤抛出等于拒绝启动_随后的停机把已起的凑单停掉() {
        admin.partitions.put(TOPIC, 2);
        BattleResultIngest mismatched = ingest(true, consumerFactory());
        List<String> events = new CopyOnWriteArrayList<>();
        MatchLifecycle lifecycle = new MatchLifecycle(recordingMatcher(events), mismatched, new FakeGatherLauncher(), () -> { },
                Duration.ofSeconds(1));
        lifecycle.start();

        assertThatThrownBy(lifecycle::startBackground).isInstanceOf(AuditTopicContractException.class)
                .hasMessageContaining("XM_BATTLE_RESULT_TOPIC_GENERATION");
        lifecycle.stop(); // Spring Boot 在启动步骤抛出后关闭上下文

        assertThat(events).containsExactly("matcher.start", "matcher.stop");
        assertThat(mismatched.isRunning()).isFalse();
        assertThat(consumers).isEmpty();
    }

    @Test
    void 经MatchLifecycle启停_开关关闭时启动是空操作_停机也不出错() {
        BattleResultIngest disabled = ingest(false, consumerFactory());
        MatchLifecycle lifecycle = new MatchLifecycle(recordingMatcher(new CopyOnWriteArrayList<>()), disabled, new FakeGatherLauncher(),
                () -> { }, Duration.ofSeconds(1));
        lifecycle.start();

        lifecycle.startBackground();
        lifecycle.stop();

        assertThat(disabled.isRunning()).isFalse();
        assertThat(admin.sessions.get()).isZero();
        assertThat(consumers).isEmpty();
    }
}
