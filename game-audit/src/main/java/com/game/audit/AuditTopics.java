package com.game.audit;

import com.game.common.KafkaNames;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 审计 topic 的规格（名字带代次后缀、分区数、保留策略）。分区数是生产方与消费方共同的契约：启动时双方都核对，不符拒绝启动。
 *
 * <p>名字即使是第一代也带 {@code -g1}：裸名字一旦被 broker 自动建成默认分区数，分区契约就永久失配（mmorpg audit_topic.h 同理）；
 * 本机 broker 还不能删 topic，换分区数只能升代次。
 *
 * <p>基名随部署命名空间：{@code <ns>-transaction-log}、{@code <ns>-player-snapshot}（缺省 {@code game-…}），由注入的 {@link KafkaNames} 给出；
 * 生产方（scene）与消费方（data）各用自己进程的 {@code KafkaNames} bean 构造本类，两端必须是同一个命名空间。
 */
public final class AuditTopics {

    /** 资产流水的分区数（同 mmorpg transaction_log_topic 的 6 分区）。 */
    public static final int TRANSACTION_LOG_PARTITIONS = 6;
    /** 玩家快照的分区数（同 mmorpg player_snapshot_topic 的 3 分区）。 */
    public static final int PLAYER_SNAPSHOT_PARTITIONS = 3;
    /** 保留 30 天（逐 topic 显式声明，不继承 broker 默认；大小不设上限，免得消费者积压时丢审计数据）。 */
    static final Map<String, String> RETENTION = Map.of(
            "retention.ms", "2592000000",
            "retention.bytes", "-1",
            "cleanup.policy", "delete");

    private final String transactionLogBase;
    private final String playerSnapshotBase;

    public AuditTopics(KafkaNames names) {
        Objects.requireNonNull(names, "names");
        this.transactionLogBase = names.transactionLogTopicBase();
        this.playerSnapshotBase = names.playerSnapshotTopicBase();
    }

    /** 代次后的 topic 名：{@code base + "-g" + generation}。 */
    public static String name(String base, int generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("topic 代次必须 ≥ 1: " + generation);
        }
        return base + "-g" + generation;
    }

    /** 资产流水 topic 的基名 {@code <ns>-transaction-log}（缺省 {@code game-transaction-log}）。 */
    public String transactionLogBase() {
        return transactionLogBase;
    }

    /** 玩家快照 topic 的基名 {@code <ns>-player-snapshot}（缺省 {@code game-player-snapshot}）。 */
    public String playerSnapshotBase() {
        return playerSnapshotBase;
    }

    public TopicSpec transactionLog(int generation) {
        return new TopicSpec(name(transactionLogBase, generation), TRANSACTION_LOG_PARTITIONS, RETENTION);
    }

    public TopicSpec playerSnapshot(int generation) {
        return new TopicSpec(name(playerSnapshotBase, generation), PLAYER_SNAPSHOT_PARTITIONS, RETENTION);
    }

    /** 当前代次的全部审计 topic（生产方与消费方都核对这一组）。 */
    public List<TopicSpec> all(int generation) {
        return List.of(transactionLog(generation), playerSnapshot(generation));
    }

    /** 单 broker 保留原规格；多同步副本显式声明持久性，不能依赖 broker 的缺省值。 */
    public List<TopicSpec> all(int generation, int minInSyncReplicas) {
        if (minInSyncReplicas < 1) throw new IllegalArgumentException("minInSyncReplicas 必须至少为 1");
        if (minInSyncReplicas == 1) return all(generation);
        Map<String, String> configs = new java.util.HashMap<>(RETENTION);
        configs.put(AuditTopicDurability.MIN_ISR, Integer.toString(minInSyncReplicas));
        configs.put(AuditTopicDurability.UNCLEAN_ELECTION, "false");
        return all(generation).stream().map(spec -> new TopicSpec(spec.name(), spec.partitions(), configs)).toList();
    }
}
