package com.game.audit;

import com.game.common.RunMode;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 资产审计管线的配置（前缀 {@code game.audit}），生产方（game-scene）与消费方（game-data）同一份口径。
 *
 * @param enabled           生产方是否经 Kafka 发流水（关掉时 scene 只写本地审计日志，消费方不受影响）
 * @param bootstrapServers  Kafka 地址（环境变量 {@code GAME_KAFKA_BOOTSTRAP_SERVERS}）
 * @param topicGeneration   topic 代次（{@code GAME_AUDIT_TOPIC_GENERATION}，≥ 1，生产方与消费方必须一致）。
 *                          分区数改了就升代次换新 topic，绝不原地扩分区（那会让同一玩家的键换分区、打乱顺序）
 * @param replicationFactor 新建 topic 的副本数与既有 topic 的最小副本要求；不足需人工迁移
 * @param initTimeout       启动期核对 / 创建 topic 的上限
 * @param minInSyncReplicas 最小同步副本数；默认 1 仅用于 dev/test，prod 至少 2 且副本数至少 3
 */
@ConfigurationProperties("game.audit")
public record AuditProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("127.0.0.1:9092") String bootstrapServers,
        @DefaultValue("1") int topicGeneration,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("10s") Duration initTimeout,
        @DefaultValue("1") int minInSyncReplicas) {

    @ConstructorBinding
    public AuditProperties {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("game.audit.bootstrap-servers 不能为空");
        }
        if (topicGeneration < 1) {
            throw new IllegalArgumentException("game.audit.topic-generation 必须 ≥ 1: " + topicGeneration);
        }
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("game.audit.replication-factor 必须 ≥ 1: " + replicationFactor);
        }
        if (minInSyncReplicas < 1 || minInSyncReplicas > replicationFactor) {
            throw new IllegalArgumentException("game.audit.min-in-sync-replicas 必须在 [1, replication-factor] 内");
        }
        if (initTimeout.isNegative() || initTimeout.isZero()) {
            throw new IllegalArgumentException("game.audit.init-timeout 必须为正: " + initTimeout);
        }
    }

    /** 保留现有显式构造的单 broker 调用；运行模式仍由装配处验证，不能据此绕过生产门禁。 */
    public AuditProperties(boolean enabled, String bootstrapServers, int topicGeneration, short replicationFactor,
                           Duration initTimeout) {
        this(enabled, bootstrapServers, topicGeneration, replicationFactor, initTimeout, 1);
    }

    /** data 消费者始终调用；scene 只有启用 Kafka 审计时调用。运行模式由调用者显式注入。 */
    public void validateFor(RunMode mode) {
        if (Objects.requireNonNull(mode, "mode") == RunMode.PROD
                && (replicationFactor < 3 || minInSyncReplicas < 2)) {
            throw new AuditTopicContractException("生产审计要求 GAME_AUDIT_REPLICATION_FACTOR 至少 3，"
                    + "GAME_AUDIT_MIN_IN_SYNC_REPLICAS 至少 2；单 broker 仅用于显式 dev/test 模式");
        }
    }
}
