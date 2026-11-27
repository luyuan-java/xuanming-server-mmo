package com.game.data.consume;

/**
 * 审计流水链路水平扩展规则（主仓缺口清单第 15 条，{@code docs/design/audit-pipeline-horizontal-scale-20261106.md}）：
 * <ul>
 *   <li>审计表按主键 {@code tx_id} / {@code snapshot_id} 幂等去重、落库不依赖分区内顺序，因此两张审计 topic 只许原地加分区，减分区必须换代；</li>
 *   <li>时间索引加分桶列 {@code shard = unsigned(id) % 16}（0..15），消除 TiDB 单调时间索引尾部写热点；</li>
 *   <li>单实例跨分区并行落库并发上限 = {@code max(1, poolSize - 1)}，为运维与保留期清理留出连接余量。</li>
 * </ul>
 */
public final class AuditTopicScalingPolicy {

    /** 审计表时间索引与分桶过期清理的固定桶数（0..15）。 */
    public static final int SHARD_COUNT = 16;

    private AuditTopicScalingPolicy() {
    }

    /** 计算无符号雪花号对应的分桶号（0..15）。 */
    public static int shardOf(long unsignedId) {
        return (int) Long.remainderUnsigned(unsignedId, SHARD_COUNT);
    }

    /** 校验审计 topic 分区变更：只许原地增分区或不变，缩分区必须换代（切换新 generation topic）。 */
    public static void validatePartitionChange(int previousPartitions, int targetPartitions) {
        if (previousPartitions < 1 || targetPartitions < 1) {
            throw new IllegalArgumentException("审计 topic 分区数必须为正整数");
        }
        if (targetPartitions < previousPartitions) {
            throw new IllegalArgumentException("审计 topic 禁止原地缩减分区(" + previousPartitions + " -> "
                    + targetPartitions + ")，减分区必须换代");
        }
    }

    /** 单实例审计消费并行落库并发上限 = 连接池连接数 - 1（下限 1）。 */
    public static int maxDbConcurrency(int poolSize) {
        return Math.max(1, poolSize - 1);
    }
}
