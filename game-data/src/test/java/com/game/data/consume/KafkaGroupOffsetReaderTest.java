package com.game.data.consume;

import static com.game.data.consume.AuditProbeFailure.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.Uuid;
import org.junit.jupiter.api.Test;

class KafkaGroupOffsetReaderTest {
    private static final String TOPIC = "probe-test-g7";
    private final AuditOffsetSnapshot.Target target = new AuditOffsetSnapshot.Target(
            AuditOffsetSnapshot.ConsumerName.TRANSACTION_LOG, TOPIC, 7, "probe-test-group", 2);
    private final Admin admin = mock(Admin.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Map<TopicPartition, ListOffsetsResultInfo> starts = new HashMap<>();
    private final Map<TopicPartition, ListOffsetsResultInfo> ends = new HashMap<>();
    private final Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
    private final Uuid id = Uuid.randomUuid();
    private TopicDescription description = description(id, 0, 1);
    private int descriptions;
    private boolean changed;
    private final List<Integer> requestTimeouts = new ArrayList<>();

    KafkaGroupOffsetReaderTest() {
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenAnswer(invocation -> {
            var options = invocation.getArgument(1, DescribeTopicsOptions.class);
            requestTimeouts.add(options.timeoutMs());
            var result = mock(DescribeTopicsResult.class);
            TopicDescription answer = changed && descriptions++ > 0 ? description(Uuid.randomUuid(), 0, 1) : description;
            when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of(TOPIC, answer)));
            return result;
        });
        when(admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenAnswer(invocation -> {
                    var options = invocation.getArgument(1, ListConsumerGroupOffsetsOptions.class);
                    requestTimeouts.add(options.timeoutMs());
                    Map<String, ListConsumerGroupOffsetsSpec> specs = invocation.getArgument(0);
                    assertThat(specs.keySet()).containsExactly(target.group());
                    assertThat(specs.get(target.group()).topicPartitions())
                            .containsExactly(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
                    var result = mock(ListConsumerGroupOffsetsResult.class);
                    when(result.partitionsToOffsetAndMetadata(target.group())).thenReturn(KafkaFuture.completedFuture(commits));
                    return result;
                });
        when(admin.listOffsets(anyMap(), any(ListOffsetsOptions.class))).thenAnswer(invocation -> {
            Map<TopicPartition, OffsetSpec> specs = invocation.getArgument(0);
            requestTimeouts.add(invocation.getArgument(1, ListOffsetsOptions.class).timeoutMs());
            assertThat(specs.keySet()).containsExactlyInAnyOrder(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
            var result = mock(ListOffsetsResult.class);
            boolean first = specs.values().iterator().next() instanceof OffsetSpec.EarliestSpec;
            when(result.all()).thenReturn(KafkaFuture.completedFuture(first ? starts : ends));
            nanos.addAndGet(Duration.ofMillis(100).toNanos());
            return result;
        });
        row(0, 0, 10L, 30);
        row(1, 5, 20L, 70);
    }

    private TopicDescription description(Uuid identity, int... partitions) {
        var value = mock(TopicDescription.class);
        when(value.name()).thenReturn(TOPIC);
        when(value.topicId()).thenReturn(identity);
        List<TopicPartitionInfo> all = new ArrayList<>();
        for (int partition : partitions) {
            var part = mock(TopicPartitionInfo.class);
            when(part.partition()).thenReturn(partition);
            all.add(part);
        }
        when(value.partitions()).thenReturn(all);
        return value;
    }

    private ListOffsetsResultInfo offset(long offset) {
        var value = mock(ListOffsetsResultInfo.class);
        when(value.offset()).thenReturn(offset);
        return value;
    }

    private void row(int partition, long start, Long committed, long end) {
        TopicPartition key = new TopicPartition(TOPIC, partition);
        starts.put(key, offset(start));
        ends.put(key, offset(end));
        commits.put(key, committed == null ? null : new OffsetAndMetadata(committed));
    }

    private AuditOffsetSnapshot read() {
        return new KafkaGroupOffsetReader(admin).read(target, new AuditProbeBudget(Duration.ofSeconds(1), nanos::get));
    }

    private void rejects(AuditProbeFailure.Reason reason) {
        assertThatThrownBy(this::read).isInstanceOf(AuditProbeFailure.class)
                .extracting(error -> ((AuditProbeFailure) error).reason()).isEqualTo(reason);
    }

    @Test void 完整组分区总积压和最大分区_不取本地assignment() {
        var sample = read();
        assertThat(sample.lag()).isEqualTo(70);
        assertThat(sample.maxPartitionLag()).isEqualTo(50);
        assertThat(sample.retentionGap()).isZero();
        assertThat(sample.uncommittedPartitions()).isZero();
        assertThat(sample.topicId()).isEqualTo(id.toString());
        assertThat(sample.partitions()).hasSize(2);
        assertThat(requestTimeouts).containsExactly(1000, 1000, 900, 900, 800);
        verify(admin, times(2)).describeTopics(eq(List.of(TOPIC)), any(DescribeTopicsOptions.class));
        verify(admin, times(2)).listOffsets(anyMap(), any(ListOffsetsOptions.class));
        verify(admin).listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class));
        verifyNoMoreInteractions(admin);
    }

