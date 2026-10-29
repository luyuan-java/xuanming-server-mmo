package com.game.data.consume;

import java.util.List;
import java.util.Objects;

/** broker 只读原件；offset 是位置差，不能当作精确的消息条数或 SQL 丢失条数。 */
public record AuditOffsetSnapshot(Target target, String topicId, List<Partition> partitions,
                                  long lag, long maxPartitionLag, long retentionGap,
                                  int uncommittedPartitions) {
    public enum ConsumerName {
        TRANSACTION_LOG("transaction_log"), PLAYER_SNAPSHOT("player_snapshot");
        private final String label;
        ConsumerName(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Target(ConsumerName consumer, String topic, int generation, String group, int partitionCount) {
        public Target {
            Objects.requireNonNull(consumer, "consumer");
            if (topic == null || topic.isBlank() || group == null || group.isBlank()
                    || generation < 1 || partitionCount < 1 || partitionCount > 256) {
                throw new IllegalArgumentException("审计探针目标非法");
            }
        }
    }

    /** committed 为 null 表示无提交历史；此时从 beginning 计算现存积压，绝不据此证明历史无缺口。 */
    public record Partition(int partition, long beginning, Long committed, long end, long lag, Long retentionGap) {}

    public AuditOffsetSnapshot {
        Objects.requireNonNull(target, "target");
        if (topicId == null || topicId.isBlank() || partitions == null
                || partitions.size() != target.partitionCount() || lag < 0 || maxPartitionLag < 0
                || retentionGap < 0 || uncommittedPartitions < 0
                || uncommittedPartitions > target.partitionCount()) {
            throw new IllegalArgumentException("审计探针原件非法");
        }
        partitions = List.copyOf(partitions);
    }
}
