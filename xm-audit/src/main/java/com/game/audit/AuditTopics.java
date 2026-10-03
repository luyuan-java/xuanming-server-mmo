package com.game.audit;

import java.util.List;
import java.util.Map;

/**
 * 审计 topic 的规格（名字带代次后缀、分区数、保留策略）。分区数是生产方与消费方共同的契约：启动时双方都核对，不符拒绝启动。
 *
 * <p>名字即使是第一代也带 {@code -g1}：裸名字一旦被 broker 自动建成默认分区数，分区契约就永久失配（mmorpg audit_topic.h 同理）；
 * 本机 broker 还不能删 topic，换分区数只能升代次。
 */
public final class AuditTopics {

    /** 资产流水 topic 的基名。 */
    public static final String TRANSACTION_LOG_BASE = "xm-transaction-log";
    /** 资产流水的分区数（同 mmorpg transaction_log_topic 的 6 分区）。 */
    public static final int TRANSACTION_LOG_PARTITIONS = 6;
    /** 保留 30 天（逐 topic 显式声明，不继承 broker 默认；大小不设上限，免得消费者积压时丢审计数据）。 */
    static final Map<String, String> RETENTION = Map.of(
            "retention.ms", "2592000000",
            "retention.bytes", "-1",
            "cleanup.policy", "delete");

    private AuditTopics() {
    }

    /** 代次后的 topic 名：{@code base + "-g" + generation}。 */
    public static String name(String base, int generation) {
        if (generation < 1) {
            throw new IllegalArgumentException("topic 代次必须 ≥ 1: " + generation);
        }
        return base + "-g" + generation;
    }

    public static TopicSpec transactionLog(int generation) {
        return new TopicSpec(name(TRANSACTION_LOG_BASE, generation), TRANSACTION_LOG_PARTITIONS, RETENTION);
    }

    /** 当前代次的全部审计 topic（生产方与消费方都核对这一组）。 */
    public static List<TopicSpec> all(int generation) {
        return List.of(transactionLog(generation));
    }
}
