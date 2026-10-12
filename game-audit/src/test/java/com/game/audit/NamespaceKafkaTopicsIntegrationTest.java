package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.AuditTopicInitializer.Mode;
import com.game.common.GameNamespace;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真 Kafka 上的命名空间隔离（namespace 执行 + {@code -Dgame.it.kafka}，缺省跳过）：按规格建出 {@code nstest-*-g<代次>}，分区数符合契约；
 * 同代次的缺省命名空间 topic 不会被顺手建出来。本机 broker 不能删 topic，所以用固定的测试代次（幂等创建、反复使用，与切片用的代次 1 不相干）。
 */
@Tag("namespace")
@EnabledIfSystemProperty(named = "game.it.kafka", matches = ".+")
class NamespaceKafkaTopicsIntegrationTest {

    private static final int GENERATION = 9651;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    @Test
    void 建出带命名空间前缀的topic_分区数符合规格_缺省命名空间的同代次topic不出现() {
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(System.getProperty("game.it.kafka"), KafkaNames.clientId("it-admin"))) {
            AuditTopicInitializer.ensure(admin, AuditTopics.all(GENERATION), Mode.OWN, (short) 1, TIMEOUT);
            BattleResultTopics.ensure(admin, GENERATION, Mode.OWN, (short) 1, TIMEOUT);

            assertThat(admin.partitionCounts(List.of("nstest-transaction-log-g" + GENERATION, "nstest-player-snapshot-g" + GENERATION,
                    "nstest-battle-result-g" + GENERATION), TIMEOUT))
                    .containsEntry("nstest-transaction-log-g" + GENERATION, AuditTopics.TRANSACTION_LOG_PARTITIONS)
                    .containsEntry("nstest-player-snapshot-g" + GENERATION, AuditTopics.PLAYER_SNAPSHOT_PARTITIONS)
                    .containsEntry("nstest-battle-result-g" + GENERATION, BattleResultTopics.PARTITIONS);
            assertThat(admin.partitionCounts(List.of("game-transaction-log-g" + GENERATION, "game-battle-result-g" + GENERATION), TIMEOUT))
                    .as("只建本命名空间的 topic").isEmpty();
        }
    }
}
