package com.game.scene.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.audit.AuditBrokerUnavailableException;
import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditTopics;
import com.game.audit.TopicAdmin;
import com.game.audit.TopicSpec;
import com.game.audit.proto.AssetKind;
import com.game.audit.proto.TransactionLogRecord;
import com.game.audit.proto.TransactionReason;
import com.game.common.id.Snowflake;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.id.SceneGuids;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuditPipelineTest {

    private static final int GENERATION = 1;
    private static final String TOPIC = AuditTopics.transactionLog(GENERATION).name();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private MockProducer<String, byte[]> producer =
            new MockProducer<>(false, new StringSerializer(), new ByteArraySerializer());
    /** 生产者工厂抛错（模拟 bootstrap 地址解析不了：KafkaProducer 构造器直接抛）。 */
    private volatile boolean producerFactoryFails;
    private final java.util.concurrent.atomic.AtomicInteger producersMade = new java.util.concurrent.atomic.AtomicInteger();
    private final FakeAdmin admin = new FakeAdmin();
    private final AtomicBoolean leaseValid = new AtomicBoolean(true);
    private CountDownLatch leaseGate;
    private AuditPipeline pipeline;

    /** 假 broker：已有的 topic 与分区数；可设为不可达。 */
    private static final class FakeAdmin implements TopicAdmin {
        final Map<String, Integer> partitions = new HashMap<>();
        boolean unreachable;

        @Override
        public Map<String, Integer> partitionCounts(Collection<String> topics, Duration timeout) {
            if (unreachable) {
                throw new AuditBrokerUnavailableException("连不上", null);
            }
            Map<String, Integer> out = new HashMap<>();
            topics.stream().filter(partitions::containsKey).forEach(t -> out.put(t, partitions.get(t)));
            return out;
        }

        @Override
        public void create(Collection<TopicSpec> specs, short replicationFactor, Duration timeout) {
            specs.forEach(s -> partitions.put(s.name(), s.partitions()));
        }

        @Override
        public Map<String, String> configs(String topic, Collection<String> keys, Duration timeout) {
            return Map.of();
        }

        @Override
        public void alterConfigs(String topic, Map<String, String> configs, Duration timeout) {
        }

        @Override
        public void close() {
        }
    }

    private AuditPipeline pipeline(int queueCapacity) {
        SceneGuids guids = new SceneGuids(new Snowflake(7), () -> {
            CountDownLatch gate = leaseGate;
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return leaseValid.get();
        });
        pipeline = new AuditPipeline(() -> {
            if (producerFactoryFails) {
                throw new org.apache.kafka.common.KafkaException("Failed to construct kafka producer",
                        new org.apache.kafka.common.config.ConfigException("No resolvable bootstrap urls given in bootstrap.servers"));
            }
            producersMade.incrementAndGet();
            return producer;
        }, () -> admin, AuditTopics.all(GENERATION), TOPIC, (short) 1,
                Duration.ofSeconds(1), guids, queueCapacity, new SceneMetrics(meters));
        return pipeline;
    }

    @AfterEach
    void close() {
        if (leaseGate != null) {
            leaseGate.countDown();
        }
        if (pipeline != null) {
            pipeline.close(Duration.ofSeconds(1));
        }
    }

    private double count(String result) {
        return meters.get("xm.scene.audit.records").tag("kind", "transaction").tag("result", result).counter().count();
    }

    private static TransactionLogRecord draft(long fromPlayer, long toPlayer, long delta) {
        return TransactionLogRecord.newBuilder().setTimeMs(1_800_000_000_123L).setReason(TransactionReason.TX_GM_GRANT)
                .setKind(AssetKind.ASSET_CURRENCY).setFromPlayer(fromPlayer).setToPlayer(toPlayer).setCurrencyType(1)
                .setCurrencyDelta(delta).setBalanceBefore(0).setBalanceAfter(delta).setZoneId(1).build();
    }

    @Test
    void 核对通过前不发_走兜底() {
        AuditPipeline p = pipeline(10);

        p.submitTransaction(draft(0, 1001, 500));

        await().atMost(Duration.ofSeconds(5)).until(() -> count("unverified") == 1);
        assertThat(producer.history()).isEmpty();
    }

    @Test
    void 核对通过后发往流水topic_键是玩家号_发号在审计线程上_确认后计acked() throws Exception {
        AuditPipeline p = pipeline(10);
        assertThat(p.verifyNow()).isTrue();
        assertThat(admin.partitions).containsEntry(TOPIC, 6);

        p.submitTransaction(draft(0, -1L, 500));
        await().atMost(Duration.ofSeconds(5)).until(() -> producer.history().size() == 1);
        ProducerRecord<String, byte[]> sent = producer.history().get(0);
        producer.completeNext();

        assertThat(sent.topic()).isEqualTo("xm-transaction-log-g1");
        assertThat(sent.key()).isEqualTo("18446744073709551615");
        TransactionLogRecord record = TransactionLogRecord.parseFrom(sent.value());
        assertThat(record.getTxId()).isNotZero();
        assertThat(Snowflake.workerOf(record.getTxId())).isEqualTo(7);
        assertThat(record.toBuilder().setTxId(0).build()).isEqualTo(draft(0, -1L, 500));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("acked") == 1);
    }

    @Test
    void 投递失败_发号失败_都走兜底并计数() {
        AuditPipeline p = pipeline(10);
        p.verifyNow();

        p.submitTransaction(draft(1001, 0, -5));
        await().atMost(Duration.ofSeconds(5)).until(() -> producer.history().size() == 1);
        producer.errorNext(new RuntimeException("broker 拒绝"));
        leaseValid.set(false);
        p.submitTransaction(draft(1001, 0, -5));

        await().atMost(Duration.ofSeconds(5)).until(() -> count("delivery_failed") == 1 && count("no_id") == 1);
        assertThat(producer.history()).hasSize(1);
    }

    @Test
    void 分区契约不符拒绝_broker不可达返回false() {
        admin.partitions.put(TOPIC, 1);
        AuditPipeline p = pipeline(10);
        assertThatThrownBy(p::verifyNow).isInstanceOf(AuditTopicContractException.class);

        admin.partitions.clear();
        admin.unreachable = true;
        assertThat(p.verifyNow()).isFalse();
        assertThat(p.verified()).isFalse();

        admin.unreachable = false;
        p.requestVerify();
        await().atMost(Duration.ofSeconds(5)).until(p::verified);
    }

    @Test
    void 队列满走兜底_提交从不阻塞不抛() {
        AuditPipeline p = pipeline(1);
        p.verifyNow();
        leaseGate = new CountDownLatch(1);

        p.submitTransaction(draft(0, 1, 1));
        await().atMost(Duration.ofSeconds(5)).until(() -> meters.get("executor.active").tag("name", "scene-audit")
                .gauge().value() == 1);
        p.submitTransaction(draft(0, 2, 1));
        p.submitTransaction(draft(0, 3, 1));

        assertThat(count("queue_full")).isEqualTo(1);
        leaseGate.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> producer.history().size() == 2);
    }

    @Test
    void 停服_预算内没发完的逐条写兜底() {
        AuditPipeline p = pipeline(10);
        p.verifyNow();
        leaseGate = new CountDownLatch(1);
        p.submitTransaction(draft(0, 1, 1));
        await().atMost(Duration.ofSeconds(5)).until(() -> meters.get("executor.active").tag("name", "scene-audit")
                .gauge().value() == 1);
        p.submitTransaction(draft(0, 2, 1));
        p.submitTransaction(draft(0, 3, 1));

        p.close(Duration.ofMillis(200));
        pipeline = null;

        assertThat(count("shutdown_dropped")).isEqualTo(2);
        assertThat(producer.closed()).isTrue();
    }

    @Test
    void 资产审计组装草稿_加币记获得方_扣币记扣减方_原因穷举映射() {
        AuditPipeline p = pipeline(10);
        p.verifyNow();
        KafkaAssetAudit audit = new KafkaAssetAudit(p, new ManualClock(), 3);

        audit.currencyChanged(1001, 1, 500, 0, 500, Reason.GM_GRANT);
        audit.currencyChanged(1001, 0, -500, 600, 100, Reason.ATTRIBUTE_RESET);
        await().atMost(Duration.ofSeconds(5)).until(() -> producer.history().size() == 2);

        List<TransactionLogRecord> records = producer.history().stream().map(r -> {
            try {
                return TransactionLogRecord.parseFrom(r.value());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }).toList();
        assertThat(records.get(0).getToPlayer()).isEqualTo(1001);
        assertThat(records.get(0).getFromPlayer()).isZero();
        assertThat(records.get(0).getReason()).isEqualTo(TransactionReason.TX_GM_GRANT);
        assertThat(records.get(0).getTimeMs()).isEqualTo(ManualClock.EPOCH_MILLIS_START);
        assertThat(records.get(0).getZoneId()).isEqualTo(3);
        assertThat(records.get(1).getFromPlayer()).isEqualTo(1001);
        assertThat(records.get(1).getReason()).isEqualTo(TransactionReason.TX_ATTRIBUTE_RESET);
        assertThat(records.get(1).getCurrencyDelta()).isEqualTo(-500);
        assertThat(producer.history().get(1).key()).isEqualTo("1001");
        for (Reason reason : Reason.values()) {
            assertThat(KafkaAssetAudit.reasonOf(reason)).isNotEqualTo(TransactionReason.TX_REASON_UNSPECIFIED);
        }
    }

    @Test
    void 生产者建不出来_地址解析不了_核对返回false不抛_恢复后照常发() {
        producerFactoryFails = true;
        AuditPipeline p = pipeline(10);

        assertThat(p.verifyNow()).isFalse();
        assertThat(p.verified()).isFalse();

        producerFactoryFails = false;
        p.requestVerify();
        await().atMost(Duration.ofSeconds(5)).until(p::verified);
        p.submitTransaction(draft(0, 1001, 5));
        await().atMost(Duration.ofSeconds(5)).until(() -> producer.history().size() == 1);
    }

    @Test
    void 生产者进入致命状态_这条只计一次_丢弃后下次核对重建() {
        AuditPipeline p = pipeline(10);
        p.verifyNow();
        MockProducer<String, byte[]> broken = producer;
        broken.sendException = new org.apache.kafka.common.KafkaException(
                "Cannot perform send because at least one previous transactional or idempotent request has failed");

        p.submitTransaction(draft(0, 1001, 5));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("send_error") == 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> !p.verified());
        assertThat(broken.closed()).isTrue();
        p.submitTransaction(draft(0, 1001, 6));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("unverified") == 1);

        producer = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        p.requestVerify();
        await().atMost(Duration.ofSeconds(5)).until(p::verified);
        p.submitTransaction(draft(0, 1001, 7));
        await().atMost(Duration.ofSeconds(5)).until(() -> count("acked") == 1);
        assertThat(producersMade.get()).isEqualTo(2);
        assertThat(count("send_error")).isEqualTo(1);
    }
}
