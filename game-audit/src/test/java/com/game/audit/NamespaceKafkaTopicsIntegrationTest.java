package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.AuditTopicInitializer.Mode;
import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真 Kafka 上的命名空间隔离（{@code -Dgame.it.kafka}，缺省跳过）：同一个 JVM 里直接构造 {@code nstest} 的名字实例，按规格建出
 * {@code nstest-*-g<代次>}，分区数符合契约；同代次的缺省命名空间 {@code game} 的 topic 不会被顺手建出来。只验证名字层（topic 建立），
 * 不是两套部署同时运行。本机 broker 不能删 topic，所以用固定的测试代次（幂等创建、反复使用，与切片用的代次 1 不相干）。
 */
@EnabledIfSystemProperty(named = "game.it.kafka", matches = ".+")
class NamespaceKafkaTopicsIntegrationTest {

    private static final int GENERATION = 9651;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void 建出带命名空间前缀的topic_分区数符合规格_缺省命名空间的同代次topic不出现() {
        KafkaNames nstest = new KafkaNames(GameNamespace.of("nstest"));
        KafkaNames game = new KafkaNames(GameNamespace.of("game"));
        AuditTopics auditTopics = new AuditTopics(nstest);
        BattleResultTopics battleTopics = new BattleResultTopics(nstest);
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(System.getProperty("game.it.kafka"), nstest.prefix() + "it-admin")) {
            AuditTopicInitializer.ensure(admin, auditTopics.all(GENERATION), Mode.OWN, (short) 1, TIMEOUT);
            battleTopics.ensure(admin, GENERATION, Mode.OWN, (short) 1, TIMEOUT);

            assertThat(admin.partitionCounts(List.of("nstest-transaction-log-g" + GENERATION, "nstest-player-snapshot-g" + GENERATION,
                    "nstest-battle-result-g" + GENERATION), TIMEOUT))
                    .containsEntry("nstest-transaction-log-g" + GENERATION, AuditTopics.TRANSACTION_LOG_PARTITIONS)
                    .containsEntry("nstest-player-snapshot-g" + GENERATION, AuditTopics.PLAYER_SNAPSHOT_PARTITIONS)
                    .containsEntry("nstest-battle-result-g" + GENERATION, BattleResultTopics.PARTITIONS);
            assertThat(admin.partitionCounts(List.of(new AuditTopics(game).transactionLog(GENERATION).name(),
                    new BattleResultTopics(game).name(GENERATION)), TIMEOUT))
                    .as("只建本命名空间的 topic").isEmpty();
        }
    }
}
