package com.game.battle.port.kafka;

import com.game.audit.BattleResultTopics;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 对局结果 Kafka 生产方的配置（前缀 {@code xm.battle.result}；match-spec §5.4）。四个键与 xm-match 的 {@code xm.match.kafka.*} 同一口径，
 * 环境变量也共用（{@code XM_KAFKA_BOOTSTRAP_SERVERS}、{@value BattleResultTopics#GENERATION_ENV}）。
 *
 * @param bootstrapServers  Kafka 地址（{@code XM_KAFKA_BOOTSTRAP_SERVERS}，缺省 127.0.0.1:9092）
 * @param topicGeneration   topic 代次（{@code XM_BATTLE_RESULT_TOPIC_GENERATION}，≥ 1，<b>必须与 xm-match 一致</b>）：
 *                          topic 名是 {@code xm-battle-result-g<代次>}；分区数是契约，改分区数只能升代次换新 topic
 * @param replicationFactor 新建 topic 的副本数（只在本进程先于 xm-match 启动、topic 还不存在时用到）
 * @param initTimeout       启动期核对 / 创建 topic 的上限：Kafka 不可达时启动线程最多等这么久，之后照常启动、结果事件只写兜底日志，
 *                          有事件要发时每 30 s 再试一次（每次再试也用这个上限，只占 {@code battle-result-out} 线程）
 */
@ConfigurationProperties("xm.battle.result")
public record BattleResultProperties(
        @DefaultValue("127.0.0.1:9092") String bootstrapServers,
        @DefaultValue("1") int topicGeneration,
        @DefaultValue("1") short replicationFactor,
        @DefaultValue("10s") Duration initTimeout) {

    public BattleResultProperties {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("xm.battle.result.bootstrap-servers 不能为空");
        }
        if (topicGeneration < 1) {
            throw new IllegalArgumentException("xm.battle.result.topic-generation 必须 ≥ 1: " + topicGeneration);
        }
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("xm.battle.result.replication-factor 必须 ≥ 1: " + replicationFactor);
        }
        if (initTimeout == null || initTimeout.isNegative() || initTimeout.isZero()) {
            throw new IllegalArgumentException("xm.battle.result.init-timeout 必须为正: " + initTimeout);
        }
    }

    /** 全部取缺省值（测试用）。 */
    public static BattleResultProperties defaults() {
        return new BattleResultProperties("127.0.0.1:9092", 1, (short) 1, Duration.ofSeconds(10));
    }

    /** 这一代次的 topic 名（{@code xm-battle-result-g<代次>}）。 */
    public String topic() {
        return BattleResultTopics.name(topicGeneration);
    }
}
