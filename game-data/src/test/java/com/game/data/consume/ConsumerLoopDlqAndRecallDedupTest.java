package com.game.data.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.audit.proto.AssetKind;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.OpsIds;
import com.game.data.ops.OpsJobStore;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.recall.RecallPlanner;
import com.game.data.store.PersistedPlayerMapper;
import com.game.data.store.TransactionLogEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** 覆盖 Batch 4S-2：审计消费组死信 fail-closed、提交失败指数退避、GM 回收同指令去重与四条件闸。 */
class ConsumerLoopDlqAndRecallDedupTest {

    @Test
    void 提交失败指数退避_首错0ms_随后1s翻倍30s封顶() {
        assertThat(ConsumerLoop.commitFailureBackoffMs(0)).isZero();
        assertThat(ConsumerLoop.commitFailureBackoffMs(1)).isZero();
        assertThat(ConsumerLoop.commitFailureBackoffMs(2)).isEqualTo(1_000L);
        assertThat(ConsumerLoop.commitFailureBackoffMs(3)).isEqualTo(2_000L);
        assertThat(ConsumerLoop.commitFailureBackoffMs(4)).isEqualTo(4_000L);
        assertThat(ConsumerLoop.commitFailureBackoffMs(6)).isEqualTo(16_000L);
        assertThat(ConsumerLoop.commitFailureBackoffMs(7)).isEqualTo(30_000L);
        assertThat(ConsumerLoop.commitFailureBackoffMs(20)).isEqualTo(30_000L);
    }

    @Test
    void 坏消息先写死信再提交_死信写入失败时不提交位点() {
        TopicPartition tp = new TopicPartition("game.audit.txlog", 0);
        java.util.concurrent.atomic.AtomicLong committedOffset = new java.util.concurrent.atomic.AtomicLong(-1L);
        MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.NONE) {
            @Override
            public void commitSync(Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets) {
                super.commitSync(offsets);
                if (offsets.containsKey(tp)) {
                    committedOffset.set(offsets.get(tp).offset());
                }
            }
        };
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(tp));
            consumer.updateBeginningOffsets(Map.of(tp, 0L));
            consumer.seek(tp, 0L);
            consumer.addRecord(new ConsumerRecord<>("game.audit.txlog", 0, 0L, "k", new byte[] {1, 2}));
        });
        List<String> dlqTopics = new ArrayList<>();
        AtomicInteger dlqAttempts = new AtomicInteger();
        DataMetrics metrics = new DataMetrics(new SimpleMeterRegistry());
        metrics.registerConsumer("txlog");
        ConsumerLoop<String> loop = new ConsumerLoop<>(
                "txlog", consumer, "game.audit.txlog",
                rec -> ConsumerLoop.Decoded.skip(DataMetrics.Outcome.DECODE_ERROR),
                rows -> 0, metrics, Duration.ofMillis(10), Duration.ofMillis(1), Duration.ofMillis(5),
                1, (d, poll) -> poll.run());
        loop.withDlqSink((dlqTopic, rec, reason) -> {
            if (dlqAttempts.incrementAndGet() == 1) {
                throw new RuntimeException("dlq broker down");
            }
            dlqTopics.add(dlqTopic + ":" + rec.offset() + ":" + reason);
            loop.stop();
        });
        loop.run();

        assertThat(dlqAttempts.get()).isEqualTo(2);
        assertThat(dlqTopics).containsExactly("txlog-dlq:0:DECODE_ERROR");
        assertThat(committedOffset.get()).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void GM回收同指令去重与四条件自动回收闸() {
        TransactionLogQueryService txlog = mock(TransactionLogQueryService.class);
        PersistedPlayerMapper players = mock(PersistedPlayerMapper.class);
        OpsJobStore jobs = mock(OpsJobStore.class);
        OpsIds ids = mock(OpsIds.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        Clock clock = Clock.fixed(Instant.ofEpochMilli(100_000L), ZoneOffset.UTC);

        TransactionLogEntry e1 = new TransactionLogEntry();
        e1.setTxId(101L);
        e1.setToPlayer(5001L);
        e1.setCorrelationId(999L);
        e1.setCurrencyDelta(200L);
        e1.setTimeMs(50_000L);
        TransactionLogEntry e2 = new TransactionLogEntry();
        e2.setTxId(102L);
        e2.setToPlayer(5001L);
        e2.setCorrelationId(999L);
        e2.setCurrencyDelta(200L);
        e2.setTimeMs(50_001L);
        when(txlog.scan(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(e1, e2));
        when(jobs.recalledAmong(any())).thenReturn(Set.of());

        RecallPlanner planner = new RecallPlanner(txlog, players, jobs, ids, tx,
                new com.fasterxml.jackson.databind.ObjectMapper(), clock, 100, "test");
        RecallPlanner.Plan plan = RecallPlanner.plan(List.of(5001L), "currency", 0L, null,
                10_000L, 90_000L, List.of(), "dup-test", 100_000L, Duration.ZERO, Duration.ofHours(24));
        Map<String, Object> result = planner.dryRun(plan, "op");
        Map<String, Object> totals = (Map<String, Object>) result.get("totals");

        assertThat(totals.get("owed")).as("同一 (to_player, correlation_id) 只算一次").isEqualTo("200");
        assertThat(totals.get("deduplicatedCorrelations")).isEqualTo(1);

        assertThat(RecallPlanner.autoRecallEligible(new RecallPlanner.AutoRecallEvidence(true, true, false, true))).isTrue();
        assertThat(RecallPlanner.autoRecallEligible(new RecallPlanner.AutoRecallEvidence(false, true, false, true))).isFalse();
        assertThat(RecallPlanner.autoRecallEligible(new RecallPlanner.AutoRecallEvidence(true, false, false, true))).isFalse();
        assertThat(RecallPlanner.autoRecallEligible(new RecallPlanner.AutoRecallEvidence(true, true, true, true))).isFalse();
        assertThat(RecallPlanner.autoRecallEligible(new RecallPlanner.AutoRecallEvidence(true, true, false, false))).isFalse();
    }
}
