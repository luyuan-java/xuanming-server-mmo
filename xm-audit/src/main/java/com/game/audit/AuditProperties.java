package com.game.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 资产审计管线的配置（前缀 {@code xm.audit}），生产方（xm-scene）与消费方（xm-data）同一份口径。
 *
 * @param enabled           生产方是否经 Kafka 发流水（关掉时 scene 只写本地审计日志，消费方不受影响）
 * @param bootstrapServers  Kafka 地址（环境变量 {@code XM_KAFKA_BOOTSTRAP_SERVERS}）
 * @param topicGeneration   topic 代次（{@code XM_AUDIT_TOPIC_GENERATION}，≥ 1，生产方与消费方必须一致）。
 *                          分区数改了就升代次换新 topic，绝不原地扩分区（那会让同一玩家的键换分区、打乱顺序）
 * @param replicationFactor 新建 topic 的副本数（只在创建时用）
 * @param initTimeout       启动期核对 / 创建 topic 的上限
 */
@ConfigurationProperties("xm.audit")
public record AuditProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("127.0.0.1:9092") String bootstrapServers,
        @DefaultValue("1") int topicGeneration,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("10s") Duration initTimeout) {

    public AuditProperties {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("xm.audit.bootstrap-servers 不能为空");
        }
        if (topicGeneration < 1) {
            throw new IllegalArgumentException("xm.audit.topic-generation 必须 ≥ 1: " + topicGeneration);
        }
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("xm.audit.replication-factor 必须 ≥ 1: " + replicationFactor);
        }
        if (initTimeout.isNegative() || initTimeout.isZero()) {
            throw new IllegalArgumentException("xm.audit.init-timeout 必须为正: " + initTimeout);
        }
    }
}