    @Test void 已提交位点落在保留起点之前_保留分区原件() {
        row(1, 40, 20L, 70);
        var sample = read();
        assertThat(sample.retentionGap()).isEqualTo(20);
        assertThat(sample.lag()).isEqualTo(70);
        assertThat(sample.partitions().get(1)).isEqualTo(new AuditOffsetSnapshot.Partition(1, 40, 20L, 70, 50, 20L));
    }

    @Test void 无提交历史只计算现存积压_不伪造历史无缺口() {
        row(1, 40, null, 70);
        var sample = read();
        assertThat(sample.uncommittedPartitions()).isEqualTo(1);
        assertThat(sample.lag()).isEqualTo(50);
        assertThat(sample.partitions().get(1).committed()).isNull();
        assertThat(sample.partitions().get(1).retentionGap()).isNull();
    }

    @Test void 超过日志末尾的已提交位点不能夹成零积压() { row(1, 5, 71L, 70); rejects(INVALID_OFFSETS); }
    @Test void 负保留起点拒绝() { row(1, -1, 20L, 70); rejects(INVALID_OFFSETS); }
    @Test void 末尾小于起点拒绝() { row(1, 80, 20L, 70); rejects(INVALID_OFFSETS); }
    @Test void 少一个分区的响应拒绝() { ends.remove(new TopicPartition(TOPIC, 1)); rejects(INVALID_OFFSETS); }
    @Test void 已提交响应缺key不同于显式无历史null() { commits.remove(new TopicPartition(TOPIC, 1)); rejects(INVALID_OFFSETS); }
    @Test void 额外其他topic组位点拒绝() { commits.put(new TopicPartition("other", 0), new OffsetAndMetadata(0)); rejects(INVALID_OFFSETS); }
    @Test void topic分区契约漂移拒绝() { description = description(id, 0, 2); rejects(INVALID_METADATA); }
    @Test void 无topic身份拒绝() { description = description(Uuid.ZERO_UUID, 0, 1); rejects(INVALID_METADATA); }
    @Test void 两次元数据间topic被重建拒绝() { changed = true; rejects(TOPIC_CHANGED); }
    @Test void 总量溢出拒绝_不变负数() { row(0, 0, 0L, Long.MAX_VALUE); row(1, 0, 0L, 1); rejects(OFFSET_OVERFLOW); }

    @Test void 请求后预算到期_不能以成功future绕过总预算() {
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenAnswer(invocation -> {
            var result = mock(DescribeTopicsResult.class);
            when(result.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of(TOPIC, description)));
            nanos.addAndGet(Duration.ofSeconds(2).toNanos());
            return result;
        });
        rejects(BUDGET);
        verify(admin, never()).listOffsets(anyMap(), any(ListOffsetsOptions.class));
    }

    @Test void broker错误只暴露固定原因码() {
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class)))
                .thenThrow(new IllegalStateException("password=不要外泄"));
        assertThatThrownBy(this::read).isInstanceOf(AuditProbeFailure.class).hasMessage("BROKER");
    }

    @Test void 超时future被取消_不得继续读取下一类原件() throws Exception {
        var result = mock(DescribeTopicsResult.class);
        @SuppressWarnings("unchecked") KafkaFuture<Map<String, TopicDescription>> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException("敏感网络地址"));
        when(result.allTopicNames()).thenReturn(future);
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(result);
        rejects(BUDGET);
        verify(future).cancel(true);
        verify(admin, never()).listOffsets(anyMap(), any(ListOffsetsOptions.class));
    }

    @Test void 被中断的future保留中断标记且取消() throws Exception {
        var result = mock(DescribeTopicsResult.class);
        @SuppressWarnings("unchecked") KafkaFuture<Map<String, TopicDescription>> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException());
        when(result.allTopicNames()).thenReturn(future);
        when(admin.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(result);
        try {
            rejects(INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(future).cancel(true);
            verify(admin, never()).listOffsets(anyMap(), any(ListOffsetsOptions.class));
        } finally { Thread.interrupted(); }
    }

    @Test void close立即取消且幂等() {
        var reader = new KafkaGroupOffsetReader(admin);
        reader.close();
        reader.close();
        verify(admin).close(Duration.ZERO);
        assertThatThrownBy(() -> reader.read(target, new AuditProbeBudget(Duration.ofSeconds(1), nanos::get)))
                .isInstanceOf(AuditProbeFailure.class).hasMessage("INTERRUPTED");
    }
}
