package com.game.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 金样：缺省部署命名空间（{@code game}）下，审计与对局结果 topic 的名字与引入命名空间之前逐字节相同。 */
class KafkaNamesGoldenTest {

    @BeforeAll
    static void 只在缺省命名空间里跑() {
        assertThat(GameNamespace.get())
                .as("金样只在缺省命名空间里成立；不要给 mvn 传 -Dgame.namespace（surefire 会让它覆盖 POM 钉的 game）")
                .isEqualTo("game");
    }

    @Test
    void topic名字与金样相同() {
        assertThat(AuditTopics.TRANSACTION_LOG_BASE).isEqualTo("game-transaction-log");
        assertThat(AuditTopics.PLAYER_SNAPSHOT_BASE).isEqualTo("game-player-snapshot");
        assertThat(AuditTopics.all(1)).extracting(TopicSpec::name).containsExactly("game-transaction-log-g1", "game-player-snapshot-g1");
        assertThat(AuditTopics.all(3)).extracting(TopicSpec::name).containsExactly("game-transaction-log-g3", "game-player-snapshot-g3");
        assertThat(BattleResultTopics.BASE).isEqualTo("game-battle-result");
        assertThat(BattleResultTopics.name(1)).isEqualTo("game-battle-result-g1");
        assertThat(BattleResultTopics.spec(2).name()).isEqualTo("game-battle-result-g2");
    }

    @Test
    void KafkaNames在缺省命名空间下前缀是game横杠() {
        assertThat(KafkaNames.prefix()).isEqualTo("game-");
        assertThat(KafkaNames.of("data-admin")).isEqualTo("game-data-admin");
        assertThat(KafkaNames.clientId("scene-audit-z1-n2")).isEqualTo("game-scene-audit-z1-n2");
        assertThatThrownBy(() -> KafkaNames.of("")).as("后缀非空").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaNames.of("a/b")).as("只许 topic 的合法字符").isInstanceOf(IllegalArgumentException.class);
    }
}
