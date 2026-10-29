package com.game.data.consume;

import static com.game.data.consume.AuditProbeFailure.Reason.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;

/**
 * Kafka 3.9 管理 API 的只读适配。不使用 Consumer，因而不会加入消费组、触发重平衡或修改位点。
 * 顺序为 metadata → beginning → committed → end → metadata；先读 beginning 可减少读取间隙里的误报，
 * 仍不是 broker 的事务快照。保留缺口仅证明已提交恢复位点无法由 Kafka 完整重放，未证明在途 SQL 批次已丢。
 */
public final class KafkaGroupOffsetReader implements GroupOffsetReader {
    private final Admin admin;
    private final AtomicBoolean closed = new AtomicBoolean();

    public KafkaGroupOffsetReader(Admin admin) { this.admin = Objects.requireNonNull(admin, "admin"); }

    @Override
    public AuditOffsetSnapshot read(AuditOffsetSnapshot.Target target, AuditProbeBudget budget) {
        if (closed.get()) throw new AuditProbeFailure(INTERRUPTED);
        try {
            TopicDescription first = describe(target, budget);
            List<TopicPartition> partitions = new ArrayList<>();
            for (int i = 0; i < target.partitionCount(); i++) partitions.add(new TopicPartition(target.topic(), i));
            Map<TopicPartition, ListOffsetsResultInfo> beginnings = offsets(partitions, OffsetSpec.earliest(), budget);
            Map<TopicPartition, OffsetAndMetadata> committed = await(admin.listConsumerGroupOffsets(
                    Map.of(target.group(), new ListConsumerGroupOffsetsSpec().topicPartitions(partitions)),
                    new ListConsumerGroupOffsetsOptions().timeoutMs(budget.timeoutMillis()))
                    .partitionsToOffsetAndMetadata(target.group()), budget);
            Map<TopicPartition, ListOffsetsResultInfo> ends = offsets(partitions, OffsetSpec.latest(), budget);
            TopicDescription last = describe(target, budget);
            if (!first.topicId().equals(last.topicId())) throw new AuditProbeFailure(TOPIC_CHANGED);
            budget.remainingNanos();
            // API 以完整 key + null value 表示从未提交；缺 key 是不完整响应，不能冒充新组。
            if (committed == null || !Set.copyOf(partitions).equals(committed.keySet())) {
                throw new AuditProbeFailure(INVALID_OFFSETS);
            }
            List<AuditOffsetSnapshot.Partition> rows = new ArrayList<>();
            long total = 0, max = 0, gaps = 0;
            int uncommitted = 0;
            for (TopicPartition partition : partitions) {
                long beginning = beginnings.get(partition).offset();
                long end = ends.get(partition).offset();
                OffsetAndMetadata metadata = committed.get(partition);
                Long from = metadata == null ? null : metadata.offset();
                if (beginning < 0 || end < beginning || (from != null && (from < 0 || from > end))) {
                    throw new AuditProbeFailure(INVALID_OFFSETS);
                }
                long lag = end - (from == null ? beginning : from);
                Long gap = from == null ? null : Math.max(0, beginning - from);
                total = Math.addExact(total, lag);
                max = Math.max(max, lag);
                if (gap == null) uncommitted++; else gaps = Math.addExact(gaps, gap);
                rows.add(new AuditOffsetSnapshot.Partition(partition.partition(), beginning, from, end, lag, gap));
            }
            return new AuditOffsetSnapshot(target, first.topicId().toString(), rows, total, max, gaps, uncommitted);
        } catch (AuditProbeFailure e) {
            throw e;
        } catch (ArithmeticException e) {
            throw new AuditProbeFailure(OFFSET_OVERFLOW);
        } catch (RuntimeException e) {
            throw new AuditProbeFailure(BROKER);
        }
    }

    private TopicDescription describe(AuditOffsetSnapshot.Target target, AuditProbeBudget budget) {
        Map<String, TopicDescription> descriptions = await(admin.describeTopics(List.of(target.topic()),
                new DescribeTopicsOptions().timeoutMs(budget.timeoutMillis())).allTopicNames(), budget);
        TopicDescription description = descriptions == null ? null : descriptions.get(target.topic());
        if (description == null || !target.topic().equals(description.name()) || description.isInternal()
                || description.topicId() == null || Uuid.ZERO_UUID.equals(description.topicId())
                || description.partitions().size() != target.partitionCount()) {
            throw new AuditProbeFailure(INVALID_METADATA);
        }
        Set<Integer> found = new java.util.HashSet<>();
        for (var partition : description.partitions()) {
            if (partition.partition() < 0 || partition.partition() >= target.partitionCount()
                    || !found.add(partition.partition())) throw new AuditProbeFailure(INVALID_METADATA);
        }
        return description;
    }

    private Map<TopicPartition, ListOffsetsResultInfo> offsets(List<TopicPartition> partitions, OffsetSpec spec,
                                                              AuditProbeBudget budget) {
        Map<TopicPartition, OffsetSpec> query = new HashMap<>();
        partitions.forEach(partition -> query.put(partition, spec));
        Map<TopicPartition, ListOffsetsResultInfo> values = await(admin.listOffsets(query,
                new ListOffsetsOptions().timeoutMs(budget.timeoutMillis())).all(), budget);
        if (values == null || !values.keySet().equals(query.keySet()) || values.values().stream().anyMatch(Objects::isNull)) {
            throw new AuditProbeFailure(INVALID_OFFSETS);
        }
        return values;
    }

    private static <T> T await(KafkaFuture<T> future, AuditProbeBudget budget) {
        try {
            T value = future.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            budget.remainingNanos();
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new AuditProbeFailure(INTERRUPTED);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AuditProbeFailure(BUDGET);
        } catch (ExecutionException e) {
            throw new AuditProbeFailure(BROKER);
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) admin.close(Duration.ZERO);
    }
}
