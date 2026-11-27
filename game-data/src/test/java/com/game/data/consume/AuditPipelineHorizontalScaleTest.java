package com.game.data.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.data.metrics.DataMetrics;
import com.game.data.store.BucketedRetentionPurger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** 覆盖主仓缺口清单第 15 条：审计只许原地加分区、多分区受限并发落库（上限=poolSize-1）、16 桶无符号分桶与双副本分桶互斥清理。 */
class AuditPipelineHorizontalScaleTest {

    @Test
    void policyEnforcesGrowOnlyPartitionsUnsignedShardAndPoolMinusOneConcurrency() {
        AuditTopicScalingPolicy.validatePartitionChange(6, 6);
        AuditTopicScalingPolicy.validatePartitionChange(6, 16);
        assertThatThrownBy(() -> AuditTopicScalingPolicy.validatePartitionChange(16, 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AuditTopicScalingPolicy.maxDbConcurrency(1)).isEqualTo(1);
        assertThat(AuditTopicScalingPolicy.maxDbConcurrency(8)).isEqualTo(7);

        assertThat(AuditTopicScalingPolicy.shardOf(0L)).isEqualTo(0);
        assertThat(AuditTopicScalingPolicy.shardOf(17L)).isEqualTo(1);
        // 无符号 64 位最高位为 1（-1L = 2^64 - 1 = 15 mod 16）
        assertThat(AuditTopicScalingPolicy.shardOf(-1L)).isEqualTo(15);
    }

    @Test
    void consumerLoopFlushesPartitionsInParallelBoundedByPoolMinusOneWithoutDuplicates() throws Exception {
        String topic = "game-transaction-log-g1";
        TopicPartition p0 = new TopicPartition(topic, 0);
        TopicPartition p1 = new TopicPartition(topic, 1);
        TopicPartition p2 = new TopicPartition(topic, 2);

        Map<TopicPartition, Long> committedOffsets = new ConcurrentHashMap<>();
        MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST) {
            @Override
            public synchronized void commitSync(Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets) {
                offsets.forEach((tp, meta) -> committedOffsets.put(tp, meta.offset()));
                super.commitSync(offsets);
            }
        };
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(p0, p1, p2));
            consumer.updateBeginningOffsets(Map.of(p0, 0L, p1, 0L, p2, 0L));
            consumer.addRecord(new ConsumerRecord<>(topic, 0, 0L, "k0", new byte[]{1}));
            consumer.addRecord(new ConsumerRecord<>(topic, 1, 0L, "k1", new byte[]{2}));
            consumer.addRecord(new ConsumerRecord<>(topic, 2, 0L, "k2", new byte[]{3}));
        });

        AtomicInteger activeDb = new AtomicInteger();
        AtomicInteger maxConcurrentDb = new AtomicInteger();
        Set<Integer> written = ConcurrentHashMap.newKeySet();
        CountDownLatch allWritten = new CountDownLatch(3);

        DataMetrics metrics = new DataMetrics(new SimpleMeterRegistry());
        metrics.registerConsumer("txlog");
        int concurrency = AuditTopicScalingPolicy.maxDbConcurrency(3); // 3 - 1 = 2
        assertThat(concurrency).isEqualTo(2);

        ConsumerLoop<Integer> loop = new ConsumerLoop<>(
                "txlog",
                consumer,
                topic,
                rec -> ConsumerLoop.Decoded.ok((int) rec.value()[0]),
                rows -> {
                    int now = activeDb.incrementAndGet();
                    maxConcurrentDb.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(60);
                        int inserted = 0;
                        for (int v : rows) {
                            if (written.add(v)) inserted++;
                            allWritten.countDown();
                        }
                        return inserted;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return 0;
                    } finally {
                        activeDb.decrementAndGet();
                    }
                },
                metrics,
                Duration.ofMillis(20),
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                concurrency);

        Thread worker = new Thread(loop);
        worker.start();
        assertThat(allWritten.await(5, TimeUnit.SECONDS)).isTrue();
        // 给 commitSync 一个简短窗口再停止循环
        Thread.sleep(80);
        loop.stop();
        worker.join(3000);

        assertThat(written).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(maxConcurrentDb.get()).isGreaterThanOrEqualTo(2).isLessThanOrEqualTo(concurrency);
        assertThat(committedOffsets).containsEntry(p0, 1L).containsEntry(p1, 1L).containsEntry(p2, 1L);
    }

    @Test
    void twoReplicasNeverPurgeTheSameShardConcurrently() throws Exception {
        Set<String> heldLocks = ConcurrentHashMap.newKeySet();
        BucketedRetentionPurger.ShardLock memLock = (lockName, body) -> {
            if (!heldLocks.add(lockName)) {
                return false;
            }
            try {
                body.run();
                return true;
            } finally {
                heldLocks.remove(lockName);
            }
        };

        Set<Integer> activeShards = ConcurrentHashMap.newKeySet();
        AtomicInteger overlapViolations = new AtomicInteger();
        Set<Integer> purgedShards = ConcurrentHashMap.newKeySet();

        BucketedRetentionPurger replicaA = new BucketedRetentionPurger(memLock, 0);
        BucketedRetentionPurger replicaB = new BucketedRetentionPurger(memLock, 8);

        BucketedRetentionPurger.ShardBatchDelete deleter = (shard, before, limit) -> {
            if (!activeShards.add(shard)) {
                overlapViolations.incrementAndGet();
            }
            try {
                Thread.sleep(15);
                return purgedShards.add(shard) ? 5 : 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            } finally {
                activeShards.remove(shard);
            }
        };

        try (var pool = Executors.newFixedThreadPool(2)) {
            var fa = pool.submit(() -> replicaA.purgeAllShards("transaction_log", 1000L, 100, () -> true, null, deleter));
            var fb = pool.submit(() -> replicaB.purgeAllShards("transaction_log", 1000L, 100, () -> true, null, deleter));
            long total = fa.get(5, TimeUnit.SECONDS) + fb.get(5, TimeUnit.SECONDS);
            assertThat(overlapViolations.get()).isZero();
            assertThat(purgedShards).hasSize(16);
            assertThat(total).isEqualTo(16L * 5L);
        }
    }
}
