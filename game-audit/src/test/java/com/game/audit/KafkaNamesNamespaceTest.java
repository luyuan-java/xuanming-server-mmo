package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 非缺省部署命名空间（namespace 执行，{@code -Dgame.namespace=nstest}）下的 Kafka 名字：topic、消费组缺省与 client.id 都以 {@code nstest-} 开头。 */
@Tag("namespace")
class KafkaNamesNamespaceTest {

    @BeforeAll
    static void 只在namespace执行里跑() {
        assertThat(GameNamespace.get())
                .as("这类用例只在 surefire 的 namespace execution 里跑（-Dgame.namespace=nstest）；IDE 里单跑请加 -Dgame.namespace=nstest")
                .isEqualTo("nstest");
    }

    @Test
    void topic随命名空间_代次与规格不变() {
        assertThat(AuditTopics.TRANSACTION_LOG_BASE).isEqualTo("nstest-transaction-log");
        assertThat(AuditTopics.PLAYER_SNAPSHOT_BASE).isEqualTo("nstest-player-snapshot");
        assertThat(AuditTopics.all(1)).extracting(TopicSpec::name).containsExactly("nstest-transaction-log-g1", "nstest-player-snapshot-g1");
        assertThat(AuditTopics.transactionLog(1).partitions()).isEqualTo(AuditTopics.TRANSACTION_LOG_PARTITIONS);
        assertThat(BattleResultTopics.BASE).isEqualTo("nstest-battle-result");
        assertThat(BattleResultTopics.name(1)).isEqualTo("nstest-battle-result-g1");
        assertThat(BattleResultTopics.spec(1).partitions()).isEqualTo(BattleResultTopics.PARTITIONS);
        assertThat(AuditTopics.name("x", 2)).as("name(base, gen) 仍是纯函数").isEqualTo("x-g2");
    }

    @Test
    void KafkaNames_前缀随命名空间_后缀校验() {
        assertThat(KafkaNames.prefix()).isEqualTo("nstest-");
        assertThat(KafkaNames.of("match-admin")).isEqualTo("nstest-match-admin");
        assertThat(KafkaNames.clientId("battle-result-::1-21200")).as("client.id 不收紧字符集").isEqualTo("nstest-battle-result-::1-21200");
        assertThatThrownBy(() -> KafkaNames.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaNames.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaNames.of("a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaNames.of("a:b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaNames.of("x".repeat(KafkaNames.MAX_NAME_LENGTH))).isInstanceOf(IllegalArgumentException.class);
        assertThat(KafkaNames.of("x".repeat(KafkaNames.MAX_NAME_LENGTH - "nstest-".length()))).hasSize(KafkaNames.MAX_NAME_LENGTH);
        assertThat(KafkaNames.of("A.b_c-1")).isEqualTo("nstest-A.b_c-1");
        assertThatThrownBy(() -> KafkaNames.clientId("")).isInstanceOf(IllegalArgumentException.class);
    }
}
