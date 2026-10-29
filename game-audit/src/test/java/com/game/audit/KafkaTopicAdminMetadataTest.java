package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.Test;

class KafkaTopicAdminMetadataTest {
    @Test void 真实适配器取所有分区不同broker数的最小值而非首分区或ISR数() {
        var api = mock(Admin.class);
        var response = mock(DescribeTopicsResult.class);
        var topic = mock(TopicDescription.class);
        var empty = mock(TopicDescription.class);
        var first = mock(TopicPartitionInfo.class);
        var last = mock(TopicPartitionInfo.class);
        var one = new Node(1, "one", 9092);
        var two = new Node(2, "two", 9092);
        var three = new Node(3, "three", 9092);
        when(first.replicas()).thenReturn(List.of(one, two, three));
        when(last.replicas()).thenReturn(List.of(one, two, two));
        when(topic.partitions()).thenReturn(List.of(first, last));
        when(empty.partitions()).thenReturn(List.of());
        var names = List.of("audit", "empty");
        when(api.describeTopics(names)).thenReturn(response);
        when(response.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of("audit", topic, "empty", empty)));
        assertThat(new KafkaTopicAdmin(api).minimumReplicaCounts(names, Duration.ofSeconds(1)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("audit", 2, "empty", 0));
    }
}
