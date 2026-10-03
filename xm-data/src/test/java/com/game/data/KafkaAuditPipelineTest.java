package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.audit.AuditKeys;
import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.AuditTopicInitializer.Mode;
import com.game.audit.AuditTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.audit.TopicSpec;
import com.game.audit.proto.AssetKind;
import com.game.audit.proto.TransactionLogRecord;
import com.game.audit.proto.TransactionReason;
import com.game.data.consume.ConsumerLoop;
import com.game.data.metrics.DataMetrics;
import com.game.data.txlog.TransactionLogDecoder;
import com.game.data.txlog.TransactionLogRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 连真 Kafka 的集成测试（{@code -Dxm.it.kafka=127.0.0.1:9092}，缺省跳过）。本机 broker 不能删 topic，所以用固定的测试代次
 * （{@value #GENERATION}，幂等创建、反复使用），每次运行用唯一的流水号与消费组，不互相干扰。
 */
@EnabledIfSystemProperty(named = "xm.it.kafka", matches = ".+")
class KafkaAuditPipelineTest {

    private static final int GENERATION = 9001;
    private static final String BOOTSTRAP = System.getProperty("xm.it.kafka");

    @Test
    void topic核对幂等_分区不符拒绝() {
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-it-admin")) {
            AuditTopicInitializer.ensure(admin, AuditTopics.all(GENERATION), Mode.OWN, (short) 1, Duration.ofSeconds(20));
            AuditTopicInitializer.ensure(admin, AuditTopics.all(GENERATION), Mode.OWN, (short) 1, Duration.ofSeconds(20));
            assertThat(admin.partitionCounts(List.of(AuditTopics.transactionLog(GENERATION).name()), Duration.ofSeconds(10)))
                    .containsEntry(AuditTopics.transactionLog(GENERATION).name(), 6);
            assertThat(admin.configs(AuditTopics.transactionLog(GENERATION).name(), List.of("retention.ms"),
                    Duration.ofSeconds(10))).containsEntry("retention.ms", "2592000000");

            TopicSpec twoPartitions = new TopicSpec("xm-it-mismatch-p2", 2, Map.of());
            AuditTopicInitializer.ensure(admin, List.of(twoPartitions), Mode.CREATE_AND_VERIFY, (short) 1,
                    Duration.ofSeconds(20));
            assertThatThrownBy(() -> AuditTopicInitializer.ensure(admin, List.of(new TopicSpec("xm-it-mismatch-p2", 3,
                    Map.of())), Mode.CREATE_AND_VERIFY, (short) 1, Duration.ofSeconds(20)))
                    .isInstanceOf(AuditTopicContractException.class);
        }
    }

    @Test
    void 生产到消费落库_重复与坏记录_提交后换消费者不重放() throws Exception {
        String topic = AuditTopics.transactionLog(GENERATION).name();
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-it-admin")) {
            AuditTopicInitializer.ensure(admin, AuditTopics.all(GENERATION), Mode.OWN, (short) 1, Duration.ofSeconds(20));
        }
        long base = System.currentTimeMillis() * 1000;
        List<Long> txIds = LongStream.range(0, 20).map(i -> base + i).boxed().toList();
        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer =
                     new KafkaProducer<>(pp, new StringSerializer(), new ByteArraySerializer())) {
            for (long txId : txIds) {
                long player = txId % 7 + 1;
                TransactionLogRecord r = TransactionLogRecord.newBuilder().setTxId(txId).setTimeMs(1)
                        .setReason(TransactionReason.TX_GM_GRANT).setKind(AssetKind.ASSET_CURRENCY).setToPlayer(player)
                        .setCurrencyDelta(1).setZoneId(1).build();
                producer.send(new ProducerRecord<>(topic, AuditKeys.transactionKey(0, player), r.toByteArray())).get();
                if (txId == txIds.get(3)) {
                    // 同一条再发一遍（生产方重试 / 回灌）：落库按主键去重
                    producer.send(new ProducerRecord<>(topic, AuditKeys.transactionKey(0, player), r.toByteArray())).get();
                }
            }
            producer.send(new ProducerRecord<>(topic, "1", new byte[] {9, 9, 9})).get();
        }

        String group = "xm-it-" + base;
        Set<Long> stored = ConcurrentHashMap.newKeySet();
        ConsumerLoop.Sink<TransactionLogRow> sink = rows -> {
            int inserted = 0;
            for (TransactionLogRow row : rows) {
                if (stored.add(row.txId())) {
                    inserted++;
                }
            }
            return inserted;
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DataMetrics metrics = new DataMetrics(meters);
        metrics.registerConsumer("it");

        ConsumerLoop<TransactionLogRow> first = new ConsumerLoop<>("it", consumer(group), topic, new TransactionLogDecoder(),
                sink, metrics, Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofSeconds(1));
        Thread thread = new Thread(first);
        thread.start();
        await().atMost(Duration.ofSeconds(60)).until(() -> stored.containsAll(txIds));
        first.stop();
        thread.join(10_000);

        assertThat(meters.get("xm.data.kafka.records").tag("consumer", "it").tag("outcome", "duplicate").counter()
                .count()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("xm.data.kafka.records").tag("consumer", "it").tag("outcome", "decode_error").counter()
                .count()).isGreaterThanOrEqualTo(1);

        // 同组换一个消费者：位点已提交，本次运行的记录不会再来
        Set<Long> replayed = ConcurrentHashMap.newKeySet();
        ConsumerLoop<TransactionLogRow> second = new ConsumerLoop<>("it", consumer(group), topic,
                new TransactionLogDecoder(), rows -> {
                    rows.forEach(r -> replayed.add(r.txId()));
                    return rows.size();
                }, metrics, Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofSeconds(1));
        Thread thread2 = new Thread(second);
        thread2.start();
        Thread.sleep(5_000);
        second.stop();
        thread2.join(10_000);
        assertThat(replayed).doesNotContainAnyElementsOf(txIds);
    }

    private static KafkaConsumer<String, byte[]> consumer(String group) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        return new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
    }
}
